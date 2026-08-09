package me.mykindos.betterpvp.balancesim.engine;

import me.mykindos.betterpvp.balancesim.catalog.SimScenario;
import me.mykindos.betterpvp.balancesim.catalog.SimScope;
import me.mykindos.betterpvp.balancesim.catalog.SimSelection;
import org.jetbrains.annotations.Nullable;

/**
 * Everything an admin asked for when starting a sweep.
 *
 * <p>A record rather than more parameters on {@code DuelOrchestrator.run}. That method had grown to
 * seven arguments of which three were booleans, which is the shape where a call site silently passes
 * {@code audit} where {@code resume} was meant and nothing catches it -- and the two flags added here
 * would have made it nine. Naming them at the call site is the point.
 *
 * <p>The three axes of choice are deliberately separate:
 * <ul>
 *   <li>{@link #scope()} and {@link #scenario()} -- <em>what is measured</em>, which axes vary and
 *       whether both sides act.</li>
 *   <li>{@link #selection()} -- <em>over which values</em>, so a change to one weapon can be
 *       re-measured without inventing a scope tier per weapon.</li>
 *   <li>{@link #changed()} -- <em>which of those still need duels</em>, decided per matchup against a
 *       baseline run rather than per run against {@code config_hash}.</li>
 * </ul>
 * The first two narrow the catalog; the third narrows the work at a fixed catalog. They compose:
 * {@code --weapons=thornfang --changed} enumerates that weapon's permutations and then measures only
 * the ones whose config actually moved.
 *
 * @param trigger   what kicked the run off, recorded on the {@code sim_run} row
 * @param scope     which slice of the permutation space to cover
 * @param scenario  whether a duel drives one side or both
 * @param selection which values of each axis to sweep; {@link SimSelection#ALL} for all of them
 * @param audit     whether to classify each skill's relevance and write the artifact
 * @param resume    whether to continue an interrupted run at this {@code config_hash}
 * @param resumeId  a specific run to continue regardless of {@code config_hash}, or null to search
 * @param changed   whether to measure only the matchups whose own config moved since a baseline run
 * @param baselineId the run to diff against, or null to take the newest finished run at this scope
 * @param carry     whether a delta run copies the unchanged rows in, making it a whole run. On by
 *                  default: a run holding only what changed is a biased sample of the sweep it claims
 *                  to be, and every dashboard reading "latest run" would silently be reading one
 */
public record SimSweepRequest(SimulationTrigger trigger,
                              SimScope scope,
                              SimScenario scenario,
                              SimSelection selection,
                              boolean audit,
                              boolean resume,
                              @Nullable Long resumeId,
                              boolean changed,
                              @Nullable Long baselineId,
                              boolean carry) {

    /** A plain sweep: this scope, this scenario, everything in it, measured from scratch. */
    public static SimSweepRequest of(SimulationTrigger trigger, SimScope scope, SimScenario scenario) {
        return new SimSweepRequest(trigger, scope, scenario, SimSelection.ALL,
                false, false, null, false, null, true);
    }

    /**
     * Whether this sweep decides what to measure per matchup rather than per run.
     *
     * <p>Naming an explicit baseline implies it, so {@code --changed=204} needs no second flag. The
     * reverse is not true: {@code --changed} alone searches for a baseline.
     */
    public boolean isDelta() {
        return changed || baselineId != null;
    }
}
