package me.mykindos.betterpvp.balancesim;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import lombok.CustomLog;
import lombok.Getter;
import lombok.Setter;
import me.mykindos.betterpvp.balancesim.commands.BalanceSimulationCommandLoader;
import me.mykindos.betterpvp.balancesim.engine.SimCombatantPool;
import me.mykindos.betterpvp.balancesim.injector.BalanceSimulationInjectorModule;
import me.mykindos.betterpvp.balancesim.listeners.BalanceSimulationListenerLoader;
import me.mykindos.betterpvp.balancesim.world.SimWorldManager;
import me.mykindos.betterpvp.core.Core;
import me.mykindos.betterpvp.core.config.Config;
import me.mykindos.betterpvp.core.config.ConfigInjectorModule;
import me.mykindos.betterpvp.core.database.Database;
import me.mykindos.betterpvp.core.framework.BPvPPlugin;
import me.mykindos.betterpvp.core.framework.ModuleLoadedEvent;
import me.mykindos.betterpvp.core.framework.updater.UpdateEventExecutor;
import me.mykindos.betterpvp.core.locale.TranslationService;
import org.bukkit.Bukkit;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;

import java.lang.reflect.Field;
import java.util.Set;

/**
 * Dev-only balance simulation engine.
 *
 * <p>Ships as its own plugin rather than living inside {@code champions} so that a build or
 * deploy which does not want simulation code on the classpath can simply omit the jar. The
 * {@code champions.simulation.enabled} config flag ({@link SimulationGate}) is defence in
 * depth on top of that, not the primary isolation mechanism.
 *
 * <p>See {@code docs/balance-simulation/DESIGN.md}. This class is scaffolding: it wires the
 * module into the framework (injector, loaders, migrations) so the engine pieces have
 * somewhere to land, but nothing here runs a duel yet.
 */
@Singleton
@CustomLog
public class BalanceSimulation extends BPvPPlugin {

    private final String PACKAGE = getClass().getPackageName();

    @Getter
    @Setter
    private Injector injector;

    @Inject
    private Database database;

    @Inject
    private UpdateEventExecutor updateEventExecutor;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        var core = (Core) Bukkit.getPluginManager().getPlugin("Core");
        if (core == null) {
            return;
        }

        TranslationService.registerBundle(this, "translations.balancesim");

        final Reflections fieldReflections = new Reflections(PACKAGE, Scanners.FieldsAnnotated);
        final Set<Field> fields = fieldReflections.getFieldsAnnotatedWith(Config.class);

        injector = core.getInjector().createChildInjector(new BalanceSimulationInjectorModule(this),
                new ConfigInjectorModule(this, fields));
        injector.injectMembers(this);

        database.getConnection().runDatabaseMigrations(getClass().getClassLoader(),
                "classpath:balancesim-migrations/postgres/", "balancesim");

        Bukkit.getPluginManager().callEvent(new ModuleLoadedEvent("BalanceSimulation"));

        var listenerLoader = injector.getInstance(BalanceSimulationListenerLoader.class);
        listenerLoader.registerListeners(PACKAGE);

        var commandLoader = injector.getInstance(BalanceSimulationCommandLoader.class);
        commandLoader.loadCommands(PACKAGE);

        updateEventExecutor.loadPlugin(this);

        final SimulationGate gate = injector.getInstance(SimulationGate.class);
        if (gate.isEnabled()) {
            log.warn("Balance simulation is ENABLED. This must never be a production realm.").submit();
        } else {
            log.info("Balance simulation loaded but gated off (champions.simulation.enabled=false).").submit();
        }
    }

    @Override
    public void onDisable() {
        if (injector == null) {
            return;
        }
        // Duels leave fake players and a void world behind; tear both down so a reload does
        // not resurrect a half-built arena. The pool first: its residents are entities inside the
        // world, and between sweeps they outlive any duel, so a disable mid-quarantine still has
        // combatants standing in arenas that are about to stop existing.
        injector.getInstance(SimCombatantPool.class).drain();
        injector.getInstance(SimWorldManager.class).teardown();
    }
}
