package me.mykindos.betterpvp.balancesim.engine;

/**
 * Decides what a combatant does on a given tick and synthesises the corresponding real input
 * event.
 *
 * <p>The policy must never call a skill's activation method directly -- it produces the same
 * input a player would (interact, drop, hold) via {@link SimInputs} and lets the real listener chain
 * decide whether the skill fires, so cooldown, energy and state gating stay in the game's code.
 *
 * <p>An implementation covers <em>every</em> archetype rather than there being one implementation per
 * archetype, which was the original sketch. The reason is that the interesting part of a rotation is
 * not how a single skill is pressed but how several compose on one body: a combatant has one weapon,
 * one right hand, and one drop key, so a build carrying both a channel and an interact skill cannot
 * drive them independently. Splitting by archetype would have put that arbitration nowhere.
 *
 * @see GreedyRotationPolicy the only implementation, and what {@code sim_run.scenario} names
 */
@FunctionalInterface
public interface RotationPolicy {

    /**
     * Called once per tick for one combatant, before its melee swing for that tick.
     *
     * @param combatant the acting fake player
     * @param opponent  its duel partner
     * @param tick      ticks elapsed since the duel started
     */
    void act(SimCombatant combatant, SimCombatant opponent, long tick);
}
