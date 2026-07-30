package me.mykindos.betterpvp.balancesim.engine;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.balancesim.BalanceSimulation;
import me.mykindos.betterpvp.balancesim.SimulationGate;
import me.mykindos.betterpvp.balancesim.catalog.BalanceCatalog;
import me.mykindos.betterpvp.balancesim.catalog.SimBuildSpec;
import me.mykindos.betterpvp.balancesim.catalog.SimEquipment;
import me.mykindos.betterpvp.balancesim.catalog.SimScenario;
import me.mykindos.betterpvp.balancesim.catalog.SimScope;
import me.mykindos.betterpvp.balancesim.catalog.SimSkillAllocation;
import me.mykindos.betterpvp.balancesim.catalog.SimSkillFilter;
import me.mykindos.betterpvp.balancesim.catalog.SimTargetSpec;
import me.mykindos.betterpvp.balancesim.repository.SimResultRepository;
import me.mykindos.betterpvp.balancesim.repository.SimResultRow;
import me.mykindos.betterpvp.balancesim.world.SimWorldManager;
import me.mykindos.betterpvp.champions.Champions;
import me.mykindos.betterpvp.champions.champions.roles.RoleManager;
import me.mykindos.betterpvp.champions.champions.skills.ChampionsSkillManager;
import me.mykindos.betterpvp.core.Core;
import me.mykindos.betterpvp.core.utilities.UtilServer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Runs the sweep: pairs every catalog build against every target, drives both sides through the
 * real combat pipeline, and hands the recorder's output to the repository.
 *
 * <p>Duels run in real time -- a 30 s fight costs 30 s -- but all combat state is keyed per
 * player UUID, so many run simultaneously in separate arenas and a sweep is minutes rather than
 * hours. Per tick the orchestrator performs due melee swings and lets the {@link RotationPolicy}
 * synthesise skill inputs.
 *
 * <p><b>Phase 3 scope.</b> The catalog's role, weapon, rune, skill and target-armour axes all reach
 * the pipeline; actives are cast through synthesised real inputs
 * ({@link GreedyRotationPolicy}, {@link SimInputs}); a lethal blow is a real death, so the on-death
 * mechanics fire; and a duel drives one side or both depending on {@link SimScenario}. Each matchup is
 * measured over {@code gate.getIterations()} Monte-Carlo iterations and reduced to mean plus
 * percentiles in {@link SimMeasurement}.
 *
 * <p>What is still <em>not</em> measured, and is excluded rather than approximated: the bow archetypes
 * (they need a real arrow and a ranged engagement, not an input -- {@code SimSkillFilter} keeps them out
 * of the catalog), and the defender's own build (the target axis is role and armour, so a mutual duel is
 * a swept attacker against a bare-role defender, and {@code sim_result.target_skills} stays empty rather
 * than fabricated).
 */
@Singleton
@CustomLog
public class DuelOrchestrator {

    /**
     * Bumped whenever measurement semantics change, so old runs are not diffed against new ones.
     *
     * <p>Phase 3 changes them in three ways that all move numbers: actives are cast, deaths are real
     * (so on-death mechanics fire), and combatants are resolvable by UUID while fighting -- which is
     * what finally makes the energy gate bind on a rotation. A phase 2 row and a phase 3 row of the same
     * build are not the same measurement even at the same {@code config_hash}.
     */
    // Bumped from phase3-actives-1: until DEATH_SETTLE_TICKS existed, a duel was torn down before its
    // recorded kill was applied, so every death aborted partway through the real death path. Runs on
    // the older version measured a different engine and should not be diffed against these.
    private static final String ENGINE_VERSION = "phase3-actives-2";

    private static final long MILLIS_PER_TICK = 50L;

    /** Window burst DPS is measured over: one second, in ticks. */
    private static final int BURST_WINDOW_TICKS = 20;

    /** Results are flushed in chunks so a long sweep is durable as it goes, not only at the end. */
    private static final int RESULT_FLUSH_CHUNK = 500;

    /**
     * Ticks a resolved duel is held open before its combatants are torn down.
     *
     * <p>A kill is <em>recorded</em> a tick before it is <em>applied</em>.
     * {@code SimRecorder} timestamps the lethal blow synchronously at {@code MONITOR} on
     * {@code DamageEvent}, while {@code DamageEventFinalizer.applyFinalDamage} defers the actual
     * {@code setHealth(0)} by one tick to work around
     * <a href="https://github.com/PaperMC/Paper/issues/12148">Paper #12148</a>. Tearing down on the
     * recorded tick therefore destroyed the combatant's client out from under a death that had not
     * happened yet: {@code ServerPlayer.die} then ran with nothing in {@code ClientManager}'s cache,
     * and every death handler that calls {@code search().online(player)} kicked the combatant and
     * threw {@code ClientNotLoadedException} -- which in turn cost the pool its resident.
     *
     * <p>Two ticks rather than one, so the deferred task and the death it causes both land inside the
     * window. This does not touch any measurement: TTK is read from the recording's own lethal-blow
     * tick, so the extra ticks are held open but never counted.
     */
    private static final int DEATH_SETTLE_TICKS = 2;

    private final SimulationGate gate;
    private final SimWorldManager worldManager;
    private final BalanceCatalog catalog;
    private final SimResultRepository repository;
    private final SimRecorder recorder;
    private final SimCombatantPool combatantPool;
    private final SimCombatant.SimContext context;
    private final RotationPolicy rotationPolicy;
    private final SimConfigDigest configDigest;
    private final BalanceSimulation plugin;

    /** The scenario the in-flight sweep is running, so every duel of a run is the same measurement. */
    private volatile SimScenario scenario = SimScenario.ONE_WAY;

    /** Guards against a second sweep being started while one is in flight. */
    private volatile boolean running;

    /**
     * Set by {@link #requestStop()}; cleared when a run starts.
     *
     * <p>Volatile rather than synchronised because the two sides are a single boolean written by
     * whoever ran the command and read by the tick loop, and a stop that takes one extra tick to be
     * noticed is not a defect.
     */
    private volatile boolean stopRequested;

    @Inject
    public DuelOrchestrator(SimulationGate gate,
                            SimWorldManager worldManager,
                            BalanceCatalog catalog,
                            SimResultRepository repository,
                            SimRecorder recorder,
                            SimCombatantPool combatantPool,
                            SimClientFactory clientFactory,
                            SimEquipment equipment,
                            SimStatePurge statePurge,
                            SimInputs inputs,
                            GreedyRotationPolicy rotationPolicy,
                            SimConfigDigest configDigest,
                            BalanceSimulation plugin) {
        this.gate = gate;
        this.worldManager = worldManager;
        this.catalog = catalog;
        this.repository = repository;
        this.recorder = recorder;
        this.combatantPool = combatantPool;
        this.rotationPolicy = rotationPolicy;
        this.configDigest = configDigest;
        this.plugin = plugin;

        // Both managers come from Champions' own injector, never from Guice injection here. This
        // plugin's injector is a *sibling* of Champions' under Core, and neither manager is
        // explicitly bound in either, so both would be satisfied by a just-in-time binding whose
        // placement depends on where the dependency graph happens to resolve.
        //
        // For ChampionsSkillManager that placement is decided and wrong: it depends on Champions,
        // which is bound only inside Champions' injector, so a JIT binding lands here and yields a
        // second manager holding zero loaded skills -- every catalog lookup would fail. For
        // RoleManager every dependency resolves from Core, so Guice may well hoist it and share the
        // instance; that is exactly the kind of thing that should not be left to inference. A
        // second RoleManager would carry its own role store while the champions listeners kept
        // reading the first, so Skill.getSkill would see Role.DEFAULT on every combatant no matter
        // what was equipped and no non-knight skill would ever resolve -- a wrong answer rather
        // than a crash, and the worst possible failure mode for a measurement tool.
        final var championsInjector = JavaPlugin.getPlugin(Champions.class).getInjector();
        this.context = new SimCombatant.SimContext(clientFactory,
                championsInjector.getInstance(RoleManager.class),
                championsInjector.getInstance(ChampionsSkillManager.class),
                equipment,
                statePurge,
                inputs);
    }

    /**
     * Starts a sweep.
     *
     * @param trigger what kicked it off, recorded on the {@code sim_run} row
     * @param scope   which slice of the permutation space to cover
     * @return completes when every matchup has been measured and persisted
     * @throws IllegalStateException if the simulation gate is closed. A sweep that is already
     *                               running yields a failed future rather than a thrown exception.
     */
    public CompletableFuture<SimSummary> run(SimulationTrigger trigger, SimScope scope) {
        return run(trigger, scope, configuredScenario(), progress -> {
        });
    }

    /**
     * The scenario a run gets when the caller does not name one: whatever the config says, falling back
     * to {@code ONE_WAY} for an unrecognised value.
     *
     * <p>Falling back rather than refusing, because this is only reached when nobody asked for a
     * scenario at all -- a typed argument is validated by the command and refused there. {@code ONE_WAY}
     * is the safe default because it is the measurement every existing row was taken with.
     */
    private SimScenario configuredScenario() {
        return SimScenario.parse(gate.getScenario()).orElse(SimScenario.ONE_WAY);
    }

    /**
     * Starts a sweep, reporting progress as it goes.
     *
     * @param trigger  what kicked it off, recorded on the {@code sim_run} row
     * @param scope    which slice of the permutation space to cover
     * @param scenario whether a duel drives one side or both. Fixed for the run: mixing the two would
     *                 make a row ambiguous, since a mutual TTK is "when it won" and a one-way TTK is
     *                 unconditional
     * @param listener called on the main thread with a periodic snapshot, and once more when the
     *                 run ends. Progress also goes to the server log unconditionally, so a run
     *                 started from the console or by a schedule is still observable.
     * @return completes with the run's {@link SimSummary} once every matchup has been measured and
     *         persisted. The summary is the run's result rather than a log line so the closing
     *         figures reach the caller, not only the console.
     * @throws IllegalStateException if the simulation gate is closed. A sweep that is already
     *                               running yields a failed future rather than a thrown exception.
     */
    public CompletableFuture<SimSummary> run(SimulationTrigger trigger,
                                             SimScope scope,
                                             SimScenario scenario,
                                             Consumer<SimProgress> listener) {
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
        stopRequested = false;
        this.scenario = scenario;
        // Before configHash reads it: a /champions reload between runs can change every balance value,
        // and a stale digest would group two runs that measured different games.
        configDigest.invalidate();

        final CompletableFuture<SimSummary> done = new CompletableFuture<>();
        final int realm = Core.getCurrentRealm().getId();

        repository.openRun(realm, trigger, ENGINE_VERSION, configHash(scope), scenarioJson(scope))
                .whenComplete((runId, throwable) -> {
                    if (throwable != null || runId == null) {
                        running = false;
                        done.completeExceptionally(throwable != null ? throwable
                                : new IllegalStateException("sim_run insert returned no id"));
                        return;
                    }
                    // Everything from here touches world and entity state, so it must be on the
                    // main thread; the repository future completed on a database thread.
                    UtilServer.runTask(plugin, () -> startSweep(runId, scope, done, listener));
                });

        return done;
    }

    /**
     * Asks the running sweep to stop early.
     *
     * <p>The sweep drains rather than halting: no further duels are started, the ones already in
     * flight are allowed to resolve, and the run then closes through the normal path with status
     * {@code CANCELLED}. Draining is what makes a stop safe to use -- the duels in flight are real
     * measurements a few seconds from completion, their matchups would otherwise be left with some
     * iterations recorded and no row, and every result already reduced still needs flushing. It is
     * also bounded: no duel outlives {@code duelTimeoutSeconds}, so the drain cannot hang.
     *
     * <p>A stop requested before the tick loop starts is honoured on its first tick, so cancelling
     * during the database round trip that opens the run works. It cannot interrupt catalog
     * enumeration, which is one synchronous main-thread call -- while that is running no command
     * can be processed at all, so there is nothing here that could observe the request.
     *
     * @return false if no sweep was running, in which case nothing was requested
     */
    public boolean requestStop() {
        if (!running) {
            return false;
        }
        stopRequested = true;
        return true;
    }

    /** Whether a sweep is currently in flight. */
    public boolean isRunning() {
        return running;
    }

    /**
     * Builds the matchup queue and starts the tick loop that drives every in-flight duel.
     *
     * <p>Main thread only: enumeration reads the item registry and creates {@code ItemStack}s.
     */
    private void startSweep(long runId,
                            SimScope scope,
                            CompletableFuture<SimSummary> done,
                            Consumer<SimProgress> listener) {
        try {
            // One registry read per run: the axes are then fixed for its duration, so a reload
            // part-way through cannot make a target's measured armour differ from the armour its
            // target_hp was computed from.
            context.equipment().invalidate();

            final List<SimBuildSpec> builds = catalog.enumerateBuilds(scope, gate.getMaxBuilds());
            final List<SimTargetSpec> targets = catalog.enumerateTargets(scope);
            final int iterations = Math.max(1, gate.getIterations());

            final long duels = (long) builds.size() * targets.size() * iterations;
            log.info("Simulation run {} scope {}: {} builds x {} targets x {} iterations = {} duels."
                            + " At {} concurrent and a {}s timeout that is at most {} minutes of wall clock.",
                    runId, scope, builds.size(), targets.size(), iterations, duels,
                    gate.getMaxConcurrentDuels(), gate.getDuelTimeoutSeconds(),
                    worstCaseMinutes(duels)).submit();

            if (builds.isEmpty() || targets.isEmpty()) {
                finish(runId, done, List.of(), List.of(), SimSummary.empty(runId, scope, "COMPLETED"));
                return;
            }

            repository.insertBuilds(runId, builds).whenComplete((buildIds, throwable) -> {
                if (throwable != null) {
                    running = false;
                    done.completeExceptionally(throwable);
                    return;
                }
                UtilServer.runTask(plugin, () -> {
                    final Deque<Matchup> pending = new ArrayDeque<>();
                    for (SimBuildSpec build : builds) {
                        final Long buildId = buildIds.get(build.fingerprint());
                        if (buildId == null) {
                            // Only reachable if two catalog entries collided on a fingerprint, which
                            // the unique index would have rejected. Skipping loudly beats writing a
                            // result row against someone else's build id.
                            log.warn("No sim_build row came back for fingerprint {}; skipping",
                                    build.fingerprint()).submit();
                            continue;
                        }
                        for (SimTargetSpec target : targets) {
                            // One Matchup, one SimMeasurement; the iteration count lives in the
                            // measurement so a matchup is only reduced once every duel for it has
                            // been recorded.
                            final Matchup matchup = new Matchup(build, buildId, target,
                                    new SimMeasurement(iterations));
                            for (int iteration = 0; iteration < iterations; iteration++) {
                                pending.add(matchup);
                            }
                        }
                    }
                    // Creating the world here rather than lazily inside a duel keeps the one
                    // blocking, main-thread-only operation out of the tick loop.
                    worldManager.getOrCreate();
                    // The matchup count is pending.size() / iterations, but taken from the queue
                    // rather than recomputed, so a build skipped above is not counted as planned.
                    new SweepLoop(runId, scope, pending, pending.size() / iterations, done, listener).start();
                });
            });
        } catch (Exception e) {
            running = false;
            done.completeExceptionally(e);
        }
    }

    /**
     * Closes the run and completes the caller's future. Every outstanding result flush is awaited
     * first, so a run is never marked {@code COMPLETED} before its rows are durable.
     *
     * <p>The closing figures are logged here rather than at the call sites, so a run reports the
     * same way however it ended -- normal completion, an empty catalog, or an abort -- and so the
     * line is emitted only once the rows are actually durable. A run that fails to persist logs a
     * failure instead of a summary, because the counts would describe work whose output did not
     * survive.
     */
    private void finish(long runId,
                        CompletableFuture<SimSummary> done,
                        List<SimResultRow> tail,
                        List<CompletableFuture<Void>> inFlight,
                        SimSummary summary) {
        // Before the world goes: residents are entities inside it, and the pool's slots describe
        // arenas that stop existing the moment it is unloaded.
        combatantPool.drain();
        worldManager.teardown();
        CompletableFuture.allOf(inFlight.toArray(new CompletableFuture[0]))
                .thenCompose(ignored -> repository.insertResults(runId, tail))
                .thenCompose(ignored -> repository.closeRun(runId, summary.status()))
                .whenComplete((ignored, throwable) -> {
                    running = false;
                    if (throwable != null) {
                        log.error("Simulation run {} could not be closed; {} of {} duels had been"
                                        + " measured", runId, summary.completedDuels(),
                                summary.plannedDuels(), throwable).submit();
                        done.completeExceptionally(throwable);
                        return;
                    }
                    logSummary(summary);
                    done.complete(summary);
                });
    }

    /**
     * The closing report: what the run measured, what it wrote, and how long it took.
     *
     * <p>Planned and completed are both printed. A sweep that skipped a build, or one that aborted,
     * still closes -- printing only what it managed would make a partial run read as a whole one,
     * and the whole point of the summary is to tell you whether the rows you are about to query
     * cover what you asked for. {@code millisPerDuel} is included because it is the number needed
     * to size the next sweep, and it is otherwise only recoverable by dividing two figures from
     * different log lines.
     */
    private void logSummary(SimSummary summary) {
        log.info("Simulation run {} [{}] finished with status {}: {}/{} duels measured,"
                        + " {}/{} matchups reduced, {} result rows written in {} ({} ms/duel).{}",
                summary.runId(), summary.scope(), summary.status(),
                summary.completedDuels(), summary.plannedDuels(),
                summary.completedMatchups(), summary.plannedMatchups(),
                summary.resultRows(), summary.elapsedFormatted(),
                String.format(Locale.ROOT, "%.1f", summary.millisPerDuel()),
                summary.whole() ? "" : " Run did not cover every planned matchup;"
                        + " treat sim_result for this run as partial.").submit();
    }

    /**
     * sha256 over every config value that fed the run, so two runs are only comparable when this
     * differs for the reason you think it does.
     *
     * <p>This closes design open question 10. Until phase 3 the hash covered only the simulation
     * knobs, not the champions and item config that skill damage and weapon stats are read from
     * <em>live</em> during a run -- so two sweeps taken either side of a balance change shared a hash,
     * which is precisely the case §3.3 says this column exists to distinguish. {@link SimConfigDigest}
     * supplies the missing half.
     *
     * <p>What is hashed, and why each part is here:
     * <ul>
     *   <li>the balance config digest -- the numbers the damage came from;</li>
     *   <li>the scope, because it decides which builds exist;</li>
     *   <li>the skill filter, because two {@code FULL} runs under different filters cover different
     *       spaces while sharing a scope, and comparing them would read a narrower sweep's absent
     *       builds as a balance change;</li>
     *   <li>the scenario and the channel hold budget, because both change what a row <em>means</em>
     *       rather than just how much of the space it covers;</li>
     *   <li>iterations, timeout and concurrency, which bound the measurement's precision;</li>
     *   <li>the engine version, so a semantics change cannot masquerade as an unchanged config.</li>
     * </ul>
     * The retry interval is deliberately absent: it rate-limits attempts the real gates would have
     * refused anyway, so two runs differing only in it are measuring the same thing.
     */
    private String configHash(SimScope scope) {
        return Integer.toHexString((scope + ":" + scenario + ":" + gate.getIterations()
                + ":" + gate.getDuelTimeoutSeconds() + ":" + gate.getMaxConcurrentDuels()
                + ":" + SimSkillFilter.parse(gate.getSkillFilter())
                + ":" + gate.getChannelHoldTicks()
                + ":" + configDigest.digest()
                + ":" + ENGINE_VERSION).hashCode());
    }

    /**
     * The run's own description of what it measured, for a reader who has only the row.
     *
     * <p>{@code channel_hold_ticks} is in here because it is the engine's one policy choice rather than
     * an observation: a channel produces damage for as long as it is held, so a channel build's DPS is
     * partly this number, and two runs with different budgets are not comparable for those builds.
     * {@code balance_config} is the digest's own value, so a dashboard can group runs by the balance
     * numbers they were taken against without unpacking {@code config_hash}.
     */
    private String scenarioJson(SimScope scope) {
        return "{\"phase\":3"
                + ",\"scope\":\"" + scope + '"'
                + ",\"scenario\":\"" + scenario.jsonValue() + '"'
                + ",\"iterations\":" + Math.max(1, gate.getIterations())
                + ",\"actives\":true"
                + ",\"rotation\":\"greedy\""
                + ",\"channel_hold_ticks\":" + Math.max(1, gate.getChannelHoldTicks())
                + ",\"skill_filter\":\"" + SimSkillFilter.parse(gate.getSkillFilter()) + '"'
                + ",\"balance_config\":\"" + configDigest.digest() + '"'
                + ",\"timeout_s\":" + gate.getDuelTimeoutSeconds()
                + '}';
    }

    /**
     * Ticks are the sim's unit of time; seconds exist only for the columns dashboards read.
     * Kept exact rather than approximated, so a value always lands on a 0.05 boundary.
     */
    private static double ticksToSeconds(long ticks) {
        return ticks * MILLIS_PER_TICK / 1000.0;
    }

    /** Upper bound on the sweep, assuming every duel runs to the timeout rather than to a kill. */
    private long worstCaseMinutes(long duels) {
        final double batches = Math.ceil((double) duels / Math.max(1, gate.getMaxConcurrentDuels()));
        return Math.round(batches * gate.getDuelTimeoutSeconds() / 60.0);
    }

    /**
     * One attacker build measured against one defender configuration, across every iteration.
     *
     * @param buildId     the {@code sim_build} row id, carried so the result row can satisfy its
     *                    foreign key without a second lookup at harvest time
     * @param measurement accumulates the iterations and reduces them once they are all in
     */
    private record Matchup(SimBuildSpec build, long buildId, SimTargetSpec target, SimMeasurement measurement) {
    }

    /**
     * The tick loop. Keeps up to {@code maxConcurrentDuels} duels in flight, starting new ones as
     * others resolve, and reduces each completed matchup into a {@link SimResultRow}.
     */
    private final class SweepLoop implements Runnable {

        private final long runId;
        private final SimScope scope;
        private final Deque<Matchup> pending;
        private final CompletableFuture<SimSummary> done;
        private final Consumer<SimProgress> listener;
        private final List<Duel> active = new ArrayList<>();
        private final List<SimResultRow> results = new ArrayList<>();
        private final List<CompletableFuture<Void>> flushes = new ArrayList<>();

        private final long plannedDuels;
        private final int plannedMatchups;
        private final long startedAtMillis = System.currentTimeMillis();

        private long completedDuels;
        private int completedMatchups;
        private long lastProgressTick;
        /** Rows already handed to the repository, so the summary counts output and not intent. */
        private int rowsFlushed;
        /** Whether the drain has been logged, so a stop reports once rather than every tick. */
        private boolean stopAnnounced;

        /**
         * Builds whose effective levels have already been written back, so the update runs once per
         * build rather than once per duel.
         */
        private final Set<Long> effectiveLevelsWritten = new HashSet<>();

        private BukkitTask task;
        private long tick;
        /** Names combatants. Distinct from the arena index, which is recycled between duels. */
        private int duelCounter;

        private SweepLoop(long runId,
                          SimScope scope,
                          Deque<Matchup> pending,
                          int plannedMatchups,
                          CompletableFuture<SimSummary> done,
                          Consumer<SimProgress> listener) {
            this.runId = runId;
            this.scope = scope;
            this.pending = pending;
            this.plannedDuels = pending.size();
            this.plannedMatchups = plannedMatchups;
            this.done = done;
            this.listener = listener;
        }

        private void start() {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this, 1L, 1L);
            report();
        }

        @Override
        public void run() {
            tick++;
            try {
                // A stop stops the queue, not the fights. Duels already in flight are measurements
                // seconds from completing, and abandoning them would leave their matchups with some
                // iterations recorded and no row to show for them.
                if (stopRequested) {
                    announceStopOnce();
                } else {
                    fill();
                }
                step();
                if (active.isEmpty() && (pending.isEmpty() || stopRequested)) {
                    task.cancel();
                    report();
                    finish(runId, done, List.copyOf(results), List.copyOf(flushes),
                            summarise(stopRequested ? "CANCELLED" : "COMPLETED"));
                    return;
                }
                if (tick - lastProgressTick >= progressIntervalTicks()) {
                    lastProgressTick = tick;
                    report();
                }
            } catch (Exception e) {
                task.cancel();
                active.forEach(duel -> duel.teardown(tick));
                log.error("Simulation run {} aborted after {} of {} duels", runId, completedDuels,
                        plannedDuels, e).submit();
                report();
                // Still finished through the same path, so the partial run's rows are flushed and
                // summarised rather than discarded -- an aborted sweep's measurements are valid for
                // the matchups that did complete, and the summary says how far it got.
                finish(runId, done, List.copyOf(results), List.copyOf(flushes), summarise("FAILED"));
            }
        }

        /**
         * Logs the drain once, with what is left to wait for.
         *
         * <p>Once, because the loop sees the flag every tick and the useful information -- how many
         * duels are still finishing, and how much of the queue is being abandoned -- is a one-time
         * answer, not a stream. Progress reporting continues as normal, so the drain is still
         * visible as it shrinks.
         */
        private void announceStopOnce() {
            if (stopAnnounced) {
                return;
            }
            stopAnnounced = true;
            log.info("Simulation run {} stopping on request: draining {} duels in flight and"
                            + " abandoning {} queued. Rows already measured will be written.",
                    runId, active.size(), pending.size()).submit();
        }

        /** Freezes the loop's counters into the run's result. */
        private SimSummary summarise(String status) {
            return new SimSummary(runId, scope, status, plannedDuels, completedDuels,
                    plannedMatchups, completedMatchups, rowsFlushed + results.size(),
                    Duration.ofMillis(System.currentTimeMillis() - startedAtMillis));
        }

        /**
         * Emits a progress snapshot to the log and to whoever started the run.
         *
         * <p>The ETA is projected from the run's own observed duel rate rather than from the
         * configured timeout. Most duels end on a kill long before the timeout, so a
         * timeout-based estimate would overstate the remaining time by an order of magnitude and
         * be useless for deciding whether to wait. Extrapolating from what this run has actually
         * managed also absorbs whatever else the server is doing.
         */
        private void report() {
            final long elapsedMillis = System.currentTimeMillis() - startedAtMillis;
            final long remaining = Math.max(0, plannedDuels - completedDuels);
            final Duration eta = completedDuels <= 0 || remaining == 0 ? null
                    : Duration.ofMillis((long) (elapsedMillis * ((double) remaining / completedDuels)));

            final SimProgress progress = new SimProgress(scope, plannedDuels, completedDuels,
                    active.size(), plannedMatchups, completedMatchups,
                    Duration.ofMillis(elapsedMillis), eta);

            // Residency is reported because the pool's cap can bind: once it does, throughput stops
            // tracking maxConcurrentDuels and starts tracking how fast quarantine releases slots,
            // and without this number that shows up only as an unexplained drop in duels/s. The
            // packet backlog is reported for the opposite reason -- it should never be anything but
            // zero, and the run that discovered it retained tens of millions of packets and showed
            // nothing at all until the heap ran out.
            log.info("Simulation run {} [{}]: {}/{} duels ({}%), {} in flight, {} combatants resident,"
                            + " {} packets queued, {}/{} matchups measured."
                            + " Elapsed {}, remaining {}, ETA {}.",
                    runId, scope, completedDuels, plannedDuels,
                    String.format(Locale.ROOT, "%.1f", progress.percent()), active.size(),
                    combatantPool.residentCount(), combatantPool.queuedPacketBacklog(),
                    completedMatchups, plannedMatchups,
                    progress.elapsedFormatted(), progress.etaFormatted(),
                    progress.estimatedFinish()).submit();

            listener.accept(progress);
        }

        /** How often a snapshot is emitted, floored at a second so a bad config cannot spam. */
        private long progressIntervalTicks() {
            return Math.max(20L, (long) (gate.getProgressIntervalSeconds() * 1000L / MILLIS_PER_TICK));
        }

        /**
         * Starts duels until the concurrency budget is full, the queue is empty, or this tick's
         * setup allowance is spent.
         */
        private void fill() {
            int setupsRemaining = Math.max(1, gate.getDuelSetupsPerTick());
            while (setupsRemaining-- > 0
                    && active.size() < gate.getMaxConcurrentDuels()
                    && !pending.isEmpty()) {
                // Asked for before the matchup is polled, because the pool can legitimately have
                // nothing to give: past the residency cap every slot may still be inside its
                // quarantine window. Taking the matchup first and then discovering that would drop
                // it, so a duel that is merely postponed would be silently unmeasured.
                final SimCombatantPool.Slot slot = combatantPool.acquire(tick);
                if (slot == null) {
                    break;
                }
                final Duel duel = new Duel(pending.poll(), duelCounter++, tick, slot);
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
                duel.record(tick, this);
                duel.teardown(tick);
                completedDuels++;
                return true;
            });

            if (results.size() >= RESULT_FLUSH_CHUNK) {
                flush();
            }
        }

        /**
         * Reduces a matchup whose last iteration has landed, and hands the row on.
         */
        private void complete(Matchup matchup) {
            completedMatchups++;
            final SimMeasurement.Aggregate aggregate = matchup.measurement().aggregate();
            results.add(new SimResultRow(matchup.buildId(),
                    matchup.target(),
                    aggregate.dmgPerHit(),
                    aggregate.dpsSustained(),
                    aggregate.dpsBurst(),
                    aggregate.ttkSeconds(),
                    aggregate.hitsToKill(),
                    // Observed, not modelled: SimRecorder watches for a skill use the real chain
                    // refused for energy rather than for a cooldown.
                    aggregate.energyLimited(),
                    aggregate.extrasJson()));
        }

        /**
         * Writes a build's observed effective levels back over the allocated ones it was inserted
         * with. Once per build: the levels depend on the loadout, which is identical across a
         * build's iterations and targets.
         */
        private void writeEffectiveLevels(long buildId, List<SimSkillAllocation> measured) {
            if (measured.isEmpty() || !effectiveLevelsWritten.add(buildId)) {
                return;
            }
            flushes.add(repository.updateBuildSkills(buildId, SimResultRepository.skillsToJson(measured)));
        }

        /** Hands the accumulated rows to the repository and starts a fresh batch. */
        private void flush() {
            flushes.add(repository.insertResults(runId, List.copyOf(results)));
            rowsFlushed += results.size();
            results.clear();
        }
    }

    /**
     * A single measured fight: two fake players on their own platform, swinging every tick,
     * watched by the recorder.
     */
    private final class Duel {

        private final Matchup matchup;
        private final SimCombatantPool.Slot slot;
        private final SimCombatant attacker;
        private final SimCombatant defender;
        private final long startTick;
        private final long timeoutTicks;

        private SimRecorder.Recording recording;

        /** The tick a death resolved this duel on, or -1 while it is still being fought. */
        private long resolvedAtTick = -1;

        private Duel(Matchup matchup, int sequence, long startTick, SimCombatantPool.Slot slot) {
            this.matchup = matchup;
            this.slot = slot;
            this.startTick = startTick;
            this.timeoutTicks = (long) (gate.getDuelTimeoutSeconds() * 1000L / MILLIS_PER_TICK);

            // Both combatants are the slot's residents rather than fresh entities, so their names
            // and UUIDs belong to the arena and not to this duel. The duel's identity is the
            // sim_result row; `sequence` remains only as the ordering it was started in.
            this.attacker = new SimCombatant(slot.getAttacker(), matchup.build());
            this.defender = new SimCombatant(slot.getDefender(), defenderBuild());
        }

        /**
         * The defender is spawned as a full combatant rather than an HP bag, because its role is
         * what supplies base health and its armour is what supplies the rest.
         *
         * <p>The synthesised spec's fingerprint is not a catalog fingerprint and never reaches
         * {@code sim_build}: the defender is described by {@code sim_result}'s own target columns.
         * It is still distinct per role and armour set so two targets can never share a spec.
         */
        private SimBuildSpec defenderBuild() {
            final SimTargetSpec target = matchup.target();
            return new SimBuildSpec(target.role(),
                    // The role's standard kit, which is all the defender needs: under ONE_WAY it never
                    // swings, and under MUTUAL the weapon axis belongs to the attacker -- sweeping the
                    // defender's weapon as well would square the space to answer a question no column on
                    // the row asks.
                    context.equipment().defaultWeapon().key(),
                    target.armorSetId(),
                    List.of(),
                    target.skills(),
                    target.pointsSpent(),
                    false,
                    "target:" + target.role() + ":" + target.armorSetId());
        }

        private void setUp() {
            attacker.spawn(context, slot.getArena().spawnA());
            defender.spawn(context, slot.getArena().spawnB());
            recording = recorder.startDuel(attacker.getUuid(), defender.getUuid());
        }

        /**
         * Runs the tick's rotation and swing, and reports whether the duel has resolved.
         *
         * <p>Which sides act is {@link SimScenario}'s answer, fixed for the run. Under
         * {@code ONE_WAY} only the attacker does anything and the defender is a durable target, which
         * makes {@code ttk_s} an unconditional "how long this build needs". Under {@code MUTUAL} both
         * rotations run and both sides swing, which is what makes an attacker's own reactive passives
         * and {@code DefensiveSkill}s measurable at all -- at the cost that the duel can end with the
         * attacker dead, so {@code ttk_s} becomes "how long when it won" and {@code kill_rate} carries
         * the rest of the answer.
         *
         * <p>The rotation runs before the swing, in the order a player's own tick would: a prepare
         * armed on this tick should be paid off by this tick's hit rather than the next one.
         *
         * @return true when the duel is over, by kill or by timeout
         */
        private boolean advance(long currentTick) {
            // Already resolved by a death: stop acting, but stay spawned until the deferred kill has
            // actually landed. See DEATH_SETTLE_TICKS.
            if (resolvedAtTick >= 0) {
                return currentTick - resolvedAtTick >= DEATH_SETTLE_TICKS;
            }
            // The recording owns the duel's notion of death, because only the damage event knows which
            // tick the lethal blow landed on. isAlive() is the backstop for a combatant that stopped
            // being fightable some other way.
            if (recording.getKilled() != null || !attacker.isAlive() || !defender.isAlive()) {
                resolvedAtTick = currentTick;
                return false;
            }
            // A timeout has nothing pending against it -- non-lethal damage is applied inline, only
            // the killing blow is deferred -- so it resolves and tears down on the same tick.
            if (currentTick - startTick >= timeoutTicks) {
                return true;
            }
            // Elapsed rather than absolute ticks, so a rotation's timing is identical whenever in the
            // sweep the duel happens to start -- the same reason hit timestamps are relative.
            final long elapsed = currentTick - startTick;
            rotationPolicy.act(attacker, defender, elapsed);
            if (scenario.isDefenderDriven()) {
                rotationPolicy.act(defender, attacker, elapsed);
                defender.swingAt(attacker);
            }
            // Swing every tick and let the pipeline decide what lands. The sim previously paced
            // itself at DamageCause.DEFAULT_DELAY, on the assumption that matching the floor
            // exactly would land every swing -- but back then the delay was gated on the wall
            // clock, so a swing scheduled on the 400 ms boundary arrived a hair early as often as
            // not, DamageDelayManager rejected it, and the next attempt was a further 8 ticks out.
            // DelayData now counts ticks, so the gate is exact and a swing on the boundary always
            // lands; swinging every tick is kept anyway because it costs nothing and keeps the sim
            // correct for delays that are not a whole number of ticks. There is no attack-strength
            // charge to husband here (1.8-style combat, constant damage), so the only cost of a
            // rejected swing is the call itself, and rejections are invisible to the measurement:
            // processPreEventDelay returns before DamageEventProcessor fires DamageEvent, so the
            // recorder only ever sees hits that actually landed.
            attacker.swingAt(defender);
            return false;
        }

        /** Reduces the recording into one iteration's sample and files it against the matchup. */
        private void record(long endTick, SweepLoop loop) {
            recorder.endDuel(recording);
            final List<SimRecorder.HitRecord> hits =
                    recording.hitsFrom(attacker.getUuid(), defender.getUuid());

            final boolean killed = defender.getUuid().equals(recording.getKilled());

            // The fight is measured from the first landed hit, not from when the duel object was
            // built. Setting a duel up costs real main-thread time -- spawning two ServerPlayers,
            // equipping roles, builds, weapons and armour -- so anchoring on the recording's start
            // folded each duel's position in the setup batch into its TTK: identical matchups came
            // out staggered, decreasing down the batch. Hit timestamps share that tick base, so
            // subtracting the first one cancels the offset entirely.
            final Integer engagementStartTick = hits.isEmpty() ? null : hits.get(0).elapsedTicks();
            final Integer ttkTicks = killed && engagementStartTick != null
                    ? recording.getKilledElapsedTicks() - engagementStartTick
                    : null;

            Double dmgPerHit = null;
            Double dpsSustained = null;
            Double dpsBurst = null;
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
                dpsBurst = burstDps(hits);
            }

            loop.writeEffectiveLevels(matchup.buildId(), attacker.getMeasuredSkills());

            // Recorded rather than inferred from !killed: a duel can also time out with both alive, and
            // under ONE_WAY the attacker cannot die at all, so a zero here is what distinguishes the
            // two scenarios on the row as well as on the run.
            final boolean attackerDied = attacker.getUuid().equals(recording.getKilled());

            matchup.measurement().add(
                    new SimMeasurement.Sample(dmgPerHit, dpsSustained, dpsBurst, ttkTicks,
                            hits.size(), killed, attackerDied, recording.isEnergyLimited()),
                    reasonCounts(hits));

            if (matchup.measurement().isComplete()) {
                loop.complete(matchup);
            }
        }

        /**
         * Damage over the heaviest one-second window of the fight.
         *
         * <p>Sustained DPS averages a burst away, which is exactly wrong for the mechanics that
         * matter most in a duel -- a build that front-loads damage kills through a heal that a
         * flat-DPS build does not. Measured from the recorded hits rather than modelled, so it
         * needs nothing the recorder does not already capture.
         */
        private Double burstDps(List<SimRecorder.HitRecord> hits) {
            double best = 0;
            for (int start = 0; start < hits.size(); start++) {
                final int windowEnd = hits.get(start).elapsedTicks() + BURST_WINDOW_TICKS;
                double window = 0;
                for (int i = start; i < hits.size() && hits.get(i).elapsedTicks() < windowEnd; i++) {
                    window += hits.get(i).finalDamage();
                }
                best = Math.max(best, window);
            }
            return best / ticksToSeconds(BURST_WINDOW_TICKS);
        }

        /**
         * How often each pipeline reason appeared across this duel's hits, so mitigation and
         * damage modifiers stay observable on the result rather than inferred from the total.
         */
        private Map<String, Integer> reasonCounts(List<SimRecorder.HitRecord> hits) {
            final Map<String, Integer> counts = new TreeMap<>();
            for (SimRecorder.HitRecord hit : hits) {
                for (String reason : hit.reasons()) {
                    counts.merge(reason, 1, Integer::sum);
                }
            }
            return counts.isEmpty() ? Map.of() : new HashMap<>(counts);
        }

        /**
         * Releases both combatants and hands the slot back for quarantine.
         *
         * <p>The slot is released after the combatants are done, never before: it is returned to a
         * queue the same tick loop draws from, and handing it back early could put four combatants
         * on one platform.
         *
         * @param currentTick when the quarantine window starts counting from
         */
        private void teardown(long currentTick) {
            attacker.despawn(context);
            defender.despawn(context);
            combatantPool.release(slot, currentTick);
        }
    }
}
