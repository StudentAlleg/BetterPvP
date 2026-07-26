package me.mykindos.betterpvp.balancesim.engine;

/**
 * What started a run. Persisted on {@code sim_run.trigger} so a surprising result can be traced
 * back to how it was produced.
 */
public enum SimulationTrigger {

    /** {@code /simulate}, run by hand. */
    COMMAND,

    /** Fired after {@code /reload}, so dashboards are never stale. Skipped when the config hash is unchanged. */
    CONFIG_RELOAD,

    /** An {@code @UpdateEvent} schedule, e.g. a nightly all-vectors sweep. */
    SCHEDULED
}
