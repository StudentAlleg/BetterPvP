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
import org.jetbrains.annotations.Nullable;

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

        // Same arithmetic DamageEventFinalizer.applyFinalDamage is about to do. It runs after the
        // event returns, so this is the last point at which the kill can be stopped.
        if (!event.isDamageeLiving()) {
            return;
        }
        final LivingEntity living = event.getLivingDamagee();
        if (living == null || living.getHealth() - event.getModifiedDamage() > 0.0) {
            return;
        }

        // A fake player must never actually die. PlayerDeathEvent has upwards of forty listeners
        // across the plugins, and they reasonably assume a real logged-in player: Gamer.getPlayer()
        // resolves through Bukkit.getPlayer, which returns null for a combatant that was never
        // added to the PlayerList, and BuildManager has no GamerBuilds for a UUID that never
        // logged in. Guarding each listener would mean scattering simulation awareness across the
        // codebase, which is exactly what this project is not allowed to do -- so the kill is
        // stopped here instead, in the simulator.
        //
        // Cancelling makes DamageEventProcessor return before the finalizer, so no health is
        // applied. The hit is already recorded above at its true value, and the duel is resolved
        // from that record rather than from the entity's health.
        //
        // TODO(phase 2): remove this. Suppressing the death also suppresses every on-death
        // mechanic -- SoulHarvest, BloodBarrier, Vengeance expiry -- which is harmless while
        // builds carry no skills and silently under-measures them once the catalog does. See
        // docs/balance-simulation/DESIGN.md open question 8: phase 2 raises fake-player fidelity
        // (PlayerList registration, synthesised GamerBuilds) so combatants can really die.
        recording.markKilled(damagee.getUniqueId(), System.nanoTime() - recording.startNanos);
        event.setCancelled(true);
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

        /**
         * Whoever took a blow that would have been lethal, or null while both are alive. This is
         * the duel's notion of death -- the entity itself never dies, see
         * {@link SimRecorder#onDamage}.
         */
        @Nullable
        private volatile UUID killed;

        /** Nanoseconds from duel start to the lethal blow. Only meaningful once {@link #killed} is set. */
        private volatile long killedElapsedNanos;

        private Recording(UUID combatantA, UUID combatantB) {
            this.combatantA = combatantA;
            this.combatantB = combatantB;
        }

        private synchronized void record(HitRecord hit) {
            hits.add(hit);
        }

        private void markKilled(UUID victim, long elapsedNanos) {
            // First lethal blow wins; a duel is over the moment one side would have dropped.
            if (killed == null) {
                killed = victim;
                killedElapsedNanos = elapsedNanos;
            }
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
