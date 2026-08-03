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

    /**
     * How long vanilla waits for a client to report its world loaded before assuming it has.
     *
     * <p>{@code ServerGamePacketListenerImpl.restartClientLoadTimerAfterRespawn} sets exactly this,
     * and {@code tickClientLoadTimeout} spends one per tick, so this many calls take a combatant from
     * "just respawned" to "loaded" without waiting three seconds of duel for it.
     */
    private static final int CLIENT_LOAD_TIMEOUT_TICKS = 60;

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

        // A brand-new connection starts its client-load timer at 60, and until it runs out vanilla
        // treats the player as still on the loading screen and refuses all damage. Left alone, that is
        // the first three seconds of every duel a freshly spawned combatant takes part in -- silently
        // added to the time-to-kill of every measurement the sweep has ever produced.
        player.ensureClientLoaded();

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
        // Before the entity-level unsetting below, because it is the one that actually matters: every
        // field this method clears was already clear on run 141's poisoned defenders, and they still
        // could not be hurt. The death set waitingForRespawn on the connection, and that is what made
        // them invulnerable.
        ensureClientLoaded();
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
        // The whole reason recycling did not work. The death that made this resident need recycling
        // also set waitingForRespawn on its connection, and nothing short of a real respawn clears
        // it -- so without this the re-added entity is invulnerable to everything, forever.
        ensureClientLoaded();
        ((CraftWorld) at.getWorld()).getHandle().addNewPlayer(this);
        // After the re-add: max health depends on the attribute modifiers, and a level add is entitled
        // to touch them.
        setHealth(getMaxHealth());
    }

    /**
     * Tells the server this combatant's client has finished loading, which is the thing that makes it
     * possible to hurt at all.
     *
     * <p><b>This is the bug runs 140 to 150 were chasing.</b> {@code ServerPlayer.isInvulnerableTo}
     * -- vanilla's first gate, ahead of every Bukkit event and therefore ahead of the entire
     * BetterPvP pipeline -- returns true whenever {@code connection.hasClientLoaded()} is false, and
     * that reads {@code !waitingForRespawn && clientLoadedTimeoutTimer <= 0}. Two separate things set
     * it against a simulation combatant:
     *
     * <ul>
     *   <li><b>Death.</b> {@code ServerPlayer.die} calls {@code markClientUnloadedAfterDeath()},
     *       which raises {@code waitingForRespawn} and leaves it raised. Vanilla clears it in
     *       {@code restartClientLoadTimerAfterRespawn}, reached only through the real respawn path --
     *       so a combatant revived or recycled in place is invulnerable <em>forever</em>, and every
     *       swing at it is discarded before anything the sim can observe. That is precisely the
     *       shape of the evidence: {@code REVIVE} and {@code RECYCLE} produce barren duels while both
     *       respawn strategies work, the poisoned defender's own state reads perfectly healthy
     *       because the flag is on the connection rather than the entity, and the attacker -- which
     *       under {@code ONE_WAY} never dies -- takes damage in the same duel.</li>
     *   <li><b>Being new.</b> The timer starts at {@link #CLIENT_LOAD_TIMEOUT_TICKS} and is spent one
     *       per tick from {@code ServerPlayer.tick}, so a freshly constructed combatant is
     *       invulnerable for its first three seconds. Under {@code RESPAWN_NEW} the defender is fresh
     *       every duel, which means every measurement taken so far has up to 60 ticks of
     *       invulnerability folded into its time-to-kill.</li>
     * </ul>
     *
     * <p>Both are cleared the same way, and through public API rather than by reflecting at the two
     * private fields: restart the timer, which is what clears {@code waitingForRespawn}, then spend it
     * in one go instead of waiting out three seconds of the duel it is supposed to be measuring.
     * Spending it is what fires {@code PlayerClientLoadedWorldEvent}, once, exactly as a real client
     * finishing its load would -- which is the correct thing for a combatant that is about to be
     * treated as loaded.
     *
     * @return whether anything needed clearing, so a caller can report on it
     */
    public boolean ensureClientLoaded() {
        if (connection.hasClientLoaded()) {
            return false;
        }
        connection.restartClientLoadTimerAfterRespawn();
        for (int i = 0; i < CLIENT_LOAD_TIMEOUT_TICKS; i++) {
            connection.tickClientLoadTimeout();
        }
        return true;
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
                // Last because it is the one that turned out to matter, and it is not on the entity at
                // all: false here means vanilla refuses every hit before Bukkit hears about it.
                + " clientLoaded=" + connection.hasClientLoaded()
                + " attackDamage=" + getAttributeValue(Attributes.ATTACK_DAMAGE);
    }

    // ------------------------------------------------------------------------------------------
    // Pipeline telemetry. Written by SimDamageTelemetry, read by whoever is trying to work out why a
    // duel measured nothing. Kept on the entity rather than in a map because the whole point is
    // per-combatant attribution, and a duel that swung 600 times needs to be able to say how many of
    // those 600 reached each stage -- a run-wide histogram would average its own answer away.
    // ------------------------------------------------------------------------------------------

    // ------------------------------------------------------------------------------------------
    // Residency history. Unlike the pipeline counters below, these are NOT reset per duel: they
    // describe the entity's whole career, which is exactly the question. A resident fights many
    // duels and is revived after each death, and the catalog emits skill-less baselines before any
    // skill build -- so a baseline systematically fights younger residents than the builds whose
    // deltas are measured against it. That is a confound between the sweep's ordering and its
    // results, and nothing on a sim_result row can distinguish it from a real skill effect.
    // ------------------------------------------------------------------------------------------

    /** Duels this entity has been set up for, including the one in progress. */
    public int duelsFought;
    /** Times the pool has had to bring this entity back after it stopped being fightable. */
    public int revives;
    /** Whether this entity has ever lost a duel. A resident that has died is not a fresh one. */
    public boolean everDied;

    /** Swings this combatant issued at somebody, counted at the call rather than at the landing. */
    public int pipeSwingsMade;
    /** Swings issued at this combatant. Under ONE_WAY the defender's copy is the interesting one. */
    public int pipeSwingsTaken;
    /** {@code EntityDamageEvent}s raised with this combatant as the damagee. */
    public int pipeVanilla;
    /** Of those, how many arrived already cancelled by something ahead of BetterPvP. */
    public int pipeVanillaPreCancelled;
    /** {@code EntityCanHurtEntityEvent}s asked about this combatant, and how many said no. */
    public int pipeCanHurt;
    public int pipeCanHurtDenied;
    /** BetterPvP {@code DamageEvent}s for this combatant, and how many were cancelled. */
    public int pipeDamageEvent;
    public int pipeDamageCancelled;
    /** The last cancellation's stated reason, which is the only free-text clue in the chain. */
    public String pipeLastCancelReason;
    /** Total post-modifier damage seen on uncancelled events, to catch a hit modified to nothing. */
    public double pipeDamageAllowed;

    /**
     * Zeroes the counters so they describe one duel rather than the slot's whole career.
     *
     * <p>Called at duel setup for both sides. A resident survives many duels, and counters that
     * accumulated across all of them would make the one number worth having -- "this defender was
     * swung at 600 times and saw zero damage events" -- unreadable.
     */
    public void resetPipelineCounters() {
        pipeSwingsMade = 0;
        pipeSwingsTaken = 0;
        pipeVanilla = 0;
        pipeVanillaPreCancelled = 0;
        pipeCanHurt = 0;
        pipeCanHurtDenied = 0;
        pipeDamageEvent = 0;
        pipeDamageCancelled = 0;
        pipeLastCancelReason = null;
        pipeDamageAllowed = 0.0D;
    }

    /**
     * How far this duel's swings against this combatant got, stage by stage, as one line.
     *
     * <p>Read as a funnel. Swings taken is the denominator; each subsequent count is what survived
     * the previous stage. Where it collapses to zero is where the bug is, and no other reading of a
     * barren duel says that -- the recorder only ever sees hits that landed, so everything upstream of
     * the landing is invisible to it by construction.
     */
    public String describePipeline() {
        return "swingsMade=" + pipeSwingsMade
                + " swingsTaken=" + pipeSwingsTaken
                + " vanillaDamageEvents=" + pipeVanilla
                + " (preCancelled=" + pipeVanillaPreCancelled + ")"
                + " canHurtAsked=" + pipeCanHurt
                + " canHurtDenied=" + pipeCanHurtDenied
                + " damageEvents=" + pipeDamageEvent
                + " damageCancelled=" + pipeDamageCancelled
                + " lastCancelReason=" + pipeLastCancelReason
                + " damageAllowed=" + pipeDamageAllowed;
    }

    /**
     * Hurts this combatant and reports how far down the damage pipeline the hit actually got.
     *
     * <p>The instrument run 148 forced. The previous version measured health lost inside the
     * {@code damage()} call and read {@code 0.0} on the poisoned combatant -- but also {@code 0.0} on
     * a freshly spawned control that had never died, so the number distinguished nothing. That is a
     * property of the pipeline rather than of either combatant: {@code DamageEventProcessor} cancels
     * every vanilla {@code EntityDamageEvent} unconditionally and reapplies the damage through
     * BetterPvP's own path, where it may be reduced away, delayed, or declined by any of seven gates
     * on the way to the finalizer. A health delta cannot tell those apart, so this watches the gates
     * instead of the outcome.
     *
     * <p>Each of the three events below is observed at both ends of the chain, so the report says
     * which gate the hit died at rather than only that it died:
     *
     * <ul>
     *   <li>No {@code EntityDamageEvent} at all -- nothing above BetterPvP was reached, and the
     *       refusal is in vanilla {@code hurtServer} or in the entity's level registration.</li>
     *   <li>Vanilla fired but no {@code EntityCanHurtEntityEvent} -- rejected by the entry checks in
     *       {@code processDamageEvent} or by {@code DamageDelayManager.processPreEventDelay}, which
     *       is the one rejection a duel could never see, since it happens before any event fires.</li>
     *   <li>{@code canHurtResult=DENY} -- a listener refuses the <em>pairing</em>, which is the shape
     *       an identity-keyed map holding a stale entry would take.</li>
     *   <li>{@code DamageEvent} cancelled -- {@code cancelReason} names the culprit outright.</li>
     *   <li>Everything allowed and health still unchanged -- the damage was modified to nothing, and
     *       the fault is in the modifier stack rather than in any gate.</li>
     * </ul>
     *
     * <p>Run once with a source and once without. The sourced hit is what a duel does; the sourceless
     * one skips {@code EntityCanHurtEntityEvent} entirely, so a difference between them isolates the
     * refusal to the attacker/defender relationship rather than to the target. Invulnerability frames
     * are cleared between the two, because the first hit sets them and would otherwise guarantee the
     * second reads as refused on a perfectly healthy combatant.
     *
     * <p>Mutates the combatant, deliberately, then puts its health back. The poisoned subject is about
     * to be despawned so it makes no difference there, but the control is a fresh resident going
     * straight back into service, and one that started its next duel two hearts down would quietly
     * bias that measurement.
     */
    public String probeDamage(SimPlayer source) {
        final String sourced = watchOneHit(source);
        invulnerableTime = 0;
        hurtTime = 0;
        final String sourceless = watchOneHit(null);
        invulnerableTime = 0;
        hurtTime = 0;
        final String direct = directHurt();

        setHealth(getMaxHealth());
        invulnerableTime = 0;
        hurtTime = 0;

        return "sourced[" + sourced + "] sourceless[" + sourceless + "] " + direct;
    }

    /**
     * Bypasses Bukkit entirely and reports what vanilla itself says about hurting this combatant.
     *
     * <p>Run 149 reported {@code vanillaFired=false} on all four probes -- poisoned and control, sourced
     * and sourceless -- which is a stronger claim than anything about the recycled entity: the
     * {@code EntityDamageEvent} is raised from inside {@code LivingEntity.hurtServer}, so a combatant
     * that never raises one was refused before BetterPvP was reachable, and the control was a
     * combatant spawned seconds earlier that goes on to fight measurable duels. Either the probe never
     * reaches vanilla, or vanilla declines both alike for a reason that has nothing to do with dying.
     *
     * <p>So this calls {@code hurtServer} directly and prints its verdict next to each predicate that
     * can produce it. {@code isInvulnerableTo} is the whole of the first gate; the rest are the
     * environment the {@code CraftLivingEntity.damage} wrapper checks before it delegates, and which a
     * teardown-time probe is the most likely thing in the codebase to be violating.
     */
    /**
     * Decomposes {@code isInvulnerableTo} into the individual predicates it is an or-chain of.
     *
     * <p>Run 150 found the answer and could not name it: the barren duel's defender reported
     * {@code invulnerableTo=true} while its attacker, in the same duel and against the same damage
     * source, reported false and took a clean six points of damage through the whole pipeline. So the
     * refusal is vanilla's first gate and nothing further down -- but every flag the state dump prints
     * reads identically on both, which means the term that is firing is one the dump does not contain.
     *
     * <p>{@code isInvulnerableTo} is {@code isRemoved() || invulnerable&&.. || isInvulnerableToBase()},
     * and the last of those is overridden twice on the way down to {@code ServerPlayer}, picking up
     * conditions that have nothing to do with combat: whether the entity is mid-dimension-change, and
     * whether the client has finished loading. A fake player has no client to finish loading, and
     * nothing in the simulator ever tells the server otherwise -- which would make every combatant
     * invulnerable until something else cleared it, and would explain why the one combatant that has
     * never been recycled is the one that can be hurt.
     */
    private String describeInvulnerability(ServerLevel serverLevel,
                                           net.minecraft.world.damagesource.DamageSource source) {
        return "invulnerableTo=" + isInvulnerableTo(serverLevel, source)
                + " [base=" + isInvulnerableToBase(source)
                + " clientLoaded=" + connection.hasClientLoaded()
                + " changingDimension=" + isChangingDimension()
                + " fireImmune=" + fireImmune() + "]";
    }

    private String directHurt() {
        final ServerLevel serverLevel = (ServerLevel) level();
        final net.minecraft.world.damagesource.DamageSource generic = damageSources().generic();
        final double before = getHealth();
        String hurt;
        try {
            hurt = String.valueOf(hurtServer(serverLevel, generic, 1.0F));
        } catch (Throwable t) {
            hurt = "threw " + t;
        }
        return "nms[" + describeInvulnerability(serverLevel, generic)
                + " hurtServer=" + hurt
                + " healthLost=" + (before - getHealth())
                + " dimension=" + serverLevel.dimension()
                + " levelMatchesBukkit=" + (serverLevel == ((CraftWorld) asBukkit().getWorld()).getHandle())
                + " chunkLoaded=" + serverLevel.hasChunkAt(blockPosition())
                + " gameMode=" + asBukkit().getGameMode()
                + " mainThread=" + org.bukkit.Bukkit.isPrimaryThread()
                + "]";
    }

    /**
     * Lands a single point of damage with a listener attached to every stage of the pipeline.
     *
     * <p>The listener is registered and torn down around the one call rather than kept for the run:
     * {@code DamageEvent} shares a static {@code HandlerList} with every other
     * {@code CustomCancellableEvent}, so a permanent registration here would sit in the dispatch path
     * of the whole server for the sake of at most two hits per sweep.
     */
    private String watchOneHit(SimPlayer source) {
        final ProbeWatcher watcher = new ProbeWatcher();
        final org.bukkit.plugin.Plugin plugin =
                org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(SimPlayer.class);
        org.bukkit.Bukkit.getPluginManager().registerEvents(watcher, plugin);
        final double start = getHealth();
        try {
            if (source == null) {
                asBukkit().damage(1.0D);
            } else {
                asBukkit().damage(1.0D, source.asBukkit());
            }
        } catch (Throwable t) {
            watcher.threw = String.valueOf(t);
        } finally {
            org.bukkit.event.HandlerList.unregisterAll(watcher);
        }
        return watcher.describe(start - getHealth());
    }

    /**
     * Records what each stage of the damage pipeline did to a single probe hit.
     *
     * <p>Everything is at {@code MONITOR} so the reading is of the chain's verdict rather than of some
     * intermediate state, except the entry flags, which only need to know the event was constructed at
     * all. Nothing here mutates an event; a probe that changed the answer would not be one.
     */
    private static final class ProbeWatcher implements org.bukkit.event.Listener {

        private boolean vanillaFired;
        private boolean vanillaCancelled;
        private boolean canHurtFired;
        private String canHurtResult = "-";
        private boolean damageEventFired;
        private boolean damageEventCancelled;
        private String cancelReason;
        private double finalDamage = -1.0D;
        private String threw;

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = false)
        public void onVanilla(org.bukkit.event.entity.EntityDamageEvent event) {
            vanillaFired = true;
            vanillaCancelled = event.isCancelled();
        }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
        public void onCanHurt(me.mykindos.betterpvp.core.combat.events.EntityCanHurtEntityEvent event) {
            canHurtFired = true;
            canHurtResult = String.valueOf(event.getResult());
        }

        @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = false)
        public void onDamage(me.mykindos.betterpvp.core.combat.events.DamageEvent event) {
            damageEventFired = true;
            damageEventCancelled = event.isCancelled();
            cancelReason = event.getCancelReason();
            finalDamage = event.getDamage();
        }

        private String describe(double healthLost) {
            return "healthLost=" + healthLost
                    + " vanillaFired=" + vanillaFired
                    + " vanillaCancelled=" + vanillaCancelled
                    + " canHurtFired=" + canHurtFired
                    + " canHurtResult=" + canHurtResult
                    + " damageEventFired=" + damageEventFired
                    + " damageEventCancelled=" + damageEventCancelled
                    + " cancelReason=" + cancelReason
                    + " finalDamage=" + finalDamage
                    + (threw == null ? "" : " threw=" + threw);
        }
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
        // The other, much larger invulnerability, and the one nothing here could see for ten runs.
        // Belt and braces alongside the calls in the spawn and recycle paths: this runs before every
        // duel on every combatant, so whatever else a slot has been through, the fight starts with a
        // target the damage pipeline is allowed to reach.
        ensureClientLoaded();
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
