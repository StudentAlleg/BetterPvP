package me.mykindos.betterpvp.balancesim.engine;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import lombok.CustomLog;
import lombok.Getter;
import me.mykindos.betterpvp.balancesim.world.SimWorldManager;
import me.mykindos.betterpvp.champions.champions.skills.types.EnergySkill;
import me.mykindos.betterpvp.core.Core;
import me.mykindos.betterpvp.core.combat.events.DamageEvent;
import me.mykindos.betterpvp.core.components.champions.events.PlayerUseSkillEvent;
import me.mykindos.betterpvp.core.cooldowns.CooldownManager;
import me.mykindos.betterpvp.core.energy.EnergyService;
import me.mykindos.betterpvp.core.energy.events.DegenerateEnergyEvent;
import me.mykindos.betterpvp.core.energy.events.EnergyEvent;
import me.mykindos.betterpvp.core.energy.events.RegenerateEnergyEvent;
import me.mykindos.betterpvp.core.listener.BPvPListener;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
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

    /**
     * Core's cooldown manager, used only to tell an energy refusal from a cooldown refusal.
     *
     * <p>From Core's injector rather than injected, for the reason {@code SimStatePurge} documents at
     * length: this plugin's injector is a sibling of Champions' under Core, none of Core's singletons
     * is bound in it, and a just-in-time binding here would hand back a second, empty
     * {@code CooldownManager} whose answers are unrelated to the one the skill listeners use.
     */
    private final CooldownManager cooldownManager;

    /**
     * Core's energy service, read for the current and maximum energy behind an observed flow.
     *
     * <p>From Core's injector for the same reason as the cooldown manager above: a just-in-time
     * binding would build a second service with an empty energy map, whose answers would have nothing
     * to do with the one the skill listeners spend from.
     *
     * <p>Only ever <em>read</em> here. The flows themselves come off the events, so nothing in this
     * class re-implements {@code EnergyService.use}'s decision.
     */
    private final EnergyService energyService;

    /** Every participant UUID of an in-flight duel, mapped to that duel's recording. */
    private final Map<UUID, Recording> active = new ConcurrentHashMap<>();

    @Inject
    public SimRecorder(SimWorldManager worldManager) {
        this.worldManager = worldManager;
        final Injector core = JavaPlugin.getPlugin(Core.class).getInjector();
        this.cooldownManager = core.getInstance(CooldownManager.class);
        this.energyService = core.getInstance(EnergyService.class);
    }

    /**
     * Notes that a rotation pressed {@code skillName}'s button for {@code combatant}.
     *
     * <p>Called by the rotation policy rather than observed, because it is the one part of an
     * activation that no listener can see: a press the rotation rate-limits away, or one that reaches
     * a chain which drops it before any event fires, produces nothing to listen for. An attempt count
     * of zero is what tells the audit a skill was never driven, as opposed to driven and useless.
     *
     * <p>Silently does nothing when the combatant is not in a registered duel, so a rotation running
     * during setup or teardown cannot file counts against a fight that is not being measured.
     */
    public void noteActivationAttempt(UUID combatant, String skillName) {
        final Recording recording = active.get(combatant);
        if (recording != null) {
            recording.skills.attempted(combatant, skillName);
        }
    }

    /**
     * Begins recording a duel between two fake players. Both directions are captured: a full duel
     * has both sides swinging, and the orchestrator later reads out whichever direction the
     * {@code sim_result} row is for.
     *
     * @param combatantA the <em>attacker</em>'s UUID. The order matters: a {@code sim_result} row
     *                   describes the attacker's build, so {@code energy_limited} is only set for
     *                   refusals on this side
     * @param combatantB the defender's UUID
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
                Bukkit.getCurrentTick() - recording.startTick,
                event.getDamage(),
                event.getModifiedDamage(),
                List.of(event.getReasons())));

        // Same arithmetic DamageEventFinalizer.applyFinalDamage is about to do, run one step ahead of
        // it so the kill is timestamped at the blow that caused it rather than at whichever sweep tick
        // noticed the corpse. The blow is NOT stopped: the entity goes on to die for real.
        if (!event.isDamageeLiving()) {
            return;
        }
        final LivingEntity living = event.getLivingDamagee();
        if (living == null || living.getHealth() - event.getModifiedDamage() > 0.0) {
            return;
        }

        // Phase 2 cancelled the event here, so that a fake player never actually died. That bought
        // isolation from PlayerDeathEvent's ~47 listeners and cost every on-death mechanic in the
        // game -- SoulHarvest, BloodBarrier, Riposte, SeismicSlam, MagneticAxe -- all of which are
        // actives, and so all of which phase 3's rotation policy can now cast. A build whose value is
        // partly realised on kill would be under-measured with nothing on the row to say so, which is
        // the failure mode this project exists to remove.
        //
        // So the death is allowed, and the surface is closed where it should be. Only core and
        // champions are on a dev simulation server's classpath (the `devsimulation` output bucket), and
        // of their death handlers exactly three touch anything durable: RoleStatsListener writes
        // role kill/death data, UUIDController writes an item log, and core's DeathListener fans a
        // death message out to every online player. All three now check SimulatedEntity, at the
        // chokepoint rather than scattered through the mechanics. Everything else either writes through
        // SimClient (which records nothing) or is a mechanic whose firing is the point.
        //
        // The recording still owns the duel's notion of death, because it is the only one that knows
        // which tick the lethal blow landed on; SimDeathListener keeps the death itself silent and
        // litter-free, and SimCombatantPool revives the loser in place.
        recording.markKilled(damagee.getUniqueId(), Bukkit.getCurrentTick() - recording.startTick);
    }

    /**
     * Notes when a combatant's rotation stalled on energy rather than on a cooldown.
     *
     * <p>{@code MONITOR} priority, so the verdict is whatever the real gates decided.
     * {@code SkillListener.onUseSkill} runs at {@code HIGH} and cancels for three separable reasons:
     * the skill is on cooldown, the skill costs energy the player does not have, or
     * {@code canUse} refused. Only the first two apply to a combatant fighting in an empty void
     * world, and they are distinguishable after the fact -- a cooldown refusal leaves the cooldown
     * running, an energy refusal does not consume one. So a cancelled use of an energy skill with no
     * cooldown outstanding is an energy refusal.
     *
     * <p>That is an inference rather than a reading, and it is a deliberate one: the alternative is
     * for the simulator to compare {@code EnergyService.getEnergy} against
     * {@code EnergySkill.getEnergy} itself, which is re-deriving the rule
     * {@code EnergyService.use} owns -- the class of drift this project exists to remove. The
     * inference is confined to a boolean diagnostic column; every figure that carries a number is
     * still measured.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onUseSkill(PlayerUseSkillEvent event) {
        final Player player = event.getPlayer();
        final Recording recording = active.get(player.getUniqueId());
        if (recording == null) {
            return;
        }

        final String skillName = event.getSkill().getName();
        final boolean onCooldown = cooldownManager.hasCooldown(player, skillName);
        final SimActivationOutcome outcome = classify(event, onCooldown);
        recording.skills.outcome(player.getUniqueId(), skillName, outcome);

        // The energy_limited column predates the ledger and keeps its original, narrower meaning:
        // the attacker only, because sim_result describes the attacker's build and a defender running
        // dry would set a column that claims something about the wrong side. The ledger records both
        // sides, so the defensive audit is not blocked by this column's asymmetry.
        if (outcome == SimActivationOutcome.ENERGY
                && recording.getCombatantA().equals(player.getUniqueId())) {
            recording.markEnergyLimited();
        }
    }

    /**
     * What the real chain did with this use.
     *
     * <p>The cooldown/energy split is the inference {@link #onUseSkill} has always made, unchanged
     * and for the same reason: {@code SkillListener.onUseSkill} cancels for three separable causes and
     * only two of them can apply to a combatant fighting in an empty void world, so a cancelled use of
     * an energy skill with no cooldown outstanding is an energy refusal. Reading
     * {@code EnergyService.getEnergy} against {@code EnergySkill.getEnergy} instead would re-derive
     * the rule {@code EnergyService.use} owns.
     *
     * <p>{@code DECLINED} is the residue, and it is deliberately not folded into either named cause.
     * Neither refusal should be able to produce it, so a build accumulating declines is evidence that
     * this engine is driving the skill wrongly -- exactly the finding that must not be silently
     * relabelled as a balance result.
     */
    private static SimActivationOutcome classify(PlayerUseSkillEvent event, boolean onCooldown) {
        if (!event.isCancelled()) {
            return SimActivationOutcome.SUCCESS;
        }
        if (onCooldown) {
            return SimActivationOutcome.COOLDOWN;
        }
        if (event.getSkill() instanceof EnergySkill) {
            return SimActivationOutcome.ENERGY;
        }
        return SimActivationOutcome.DECLINED;
    }

    /**
     * Records energy leaving a combatant, at {@code MONITOR} so the amount is the one that was
     * actually deducted rather than the one the caller asked for.
     *
     * <p>{@code DegenerateEnergyEvent} is both cancellable and mutable -- {@code EnergyService}
     * reduces by {@code event.getEnergy()} after the call -- so reading it any earlier would record a
     * figure no combatant ever paid.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDegenerateEnergy(DegenerateEnergyEvent event) {
        note(event.getPlayer(), event.getEnergy(), event.getCause(), true);
    }

    /** Records energy arriving at a combatant. Same reasoning as {@link #onDegenerateEnergy}. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRegenerateEnergy(RegenerateEnergyEvent event) {
        note(event.getPlayer(), event.getEnergy(), event.getCause(), false);
    }

    /**
     * Files one energy flow against the duel the player is fighting, and samples their current and
     * maximum energy while there is a reason to look.
     *
     * <p>The maximum is what makes {@code Energy Pool} measurable: it raises max energy through
     * {@code UpdateMaxEnergyEvent} and moves no energy at all, so it appears in no flow and in no
     * damage figure. Without this sample its entire effect would be invisible to the sweep.
     */
    private void note(Player player, double amount, EnergyEvent.Cause cause, boolean outgoing) {
        final UUID uuid = player.getUniqueId();
        final Recording recording = active.get(uuid);
        if (recording == null) {
            return;
        }
        if (outgoing) {
            recording.energy.degenerated(uuid, amount, cause);
        } else {
            recording.energy.regenerated(uuid, amount, cause);
        }
        recording.energy.observed(uuid, energyService.getEnergy(uuid), energyService.getMax(uuid));
    }

    /**
     * A single measured hit. {@code rawDamage} is the damage before the modifier chain,
     * {@code finalDamage} is {@link DamageEvent#getModifiedDamage()} -- what the entity actually
     * lost -- and {@code reasons} is the pipeline's own breakdown of what contributed.
     *
     * <p>Time is counted in server ticks, not wall clock. That is the resolution combat actually
     * has -- the damage delay, cooldowns and skill durations are all tick-quantised -- so a
     * measurement in ticks is exact where one in nanoseconds only looked precise, carrying
     * scheduler jitter and GC pauses into figures that should be reproducible between runs.
     *
     * @param damager      who dealt the hit
     * @param damagee      who took it
     * @param elapsedTicks server ticks since the duel started
     * @param rawDamage    pre-modifier damage
     * @param finalDamage  post-modifier damage applied
     * @param reasons      the pipeline's reason/modifier labels for this hit
     */
    public record HitRecord(UUID damager, UUID damagee, int elapsedTicks,
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
        private final int startTick = Bukkit.getCurrentTick();
        private final List<HitRecord> hits = new ArrayList<>();

        /**
         * What happened to every button the rotation pressed this duel, per combatant.
         *
         * <p>Not {@code @Getter}-exposed as a mutable ledger: callers take an immutable
         * {@link #activationsOf snapshot} for one side, so a reduction running off the main thread
         * cannot observe counts still being written by the pipeline on it.
         */
        private final SimSkillLedger skills = new SimSkillLedger();

        /** Every unit of energy that moved this duel, per combatant and per cause. */
        private final SimEnergyLedger energy = new SimEnergyLedger();

        /**
         * Whoever took the lethal blow, or null while both are alive.
         *
         * <p>Set from the damage event rather than from {@code PlayerDeathEvent}, even though the death
         * is real since phase 3: only the damage event knows which tick the blow landed on, and a TTK
         * read off the death would be quantised to whichever tick the sweep loop next looked.
         */
        @Nullable
        private volatile UUID killed;

        /** Ticks from duel start to the lethal blow. Only meaningful once {@link #killed} is set. */
        private volatile int killedElapsedTicks;

        /**
         * Whether a skill this duel was refused for want of energy rather than for want of a cooldown.
         *
         * <p>This is what {@code sim_result.energy_limited} reports, and it is observed rather than
         * modelled -- see {@link SimRecorder#onUseSkill}. It matters because an energy-limited rotation
         * and a cooldown-limited one respond to opposite balance levers, and the DPS figure alone
         * cannot tell them apart.
         */
        private volatile boolean energyLimited;

        private Recording(UUID combatantA, UUID combatantB) {
            this.combatantA = combatantA;
            this.combatantB = combatantB;
        }

        private synchronized void record(HitRecord hit) {
            hits.add(hit);
        }

        private void markEnergyLimited() {
            energyLimited = true;
        }

        private void markKilled(UUID victim, int elapsedTicks) {
            // First lethal blow wins; a duel is over the moment one side would have dropped.
            if (killed == null) {
                killed = victim;
                killedElapsedTicks = elapsedTicks;
            }
        }

        /**
         * An immutable snapshot of one combatant's per-skill activation counts.
         *
         * <p>Taken per side rather than whole, because every figure on a {@code sim_result} row
         * describes one combatant and a merged view would silently attribute the defender's casts to
         * the attacker's build.
         */
        public Map<String, SimSkillLedger.SkillActivation> activationsOf(UUID combatant) {
            return skills.snapshot(combatant);
        }

        /** One combatant's energy accounting for this duel, or null if none was observed. */
        @Nullable
        public SimEnergyLedger.EnergyUse energyOf(UUID combatant) {
            return energy.snapshot(combatant);
        }

        /**
         * An immutable snapshot of every hit of the duel, both directions, in arrival order.
         *
         * <p>Synchronised for the reason {@link #hitsFrom} is: the damage pipeline appends on the main
         * thread while a reduction may be reading off it. Exposed separately from the {@code @Getter}
         * on the field because a caller holding the live list would be reading it unsynchronised.
         */
        public synchronized List<HitRecord> allHits() {
            return List.copyOf(hits);
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
