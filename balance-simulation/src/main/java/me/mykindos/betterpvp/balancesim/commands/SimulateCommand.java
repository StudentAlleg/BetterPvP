package me.mykindos.betterpvp.balancesim.commands;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.balancesim.SimulationGate;
import me.mykindos.betterpvp.balancesim.engine.DuelOrchestrator;
import me.mykindos.betterpvp.balancesim.engine.SimulationTrigger;
import me.mykindos.betterpvp.core.client.Client;
import me.mykindos.betterpvp.core.command.Command;
import me.mykindos.betterpvp.core.framework.annotations.WithReflection;
import me.mykindos.betterpvp.core.utilities.UtilMessage;
import org.bukkit.entity.Player;

/**
 * Admin entry point for a simulation sweep.
 *
 * <p>Refuses to do anything when {@link SimulationGate#isEnabled()} is false, which is the
 * default -- the simulator drives real combat with fake players, so it belongs on dev and
 * staging only.
 */
@Singleton
@WithReflection
@CustomLog
public class SimulateCommand extends Command {

    private final SimulationGate gate;
    private final DuelOrchestrator orchestrator;

    @Inject
    public SimulateCommand(SimulationGate gate, DuelOrchestrator orchestrator) {
        this.gate = gate;
        this.orchestrator = orchestrator;
    }

    @Override
    public String getName() {
        return "simulate";
    }

    @Override
    public String getDescription() {
        return "balancesim.command.simulate.description";
    }

    @Override
    public void execute(Player player, Client client, String... args) {
        if (!gate.isEnabled()) {
            UtilMessage.message(player, "core.prefix.command", "balancesim.command.simulate.disabled");
            return;
        }

        UtilMessage.message(player, "core.prefix.command", "balancesim.command.simulate.started");
        orchestrator.run(SimulationTrigger.COMMAND)
                .thenRun(() -> UtilMessage.message(player, "core.prefix.command",
                        "balancesim.command.simulate.finished"))
                .exceptionally(ex -> {
                    log.error("Simulation run failed", ex).submit();
                    UtilMessage.message(player, "core.prefix.command", "balancesim.command.simulate.failed");
                    return null;
                });
    }
}
