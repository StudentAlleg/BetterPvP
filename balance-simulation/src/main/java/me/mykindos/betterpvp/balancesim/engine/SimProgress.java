package me.mykindos.betterpvp.balancesim.engine;

import me.mykindos.betterpvp.balancesim.catalog.SimScope;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * A snapshot of a sweep's progress, emitted periodically to the log and to whoever started it.
 *
 * <p>A sweep is long and silent by nature -- duels run in real time, so a {@code SKILLS} scope is
 * hours of nothing on the console. Without progress there is no way to tell a healthy long run
 * from a wedged one, and no way to decide whether to let it finish. So the snapshot reports both
 * halves of "how far along": duels, which is the unit of work and moves every tick, and matchups,
 * which is the unit of *output* -- a matchup only becomes a {@code sim_result} row once all of its
 * Monte-Carlo iterations are in, so a run can be most of the way through its duels with far fewer
 * rows written than expected.
 *
 * @param scope             the tier being swept
 * @param plannedDuels      total duels the run will perform (matchups x iterations)
 * @param completedDuels    duels measured so far
 * @param activeDuels       duels currently in flight
 * @param plannedMatchups   total matchups, i.e. rows the run will produce
 * @param completedMatchups matchups whose every iteration has landed
 * @param elapsed           since the tick loop started, not since the command was typed
 * @param eta               projected time remaining, or null before there is any rate to project
 *                          from
 */
public record SimProgress(SimScope scope,
                          long plannedDuels,
                          long completedDuels,
                          int activeDuels,
                          int plannedMatchups,
                          int completedMatchups,
                          Duration elapsed,
                          @Nullable Duration eta) {

    private static final DateTimeFormatter FINISH_TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /** Completion as a percentage of duels, 0 when nothing is planned. */
    public double percent() {
        return plannedDuels <= 0 ? 0 : 100.0 * completedDuels / plannedDuels;
    }

    /** Wall-clock time the run is projected to finish, or {@code "unknown"} before there is a rate. */
    public String estimatedFinish() {
        return eta == null ? "unknown" : FINISH_TIME.format(Instant.now().plus(eta));
    }

    public String elapsedFormatted() {
        return format(elapsed);
    }

    public String etaFormatted() {
        return eta == null ? "unknown" : format(eta);
    }

    /**
     * Renders a duration as {@code 1h 04m 09s}, dropping leading units that are zero.
     *
     * <p>Written out rather than using {@code Duration.toString}, which would render the same value
     * as {@code PT1H4M9S} -- correct, and unreadable at a glance in a console line an operator is
     * scanning to decide whether to keep waiting.
     */
    static String format(Duration duration) {
        final long seconds = Math.max(0, duration.getSeconds());
        final long hours = seconds / 3600;
        final long minutes = (seconds % 3600) / 60;
        final long secs = seconds % 60;
        if (hours > 0) {
            return String.format("%dh %02dm %02ds", hours, minutes, secs);
        }
        if (minutes > 0) {
            return String.format("%dm %02ds", minutes, secs);
        }
        return secs + "s";
    }
}
