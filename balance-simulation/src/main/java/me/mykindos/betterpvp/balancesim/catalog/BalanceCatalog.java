package me.mykindos.betterpvp.balancesim.catalog;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.balancesim.catalog.SimEquipment.WeaponOption;
import me.mykindos.betterpvp.champions.Champions;
import me.mykindos.betterpvp.champions.champions.builds.RoleBuild;
import me.mykindos.betterpvp.champions.champions.skills.ChampionsSkillManager;
import me.mykindos.betterpvp.champions.champions.skills.Skill;
import me.mykindos.betterpvp.core.components.champions.Role;
import me.mykindos.betterpvp.core.components.champions.SkillType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Enumerates the permutation space from the <em>live registries</em>, never from YAML.
 *
 * <p>Weapons and armour come from {@link SimEquipment} (which reads {@code ItemRegistry}), roles
 * from the {@code Role} enum, and skills from the Guice {@code Skill} singletons via
 * {@code ChampionsSkillManager.getSkillsForRole}, which is also what applies the class/slot
 * constraints -- they are read from the same code the game uses rather than re-encoded here.
 *
 * <h2>Level vectors</h2>
 * Every skill in a build varies independently over its legal range, bounded by two rules that
 * both come from real code:
 * <ol>
 *   <li>allocated level is {@code 1..Skill.getMaxLevel()} ({@code maxlevel}, config-driven,
 *       default 5); {@code BuildRepository} clamps to it on load, so higher allocations are
 *       not representable.</li>
 *   <li>total allocated levels across the build must be {@code <= RoleBuild.points} (12), read
 *       from a real {@link RoleBuild} rather than written as a literal.</li>
 * </ol>
 * The second is the dominant prune -- it cuts the naive 5^5 = 3125 vectors per role to a few
 * hundred, and means "everything at max" is not a reachable build at all. Vectors are generated
 * budget-feasible rather than generated and filtered.
 *
 * <h2>Why the space is tiered</h2>
 * Even after both prunes the unrestricted product is millions of matchups, and duels cost real
 * time. {@link SimScope} is the tier, chosen at invocation. A tier that would exceed the
 * configured build cap is refused with its count rather than silently truncated, because a prefix
 * of an enumeration is a biased sample and nothing on the row would say so.
 */
@Singleton
@CustomLog
public class BalanceCatalog {

    private final SimEquipment equipment;
    private final ChampionsSkillManager skillManager;

    @Inject
    public BalanceCatalog(SimEquipment equipment) {
        this.equipment = equipment;
        // Pulled from Champions' injector rather than injected, for the same reason SimClientFactory
        // does it: this plugin's injector is a sibling of Champions' under Core, and asking Guice
        // for a Champions-scoped singleton here would construct a second Champions.
        this.skillManager = JavaPlugin.getPlugin(Champions.class).getInjector()
                .getInstance(ChampionsSkillManager.class);
    }

    /**
     * Enumerates every attacker build in scope.
     *
     * @param scope     which axes are varied
     * @param maxBuilds refuse rather than truncate above this many builds
     * @throws IllegalStateException if the scope exceeds {@code maxBuilds}
     */
    public List<SimBuildSpec> enumerateBuilds(SimScope scope, int maxBuilds) {
        final List<SimBuildSpec> builds = new ArrayList<>();
        for (Role role : Role.values()) {
            switch (scope.getSkillAxis()) {
                case NONE -> enumerateSkilless(role, scope, builds);
                case ONE_AT_A_TIME -> enumerateSingleSkill(role, scope, builds, maxBuilds);
                case BUDGET_VECTORS -> enumerateBudgetVectors(role, scope, builds, maxBuilds);
            }
            checkCap(builds.size(), maxBuilds, scope);
        }
        log.info("Catalog scope {} enumerated {} builds", scope, builds.size()).submit();
        return List.copyOf(builds);
    }

    /**
     * Enumerates every defender configuration in scope.
     *
     * <p>Targets carry no skills yet: a defender's {@code DefensiveSkill} passives change the
     * outcome, but sweeping the defender's build as well squares an already real-time-bound space.
     * Defender builds are enumerated once the attacker axis is affordable -- until then a target is
     * identified by role and armour, and {@code target_skills} on the row is empty rather than
     * fabricated.
     *
     * <p>Durability comes from {@link SimEquipment#durability}, which sums the armour's real
     * {@code StatTypes.HEALTH} through {@code EntityHealthService} -- the same code that computes
     * the live entity's max health -- so the column and the entity cannot disagree.
     */
    public List<SimTargetSpec> enumerateTargets(SimScope scope) {
        final List<SimTargetSpec> targets = new ArrayList<>();
        for (Role role : Role.values()) {
            targets.add(target(role, SimEquipment.NO_ARMOR));
            if (scope.isArmorSets()) {
                targets.add(target(role, SimEquipment.ROLE_ARMOR));
            }
        }
        return List.copyOf(targets);
    }

    private SimTargetSpec target(Role role, String armorSetId) {
        return new SimTargetSpec(role.name(), armorSetId, equipment.durability(role, armorSetId), List.of(), 0);
    }

    // -------------------------------------------------------------------------
    // Skill axis
    // -------------------------------------------------------------------------

    /** A bare role on each weapon of the axis. */
    private void enumerateSkilless(Role role, SimScope scope, List<SimBuildSpec> out) {
        for (WeaponOption weapon : weaponsFor(scope, null)) {
            out.add(build(role, weapon, List.of()));
        }
    }

    /**
     * One filled slot per build, swept over every skill of the role and every allocated level.
     *
     * <p>This is the tier that produces a readable per-skill strength curve. A full build folds
     * several skills' contributions into one DPS figure and there is no way to attribute it
     * afterwards, so isolating the skill is not a simplification of the full sweep -- it answers a
     * question the full sweep cannot.
     */
    private void enumerateSingleSkill(Role role, SimScope scope, List<SimBuildSpec> out, int maxBuilds) {
        final int budget = buildPoints();
        for (Map.Entry<SkillType, List<Skill>> entry : skillsByType(role).entrySet()) {
            for (Skill skill : entry.getValue()) {
                final int maxLevel = Math.min(skill.getMaxLevel(), budget);
                for (int level = 1; level <= maxLevel; level++) {
                    final List<SimSkillAllocation> allocation = List.of(allocation(skill, level));
                    for (WeaponOption weapon : weaponsFor(scope, entry.getKey())) {
                        out.add(build(role, weapon, allocation));
                    }
                }
                checkCap(out.size(), maxBuilds, scope);
            }
        }
    }

    /**
     * Every combination of slot occupancy and budget-feasible level vector.
     *
     * <p>Slots are filled recursively and the remaining budget is carried down, so an allocation
     * that cannot fit is never generated in the first place. A slot may also be left empty, which
     * is what makes partial builds -- the common case for a real player, who rarely spends all
     * twelve points on the maximum number of slots -- part of the space.
     */
    private void enumerateBudgetVectors(Role role, SimScope scope, List<SimBuildSpec> out, int maxBuilds) {
        final List<SkillType> slots = List.of(SkillType.values());
        final Map<SkillType, List<Skill>> byType = skillsByType(role);
        fillSlots(role, scope, byType, slots, 0, buildPoints(), new ArrayList<>(), out, maxBuilds);
    }

    private void fillSlots(Role role,
                           SimScope scope,
                           Map<SkillType, List<Skill>> byType,
                           List<SkillType> slots,
                           int slotIndex,
                           int remainingPoints,
                           List<SimSkillAllocation> chosen,
                           List<SimBuildSpec> out,
                           int maxBuilds) {
        if (slotIndex == slots.size()) {
            // The empty build is emitted by the skill-less tier and would be duplicated here once
            // per role, so only allocations that actually spend a point become a row.
            if (!chosen.isEmpty()) {
                final SkillType boostable = boostableSlot(chosen);
                for (WeaponOption weapon : weaponsFor(scope, boostable)) {
                    out.add(build(role, weapon, List.copyOf(chosen)));
                }
                checkCap(out.size(), maxBuilds, scope);
            }
            return;
        }

        final SkillType slot = slots.get(slotIndex);
        // Leaving the slot empty.
        fillSlots(role, scope, byType, slots, slotIndex + 1, remainingPoints, chosen, out, maxBuilds);

        for (Skill skill : byType.getOrDefault(slot, List.of())) {
            final int maxLevel = Math.min(skill.getMaxLevel(), remainingPoints);
            for (int level = 1; level <= maxLevel; level++) {
                chosen.add(allocation(skill, level));
                fillSlots(role, scope, byType, slots, slotIndex + 1, remainingPoints - level,
                        chosen, out, maxBuilds);
                chosen.remove(chosen.size() - 1);
            }
        }
    }

    /**
     * The one slot in a build that a booster weapon could raise, or null if none can.
     *
     * <p>A player holds a single weapon, so at most one slot is boosted at a time; when a build
     * fills more than one boostable slot the booster variants would differ only in which slot got
     * the {@code +1}, and the weapon axis already distinguishes them by key. The first boostable
     * slot is enough to decide whether a booster variant is worth enumerating at all.
     */
    @Nullable
    private static SkillType boostableSlot(List<SimSkillAllocation> chosen) {
        for (SimSkillAllocation allocation : chosen) {
            final SkillType slot = SkillType.valueOf(allocation.slot());
            if (slot == SkillType.SWORD || slot == SkillType.AXE || slot == SkillType.BOW) {
                return slot;
            }
        }
        return null;
    }

    /**
     * The role's enabled skills grouped by slot, ordered by name so a sweep enumerates the same
     * space twice. Disabled skills are dropped because {@code SkillListener} will not run them --
     * a build containing one would measure as if the slot were empty while claiming otherwise.
     */
    private Map<SkillType, List<Skill>> skillsByType(Role role) {
        final Map<SkillType, List<Skill>> byType = new EnumMap<>(SkillType.class);
        for (Skill skill : skillManager.getSkillsForRole(role)) {
            if (!skill.isEnabled()) {
                continue;
            }
            byType.computeIfAbsent(skill.getType(), ignored -> new ArrayList<>()).add(skill);
        }
        byType.values().forEach(skills -> skills.sort(Comparator.comparing(Skill::getName)));
        return byType;
    }

    /**
     * {@code effectiveLevel} is seeded with the allocated level and overwritten by the orchestrator
     * once the combatant is equipped and it can be read back through the real accessor. It is never
     * derived here: {@code SkillListener.getLevel} adds the booster {@code +1} and any
     * {@code SkillBoostEffect} amplifier without re-clamping to {@code maxLevel}, and reproducing
     * that arithmetic is exactly the drift this project removes.
     */
    private static SimSkillAllocation allocation(Skill skill, int level) {
        return new SimSkillAllocation(skill.getName(), skill.getType().name(), level, level);
    }

    // -------------------------------------------------------------------------
    // Weapon axis
    // -------------------------------------------------------------------------

    /**
     * The weapons a build occupying {@code slot} is measured on.
     *
     * <p>For the booster tier a booster variant is only emitted when the weapon can serve the
     * build's slot, because {@code Skill.getLevel} requires {@code isHolding(player, type)} as well
     * as {@code hasBooster}: a booster axe leaves a sword skill at its allocated level, so that
     * build would be a duplicate recorded under a different weapon key.
     */
    private List<WeaponOption> weaponsFor(SimScope scope, @Nullable SkillType slot) {
        return switch (scope.getWeaponAxis()) {
            case ROLE_DEFAULT -> List.of(equipment.defaultWeapon());
            case ALL_MELEE -> equipment.meleeWeapons();
            case DEFAULT_AND_BOOSTER -> {
                final List<WeaponOption> weapons = new ArrayList<>();
                weapons.add(equipment.defaultWeapon());
                if (slot != null) {
                    for (WeaponOption booster : equipment.boosterWeapons()) {
                        if (equipment.skillTypeOf(booster.key()) == slot) {
                            weapons.add(booster);
                        }
                    }
                }
                yield List.copyOf(weapons);
            }
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SimBuildSpec build(Role role, WeaponOption weapon, List<SimSkillAllocation> skills) {
        int points = 0;
        for (SimSkillAllocation allocation : skills) {
            points += allocation.allocatedLevel();
        }
        // The attacker never wears armour: it is effective HP, so it changes how long the attacker
        // survives and nothing about the damage it deals, which is what a sim_result row measures.
        return new SimBuildSpec(role.name(),
                weapon.key(),
                SimEquipment.NO_ARMOR,
                List.of(),
                skills,
                points,
                weapon.booster(),
                fingerprint(role, weapon, skills));
    }

    /**
     * The build point budget, read from a real {@link RoleBuild} rather than written as a literal,
     * so a change to {@code RoleBuild.points} reaches the enumerator without an edit here.
     */
    private static int buildPoints() {
        return new RoleBuild(0L, UUID.nameUUIDFromBytes(new byte[0]), Role.DEFAULT, 0).getPoints();
    }

    private static void checkCap(int count, int maxBuilds, SimScope scope) {
        if (count > maxBuilds) {
            throw new IllegalStateException("Scope " + scope + " enumerates more than " + maxBuilds
                    + " builds. Narrow the scope or raise champions.simulation.maxBuilds -- a sweep is"
                    + " refused rather than truncated, because a prefix of the enumeration is a biased"
                    + " sample and nothing on the row would say so.");
        }
    }

    /**
     * Stable hash over the configuration a build is identified by, so the same build can be found
     * across runs and joined to live per-build player data.
     *
     * <p>Hashed from a canonical delimited string rather than from record identity: the inputs grow
     * as the catalog does, and a fingerprint has to stay comparable across engine versions for the
     * patch-diff dashboard to work at all. Only the <em>allocated</em> level is hashed -- the
     * effective level is an outcome of the run, not part of what a player configured -- but the
     * weapon key is, which is what keeps a booster run distinguishable from a plain one.
     */
    private static String fingerprint(Role role, WeaponOption weapon, List<SimSkillAllocation> skills) {
        final StringBuilder canonical = new StringBuilder(role.name()).append('|').append(weapon.key());
        // Sorted so two builds that differ only in enumeration order hash identically.
        skills.stream()
                .sorted(Comparator.comparing(SimSkillAllocation::slot).thenComparing(SimSkillAllocation::skillName))
                .forEach(allocation -> canonical.append('|')
                        .append(allocation.slot()).append(':')
                        .append(allocation.skillName()).append(':')
                        .append(allocation.allocatedLevel()));
        return sha256(canonical.toString());
    }

    private static String sha256(String input) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to fingerprint a build", e);
        }
    }
}
