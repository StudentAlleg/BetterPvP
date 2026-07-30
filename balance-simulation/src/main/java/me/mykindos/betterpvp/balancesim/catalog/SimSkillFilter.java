package me.mykindos.betterpvp.balancesim.catalog;

import me.mykindos.betterpvp.balancesim.engine.ActivationArchetype;
import me.mykindos.betterpvp.champions.champions.skills.Skill;
import me.mykindos.betterpvp.champions.champions.skills.types.OffensiveSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.PassiveSkill;

import java.util.Locale;
import java.util.Optional;

/**
 * Which skills are allowed into the permutation space.
 *
 * <p>The skill axis is the dominant term in the sweep size, and it is combinatorial: skills occupy
 * six independent slots, so halving the pool does not halve the space, it divides it by roughly
 * 2^6. Enumerating {@code FULL} over all 147 enabled skills produces about 60 million builds before
 * the weapon axis multiplies it again -- not a sweep that is slow, a sweep that cannot be built in
 * memory at all. Filtering the pool is the only lever with that kind of leverage.
 *
 * <p>The filters below are not all the same kind of claim, and the difference matters:
 *
 * <ul>
 *   <li>{@link #EXERCISABLE} is a statement about <em>this engine</em>: whether an input exists that
 *       can make the skill fire at all. Phase 2 could exercise nothing but passives, because nothing
 *       synthesised an input. Phase 3's rotation policy covers every archetype except the bow ones,
 *       which need a real arrow through the vanilla bow path and a ranged engagement rather than an
 *       input ({@link ActivationArchetype#BOW}). A skill this filter rejects does not measure as
 *       absent -- it measures as an <em>empty slot</em> while its {@code sim_build} row claims a
 *       skill, so excluding it is not an approximation; including it manufactures duplicate rows
 *       under distinct fingerprints.</li>
 *   <li>{@link #OFFENSIVE} and {@link #OFFENSIVE_PASSIVES} additionally trust the skill's own
 *       {@code OffensiveSkill} marker. A one-way {@code sim_result} row measures the attacker's damage
 *       against a defender that never swings back, so a skill that only reduces damage taken, grants
 *       movement or changes knockback cannot move any column on it. That is true of the mechanics as
 *       written, but it is read off a marker interface the skill author maintains, so an author's
 *       omission silently drops a skill that does matter. {@link #OFFENSIVE} is the default because
 *       the alternative is a space that cannot be enumerated, and the exclusion count is logged so the
 *       omission is visible rather than assumed. Note that under the {@code MUTUAL} scenario the
 *       premise weakens -- a defensive skill does move the attacker's TTK once the defender fights
 *       back -- so a mutual sweep of defensive builds wants {@link #EXERCISABLE}.</li>
 *   <li>{@link #PASSIVES} and {@link #OFFENSIVE_PASSIVES} are the phase 2 filters, kept so a phase 2
 *       run can be reproduced exactly. They are the only way to ask for "melee and passives only" now
 *       that actives are drivable.</li>
 * </ul>
 *
 * <p>The filter is part of {@code config_hash} and the scenario JSON. Two runs at the same
 * scope with different filters cover different spaces, and nothing else on the row would say so.
 */
public enum SimSkillFilter {

    /**
     * Every enabled skill, including the bow archetypes this engine cannot drive. Those measure as an
     * empty slot, so this is for deliberately measuring the size of that gap rather than for balance
     * work.
     */
    ALL,

    /** Every enabled skill the engine has an input for -- everything except the bow archetypes. */
    EXERCISABLE,

    /** Exercisable skills the skill declares as offensive. The default. */
    OFFENSIVE,

    /** Every passive, including those that only affect the attacker's own durability or movement. */
    PASSIVES,

    /** Passives the skill declares as offensive. The phase 2 default, kept for reproducing those runs. */
    OFFENSIVE_PASSIVES;

    /** Whether a sweep under this filter should enumerate builds containing {@code skill}. */
    public boolean admits(Skill skill) {
        return switch (this) {
            case ALL -> true;
            case EXERCISABLE -> exercisable(skill);
            case OFFENSIVE -> exercisable(skill) && skill instanceof OffensiveSkill;
            case PASSIVES -> skill instanceof PassiveSkill;
            case OFFENSIVE_PASSIVES -> skill instanceof PassiveSkill && skill instanceof OffensiveSkill;
        };
    }

    /**
     * Whether the engine has an input that can make this skill fire.
     *
     * <p>Read from the archetype rather than from a list of skill names, so a new skill is admitted or
     * excluded on the strength of the type it actually extends.
     */
    private static boolean exercisable(Skill skill) {
        return ActivationArchetype.of(skill).isSupported();
    }

    /**
     * Parses a config value, falling back to {@link #OFFENSIVE} for anything unrecognised.
     *
     * <p>A typo defaults to a narrow filter rather than the widest: the wide end of this
     * setting is a sweep that exhausts the heap during enumeration, which is a worse failure than
     * a run that covers less than the admin intended and says so in its log line.
     */
    public static SimSkillFilter parse(String raw) {
        return raw == null || raw.isBlank()
                ? OFFENSIVE
                : Optional.ofNullable(lookup(raw.trim().toUpperCase(Locale.ROOT))).orElse(OFFENSIVE);
    }

    private static SimSkillFilter lookup(String name) {
        for (SimSkillFilter filter : values()) {
            if (filter.name().equals(name)) {
                return filter;
            }
        }
        return null;
    }
}
