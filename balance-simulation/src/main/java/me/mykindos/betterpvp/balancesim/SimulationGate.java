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

    /**
     * Which slice of the permutation space {@code /simulate} sweeps when the command is given no
     * argument. See {@code SimScope}; defaults to the cheapest tier so an accidental invocation
     * cannot start a multi-hour sweep.
     */
    @Inject
    @Config(path = "champions.simulation.scope", defaultValue = "MELEE")
    private String scope;

    /**
     * Ceiling on how many builds a scope may enumerate. A scope over this is refused with its
     * count rather than truncated: a prefix of an enumeration is a biased sample, and nothing on
     * the resulting rows would say so.
     */
    @Inject
    @Config(path = "champions.simulation.maxBuilds", defaultValue = "5000")
    private int maxBuilds;

    /**
     * How many duels may be set up in a single tick.
     *
     * <p>Setting a duel up is expensive and strictly main-thread: two {@code ServerPlayer}s are
     * spawned, roles equipped, builds registered and armour events fired, on the order of tens of
     * milliseconds each. Filling the whole concurrency budget in one tick therefore stalls the
     * server for as long as the batch takes -- which is also what made every duel in a batch start
     * at a different real time, the aliasing that first showed up as TTK variance across identical
     * matchups. Spreading setup over ticks keeps the tick loop responsive and the arrivals even.
     */
    @Inject
    @Config(path = "champions.simulation.duelSetupsPerTick", defaultValue = "4")
    private int duelSetupsPerTick;

    /**
     * How often a sweep reports progress to the log and to whoever started it.
     *
     * <p>A sweep is long and otherwise silent -- duels run in real time -- so without this there
     * is no way to tell a healthy long run from a wedged one, and no basis for deciding whether to
     * let it finish. Floored at one second by the orchestrator regardless of what is configured.
     */
    @Inject
    @Config(path = "champions.simulation.progressIntervalSeconds", defaultValue = "15.0")
    private double progressIntervalSeconds;

}
