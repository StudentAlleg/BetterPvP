package me.mykindos.betterpvp.balancesim.engine;

import lombok.CustomLog;
import lombok.Getter;
import org.bukkit.Bukkit;

import java.util.Arrays;
import java.util.function.DoubleSupplier;

/**
 * Holds the sweep's concurrency at the most duels the server can carry without missing its tick
 * budget.
 *
 * <h2>What this is for, and what it is not for</h2>
 * The obvious motivation is throughput, and that is the one it does <em>not</em> deliver. Model the
 * sweep: a duel lasts a fixed number of ticks (the timeout is converted to ticks and every rotation
 * is tick-driven), the server does a fixed amount of work per duel, and a tick costs
 * {@code F + P*C} milliseconds for fixed overhead {@code F}, per-duel cost {@code P} and concurrency
 * {@code C}. Duels finished per second is then
 *
 * <pre>{@code   C/D * 1000/(F + P*C)   ->   1000/(D*P)   as C grows }</pre>
 *
 * which is monotonically <em>increasing</em> in {@code C} and merely saturates. Against the figures
 * a real sweep measured -- roughly 7 ms/tick of duel-independent overhead and 0.17 ms/tick per duel
 * -- moving from 300 concurrent duels to 150 costs about 11% of throughput rather than gaining any.
 * Raising concurrency is the throughput lever, not lowering it, and a governor that targets 20 TPS
 * is deliberately giving some of that up.
 *
 * <p>What it buys instead is that the numbers mean something. Around 130 files across champions and
 * core drive game logic off {@code System.currentTimeMillis} rather than off ticks, so at 10 TPS a
 * 2000 ms cooldown elapses in about 20 ticks instead of 40 and every skill gated on one fires at
 * roughly twice its intended rate. A degraded window is not a slower measurement of the same game,
 * it is a faster measurement of a different one -- and nothing on the row says which happened. So
 * the target is a correctness knob wearing a performance knob's clothes.
 *
 * <p>Which is also why the target is configurable rather than pinned at 50 ms. A run whose purpose
 * is throughput can be pointed at a looser budget and will find the largest concurrency that holds
 * it; the linear model above stops holding at the extremes, where garbage collection and the
 * residency cap turn into cliffs, and finding that edge by measurement beats guessing at it.
 *
 * <h2>The control law</h2>
 * Additive increase, multiplicative decrease, the same shape as TCP congestion control and for the
 * same reason: overshoot is much more expensive than undershoot, so back off fast and creep back up.
 *
 * <h2>The signal, and the trap in it</h2>
 * The input is <em>tick processing time</em> from {@code Bukkit.getTickTimes()}, not the wall-clock
 * gap between ticks. The distinction is the whole ballgame: a healthy server sleeps out the
 * remainder of its 50 ms budget, so the interval between ticks is <em>pinned at 50 ms</em> and never
 * goes below it however idle the machine is. A controller fed that number can never observe headroom
 * -- every healthy tick reads as exactly at budget -- so it would refuse to grow, and against any
 * target under 50 ms it would read 50 > target and cut on a perfectly healthy server, every time,
 * until it hit the floor. Tick <em>times</em> measure the work rather than the period and fall to
 * whatever the server is actually spending, which is the only reading with headroom in it.
 *
 * <ul>
 *   <li><b>Median, not mean.</b> Over Paper's own 100-sample tick-time ring. A garbage collection
 *       pause is one sample of 80 ms in a window that is otherwise fine, and a mean would react to it
 *       by cutting concurrency the sweep did not need to lose.</li>
 *   <li><b>Adjust every {@link #ADJUST_INTERVAL_TICKS}, not every tick.</b> A change takes time to
 *       show up: new duels arrive at the setup rate, and a cut only takes effect as existing duels
 *       finish. Reacting faster than the system responds is how a controller oscillates.</li>
 *   <li><b>A cut is a ceiling, not a cull.</b> Lowering the limit never stops a duel that is already
 *       in flight -- those are real measurements seconds from completing, and throwing them away
 *       would mean re-running them. So a decrease is applied by starvation and lands over the
 *       following seconds rather than immediately, which is another reason to cut hard.</li>
 *   <li><b>A deadband.</b> Growth needs the median to be under the target by
 *       {@link #GROWTH_HEADROOM_FRACTION}, so the controller settles instead of hunting across the
 *       boundary forever.</li>
 * </ul>
 */
@CustomLog
public final class ConcurrencyGovernor {

    /**
     * How often the limit is reconsidered.
     *
     * <p>Paper's tick-time ring holds 100 samples, so this is one full window: each decision sees
     * data that does not overlap the one before it, and the controller cannot react twice to the same
     * spike.
     */
    private static final int ADJUST_INTERVAL_TICKS = 100;

    /**
     * How far under target the median must sit before the limit grows, as a fraction of target.
     *
     * <p>Without it the controller would raise the limit the moment it was one millisecond under
     * budget, overshoot, cut, and repeat -- a sweep that spends its life oscillating rather than
     * measuring at a stable concurrency, which would itself be a source of variance between duels.
     */
    private static final double GROWTH_HEADROOM_FRACTION = 0.08;

    /** Duels added per adjustment when there is headroom. Small: the cost of overshoot is a cut. */
    private static final int GROWTH_STEP = 8;

    /** Fraction of the limit kept when over budget. Aggressive, because a cut lands slowly. */
    private static final double DECAY_FACTOR = 0.85;

    private final int floor;
    private final int ceiling;
    private final double targetMillis;

    /**
     * Median tick processing time in milliseconds, or a negative value when it is not yet knowable.
     *
     * <p>A supplier rather than a direct {@code Bukkit} call so the control law can be driven with
     * synthetic tick times in a test. The law is the part that fails quietly -- a governor that never
     * grows looks like a slow machine -- and it should not need a running server to exercise.
     */
    private final DoubleSupplier medianMspt;

    private int ticksSinceAdjust;

    /** Last median actually acted on, for the progress line. Negative until the first adjustment. */
    private double lastObservedMspt = -1;

    /** The live limit, which is what {@code fill()} budgets against. */
    @Getter
    private int limit;

    /** Running extremes and mean, so the run can report what it actually sustained. */
    @Getter
    private int lowestLimit;
    @Getter
    private int highestLimit;
    private long limitSum;
    private long limitSamples;

    /**
     * @param floor        fewest duels the sweep will be reduced to, however bad the tick rate gets.
     *                     A sweep that governed itself down to nothing would stop measuring rather
     *                     than measure slowly, and the cause would be invisible: the progress line
     *                     would simply stop advancing
     * @param ceiling      most duels the sweep may run, which is {@code maxConcurrentDuels}. Never
     *                     exceeded, because arenas are warmed up to exactly this many before the
     *                     first duel and generating another mid-run is seconds of main-thread work
     * @param targetMillis tick budget to hold the median under
     */
    public ConcurrencyGovernor(int floor, int ceiling, double targetMillis, int start,
                               DoubleSupplier medianMspt) {
        this.floor = Math.max(1, Math.min(floor, ceiling));
        this.ceiling = Math.max(1, ceiling);
        this.targetMillis = targetMillis;
        this.medianMspt = medianMspt;
        this.limit = Math.max(this.floor, Math.min(this.ceiling, start));
        this.lowestLimit = this.limit;
        this.highestLimit = this.limit;
    }

    /** A governor reading the live server's tick times. */
    public static ConcurrencyGovernor adaptive(int floor, int ceiling, double targetMillis, int start) {
        return new ConcurrencyGovernor(floor, ceiling, targetMillis, start,
                ConcurrencyGovernor::serverMedianMspt);
    }

    /**
     * Median of Paper's last 100 tick times, in milliseconds, or -1 if it cannot be read yet.
     *
     * <p>Zero entries are dropped rather than counted. The ring is pre-sized and starts full of
     * zeroes, so a sweep that consulted it in its first seconds would see a median near zero, read
     * unlimited headroom and grow straight to the ceiling on no evidence at all.
     */
    private static double serverMedianMspt() {
        final long[] nanos = Bukkit.getTickTimes();
        if (nanos == null || nanos.length == 0) {
            return -1;
        }
        final long[] populated = Arrays.stream(nanos).filter(sample -> sample > 0).sorted().toArray();
        // Half the ring is a low bar, but the alternative is a controller that never acts on a server
        // that has only just started ticking.
        if (populated.length < nanos.length / 2) {
            return -1;
        }
        return populated[populated.length / 2] / 1_000_000.0;
    }

    /**
     * A governor that never moves, for a run with the adaptive knob off.
     *
     * <p>Returned rather than making the caller null-check, so the tick loop has one code path and a
     * fixed-concurrency run is exactly an adaptive one whose floor and ceiling coincide.
     */
    public static ConcurrencyGovernor fixed(int concurrency) {
        return new ConcurrencyGovernor(concurrency, concurrency, Double.MAX_VALUE, concurrency, () -> -1);
    }

    /** Whether this governor can actually move the limit. False for {@link #fixed}. */
    public boolean isAdaptive() {
        return floor != ceiling;
    }

    /**
     * Counts the tick and, on the adjustment boundary, moves the limit.
     *
     * <p>Called once per tick from the sweep loop. The signal is read from the server's own tick
     * times rather than from {@code Bukkit.getTPS()}: that is a one-minute rolling average, two
     * orders of magnitude slower than this needs to react, and it would still be reporting a healthy
     * server well after the sweep had buried it.
     */
    public void observe() {
        limitSum += limit;
        limitSamples++;
        if (!isAdaptive() || ++ticksSinceAdjust < ADJUST_INTERVAL_TICKS) {
            return;
        }
        ticksSinceAdjust = 0;
        final double median = medianMspt.getAsDouble();
        if (median < 0) {
            // Not enough tick history yet. Holding still is the safe answer: acting on a signal that
            // is not there is how a controller ends up at a limit nothing justified.
            return;
        }
        lastObservedMspt = median;
        adjust(median);
    }

    private void adjust(double median) {
        final int before = limit;
        if (median > targetMillis) {
            limit = Math.max(floor, (int) Math.round(limit * DECAY_FACTOR));
        } else if (median < targetMillis * (1.0 - GROWTH_HEADROOM_FRACTION)) {
            limit = Math.min(ceiling, limit + GROWTH_STEP);
        }
        if (limit == before) {
            return;
        }
        lowestLimit = Math.min(lowestLimit, limit);
        highestLimit = Math.max(highestLimit, limit);
        // Kept quiet by being rare -- once per five seconds at most, and only when the limit actually
        // moved. A sweep that is hunting rather than settling shows up here as a stream of alternating
        // lines, which is the symptom worth being able to see.
        log.info("Sim concurrency {} -> {} (median tick time {}ms against a {}ms target)",
                before, limit, String.format("%.1f", median),
                String.format("%.0f", targetMillis)).submit();
    }

    /** Median tick processing time last acted on, or 0 before the first adjustment. */
    public double medianTickMillis() {
        return Math.max(0, lastObservedMspt);
    }

    /** Mean limit across the run, which is what its throughput was actually achieved at. */
    public double meanLimit() {
        return limitSamples == 0 ? limit : (double) limitSum / limitSamples;
    }
}
