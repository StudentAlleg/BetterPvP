package me.mykindos.betterpvp.balancesim.engine;

import com.mojang.authlib.GameProfile;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

/**
 * A real, world-resident {@code ServerPlayer} with nobody on the other end of the socket.
 *
 * <p>This is the opposite of {@code HumanNPC}/{@code HumanNMS}, which are deliberately
 * <em>packet-only</em> and never added to the world because they only need to be looked at. A
 * simulation combatant needs the reverse: it has no viewer at all, but it must be a genuine
 * entity in the level so the vanilla attack path, damage pipeline, targeting, effect ticking and
 * cooldowns all engage on it. Anything less and the simulator would be measuring a model again,
 * which is the entire thing this project exists to avoid.
 *
 * <h2>Why it is not registered with {@code PlayerList}</h2>
 * The obvious way to add a player is {@code PlayerList.placeNewPlayer}, but that is the login
 * path: it fires {@code PlayerJoinEvent}, which kicks off the async, database-backed client load
 * and would write a row to {@code clients} for every fake combatant. Instead the entity is added
 * straight to the level with {@link ServerLevel#addNewPlayer}, and its ephemeral
 * {@code Client}/{@code Gamer} is registered directly into the {@code ClientManager} cache by
 * {@link SimClientFactory}. That is enough for the real managers, because
 * {@code clientManager.search().online(player)} resolves out of that cache rather than through
 * {@code Bukkit.getPlayer}.
 *
 * <p>Staying out of the player list is also the isolation win: a fake player is absent from
 * {@code Bukkit.getOnlinePlayers()}, so every per-player loop on the server -- sidebars, action
 * bars, tab list, broadcasts, scoreboard updates -- skips it without needing to be taught about
 * simulation.
 *
 * <h2>...but it <em>is</em> registered for lookup (phase 3)</h2>
 * {@code PlayerList} keeps two separate structures, and only one of them is the thing the
 * isolation above depends on. {@code players} is the list {@code Bukkit.getOnlinePlayers()} is a
 * view of; {@code playersByUUID} is the map {@code Bukkit.getPlayer(UUID)} resolves through
 * ({@code CraftServer.getPlayer} -> {@code PlayerList.getPlayer}). Phase 3 puts a combatant in the
 * <em>map only</em>, via {@link #registerForLookup()}.
 *
 * <p>That split is what makes actives measurable at all. A skill that stays active over time keeps
 * its holders as UUIDs and resolves them back with {@code Bukkit.getPlayer} every tick -- 30 skill
 * files do -- so with the map entry missing, every channel, charge and active-toggle skill dropped
 * its holder on the first tick after activation and measured as if it had never been cast. The same
 * null was silently handing combatants infinite energy: {@code EnergyService.tick()} removes the
 * entry of anyone {@code Bukkit.getPlayer} cannot resolve, and {@code addToMap} re-seeds it at
 * full, so the energy gate that is supposed to bound a rotation never bound anything.
 *
 * <p>The cost is bounded and paid for explicitly, because two things do read the map:
 * {@code Client.isLoaded()} (closed by {@code SimClient}, which overrides it to false) and
 * {@code Player.isOnline()}, which is now true -- and wants to be, since the listeners that gate on
 * it are the ones that drive a live player's skills. Nothing that iterates
 * {@code Bukkit.getOnlinePlayers()} is affected, which is the large majority of the per-player
 * persistence and display surface.
 *
 * <h2>The connection</h2>
 * A {@code ServerPlayer} with a null {@code connection} NPEs the moment anything tries to send it
 * a packet, and the combat pipeline does exactly that (damage indicators, effect feedback). So it
 * gets a real {@link ServerGamePacketListenerImpl} over a {@code Connection} bound to an
 * {@link EmbeddedChannel}, with a {@link PacketSink} in front of it that drops every outbound
 * packet on the floor. Sends are therefore harmless no-ops rather than crashes.
 *
 * <p><b>The sink is not optional.</b> An {@code EmbeddedChannel} on its own does not discard what
 * is written to it -- it <em>records</em> it, in the unbounded {@code ArrayDeque} behind
 * {@code outboundMessages()}, for a test to assert against later. Nothing here ever reads that
 * deque, so before the sink existed every packet the server ever addressed to a fake player was
 * retained for the length of the run. That is a real leak and it killed two 105k-duel sweeps: at
 * 600 residents the per-tick time sync alone is 600 packets, and a 30-minute run heaps up tens of
 * millions of them, so the sweep ended in a GC death spiral -- progress reports stretching from 16
 * seconds to nine minutes apart at constant duels-per-report -- with watchdog dumps caught inside
 * {@code ArrayDeque.add} under {@code Connection.send}. {@link #queuedPacketCount()} exists so the
 * backlog is a number in the progress line rather than something to be inferred from a crash.
 */
public class SimPlayer extends ServerPlayer {

    /**
     * {@code PlayerList.playersByUUID}, the map {@code Bukkit.getPlayer(UUID)} resolves through.
     *
     * <p>Reflective because it is the one of {@code PlayerList}'s two player structures that is
     * private -- {@code players}, the list behind {@code Bukkit.getOnlinePlayers()}, is public and is
     * deliberately <em>not</em> touched. Resolved once at class load so a sweep does not pay a
     * reflective lookup per combatant, and eagerly enough that a mapping change breaks the plugin
     * loading rather than the first duel.
     */
    private static final Field PLAYERS_BY_UUID = playersByUuidField();

    /** The dead-end channel, kept only so {@link #queuedPacketCount()} can report on it. */
    private EmbeddedChannel channel;

    /** Whether this combatant is currently in {@code playersByUUID}, so the removal is idempotent. */
    private boolean registeredForLookup;

    private SimPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, ClientInformation.createDefault());
    }

    /**
     * Creates a fake player, attaches its dead-end connection and adds it to the world at
     * {@code location}.
     *
     * <p>Must be called on the main thread: it mutates level entity state.
     *
     * @param uuid     the combatant's identity, which every per-UUID system keys off
     * @param name     display name, only ever seen in logs
     * @param location where in the sim world to place it, including the facing it starts on
     * @return the spawned fake player
     */
    public static SimPlayer spawn(UUID uuid, String name, Location location) {
        final MinecraftServer server = MinecraftServer.getServer();
        final ServerLevel level = ((CraftWorld) location.getWorld()).getHandle();
        final GameProfile profile = new GameProfile(uuid, name);

        final SimPlayer player = new SimPlayer(server, level, profile);
        player.setPos(location.getX(), location.getY(), location.getZ());
        player.setYRot(location.getYaw());
        player.setXRot(location.getPitch());
        // Head yaw is what the server uses for "is the target in front of me" checks, so it has to
        // agree with the body yaw or the first swing can miss for no visible reason.
        player.setYHeadRot(location.getYaw());

        // A Connection is a Netty channel handler; constructing an EmbeddedChannel around it runs
        // the handler-added/channel-active callbacks that populate Connection.channel, which is
        // what packet sends need in order to be silently swallowed instead of NPEing.
        // Fully qualified: ServerPlayer inherits WaypointTransmitter.Connection into scope, which
        // shadows the network Connection an import would otherwise bind.
        final net.minecraft.network.Connection connection =
                new net.minecraft.network.Connection(PacketFlow.SERVERBOUND);
        // Sink first, so it sits between the Connection and the channel's head: outbound writes
        // travel tail-to-head, so the last handler to see a packet before EmbeddedChannel would
        // record it is the one added first. Reverse the order and every packet is retained.
        player.channel = new EmbeddedChannel(new PacketSink(), connection);
        player.connection = new ServerGamePacketListenerImpl(server, connection, player,
                CommonListenerCookie.createInitial(profile, false));

        // Advancements are pure overhead for a combatant nobody is watching, and they are not free at
        // rest: run 142's profile caught PlayerAdvancements.updateTreeVisibility,
        // AdvancementRequirements.anyMatch and PlayerTrigger.trigger together at about 2% of the server
        // thread, all of it criterion listeners firing during combat. The registration itself happens
        // inside ServerPlayer.<init> and cannot be skipped from here, but the listeners can be dropped
        // the moment the entity exists.
        player.getAdvancements().stopListening();

        level.addNewPlayer(player);
        return player;
    }

    /** The Bukkit view, which is what every BetterPvP manager and listener actually works with. */
    public Player asBukkit() {
        return getBukkitEntity();
    }

    /**
     * Makes {@code Bukkit.getPlayer(uuid)} resolve to this combatant, without making
     * {@code Bukkit.getOnlinePlayers()} contain it.
     *
     * <p>See the class comment for why the two are separable and why only the first is wanted.
     *
     * <p>Called per <em>duel</em> rather than once per resident, and paired with
     * {@link #unregisterForLookup()} at teardown. A resident between duels needs no resolvability, and
     * three things get better for not having it: the phase 2 isolation holds for the whole idle
     * period, {@code ClientManager.unload} can actually drop the ephemeral client (it refuses while
     * {@code Bukkit.getPlayer} resolves), and {@code EnergyService.tick} discards the energy entry so
     * the next duel opens on a full bar instead of inheriting the last one's remainder.
     */
    public void registerForLookup() {
        if (registeredForLookup) {
            return;
        }
        playersByUuid().put(getUUID(), this);
        registeredForLookup = true;
    }

    /**
     * Drops the lookup entry.
     *
     * <p>Not merely tidy. {@code EnergyService.tick}, {@code SkillListener.processActiveToggleSkills}
     * and every skill that resolves its holders through {@code Bukkit.getPlayer} treat a resolvable
     * UUID as a live player to keep working on, and they run on a global sweep rather than per duel.
     * A combatant left in the map between duels is therefore an entry those loops keep visiting, on a
     * player that is not fighting -- and for a discarded resident, on an entity no longer in any level.
     */
    public void unregisterForLookup() {
        if (!registeredForLookup) {
            return;
        }
        playersByUuid().remove(getUUID(), this);
        registeredForLookup = false;
    }

    /**
     * Unsets the death on this entity, in place.
     *
     * <p>{@link ResidentRecycleStrategy#REVIVE}. Known not to be sufficient -- see that class for the
     * two runs that measured it -- and kept as the control the other strategies are graded against.
     * Everything vanilla sets on death that anyone here could name is unset, including the pose, whose
     * {@code Player} dimensions are a fixed 0.2x0.2 box rather than a standing hitbox. It is still not
     * enough, which is the finding rather than an oversight.
     *
     * @return true if the combatant had died and the death was unset
     */
    public boolean reviveIfDead() {
        if (isRemoved()) {
            return false;
        }
        if (!isDeadOrDying() && !dead && getPose() != Pose.DYING) {
            return false;
        }
        dead = false;
        deathTime = 0;
        setHealth(getMaxHealth());
        // refreshDimensions explicitly rather than relying on setPose to reach it through the
        // synced-data callback: this entity's data watcher feeds a connection that is a dead end.
        setPose(Pose.STANDING);
        refreshDimensions();
        getCombatTracker().recheckStatus();
        setLastHurtByMob(null);
        hurtTime = 0;
        hurtDuration = 0;
        invulnerableTime = 0;
        setAbsorptionAmount(0f);
        setRemainingFireTicks(0);
        setTicksFrozen(0);
        return true;
    }

    /**
     * Takes this entity out of the level and puts it straight back in, keeping the instance.
     *
     * <p>{@link ResidentRecycleStrategy#RECYCLE}. The interesting strategy: it differs from
     * {@link #reviveIfDead()} only in the level and entity-tracking registration, and from a respawn
     * only in that the {@code ServerPlayer} instance survives. So it separates "the residue is
     * registration state" from "the residue is on the object", and if it is the former it is nearly
     * free -- {@code ServerPlayer.<init>} is 8.84% of the server thread and carries the advancement
     * listener registration and the stats-file lookup with it.
     *
     * <p>The death is unset before the re-add rather than after, so the entity is never added to a
     * level in a dying state.
     */
    public void recycle(Location at) {
        unregisterForLookup();
        remove(Entity.RemovalReason.DISCARDED);
        // Clears the removal reason, without which the entity cannot be added to a level again.
        unsetRemoved();

        dead = false;
        deathTime = 0;
        setPose(Pose.STANDING);
        refreshDimensions();
        getCombatTracker().recheckStatus();
        setLastHurtByMob(null);
        hurtTime = 0;
        hurtDuration = 0;
        invulnerableTime = 0;
        setAbsorptionAmount(0f);
        setRemainingFireTicks(0);
        setTicksFrozen(0);

        setPos(at.getX(), at.getY(), at.getZ());
        setYRot(at.getYaw());
        setXRot(at.getPitch());
        setYHeadRot(at.getYaw());
        ((CraftWorld) at.getWorld()).getHandle().addNewPlayer(this);
        // After the re-add: max health depends on the attribute modifiers, and a level add is entitled
        // to touch them.
        setHealth(getMaxHealth());
    }

    /**
     * Whether this combatant is in a state that could take part in a duel.
     *
     * <p>What {@link SimCombatantPool} tests a resident against before handing its slot out again, and
     * since phase 3 stopped intercepting lethal blows the ordinary way to fail it is to have lost the
     * last duel. A combatant that fails is discarded and respawned rather than repaired -- see that
     * class for the two runs that established that reviving a dead {@code ServerPlayer} in place does
     * not work, however much of the post-death state is unset.
     *
     * <p><b>Necessary, not sufficient.</b> Run 141's defenders passed every check here and still could
     * not be hurt: something a death leaves behind is not visible in the entity's own state at all.
     * So this is deliberately about the entity only -- whether the managers hold stale per-UUID state
     * is {@code SimStatePurge}'s question, and whether the combatant can actually be <em>hurt</em> is
     * only answerable by hurting it, which is what the barren duel tripwire in
     * {@code DuelOrchestrator} does one duel later.
     */
    public boolean isFightable() {
        return !isRemoved()
                && !dead
                && !isDeadOrDying()
                && getHealth() > 0
                && getPose() != Pose.DYING;
    }

    /**
     * Every piece of state that can make {@code ServerPlayer.attack} decline to land a hit, as one line.
     *
     * <p>Exists because {@link #isFightable()} is not sufficient and never was: a poisoned combatant
     * passes it and still cannot be hurt, so runs 140, 141 and 144 could each only report *that* the
     * duel measured nothing. The engine attacks by direct reference -- {@code handle.attack(opponent)},
     * no entity lookup -- so the refusal is somewhere between {@code isAttackable} and the damage
     * actually applying, and everything on that path that is cheap to read is read here.
     *
     * <p>{@code inLevelLookup} is the one that is not about the entity's own state: it asks the level
     * whether it still knows about this entity by id. A {@code RECYCLE}d resident that answers
     * {@code false} there was never really re-added, whatever {@code addNewPlayer} appeared to do, and
     * that is a different bug from anything the invulnerability flags would show.
     */
    public String describeCombatState() {
        return "removed=" + isRemoved()
                + " dead=" + dead
                + " deadOrDying=" + isDeadOrDying()
                + " alive=" + isAlive()
                + " health=" + getHealth() + "/" + getMaxHealth()
                + " pose=" + getPose()
                + " attackable=" + isAttackable()
                + " invulnerable=" + isInvulnerable()
                + " abilitiesInvulnerable=" + getAbilities().invulnerable
                + " invulnerableTime=" + invulnerableTime
                + " hurtTime=" + hurtTime
                + " spectator=" + isSpectator()
                + " deathTime=" + deathTime
                + " inLevelLookup=" + (level().getEntity(getId()) != null)
                + " bukkitValid=" + asBukkit().isValid()
                + " bukkitOnline=" + asBukkit().isOnline()
                + " attackDamage=" + getAttributeValue(Attributes.ATTACK_DAMAGE);
    }

    /**
     * Tries to hurt this combatant two different ways and reports what each one did to its health.
     *
     * <p>The instrument for the question run 146 left open. Every field in
     * {@link #describeCombatState()} came back healthy on a combatant that had just absorbed 600
     * swings without taking a point of damage, so the refusal is not in the entity's own state and
     * the next thing worth knowing is how far down the pipeline a hit gets. Three outcomes, three
     * different bugs:
     *
     * <ul>
     *   <li>Both land -- the target is hurtable and the fault is on the attacker's side of
     *       {@code ServerPlayer.attack}, before {@code hurtServer} is ever reached.</li>
     *   <li>Neither lands -- something in the damage pipeline refuses this entity outright,
     *       independently of who is hitting it.</li>
     *   <li>Only the sourceless one lands -- the refusal is about the pairing rather than the
     *       target, which points at the attacker/defender relationship the managers hold.</li>
     * </ul>
     *
     * <p>Invulnerability frames are cleared between the two attempts, because the first hit sets them
     * and would otherwise guarantee the second reads as refused on a perfectly healthy combatant.
     *
     * <p>Mutates the combatant, deliberately. It is only ever called on a slot the barren branch is
     * about to replace anyway, and only on the first barren duel of a run, so it cannot affect a
     * measurement -- the duel that would have been measured here already failed.
     */
    public String probeDamage(SimPlayer source) {
        final double start = getHealth();
        String sourced;
        try {
            asBukkit().damage(1.0D, source.asBukkit());
            sourced = String.valueOf(start - getHealth());
        } catch (Throwable t) {
            sourced = "threw " + t;
        }

        final double mid = getHealth();
        invulnerableTime = 0;
        hurtTime = 0;

        String sourceless;
        try {
            asBukkit().damage(1.0D);
            sourceless = String.valueOf(mid - getHealth());
        } catch (Throwable t) {
            sourceless = "threw " + t;
        }

        // Left as it was found. The poisoned subject is about to be despawned so it makes no
        // difference there, but the control is a fresh resident going straight back into service, and
        // a combatant that starts its next duel two hearts down would quietly bias that measurement.
        setHealth(getMaxHealth());
        invulnerableTime = 0;
        hurtTime = 0;

        return "healthLostToSourcedHit=" + sourced + " healthLostToSourcelessHit=" + sourceless;
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<UUID, ServerPlayer> playersByUuid() {
        try {
            return (java.util.Map<UUID, ServerPlayer>) PLAYERS_BY_UUID.get(MinecraftServer.getServer().getPlayerList());
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("PlayerList.playersByUUID became inaccessible", e);
        }
    }

    private static Field playersByUuidField() {
        try {
            final Field field = net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID");
            field.setAccessible(true);
            return field;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("PlayerList.playersByUUID is gone; simulation combatants"
                    + " cannot be made resolvable by Bukkit.getPlayer, which every active skill needs", e);
        }
    }

    /**
     * How many outbound packets the dead-end channel is holding.
     *
     * <p>Zero, always, while {@link PacketSink} is in the pipeline -- which is precisely why it is
     * worth reporting. This is the number that says the sink is doing its job, and the one that
     * grew without bound when there was no sink. A sweep whose progress line shows this rising is
     * leaking packets again, and knows it inside one progress interval rather than at the OOM.
     */
    public int queuedPacketCount() {
        return channel == null ? 0 : channel.outboundMessages().size();
    }

    /**
     * Drops every outbound packet before the channel can retain it.
     *
     * <p>{@code EmbeddedChannel} is a testing transport: its whole purpose is to keep what was
     * written so a test can assert on it, so writing to one without a sink in front is a retention
     * bug dressed up as a no-op. Releasing the message keeps any pooled buffer accounting honest,
     * and {@code trySuccess} rather than {@code setSuccess} because a void promise -- which the
     * server uses for fire-and-forget sends -- throws on the latter.
     */
    private static final class PacketSink extends ChannelOutboundHandlerAdapter {

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ReferenceCountUtil.release(msg);
            promise.trySuccess();
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            // Nothing was written on, so there is nothing to push; overridden so the flush does not
            // travel to the head and exercise the channel's outbound bookkeeping at all.
        }
    }

    /**
     * Returns a resident combatant to a fightable state and puts it on {@code at}.
     *
     * <p>Called by {@link SimCombatantPool} in place of a spawn when a slot is recycled. It resets
     * only what belongs to the entity; the per-UUID state Core's managers hold is
     * {@link SimStatePurge}'s job, and the per-{@code Player} state champions' passives hold is
     * what the pool's quarantine window waits out.
     *
     * <p>Position is set rather than teleported. {@code at} is a couple of blocks from where the
     * combatant already stands, inside the same chunk, so writing the coordinates keeps Moonrise's
     * area maps untouched -- and avoiding their update is the entire reason residents are pinned to
     * one arena. A {@code teleport} would run the full move path and give back the cost the pool
     * exists to save.
     *
     * <p>The inventory is emptied rather than overwritten because a build's kit is not the same
     * shape every duel: a ranger leaves a bow and arrows behind that a brute never sets, and an
     * armour set with fewer pieces than the last one would inherit the difference. Equipping over
     * the top would measure the union of two loadouts.
     */
    public void prepareForDuel(Location at) {
        setPos(at.getX(), at.getY(), at.getZ());
        setYRot(at.getYaw());
        setXRot(at.getPitch());
        setYHeadRot(at.getYaw());
        setDeltaMovement(0, 0, 0);
        resetAttackStrengthTicker();

        final Player bukkit = asBukkit();
        bukkit.getInventory().clear();
        bukkit.setFireTicks(0);
        bukkit.setFallDistance(0f);
        bukkit.setVelocity(new Vector());
        // Vanilla potion effects, as distinct from Core's Effects: skills apply both, and only the
        // latter is reachable through SimStatePurge.
        for (PotionEffect effect : List.copyOf(bukkit.getActivePotionEffects())) {
            bukkit.removePotionEffect(effect.getType());
        }
        // Otherwise the first swing of the new duel can be swallowed by invulnerability left over
        // from the last hit of the previous one, which would show up as an inflated time-to-kill on
        // whichever matchup happened to inherit the slot.
        bukkit.setNoDamageTicks(0);
        bukkit.setLastDamage(0);
    }

    /**
     * Removes the fake player from the world.
     *
     * <p>{@code DISCARDED} rather than {@code KILLED}: a kill would run the death path and emit a
     * {@code PlayerDeathEvent}, which is a persistence trigger. Teardown must be silent.
     *
     * <p>The lookup entry goes first, so no global sweep can resolve a UUID to an entity that is
     * already out of the level.
     */
    public void despawn() {
        unregisterForLookup();
        remove(Entity.RemovalReason.DISCARDED);
    }
}
