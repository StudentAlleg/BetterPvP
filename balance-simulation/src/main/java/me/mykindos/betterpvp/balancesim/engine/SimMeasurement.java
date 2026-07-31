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

    /**
     * Per-skill activation counts summed across every iteration of this matchup.
     *
     * <p>Summed rather than averaged, because the question the audit asks of them is "did this ever
     * fire", and a mean of 0.4 successes answers it less clearly than a total of 2. The iteration
     * count travels alongside in {@code extras}, so a rate is recoverable.
     */
    private final Map<String, SimSkillLedger.SkillActivation> activationTotals = new TreeMap<>();

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
     * @param ttkTicks      server ticks from first landed hit to the lethal one
     * @param hits          landed hits
     * @param killed        whether the defender took the lethal blow
     * @param attackerDied  whether the <em>attacker</em> died instead, which only a {@code MUTUAL}
     *                      duel can produce. Recorded rather than inferred from {@code !killed},
     *                      because a duel can also simply time out with both alive.
     * @param energyLimited whether a skill was refused for want of energy during this duel
     * @param activations   per-skill activation counts for the side this sample describes, keyed by
     *                      skill name. Empty for a build with no skills, and zero-attempt for a
     *                      passive, which has no button to press
     * @param energy        the side's energy accounting, or null when no energy moved and none was
     *                      observed -- which is a different claim from "spent nothing"
     */
    public record Sample(@Nullable Double dmgPerHit,
                         @Nullable Double dpsSustained,
                         @Nullable Double dpsBurst,
                         @Nullable Integer ttkTicks,
                         int hits,
                         boolean killed,
                         boolean attackerDied,
                         boolean energyLimited,
                         Map<String, SimSkillLedger.SkillActivation> activations,
                         @Nullable SimEnergyLedger.EnergyUse energy) {
    }

    /**
     * The reduced figures, ready to become a {@code sim_result} row.
     *
     * @param energyLimited true when any iteration of this matchup had a skill refused for energy.
     *                      Any rather than all: a rotation that runs dry in some iterations and not
     *                      others is energy-limited, and the fraction is not what the column claims.
     * @param activations   per-skill activation totals across the matchup's iterations, exposed as
     *                      typed data as well as inside {@code extrasJson} so the relevance audit
     *                      reads the figures rather than re-parsing its own JSON
     * @param energy        mean per-duel energy accounting, or null when none was observed
     */
    public record Aggregate(@Nullable Double dmgPerHit,
                            @Nullable Double dpsSustained,
                            @Nullable Double dpsBurst,
                            @Nullable Double ttkSeconds,
                            @Nullable Double hitsToKill,
                            boolean energyLimited,
                            Map<String, SimSkillLedger.SkillActivation> activations,
                            @Nullable SimEnergyLedger.EnergyUse energy,
                            int iterations,
                            String extrasJson) {
    }

    public void add(Sample sample, Map<String, Integer> hitReasons) {
        samples.add(sample);
        hitReasons.forEach((reason, count) -> reasonCounts.merge(reason, count, Integer::sum));
        sample.activations().forEach((skill, counts) ->
                activationTotals.merge(skill, counts, SimSkillLedger.SkillActivation::plus));
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

        final long attackerDeaths = samples.stream().filter(Sample::attackerDied).count();
        final boolean energyLimited = samples.stream().anyMatch(Sample::energyLimited);

        final Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("iterations", samples.size());
        extras.put("planned_iterations", expectedIterations);
        extras.put("kills", killing.size());
        extras.put("kill_rate", samples.isEmpty() ? 0.0 : (double) killing.size() / samples.size());
        // Under MUTUAL these two are the honest reading of the matchup: kill_rate is how often the
        // attacker won the race and attacker_deaths is how often it lost, and the pair does not have to
        // sum to the iteration count because a duel can also time out with both alive. Under ONE_WAY
        // attacker_deaths is always zero, which is what makes the two scenarios distinguishable on a row
        // as well as on the run.
        extras.put("attacker_deaths", attackerDeaths);
        extras.put("energy_limited_iterations", samples.stream().filter(Sample::energyLimited).count());
        // The tick count rides in extras so the unit the sim actually measured in is recoverable
        // from the row: ttk_s is a rendering of it and is always a multiple of 0.05.
        extras.put("ttk_ticks_mean", ttkTicks);
        extras.put("ttk_ticks_p50", percentile(killing, sample -> toDouble(sample.ttkTicks()), 50));
        extras.put("ttk_ticks_p90", percentile(killing, sample -> toDouble(sample.ttkTicks()), 90));
        extras.put("dps_p50", percentile(samples, Sample::dpsSustained, 50));
        extras.put("dps_p90", percentile(samples, Sample::dpsSustained, 90));
        extras.put("dmg_per_hit_stddev", stddev(samples, Sample::dmgPerHit));
        extras.put("reasons", reasonCounts);
        // The two blocks the relevance audit reads. Both are absent rather than empty when there is
        // nothing to say: a build with no skills has no activations, and a duel where no energy moved
        // has no energy accounting -- and "absent" and "all zero" are different claims, the second of
        // which would read as a measured result.
        if (!activationTotals.isEmpty()) {
            extras.put("activations", activationsJson());
        }
        final SimEnergyLedger.EnergyUse energy = meanEnergy();
        if (energy != null) {
            extras.put("energy", energyJson(energy));
        }

        return new Aggregate(dmgPerHit,
                dpsSustained,
                dpsBurst,
                ttkTicks == null ? null : ticksToSeconds(ttkTicks),
                hitsToKill,
                energyLimited,
                Map.copyOf(activationTotals),
                energy,
                samples.size(),
                toJson(extras));
    }

    /** Per-skill activation totals, as a nested object keyed by skill name. */
    private Map<String, Object> activationsJson() {
        final Map<String, Object> bySkill = new LinkedHashMap<>();
        activationTotals.forEach((skill, counts) -> {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("attempts", counts.attempts());
            entry.put("successes", counts.successes());
            entry.put("cooldown_refusals", counts.cooldownRefusals());
            entry.put("energy_refusals", counts.energyRefusals());
            entry.put("declined", counts.declined());
            bySkill.put(skill, entry);
        });
        return bySkill;
    }

    /**
     * The matchup's energy accounting, averaged per duel.
     *
     * <p>Means rather than totals, unlike the activation counts. The audit's question here is
     * comparative -- "did this build move more energy than the baseline" -- and two matchups can be
     * reduced from different numbers of iterations, so totals would make a matchup that ran more
     * duels look like a bigger energy user. The activation counts are asked a different question
     * ("did this ever fire at all"), which totals answer better.
     *
     * <p>{@code minEnergy} takes the lowest floor any duel reached and {@code maxEnergy} the highest
     * capacity any duel had, because neither is a flow: the floor is the worst case the rotation hit,
     * and the capacity is a property of the build that {@code Energy Pool} moves and nothing else does.
     */
    @Nullable
    private SimEnergyLedger.EnergyUse meanEnergy() {
        final List<SimEnergyLedger.EnergyUse> uses = samples.stream()
                .map(Sample::energy)
                .filter(use -> use != null && !use.isEmpty())
                .toList();
        if (uses.isEmpty()) {
            return null;
        }
        final int n = uses.size();
        return new SimEnergyLedger.EnergyUse(
                sum(uses, SimEnergyLedger.EnergyUse::spentOnSkills) / n,
                sum(uses, SimEnergyLedger.EnergyUse::drainedCustom) / n,
                sum(uses, SimEnergyLedger.EnergyUse::drainedNatural) / n,
                sum(uses, SimEnergyLedger.EnergyUse::regenNatural) / n,
                sum(uses, SimEnergyLedger.EnergyUse::regenCustom) / n,
                uses.stream().mapToDouble(SimEnergyLedger.EnergyUse::minEnergy).min().orElse(0),
                uses.stream().mapToDouble(SimEnergyLedger.EnergyUse::maxEnergy).max().orElse(0));
    }

    /** The per-duel energy means, named so a dashboard reader knows they are not totals. */
    private Map<String, Object> energyJson(SimEnergyLedger.EnergyUse energy) {
        final Map<String, Object> json = new LinkedHashMap<>();
        json.put("spent_on_skills_per_duel", energy.spentOnSkills());
        json.put("drained_custom_per_duel", energy.drainedCustom());
        json.put("drained_natural_per_duel", energy.drainedNatural());
        json.put("regen_natural_per_duel", energy.regenNatural());
        json.put("regen_custom_per_duel", energy.regenCustom());
        json.put("min_energy", energy.minEnergy());
        json.put("max_energy", energy.maxEnergy());
        json.put("iterations_observed", samples.stream()
                .map(Sample::energy).filter(use -> use != null && !use.isEmpty()).count());
        return json;
    }

    private static double sum(List<SimEnergyLedger.EnergyUse> uses,
                              java.util.function.ToDoubleFunction<SimEnergyLedger.EnergyUse> field) {
        return uses.stream().mapToDouble(field).sum();
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
     * primitive, a null, or a map of those, and the column is read by Grafana with {@code ->>} and
     * {@code ->} rather than deserialised into a type.
     *
     * <p>Nesting is handled recursively rather than at a fixed depth, because the activation block is
     * a map of maps and the next thing the engine learns to measure will not be flat either.
     */
    private static String toJson(Map<String, Object> extras) {
        final StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : extras.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(escape(entry.getKey())).append("\":");
            appendValue(json, entry.getValue());
        }
        return json.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendValue(StringBuilder json, Object value) {
        if (value == null) {
            json.append("null");
        } else if (value instanceof Map<?, ?> map) {
            json.append(toJson((Map<String, Object>) map));
        } else if (value instanceof Double number && !Double.isFinite(number)) {
            // A non-finite double is not valid JSON, and Postgres rejects the whole document rather
            // than the field -- which would lose an entire matchup's extras to one degenerate divide.
            json.append("null");
        } else if (value instanceof Number || value instanceof Boolean) {
            json.append(value);
        } else {
            json.append('"').append(escape(value.toString())).append('"');
        }
    }

    private static String escape(String raw) {
        return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
