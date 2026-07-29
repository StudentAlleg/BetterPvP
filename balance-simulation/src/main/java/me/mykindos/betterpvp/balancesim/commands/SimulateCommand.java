package me.mykindos.betterpvp.balancesim.commands;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.balancesim.SimulationGate;
import me.mykindos.betterpvp.balancesim.catalog.SimScope;
import me.mykindos.betterpvp.balancesim.engine.DuelOrchestrator;
import me.mykindos.betterpvp.balancesim.engine.SimProgress;
import me.mykindos.betterpvp.balancesim.engine.SimSummary;
import me.mykindos.betterpvp.balancesim.engine.SimulationTrigger;
import me.mykindos.betterpvp.core.client.Client;
import me.mykindos.betterpvp.core.command.Command;
import me.mykindos.betterpvp.core.command.IConsoleCommand;
import me.mykindos.betterpvp.core.framework.annotations.WithReflection;
import me.mykindos.betterpvp.core.utilities.UtilMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

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
public class SimulateCommand extends Command implements IConsoleCommand {

    /** First argument that asks the running sweep to stop rather than naming a tier to start. */
    private static final String STOP_ARGUMENT = "stop";

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
        execute(player, args);
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!gate.isEnabled()) {
            UtilMessage.message(sender, "core.prefix.command", "balancesim.command.simulate.disabled");
            return;
        }

        // Checked before the scope is parsed, so "stop" cannot be mistaken for a mistyped tier and
        // answered with the list of valid scopes.
        if (args.length > 0 && STOP_ARGUMENT.equalsIgnoreCase(args[0])) {
            stop(sender);
            return;
        }

        // An unrecognised scope is refused rather than defaulted. The tiers differ by orders of
        // magnitude in cost, so silently running MELEE for a mistyped FULL would look like a
        // finished sweep and be a different measurement entirely.
        final SimScope scope;
        if (args.length > 0) {
            final Optional<SimScope> parsed = SimScope.parse(args[0]);
            if (parsed.isEmpty()) {
                UtilMessage.message(sender, "core.prefix.command", "balancesim.command.simulate.badScope",
                        Component.text(args[0]), Component.text(scopeNames()));
                return;
            }
            scope = parsed.get();
        } else {
            scope = SimScope.parse(gate.getScope()).orElse(SimScope.MELEE);
        }

        UtilMessage.message(sender, "core.prefix.command", "balancesim.command.simulate.started",
                Component.text(scope.name()));
        orchestrator.run(SimulationTrigger.COMMAND, scope, progress -> report(sender, progress))
                .thenAccept(summary -> report(sender, summary))
                .exceptionally(ex -> {
                    log.error("Simulation run failed", ex).submit();
                    UtilMessage.message(sender, "core.prefix.command", "balancesim.command.simulate.failed");
                    return null;
                });
    }

    /**
     * Asks the running sweep to stop, and says what that means.
     *
     * <p>The acknowledgement is deliberately not "stopped": the sweep drains its in-flight duels
     * first, so between this message and the summary line there is a window in which duels are
     * still being measured. Reporting a stop as immediate would invite a second command, or a
     * server stop, in exactly the seconds the run needs to write its rows.
     */
    private void stop(CommandSender sender) {
        if (!orchestrator.requestStop()) {
            UtilMessage.message(sender, "core.prefix.command", "balancesim.command.simulate.notRunning");
            return;
        }
        UtilMessage.message(sender, "core.prefix.command", "balancesim.command.simulate.stopping");
    }

    /**
     * Relays a progress snapshot to whoever started the sweep.
     *
     * <p>Both counts are shown, because they answer different questions and can be far apart: a
     * duel is a unit of work and moves constantly, while a matchup only becomes a
     * {@code sim_result} row once all of its Monte-Carlo iterations are in. Reporting duels alone
     * would suggest a run is nearly done when very few rows have actually been written.
     *
     * <p>Sent to the {@code CommandSender} that ran the command. If that was a player who has
     * since logged out the messages simply go nowhere -- the log copy is unconditional, so a run
     * is never unobservable.
     */
    private static void report(CommandSender sender, SimProgress progress) {
        UtilMessage.message(sender, "core.prefix.command", "balancesim.command.simulate.progress",
                Component.text(progress.completedDuels()),
                Component.text(progress.plannedDuels()),
                Component.text(String.format(Locale.ROOT, "%.1f", progress.percent())),
                Component.text(progress.activeDuels()),
                Component.text(progress.completedMatchups()),
                Component.text(progress.plannedMatchups()),
                Component.text(progress.elapsedFormatted()),
                Component.text(progress.etaFormatted()),
                Component.text(progress.estimatedFinish()));
    }

    /**
     * Relays the closing figures to whoever started the sweep, mirroring the summary line the
     * orchestrator writes to the log.
     *
     * <p>Sent rather than a bare "finished" because the useful question at the end of a multi-hour
     * run is not whether it stopped but whether its rows are worth querying: a run can close
     * {@code COMPLETED} having skipped builds, and the planned-versus-measured counts are the only
     * thing that says so. A run that did not cover everything it planned gets an explicit warning
     * line, so a partial sweep is not quietly treated as a whole one.
     */
    private static void report(CommandSender sender, SimSummary summary) {
        UtilMessage.message(sender, "core.prefix.command", "balancesim.command.simulate.finished",
                Component.text(summary.runId()),
                Component.text(summary.scope().name()),
                Component.text(summary.status()),
                Component.text(summary.completedDuels()),
                Component.text(summary.plannedDuels()),
                Component.text(summary.completedMatchups()),
                Component.text(summary.plannedMatchups()),
                Component.text(summary.resultRows()),
                Component.text(summary.elapsedFormatted()));
        if (!summary.whole()) {
            UtilMessage.message(sender, "core.prefix.command", "balancesim.command.simulate.partial");
        }
    }

    /**
     * Tab completion offers the tiers, so the cost difference between them is discoverable rather
     * than something an admin has to read the source to find, plus {@code stop} while a sweep is
     * running -- the moment you need it is the moment a long run is underway, which is exactly when
     * looking it up is least convenient.
     */
    @Override
    public List<String> processTabComplete(CommandSender sender, String[] args) {
        if (args.length <= 1) {
            final String prefix = args.length == 0 ? "" : args[0].toUpperCase(Locale.ROOT);
            final List<String> options = Arrays.stream(SimScope.values())
                    .map(Enum::name)
                    .filter(name -> name.startsWith(prefix))
                    .collect(Collectors.toList());
            if (orchestrator.isRunning() && STOP_ARGUMENT.startsWith(prefix.toLowerCase(Locale.ROOT))) {
                options.add(STOP_ARGUMENT);
            }
            return options;
        }
        return super.processTabComplete(sender, args);
    }

    private static String scopeNames() {
        return Arrays.stream(SimScope.values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
