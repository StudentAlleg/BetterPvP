package me.mykindos.betterpvp.balancesim.catalog;

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
 * <p>The two filters below are not the same kind of claim, and the difference matters:
 *
 * <ul>
 *   <li>{@link #PASSIVES} is a statement about <em>this engine</em>. Nothing in the orchestrator
 *       ever activates a skill -- {@code Duel.advance} swings and does nothing else, which is what
 *       {@code "actives": false} in the run's scenario records. A skill that must be triggered
 *       therefore cannot fire, and a build containing one measures exactly as if the slot were
 *       empty while its row claims otherwise. Excluding those is not an approximation; including
 *       them manufactures duplicate rows under distinct fingerprints. This constraint lifts when
 *       phase 3 adds a rotation policy.</li>
 *   <li>{@link #OFFENSIVE_PASSIVES} additionally trusts the skill's own {@code OffensiveSkill}
 *       marker. A {@code sim_result} row measures one direction -- the attacker's damage against a
 *       defender that never swings back -- so a passive that only reduces damage taken, grants
 *       movement or changes knockback cannot move any column on it. That is true of the mechanics
 *       as written, but it is read off a marker interface the skill author maintains, so an
 *       author's omission silently drops a skill that does matter. It is the default because the
 *       alternative is a space that cannot be enumerated, and the exclusion count is logged so the
 *       omission is visible rather than assumed.</li>
 * </ul>
 *
 * <p>The active filter is part of {@code config_hash} and the scenario JSON. Two runs at the same
 * scope with different filters cover different spaces, and nothing else on the row would say so.
 */
public enum SimSkillFilter {

    /**
     * Every enabled skill. Correct only for an engine that can activate skills; until then it
     * enumerates builds whose measured behaviour is that of an empty slot.
     */
    ALL,

    /** Every passive, including those that only affect the attacker's own durability or movement. */
    PASSIVES,

    /** Passives the skill declares as offensive -- the ones that can move a column on the row. */
    OFFENSIVE_PASSIVES;

    /** Whether a sweep under this filter should enumerate builds containing {@code skill}. */
    public boolean admits(Skill skill) {
        return switch (this) {
            case ALL -> true;
            case PASSIVES -> skill instanceof PassiveSkill;
            case OFFENSIVE_PASSIVES -> skill instanceof PassiveSkill && skill instanceof OffensiveSkill;
        };
    }

    /**
     * Parses a config value, falling back to {@link #OFFENSIVE_PASSIVES} for anything unrecognised.
     *
     * <p>A typo defaults to the narrowest filter rather than the widest: the wide end of this
     * setting is a sweep that exhausts the heap during enumeration, which is a worse failure than
     * a run that covers less than the admin intended and says so in its log line.
     */
    public static SimSkillFilter parse(String raw) {
        return raw == null || raw.isBlank()
                ? OFFENSIVE_PASSIVES
                : Optional.ofNullable(lookup(raw.trim().toUpperCase(Locale.ROOT))).orElse(OFFENSIVE_PASSIVES);
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
