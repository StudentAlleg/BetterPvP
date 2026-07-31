package me.mykindos.betterpvp.balancesim.audit;

import me.mykindos.betterpvp.balancesim.engine.SimMeasurement;

import java.util.Locale;

/**
 * How large a delta has to be before the audit will call a skill relevant.
 *
 * <p>There has to be a threshold. Weapon damage rolls are deterministic in this simulator, but duels
 * are not: tick timing, crit rolls and the skills' own probabilistic branches all vary between
 * iterations, so two measurements of the same build differ slightly and exact equality would
 * classify almost everything as relevant.
 *
 * <p>Each dimension is judged on an absolute floor <em>or</em> a relative one, whichever is larger.
 * The absolute floor keeps noise on small figures from reading as an effect; the relative one keeps a
 * genuinely small effect on a large figure from being dismissed. A build killing in twelve seconds
 * and one killing in eleven differ by far more than the tick quantum, and no absolute threshold that
 * also works for a two-second fight would catch it.
 *
 * <p>Recorded in the artifact, because a verdict is only meaningful alongside the threshold that
 * produced it -- and re-running the classification at a different epsilon is exactly the kind of
 * question a reviewer should be able to ask.
 *
 * @param ttkSeconds      absolute floor on a time-to-kill change, in seconds
 * @param ttkRelative     fractional floor on a time-to-kill change, against the baseline TTK
 * @param dps             absolute floor on a sustained DPS change
 * @param dpsRelative     fractional floor on a sustained DPS change, against the baseline DPS
 * @param energy          absolute floor on an energy-economy change, in energy units
 * @param minSuccesses    how many times a driven skill must have fired before an inert verdict is
 *                        trusted. Below this the sample is too thin to distinguish "did nothing" from
 *                        "barely ran", and the skill is reported as undrivable instead
 */
public record AuditThresholds(double ttkSeconds,
                              double ttkRelative,
                              double dps,
                              double dpsRelative,
                              double energy,
                              int minSuccesses) {

    /**
     * Defaults chosen against what the engine can actually resolve.
     *
     * <p>{@code ttkSeconds} is 0.1s -- two server ticks, the quantum TTK is measured in doubled, so a
     * one-tick timing wobble cannot register. {@code energy} is 1.0, comfortably below the smallest
     * real effect in the game ({@code Null Blade} siphons 9 at level 1) and above any rounding.
     */
    public static AuditThresholds defaults() {
        return new AuditThresholds(0.1, 0.02, 0.25, 0.02, 1.0, 3);
    }

    /**
     * Whether any dimension moved enough to call the skill relevant for this matchup.
     *
     * <p>Judged per matchup rather than on pooled figures, so an effect that only appears against one
     * target is not averaged away by the targets it does not appear against.
     */
    public boolean isSignificant(SimMeasurement.Aggregate baseline,
                                 SimMeasurement.Aggregate measured,
                                 double ttkDelta,
                                 double dpsDelta,
                                 double energyDelta) {
        return exceeds(ttkDelta, ttkSeconds, ttkRelative, baseline.ttkSeconds())
                || exceeds(dpsDelta, dps, dpsRelative, baseline.dpsSustained())
                || Math.abs(energyDelta) > energy
                // A matchup the baseline could not resolve but the skill build could -- or the
                // reverse -- is the strongest possible signal and has no delta to test, because one
                // side has no figure at all.
                || resolvedDifferently(baseline, measured);
    }

    private static boolean exceeds(double delta, double absolute, double relative, Double baseline) {
        final double floor = baseline == null || baseline == 0
                ? absolute
                : Math.max(absolute, Math.abs(baseline) * relative);
        return Math.abs(delta) > floor;
    }

    /**
     * Whether one of the two matchups produced a kill and the other did not.
     *
     * <p>A skill that turns an unwinnable fight into a winnable one produces a null baseline TTK and a
     * real one, so every numeric delta is zero and the skill would read as inert. This is the case
     * that most obviously must not be missed.
     */
    private static boolean resolvedDifferently(SimMeasurement.Aggregate baseline,
                                               SimMeasurement.Aggregate measured) {
        return (baseline.ttkSeconds() == null) != (measured.ttkSeconds() == null);
    }

    /** A one-line rendering for the artifact header. */
    public String describe() {
        return String.format(Locale.ROOT,
                "ttk %.3fs or %.1f%%, dps %.3f or %.1f%%, energy %.2f, min successes %d",
                ttkSeconds, ttkRelative * 100, dps, dpsRelative * 100, energy, minSuccesses);
    }
}
