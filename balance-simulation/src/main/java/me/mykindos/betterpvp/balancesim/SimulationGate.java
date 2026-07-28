package me.mykindos.betterpvp.balancesim;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.Getter;
import me.mykindos.betterpvp.core.config.Config;

/**
 * Hard gate on every simulation entry point, plus the knobs a run is parameterised by.
 *
 * <p>The simulator spawns fake players into the real combat pipeline and writes to the real
 * database, so it must only ever run on dev/staging. Isolation is layered: the primary
 * mechanism is that this plugin's jar is absent from production deploys, and this flag is the
 * second layer for the case where it is present anyway.
 *
 * <p>Every command, scheduler and reload hook checks {@link #isEnabled()} before doing any
 * work. Default is {@code false}.
 */
@Singleton
@Getter
public class SimulationGate {

    @Inject
    @Config(path = "champions.simulation.enabled", defaultValue = "false")
    private boolean enabled;

    @Inject
    @Config(path = "champions.simulation.worldName", defaultValue = "bpvp_sim")
    private String worldName;

    @Inject
    @Config(path = "champions.simulation.iterations", defaultValue = "100")
    private int iterations;

    @Inject
    @Config(path = "champions.simulation.maxConcurrentDuels", defaultValue = "64")
    private int maxConcurrentDuels;

    @Inject
    @Config(path = "champions.simulation.duelTimeoutSeconds", defaultValue = "30.0")
    private double duelTimeoutSeconds;

}
