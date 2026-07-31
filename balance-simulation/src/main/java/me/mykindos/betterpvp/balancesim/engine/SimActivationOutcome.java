package me.mykindos.betterpvp.balancesim.engine;

/**
 * What the real chain did with a button the rotation pressed.
 *
 * <p>This is the distinction the relevance audit is built on. A skill that measures identically to
 * an empty slot has two completely different explanations -- it fired and did nothing, or it never
 * fired at all -- and the first is a finding about the game while the second is a bug in this
 * engine. Nothing on a {@code sim_result} row could tell them apart before these were counted.
 *
 * <p>Every value except {@link #ATTEMPTED} is read from {@code PlayerUseSkillEvent} at
 * {@code MONITOR}, so it is the verdict of the real gates rather than a prediction of them.
 */
public enum SimActivationOutcome {

    /**
     * The rotation pressed the button. Counted at the press by {@code GreedyRotationPolicy}, because
     * a press that reaches no listener at all produces no event to observe -- and a skill whose
     * attempts are zero was never driven, which is the single most important thing the audit needs to
     * know.
     */
    ATTEMPTED,

    /** The chain let the use through. The only outcome that can move a damage figure. */
    SUCCESS,

    /** Refused because the skill was still on cooldown. Expected and healthy under a greedy rotation. */
    COOLDOWN,

    /**
     * Refused for want of energy. The outcome that makes an energy skill's cost visible: a rotation
     * stalling here is limited by the energy economy rather than by cooldowns, and the two respond to
     * opposite balance levers.
     */
    ENERGY,

    /**
     * Cancelled for some other reason -- {@code canUse} refused, or a silence/stun gate caught it.
     * Distinguished from the two named refusals because in an empty void world neither of those
     * applies, so a non-trivial count here means the engine is driving the skill wrongly.
     */
    DECLINED
}
