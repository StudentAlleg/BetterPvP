package me.mykindos.betterpvp.balancesim.engine;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.balancesim.SimulationGate;
import me.mykindos.betterpvp.balancesim.catalog.BalanceCatalog;
import me.mykindos.betterpvp.balancesim.repository.SimResultRepository;
import me.mykindos.betterpvp.balancesim.world.SimWorldManager;

import java.util.concurrent.CompletableFuture;

/**
 * Runs the sweep: pairs every catalog build against every target, drives both sides through the
 * real combat pipeline, and hands the recorder's output to the repository.
 *
 * <p>Duels run in real time -- a 30 s fight costs 30 s -- but all combat state is keyed per
 * player UUID, so hundreds run simultaneously in separate arenas and a full sweep is minutes
 * rather than hours. Per tick the orchestrator performs due melee swings (timing from the real
 * {@code DEFAULT_DELAY / (1 + attackSpeed)}, with {@code DamageDelayManager} left in place as
 * the enforcement backstop) and lets the {@link RotationPolicy} synthesise skill inputs.
 *
 * <p><b>Scaffolding.</b> {@link #run} validates the gate and returns immediately.
 */
@Singleton
@CustomLog
public class DuelOrchestrator {

    private final SimulationGate gate;
    private final SimWorldManager worldManager;
    private final BalanceCatalog catalog;
    private final SimResultRepository repository;

    @Inject
    public DuelOrchestrator(SimulationGate gate,
                            SimWorldManager worldManager,
                            BalanceCatalog catalog,
                            SimResultRepository repository) {
        this.gate = gate;
        this.worldManager = worldManager;
        this.catalog = catalog;
        this.repository = repository;
    }

    /**
     * Starts a sweep.
     *
     * @param trigger what kicked it off, recorded on the {@code sim_run} row
     * @return completes when every matchup has been measured and persisted
     * @throws IllegalStateException if the simulation gate is closed
     */
    public CompletableFuture<Void> run(SimulationTrigger trigger) {
        if (!gate.isEnabled()) {
            throw new IllegalStateException("Simulation is disabled");
        }

        // TODO(phase 2): open a sim_run, spawn combatants into worldManager arenas up to
        // gate.getMaxConcurrentDuels(), run gate.getIterations() iterations per matchup with
        // gate.getDuelTimeoutSeconds() as the hard stop, batch results through the repository,
        // then tear the world down and close the run.
        log.warn("DuelOrchestrator.run({}) is scaffolding; no duels were executed", trigger).submit();
        return CompletableFuture.completedFuture(null);
    }
}
