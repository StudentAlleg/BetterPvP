package me.mykindos.betterpvp.balancesim.engine;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.balancesim.BalanceSimulation;
import me.mykindos.betterpvp.balancesim.SimulationGate;
import me.mykindos.betterpvp.balancesim.catalog.BalanceCatalog;
import me.mykindos.betterpvp.balancesim.catalog.SimBuildSpec;
import me.mykindos.betterpvp.balancesim.catalog.SimTargetSpec;
import me.mykindos.betterpvp.balancesim.repository.SimResultRepository;
import me.mykindos.betterpvp.balancesim.repository.SimResultRow;
import me.mykindos.betterpvp.balancesim.world.SimWorldManager;
import me.mykindos.betterpvp.champions.champions.roles.RoleManager;
import me.mykindos.betterpvp.core.Core;
import me.mykindos.betterpvp.core.utilities.UtilServer;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the sweep: pairs every catalog build against every target, drives both sides through the
 * real combat pipeline, and hands the recorder's output to the repository.
 *
 * <p>Duels run in real time -- a 30 s fight costs 30 s -- but all combat state is keyed per
 * player UUID, so many run simultaneously in separate arenas and a full sweep is minutes rather
 * than hours. Per tick the orchestrator performs due melee swings and (from phase 3) lets the
 * {@link RotationPolicy} synthesise skill inputs.
 *
 * <p><b>Phase 1 scope.</b> Melee only, one iteration per matchup, and the attacker swinging every
 * tick rather than on a cadence read from the weapon's attack-speed stat -- the pipeline's own
 * damage delay is what decides which swings land. That is enough to prove the pipeline end to end -- fake players, real damage events, rows in
 * {@code sim_result} -- which is what the phase 1 exit criterion asks for. Monte-Carlo iteration
 * ({@code gate.getIterations()}), per-weapon attack speed and active-skill rotations are phase 2
 * and 3; until then results are single-sample and must not be read as distributions.
 */
@Singleton
@CustomLog
public class DuelOrchestrator {

    /** Bumped whenever measurement semantics change, so old runs are not diffed against new ones. */
    private static final String ENGINE_VERSION = "phase1-melee-1";

    private static final long MILLIS_PER_TICK = 50L;

    private final SimulationGate gate;
    private final SimWorldManager worldManager;
    private final BalanceCatalog catalog;
    private final SimResultRepository repository;
    private final SimRecorder recorder;
    private final SimCombatant.SimContext context;
    private final BalanceSimulation plugin;

    /** Guards against a second sweep being started while one is in flight. */
    private volatile boolean running;

    @Inject
    public DuelOrchestrator(SimulationGate gate,
                            SimWorldManager worldManager,
                            BalanceCatalog catalog,
                            SimResultRepository repository,
                            SimRecorder recorder,
                            SimClientFactory clientFactory,
                            RoleManager roleManager,
                            BalanceSimulation plugin) {
        this.gate = gate;
        this.worldManager = worldManager;
        this.catalog = catalog;
        this.repository = repository;
        this.recorder = recorder;
        this.plugin = plugin;
        this.context = new SimCombatant.SimContext(plugin, clientFactory, roleManager);
    }

    /**
     * Starts a sweep.
     *
     * @param trigger what kicked it off, recorded on the {@code sim_run} row
     * @return completes when every matchup has been measured and persisted
     * @throws IllegalStateException if the simulation gate is closed. A sweep that is already
     *                               running yields a failed future rather than a thrown exception.
     */
    public CompletableFuture<Void> run(SimulationTrigger trigger) {
        if (!gate.isEnabled()) {
            throw new IllegalStateException("Simulation is disabled");
        }
        if (running) {
            // Returned rather than thrown so an admin double-running /simulate gets the command's
            // failure message instead of a stack trace out of execute().
            return CompletableFuture.failedFuture(
                    new IllegalStateException("A simulation sweep is already running"));
        }
        running = true;

        final CompletableFuture<Void> done = new CompletableFuture<>();
        final int realm = Core.getCurrentRealm().getId();

        repository.openRun(realm, trigger, ENGINE_VERSION, configHash(), scenarioJson())
                .whenComplete((runId, throwable) -> {
                    if (throwable != null || runId == null) {
                        running = false;
                        done.completeExceptionally(throwable != null ? throwable
                                : new IllegalStateException("sim_run insert returned no id"));
                        return;
                    }
                    // Everything from here touches world and entity state, so it must be on the
                    // main thread; the repository future completed on a database thread.
                    UtilServer.runTask(plugin, () -> startSweep(runId, done));
                });

        return done;
    }

    /**
     * Builds the matchup queue and starts the tick loop that drives every in-flight duel.
     *
     * <p>Main thread only.
     */
    private void startSweep(long runId, CompletableFuture<Void> done) {
        try {
            final List<SimBuildSpec> builds = catalog.enumerateBuilds();
            final List<SimTargetSpec> targets = catalog.enumerateTargets();

            log.info("Simulation run {} starting: {} builds x {} targets = {} matchups",
                    runId, builds.size(), targets.size(), builds.size() * targets.size()).submit();

            if (builds.isEmpty() || targets.isEmpty()) {
                finish(runId, done, List.of(), "COMPLETED");
                return;
            }

            // sim_result.build_id is a foreign key, so every build row has to exist before the
            // first duel resolves. Inserting them up front also means a run that dies mid-sweep
            // still describes which builds it intended to measure.
            insertBuilds(runId, builds).whenComplete((buildIds, throwable) -> {
                if (throwable != null) {
                    running = false;
                    done.completeExceptionally(throwable);
                    return;
                }
                UtilServer.runTask(plugin, () -> {
                    final Deque<Matchup> pending = new ArrayDeque<>();
                    for (SimBuildSpec build : builds) {
                        final long buildId = buildIds.get(build.fingerprint());
                        for (SimTargetSpec target : targets) {
                            pending.add(new Matchup(build, buildId, target));
                        }
                    }
                    // Creating the world here rather than lazily inside a duel keeps the one
                    // blocking, main-thread-only operation out of the tick loop.
                    worldManager.getOrCreate();
                    new SweepLoop(runId, pending, done).start();
                });
            });
        } catch (Exception e) {
            running = false;
            done.completeExceptionally(e);
        }
    }

    /**
     * Inserts one {@code sim_build} row per catalog build and returns fingerprint to row id.
     *
     * <p>Sequential rather than batched: each insert returns a generated id that the result rows
     * need, and phase 1 emits one build per role. Phase 2's much larger catalog wants a batched
     * insert with a single {@code RETURNING} sweep instead.
     */
    private CompletableFuture<Map<String, Long>> insertBuilds(long runId, List<SimBuildSpec> builds) {
        final Map<String, Long> ids = new ConcurrentHashMap<>();
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (SimBuildSpec build : builds) {
            chain = chain.thenCompose(ignored -> repository
                    // Phase 1 builds carry no runes and no skills, so both columns are empty
                    // arrays rather than a fabricated allocation.
                    .insertBuild(runId, build, "[]", "[]")
                    .thenAccept(id -> ids.put(build.fingerprint(), id)));
        }
        return chain.thenApply(ignored -> ids);
    }

    /**
     * Closes the run and completes the caller's future. Results are flushed first so a run is
     * never marked {@code COMPLETED} before its rows are durable.
     */
    private void finish(long runId, CompletableFuture<Void> done, List<SimResultRow> rows, String status) {
        worldManager.teardown();
        repository.insertResults(runId, rows)
                .thenCompose(ignored -> repository.closeRun(runId, status))
                .whenComplete((ignored, throwable) -> {
                    running = false;
                    log.info("Simulation run {} finished with status {} ({} results)",
                            runId, status, rows.size()).submit();
                    if (throwable != null) {
                        done.completeExceptionally(throwable);
                    } else {
                        done.complete(null);
                    }
                });
    }

    /**
     * sha256 over every config value that fed the run, so two runs are only comparable when this
     * differs for the reason you think it does.
     *
     * <p>TODO(phase 2): hash the champions/item config values that actually determine damage, not
     * just the simulation knobs. Until the catalog reads those values this covers what varies.
     */
    private String configHash() {
        return Integer.toHexString((gate.getIterations() + ":" + gate.getDuelTimeoutSeconds()
                + ":" + gate.getMaxConcurrentDuels() + ":" + ENGINE_VERSION).hashCode());
    }

    /**
     * Ticks are the sim's unit of time; seconds exist only for the columns dashboards read.
     * Kept exact rather than approximated, so a value always lands on a 0.05 boundary.
     */
    private static double ticksToSeconds(long ticks) {
        return ticks * MILLIS_PER_TICK / 1000.0;
    }

    private String scenarioJson() {
        return "{\"phase\":1,\"iterations\":1,\"melee_only\":true,\"timeout_s\":"
                + gate.getDuelTimeoutSeconds() + "}";
    }

    /**
     * One attacker build measured against one defender configuration.
     *
     * @param buildId the {@code sim_build} row id, carried so the result row can satisfy its
     *                foreign key without a second lookup at harvest time
     */
    private record Matchup(SimBuildSpec build, long buildId, SimTargetSpec target) {
    }

    /**
     * The tick loop. Keeps up to {@code maxConcurrentDuels} duels in flight, starting new ones as
     * others resolve, and reduces each finished duel into a {@link SimResultRow}.
     */
    private final class SweepLoop implements Runnable {

        private final long runId;
        private final Deque<Matchup> pending;
        private final CompletableFuture<Void> done;
        private final List<Duel> active = new ArrayList<>();
        private final List<SimResultRow> results = new ArrayList<>();

        private BukkitTask task;
        private long tick;
        private int arenaCounter;

        private SweepLoop(long runId, Deque<Matchup> pending, CompletableFuture<Void> done) {
            this.runId = runId;
            this.pending = pending;
            this.done = done;
        }

        private void start() {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this, 1L, 1L);
        }

        @Override
        public void run() {
            tick++;
            try {
                fill();
                step();
                if (active.isEmpty() && pending.isEmpty()) {
                    task.cancel();
                    finish(runId, done, List.copyOf(results), "COMPLETED");
                }
            } catch (Exception e) {
                task.cancel();
                active.forEach(Duel::teardown);
                log.error("Simulation run {} aborted", runId, e).submit();
                finish(runId, done, List.copyOf(results), "FAILED");
            }
        }

        /** Starts duels until the concurrency budget is full or the queue is empty. */
        private void fill() {
            while (active.size() < gate.getMaxConcurrentDuels() && !pending.isEmpty()) {
                final Matchup matchup = pending.poll();
                final Duel duel = new Duel(matchup, arenaCounter++, tick);
                duel.setUp();
                active.add(duel);
            }
        }

        /** Advances every in-flight duel by one tick, harvesting any that resolved. */
        private void step() {
            active.removeIf(duel -> {
                if (!duel.advance(tick)) {
                    return false;
                }
                results.add(duel.harvest(tick));
                duel.teardown();
                return true;
            });
        }
    }

    /**
     * A single measured fight: two fake players on their own platform, swinging on the real
     * cadence, watched by the recorder.
     */
    private final class Duel {

        private final Matchup matchup;
        private final SimWorldManager.ArenaSlot arena;
        private final SimCombatant attacker;
        private final SimCombatant defender;
        private final long startTick;
        private final long timeoutTicks;

        private SimRecorder.Recording recording;

        private Duel(Matchup matchup, int arenaIndex, long startTick) {
            this.matchup = matchup;
            this.arena = worldManager.prepareArena(arenaIndex);
            this.startTick = startTick;
            this.timeoutTicks = (long) (gate.getDuelTimeoutSeconds() * 1000L / MILLIS_PER_TICK);

            final UUID attackerId = UUID.randomUUID();
            final UUID defenderId = UUID.randomUUID();
            this.attacker = new SimCombatant(attackerId, "sim_atk_" + arenaIndex, matchup.build());
            this.defender = new SimCombatant(defenderId, "sim_def_" + arenaIndex, defenderBuild());
        }

        /**
         * The defender is spawned as a full combatant rather than an HP bag, because its role is
         * what supplies base health and (from phase 2) its skills are what mitigate.
         */
        private SimBuildSpec defenderBuild() {
            return new SimBuildSpec(matchup.target().role(), "role_default", List.of(), List.of(),
                    matchup.target().pointsSpent(), false, "target:" + matchup.target().role());
        }

        private void setUp() {
            attacker.spawn(context, arena.spawnA());
            defender.spawn(context, arena.spawnB());
            recording = recorder.startDuel(attacker.getUuid(), defender.getUuid());
        }

        /**
         * Performs any due swing and reports whether the duel has resolved.
         *
         * <p>Only the attacker swings in phase 1. A mutual exchange would truncate the attacker's
         * time-to-kill whenever the defender won the race, which is a meaningful measurement but a
         * different one; it belongs with the phase 3 rotation policy that can actually drive both
         * sides properly. Measuring one direction keeps a phase 1 row unambiguous.
         *
         * @return true when the duel is over, by kill or by timeout
         */
        private boolean advance(long currentTick) {
            // Death is the recorder's call, not the entity's: a lethal blow is intercepted before
            // it lands so no PlayerDeathEvent ever fires for a fake player. isAlive() still guards
            // the case where a combatant vanished for some other reason.
            if (recording.getKilled() != null || !attacker.isAlive() || !defender.isAlive()) {
                return true;
            }
            if (currentTick - startTick >= timeoutTicks) {
                return true;
            }
            // Swing every tick and let the pipeline decide what lands. The sim previously paced
            // itself at DamageCause.DEFAULT_DELAY, on the assumption that matching the floor
            // exactly would land every swing -- but a swing scheduled on the 400 ms boundary
            // arrives a hair early as often as not, DamageDelayManager rejects it, and the next
            // attempt is a further 8 ticks out. That aliasing is what stretched a 4-hit kill
            // across ~89 ticks instead of ~24. There is no attack-strength charge to husband
            // here (1.8-style combat, constant damage), so the only cost of a rejected swing is
            // the call itself, and rejections are invisible to the measurement:
            // processPreEventDelay returns before DamageEventProcessor fires DamageEvent, so the
            // recorder only ever sees hits that actually landed.
            attacker.swingAt(defender);
            return false;
        }

        /** Reduces the recording into the row that reaches {@code sim_result}. */
        private SimResultRow harvest(long endTick) {
            recorder.endDuel(recording);
            final List<SimRecorder.HitRecord> hits =
                    recording.hitsFrom(attacker.getUuid(), defender.getUuid());

            final boolean killed = defender.getUuid().equals(recording.getKilled());

            // The fight is measured from the first landed hit, not from when the duel object was
            // built. Setting a duel up costs real main-thread time -- spawning two ServerPlayers,
            // equipping roles and weapons -- and fill() does that for the whole concurrency batch
            // back to back, while the first swing of every duel in the batch lands on the same
            // tick afterwards. Anchoring on the recording's start therefore folded each duel's
            // position in the batch into its TTK: identical matchups came out staggered by the
            // per-duel setup cost, decreasing down the batch. Hit timestamps share that tick
            // base, so subtracting the first one cancels the offset entirely.
            final Integer engagementStartTick = hits.isEmpty() ? null : hits.get(0).elapsedTicks();
            final Integer ttkTicks = killed && engagementStartTick != null
                    ? recording.getKilledElapsedTicks() - engagementStartTick
                    : null;

            Double dmgPerHit = null;
            Double dpsSustained = null;
            Double hitsToKill = null;
            if (!hits.isEmpty()) {
                final double total = hits.stream().mapToDouble(SimRecorder.HitRecord::finalDamage).sum();
                dmgPerHit = total / hits.size();
                // Same window as the TTK when there is one, so the figures on a row stay
                // consistent with each other: dmg_per_hit * hits_to_kill / ttk_s == dps_sustained.
                // A duel that timed out has no lethal hit to bound the window, so it falls back to
                // the loop's own elapsed ticks.
                final long windowTicks = ttkTicks != null && ttkTicks > 0
                        ? ttkTicks
                        : Math.max(1, endTick - startTick);
                dpsSustained = total / ticksToSeconds(windowTicks);
                // Only meaningful when the target actually died; otherwise the hit count is just
                // "however many landed before the timeout" and would read as a real figure.
                hitsToKill = killed ? (double) hits.size() : null;
            }

            return new SimResultRow(
                    matchup.buildId(),
                    matchup.target(),
                    dmgPerHit,
                    dpsSustained,
                    // Burst DPS needs a windowed maximum over a rotation; melee-only phase 1 has no
                    // burst to distinguish, so it is left null rather than duplicating sustained.
                    null,
                    // ttk_s is a rendering of the tick count for dashboards, not a second
                    // measurement: it is always an exact multiple of 0.05.
                    ttkTicks == null ? null : ticksToSeconds(ttkTicks),
                    hitsToKill,
                    false,
                    // The tick count rides in extras so the unit the sim actually measured in is
                    // recoverable from the row without a schema change.
                    "{\"iterations\":1,\"hits\":" + hits.size() + ",\"killed\":" + killed
                            + ",\"ttk_ticks\":" + ttkTicks + "}");
        }

        private void teardown() {
            attacker.despawn(context);
            defender.despawn(context);
        }
    }
}
