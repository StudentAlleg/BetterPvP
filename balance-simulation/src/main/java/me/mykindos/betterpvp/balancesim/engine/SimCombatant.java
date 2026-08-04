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
import me.mykindos.betterpvp.champions.champions.skills.types.ActiveToggleSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.ChannelSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.ChargedPassiveSkill;
import me.mykindos.betterpvp.core.client.Client;
import me.mykindos.betterpvp.core.components.champions.Role;
import me.mykindos.betterpvp.core.item.armor.ArmorEquipEvent;
import me.mykindos.betterpvp.core.item.armor.ArmorUnequipEvent;
import me.mykindos.betterpvp.core.utilities.UtilPlayer;
import me.mykindos.betterpvp.core.utilities.UtilServer;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
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
     * The build's skills paired with the input each one needs, plus the rotation's per-skill timing.
     *
     * <p>Resolved once at spawn rather than per tick: classifying an archetype walks the skill's type
     * hierarchy and resolving the name walks the manager's map, and the rotation runs on every duel on
     * every tick. Empty until spawned, so a rotation on an unspawned combatant does nothing.
     */
    private List<DrivenSkill> drivenSkills = List.of();

    /**
     * The resident's state as it arrived for this duel, before anything was equipped.
     *
     * <p>Null until {@link #spawn}. A resident is supposed to arrive indistinguishable from a fresh
     * entity -- {@code despawn} unequips every registered skill and purges the per-UUID state -- and
     * this is the only record of whether it actually did.
     */
    @Nullable
    private String residueBeforeSetup;

    /** The role the resident was still carrying from its previous duel. Null until {@link #spawn}. */
    @Nullable
    private Role roleBeforeEquip;

    /**
     * Whether {@link #equipRole} had to pass through a priming role to force a real change.
     *
     * <p>Load-bearing for the diagnostic rather than for the duel: a primed equip fires two
     * {@code RoleChangeEvent}s and an unprimed one fires one, and which happens depends on the
     * previous duel on this resident rather than on this build.
     */
    private boolean rolePrimed;

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
     * @param context     the managers and plugin handle this combatant is built through
     * @param at          where in the sim world to place it
     * @param diagnostics whether this run is collecting per-duel diagnostics. Gates the residue
     *                    probe only: it walks the entity's level registration and connection state,
     *                    which is a handful of microseconds nobody should pay on a sweep that is not
     *                    going to read it
     */
    public void spawn(SimContext context, Location at, boolean diagnostics) {
        if (player != null) {
            throw new IllegalStateException("Combatant " + name + " is already spawned");
        }

        // Read before anything is applied, so it describes what the previous duel on this resident
        // left behind rather than what this one is about to set up. A resident is supposed to arrive
        // here indistinguishable from a fresh entity; this is the only place that claim is testable.
        this.residueBeforeSetup = diagnostics ? handle.describeCombatState() : null;

        // The entity already exists and is already flagged -- SimCombatantPool marks a resident as
        // it is added to the level, which is strictly earlier than this used to happen. All that is
        // left is to undo the last duel's marks on it.
        handle.prepareForDuel(at);
        handle.duelsFought++;
        // Makes Bukkit.getPlayer resolve the combatant for the length of the duel, which is what every
        // skill that holds its targets as UUIDs needs in order to keep working on one -- and what makes
        // the energy gate bind. Before the client and the role, because the RoleChangeEvent listeners
        // are among the code that resolves a player that way. See SimPlayer for why this is the map
        // and not the player list.
        handle.registerForLookup();
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
        this.drivenSkills = resolveDrivenSkills(context);
        primeChargedPassives();
    }

    /**
     * Banks every {@link ChargedPassiveSkill} in the build before the first swing.
     *
     * <p>These passives accrue charges only while {@code Gamer.hasBeenOutOfCombatFor} holds, and a
     * duel here is one unbroken engagement from the first tick. So their charges seeded at zero and
     * stayed there: {@code Deflection} spent a zero-charge modifier on every hit it saw, and
     * {@code Swordsmanship} the same, which is why both measured as doing exactly nothing while
     * being perfectly functional skills.
     *
     * <p>Priming them is a statement about the starting conditions, not a change to the mechanic. A
     * real knight walks into a fight already banked, having not been hit for the preceding seconds;
     * what the sweep should measure is what the charges are worth when spent, and it cannot measure
     * that from a state the mechanic never intends its holder to be in. The accrual rule itself is
     * untouched -- once the duel starts, combat suppresses further charging exactly as it does live.
     *
     * <p>Runs after {@link #resolveDrivenSkills} so the build's levels are readable; each skill reads
     * its own level and does nothing for a player who does not hold it. Called from {@link #spawn},
     * which runs once per duel rather than once per pooled resident, so a recycled combatant is
     * re-banked for each fight instead of carrying a spent state into the next one.
     */
    private void primeChargedPassives() {
        for (DrivenSkill driven : drivenSkills) {
            if (driven.getSkill() instanceof ChargedPassiveSkill charged) {
                charged.fillCharges(player);
            }
        }
    }

    /**
     * Everything about this combatant that was decided before the first swing.
     *
     * <p>Taken after {@link #spawn} has finished, so it is what the duel actually starts from rather
     * than what the build asked for. The point of collecting it is subtraction: a build carrying one
     * passive that presses nothing should be identical here to a bare build on the same weapon
     * against the same target, and run 164 says the two do not fight the same fight. If they differ
     * on any line of this, the fight was decided at setup and no amount of combat instrumentation
     * would have shown it.
     *
     * <p>Read through the Bukkit API rather than the handle where both offer it, because the Bukkit
     * value is the one every listener in the damage pipeline sees.
     */
    public SetupSnapshot setupSnapshot() {
        if (player == null) {
            throw new IllegalStateException("Combatant " + name + " is not spawned");
        }
        final List<String> effects = new ArrayList<>();
        for (PotionEffect effect : player.getActivePotionEffects()) {
            effects.add(effect.getType().getKey().getKey() + ":" + effect.getAmplifier()
                    + "@" + effect.getDuration());
        }
        return new SetupSnapshot(
                roleBeforeEquip == null ? "none" : roleBeforeEquip.name(),
                rolePrimed,
                player.getHealth(),
                UtilPlayer.getMaxHealth(player),
                attribute(Attribute.ARMOR),
                attribute(Attribute.ARMOR_TOUGHNESS),
                attribute(Attribute.ATTACK_DAMAGE),
                attribute(Attribute.ATTACK_SPEED),
                attribute(Attribute.KNOCKBACK_RESISTANCE),
                attribute(Attribute.MOVEMENT_SPEED),
                String.valueOf(player.getInventory().getItemInMainHand().getType()),
                player.getInventory().getHeldItemSlot(),
                measuredSkills,
                effects,
                handle.duelsFought,
                handle.revives,
                handle.everDied,
                residueBeforeSetup == null ? "(not captured)" : residueBeforeSetup);
    }

    /** An attribute's current value, or NaN when the entity does not carry it at all. */
    private double attribute(Attribute type) {
        final AttributeInstance instance = player.getAttribute(type);
        return instance == null ? Double.NaN : instance.getValue();
    }

    /**
     * One combatant's start-of-duel state.
     *
     * @param roleBeforeEquip   the role left over from the previous duel on this resident
     * @param rolePrimed        whether equipping needed a priming pass, which doubles the
     *                          {@code RoleChangeEvent}s every skill's tracking hangs off
     * @param measuredSkills    the build's allocation with effective levels read back off the entity
     * @param effects           active potion effects, as {@code key:amplifier@duration}
     * @param duelsFought       how many duels this entity has now been set up for
     * @param revives           how many times the pool has had to restore it
     * @param everDied          whether it has ever lost a duel
     * @param residueBeforeSetup the entity's combat state as it arrived, before anything was applied
     */
    public record SetupSnapshot(String roleBeforeEquip,
                                boolean rolePrimed,
                                double health,
                                double maxHealth,
                                double armor,
                                double armorToughness,
                                double attackDamage,
                                double attackSpeed,
                                double knockbackResistance,
                                double movementSpeed,
                                String heldItem,
                                int heldSlot,
                                List<SimSkillAllocation> measuredSkills,
                                List<String> effects,
                                int duelsFought,
                                int revives,
                                boolean everDied,
                                String residueBeforeSetup) {
    }

    /**
     * Pairs each of the build's skills with the input archetype that activates it.
     *
     * <p>Resolved after the weapon is equipped, because {@link ActivationArchetype} is a property of the
     * skill class but whether the skill can fire at all depends on what is held -- and the rotation asks
     * that per tick through the real {@code SkillWeapons} accessor rather than caching it, since a
     * booster swap mid-sweep would invalidate a cached answer.
     *
     * <p>A skill the catalog named but the manager does not know is skipped with a warning rather than
     * throwing: the sweep's other axes are still measurable, and {@code SimClientFactory} would already
     * have failed loudly on the same name when it built the build.
     */
    private List<DrivenSkill> resolveDrivenSkills(SimContext context) {
        if (build.skills().isEmpty()) {
            return List.of();
        }
        final List<DrivenSkill> driven = new ArrayList<>(build.skills().size());
        for (SimSkillAllocation allocation : build.skills()) {
            final Skill skill = context.skillManager().getObject(allocation.skillName()).orElse(null);
            if (skill == null) {
                log.warn("Catalog named skill {} which is not registered; combatant {} will not drive it",
                        allocation.skillName(), name).submit();
                continue;
            }
            driven.add(new DrivenSkill(skill, ActivationArchetype.of(skill)));
        }
        return List.copyOf(driven);
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
        // Recorded because the branch is taken or not depending on what the *previous* duel on this
        // resident equipped, which makes the number of RoleChangeEvents a build sees a function of
        // sweep ordering rather than of the build. Every skill's trackPlayer hangs off that event.
        this.roleBeforeEquip = roles.getRole(player);
        this.rolePrimed = roleBeforeEquip == role;
        if (rolePrimed) {
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
        // With the build's runes socketed in. The rune handlers read the container off the held stack, so
        // this is the only place a rune becomes real -- there is no separate "apply rune" step.
        final ItemStack weapon = context.equipment().weaponStack(build.weaponKey(), build.runeKeys());
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
            // Release any held input first, so the channel that is about to be cancelled is not also
            // left with the server believing the hand is still raised.
            releaseHeldInput(context);
            // Before the role goes: an invalidate may read the player's level or energy max, both of
            // which resolve through the role and build that cleanUp and destroy are about to drop.
            invalidateSkills(context);
            unequipArmor();
            context.roleManager().cleanUp(player);
        }
        // Before the client goes: ClientManager.unload refuses while Bukkit.getPlayer(uuid) resolves,
        // so with the combatant still registered the ephemeral client would stay in the cache until the
        // next duel on this slot happened to replace it.
        handle.unregisterForLookup();
        if (client != null) {
            context.clientFactory().destroy(client);
            client = null;
        }
        // Purge before dropping the reference: clearing this combatant's damage delays needs the
        // entity, not just its UUID. The entity itself stays in the world -- the pool owns it, and
        // the whole point of a resident is that it is never removed from the level.
        context.statePurge().purge(uuid, player);
        player = null;
        drivenSkills = List.of();
    }

    /**
     * Strips the armour set and fires the real {@code ArmorUnequipEvent} for each piece.
     *
     * <p>The mirror of {@link #equipArmor}, and required for the same reason it exists. The
     * {@code MAX_HEALTH} modifier that {@code HealthListener.updateHealth} installs for an armour
     * set's HEALTH stats is keyed on {@code betterpvp:health} and recomputed only when an armour
     * event fires. Clearing the inventory slots silently would leave that modifier installed, and
     * the modifier is what max health is actually made of -- {@code EntityHealthService.getMaxHealth}
     * reads the worn set, but the attribute keeps whatever was last written to it.
     *
     * <p>Without this, a resident handed out for a build with no armour never fires an armour event
     * at all ({@code equipArmor} returns early on an empty set), so it fights at its own role's base
     * health plus the previous occupant's armour bonus. Run 165 measured exactly that: an
     * {@code ASSASSIN/none} target, whose true max health is 29, was recorded at 29, 36, 43 and 47 --
     * its base plus the +7/+14/+18 of whichever set the entity wore in the duel before, matching
     * one-to-one across all 45,254 duels. It decided the sweep: that target is killable in five hits
     * at 29 health and not at all at 47, so the skill-less baselines, which are enumerated first and
     * therefore inherited least, won matchups that every skill build then lost -- and the difference
     * was booked as the skill's contribution.
     */
    private void unequipArmor() {
        // Unset the death first, or the events below are fired at a corpse and do nothing:
        // HealthListener.updateHealth opens by reading getHealth()/getValue() and returns early when
        // that is 0, deliberately, so a dead entity is not resized. A combatant that died wearing a
        // set therefore kept the set's modifier through despawn, and the pool's revive then healed it
        // to the inflated maximum. Run 166 measured the residue this leaves: a target that had never
        // died was clean in all 686 duels, while one that had died carried a stale bonus into 27.4%
        // of its no-armour duels. Reviving here is not redundant with the pool's own revive -- that
        // one runs after this, on a resident whose armour is already gone and which can no longer
        // raise the event that would correct the attribute.
        handle.reviveIfDead();
        final PlayerInventory inventory = player.getInventory();
        for (EquipmentSlot slot : SimEquipment.ARMOR_SLOTS) {
            final ItemStack worn = inventory.getItem(slot);
            if (worn == null || worn.getType().isAir()) {
                continue;
            }
            inventory.setItem(slot, null);
            UtilServer.callEvent(new ArmorUnequipEvent(player, worn, slot));
        }
    }

    /**
     * Stops holding right click, if the rotation left this combatant mid-channel.
     *
     * <p>A duel can end on any tick, including one where a channel is halfway through its hold budget.
     * The held item is entity state on a resident, so leaving it set would carry a raised hand into the
     * next duel on this platform -- where {@code SimInputs.beginHold} would decline to start a hold
     * because one is apparently already running.
     */
    private void releaseHeldInput(SimContext context) {
        if (heldSkill() != null) {
            context.inputs().endHold(player);
        }
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
                cancelIfRunning(skill);
                skill.invalidatePlayer(player, client.getGamer());
            } catch (Exception e) {
                log.warn("Skill {} failed to invalidate sim combatant {}", skill.getName(), name, e).submit();
            }
        }
    }

    /**
     * Stops a channel, charge or active toggle that is still running on this combatant.
     *
     * <p>{@code invalidatePlayer} is not enough for these. Each keeps its holders in its own set and
     * clears them from {@code PlayerDeathEvent}, {@code PlayerQuitEvent} or a state-change handler --
     * none of which a combatant that survives its duel produces. Under pooling that residue is
     * inherited: the next build on this platform would open its duel already channelling a skill it does
     * not carry, and the row would attribute that damage to the wrong build.
     *
     * <p>It is done through each type's own public {@code cancel}, so the skill runs its own
     * teardown -- {@code onCancel}, the effects it applied, the charge data it accumulated -- rather
     * than having its set reached into. That is the same call the death and quit handlers make.
     *
     * <p>Every registered skill is offered, not only the build's, for the reason the caller documents:
     * state under a combatant's identity is not always held by a skill that combatant carries. The
     * channel cancel is unconditional because {@code ChannelSkill} exposes no way to ask whether it is
     * running -- which is safe, because cancelling reduces to a set removal plus {@code onCancel}, and
     * the sole implementation of that hook returns immediately when the player has no data.
     */
    private void cancelIfRunning(Skill skill) {
        if (skill instanceof ChannelSkill channel) {
            // Covers ChargeSkill too, which overrides cancel to drop its charge data as well.
            channel.cancel(player);
        } else if (skill instanceof ActiveToggleSkill toggle && toggle.getActive().contains(uuid)) {
            toggle.cancel(player);
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
     * One skill of the build, the input that activates it, and the rotation's timing for it.
     *
     * <p>Mutable and per combatant per duel, which is the whole reason it lives here rather than in
     * {@link GreedyRotationPolicy}: the policy is a singleton shared by every duel in flight, so any
     * state it kept would have to be a map keyed by combatant, and the natural owner of "when may this
     * combatant next press this button" is the combatant.
     */
    @Getter
    public static final class DrivenSkill {

        private final Skill skill;
        private final ActivationArchetype archetype;

        /**
         * Earliest tick the rotation may press this skill's button again.
         *
         * <p>An attempt is cheap but not free -- it dispatches a real event through the whole listener
         * chain -- and pressing every tick on every combatant in a 128-duel sweep is tens of thousands of
         * event dispatches a second for no extra information. The interval is a rate limit on
         * <em>attempts</em>, not a model of the cooldown: whether anything happens is still the real
         * gates' decision.
         */
        private long nextAttemptTick;

        /** Tick the held input is released on, or {@code -1} when this skill is not being held. */
        private long releaseHoldAtTick = -1;

        private DrivenSkill(Skill skill, ActivationArchetype archetype) {
            this.skill = skill;
            this.archetype = archetype;
        }

        void attemptedAt(long tick, long retryIntervalTicks) {
            this.nextAttemptTick = tick + retryIntervalTicks;
        }

        void holdUntil(long tick) {
            this.releaseHoldAtTick = tick;
        }

        void released() {
            this.releaseHoldAtTick = -1;
        }

        boolean isHolding() {
            return releaseHoldAtTick >= 0;
        }
    }

    /**
     * The skill this combatant is currently holding right click for, or null.
     *
     * <p>At most one, because a combatant has one right hand -- which is the arbitration
     * {@link GreedyRotationPolicy} needs and the reason it is asked here rather than tracked there.
     */
    @Nullable
    public DrivenSkill heldSkill() {
        for (DrivenSkill driven : drivenSkills) {
            if (driven.isHolding()) {
                return driven;
            }
        }
        return null;
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
     * @param inputs        synthesises the real input events a rotation presses
     */
    public record SimContext(SimClientFactory clientFactory,
                             RoleManager roleManager,
                             ChampionsSkillManager skillManager,
                             SimEquipment equipment,
                             SimStatePurge statePurge,
                             SimInputs inputs) {
    }
}
