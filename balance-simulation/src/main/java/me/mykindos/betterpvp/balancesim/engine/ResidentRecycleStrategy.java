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
 * <h2>Why this was ever a question</h2>
 * For eleven runs a combatant that had died could not be hurt again, and no amount of unsetting the
 * post-death state on the entity changed it. Run 140 unset three fields and 95.6% of its duels landed
 * no hit; run 141 unset everything anyone could name -- pose and dimensions out of the 0.2x0.2
 * {@code DYING} box, combat tracker, last-hurt-by, hurt and invulnerability counters, absorption,
 * fire, freeze -- and still measured nothing. Only a full respawn worked, so {@link #RESPAWN_NEW} was
 * the default and the other three were experiments.
 *
 * <p>None of that was about the entity. {@code ServerPlayer.die} calls
 * {@code connection.markClientUnloadedAfterDeath()}, and {@code ServerPlayer.isInvulnerableTo} --
 * vanilla's first gate, ahead of {@code EntityDamageEvent} and therefore ahead of everything the
 * simulator can observe -- returns true while {@code connection.hasClientLoaded()} is false. Vanilla
 * clears that flag only on the real respawn path, so an in-place revival produced an entity whose own
 * state was flawless and which the server would not let anything touch. Run 150 caught it by dumping
 * the two combatants of a barren duel side by side: identical in every field, and the one that had
 * died reported {@code invulnerableTo=true} while the one that had not took damage normally.
 *
 * <p>{@code SimPlayer.ensureClientLoaded} clears it on every path back into a duel, and all three
 * in-place strategies started working at once. Over the same 864 duels: {@link #RESPAWN_NEW} 95.7
 * ms/duel, {@link #RECYCLE} 54.0, {@link #REVIVE} 40.1, none of them barren. Runs 151 and 152 reported
 * identical pipeline counts down to the swing -- 73,254 swings, 73,254 vanilla damage events, 10,038
 * {@code DamageEvent}s -- so the cheaper strategy is not cheaper by measuring less. What is left is a
 * cost ordering:
 *
 * <ul>
 *   <li>{@link #REVIVE} -- same entity, same UUID, same level registration. Nothing is added to or
 *       removed from the level, so none of Moonrise's six area maps is touched. The default.</li>
 *   <li>{@link #RECYCLE} -- same entity and UUID, removed from the level and re-added. Works, and
 *       costs a third more than {@link #REVIVE} for the re-registration. Skips
 *       {@code ServerPlayer.<init>}, which run 142's profile put at 8.84% of the server thread, along
 *       with the entity add/remove and chunk-ticket churn behind another ~35%.</li>
 *   <li>{@link #RESPAWN_SAME_UUID} -- new entity, same UUID. Costs what {@link #RESPAWN_NEW} costs;
 *       kept as the diagnostic that separates entity-held residue from UUID-held residue, should
 *       something ever look like it is carrying between duels again.</li>
 *   <li>{@link #RESPAWN_NEW} -- new entity, new UUID. The most expensive value, and still the
 *       fallback: it is the one that cannot depend on any in-place recovery being correct.</li>
 * </ul>
 *
 * <p>The barren timeout count on the progress line remains the whole result, and a broken strategy
 * shows up within the first few hundred duels. {@code SimCombatantPool} demotes itself to
 * {@link #RESPAWN_NEW} if the configured value stops working, so trying one costs a slower sweep
 * rather than a useless one.
 */
public enum ResidentRecycleStrategy {

    /** Unset the death on the entity in place. The cheapest recovery, and the default. */
    REVIVE,

    /** Same entity and UUID, removed from the level and re-added. Works; costs a third more. */
    RECYCLE,

    /** A freshly constructed entity that keeps the old resident's UUID. A diagnostic, not a saving. */
    RESPAWN_SAME_UUID,

    /** A freshly constructed entity with a new UUID. The most expensive value, and the fallback. */
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
