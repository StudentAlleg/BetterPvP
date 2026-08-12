package me.mykindos.betterpvp.balancesim.catalog;

import me.mykindos.betterpvp.balancesim.engine.ActivationArchetype;
import me.mykindos.betterpvp.champions.champions.skills.Skill;
import me.mykindos.betterpvp.champions.champions.skills.types.OffensiveSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.PassiveSkill;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
    OFFENSIVE_PASSIVES,

    /**
     * Only the skills a reviewed audit named relevant, read from
     * {@code champions.simulation.relevantSkills}.
     *
     * <p>The strongest available prune, and the only one derived from measurement rather than from a
     * marker interface: {@link #OFFENSIVE} trusts what a skill author declared, whereas this trusts
     * what a sweep observed the skill doing. That closes the gap {@code SimSkillFilter}'s own docs
     * name -- an author's missing {@code OffensiveSkill} marker silently dropping a skill that
     * matters -- because a skill measured relevant is admitted whatever it is labelled.
     *
     * <p>Read from config rather than from the newest audit artifact, which is the whole point.
     * {@code SkillAuditReport} generates a proposal and refuses to apply it, because an
     * {@code INERT} verdict means "moved nothing measurable in this scenario" and not "does nothing":
     * a crowd-control skill lands there correctly and would be excluded forever by an automatic
     * pipeline. Generate, review, commit -- so the list a run sweeps is one a human agreed to.
     *
     * <p><b>This filter goes stale.</b> A skill buffed from zero stays excluded until the audit is
     * re-run and the list re-committed; that is why the artifact records {@code config_hash} and warns
     * about it, and why an empty list is refused rather than treated as "exclude everything".
     */
    RELEVANT;

    /**
     * Whether a sweep under this filter should enumerate builds containing {@code skill}.
     *
     * @param relevant skill names a reviewed audit named relevant. Only consulted by
     *                 {@link #RELEVANT}; every other filter answers from the skill alone
     */
    public boolean admits(Skill skill, Set<String> relevant) {
        return switch (this) {
            case ALL -> true;
            case EXERCISABLE -> exercisable(skill);
            case OFFENSIVE -> exercisable(skill) && skill instanceof OffensiveSkill;
            case PASSIVES -> skill instanceof PassiveSkill;
            case OFFENSIVE_PASSIVES -> skill instanceof PassiveSkill && skill instanceof OffensiveSkill;
            // Still gated on exercisability. A skill the audit called relevant is by definition one
            // this engine drove, so the test is redundant today -- but the list is hand-edited config,
            // and a name typed into it for a bow archetype would otherwise enumerate builds that
            // measure as an empty slot while claiming a skill.
            case RELEVANT -> exercisable(skill) && relevant.contains(skill.getName());
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

    /**
     * Parses the committed relevant-skill list, as it appears in the audit artifact's
     * {@code relevantSkills} block.
     *
     * <p>Takes a list because that is the shape the config is declared in and the shape the audit
     * emits. Entries are still split on commas, so a single entry holding
     * {@code "Backstab, Riposte"} means the same two skills as two entries -- the config loader
     * accepts a YAML sequence or a comma-separated scalar interchangeably, and this must not be the
     * place where those two stop meaning the same thing.
     *
     * <p>Order is preserved and duplicates dropped, so the set reads back in the order it was
     * reviewed in. Names are kept verbatim rather than case-folded, because they are matched against
     * {@code Skill.getName} -- a fuzzy match here would admit a skill nobody put on the list.
     */
    public static Set<String> parseRelevantSkills(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        final Set<String> names = new LinkedHashSet<>();
        raw.stream()
                .filter(Objects::nonNull)
                .flatMap(entry -> Arrays.stream(entry.split(",")))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .forEach(names::add);
        return Set.copyOf(names);
    }

    /**
     * Whether this filter cannot enumerate anything without a relevant-skill list to consult.
     *
     * <p>Asked so an empty list is refused at the top of a run rather than silently producing a sweep
     * with no skills in it. That failure is particularly worth catching because the result is a valid
     * sweep -- skill-less builds against every target, completing normally -- whose rows answer a
     * question nobody asked, and nothing on them says the skill axis was empty.
     */
    public boolean requiresRelevantSkills() {
        return this == RELEVANT;
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
