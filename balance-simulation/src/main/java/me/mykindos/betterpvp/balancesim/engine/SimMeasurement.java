package me.mykindos.betterpvp.balancesim.engine;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Accumulates the Monte-Carlo iterations of one matchup and reduces them to the figures a
 * {@code sim_result} row carries.
 *
 * <p>A single duel is not a measurement. Weapon damage is rolled between a min and a max, crits
 * fire probabilistically, and several skills roll their own chances -- so one fight samples a
 * distribution rather than reporting it. Running the matchup N times and averaging is the same
 * answer SimulationCraft reaches for the same reason: closed-form arithmetic cannot describe a
 * stateful, stochastic mechanic, but repeated observation can.
 *
 * <p>The mean goes in the typed columns because that is what the dashboards plot; the spread goes
 * in {@code extras} rather than widening the table, because a column per percentile per figure
 * would be a schema change every time the engine learns to measure something new. What must never
 * happen is a mean presented without its iteration count -- a single-iteration run and a
 * hundred-iteration run produce the same column shape and are not the same claim -- so
 * {@code iterations} is always written.
 */
public final class SimMeasurement {

    private static final long MILLIS_PER_TICK = 50L;

    private final int expectedIterations;
    private final List<Sample> samples = new ArrayList<>();

    /**
     * How often each pipeline reason/modifier label appeared across every hit of every iteration.
     *
     * <p>This is what keeps mitigation observable rather than assumed: the recorder takes the
     * labels from {@code DamageEvent.getReasons()}, so a result says which modifiers actually
     * contributed instead of leaving a reader to infer it from the damage number.
     */
    private final Map<String, Integer> reasonCounts = new TreeMap<>();

    public SimMeasurement(int expectedIterations) {
        this.expectedIterations = Math.max(1, expectedIterations);
    }

    /**
     * One duel's worth of measurement.
     *
     * <p>Every figure is nullable in the way the duel makes it nullable: a matchup where the
     * attacker never landed a hit has no damage figures at all, and one that timed out has no TTK
     * and therefore no honest hit count -- "however many landed before the clock ran out" is not
     * hits-to-kill, and storing it as such would read as a real number.
     *
     * @param dmgPerHit    mean final damage across this duel's landed hits
     * @param dpsSustained damage over the engagement window
     * @param dpsBurst     damage over the best one-second window
     * @param ttkTicks     server ticks from first landed hit to the lethal one
     * @param hits         landed hits
     * @param killed       whether the defender took a blow that would have been lethal
     */
    public record Sample(@Nullable Double dmgPerHit,
                         @Nullable Double dpsSustained,
                         @Nullable Double dpsBurst,
                         @Nullable Integer ttkTicks,
                         int hits,
                         boolean killed) {
    }

    /** The reduced figures, ready to become a {@code sim_result} row. */
    public record Aggregate(@Nullable Double dmgPerHit,
                            @Nullable Double dpsSustained,
                            @Nullable Double dpsBurst,
                            @Nullable Double ttkSeconds,
                            @Nullable Double hitsToKill,
                            String extrasJson) {
    }

    public void add(Sample sample, Map<String, Integer> hitReasons) {
        samples.add(sample);
        hitReasons.forEach((reason, count) -> reasonCounts.merge(reason, count, Integer::sum));
    }

    /** Whether every planned iteration of this matchup has been measured. */
    public boolean isComplete() {
        return samples.size() >= expectedIterations;
    }

    /**
     * Reduces the collected iterations.
     *
     * <p>TTK and hits-to-kill average over the iterations that produced a kill only. Averaging a
     * timeout in as if it were a slow kill would drag the mean toward a value no duel ever
     * produced; the fraction that timed out is reported separately as {@code kill_rate}, which is
     * the honest way to say "this matchup does not reliably resolve".
     */
    public Aggregate aggregate() {
        final List<Sample> killing = samples.stream().filter(Sample::killed).toList();

        final Double dmgPerHit = mean(samples, Sample::dmgPerHit);
        final Double dpsSustained = mean(samples, Sample::dpsSustained);
        final Double dpsBurst = mean(samples, Sample::dpsBurst);
        final Double ttkTicks = mean(killing, sample -> sample.ttkTicks() == null
                ? null : sample.ttkTicks().doubleValue());
        final Double hitsToKill = killing.isEmpty() ? null
                : killing.stream().mapToInt(Sample::hits).average().orElseThrow();

        final Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("iterations", samples.size());
        extras.put("planned_iterations", expectedIterations);
        extras.put("kills", killing.size());
        extras.put("kill_rate", samples.isEmpty() ? 0.0 : (double) killing.size() / samples.size());
        // The tick count rides in extras so the unit the sim actually measured in is recoverable
        // from the row: ttk_s is a rendering of it and is always a multiple of 0.05.
        extras.put("ttk_ticks_mean", ttkTicks);
        extras.put("ttk_ticks_p50", percentile(killing, sample -> toDouble(sample.ttkTicks()), 50));
        extras.put("ttk_ticks_p90", percentile(killing, sample -> toDouble(sample.ttkTicks()), 90));
        extras.put("dps_p50", percentile(samples, Sample::dpsSustained, 50));
        extras.put("dps_p90", percentile(samples, Sample::dpsSustained, 90));
        extras.put("dmg_per_hit_stddev", stddev(samples, Sample::dmgPerHit));
        extras.put("reasons", reasonCounts);

        return new Aggregate(dmgPerHit,
                dpsSustained,
                dpsBurst,
                ttkTicks == null ? null : ticksToSeconds(ttkTicks),
                hitsToKill,
                toJson(extras));
    }

    /**
     * Ticks are the sim's unit of time; seconds exist only for the columns dashboards read. The
     * damage delay, cooldowns and skill durations are all tick-quantised, so a measurement in ticks
     * is exact where one in nanoseconds only looked precise -- the latter carried scheduler jitter
     * and GC pauses into figures that should be reproducible between runs.
     */
    private static double ticksToSeconds(double ticks) {
        return ticks * MILLIS_PER_TICK / 1000.0;
    }

    @Nullable
    private static Double toDouble(@Nullable Integer value) {
        return value == null ? null : value.doubleValue();
    }

    @Nullable
    private static Double mean(List<Sample> samples, java.util.function.Function<Sample, Double> field) {
        final List<Double> values = values(samples, field);
        if (values.isEmpty()) {
            return null;
        }
        return values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
    }

    @Nullable
    private static Double stddev(List<Sample> samples, java.util.function.Function<Sample, Double> field) {
        final List<Double> values = values(samples, field);
        if (values.size() < 2) {
            return null;
        }
        final double mean = values.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        final double variance = values.stream()
                .mapToDouble(value -> (value - mean) * (value - mean))
                .sum() / (values.size() - 1);
        return Math.sqrt(variance);
    }

    /**
     * Nearest-rank percentile. Chosen over interpolation deliberately: every value here is a real
     * observed duel, and an interpolated percentile is a number no iteration produced.
     */
    @Nullable
    private static Double percentile(List<Sample> samples,
                                     java.util.function.Function<Sample, Double> field,
                                     int percentile) {
        final List<Double> values = new ArrayList<>(values(samples, field));
        if (values.isEmpty()) {
            return null;
        }
        values.sort(Comparator.naturalOrder());
        final int rank = (int) Math.ceil(percentile / 100.0 * values.size());
        return values.get(Math.min(values.size(), Math.max(1, rank)) - 1);
    }

    private static List<Double> values(List<Sample> samples, java.util.function.Function<Sample, Double> field) {
        final List<Double> values = new ArrayList<>(samples.size());
        for (Sample sample : samples) {
            final Double value = field.apply(sample);
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    /**
     * Serialises the extras map by hand rather than pulling in a binder: every value is a
     * primitive, a null, or a flat string-to-int map, and the column is read by Grafana with
     * {@code ->>} rather than deserialised into a type.
     */
    @SuppressWarnings("unchecked")
    private static String toJson(Map<String, Object> extras) {
        final StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : extras.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(entry.getKey())).append("\":");
            final Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                json.append(mapToJson((Map<String, Integer>) map));
            } else if (value instanceof Number || value instanceof Boolean) {
                json.append(value);
            } else if (value == null) {
                json.append("null");
            } else {
                json.append('"').append(escape(value.toString())).append('"');
            }
        }
        return json.append('}').toString();
    }

    private static String mapToJson(Map<String, Integer> map) {
        final StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(entry.getKey())).append("\":").append(entry.getValue());
        }
        return json.append('}').toString();
    }

    private static String escape(String raw) {
        return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
