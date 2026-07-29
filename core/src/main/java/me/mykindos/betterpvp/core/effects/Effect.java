package me.mykindos.betterpvp.core.effects;

import lombok.Data;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;

import java.lang.ref.WeakReference;
import java.util.function.Predicate;

@Data
public class Effect {

    private static final long MILLIS_PER_TICK = 50L;

    private final String uuid;
    private WeakReference<LivingEntity> applier;
    private final EffectType effectType;
    private final String name;
    /**
     * The server tick the effect was applied on.
     *
     * <p>Ticks rather than wall clock, for the reason {@code DelayData} and {@link
     * me.mykindos.betterpvp.core.cooldowns.Cooldown} document: an effect only does anything on a
     * tick, so a duration in milliseconds is really "however many ticks fit in that many
     * milliseconds" -- a count that falls as the server falls behind. A 5-second wither ticked
     * fewer times on a loaded server than a healthy one, which is exactly the kind of load-dependent
     * outcome that makes a simulated fight untrustworthy.
     */
    private int startTick;
    /**
     * The effect's duration in server ticks, including the one-tick grace the constructor adds.
     *
     * <p>Signed on purpose. A sufficiently negative duration never expires, which is how callers
     * request an effect that only ends when something removes it; the old code expressed the same
     * thing as {@code rawLength >= 0} over a millisecond value.
     */
    private long durationTicks;
    private int amplifier;
    private boolean permanent;
    private boolean showParticles;
    private Predicate<LivingEntity> removalPredicate;


    /**
     * Constructs a new Effect instance with the given parameters.
     *
     * @param uuid the unique identifier for this effect
     * @param applier the entity that applied this effect
     * @param effectType the type of the effect
     * @param name the name of the effect
     * @param amplifier the amplifier level of the effect
     * @param length the duration of the effect in milliseconds
     * @param permanent indicates whether the effect is permanent
     * @param showParticles indicates whether particles should be shown while the effect is active
     * @param removalPredicate a predicate that determines whether the effect should be removed based on the entity's state
     */
    public Effect(String uuid, LivingEntity applier, EffectType effectType, String name, int amplifier, long length, boolean permanent, boolean showParticles, Predicate<LivingEntity> removalPredicate) {
        this.uuid = uuid;
        this.applier = new WeakReference<>(applier);
        this.effectType = effectType;
        this.name = name;
        this.durationTicks = toDurationTicks(length);
        this.startTick = Bukkit.getCurrentTick();
        this.amplifier = amplifier;
        this.permanent = permanent;
        this.showParticles = showParticles;
        this.removalPredicate = removalPredicate;
    }

    /**
     * Updates the length of the effect and restarts it from now.
     *
     * <p>Still takes milliseconds: every caller across the codebase configures effect durations in
     * milliseconds, and only the representation underneath changed.
     *
     * @param length the base length of the effect in milliseconds
     */
    public void setLength(long length) {
        this.durationTicks = toDurationTicks(length);
        this.startTick = Bukkit.getCurrentTick();
    }

    /**
     * Converts a caller's millisecond duration to ticks, preserving the one-tick grace the original
     * implementation added as {@code + 50} milliseconds, and preserving the sign so a very negative
     * duration still means "does not expire".
     */
    private static long toDurationTicks(long millis) {
        return Math.round((millis + MILLIS_PER_TICK) / (double) MILLIS_PER_TICK);
    }

    /** Ticks elapsed since the effect was applied. */
    private long elapsedTicks() {
        return Math.max(0L, (long) Bukkit.getCurrentTick() - startTick);
    }

    /**
     * The effect's full duration in milliseconds.
     *
     * <p>Retained under its original name and unit so callers that scale a duration -- Resilience's
     * reduction, Silence's readout -- keep working unchanged.
     */
    public long getRawLength() {
        return durationTicks * MILLIS_PER_TICK;
    }

    /**
     * Determines whether the effect has expired based on its length, current time,
     * and whether it is marked as permanent.
     *
     * @return true if the effect is not permanent, its raw length is non-negative,
     *         and the remaining time has elapsed; false otherwise.
     */
    public boolean hasExpired() {
        return durationTicks >= 0 && elapsedTicks() >= durationTicks && !permanent;
    }

    /**
     * Calculates and returns the remaining duration of the effect in milliseconds.
     * The remaining duration is computed by subtracting the current system time
     * in milliseconds from the length of the effect.
     *
     * @return the remaining duration of the effect in milliseconds
     */
    public long getRemainingDuration() {
        return (durationTicks - elapsedTicks()) * MILLIS_PER_TICK;
    }

    /**
     * Calculates and returns the "vanilla" duration of the effect, measured in ticks.
     * The vanilla duration is computed based on the raw length of the effect's duration.
     * If the effect is permanent, it returns -1.
     *
     * @return the vanilla duration in ticks, or -1 if the effect is permanent
     */
    public int getVanillaDuration() {
        // Vanilla potion durations were always ticks; this used to round-trip through milliseconds
        // to get back to the number the effect is now stored in.
        return permanent ? -1 : (int) durationTicks;
    }

    /**
     * Calculates the remaining duration of the effect in vanilla ticks.
     * If the effect is permanent, returns -1.
     * The conversion to vanilla ticks assumes 20 ticks per second.
     *
     * @return The remaining duration in vanilla ticks, or -1 if the effect is permanent.
     */
    public int getRemainingVanillaDuration() {
        return permanent ? -1 : (int) (durationTicks - elapsedTicks());
    }
}
