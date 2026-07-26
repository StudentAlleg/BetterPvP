package me.mykindos.betterpvp.balancesim.engine;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import lombok.Getter;
import me.mykindos.betterpvp.balancesim.world.SimWorldManager;
import me.mykindos.betterpvp.core.combat.events.DamageEvent;
import me.mykindos.betterpvp.core.listener.BPvPListener;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Measures duels by observing the real damage events at {@code MONITOR} priority.
 *
 * <p>Listening last means it sees exactly what the game applied: per-hit timestamp, raw and
 * final damage, and the reasons/modifiers the pipeline attached -- so mitigation stays observable
 * rather than assumed. Kills give TTK directly.
 *
 * <p>The handler only records hits between two combatants that the orchestrator has
 * {@link #startDuel registered}, and only in the sim world, so it is inert on a server where the
 * gate is closed even though it is registered. Each matchup runs N iterations to average over the
 * stochastic parts (damage min/max rolls, crits); the orchestrator reduces the per-duel
 * {@link Recording}s into the mean and percentiles that reach {@code sim_result}.
 */
@Singleton
@BPvPListener
@CustomLog
public class SimRecorder implements Listener {

    private final SimWorldManager worldManager;

    /** Every participant UUID of an in-flight duel, mapped to that duel's recording. */
    private final Map<UUID, Recording> active = new ConcurrentHashMap<>();

    @Inject
    public SimRecorder(SimWorldManager worldManager) {
        this.worldManager = worldManager;
    }

    /**
     * Begins recording a duel between two fake players. Both directions are captured: a full duel
     * has both sides swinging, and the orchestrator later reads out whichever direction the
     * {@code sim_result} row is for.
     *
     * @param combatantA one combatant's UUID
     * @param combatantB the other combatant's UUID
     * @return the recording to hand back to {@link #endDuel} once the duel resolves
     */
    public Recording startDuel(UUID combatantA, UUID combatantB) {
        final Recording recording = new Recording(combatantA, combatantB);
        active.put(combatantA, recording);
        active.put(combatantB, recording);
        return recording;
    }

    /**
     * Stops recording a duel and detaches it from the live lookup. The returned {@link Recording}
     * still holds every hit for the orchestrator to reduce.
     */
    public Recording endDuel(Recording recording) {
        active.remove(recording.combatantA);
        active.remove(recording.combatantB);
        return recording;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(DamageEvent event) {
        final Entity damagee = event.getDamagee();
        if (!worldManager.isSimWorld(damagee.getWorld())) {
            return;
        }

        final LivingEntity damager = event.getDamager();
        if (damager == null) {
            return;
        }

        final Recording recording = active.get(damager.getUniqueId());
        // Both endpoints must belong to the *same* registered duel -- never cross-record two
        // arenas, and never record damage from anything that is not a tracked combatant.
        if (recording == null || recording != active.get(damagee.getUniqueId())) {
            return;
        }

        recording.record(new HitRecord(
                damager.getUniqueId(),
                damagee.getUniqueId(),
                System.nanoTime() - recording.startNanos,
                event.getDamage(),
                event.getModifiedDamage(),
                List.of(event.getReasons())));
    }

    /**
     * A single measured hit. {@code rawDamage} is the damage before the modifier chain,
     * {@code finalDamage} is {@link DamageEvent#getModifiedDamage()} -- what the entity actually
     * lost -- and {@code reasons} is the pipeline's own breakdown of what contributed.
     *
     * @param damager     who dealt the hit
     * @param damagee     who took it
     * @param elapsedNanos nanoseconds since the duel started
     * @param rawDamage   pre-modifier damage
     * @param finalDamage post-modifier damage applied
     * @param reasons     the pipeline's reason/modifier labels for this hit
     */
    public record HitRecord(UUID damager, UUID damagee, long elapsedNanos,
                            double rawDamage, double finalDamage, List<String> reasons) {
    }

    /**
     * The hits of one duel, in arrival order. Thread-safe because the damage pipeline runs on the
     * main thread but the orchestrator reduces recordings off it.
     */
    @Getter
    public static final class Recording {

        private final UUID combatantA;
        private final UUID combatantB;
        private final long startNanos = System.nanoTime();
        private final List<HitRecord> hits = new ArrayList<>();

        private Recording(UUID combatantA, UUID combatantB) {
            this.combatantA = combatantA;
            this.combatantB = combatantB;
        }

        private synchronized void record(HitRecord hit) {
            hits.add(hit);
        }

        /** An immutable snapshot of the hits dealt by {@code damager} to {@code damagee}. */
        public synchronized List<HitRecord> hitsFrom(UUID damager, UUID damagee) {
            final List<HitRecord> filtered = new ArrayList<>();
            for (HitRecord hit : hits) {
                if (hit.damager().equals(damager) && hit.damagee().equals(damagee)) {
                    filtered.add(hit);
                }
            }
            return List.copyOf(filtered);
        }
    }
}
