package me.mykindos.betterpvp.core.cooldowns;


import lombok.Data;
import me.mykindos.betterpvp.core.utilities.UtilTime;
import org.bukkit.Bukkit;

import java.util.function.Consumer;

@Data
public class Cooldown {

    /**
     * The unique identifier or name for the cooldown.
     * This value is immutable once set and is typically used
     * to distinguish between different cooldown instances.
     */
    private final String name;
    /**
     * The duration of the cooldown, in <em>server ticks</em>.
     *
     * <p>A double so that a reduction (the cooldown-reduction effect, the diamond gem) can scale it
     * by a fraction without quantising to a whole tick on every application.
     *
     * <p>This replaced a field named {@code seconds} that in fact held milliseconds -- the
     * constructor multiplied its seconds argument by 1000 on the way in, so every reader had to
     * remember to divide by 1000 again, and the two that render the progress bar did. The public
     * accessors here are named for the unit they actually return.
     */
    private double durationTicks;
    /**
     * The server tick this cooldown was started on.
     *
     * <p>Ticks rather than a wall-clock stamp. A cooldown is a piece of game logic: it only counts
     * down while the server is ticking, and everything it gates -- ability use, charge accrual --
     * only happens on a tick. Measured in milliseconds, "5 seconds" silently means "however many
     * ticks fit in 5 seconds", which is 100 on a healthy server and fewer on a loaded one, so
     * abilities came off cooldown in fewer game ticks exactly when the server was struggling. In
     * ticks the gate is exact and a fight replays identically regardless of tick rate, which is
     * what makes a simulation of it trustworthy.
     */
    private final int startTick;
    /**
     * Indicates whether this cooldown should be removed automatically when the associated
     * entity experiences a death-related event. If set to true, the cooldown will be cleared
     * upon death, otherwise it will persist.
     */
    private final boolean removeOnDeath;
    /**
     * Indicates whether a notification or alert should be sent when the cooldown is triggered.
     * This property is immutable and determined at the time of object creation.
     */
    private final boolean inform;
    /**
     * Indicates whether the cooldown can be cancelled manually before it expires.
     * If set to true, the cooldown is cancellable, otherwise it cannot be manually interrupted.
     */
    private boolean cancellable;
    /**
     * A callback function that is triggered when the cooldown expires.
     * The callback accepts the specific {@link Cooldown} instance as a parameter.
     */
    private Consumer<Cooldown> onExpire;

    /**
     * Constructs a new Cooldown instance with the specified parameters.
     *
     * @param name the name of the cooldown, used to identify it
     * @param d the duration of the cooldown in seconds
     * @param systime the system time (in milliseconds) at which the cooldown starts
     * @param removeOnDeath whether the cooldown should be removed upon death
     * @param inform whether to inform when the cooldown is initialized or modified
     */
    public Cooldown(String name, double d, boolean removeOnDeath, boolean inform) {
        this(name, d, removeOnDeath, inform, false, null);
    }

    /**
     * Constructs a new cooldown instance with the specified parameters.
     *
     * @param name the name of the cooldown
     * @param d the duration of the cooldown in seconds
     * @param systime the system time at which the cooldown starts
     * @param removeOnDeath whether the cooldown should be removed upon death
     * @param inform whether to inform about the cooldown's state
     * @param cancellable whether the cooldown is cancellable
     */
    public Cooldown(String name, double d, boolean removeOnDeath, boolean inform, boolean cancellable) {
        this(name, d, removeOnDeath, inform, cancellable, null);
    }

    /**
     * Creates a new Cooldown object with the specified parameters.
     *
     * @param name the name of the cooldown
     * @param d the duration of the cooldown, in seconds
     * @param systime the system time when the cooldown was started, in milliseconds
     * @param removeOnDeath a flag indicating whether the cooldown should be removed upon death
     * @param inform a flag indicating whether notifications should be sent when the cooldown expires
     * @param cancellable a flag indicating whether the cooldown can be canceled before it expires
     * @param onExpire a consumer that will be executed when the cooldown expires
     */
    public Cooldown(String name, double d, boolean removeOnDeath, boolean inform, boolean cancellable, Consumer<Cooldown> onExpire) {
        this(name, d, Bukkit.getCurrentTick(), removeOnDeath, inform, cancellable, onExpire);
    }

    /**
     * Creates a cooldown that started on a specific tick.
     *
     * <p>Only for rebuilding a cooldown whose start needs shifting, as
     * {@code CooldownManager.reduceCooldown} does. Everything else should start it now.
     *
     * @param startTick the server tick the cooldown started on
     */
    public Cooldown(String name, double d, int startTick, boolean removeOnDeath, boolean inform, boolean cancellable, Consumer<Cooldown> onExpire) {
        this.name = name;
        this.durationTicks = d * UtilTime.TICKS_PER_SECOND;
        this.startTick = startTick;
        this.removeOnDeath = removeOnDeath;
        this.inform = inform;
        this.cancellable = cancellable;
        this.onExpire = onExpire;
    }

    /** The cooldown's full duration in seconds, as it was configured. */
    public double getDurationSeconds() {
        return durationTicks / (double) UtilTime.TICKS_PER_SECOND;
    }

    /**
     * Rescales the cooldown's duration.
     *
     * <p>Takes seconds because that is the unit every caller of this thinks in -- the
     * cooldown-reduction effect and the diamond gem both scale the configured duration.
     */
    public void setDurationSeconds(double seconds) {
        this.durationTicks = seconds * UtilTime.TICKS_PER_SECOND;
    }

    /** How many ticks the cooldown still has to run; zero once it has expired. */
    public double getRemainingTicks() {
        return Math.max(0.0, durationTicks - (Bukkit.getCurrentTick() - (double) startTick));
    }

    /**
     * The remaining time in seconds until the cooldown expires, rounded to one decimal place.
     *
     * <p>Still seconds, and still rounded, because this feeds the cooldown bar and the "not ready"
     * messages; only the basis underneath it changed. Note it no longer goes negative -- an expired
     * cooldown reads exactly 0 rather than an ever-growing negative, which every existing caller
     * already treated identically by clamping with {@code Math.max(0, ...)} or testing {@code <= 0}.
     *
     * @return the remaining time in seconds, zero if the cooldown has expired
     */
    public double getRemaining() {
        // Rounded arithmetically rather than through UtilTime.trim, which allocates a locale
        // NumberFormat and round-trips the value through a String. This is called for every
        // cooldown of every player on every tick by processCooldowns and again by the action bar.
        return Math.round(getRemainingTicks() / (double) UtilTime.TICKS_PER_SECOND * 10.0) / 10.0;
    }

    /** Whether the cooldown has finished. */
    public boolean hasExpired() {
        return getRemainingTicks() <= 0;
    }

}
