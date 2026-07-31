package me.mykindos.betterpvp.balancesim.engine;

import me.mykindos.betterpvp.core.combat.events.DamageEvent;
import me.mykindos.betterpvp.core.combat.events.EntityCanHurtEntityEvent;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.TreeMap;

/**
 * Watches the damage pipeline for the length of a sweep and records where each swing died.
 *
 * <p>Exists because the recorder cannot answer the question the last four runs have been about. It
 * only ever sees hits that <em>landed</em> -- {@code DamageEventProcessor} rejects a swing at any of
 * several points before {@code DamageEvent} is fired, and every one of those rejections is invisible
 * downstream -- so a barren duel arrives at the log as a single bit: nothing was measured. That bit
 * is the same whether 600 swings were never issued, issued and refused by vanilla, refused by a
 * pairing check, cancelled by a named listener, or allowed through and modified to zero damage.
 *
 * <p>Each of those is a different bug, and each leaves a different signature in the funnel this keeps
 * on {@link SimPlayer} itself: swings taken, then vanilla events, then can-hurt checks, then
 * {@code DamageEvent}s, then damage actually allowed. The stage where the count collapses is the
 * answer. Attribution is per combatant rather than per run because a sweep runs 300 duels at once and
 * a global histogram would mix a barren duel's zeroes into 299 healthy duels' successes.
 *
 * <p>Run-wide totals are kept alongside, purely so the closing line can say whether the barren duels
 * are a minority behaving differently or the whole run quietly failing the same way. The cancel-reason
 * histogram is the one piece of free text the pipeline produces about its own refusals, and it costs a
 * map merge on the cancelled path only.
 *
 * <h2>Cost</h2>
 * Three handlers at {@code MONITOR}, each doing an {@code instanceof} and a handful of increments.
 * Registered for the duration of a run and unregistered at teardown rather than living with the
 * plugin, so a server that is not sweeping pays nothing -- which matters more than usual here, since
 * {@code DamageEvent} shares one static {@code HandlerList} with every {@code CustomCancellableEvent}
 * on the server.
 */
public final class SimDamageTelemetry implements Listener {

    private long swings;
    private long vanilla;
    private long vanillaPreCancelled;
    private long canHurt;
    private long canHurtDenied;
    private long damageEvents;
    private long damageCancelled;
    private long damageZeroed;
    private final Map<String, Long> cancelReasons = new TreeMap<>();

    /**
     * Starts watching. Returns the instance so the caller can hold it for {@link #stop()}; a
     * telemetry object that cannot be unregistered would outlive its run.
     */
    public static SimDamageTelemetry start(Plugin plugin) {
        final SimDamageTelemetry telemetry = new SimDamageTelemetry();
        plugin.getServer().getPluginManager().registerEvents(telemetry, plugin);
        return telemetry;
    }

    public void stop() {
        HandlerList.unregisterAll(this);
    }

    /** Counted here rather than in the duel loop so swings and their fate share one owner. */
    public void noteSwing(SimPlayer attacker, SimPlayer defender) {
        swings++;
        attacker.pipeSwingsMade++;
        defender.pipeSwingsTaken++;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onVanilla(EntityDamageEvent event) {
        final SimPlayer sim = asSim(event.getEntity());
        if (sim == null) {
            return;
        }
        vanilla++;
        sim.pipeVanilla++;
        // Read at MONITOR, so this is "cancelled by the time the chain finished" -- which for a sim
        // combatant is almost always DamageEventProcessor doing its unconditional cancel on the way to
        // reapplying the damage through BetterPvP's own path, and therefore not a refusal at all. It is
        // recorded because the case that matters is the opposite one: a vanilla event that never
        // reaches BetterPvP shows up as a vanilla count with no DamageEvent behind it.
        if (event.isCancelled()) {
            vanillaPreCancelled++;
            sim.pipeVanillaPreCancelled++;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCanHurt(EntityCanHurtEntityEvent event) {
        final SimPlayer sim = asSim(event.getDamagee());
        if (sim == null) {
            return;
        }
        canHurt++;
        sim.pipeCanHurt++;
        if (!event.isAllowed()) {
            canHurtDenied++;
            sim.pipeCanHurtDenied++;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onDamage(DamageEvent event) {
        final SimPlayer sim = asSim(event.getDamagee());
        if (sim == null) {
            return;
        }
        damageEvents++;
        sim.pipeDamageEvent++;
        if (event.isCancelled()) {
            damageCancelled++;
            sim.pipeDamageCancelled++;
            final String reason = event.getCancelReason() == null ? "(no reason given)" : event.getCancelReason();
            sim.pipeLastCancelReason = reason;
            cancelReasons.merge(reason, 1L, Long::sum);
            return;
        }
        // An uncancelled event that carries no damage lands as a hit the recorder will file with a
        // zero, which reads as a build that does nothing rather than as a pipeline that ate the hit.
        if (event.getDamage() <= 0.0D) {
            damageZeroed++;
        }
        sim.pipeDamageAllowed += event.getDamage();
    }

    /**
     * The run's funnel in one line, plus whatever the pipeline said when it refused something.
     *
     * <p>Reported unconditionally at the end of a sweep, including a healthy one: the numbers are only
     * interpretable against a baseline, and the run where everything worked is the baseline.
     */
    public String describe() {
        final StringBuilder out = new StringBuilder()
                .append("swings=").append(swings)
                .append(" vanillaDamageEvents=").append(vanilla)
                .append(" (cancelledByChainEnd=").append(vanillaPreCancelled).append(")")
                .append(" canHurtAsked=").append(canHurt)
                .append(" canHurtDenied=").append(canHurtDenied)
                .append(" damageEvents=").append(damageEvents)
                .append(" damageCancelled=").append(damageCancelled)
                .append(" damageAllowedButZero=").append(damageZeroed);
        if (!cancelReasons.isEmpty()) {
            out.append("\n  cancel reasons:");
            cancelReasons.forEach((reason, count) -> out.append("\n    ").append(count).append(" x ").append(reason));
        }
        return out.toString();
    }

    /**
     * Resolves a damagee to the simulation combatant behind it, or null when it is not one.
     *
     * <p>Deliberately identity-based rather than world-based. A sweep runs in its own void world and
     * nothing else should be taking damage there, but "should" is what the last four runs have been
     * disproving, and a telemetry line that quietly folded in a stray mob would be worse than none.
     */
    private static SimPlayer asSim(Entity entity) {
        return entity instanceof CraftPlayer craft && craft.getHandle() instanceof SimPlayer sim ? sim : null;
    }
}
