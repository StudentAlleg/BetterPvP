package me.mykindos.betterpvp.balancesim.engine;

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
import me.mykindos.betterpvp.core.framework.simulation.SimulatedEntity;
import me.mykindos.betterpvp.core.item.armor.ArmorEquipEvent;
import me.mykindos.betterpvp.core.utilities.UtilPlayer;
import me.mykindos.betterpvp.core.utilities.UtilServer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
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

    private final UUID uuid;
    private final String name;
    private final SimBuildSpec build;

    /** The Bukkit view of the fake player, once spawned. */
    @Nullable
    private Player player;

    @Nullable
    private SimPlayer handle;

    @Nullable
    private Client client;

    /**
     * The build's allocation with {@code effectiveLevel} filled in from the real accessor, or the
     * spec's own list until the combatant has been spawned.
     */
    private List<SimSkillAllocation> measuredSkills;

    public SimCombatant(UUID uuid, String name, SimBuildSpec build) {
        this.uuid = uuid;
        this.name = name;
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
        if (handle != null) {
            throw new IllegalStateException("Combatant " + name + " is already spawned");
        }

        final SimPlayer spawned = SimPlayer.spawn(uuid, name, at);
        this.handle = spawned;
        this.player = spawned.asBukkit();

        // Flag first: everything after this point can reach a listener, and the guards keyed off
        // this metadata are what keep a simulated fight out of the stats and leaderboard tables.
        SimulatedEntity.mark(player, context.plugin());

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
     */
    private void equipWeapon(SimContext context) {
        context.roleManager().equipWeapons(player);
        final ItemStack weapon = context.equipment().weaponStack(build.weaponKey());
        player.getInventory().setItemInMainHand(weapon);
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
     * Removes the fake player and drops its ephemeral client.
     *
     * <p>Despawn happens before the client is dropped so the sequence matches a real logout, and
     * the entity is discarded rather than killed so teardown emits no death event. Leaves no row
     * in {@code clients} because none was ever written.
     */
    public void despawn(SimContext context) {
        if (handle != null) {
            if (player != null) {
                context.roleManager().cleanUp(player);
            }
            handle.despawn();
            handle = null;
        }
        if (client != null) {
            context.clientFactory().destroy(client);
            client = null;
        }
        player = null;
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
        if (handle == null || opponent.handle == null) {
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
     * @param plugin        owner of the simulated-entity metadata
     * @param clientFactory builds the ephemeral client and its builds
     * @param roleManager   applies role and standard weapons through the real code path
     * @param skillManager  resolves skill names and reads effective levels back
     * @param equipment     materialises weapons and armour from the live item registry
     */
    public record SimContext(Plugin plugin,
                             SimClientFactory clientFactory,
                             RoleManager roleManager,
                             ChampionsSkillManager skillManager,
                             SimEquipment equipment) {
    }
}
