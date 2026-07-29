package me.mykindos.betterpvp.core.combat.delay;

import lombok.Getter;
import org.bukkit.Bukkit;

/**
 * Data for a damage delay entry.
 *
 * <p>The delay is held in <em>server ticks</em>, not wall clock. Damage can only ever be dealt
 * while a tick is being processed, so a delay expressed in milliseconds is really a delay of
 * "however many ticks happen to fit in that many milliseconds" -- and that count is not stable.
 * The default 400 ms delay is exactly 8 ticks at 20 TPS, which is the worst possible alignment:
 * whether the eighth-tick attack passes depends on sub-tick timing, specifically how far into
 * each tick the server got before processing that particular entity. The same two entities
 * fighting under identical conditions would therefore land a hit every 8 ticks or every 9,
 * essentially at random, and when the server ticks slowly the 400 ms could elapse in as few as
 * 7 ticks -- making effective attack speed a function of server load.
 *
 * <p>Counting ticks removes all of that: a delay of N ticks is N ticks regardless of TPS or of
 * where in the tick the damage is processed. Durations are still supplied in milliseconds by
 * callers ({@link me.mykindos.betterpvp.core.combat.cause.DamageCause#DEFAULT_DELAY}, skill
 * configs, attack-speed modifiers) and converted on the way in.
 */
@Getter
public class DelayData {

    private static final double MILLIS_PER_TICK = 50.0;

    /**
     * The server tick the delay was created on.
     */
    private final int startTick;

    /**
     * The duration of the delay, in server ticks.
     */
    private final long durationTicks;

    public DelayData(int startTick, long durationTicks) {
        this.startTick = startTick;
        this.durationTicks = durationTicks;
    }

    /**
     * Creates a delay starting now from a duration in milliseconds.
     *
     * <p>Rounded to the nearest tick rather than up, so the tick-quantised delay stays as close as
     * possible to the millisecond value the caller asked for instead of systematically lengthening
     * every delay in the game. Any positive duration floors at one tick, so a heavily reduced
     * delay still gates at least one tick rather than collapsing to no gate at all.
     *
     * @param durationMillis the delay duration in milliseconds
     * @return the delay entry
     */
    public static DelayData ofMillis(long durationMillis) {
        final long ticks = durationMillis <= 0
                ? 0
                : Math.max(1, Math.round(durationMillis / MILLIS_PER_TICK));
        return new DelayData(Bukkit.getCurrentTick(), ticks);
    }

    /**
     * Checks if this delay has expired
     * @return true if the delay has expired
     */
    public boolean isExpired() {
        return Bukkit.getCurrentTick() - startTick >= durationTicks;
    }

    /**
     * Gets the remaining time for this delay
     * @return the remaining time in ticks, or 0 if expired
     */
    public long getRemainingTicks() {
        return Math.max(0, durationTicks - (Bukkit.getCurrentTick() - startTick));
    }

    /**
     * Gets the remaining time for this delay, for display and for the millisecond-facing API.
     * @return the remaining time in milliseconds, or 0 if expired
     */
    public long getRemainingTime() {
        return (long) (getRemainingTicks() * MILLIS_PER_TICK);
    }
}
