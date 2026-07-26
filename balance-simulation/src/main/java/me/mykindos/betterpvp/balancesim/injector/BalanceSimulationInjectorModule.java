package me.mykindos.betterpvp.balancesim.injector;

import com.google.inject.AbstractModule;
import me.mykindos.betterpvp.balancesim.BalanceSimulation;

public class BalanceSimulationInjectorModule extends AbstractModule {

    private final BalanceSimulation plugin;

    public BalanceSimulationInjectorModule(BalanceSimulation plugin) {
        this.plugin = plugin;
    }

    @Override
    protected void configure() {
        bind(BalanceSimulation.class).toInstance(plugin);
    }

}
