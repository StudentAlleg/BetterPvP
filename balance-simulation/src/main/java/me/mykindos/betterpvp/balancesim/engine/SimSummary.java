package me.mykindos.betterpvp.balancesim.engine;

import me.mykindos.betterpvp.balancesim.catalog.SimScope;

import java.time.Duration;

/**
 * What a finished sweep actually did, as opposed to what it planned to do.
 *
 * <p>This is the run's result rather than a log line, so the numbers reach whoever started it and
 * not only the console. The planned and completed counts are both carried because a run can close
 * with status {@code COMPLETED} having measured fewer matchups than it enumerated -- a build whose
 * {@code sim_build} row did not come back is skipped, and an aborted run closes with whatever it
 * had. Reporting only the completed figure would make a partial sweep look like a whole one.
 *
 * <p>{@code resultRows} is counted separately from {@code completedMatchups} rather than assumed
 * equal to it: the rows are the durable output, and a mismatch between the two is the signal that
 * something was measured but not persisted.
 *
 * @param runId             the {@code sim_run} row this summarises
 * @param scope             the slice of the permutation space that was swept
 * @param status            the terminal {@code sim_run.status}: {@code COMPLETED} or {@code FAILED}
 * @param plannedDuels      duels the sweep enumerated
 * @param completedDuels    duels that actually resolved, by kill or timeout
 * @param plannedMatchups   matchups the sweep enumerated
 * @param completedMatchups matchups whose every iteration landed and were reduced to a row
 * @param resultRows        {@code sim_result} rows handed to the repository
 * @param elapsed           wall clock from the first tick of the sweep to its last
 */
public record SimSummary(long runId,
                         SimScope scope,
                         String status,
                         long plannedDuels,
                         long completedDuels,
                         int plannedMatchups,
                         int completedMatchups,
                         int resultRows,
                         Duration elapsed) {

    /** Whether every planned duel resolved. False for an aborted run or a skipped build. */
    public boolean whole() {
        return completedDuels >= plannedDuels && completedMatchups >= plannedMatchups;
    }

    /** Mean wall clock per resolved duel, the figure worth carrying into the next sweep's sizing. */
    public double millisPerDuel() {
        return completedDuels <= 0 ? 0.0 : (double) elapsed.toMillis() / completedDuels;
    }

    public String elapsedFormatted() {
        return SimProgress.format(elapsed);
    }

    /** A zero summary, for a sweep that ended before its tick loop ever started. */
    public static SimSummary empty(long runId, SimScope scope, String status) {
        return new SimSummary(runId, scope, status, 0, 0, 0, 0, 0, Duration.ZERO);
    }
}
