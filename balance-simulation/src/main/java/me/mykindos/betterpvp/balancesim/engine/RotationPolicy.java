package me.mykindos.betterpvp.balancesim.engine;

/**
 * Decides what a combatant does on a given tick and synthesises the corresponding real input
 * event.
 *
 * <p>The policy must never call a skill's activation method directly -- it produces the same
 * input a player would (interact, drop, bow release) and lets the real listener chain decide
 * whether the skill fires, so cooldown, energy and state gating stay in the game's code.
 *
 * <p>Implementations are per-{@link ActivationArchetype}, since a single greedy rule cannot
 * express "hold this channel for N ticks" or "only pay off a prepare when a hit is about to
 * land".
 */
@FunctionalInterface
public interface RotationPolicy {

    /**
     * Called once per tick for one combatant.
     *
     * @param combatant  the acting fake player
     * @param opponent   its duel partner
     * @param tick       ticks elapsed since the duel started
     */
    void act(SimCombatant combatant, SimCombatant opponent, long tick);
}
