package me.mykindos.betterpvp.balancesim.engine;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.balancesim.BalanceSimulation;
import me.mykindos.betterpvp.balancesim.SimulationGate;
import me.mykindos.betterpvp.balancesim.catalog.BalanceCatalog;
import me.mykindos.betterpvp.balancesim.catalog.SimBuildSpec;
import me.mykindos.betterpvp.balancesim.catalog.SimEquipment;
import me.mykindos.betterpvp.balancesim.catalog.SimScope;
import me.mykindos.betterpvp.balancesim.catalog.SimSkillAllocation;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Runs the sweep: pairs every catalog build against every target, drives both sides through the
 * real combat pipeline, and hands the recorder's output to the repository.
 *
 * <p>Duels run in real time -- a 30 s fight costs 30 s -- but all combat state is keyed per
 * player UUID, so many run simultaneously in separate arenas and a sweep is minutes rather than
 * hours. Per tick the orchestrator performs due melee swings and (from phase 3) lets the
 * {@link RotationPolicy} synthesise skill inputs.
 *
 * <p><b>Phase 2 scope.</b> The catalog's role, weapon, skill and target-armour axes all reach the
 * pipeline, each matchup is measured over {@code gate.getIterations()} Monte-Carlo iterations, and
 * the reduction to mean plus percentiles happens in {@link SimMeasurement}. What is measured is
 * still melee plus <em>passives</em>: an equipped active skill is never activated, because no
 * input is synthesised for it yet. That is the phase 3 rotation policy, and until then a build
 * carrying an active is measured as if it held the slot empty -- which is why {@code sim_build}
 * records the allocation, so such a row can be told apart later rather than silently averaged in.
 */
@Singleton
@CustomLog
public class DuelOrchestrator {

    /** Bumped whenever measurement semantics change, so old runs are not diffed against new ones. */
    private static final String ENGINE_VERSION = "phase2-melee-passives-1";

    private static final long MILLIS_PER_TICK = 50L;

    /** Window burst DPS is measured over: one second, in ticks. */
    private static final int BURST_WINDOW_TICKS = 20;

    /** Results are flushed in chunks so a long sweep is durable as it goes, not only at the end. */
    private static final int RESULT_FLUSH_CHUNK = 500;

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
                            SimEquipment equipment,
                            BalanceSimulation plugin) {
        this.gate = gate;
        this.worldManager = worldManager;
        this.catalog = catalog;
        this.repository = repository;
        this.recorder = recorder;
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
        this.context = new SimCombatant.SimContext(plugin,
                clientFactory,
                championsInjector.getInstance(RoleManager.class),
                championsInjector.getInstance(ChampionsSkillManager.class),
                equipment);
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
        return run(trigger, scope, progress -> {
        });
    }

    /**
     * Starts a sweep, reporting progress as it goes.
     *
     * @param trigger  what kicked it off, recorded on the {@code sim_run} row
     * @param scope    which slice of the permutation space to cover
     * @param listener called on the main thread with a periodic snapshot, and once more when the
     *                 run ends. Progress also goes to the server log unconditionally, so a run
     *                 started from the console or by a schedule is still observable.
     * @return completes with the run's {@link SimSummary} once every matchup has been measured and
     *         persisted. The summary is the run's result rather than a log line so the closing
     *         figures reach the caller, not only the console.
     * @throws IllegalStateException if the simulation gate is closed. A sweep that is already
     *                               running yields a failed future rather than a thrown exception.
     */
    public CompletableFuture<SimSummary> run(SimulationTrigger trigger, SimScope scope, Consumer<SimProgress> listener) {
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
     * <p>TODO(phase 3): hash the champions/item config values that actually determine damage.
     * Skill damage and weapon stats are read live from those configs during a run, so two sweeps
     * with different balance numbers currently share a hash. Until that is closed, a patch diff
     * must be pinned by {@code engine_version} and run timestamp rather than by this alone.
     */
    private String configHash(SimScope scope) {
        return Integer.toHexString((scope + ":" + gate.getIterations() + ":" + gate.getDuelTimeoutSeconds()
                + ":" + gate.getMaxConcurrentDuels() + ":" + ENGINE_VERSION).hashCode());
    }

    private String scenarioJson(SimScope scope) {
        return "{\"phase\":2,\"scope\":\"" + scope + "\",\"iterations\":" + Math.max(1, gate.getIterations())
                + ",\"actives\":false,\"timeout_s\":" + gate.getDuelTimeoutSeconds() + "}";
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

        /**
         * Builds whose effective levels have already been written back, so the update runs once per
         * build rather than once per duel.
         */
        private final Set<Long> effectiveLevelsWritten = new HashSet<>();

        private BukkitTask task;
        private long tick;
        private int arenaCounter;

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
                fill();
                step();
                if (active.isEmpty() && pending.isEmpty()) {
                    task.cancel();
                    report();
                    finish(runId, done, List.copyOf(results), List.copyOf(flushes),
                            summarise("COMPLETED"));
                    return;
                }
                if (tick - lastProgressTick >= progressIntervalTicks()) {
                    lastProgressTick = tick;
                    report();
                }
            } catch (Exception e) {
                task.cancel();
                active.forEach(Duel::teardown);
                log.error("Simulation run {} aborted after {} of {} duels", runId, completedDuels,
                        plannedDuels, e).submit();
                report();
                // Still finished through the same path, so the partial run's rows are flushed and
                // summarised rather than discarded -- an aborted sweep's measurements are valid for
                // the matchups that did complete, and the summary says how far it got.
                finish(runId, done, List.copyOf(results), List.copyOf(flushes), summarise("FAILED"));
            }
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

            log.info("Simulation run {} [{}]: {}/{} duels ({}%), {} in flight, {}/{} matchups measured."
                            + " Elapsed {}, remaining {}, ETA {}.",
                    runId, scope, completedDuels, plannedDuels,
                    String.format(Locale.ROOT, "%.1f", progress.percent()), active.size(),
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
                final Duel duel = new Duel(pending.poll(), arenaCounter++, tick);
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
                duel.teardown();
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
                    // Energy limiting is a property of a rotation, and nothing activates a skill
                    // yet, so claiming it either way would be a guess. Phase 3.
                    false,
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
         * what supplies base health and its armour is what supplies the rest.
         *
         * <p>The synthesised spec's fingerprint is not a catalog fingerprint and never reaches
         * {@code sim_build}: the defender is described by {@code sim_result}'s own target columns.
         * It is still distinct per role and armour set so two targets can never share a spec.
         */
        private SimBuildSpec defenderBuild() {
            final SimTargetSpec target = matchup.target();
            return new SimBuildSpec(target.role(),
                    // The defender never swings in phase 2, so its weapon is only the standard kit.
                    context.equipment().defaultWeapon().key(),
                    target.armorSetId(),
                    List.of(),
                    target.skills(),
                    target.pointsSpent(),
                    false,
                    "target:" + target.role() + ":" + target.armorSetId());
        }

        private void setUp() {
            attacker.spawn(context, arena.spawnA());
            defender.spawn(context, arena.spawnB());
            recording = recorder.startDuel(attacker.getUuid(), defender.getUuid());
        }

        /**
         * Performs the tick's swing and reports whether the duel has resolved.
         *
         * <p>Only the attacker swings. A mutual exchange would truncate the attacker's
         * time-to-kill whenever the defender won the race, which is a meaningful measurement but a
         * different one; it belongs with the phase 3 rotation policy that can drive both sides
         * properly. Measuring one direction keeps a row unambiguous.
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

            matchup.measurement().add(
                    new SimMeasurement.Sample(dmgPerHit, dpsSustained, dpsBurst, ttkTicks,
                            hits.size(), killed),
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

        private void teardown() {
            attacker.despawn(context);
            defender.despawn(context);
        }
    }
}
