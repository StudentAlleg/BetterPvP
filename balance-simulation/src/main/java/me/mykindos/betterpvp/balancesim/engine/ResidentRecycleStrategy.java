package me.mykindos.betterpvp.balancesim.engine;

import java.util.Locale;
import java.util.Optional;

/**
 * How a slot's resident is made fit to fight again after it has died.
 *
 * <p>A knob rather than a decision because the decision cannot currently be made from evidence. Since
 * phase 3 stopped intercepting lethal blows, a duel kills its defender essentially every time, so this
 * choice is paid once per duel and is the single largest non-combat cost in the sweep: run 142's
 * profile put entity add and remove at 25.1% of the server thread, and the chunk ticket churn they
 * provoke at most of another 10.2%. Getting off {@link #RESPAWN_NEW} is worth about a third of the
 * sweep's throughput.
 *
 * <h2>What is actually known</h2>
 * A combatant that has died cannot be hurt again, and no amount of unsetting the post-death state on
 * the entity changes that. Run 140 unset three fields and 95.6% of its duels landed no hit; run 141
 * unset everything anyone could name -- pose and dimensions out of the 0.2x0.2 {@code DYING} box,
 * combat tracker, last-hurt-by, hurt and invulnerability counters, absorption, fire, freeze -- and
 * came out at 49.2%, the exact alternating pattern of a slot that works once with fresh residents and
 * never again. Run 142 replaced the dead resident outright and measured 0 barren duels in 32,713.
 *
 * <p>So a new entity with a new UUID is known to work and a revived one is known not to. What run 141
 * could not separate is <em>why</em>, because replacement changes the entity and the UUID together.
 * These four values pull those apart:
 *
 * <ul>
 *   <li>{@link #REVIVE} -- same entity, same UUID, same level registration. Known broken; kept because
 *       it is the control, and because if a later change fixes the underlying cause this is the value
 *       that shows it.</li>
 *   <li>{@link #RECYCLE} -- same entity and UUID, but removed from the level and re-added. If this
 *       works, the residue is level or entity-tracking registration, and the fix is nearly free: it
 *       skips {@code ServerPlayer.<init>}, which is 8.84% of the server thread and carries the
 *       advancement listener registration and the stats-file lookup with it.</li>
 *   <li>{@link #RESPAWN_SAME_UUID} -- new entity, same UUID. If this works but {@link #RECYCLE} does
 *       not, the residue is held against the entity object -- a {@code WeakHashMap<Player, ?>} in a
 *       skill that does not override {@code invalidatePlayer}, or something similar. If it
 *       <em>fails</em>, the residue is keyed by UUID and {@code SimStatePurge} is missing a manager.</li>
 *   <li>{@link #RESPAWN_NEW} -- new entity, new UUID. Run 142's behaviour, and the only value proven
 *       to work, which is why it is the default and the fallback.</li>
 * </ul>
 *
 * <p>One short {@code MELEE} sweep per value answers this: the barren timeout count on the progress
 * line is the whole result, and a broken strategy shows up within the first few hundred duels.
 * {@code SimCombatantPool} demotes itself to {@link #RESPAWN_NEW} if the value it was given turns out
 * not to work, so an experiment costs a slower sweep rather than a useless one.
 */
public enum ResidentRecycleStrategy {

    /** Unset the death on the entity in place. The control; known not to work. */
    REVIVE,

    /** Same entity and UUID, removed from the level and re-added. */
    RECYCLE,

    /** A freshly constructed entity that keeps the old resident's UUID. */
    RESPAWN_SAME_UUID,

    /** A freshly constructed entity with a new UUID. Proven, and the fallback. */
    RESPAWN_NEW;

    /**
     * Parses a config value, case-insensitively.
     *
     * @return empty when the name is not a strategy, so the caller can fall back loudly rather than
     *         silently running a different experiment from the one that was configured
     */
    public static Optional<ResidentRecycleStrategy> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
