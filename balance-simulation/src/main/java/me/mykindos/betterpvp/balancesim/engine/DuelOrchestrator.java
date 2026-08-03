package me.mykindos.betterpvp.balancesim.engine;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.balancesim.BalanceSimulation;
import me.mykindos.betterpvp.balancesim.SimulationGate;
import me.mykindos.betterpvp.balancesim.audit.AuditThresholds;
import me.mykindos.betterpvp.balancesim.audit.SkillAuditReport;
import me.mykindos.betterpvp.balancesim.audit.SkillRelevanceAudit;
import me.mykindos.betterpvp.balancesim.audit.SkillVerdict;
import me.mykindos.betterpvp.balancesim.catalog.BalanceCatalog;
import me.mykindos.betterpvp.balancesim.catalog.SimBuildSpec;
import me.mykindos.betterpvp.balancesim.catalog.SimEquipment;
import me.mykindos.betterpvp.balancesim.catalog.SimScenario;
import me.mykindos.betterpvp.balancesim.catalog.SimScope;
import me.mykindos.betterpvp.balancesim.catalog.SimSkillAllocation;
import me.mykindos.betterpvp.balancesim.catalog.SimSkillFilter;
import me.mykindos.betterpvp.balancesim.catalog.SimTargetSpec;
import me.mykindos.betterpvp.balancesim.repository.SimDuelDiagnosticRow;
import me.mykindos.betterpvp.balancesim.repository.SimResultRepository;
import me.mykindos.betterpvp.balancesim.repository.SimResultRow;
import me.mykindos.betterpvp.balancesim.repository.SimTraceRow;
import me.mykindos.betterpvp.balancesim.world.SimWorldManager;
import me.mykindos.betterpvp.champions.Champions;
import me.mykindos.betterpvp.champions.champions.roles.RoleManager;
import me.mykindos.betterpvp.champions.champions.skills.ChampionsSkillManager;
import me.mykindos.betterpvp.core.Core;
import me.mykindos.betterpvp.core.utilities.UtilServer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

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
    // Bumped for the activation and energy ledgers: extras gained two blocks, and the SKILLS tier now
    // enumerates skill-less baselines alongside its single-skill builds. Neither changes what an
    // existing figure means, but a SKILLS run at -2 has no baseline rows and cannot be audited, which
    // is exactly the kind of difference this string exists to keep visible.
    private static final String ENGINE_VERSION = "phase3-actives-3";

    private static final long MILLIS_PER_TICK = 50L;

    /** Window burst DPS is measured over: one second, in ticks. */
    private static final int BURST_WINDOW_TICKS = 20;

    /** Results are flushed in chunks so a long sweep is durable as it goes, not only at the end. */
    private static final int RESULT_FLUSH_CHUNK = 500;

    /**
     * How many duel diagnostics buffer before they go up.
     *
     * <p>Smaller than the result chunk because each row carries a JSON document -- the two combatant
     * snapshots, both residue strings and the whole hit timeline -- rather than a dozen numbers.
     */
    private static final int DIAGNOSTIC_FLUSH_CHUNK = 250;

    /**
     * How many trace rows accumulate before a flush. Larger than the diagnostic chunk because the
     * rows are far smaller -- a handful of numbers each, with no JSON document -- and produced far
     * faster, at roughly one per landed hit rather than one per duel.
     */
    private static final int TRACE_FLUSH_CHUNK = 2000;

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

    /**
     * The knobs the in-flight sweep was started with, frozen at {@link #run}.
     *
     * <p>Read from {@link SimulationGate} once rather than per tick. The gate's fields are injected
     * {@code @Config} values, so {@code /balancesim reload} rewrites them in place -- and every
     * consumer that asked the gate on the hot path silently switched mid-run. Run 140 is the case
     * that showed it: a reload 11 minutes in moved concurrency from 300 to 400 and the pool spawned
     * 100 new arenas, while {@code sim_run.config_hash} -- computed once, at the top of the run --
     * still recorded 300. Two runs that hash identically have to have measured under identical
     * settings, or the column means nothing, so the snapshot is taken in the same breath as the hash
     * and everything downstream reads it instead of the gate.
     */
    private volatile SweepSettings settings = SweepSettings.NONE;

    /** Guards against a second sweep being started while one is in flight. */
    private volatile boolean running;

    /**
     * The in-flight sweep's damage-pipeline watcher, or null between runs.
     *
     * <p>Per run rather than per plugin, because {@code DamageEvent} shares one static handler list
     * with every {@code CustomCancellableEvent} on the server and a sweep is the only time anyone
     * wants this. Started with the world and stopped in {@link #finish}, so an aborted run
     * unregisters through the same path a completed one does.
     */
    private SimDamageTelemetry telemetry;

    /**
     * The in-flight sweep's skill relevance audit, or null when the run was not asked for one.
     *
     * <p>Per run for the same reason the telemetry is: it accumulates this sweep's baselines and
     * single-skill observations, and carrying them into the next run would compare a build against a
     * baseline measured under a different configuration.
     */
    private SkillRelevanceAudit audit;

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
        return run(trigger, scope, scenario, false, listener);
    }

    /**
     * Starts a sweep, optionally auditing which of its skills could move a result at all.
     *
     * <p>The audit is a reporting mode over an ordinary sweep rather than a different kind of run:
     * every {@code sim_result} row is written exactly as it would have been, and the verdicts are
     * derived from those rows. That keeps a run's data the same whether or not anyone asked for an
     * audit, and means a future re-audit at a different threshold needs no new duels.
     *
     * @param audit whether to classify each skill's relevance and write the artifact
     */
    public CompletableFuture<SimSummary> run(SimulationTrigger trigger,
                                             SimScope scope,
                                             SimScenario scenario,
                                             boolean audit,
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
        // Constructed per run rather than injected, because it accumulates that run's observations
        // and a singleton would carry one sweep's baselines into the next.
        this.audit = audit
                ? new SkillRelevanceAudit(context.skillManager(), AuditThresholds.defaults())
                : null;
        // Before configHash, and before anything else reads a knob: from here to the summary the run
        // is parameterised by this snapshot and not by the gate.
        this.settings = SweepSettings.from(gate);
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

            final List<SimBuildSpec> builds = catalog.enumerateBuilds(scope, settings.maxBuilds());
            // The scenario is passed because it decides whether the target axis may be reduced: a
            // MUTUAL defender fights back, so its role is not reducible to a health total.
            final List<SimTargetSpec> targets = catalog.enumerateTargets(scope, scenario);
            final int iterations = settings.iterations();

            final long duels = (long) builds.size() * targets.size() * iterations;
            log.info("Simulation run {} scope {}: {} builds x {} targets x {} iterations = {} duels."
                            + " At {} concurrent and a {}s timeout that is at most {} minutes of wall clock.",
                    runId, scope, builds.size(), targets.size(), iterations, duels,
                    settings.maxConcurrentDuels(), settings.duelTimeoutSeconds(),
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
                    telemetry = SimDamageTelemetry.start(plugin);
                    // Announced at the top rather than discovered from the row count afterwards: the
                    // table is off by default and writes a row per duel, so a run that is quietly
                    // recording one is a surprise worth having in the log next to the sweep size.
                    if (settings.duelDiagnostics()) {
                        log.info("Duel diagnostics ON for run {}: up to {} sim_duel_diagnostic rows"
                                        + " over {} planned duels. Not part of config_hash, so this run"
                                        + " stays diffable against runs taken without it.",
                                runId, settings.duelDiagnosticsMaxRows(), pending.size()).submit();
                    }
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
        // Unconditionally, including on a run where nothing went wrong. The funnel is only readable
        // against a baseline, and a healthy run is the baseline -- logging it only when something
        // looks broken would mean never having one to compare against.
        if (telemetry != null) {
            log.info("Simulation run {} damage pipeline: {}", runId, telemetry.describe()).submit();
            telemetry.stop();
            telemetry = null;
        }
        worldManager.teardown();
        writeAudit(runId, summary);
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
     * Classifies the run's skills and writes the audit artifact, if this run was asked for one.
     *
     * <p>Runs for a cancelled or failed sweep too, deliberately. A {@code FULL} audit is not expected
     * to finish and stopping it early is the normal way to use it, so refusing to report on a partial
     * run would mean never reporting at all -- and the enumeration is interleaved by role, so an early
     * stop is a uniformly thinner sweep rather than a biased one. The artifact carries the run's
     * status, and a partial sweep's verdicts are proposals against thinner evidence rather than
     * different in kind.
     *
     * <p>Failures here are logged and swallowed. The audit is a report over rows that are already
     * durable, so losing it costs a re-derivation and nothing else -- whereas letting an IO error
     * escape would fail a run whose measurements were fine.
     */
    private void writeAudit(long runId, SimSummary summary) {
        if (audit == null) {
            return;
        }
        final SkillRelevanceAudit finished = audit;
        audit = null;
        try {
            if (!finished.hasBaselines()) {
                // Every verdict is a delta against a skill-less build of the same role and weapon, so
                // without one there is nothing to subtract and every skill would read as relevant.
                log.warn("Simulation run {} was asked for a skill audit but measured no skill-less"
                        + " baseline builds, so no verdict can be reached. Audit scopes must enumerate"
                        + " baselines -- SKILLS does; MELEE and WEAPONS have no skills to audit.",
                        runId).submit();
                return;
            }
            final List<SkillVerdict> verdicts = finished.classify();
            final Path artifact = SkillAuditReport.write(
                    plugin.getDataFolder().toPath().resolve("audits"),
                    runId,
                    summary.scope().name(),
                    scenario.name(),
                    configHash(summary.scope()),
                    finished.thresholds(),
                    verdicts,
                    finished.multiSkillBuildsSkipped());
            log.info("Simulation run {} skill audit: {}. Written to {} -- review before applying"
                            + " anything to config.",
                    runId, SkillAuditReport.summarise(verdicts), artifact).submit();
        } catch (Exception e) {
            log.error("Simulation run {} finished but its skill audit could not be written."
                    + " The rows are unaffected and the audit can be re-derived from them.",
                    runId, e).submit();
        }
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
     *   <li>the relevant-skill list, for the same reason one step down: under
     *       {@code SimSkillFilter.RELEVANT} the filter name is constant and the <em>list</em> is what
     *       decides which builds exist, so hashing the filter alone would group two sweeps of
     *       different skill sets;</li>
     *   <li>the scenario and the channel hold budget, because both change what a row <em>means</em>
     *       rather than just how much of the space it covers;</li>
     *   <li>iterations, timeout and concurrency, which bound the measurement's precision;</li>
     *   <li>the engine version, so a semantics change cannot masquerade as an unchanged config.</li>
     * </ul>
     * The retry interval is deliberately absent: it rate-limits attempts the real gates would have
     * refused anyway, so two runs differing only in it are measuring the same thing.
     */
    private String configHash(SimScope scope) {
        return Integer.toHexString((scope + ":" + scenario + ":" + settings.iterations()
                + ":" + settings.duelTimeoutSeconds() + ":" + settings.maxConcurrentDuels()
                + ":" + settings.skillFilter()
                + ":" + settings.relevantSkillsCanonical()
                + ":" + settings.channelHoldTicks()
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
                + ",\"iterations\":" + settings.iterations()
                + ",\"actives\":true"
                + ",\"rotation\":\"greedy\""
                + ",\"channel_hold_ticks\":" + settings.channelHoldTicks()
                + ",\"skill_filter\":\"" + settings.skillFilter() + '"'
                // The count rather than the names: the list runs to dozens of entries and this column
                // is read at a glance, while config_hash already makes two different lists distinct.
                + ",\"relevant_skills\":" + settings.relevantSkills().size()
                + ",\"balance_config\":\"" + configDigest.digest() + '"'
                + ",\"timeout_s\":" + settings.duelTimeoutSeconds()
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
        final double batches = Math.ceil((double) duels / Math.max(1, settings.maxConcurrentDuels()));
        return Math.round(batches * settings.duelTimeoutSeconds() / 60.0);
    }

    /**
     * Every knob a sweep is parameterised by, read from the gate once when the run opens.
     *
     * <p>A record rather than a set of fields so that "what this run was configured with" is one
     * value that can be passed to the loop and its duels, and so a knob added to {@link SimulationGate}
     * has to be added here deliberately rather than being picked up live by accident.
     *
     * <p>Only the orchestrator's own knobs. {@code SimCombatantPool} and {@link GreedyRotationPolicy}
     * are singletons that hold the gate themselves and still read it per tick -- so residency, the
     * quarantine, the channel hold budget and the retry interval remain live. That is closed at the
     * other end instead, by {@code /balancesim reload} refusing while a sweep is running; this
     * snapshot is what makes the refusal a belt rather than the only brace.
     *
     * @param iterations              Monte-Carlo iterations per matchup
     * @param maxBuilds               ceiling the catalog is enumerated under
     * @param maxConcurrentDuels      how many duels may be in flight at once
     * @param duelTimeoutSeconds      how long a duel runs before it is abandoned
     * @param duelSetupsPerTick       how many duels may be set up in a single tick
     * @param progressIntervalSeconds how often a snapshot is reported
     * @param channelHoldTicks        how long the rotation holds a channel -- hashed, so a run that
     *                                changed it mid-flight would be incomparable to itself
     * @param skillFilter             which skills the catalog was allowed to build from
     * @param relevantSkills          the reviewed audit list {@code SimSkillFilter.RELEVANT} sweeps.
     *                                Snapshotted and hashed rather than only named by the filter,
     *                                because two {@code RELEVANT} runs against different lists cover
     *                                different spaces while agreeing on every other knob -- and the
     *                                whole point of the list is that it is edited between runs
     * @param duelDiagnostics         whether every duel writes a {@code sim_duel_diagnostic} row.
     *                                Snapshotted like the rest so it cannot change mid-sweep, but
     *                                deliberately <em>not</em> hashed into {@code config_hash}:
     *                                observing a duel must not make it a different duel, and a
     *                                diagnostic run that could not be diffed against the run it
     *                                exists to explain would be useless
     * @param duelDiagnosticsMaxRows  ceiling on diagnostic rows for the run
     * @param hitTrace                whether every landed hit writes a {@code sim_trace} row.
     *                                Unhashed for the same reason as {@code duelDiagnostics}, and
     *                                more pointedly: the divergence this table exists to locate is a
     *                                timing one, so a trace that perturbed timing would manufacture
     *                                its own subject
     * @param hitTraceMaxRows         ceiling on trace rows for the run
     */
    private record SweepSettings(int iterations,
                                 int maxBuilds,
                                 int maxConcurrentDuels,
                                 double duelTimeoutSeconds,
                                 int duelSetupsPerTick,
                                 double progressIntervalSeconds,
                                 int channelHoldTicks,
                                 SimSkillFilter skillFilter,
                                 Set<String> relevantSkills,
                                 boolean duelDiagnostics,
                                 int duelDiagnosticsMaxRows,
                                 boolean hitTrace,
                                 int hitTraceMaxRows) {

        /** Stands in before the first run, so the field is never null for a reader that beats it. */
        private static final SweepSettings NONE =
                new SweepSettings(1, 0, 0, 0, 1, 15, 1, SimSkillFilter.OFFENSIVE, Set.of(),
                        false, 0, false, 0);

        private static SweepSettings from(SimulationGate gate) {
            return new SweepSettings(Math.max(1, gate.getIterations()),
                    gate.getMaxBuilds(),
                    gate.getMaxConcurrentDuels(),
                    gate.getDuelTimeoutSeconds(),
                    gate.getDuelSetupsPerTick(),
                    gate.getProgressIntervalSeconds(),
                    Math.max(1, gate.getChannelHoldTicks()),
                    SimSkillFilter.parse(gate.getSkillFilter()),
                    SimSkillFilter.parseRelevantSkills(gate.getRelevantSkills()),
                    gate.isDuelDiagnostics(),
                    Math.max(0, gate.getDuelDiagnosticsMaxRows()),
                    gate.isHitTrace(),
                    Math.max(0, gate.getHitTraceMaxRows()));
        }

        /**
         * The relevant-skill list in a stable order, for hashing and for the scenario JSON.
         *
         * <p>Sorted, because the list is hand-edited config: reordering it while reviewing an audit
         * must not read as a different sweep, and adding or removing a name must.
         */
        private String relevantSkillsCanonical() {
            return relevantSkills.stream().sorted().collect(Collectors.joining(","));
        }
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

        /**
         * Per-duel diagnostics awaiting a flush, and how many have been written.
         *
         * <p>Buffered separately from {@code results} because they are produced per duel rather than
         * per matchup -- an order of magnitude more rows, on a table that is off by default.
         */
        private final List<SimDuelDiagnosticRow> diagnostics = new ArrayList<>();
        private int diagnosticsRecorded;

        /**
         * Per-hit traces awaiting a flush, and how many have been written.
         *
         * <p>Another order of magnitude above the diagnostics -- roughly one row per landed hit --
         * which is why it carries its own cap rather than sharing the diagnostics' one.
         */
        private final List<SimTraceRow> traces = new ArrayList<>();
        private int tracesRecorded;
        /** Whether the trace cap has been announced, so it is said once rather than every duel. */
        private boolean traceCapAnnounced;
        /** Whether the row cap has been announced, so it is said once rather than every duel. */
        private boolean diagnosticsCapAnnounced;

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
         * Duels that ran to the timeout without the attacker landing a hit. See
         * {@link #noteBarrenTimeout()}.
         */
        private long barrenTimeouts;

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
                    logBarrenTimeouts();
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
                logBarrenTimeouts();
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

        /**
         * Records a duel that timed out having measured nothing, warning the first time.
         *
         * <p>Warned once and then counted, for the reason the drain is announced once: the useful
         * signal is that it is happening at all, and at several hundred duels a minute a line each
         * would bury the run's own progress. The running total then rides on the progress line, so a
         * sweep that starts producing nothing is visible while there is still time to stop it rather
         * than only in the closing summary.
         *
         * <p>Not an abort. A build genuinely unable to land a hit inside the timeout is a legitimate
         * -- if extreme -- measurement, and the engine does not get to decide that a row is too
         * strange to write. The judgement belongs to whoever reads the count.
         */
        private void noteBarrenTimeout() {
            if (barrenTimeouts++ == 0) {
                log.warn("Simulation run {} [{}]: a duel ran its full {}s timeout without the attacker"
                                + " landing a single hit. Its row will carry NULL damage and TTK. If this"
                                + " count keeps climbing the sweep is not measuring anything -- check that"
                                + " combatants are still fightable rather than waiting for the run to end.",
                        runId, scope, settings.duelTimeoutSeconds()).submit();
            }
        }

        /**
         * Dumps everything knowable about the first barren duel of the run, while it is still alive.
         *
         * <p>The placement is the point. Every previous dissection ran from
         * {@code SimCombatantPool.release}, which is after {@code teardown} has despawned both
         * combatants -- dropped their clients, unregistered their lookup entries and purged their
         * manager state -- and run 149 showed that a combatant probed there refuses damage before the
         * vanilla event is even raised, including a freshly spawned control that had never died. That
         * makes every teardown-time reading a statement about teardown rather than about the bug.
         *
         * <p>Here the duel has resolved but nothing has been torn down: both sides are still spawned,
         * still equipped, still registered, exactly as they were for the 600 swings that measured
         * nothing. Whatever the funnel says here is what was true during the fight.
         *
         * <p>Once per run, on the first one, because the interesting output is a single detailed case
         * and 300 copies of it would bury the rest of the log.
         */
        private void dissectFirstBarrenDuel(Duel duel) {
            if (barrenTimeouts != 1) {
                return;
            }
            final SimPlayer attackerHandle = duel.attacker.getHandle();
            final SimPlayer defenderHandle = duel.defender.getHandle();
            log.warn("Simulation run {} [{}]: dissecting the first barren duel, live, before teardown."
                            + " Both combatants are still spawned and registered, so unlike every"
                            + " previous dump this describes the fight rather than its cleanup."
                            + "\n  arena          {}"
                            + "\n  matchup        build {} vs target {}"
                            + "\n  attacker state {}"
                            + "\n  attacker funnel{}"
                            + "\n  defender state {}"
                            + "\n  defender funnel{}"
                            + "\n  defender probe {}"
                            + "\n  attacker probe {}",
                    runId, scope,
                    duel.slot.getArena().index(),
                    duel.matchup.build().fingerprint(), duel.matchup.target().role(),
                    attackerHandle.describeCombatState(),
                    " " + attackerHandle.describePipeline(),
                    defenderHandle.describeCombatState(),
                    " " + defenderHandle.describePipeline(),
                    defenderHandle.probeDamage(attackerHandle),
                    attackerHandle.probeDamage(defenderHandle)).submit();
        }

        /**
         * Closes the run with what fraction of it measured nothing, when any of it did.
         *
         * <p>Next to the summary rather than inside it, because it is a verdict on whether the rows
         * are worth querying rather than a count of work done -- and it is the one figure that
         * separates "this sweep found some very tanky targets" from "this sweep was broken". Silent
         * when the count is zero, so a healthy run gains no noise.
         */
        private void logBarrenTimeouts() {
            if (barrenTimeouts == 0) {
                return;
            }
            log.warn("Simulation run {} [{}]: {} of {} completed duels ({}%) timed out without the"
                            + " attacker landing a hit. Those rows carry NULL damage and TTK and measure"
                            + " nothing; treat the run as suspect rather than as a balance result.",
                    runId, scope, barrenTimeouts, completedDuels,
                    String.format(Locale.ROOT, "%.1f",
                            completedDuels == 0 ? 0.0 : 100.0 * barrenTimeouts / completedDuels)).submit();
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
                            + " {} packets queued, {} barren timeouts, {}/{} matchups measured."
                            + " Elapsed {}, remaining {}, ETA {}.",
                    runId, scope, completedDuels, plannedDuels,
                    String.format(Locale.ROOT, "%.1f", progress.percent()), active.size(),
                    combatantPool.residentCount(), combatantPool.queuedPacketBacklog(),
                    barrenTimeouts, completedMatchups, plannedMatchups,
                    progress.elapsedFormatted(), progress.etaFormatted(),
                    progress.estimatedFinish()).submit();

            listener.accept(progress);
        }

        /** How often a snapshot is emitted, floored at a second so a bad config cannot spam. */
        private long progressIntervalTicks() {
            return Math.max(20L, (long) (settings.progressIntervalSeconds() * 1000L / MILLIS_PER_TICK));
        }

        /**
         * Starts duels until the concurrency budget is full, the queue is empty, or this tick's
         * setup allowance is spent.
         */
        private void fill() {
            int setupsRemaining = Math.max(1, settings.duelSetupsPerTick());
            while (setupsRemaining-- > 0
                    && active.size() < settings.maxConcurrentDuels()
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
            // Fed the reduced matchup rather than the row, because the audit needs the build spec and
            // the typed ledgers, none of which survive into SimResultRow. Reading the same aggregate
            // the row is built from is what keeps the verdicts and the stored data consistent.
            if (audit != null) {
                audit.observe(matchup.build(), matchup.target(), aggregate);
            }
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

        /**
         * Files one duel's diagnostics, if this run is collecting them and has room left.
         *
         * <p>The whole point is that this row survives the reduction: {@code sim_result} is a mean
         * over the matchup's iterations, and run 164's flipped matchups are a difference between
         * individual duels that the mean cannot express. See {@link SimDuelDiagnosticRow}.
         *
         * <p>Failures here are swallowed rather than propagated. A diagnostic is an observation of a
         * sweep, and an observation that can abort the thing it is observing is worse than no
         * observation: the run would fail in a way that looks like a simulation bug.
         */
        private void recordDiagnostic(Duel duel, boolean killed, boolean attackerDied) {
            if (!settings.duelDiagnostics() || duel.attackerSetup == null || duel.defenderSetup == null) {
                return;
            }
            if (diagnosticsRecorded >= settings.duelDiagnosticsMaxRows()) {
                if (!diagnosticsCapAnnounced) {
                    diagnosticsCapAnnounced = true;
                    log.warn("Duel diagnostics capped at {} rows for run {}; the remaining duels of this"
                                    + " sweep are measured but not diagnosed. The rows that exist are a"
                                    + " contiguous prefix of the sweep, so an ordering question is still"
                                    + " answerable from them -- raise duelDiagnosticsMaxRows if it is not.",
                            settings.duelDiagnosticsMaxRows(), runId).submit();
                }
                return;
            }
            try {
                diagnostics.add(duel.toDiagnostic(killed, attackerDied));
                diagnosticsRecorded++;
            } catch (Exception e) {
                log.warn("Failed to build duel diagnostics for build {}", duel.matchup.buildId(), e).submit();
            }
            // On their own threshold rather than riding the result flush. Results are produced per
            // matchup and these per duel, so at ten iterations they accumulate ten times as fast --
            // and each one carries a JSON document rather than a handful of numbers.
            if (diagnostics.size() >= DIAGNOSTIC_FLUSH_CHUNK) {
                flushes.add(repository.insertDuelDiagnostics(runId, List.copyOf(diagnostics)));
                diagnostics.clear();
            }
        }

        /**
         * Files one duel's hits, if this run is tracing and has room left.
         *
         * <p>Assembled from the finished recording rather than emitted as hits land. That ordering is
         * the point: the divergence being hunted is a timing one, so tracing must not sit in the
         * damage path where it could add work to the very ticks it is timing. Reading the recording
         * afterwards costs nothing the duel can observe.
         *
         * <p>Ticks are relative to the duel's first landed hit, the same anchor TTK uses, so a trace
         * row and the {@code sim_result} row it explains share an axis. Setup cost varies with a
         * duel's position in its batch; anchoring anywhere earlier would fold that into every diff.
         */
        private void recordTrace(Duel duel, List<SimRecorder.HitRecord> attackerHits) {
            if (!settings.hitTrace()) {
                return;
            }
            if (tracesRecorded >= settings.hitTraceMaxRows()) {
                if (!traceCapAnnounced) {
                    traceCapAnnounced = true;
                    log.warn("Hit trace capped at {} rows for run {}; the remaining duels are measured"
                                    + " but not traced. The rows that exist are a contiguous prefix, so"
                                    + " whole duels are still diffable -- raise hitTraceMaxRows if the"
                                    + " matchup you need fell outside it.",
                            settings.hitTraceMaxRows(), runId).submit();
                }
                return;
            }
            try {
                // Both sides, not just the attacker's. Under MUTUAL the defender's hits are half of
                // what decides the fight, and a divergence that starts on the defender's swing would
                // otherwise show up only as an unexplained change in the attacker's later rows.
                final List<SimRecorder.HitRecord> all = duel.recording.allHits();
                if (all.isEmpty()) {
                    return;
                }
                final int anchor = attackerHits.isEmpty()
                        ? all.get(0).elapsedTicks()
                        : attackerHits.get(0).elapsedTicks();
                final UUID attackerId = duel.attacker.getUuid();
                int seq = 0;
                int lastTick = Integer.MIN_VALUE;
                for (SimRecorder.HitRecord hit : all) {
                    final int tick = hit.elapsedTicks() - anchor;
                    seq = tick == lastTick ? seq + 1 : 0;
                    lastTick = tick;
                    traces.add(new SimTraceRow(duel.matchup.buildId(),
                            duel.matchup.target().role(),
                            duel.matchup.target().armorSetId(),
                            // Read before the sample is filed, so it is this duel's index and not the
                            // next one's -- the same source and the same ordering constraint the
                            // diagnostic row's iteration has.
                            duel.matchup.measurement().samplesRecorded(),
                            duel.slot.getArena().index(),
                            tick,
                            seq,
                            attackerId.equals(hit.damager()) ? SimTraceRow.ATTACKER : SimTraceRow.DEFENDER,
                            SimTraceRow.HIT,
                            hit.rawDamage(),
                            hit.finalDamage()));
                    tracesRecorded++;
                }
            } catch (Exception e) {
                log.warn("Failed to build hit trace for build {}", duel.matchup.buildId(), e).submit();
            }
            if (traces.size() >= TRACE_FLUSH_CHUNK) {
                flushes.add(repository.insertTraces(runId, List.copyOf(traces)));
                traces.clear();
            }
        }

        /** Hands the accumulated rows to the repository and starts a fresh batch. */
        private void flush() {
            flushes.add(repository.insertResults(runId, List.copyOf(results)));
            rowsFlushed += results.size();
            results.clear();
            if (!diagnostics.isEmpty()) {
                flushes.add(repository.insertDuelDiagnostics(runId, List.copyOf(diagnostics)));
                diagnostics.clear();
            }
            if (!traces.isEmpty()) {
                flushes.add(repository.insertTraces(runId, List.copyOf(traces)));
                traces.clear();
            }
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

        /**
         * Whether this duel ran its whole timeout without the attacker landing a hit.
         *
         * <p>Set by {@link #record} and read by {@link #teardown}, which runs immediately after it.
         * It is the pool's evidence that the slot's residents are no longer fightable -- see
         * {@code SimCombatantPool.release} -- and the only such evidence there is, since a poisoned
         * combatant reads as perfectly healthy right up until nothing can hurt it.
         */
        private boolean barren;

        /**
         * Both sides' start-of-duel state, captured only when duel diagnostics are on.
         *
         * <p>Taken at setup rather than reconstructed at teardown, because most of what it records --
         * starting health, the role the resident arrived carrying, what the previous duel left on it
         * -- has been overwritten by the time the duel resolves.
         */
        @Nullable
        private SimCombatant.SetupSnapshot attackerSetup;
        @Nullable
        private SimCombatant.SetupSnapshot defenderSetup;

        /**
         * The furthest the two combatants got from each other, in blocks. {@code -1} until measured.
         *
         * <p>The one way a duel can be decided that leaves no trace in any other diagnostic: both
         * sides swing every tick regardless of range, so a pair knocked apart produces swings that
         * are refused upstream of every event the recorder watches. Knockback is applied by the
         * damage pipeline and by several skills, so a build carrying one is not necessarily fighting
         * at the same range as a bare one -- and range is not a property of either build.
         */
        private double maxSeparation = -1;

        private Duel(Matchup matchup, int sequence, long startTick, SimCombatantPool.Slot slot) {
            this.matchup = matchup;
            this.slot = slot;
            this.startTick = startTick;
            this.timeoutTicks = (long) (settings.duelTimeoutSeconds() * 1000L / MILLIS_PER_TICK);

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
                    "target:" + target.role() + ":" + target.armorSetId(),
                    // Carried because a build spec is what equips a combatant and the record requires
                    // it, not because anything reads it: this spec never reaches sim_build, so the
                    // defender's weapon profile is recorded nowhere. It stands for itself alone --
                    // the weapon dedupe applies to the swept attacker axis, and claiming the
                    // defender's fixed kit stood for other weapons would be false.
                    context.equipment().profileOf(context.equipment().defaultWeapon().key()),
                    List.of(context.equipment().defaultWeapon().key()));
        }

        private void setUp() {
            attacker.spawn(context, slot.getArena().spawnA(), settings.duelDiagnostics());
            defender.spawn(context, slot.getArena().spawnB(), settings.duelDiagnostics());
            // After the spawn, so a resident starts this duel's funnel at zero rather than carrying
            // its predecessor's counts into the one line that is supposed to describe this fight.
            attacker.getHandle().resetPipelineCounters();
            defender.getHandle().resetPipelineCounters();
            // After the counters are zeroed and before the recording opens, so the snapshot describes
            // the state the measured fight starts from and nothing it has produced yet.
            if (settings.duelDiagnostics()) {
                attackerSetup = attacker.setupSnapshot();
                defenderSetup = defender.setupSnapshot();
            }
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
                noteSwing(defender, attacker);
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
            noteSwing(attacker, defender);
            noteSeparation();
            return false;
        }

        /**
         * Keeps the furthest the pair has been apart, when this run is collecting diagnostics.
         *
         * <p>Sampled after the swings rather than before, so it reflects the knockback this tick's
         * hits applied -- which is the whole reason the figure is worth having.
         */
        private void noteSeparation() {
            if (!settings.duelDiagnostics()) {
                return;
            }
            final Player a = attacker.getPlayer();
            final Player b = defender.getPlayer();
            if (a == null || b == null || !a.getWorld().equals(b.getWorld())) {
                return;
            }
            maxSeparation = Math.max(maxSeparation, a.getLocation().distance(b.getLocation()));
        }

        /**
         * Counts a swing against the funnel, if this run is watching one.
         *
         * <p>Counted at the call rather than at the landing, which is the entire point: the recorder
         * already knows about hits that landed, and the number nobody has ever had is how many swings
         * were issued against a combatant that then measured nothing.
         */
        private void noteSwing(SimCombatant from, SimCombatant to) {
            if (telemetry != null) {
                telemetry.noteSwing(from.getHandle(), to.getHandle());
            }
        }

        /** Reduces the recording into one iteration's sample and files it against the matchup. */
        private void record(long endTick, SweepLoop loop) {
            recorder.endDuel(recording);
            final List<SimRecorder.HitRecord> hits =
                    recording.hitsFrom(attacker.getUuid(), defender.getUuid());

            final boolean killed = defender.getUuid().equals(recording.getKilled());

            // A duel that ran its whole timeout without the attacker landing a single hit is not a
            // slow build; it is a duel that never happened. resolvedAtTick < 0 is exactly the timeout
            // path -- a kill or a dead combatant would have set it -- so this is the barren case and
            // nothing else. Reported because it is otherwise invisible: run 140 spent 16 minutes here
            // for 95.6% of its duels while the progress line counted them as measured, and the only
            // trace was a NULL dmg_per_hit in a table nobody reads until the sweep is over.
            if (resolvedAtTick < 0 && hits.isEmpty()) {
                barren = true;
                loop.noteBarrenTimeout();
                loop.dissectFirstBarrenDuel(this);
            }

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
                //
                // A duel that timed out has no lethal hit to bound the window, and the fallback must
                // still start where the killed branch starts -- at the first landed hit. It used to
                // fall back to endTick - startTick, which is the whole duel including the setup and
                // the idle stretch before contact, so a timed-out row was diluted by exactly the
                // offset the engagement anchor above exists to cancel. The two branches therefore
                // measured different things and a kill/timeout mix on one matchup averaged them
                // together. Nothing else is affected: engagementStartTick is non-null whenever there
                // is a hit, and this block only runs when there is one.
                final long windowTicks = ttkTicks != null && ttkTicks > 0
                        ? ttkTicks
                        : Math.max(1, endTick - startTick - engagementStartTick);
                dpsSustained = total / ticksToSeconds(windowTicks);
                dpsBurst = burstDps(hits);
            }

            loop.writeEffectiveLevels(matchup.buildId(), attacker.getMeasuredSkills());

            // Recorded rather than inferred from !killed: a duel can also time out with both alive, and
            // under ONE_WAY the attacker cannot die at all, so a zero here is what distinguishes the
            // two scenarios on the row as well as on the run.
            final boolean attackerDied = attacker.getUuid().equals(recording.getKilled());

            // The attacker's side of both ledgers, for the same reason every other figure on the row
            // is the attacker's: a sim_result row describes the attacker's build. The defender's
            // counts are recorded too and are reachable from the recording, which is what the
            // defensive half of the relevance audit will read once defenders can carry skills.
            // Before the sample is filed, so the iteration index is this duel's own rather than the
            // next one's.
            loop.recordDiagnostic(this, killed, attackerDied);
            // Same window as the diagnostic, and for the same reason: both index the duel by how many
            // samples the matchup has taken, so both must run before the sample below is filed.
            loop.recordTrace(this, hits);

            matchup.measurement().add(
                    new SimMeasurement.Sample(dmgPerHit, dpsSustained, dpsBurst, ttkTicks,
                            hits.size(), killed, attackerDied, recording.isEnergyLimited(),
                            recording.activationsOf(attacker.getUuid()),
                            recording.energyOf(attacker.getUuid())),
                    reasonCounts(hits));

            if (matchup.measurement().isComplete()) {
                loop.complete(matchup);
            }
        }

        /**
         * This duel as a diagnostic row: what both sides started from, and everything that happened.
         *
         * <p>The hits are taken whole rather than filtered to the attacker's direction, which is the
         * one thing {@code sim_result} structurally cannot carry. Under {@code MUTUAL} the duel is a
         * race -- the defender acts and swings first on every tick, and a 29 HP target dies in five
         * hits -- so the defender's half of the timeline is not context for the measurement, it is
         * the other half of the thing being measured.
         *
         * <p>Health is read live rather than from the recording: the loser's remaining health is what
         * says whether a fight was lost by one hit or by five, and no event carries it.
         */
        private SimDuelDiagnosticRow toDiagnostic(boolean killed, boolean attackerDied) {
            final SimDuelDiagnosticRow.Outcome outcome;
            if (killed) {
                outcome = SimDuelDiagnosticRow.Outcome.DEFENDER_KILLED;
            } else if (attackerDied) {
                outcome = SimDuelDiagnosticRow.Outcome.ATTACKER_KILLED;
            } else if (barren) {
                outcome = SimDuelDiagnosticRow.Outcome.BARREN;
            } else {
                outcome = SimDuelDiagnosticRow.Outcome.TIMEOUT;
            }

            return new SimDuelDiagnosticRow(matchup.buildId(),
                    matchup.target(),
                    matchup.measurement().samplesRecorded(),
                    slot.getArena().index(),
                    outcome,
                    resolvedAtTick < 0 ? null : (int) (resolvedAtTick - startTick),
                    side(attacker, attackerSetup),
                    side(defender, defenderSetup),
                    maxSeparation,
                    recording.allHits());
        }

        /** One side of the diagnostic row, pairing its setup snapshot with what it ended up doing. */
        private SimDuelDiagnosticRow.Side side(SimCombatant combatant,
                                               SimCombatant.SetupSnapshot snapshot) {
            final Player bukkit = combatant.getPlayer();
            return new SimDuelDiagnosticRow.Side(combatant.getUuid(),
                    snapshot,
                    // Null once the combatant has been torn down, which teardown does after this runs
                    // -- but a combatant that died mid-duel can also read as absent, and "dead" and
                    // "already released" are not the same claim.
                    bukkit == null ? null : bukkit.getHealth(),
                    combatant.getHandle().describePipeline(),
                    recording.energyOf(combatant.getUuid()),
                    recording.activationsOf(combatant.getUuid()));
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
         * <p>{@link #barren} goes back with it. A duel that measured nothing is the pool's evidence
         * that this slot's residents have stopped being fightable, and it is worth acting on there
         * rather than here because the pool is what can do something about it -- replace the pair --
         * and because it is the only place that knows whether the slot has ever hosted a death.
         *
         * @param currentTick when the quarantine window starts counting from
         */
        private void teardown(long currentTick) {
            attacker.despawn(context);
            defender.despawn(context);
            combatantPool.release(slot, currentTick, barren);
        }
    }
}
