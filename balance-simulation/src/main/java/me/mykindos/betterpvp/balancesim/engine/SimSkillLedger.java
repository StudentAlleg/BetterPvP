package me.mykindos.betterpvp.balancesim.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Counts what happened to every button the rotation pressed during one duel, per combatant.
 *
 * <p>Both sides are tracked rather than the attacker alone. A {@code sim_result} row describes the
 * attacker, so that is all the aggregate currently surfaces -- but the defensive half of the
 * relevance audit needs exactly this data for the defender, and recording it now costs a map entry
 * while adding it later would mean re-running every sweep.
 *
 * <p>Thread safety: the damage and skill pipelines run on the main thread, while the orchestrator
 * reduces recordings off it. Counters are therefore atomic and the maps concurrent, in the same
 * shape as {@code SimRecorder.Recording}'s own state.
 */
public final class SimSkillLedger {

    private final Map<UUID, Map<String, Counters>> byCombatant = new ConcurrentHashMap<>();

    /**
     * Notes that the rotation pressed {@code skillName}'s button.
     *
     * <p>Separate from the outcome because the two are observed in different places: the press is
     * only visible where it is made, and a press that produces no event at all -- the case that
     * matters most -- is by definition invisible to any listener.
     */
    public void attempted(UUID combatant, String skillName) {
        counters(combatant, skillName).attempts.incrementAndGet();
    }

    /** Records the real chain's verdict on a use of {@code skillName}. */
    public void outcome(UUID combatant, String skillName, SimActivationOutcome outcome) {
        final Counters counters = counters(combatant, skillName);
        switch (outcome) {
            case ATTEMPTED -> counters.attempts.incrementAndGet();
            case SUCCESS -> counters.successes.incrementAndGet();
            case COOLDOWN -> counters.cooldownRefusals.incrementAndGet();
            case ENERGY -> counters.energyRefusals.incrementAndGet();
            case DECLINED -> counters.declined.incrementAndGet();
        }
    }

    /**
     * Notes that {@code skillName}, cast by {@code applier}, landed an effect on somebody.
     *
     * <p>The missing half of "fired but inert". {@link #outcome} records that the chain let a use
     * through, which is only a statement about the caster -- it says nothing about whether anything
     * reached the other combatant. A skill can succeed 240/240 and still be gated out by its own
     * range, facing, charge or line-of-sight checks after activation, and the two cases are
     * indistinguishable in the audit today: both read {@code fired 240/240, dDPS +0.000}.
     *
     * <p>They call for opposite responses. Landed nothing is a finding about the skill or the arena;
     * landed something the metric could not see is a finding about the metric, and a crowd-control or
     * defensive skill belongs in the second group permanently.
     */
    public void effectLanded(UUID applier, String skillName) {
        counters(applier, skillName).effectsLanded.incrementAndGet();
    }

    /** An immutable snapshot of one combatant's per-skill counts, keyed by skill name. */
    public Map<String, SkillActivation> snapshot(UUID combatant) {
        final Map<String, Counters> skills = byCombatant.get(combatant);
        if (skills == null || skills.isEmpty()) {
            return Map.of();
        }
        final Map<String, SkillActivation> snapshot = new LinkedHashMap<>(skills.size());
        skills.forEach((name, counters) -> snapshot.put(name, counters.snapshot()));
        return Map.copyOf(snapshot);
    }

    private Counters counters(UUID combatant, String skillName) {
        return byCombatant
                .computeIfAbsent(combatant, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(skillName, ignored -> new Counters());
    }

    /**
     * One skill's activation counts within one duel.
     *
     * <p>{@code attempts} is not the sum of the rest. A press can be rate-limited away by the
     * rotation's retry interval before it reaches the chain, and a passive is never pressed at all --
     * so a zero here is meaningful only alongside the skill's archetype, which the audit carries
     * separately.
     *
     * @param attempts         presses the rotation made
     * @param successes        uses the chain allowed through
     * @param cooldownRefusals uses refused because the cooldown was still running
     * @param energyRefusals   uses refused for want of energy
     * @param declined         uses cancelled for any other reason
     * @param effectsLanded    effects this skill applied to anybody, the caster included
     */
    public record SkillActivation(int attempts,
                                  int successes,
                                  int cooldownRefusals,
                                  int energyRefusals,
                                  int declined,
                                  int effectsLanded) {

        /** Whether the chain ever let this skill through. The inert/undrivable split turns on this. */
        public boolean everFired() {
            return successes > 0;
        }

        /**
         * Whether the skill fired but never reached anybody through the effect pipeline.
         *
         * <p>Only meaningful for skills that apply effects at all; a purely damaging skill lands no
         * effects by design, so this is evidence to weigh with the archetype rather than a verdict.
         */
        public boolean firedWithoutLanding() {
            return successes > 0 && effectsLanded == 0;
        }

        public SkillActivation plus(SkillActivation other) {
            return new SkillActivation(attempts + other.attempts,
                    successes + other.successes,
                    cooldownRefusals + other.cooldownRefusals,
                    energyRefusals + other.energyRefusals,
                    declined + other.declined,
                    effectsLanded + other.effectsLanded);
        }
    }

    private static final class Counters {

        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger successes = new AtomicInteger();
        private final AtomicInteger cooldownRefusals = new AtomicInteger();
        private final AtomicInteger energyRefusals = new AtomicInteger();
        private final AtomicInteger declined = new AtomicInteger();
        private final AtomicInteger effectsLanded = new AtomicInteger();

        private SkillActivation snapshot() {
            return new SkillActivation(attempts.get(),
                    successes.get(),
                    cooldownRefusals.get(),
                    energyRefusals.get(),
                    declined.get(),
                    effectsLanded.get());
        }
    }
}
