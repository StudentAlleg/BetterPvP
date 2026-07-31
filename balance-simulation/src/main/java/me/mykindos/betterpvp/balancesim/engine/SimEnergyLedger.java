package me.mykindos.betterpvp.balancesim.engine;

import me.mykindos.betterpvp.core.energy.events.EnergyEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Accounts for every unit of energy that moved during one duel, per combatant and per cause.
 *
 * <p>Energy is the second axis a build can be balanced on and, until this existed, the simulator
 * measured almost none of it: {@code sim_result.energy_limited} was a single boolean, so a skill
 * whose entire effect is on the energy economy was indistinguishable from a skill that does nothing.
 * Two skills make that concrete, and both are real:
 *
 * <ul>
 *   <li><b>Null Blade</b> ({@code mage/passives}) siphons energy from the target on every melee hit
 *       and gives it to the attacker. It deals no damage whatsoever, so against a bare baseline it
 *       moves neither TTK nor DPS -- and a damage-only audit would classify it inert. What it
 *       actually does is visible here and nowhere else, as a {@code CUSTOM} degeneration on the
 *       defender paired with a {@code CUSTOM} regeneration on the attacker.</li>
 *   <li><b>Energy Pool</b> ({@code global}) raises maximum energy through
 *       {@code UpdateMaxEnergyEvent}. It moves no energy at all, so even a full flow accounting
 *       would miss it -- which is why {@link #maxEnergy} is recorded as well as the flows.</li>
 * </ul>
 *
 * <p>Everything here is <em>observed</em>. The flows are read off the real events at
 * {@code MONITOR}, after any listener has had its chance to cancel or rescale them, so the numbers
 * are what {@code EnergyService} actually applied rather than what a caller asked for. Nothing
 * re-derives {@code EnergyService.use}'s rule, which is the drift this project exists to remove.
 */
public final class SimEnergyLedger {

    private final Map<UUID, Flows> byCombatant = new ConcurrentHashMap<>();

    /**
     * Records energy leaving a combatant.
     *
     * @param amount the post-event amount, which is what was actually deducted
     */
    public void degenerated(UUID combatant, double amount, EnergyEvent.Cause cause) {
        final Flows flows = flows(combatant);
        switch (cause) {
            case USE -> flows.add(amount, 0, 0, 0, 0);
            case CUSTOM -> flows.add(0, amount, 0, 0, 0);
            case NATURAL -> flows.add(0, 0, amount, 0, 0);
        }
    }

    /** Records energy arriving at a combatant. */
    public void regenerated(UUID combatant, double amount, EnergyEvent.Cause cause) {
        final Flows flows = flows(combatant);
        if (cause == EnergyEvent.Cause.NATURAL) {
            flows.add(0, 0, 0, amount, 0);
        } else {
            flows.add(0, 0, 0, 0, amount);
        }
    }

    /**
     * Notes the combatant's current and maximum energy at an observation point.
     *
     * <p>Sampled at each energy event rather than on a timer of its own. Natural regeneration ticks
     * for every tracked player, so in a duel of any length the sample rate is effectively the
     * regeneration rate -- close enough to a floor that a separate hook into the duel loop would buy
     * precision nobody reads.
     */
    public void observed(UUID combatant, double current, double max) {
        flows(combatant).observe(current, max);
    }

    /** An immutable snapshot of one combatant's energy accounting, or null if it never moved any. */
    public EnergyUse snapshot(UUID combatant) {
        final Flows flows = byCombatant.get(combatant);
        return flows == null ? null : flows.snapshot();
    }

    private Flows flows(UUID combatant) {
        return byCombatant.computeIfAbsent(combatant, ignored -> new Flows());
    }

    /**
     * One combatant's energy accounting for one duel.
     *
     * @param spentOnSkills  degenerated with {@code USE} -- what the rotation's own casts cost
     * @param drainedCustom  degenerated with {@code CUSTOM} -- taken by a mechanic such as Null Blade
     * @param drainedNatural degenerated with {@code NATURAL}
     * @param regenNatural   regenerated with {@code NATURAL} -- the baseline economy
     * @param regenCustom    regenerated with {@code CUSTOM} -- granted by a mechanic
     * @param minEnergy      lowest energy observed, the floor a rotation ran down to
     * @param maxEnergy      the combatant's maximum, which is what Energy Pool moves and nothing else does
     */
    public record EnergyUse(double spentOnSkills,
                            double drainedCustom,
                            double drainedNatural,
                            double regenNatural,
                            double regenCustom,
                            double minEnergy,
                            double maxEnergy) {

        /** Total energy that left the combatant, however it left. */
        public double totalOut() {
            return spentOnSkills + drainedCustom + drainedNatural;
        }

        /** Total energy that arrived, however it arrived. */
        public double totalIn() {
            return regenNatural + regenCustom;
        }

        /**
         * Whether any energy moved or any capacity was observed at all.
         *
         * <p>A matchup where nothing moved should carry no energy figures rather than a row of
         * zeroes, because zero spend and no observation are different claims.
         */
        public boolean isEmpty() {
            return totalOut() == 0 && totalIn() == 0 && maxEnergy == 0;
        }
    }

    /** Mutable accumulator. Synchronised rather than atomic because doubles have no atomic adder. */
    private static final class Flows {

        private double spentOnSkills;
        private double drainedCustom;
        private double drainedNatural;
        private double regenNatural;
        private double regenCustom;
        private double minEnergy = Double.MAX_VALUE;
        private double maxEnergy;

        private synchronized void add(double use, double custom, double natural,
                                      double regenNat, double regenCust) {
            spentOnSkills += use;
            drainedCustom += custom;
            drainedNatural += natural;
            regenNatural += regenNat;
            regenCustom += regenCust;
        }

        private synchronized void observe(double current, double max) {
            minEnergy = Math.min(minEnergy, current);
            // The maximum is a property of the build rather than of the moment, but it is read
            // per observation because Energy Pool's contribution only appears once the skill has
            // been tracked -- so the largest value seen is the one the build actually fought with.
            maxEnergy = Math.max(maxEnergy, max);
        }

        private synchronized EnergyUse snapshot() {
            return new EnergyUse(spentOnSkills,
                    drainedCustom,
                    drainedNatural,
                    regenNatural,
                    regenCustom,
                    minEnergy == Double.MAX_VALUE ? 0 : minEnergy,
                    maxEnergy);
        }
    }
}
