package me.mykindos.betterpvp.balancesim.catalog;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.balancesim.SimulationGate;
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
import java.util.LinkedHashMap;
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
    private final SimulationGate gate;
    private final ChampionsSkillManager skillManager;

    @Inject
    public BalanceCatalog(SimEquipment equipment, SimulationGate gate) {
        this.equipment = equipment;
        this.gate = gate;
        // Pulled from Champions' injector rather than injected, for the same reason SimClientFactory
        // does it: this plugin's injector is a sibling of Champions' under Core, and asking Guice
        // for a Champions-scoped singleton here would construct a second Champions.
        this.skillManager = JavaPlugin.getPlugin(Champions.class).getInjector()
                .getInstance(ChampionsSkillManager.class);
    }

    /**
     * Enumerates every attacker build in scope, ordered so that any prefix of the result covers
     * every role.
     *
     * @param scope     which axes are varied
     * @param maxBuilds refuse rather than truncate above this many builds
     * @throws IllegalStateException if the scope exceeds {@code maxBuilds}
     */
    public List<SimBuildSpec> enumerateBuilds(SimScope scope, int maxBuilds) {
        final List<SimBuildSpec> builds = new ArrayList<>();
        int excluded = 0;
        for (Role role : Role.values()) {
            excluded += switch (scope.getSkillAxis()) {
                case NONE -> {
                    enumerateSkilless(role, scope, builds);
                    yield 0;
                }
                case ONE_AT_A_TIME -> enumerateSingleSkill(role, scope, builds, maxBuilds);
                case BUDGET_VECTORS -> enumerateBudgetVectors(role, scope, builds, maxBuilds);
            };
            checkCap(builds.size(), maxBuilds, scope);
        }
        // The exclusion count is logged even when it is zero: "0 skills excluded" is the only thing
        // that distinguishes a genuinely exhaustive sweep from one the filter narrowed, and that
        // distinction is not recoverable from the rows afterwards.
        log.info("Catalog scope {} enumerated {} builds under skill filter {} ({} enabled skills"
                        + " excluded as unexercisable by this engine)",
                scope, builds.size(), SimSkillFilter.parse(gate.getSkillFilter()), excluded).submit();
        return interleaveByRole(builds);
    }

    /**
     * Reorders a role-major enumeration into a round robin over roles, so the first <em>n</em> builds
     * are a slice of all six rather than all of the first.
     *
     * <p>Applied after enumeration rather than by enumerating differently, so the {@code maxBuilds}
     * cap keeps being checked against a running total inside the recursion and nothing about which
     * builds exist changes -- only the order they are handed to the orchestrator in.
     *
     * <p>The order is load-bearing because a {@code FULL} sweep is not expected to finish. It plans
     * upwards of a hundred thousand duels at real-time cost, and the way it is actually used is to
     * run it for as long as there is time and stop it. Run 140 is what that costs role-major: it was
     * stopped at 8,990 of 105,408 duels, and every one of those rows was {@code ASSASSIN} -- not a
     * thin sample of the game but a complete answer about an eighth of it, with nothing on the row or
     * in {@code sim_run} to say so. Interleaving makes an early stop what it looks like: a uniformly
     * thinner version of the sweep that was asked for.
     *
     * <p>Roles are kept in enumeration order, and each role's builds keep theirs, so the enumeration
     * stays reproducible -- two runs of the same scope still produce the same sequence.
     */
    private static List<SimBuildSpec> interleaveByRole(List<SimBuildSpec> builds) {
        final Map<String, List<SimBuildSpec>> byRole = new LinkedHashMap<>();
        for (SimBuildSpec build : builds) {
            byRole.computeIfAbsent(build.role(), role -> new ArrayList<>()).add(build);
        }
        if (byRole.size() < 2) {
            return List.copyOf(builds);
        }

        final List<List<SimBuildSpec>> perRole = List.copyOf(byRole.values());
        final List<SimBuildSpec> interleaved = new ArrayList<>(builds.size());
        for (int index = 0; interleaved.size() < builds.size(); index++) {
            for (List<SimBuildSpec> roleBuilds : perRole) {
                if (index < roleBuilds.size()) {
                    interleaved.add(roleBuilds.get(index));
                }
            }
        }
        return List.copyOf(interleaved);
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

    /** A bare role on each weapon of the axis, and each rune set of the axis. */
    private void enumerateSkilless(Role role, SimScope scope, List<SimBuildSpec> out) {
        for (WeaponOption weapon : weaponsFor(scope, null)) {
            for (List<String> runes : runesFor(scope, weapon)) {
                out.add(build(role, weapon, runes, List.of()));
            }
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
    private int enumerateSingleSkill(Role role, SimScope scope, List<SimBuildSpec> out, int maxBuilds) {
        final int budget = buildPoints();
        final SkillPool pool = skillsByType(role);
        for (Map.Entry<SkillType, List<Skill>> entry : pool.byType().entrySet()) {
            for (Skill skill : entry.getValue()) {
                final int maxLevel = Math.min(skill.getMaxLevel(), budget);
                for (int level = 1; level <= maxLevel; level++) {
                    final List<SimSkillAllocation> allocation = List.of(allocation(skill, level));
                    for (WeaponOption weapon : weaponsFor(scope, entry.getKey())) {
                        for (List<String> runes : runesFor(scope, weapon)) {
                            out.add(build(role, weapon, runes, allocation));
                        }
                    }
                }
                checkCap(out.size(), maxBuilds, scope);
            }
        }
        return pool.excluded();
    }

    /**
     * Every combination of slot occupancy and budget-feasible level vector.
     *
     * <p>Slots are filled recursively and the remaining budget is carried down, so an allocation
     * that cannot fit is never generated in the first place. A slot may also be left empty, which
     * is what makes partial builds -- the common case for a real player, who rarely spends all
     * twelve points on the maximum number of slots -- part of the space.
     */
    private int enumerateBudgetVectors(Role role, SimScope scope, List<SimBuildSpec> out, int maxBuilds) {
        final List<SkillType> slots = List.of(SkillType.values());
        final SkillPool pool = skillsByType(role);
        fillSlots(role, scope, pool.byType(), slots, 0, buildPoints(), new ArrayList<>(), out, maxBuilds);
        return pool.excluded();
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
                    for (List<String> runes : runesFor(scope, weapon)) {
                        out.add(build(role, weapon, runes, List.copyOf(chosen)));
                    }
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
     * The role's admissible skills grouped by slot, ordered by name so a sweep enumerates the same
     * space twice.
     *
     * <p>Two exclusions, for the same reason. Disabled skills are dropped because
     * {@code SkillListener} will not run them, and skills the configured {@link SimSkillFilter}
     * rejects are dropped because this engine cannot exercise them -- in both cases a build
     * containing one measures exactly as if the slot were empty while its row claims a skill. The
     * filter is also what makes {@code FULL} enumerable at all; see {@link SimSkillFilter}.
     *
     * <p>Exclusions are counted rather than merely applied, so the run's log line can state how
     * much of the skill pool the sweep did not cover. A sweep quietly covering an eighth of the
     * skills would otherwise read as exhaustive.
     */
    private SkillPool skillsByType(Role role) {
        final SimSkillFilter filter = SimSkillFilter.parse(gate.getSkillFilter());
        final Map<SkillType, List<Skill>> byType = new EnumMap<>(SkillType.class);
        int excluded = 0;
        for (Skill skill : skillManager.getSkillsForRole(role)) {
            if (!skill.isEnabled()) {
                continue;
            }
            if (!filter.admits(skill)) {
                excluded++;
                continue;
            }
            byType.computeIfAbsent(skill.getType(), ignored -> new ArrayList<>()).add(skill);
        }
        byType.values().forEach(skills -> skills.sort(Comparator.comparing(Skill::getName)));
        return new SkillPool(byType, excluded);
    }

    /**
     * A role's admissible skills and how many the filter turned away.
     *
     * @param byType   admissible skills grouped by slot
     * @param excluded enabled skills this engine cannot exercise, carried so the count reaches the
     *                 log rather than being inferred from a build total nobody can check
     */
    private record SkillPool(Map<SkillType, List<Skill>> byType, int excluded) {
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
    // Rune axis
    // -------------------------------------------------------------------------

    /**
     * The rune sets a build on {@code weapon} is measured with.
     *
     * <p>{@code ONE_AT_A_TIME} yields an empty set first and then one set per applicable rune, so the
     * baseline the rune's contribution is read against is a row of the same sweep rather than a figure
     * carried over from a {@code MELEE} run at a different {@code config_hash}.
     *
     * <p>Which runes a weapon accepts is the rune's own {@code canApply}, resolved in
     * {@link SimEquipment}. A weapon that accepts none yields only the baseline, which is why a
     * {@code RUNES} sweep of a role whose default weapon has no compatible runes is a small sweep rather
     * than an error.
     */
    private List<List<String>> runesFor(SimScope scope, WeaponOption weapon) {
        if (scope.getRuneAxis() == SimScope.RuneAxis.NONE) {
            return NO_RUNES;
        }
        final List<List<String>> sets = new ArrayList<>();
        sets.add(List.of());
        for (SimEquipment.RuneOption rune : equipment.weaponRunes(weapon.key())) {
            sets.add(List.of(rune.key()));
        }
        return List.copyOf(sets);
    }

    /** The single "no runes at all" rune set, hoisted so the common case allocates nothing. */
    private static final List<List<String>> NO_RUNES = List.of(List.of());

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SimBuildSpec build(Role role,
                               WeaponOption weapon,
                               List<String> runeKeys,
                               List<SimSkillAllocation> skills) {
        int points = 0;
        for (SimSkillAllocation allocation : skills) {
            points += allocation.allocatedLevel();
        }
        // The attacker never wears armour: it is effective HP, so it changes how long the attacker
        // survives and nothing about the damage it deals, which is what a sim_result row measures.
        return new SimBuildSpec(role.name(),
                weapon.key(),
                SimEquipment.NO_ARMOR,
                runeKeys,
                skills,
                points,
                weapon.booster(),
                fingerprint(role, weapon, runeKeys, skills));
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
                    + " builds. Narrow the scope or champions.simulation.skillFilter -- a sweep is"
                    + " refused rather than truncated, because a prefix of the enumeration is a biased"
                    + " sample and nothing on the row would say so. Raising maxBuilds is rarely the"
                    + " answer for FULL: the skill axis is combinatorial across six slots, so the"
                    + " unfiltered space is tens of millions of builds and exhausts the heap while"
                    + " being enumerated, whatever the cap is set to.");
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
     *
     * <p>Runes are hashed sorted, for the same reason skills are: a socket order is not part of a
     * build's identity, and two rows that differ only in it must be the same build across runs.
     */
    private static String fingerprint(Role role,
                                      WeaponOption weapon,
                                      List<String> runeKeys,
                                      List<SimSkillAllocation> skills) {
        final StringBuilder canonical = new StringBuilder(role.name()).append('|').append(weapon.key());
        runeKeys.stream().sorted().forEach(rune -> canonical.append('|').append(rune));
        // Sorted so two builds that differ only in enumeration order hash identically.
        skills.stream()
                .sorted(Comparator.comparing(SimSkillAllocation::slot).thenComparing(SimSkillAllocation::skillName))
                .forEach(allocation -> canonical.append('|')
                        .append(allocation.slot()).append(':')
                        .append(allocation.skillName()).append(':')
                        .append(allocation.allocatedLevel()));
        return sha256(canonical.toString());
    }

    /**
     * One digest per thread, reset between uses.
     *
     * <p>{@code MessageDigest.getInstance} walks the JCA provider list on every call, which is
     * cheap once and ruinous per build: with the enumeration running into the millions it showed up
     * as the top frame in most samples of a stalled sweep, inside a security-provider lookup rather
     * than in any hashing. A digest is not thread safe, hence per thread rather than shared.
     */
    private static final ThreadLocal<MessageDigest> DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required to fingerprint a build", e);
        }
    });

    private static String sha256(String input) {
        final MessageDigest digest = DIGEST.get();
        digest.reset();
        final byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
        final StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
