package me.mykindos.betterpvp.balancesim.audit;

import me.mykindos.betterpvp.balancesim.engine.ActivationArchetype;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * One skill's audit result, carrying enough evidence for a reviewer to disagree with it.
 *
 * <p>The bucket alone would be an assertion. Every field below exists so that a human reading the
 * artifact can see <em>why</em> the classifier decided what it did and overrule it: the deltas it
 * measured, the level and target that produced the strongest one, how many times the skill actually
 * fired, and how many duels stand behind the figure. A verdict from three iterations and a verdict
 * from fifty have the same shape and are not the same claim.
 *
 * @param skillName        the skill, as {@code Skill.getName} reports it -- the key config uses
 * @param role             the role whose slots it occupies
 * @param archetype        how the engine drives it; a {@code PASSIVE} has no button, so its zero
 *                         attempt count is expected rather than evidence of anything
 * @param markers          the marker interfaces it declares, so a relevant skill missing
 *                         {@code DamageSkill} shows up as the champions-side bug it is
 * @param bucket           the verdict
 * @param bestLevel        the allocated level that produced the strongest effect, or null if none did
 * @param bestTtkDelta     largest reduction in time to kill versus baseline, in seconds. Positive
 *                         means the skill killed faster
 * @param bestDpsDelta     largest increase in sustained DPS versus baseline
 * @param energyDelta      largest movement in the energy economy versus baseline, in energy units
 * @param attempts         button presses across every duel the skill appeared in
 * @param successes        uses the real chain allowed through
 * @param declined         uses cancelled for neither cooldown nor energy -- a non-zero count here is
 *                         evidence the engine is driving the skill wrongly
 * @param matchups         how many (level, weapon, target) combinations were measured
 * @param iterations       total duels behind the figures
 * @param effectsLanded    effects the skill applied to anybody, caster included. Separates a skill
 *                         whose post-activation guards rejected every target from one that reached
 *                         its target and applied something TTK, DPS and energy cannot see
 */
public record SkillVerdict(String skillName,
                           String role,
                           ActivationArchetype archetype,
                           String markers,
                           SkillRelevanceBucket bucket,
                           @Nullable Integer bestLevel,
                           double bestTtkDelta,
                           double bestDpsDelta,
                           double energyDelta,
                           int attempts,
                           int successes,
                           int declined,
                           int matchups,
                           int iterations,
                           int effectsLanded) {

    /**
     * Whether this skill's only measurable contribution was to the energy economy.
     *
     * <p>Called out separately because it is the case a damage-only audit gets wrong, and the two
     * skills that prove it are real: {@code Null Blade} siphons energy and deals no damage, and
     * {@code Energy Pool} only raises the maximum. Both would be classified inert by an audit that
     * looked at TTK alone, and both change what a build can do.
     */
    public boolean isEnergyOnly() {
        return bucket == SkillRelevanceBucket.RELEVANT
                && energyDelta != 0
                && bestTtkDelta == 0
                && bestDpsDelta == 0;
    }

    /**
     * Whether the skill activated but never reached anybody through the effect pipeline.
     *
     * <p>Narrows an inert verdict to a cause. A skill that fired and landed effects did reach its
     * target, so an inert verdict on it is a statement about the metric -- crowd control and
     * defensive value do not show up in an attacker's TTK. A skill that fired and landed nothing was
     * stopped by its own guards after activation, which is a finding about the skill or the arena.
     *
     * <p>Not applicable to skills that deal damage without applying effects, so this qualifies the
     * evidence line rather than driving the bucket.
     */
    public boolean firedWithoutLanding() {
        return successes > 0 && effectsLanded == 0;
    }

    /** A one-line justification, rendered into the artifact beside the skill name. */
    public String evidence() {
        if (bucket == SkillRelevanceBucket.UNDRIVABLE) {
            // The success count is printed rather than assumed to be zero: "never fired" and "fired
            // twice, too thin to trust an inert verdict" both land here and want different fixes.
            return String.format(Locale.ROOT,
                    "%s, attempts=%d successes=%d declined=%d over %d duels%s",
                    archetype, attempts, successes, declined, iterations,
                    successes == 0 ? " -- NEVER FIRED" : " -- fired too rarely to judge");
        }
        final String level = bestLevel == null ? "n/a" : String.valueOf(bestLevel);
        return String.format(Locale.ROOT,
                "%s, best level %s, dTTK %+.3fs, dDPS %+.3f, dEnergy %+.2f, "
                        + "fired %d/%d effects %d over %d matchups / %d duels%s%s",
                archetype, level, bestTtkDelta, bestDpsDelta, energyDelta,
                successes, attempts, effectsLanded, matchups, iterations,
                isEnergyOnly() ? " [ENERGY ONLY]" : "",
                // Only worth saying on an inert verdict: on a relevant one the skill demonstrably
                // worked, so the absence of effects just means it deals damage directly.
                bucket == SkillRelevanceBucket.INERT && firedWithoutLanding()
                        ? " [REACHED NOBODY]" : "");
    }
}
