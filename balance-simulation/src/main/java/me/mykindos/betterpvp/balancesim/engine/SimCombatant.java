package me.mykindos.betterpvp.balancesim.engine;

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import lombok.CustomLog;
import lombok.Getter;
import me.mykindos.betterpvp.balancesim.catalog.SimBuildSpec;
import me.mykindos.betterpvp.balancesim.catalog.SimEquipment;
import me.mykindos.betterpvp.balancesim.catalog.SimSkillAllocation;
import me.mykindos.betterpvp.champions.champions.roles.RoleManager;
import me.mykindos.betterpvp.champions.champions.skills.ChampionsSkillManager;
import me.mykindos.betterpvp.champions.champions.skills.Skill;
import me.mykindos.betterpvp.core.client.Client;
import me.mykindos.betterpvp.core.components.champions.Role;
import me.mykindos.betterpvp.core.item.armor.ArmorEquipEvent;
import me.mykindos.betterpvp.core.utilities.UtilPlayer;
import me.mykindos.betterpvp.core.utilities.UtilServer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A headless fake player, materialised through the <em>real</em> managers.
 *
 * <p>The point of driving real objects rather than a model is that ComboAttack's ramp,
 * Vengeance's counter and expiry, energy regen and cooldowns all execute as the actual
 * mechanic instances. So a combatant gets an ephemeral {@code Client}/{@code Gamer} (never
 * persisted, see {@link SimClientFactory}), a role and build through {@code RoleManager} and
 * {@code BuildManager}, and its weapon and armour from {@code ItemRegistry} -- the same path a
 * real login takes, minus the database.
 *
 * <p>Unlike {@code HumanNPC}, which is deliberately packet-only and never added to the world,
 * a sim combatant is a real {@code ServerPlayer} added to the world with a dead-end connection
 * ({@link SimPlayer}), so the vanilla attack path, targeting and event pipeline actually engage.
 */
@Getter
@CustomLog
public class SimCombatant {

    /**
     * Raw slot of hotbar index 0 in the player's own container menu.
     *
     * <p>The nine hotbar slots sit after the 2x2 crafting grid, the result slot, the four armour
     * slots and the main inventory, which is where the server's own slot-change events number them
     * from. Announcing an equip with the plain inventory index instead would look like a change to
     * a different slot entirely.
     */
    private static final int HOTBAR_MENU_SLOT_OFFSET = 36;

    private final UUID uuid;
    private final String name;
    private final SimBuildSpec build;

    /**
     * The pooled entity this combatant drives. Owned by {@link SimCombatantPool} and outliving the
     * duel, so it is never despawned here.
     */
    private final SimPlayer handle;

    /** The Bukkit view of the fake player, non-null between {@link #spawn} and {@link #despawn}. */
    @Nullable
    private Player player;

    @Nullable
    private Client client;

    /**
     * The build's allocation with {@code effectiveLevel} filled in from the real accessor, or the
     * spec's own list until the combatant has been spawned.
     */
    private List<SimSkillAllocation> measuredSkills;

    /**
     * @param handle the pooled entity to fight this duel through. Its identity is the combatant's:
     *               a resident keeps one UUID for the life of the sweep, so log lines and recorder
     *               rows for successive duels on the same arena share it. The duel's own identity
     *               is the {@code sim_result} row, not the combatant's name.
     */
    public SimCombatant(SimPlayer handle, SimBuildSpec build) {
        this.handle = handle;
        this.uuid = handle.getUUID();
        this.name = handle.getScoreboardName();
        this.build = build;
        this.measuredSkills = build.skills();
    }

    /**
     * Spawns the backing {@code ServerPlayer} into the sim world and applies its client, role,
     * build, weapon and armour through the real managers.
     *
     * <p>Ordering is deliberate and load-bearing:
     * <ol>
     *   <li>spawn the entity, so there is something to flag;</li>
     *   <li>flag it as simulated <em>before</em> anything else, so no later step can produce a
     *       persisted side effect that escapes the guards in the stats listeners;</li>
     *   <li>register the ephemeral client and its builds, because {@code RoleManager.equipRole}
     *       looks the client up via {@code search().online(player)}, and the {@code RoleChangeEvent}
     *       it fires is what makes {@code SkillListener} call {@code trackPlayer} on the build's
     *       skills -- so the build must already be populated when the role is equipped, or the
     *       passives in it are never tracked;</li>
     *   <li>equip the role, which sets base health from {@code Role.getHealth()};</li>
     *   <li>equip the weapon, because a skill's effective level and even whether it fires at all
     *       depend on what is in the main hand ({@code SkillWeapons.isHolding});</li>
     *   <li>equip armour and top health up, in that order, because armour raises max health.</li>
     * </ol>
     *
     * <p>Must be called on the main thread.
     *
     * @param context the managers and plugin handle this combatant is built through
     * @param at      where in the sim world to place it
     */
    public void spawn(SimContext context, Location at) {
        if (player != null) {
            throw new IllegalStateException("Combatant " + name + " is already spawned");
        }

        // The entity already exists and is already flagged -- SimCombatantPool marks a resident as
        // it is added to the level, which is strictly earlier than this used to happen. All that is
        // left is to undo the last duel's marks on it.
        handle.prepareForDuel(at);
        this.player = handle.asBukkit();

        // Registers the client *and* its builds, populated from the spec. equipRole below fires
        // RoleChangeEvent, whose listeners read both, so neither can be deferred until after the
        // role is set.
        this.client = context.clientFactory().create(player, name, build);

        final Role role = Role.valueOf(build.role());
        equipRole(context, role);
        equipWeapon(context);
        equipArmor(context, role);

        // Start the duel at full health. Neither equipRole nor the armour above touches current
        // health; for a real player that reconciliation is done by HealthListener.updateHealth,
        // which runs on PlayerJoinEvent -- an event a fake player never fires, because it is
        // deliberately not in the PlayerList. Without this every combatant fought at the vanilla
        // default of 20 regardless of role, so a 29 HP assassin and a 40 HP brute both died to the
        // same four hits and target_hp on the row described a durability the entity did not have.
        player.setHealth(UtilPlayer.getMaxHealth(player));

        this.measuredSkills = readBackEffectiveLevels(context);
    }

    /**
     * Equips the role, forcing a change when the combatant already reads as that role.
     *
     * <p>{@code RoleManager.equipRole} short-circuits when the role is unchanged -- no
     * {@code RoleChangeEvent}, and no {@code updateRole}, which is what sets base health. A freshly
     * spawned player has no entry in the role store, and {@code getRole(Player)} answers
     * {@code Role.DEFAULT} for a miss rather than "unknown". So equipping the default role was a
     * complete no-op: a knight combatant kept the vanilla 20 max health while the row claimed 40,
     * and none of its skills were ever tracked.
     *
     * <p>Passing through another role first makes the second call a genuine change. The transition
     * is real rather than faked -- both events fire and both are handled by the real listeners --
     * which is why the priming role's build is registered and empty rather than absent.
     */
    private void equipRole(SimContext context, Role role) {
        final RoleManager roles = context.roleManager();
        if (roles.getRole(player) == role) {
            roles.equipRole(player, role == Role.DEFAULT ? Role.ASSASSIN : Role.DEFAULT);
        }
        roles.equipRole(player, role);
    }

    /**
     * Gives the combatant the role's standard kit and then puts the build's weapon in the main
     * hand.
     *
     * <p>{@code equipWeapons} is still called so the inventory matches a real player's -- it is
     * what hands out the off-slot sword/axe and, for ranger and assassin, a bow and arrows. The
     * main hand is then overwritten with the weapon the build is being measured on, because that
     * is what the melee stat handlers read and what {@code SkillWeapons.isHolding} tests when
     * deciding whether a sword skill is active and whether the booster {@code +1} applies.
     *
     * <p>{@code PlayerInventorySlotChangeEvent} is raised afterwards for the same reason
     * {@link #equipArmor} raises {@code ArmorEquipEvent}: the server fires it from the container
     * menu's change tracking, which a headless combatant never drives, so setting the stack alone
     * leaves every listener that keys off "this player is now holding X" unaware. That is not
     * cosmetic -- {@code InteractionListener} feeds the hold tracker from it, and items keeping
     * per-holder state build that state there. The scythe is the case that surfaced it: its soul
     * map is populated only on join and slot change, so an unannounced equip left it with no entry
     * and its damage handler threw out of every swing.
     *
     * <p>The raw slot is the hotbar's menu slot rather than the inventory index, so the event
     * carries the numbers a real swap would and listeners filtering on either read it identically.
     */
    private void equipWeapon(SimContext context) {
        context.roleManager().equipWeapons(player);
        final PlayerInventory inventory = player.getInventory();
        final ItemStack previous = inventory.getItemInMainHand();
        final ItemStack weapon = context.equipment().weaponStack(build.weaponKey());
        inventory.setItemInMainHand(weapon);
        UtilServer.callEvent(new PlayerInventorySlotChangeEvent(player,
                HOTBAR_MENU_SLOT_OFFSET + inventory.getHeldItemSlot(), previous, weapon));
    }

    /**
     * Equips the armour set and fires the real {@code ArmorEquipEvent} for each piece.
     *
     * <p>Setting the equipment directly is not enough. {@code ArmorEquipEvent} is normally raised
     * by {@code ArmorListener} from inventory clicks and drags, none of which a headless combatant
     * performs, and it is that event which drives {@code HealthListener.updateHealth} -- the code
     * that installs the {@code MAX_HEALTH} modifier for the armour's HEALTH stats. Without it the
     * entity would wear the set while fighting at bare-role durability, and {@code target_hp}
     * would describe armour that was doing nothing.
     *
     * <p>The event is raised with {@code ArmorAction.CUSTOM}, which is what the class documents for
     * callers raising it themselves, so every other armour listener (runes, gems) sees the equip
     * the same way it would for a real player.
     */
    private void equipArmor(SimContext context, Role role) {
        final List<ItemStack> armor = context.equipment().armorStacks(role, build.armorSetId());
        if (armor.isEmpty()) {
            return;
        }

        final PlayerInventory inventory = player.getInventory();
        for (ItemStack piece : armor) {
            final EquipmentSlot slot = SimEquipment.slotOf(piece);
            if (slot == null) {
                continue;
            }
            inventory.setItem(slot, piece);
            UtilServer.callEvent(new ArmorEquipEvent(player, piece, slot));
        }
    }

    /**
     * Reads each allocated skill's effective level back through {@code Skill.getLevel}.
     *
     * <p>The effective level is not the allocated one: a booster weapon adds one and a
     * {@code SkillBoostEffect} adds its amplifier, and neither is re-clamped to {@code maxLevel}.
     * It is read from the live player after everything is equipped rather than derived, because
     * deriving it would fork the definition from {@code SkillListener}'s -- the precise class of
     * drift this project exists to remove. Recording both is what keeps a booster build
     * distinguishable from a plain one at the same points spend, despite identical allocations.
     */
    private List<SimSkillAllocation> readBackEffectiveLevels(SimContext context) {
        if (build.skills().isEmpty()) {
            return List.of();
        }

        final ChampionsSkillManager skills = context.skillManager();
        final List<SimSkillAllocation> measured = new ArrayList<>(build.skills().size());
        for (SimSkillAllocation allocation : build.skills()) {
            final Skill skill = skills.getObject(allocation.skillName()).orElse(null);
            final int effective = skill == null ? allocation.allocatedLevel() : skill.getLevel(player);
            measured.add(new SimSkillAllocation(allocation.skillName(), allocation.slot(),
                    allocation.allocatedLevel(), effective));
        }
        return List.copyOf(measured);
    }

    /**
     * Releases the combatant from the duel: unequips its skills, drops its ephemeral client and
     * purges its per-UUID state, leaving the entity resident in the world for
     * {@link SimCombatantPool} to hand out again.
     *
     * <p>Leaves no row in {@code clients} because none was ever written.
     *
     * <p>The {@link SimStatePurge} runs last, once the client is dropped, and is what stands in for
     * the {@code PlayerQuitEvent} a fake player never fires. It matters more under pooling than it
     * did before: with a fresh entity per duel a missed manager merely leaked, whereas a resident
     * carries whatever is left behind into the next duel on its platform, where it becomes a
     * measurement error rather than a memory one.
     */
    public void despawn(SimContext context) {
        if (player != null) {
            // Before the role goes: an invalidate may read the player's level or energy max, both of
            // which resolve through the role and build that cleanUp and destroy are about to drop.
            invalidateSkills(context);
            context.roleManager().cleanUp(player);
        }
        if (client != null) {
            context.clientFactory().destroy(client);
            client = null;
        }
        // Purge before dropping the reference: clearing this combatant's damage delays needs the
        // entity, not just its UUID. The entity itself stays in the world -- the pool owns it, and
        // the whole point of a resident is that it is never removed from the level.
        context.statePurge().purge(uuid, player);
        player = null;
    }

    /**
     * Unequips every skill from the combatant, so nothing champions holds under this identity
     * survives into the next duel on the slot.
     *
     * <p>This is the replacement for waiting. A resident entity is the key every
     * {@code WeakHashMap<Player, ?>} in champions files its per-player combat state under, and in
     * production the only thing that clears those maps for a departed player is the entity becoming
     * garbage -- which residency is precisely designed to prevent. The pool used to hold a slot out
     * of service until every such window had certainly expired on its own timer, which cost
     * {@code quarantine / duelDuration} times the concurrency in resident entities and made
     * throughput track quarantine rather than the sweep. Dequipping is the same cleanup done
     * immediately and deterministically, so the slot is reusable the moment the duel ends.
     *
     * <p>{@code invalidatePlayer} is called directly rather than by raising {@code SkillDequipEvent}:
     * that event means "the build was edited", and {@code SkillStatListener} handles it by recording
     * a build change against the client. The direct call is exactly what {@code SkillListener} does
     * on the paths that matter here -- dequip and role change -- minus the stat write.
     *
     * <p>Every registered skill, not only the build's. The sim's requirement is stronger than a real
     * dequip's: state under a combatant's identity is not always held by a skill that combatant
     * carries. {@code Thorns} keys its internal cooldown by the <em>damager</em>, so the attacker
     * accrues an entry in a skill only the defender has equipped. Iterating the whole registry is
     * the only way to reach those, and it is cheap -- an invalidate for a skill the player never had
     * is a handful of failed map removes, against the several milliseconds a duel's setup costs.
     *
     * <p>Failures are caught per skill. One skill throwing must not leave the remaining hundred-odd
     * uncleaned, because that residue would be inherited by the next duel and read as a balance
     * difference rather than as an error.
     */
    private void invalidateSkills(SimContext context) {
        if (client == null) {
            return;
        }
        for (Skill skill : context.skillManager().getObjects().values()) {
            try {
                skill.invalidatePlayer(player, client.getGamer());
            } catch (Exception e) {
                log.warn("Skill {} failed to invalidate sim combatant {}", skill.getName(), name, e).submit();
            }
        }
    }

    /**
     * Swings at the opponent through the vanilla attack path.
     *
     * <p>{@code ServerPlayer.attack} is the same entry point a real click reaches, so the swing
     * runs the full chain -- vanilla damage calculation, {@code DamageEventProcessor}, every
     * registered passive and rune listener -- rather than injecting a damage number. The
     * simulator never computes damage itself; it only decides when to swing.
     */
    public void swingAt(SimCombatant opponent) {
        if (player == null || opponent.player == null) {
            return;
        }
        handle.attack(opponent.handle);
        // Combat here is 1.8-style: damage is constant and there is no attack-strength charge to
        // wait out. The ticker is reset anyway so that if vanilla scaling is ever in play, a
        // simulated swing is a fully-charged one rather than a partially-charged one, and swing
        // timing stays governed by the pipeline's damage delay alone.
        handle.resetAttackStrengthTicker();
    }

    /** Whether this combatant is spawned and still alive. */
    public boolean isAlive() {
        return player != null && !player.isDead() && player.getHealth() > 0;
    }

    /**
     * The managers a combatant needs to materialise itself. Passed in rather than injected so
     * {@link SimCombatant} stays a plain object the orchestrator creates per duel.
     *
     * <p>{@code roleManager} and {@code skillManager} must be the instances from Champions' own
     * injector -- see {@code DuelOrchestrator}, which resolves them there. A Guice just-in-time
     * binding in this plugin's child injector would build a second {@code RoleManager} with its own
     * role store, and the champions listeners read the first one: every combatant would look like
     * {@code Role.DEFAULT} to {@code Skill.getSkill}, so no non-knight skill would ever resolve.
     *
     * <p>No plugin handle: flagging a combatant as simulated used to happen here, and now happens
     * in {@link SimCombatantPool} as a resident is added to the level, which is the only moment
     * early enough once entities outlive the duels fought on them.
     *
     * @param clientFactory builds the ephemeral client and its builds
     * @param roleManager   applies role and standard weapons through the real code path
     * @param skillManager  resolves skill names and reads effective levels back
     * @param equipment     materialises weapons and armour from the live item registry
     * @param statePurge    drops the per-UUID state no {@code PlayerQuitEvent} will ever clear
     */
    public record SimContext(SimClientFactory clientFactory,
                             RoleManager roleManager,
                             ChampionsSkillManager skillManager,
                             SimEquipment equipment,
                             SimStatePurge statePurge) {
    }
}
