package me.mykindos.betterpvp.balancesim.engine;

/**
 * What started a run. Persisted on {@code sim_run.trigger} so a surprising result can be traced
 * back to how it was produced.
 */
public enum SimulationTrigger {

    /** {@code /simulate}, run by hand. */
    COMMAND,

    /** An {@code @UpdateEvent} schedule, e.g. a nightly all-vectors sweep. */
    SCHEDULED
}
