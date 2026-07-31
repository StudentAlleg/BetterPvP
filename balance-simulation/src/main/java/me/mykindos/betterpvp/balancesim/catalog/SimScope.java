package me.mykindos.betterpvp.balancesim.catalog;

import lombok.Getter;

import java.util.Locale;
import java.util.Optional;

/**
 * How much of the permutation space a sweep covers.
 *
 * <p>This is the answer design open question 6 asks for. Duels run in <em>real time</em> -- a
 * three-second fight costs three seconds of wall clock, and concurrency is bounded by how many
 * fake players the server can carry at once -- so the sweep size is a hard budget, not a
 * preference. Counting the space before building the orchestrator (as the design asks) gives:
 *
 * <ul>
 *   <li>~150 enabled skills across six roles, each with 1..{@code maxLevel} allocations;</li>
 *   <li>~27 melee weapons in {@code ItemRegistry};</li>
 *   <li>six slots per build, so with the 12-point budget a single role still admits thousands of
 *       slot combinations before levels are varied at all.</li>
 * </ul>
 *
 * The unrestricted product is in the millions of matchups per role, which at real-time duel cost
 * is days of wall clock. So the sweep is tiered, and the tier is chosen at invocation rather than
 * inferred: an admin asking for {@link #FULL} on a box that cannot finish it should be told the
 * count, not quietly given a biased prefix of it.
 *
 * <p>The tiers are cumulative in coverage, and each is useful on its own:
 * <ul>
 *   <li>{@link #MELEE} -- the phase 1 measurement, kept as a regression baseline: if plain melee
 *       TTK moves between runs, something changed underneath the simulator rather than in a
 *       build.</li>
 *   <li>{@link #WEAPONS} -- the weapon axis alone, with no skills to confound it. This is what
 *       answers "how much does this weapon's damage stat actually buy".</li>
 *   <li>{@link #RUNES} -- one socketed rune at a time, on the default weapon and against armoured
 *       targets. Same reasoning as {@link #SKILLS}: a rune's contribution cannot be recovered from a
 *       build that carries several, and it is a small enough axis to sweep exhaustively.</li>
 *   <li>{@link #SKILLS} -- one skill at a time over its full level range. Isolating a single
 *       skill is what makes a per-skill strength curve readable; a full build mixes several
 *       skills' contributions into one number and cannot be decomposed after the fact.</li>
 *   <li>{@link #LOADOUT} -- the weapon and rune axes crossed, with the weapon axis deduplicated by
 *       stat profile. Neither {@link #WEAPONS} nor {@link #RUNES} crosses the two, so a rune has only
 *       ever been measured on the default weapon.</li>
 *   <li>{@link #FULL} -- every budget-feasible build. The design's end state, and the one that
 *       lets live per-build player data join straight onto the fingerprint with no interpolation.
 *       Guarded by a count check rather than a truncation.</li>
 *   <li>{@link #BASELINE} -- {@link #FULL} with every lossless reduction applied, which is what makes
 *       it finishable and therefore what makes it a baseline. A stopped {@code FULL} is a sample; a
 *       finished {@code BASELINE} is the thing later runs are diffed against.</li>
 * </ul>
 *
 * <h2>Reductions</h2>
 * The last three tiers apply reductions that the earlier ones do not, each of which is a claim that
 * two permutations are <em>the same measurement</em> rather than two similar ones. Every claim is
 * argued where it is implemented, and every one records what it folded onto the row it kept, so a
 * reduction can be disagreed with from the data rather than only from the source.
 */
@Getter
public enum SimScope {

    /** Role x role-default weapon, no skills, no armour, no runes. Phase 1 parity. */
    MELEE(WeaponAxis.ROLE_DEFAULT, SkillAxis.NONE, RuneAxis.NONE, false, false),

    /** Role x every melee weapon in the registry, no skills. */
    WEAPONS(WeaponAxis.ALL_MELEE, SkillAxis.NONE, RuneAxis.NONE, false, false),

    /** Role x default weapon x one rune at a time, no skills, vs armoured targets. */
    RUNES(WeaponAxis.ROLE_DEFAULT, SkillAxis.NONE, RuneAxis.ONE_AT_A_TIME, true, false),

    /** Role x one skill at a time x level, on the default and booster weapons, vs armoured targets. */
    SKILLS(WeaponAxis.DEFAULT_AND_BOOSTER, SkillAxis.ONE_AT_A_TIME, RuneAxis.NONE, true, false),

    /**
     * Every distinct weapon x every rune it accepts, no skills, reduced targets.
     *
     * <p>The loadout half of a baseline. {@link #WEAPONS} and {@link #RUNES} answer the same question
     * separately and neither crosses the two, so a rune's contribution has only ever been measured on
     * the default weapon -- which is the wrong baseline for a rune whose value scales with the weapon
     * it is socketed into. Crossing them is affordable precisely because both axes are reduced: the
     * weapon axis is deduped by profile, and a weapon only enumerates the runes its own
     * {@code canApply} admits.
     */
    LOADOUT(WeaponAxis.DISTINCT_MELEE, SkillAxis.NONE, RuneAxis.ONE_AT_A_TIME, true, true),

    /** Role x every budget-feasible level vector x every melee weapon, vs armoured targets. */
    FULL(WeaponAxis.ALL_MELEE, SkillAxis.BUDGET_VECTORS, RuneAxis.NONE, true, false),

    /**
     * The reduced full sweep: every budget-feasible vector over the audited-relevant skills, on
     * distinct weapon profiles only, against collapsed targets.
     *
     * <p>Same space as {@link #FULL} with every reduction that costs no information applied at once,
     * which is what makes an exhaustive run finishable and therefore what makes it a <em>baseline</em>
     * -- a partial {@code FULL} is not one, however many rows it has. The reductions are independent
     * and each is documented where it is implemented: weapons at
     * {@code SimEquipment.distinctMeleeWeapons}, targets at {@code BalanceCatalog.collapseByDurability},
     * skills at {@link SimSkillFilter#RELEVANT}.
     *
     * <p>Runes are not a term here, deliberately, and this is the one reduction that <em>does</em>
     * cost something. Crossing runes with budget vectors multiplies an already combinatorial space by
     * the rune count, and the product is not enumerable at any cap. {@link #LOADOUT} covers the
     * weapon-rune cross with no skills; this covers the weapon-skill cross with no runes. A rune that
     * interacts with a specific skill falls in the gap between them, and nothing here will find it.
     */
    BASELINE(WeaponAxis.DISTINCT_MELEE, SkillAxis.BUDGET_VECTORS, RuneAxis.NONE, true, true);

    /**
     * Which weapons a build is measured on.
     *
     * <p>{@link #DEFAULT_AND_BOOSTER} exists because the booster {@code +1} is not an independent
     * axis: {@code SkillListener.getLevel} raises a SWORD/AXE/BOW skill's effective level by one
     * when the held weapon is a booster, and does <em>not</em> re-clamp to {@code maxLevel}. It is
     * therefore a property of the weapon, and the only way to measure it without re-deriving it in
     * the simulator is to equip a real booster and read the level back through the real accessor.
     */
    public enum WeaponAxis {
        /** Only what {@code RoleManager.equipWeapons} hands out -- the {@code IRON_SWORD} fallback. */
        ROLE_DEFAULT,
        /** The default plus every melee weapon whose material {@code SkillWeapons.isBooster} accepts. */
        DEFAULT_AND_BOOSTER,
        /** Every {@code WeaponItem} in the registry carrying {@code Group.MELEE}. */
        ALL_MELEE,
        /**
         * One representative per distinct {@code SimWeaponProfile}, with the weapons it stands for
         * recorded on the build as aliases.
         *
         * <p>Not a sample of {@link #ALL_MELEE} but a deduplication of it: two weapons sharing a
         * profile produce the same duel by construction, so measuring both measures the same fight
         * twice. The weapon axis multiplies every other axis, so this is the reduction that decides
         * whether an exhaustive sweep is finishable at all.
         */
        DISTINCT_MELEE
    }

    /** How the skill slots of a build are filled. */
    public enum SkillAxis {
        /** No skills at all; a bare role. */
        NONE,
        /** Exactly one filled slot per build, swept over every skill and every allocated level. */
        ONE_AT_A_TIME,
        /** Every combination of slot occupancy and level vector whose total spend fits the budget. */
        BUDGET_VECTORS
    }

    /**
     * How runes are socketed into the build's weapon.
     *
     * <p>Deliberately not a term in {@link #FULL}. Runes multiply the space the same way skills do and
     * for the same reason -- around twenty registered runes across several sockets -- so folding them
     * into the full sweep would make an already real-time-bound space unenumerable. And a full build
     * cannot be decomposed after the fact into "how much of this DPS was the rune", which is the only
     * question the rune axis is asked: hence {@link #ONE_AT_A_TIME}, on the same reasoning that gives
     * skills their own tier.
     */
    public enum RuneAxis {
        /** No runes; the weapon as the registry ships it. */
        NONE,
        /** One socketed rune per build, swept over every rune the weapon accepts, plus a bare baseline. */
        ONE_AT_A_TIME
    }

    private final WeaponAxis weaponAxis;
    private final SkillAxis skillAxis;
    private final RuneAxis runeAxis;

    /**
     * Whether targets are also enumerated wearing their role's armour set. Armour is effective HP
     * rather than mitigation (design open question 1), so this changes the defender's durability
     * and nothing else about how a result reads.
     */
    private final boolean armorSets;

    /**
     * Whether unarmoured, skill-less defenders of equal health are measured once and shared.
     *
     * <p>Off for every tier that predates it, so an existing dashboard keeps the target list it was
     * built against. The reduction is only applied where its premise holds regardless of this flag --
     * see {@code BalanceCatalog.collapseByDurability}, which declines it for a {@code MUTUAL} sweep.
     */
    private final boolean collapseTargets;

    SimScope(WeaponAxis weaponAxis,
             SkillAxis skillAxis,
             RuneAxis runeAxis,
             boolean armorSets,
             boolean collapseTargets) {
        this.weaponAxis = weaponAxis;
        this.skillAxis = skillAxis;
        this.runeAxis = runeAxis;
        this.armorSets = armorSets;
        this.collapseTargets = collapseTargets;
    }

    /**
     * Whether a sweep of this tier can support a skill relevance audit.
     *
     * <p>The audit subtracts a skill-less baseline measured in the same run, and only the
     * one-skill-at-a-time axis emits one: {@code BUDGET_VECTORS} skips the empty allocation (the
     * skill-less tier owns it) and every build it does emit carries several skills, which cannot be
     * decomposed. So {@link #BASELINE} and {@link #FULL} are exhaustive and still not auditable, which
     * is worth stating because it is the opposite of what "more coverage" suggests.
     *
     * <p>Asked as a capability rather than tested as an enum equality, so a tier added with a
     * one-at-a-time skill axis is auditable without anyone remembering to widen a condition.
     */
    public boolean isAuditable() {
        return skillAxis == SkillAxis.ONE_AT_A_TIME;
    }

    /** Parses a command argument, case-insensitively. Empty when the name is not a tier. */
    public static Optional<SimScope> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
