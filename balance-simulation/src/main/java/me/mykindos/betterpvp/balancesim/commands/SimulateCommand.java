package me.mykindos.betterpvp.balancesim.commands;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.balancesim.SimulationGate;
import me.mykindos.betterpvp.balancesim.catalog.SimScenario;
import me.mykindos.betterpvp.balancesim.catalog.SimScope;
import me.mykindos.betterpvp.balancesim.catalog.SimSelection;
import me.mykindos.betterpvp.balancesim.engine.DuelOrchestrator;
import me.mykindos.betterpvp.balancesim.engine.SimProgress;
import me.mykindos.betterpvp.balancesim.engine.SimSummary;
import me.mykindos.betterpvp.balancesim.engine.SimSweepRequest;
import me.mykindos.betterpvp.balancesim.engine.SimulationTrigger;
import me.mykindos.betterpvp.core.client.Client;
import me.mykindos.betterpvp.core.command.Command;
import me.mykindos.betterpvp.core.command.IConsoleCommand;
import me.mykindos.betterpvp.core.framework.annotations.WithReflection;
import me.mykindos.betterpvp.core.utilities.UtilMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
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

    /**
     * Asks the run to classify each of its skills as relevant, inert or undrivable and write the
     * artifact.
     *
     * <p>A flag rather than a scope, because the audit is a reporting mode over an ordinary sweep --
     * the {@code sim_result} rows are identical either way, and the verdicts are derived from them.
     */
    private static final String AUDIT_FLAG = "--audit";

    /**
     * Continues the newest interrupted run at this configuration instead of opening a new one.
     *
     * <p>A flag rather than a separate subcommand because what it modifies is where the rows go, not
     * what is measured: the scope and scenario arguments mean exactly what they always did, and a
     * resumed sitting enumerates the same catalog. Writing it as {@code /simulate FULL --resume} also
     * keeps the scope visible at the call site, which matters because the scope is part of what
     * decides whether an interrupted run is a candidate at all.
     *
     * <p>Safe to type when there is nothing to resume: no candidate means a new run opens normally.
     */
    private static final String RESUME_FLAG = "--resume";

    /**
     * Measures only the matchups whose own config moved since a baseline run.
     *
     * <p>NEXTSTEPS item 6. {@code --resume} and this look similar and are opposites: a resume finishes
     * a sweep that was interrupted at an <em>unchanged</em> configuration, while this starts a new
     * sweep at a <em>changed</em> one and skips the parts the change did not reach. Neither can do the
     * other's job -- a resume finds no candidate once any balance value moves, which is exactly when
     * this becomes useful.
     *
     * <p>{@code --changed=<runId>} names the baseline instead of taking the newest finished run at
     * this scope.
     */
    private static final String CHANGED_FLAG = "--changed";

    /**
     * Builds the delta but does not copy the unchanged rows into the new run.
     *
     * <p>Off by default, i.e. a delta run <em>is</em> a whole run. That default is the important one:
     * a run holding only what changed is a biased sample of the sweep it claims to be, "latest run"
     * on every dashboard would silently be reading it, and nothing on the run would say so.
     */
    private static final String NO_CARRY_FLAG = "--no-carry";

    /** Value flags that narrow which permutations are swept. See {@code SimSelection}. */
    private static final String ROLES_FLAG = "--roles";
    private static final String WEAPONS_FLAG = "--weapons";
    private static final String SKILLS_FLAG = "--skills";
    private static final String RUNES_FLAG = "--runes";
    private static final String TARGETS_FLAG = "--targets";
    private static final String ARMOR_FLAG = "--armor";

    /** The flags that take a {@code =value}, offered by tab completion with the {@code =} attached. */
    private static final List<String> VALUE_FLAGS =
            List.of(ROLES_FLAG, WEAPONS_FLAG, SKILLS_FLAG, RUNES_FLAG, TARGETS_FLAG, ARMOR_FLAG);

    /** Every flag this command accepts, so an unrecognised one can be refused by name. */
    private static final List<String> FLAGS = List.of(AUDIT_FLAG, RESUME_FLAG, CHANGED_FLAG,
            NO_CARRY_FLAG, ROLES_FLAG, WEAPONS_FLAG, SKILLS_FLAG, RUNES_FLAG, TARGETS_FLAG,
            ARMOR_FLAG);

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

        // Flags are pulled out first so they can be written in any position, and an unrecognised one
        // is refused rather than ignored -- for the same reason a mistyped scope is. A silently
        // dropped "--audti" would produce a sweep that looks exactly like an audited one and writes
        // no artifact, which is a failure nobody would notice until they went looking for the file.
        final List<String> unknownFlags = new ArrayList<>();
        final List<String> positional = new ArrayList<>();
        boolean audit = false;
        boolean resume = false;
        boolean changed = false;
        boolean carry = true;
        Long resumeId = null;
        Long baselineId = null;
        String roles = null;
        String weapons = null;
        String skills = null;
        String runes = null;
        String armor = null;
        String targets = null;
        for (String arg : args) {
            final String lower = arg.toLowerCase(Locale.ROOT);
            if (!arg.startsWith("--")) {
                positional.add(arg);
            } else if (AUDIT_FLAG.equalsIgnoreCase(arg)) {
                audit = true;
            } else if (RESUME_FLAG.equalsIgnoreCase(arg)) {
                resume = true;
            } else if (lower.startsWith(RESUME_FLAG + "=")) {
                // --resume=<runId>: continue that run specifically, skipping the config_hash check.
                final String raw = arg.substring(RESUME_FLAG.length() + 1);
                try {
                    resumeId = Long.parseLong(raw.trim());
                } catch (NumberFormatException e) {
                    // Refused rather than falling back to a search: an admin who typed an id meant
                    // that run, and quietly resuming a different one is the worst possible answer.
                    UtilMessage.message(sender, "core.prefix.command",
                            "balancesim.command.simulate.badResumeId", Component.text(raw));
                    return;
                }
                resume = true;
            } else if (CHANGED_FLAG.equalsIgnoreCase(arg)) {
                changed = true;
            } else if (lower.startsWith(CHANGED_FLAG + "=")) {
                // --changed=<runId>: diff against that run rather than the newest finished one.
                final String raw = arg.substring(CHANGED_FLAG.length() + 1);
                try {
                    baselineId = Long.parseLong(raw.trim());
                } catch (NumberFormatException e) {
                    // Refused for --resume=<id>'s reason: an admin who typed a baseline meant that
                    // run, and silently diffing against a different one would carry forward rows
                    // measured under a configuration nobody chose.
                    UtilMessage.message(sender, "core.prefix.command",
                            "balancesim.command.simulate.badBaselineId", Component.text(raw));
                    return;
                }
                changed = true;
            } else if (NO_CARRY_FLAG.equalsIgnoreCase(arg)) {
                carry = false;
            } else if (lower.startsWith(ROLES_FLAG + "=")) {
                roles = arg.substring(ROLES_FLAG.length() + 1);
            } else if (lower.startsWith(WEAPONS_FLAG + "=")) {
                weapons = arg.substring(WEAPONS_FLAG.length() + 1);
            } else if (lower.startsWith(SKILLS_FLAG + "=")) {
                skills = arg.substring(SKILLS_FLAG.length() + 1);
            } else if (lower.startsWith(RUNES_FLAG + "=")) {
                runes = arg.substring(RUNES_FLAG.length() + 1);
            } else if (lower.startsWith(TARGETS_FLAG + "=")) {
                targets = arg.substring(TARGETS_FLAG.length() + 1);
            } else if (lower.startsWith(ARMOR_FLAG + "=")) {
                armor = arg.substring(ARMOR_FLAG.length() + 1);
            } else {
                unknownFlags.add(arg);
            }
        }
        if (!unknownFlags.isEmpty()) {
            UtilMessage.message(sender, "core.prefix.command", "balancesim.command.simulate.badFlag",
                    Component.text(String.join(", ", unknownFlags)),
                    Component.text(String.join(", ", FLAGS)));
            return;
        }
        args = positional.toArray(new String[0]);

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

        // An unrecognised scenario is refused for the same reason an unrecognised scope is: the two
        // measure different things, so quietly running the default would produce a finished sweep whose
        // rows answer a question nobody asked.
        final SimScenario scenario;
        if (args.length > 1) {
            final Optional<SimScenario> parsed = SimScenario.parse(args[1]);
            if (parsed.isEmpty()) {
                UtilMessage.message(sender, "core.prefix.command", "balancesim.command.simulate.badScenario",
                        Component.text(args[1]), Component.text(scenarioNames()));
                return;
            }
            scenario = parsed.get();
        } else {
            scenario = SimScenario.parse(gate.getScenario()).orElse(SimScenario.ONE_WAY);
        }

        // Built after the scope and scenario are known so a bad role name is reported alongside a
        // valid tier rather than instead of one. Role names are validated here, where the message can
        // be a chat line; the item and skill selectors cannot be validated until the catalog is
        // enumerated, because what counts as available depends on the tier.
        final SimSelection selection;
        try {
            selection = new SimSelection(
                    SimSelection.parseRoles(roles, ROLES_FLAG),
                    SimSelection.parseList(weapons),
                    SimSelection.parseList(skills),
                    SimSelection.parseList(runes),
                    SimSelection.parseRoles(targets, TARGETS_FLAG),
                    SimSelection.parseList(armor));
        } catch (IllegalArgumentException e) {
            UtilMessage.message(sender, "core.prefix.command", "balancesim.command.simulate.badSelector",
                    Component.text(e.getMessage()));
            return;
        }

        UtilMessage.message(sender, "core.prefix.command", "balancesim.command.simulate.started",
                Component.text(scope.name()), Component.text(scenario.name()));
        if (!selection.isAll()) {
            // Echoed back because a selector is easy to mistype into something that still matches --
            // "reinforced*" is a family, not a piece -- and the run's cost follows directly from it.
            UtilMessage.message(sender, "core.prefix.command", "balancesim.command.simulate.selecting",
                    Component.text(selection.canonical()));
        }
        if (changed) {
            UtilMessage.message(sender, "core.prefix.command",
                    carry ? "balancesim.command.simulate.changed"
                          : "balancesim.command.simulate.changedNoCarry",
                    Component.text(baselineId == null ? "newest finished run at this scope"
                            : "run " + baselineId));
        }
        if (resume) {
            // Said up front rather than only in the log, because whether a candidate was found decides
            // how long this sitting takes, and the answer arrives seconds later in the resume line.
            UtilMessage.message(sender, "core.prefix.command", "balancesim.command.simulate.resuming");
        }
        if (audit) {
            // Warned rather than refused. A skill audit needs skill-less baseline builds to subtract,
            // which only the tiers that enumerate skills produce -- but an admin auditing a scope that
            // cannot answer should be told why the artifact will be missing, not blocked from a sweep
            // that is otherwise perfectly valid.
            UtilMessage.message(sender, "core.prefix.command",
                    scope.isAuditable()
                            ? "balancesim.command.simulate.auditing"
                            : "balancesim.command.simulate.auditScope",
                    Component.text(scope.name()));
        }
        orchestrator.run(new SimSweepRequest(SimulationTrigger.COMMAND, scope, scenario, selection,
                                audit, resume, resumeId, changed, baselineId, carry),
                        progress -> report(sender, progress))
                .thenAccept(summary -> report(sender, summary))
                .exceptionally(ex -> {
                    log.error("Simulation run failed", ex).submit();
                    // The cause carries the useful part -- an unmatched selector names what was
                    // available -- and it is the message an admin needs to fix their command line,
                    // so it goes to chat rather than only to a log they may not be reading.
                    final Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    UtilMessage.message(sender, "core.prefix.command", "balancesim.command.simulate.failedWith",
                            Component.text(String.valueOf(cause.getMessage())));
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
     * than something an admin has to read the source to find, then the scenarios, then {@code stop}
     * while a sweep is running -- the moment you need it is the moment a long run is underway, which is
     * exactly when looking it up is least convenient.
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
        if (args.length == 2) {
            final String prefix = args[1].toUpperCase(Locale.ROOT);
            final List<String> options = Arrays.stream(SimScenario.values())
                    .map(Enum::name)
                    .filter(name -> name.startsWith(prefix))
                    .collect(Collectors.toList());
            options.addAll(matchingFlags(args[1]));
            return options;
        }
        // Offered past the positional arguments too, so a flag stays discoverable however far along
        // the line the cursor is -- the alternative is an admin having to read the source to learn the
        // audit exists.
        if (args.length > 2) {
            return matchingFlags(args[args.length - 1]);
        }
        return super.processTabComplete(sender, args);
    }

    /**
     * The flags matching what has been typed so far.
     *
     * <p>Value flags are offered with the {@code =} already attached, because a bare {@code --weapons}
     * is not a usable argument -- it parses as an unknown flag and the sweep is refused. Completing to
     * the form that actually works is the difference between the flag being discoverable and it being
     * a thing you have to read the source to use.
     *
     * <p>Anything already carrying an {@code =} completes to nothing: the value after it is a weapon
     * or skill name this command cannot enumerate without the live registries, and offering the flag
     * again would replace what has been typed.
     */
    private static List<String> matchingFlags(String prefix) {
        final String lower = prefix.toLowerCase(Locale.ROOT);
        if (lower.contains("=")) {
            return List.of();
        }
        return FLAGS.stream()
                .filter(flag -> flag.startsWith(lower))
                .map(flag -> VALUE_FLAGS.contains(flag) ? flag + "=" : flag)
                .collect(Collectors.toList());
    }

    private static String scopeNames() {
        return Arrays.stream(SimScope.values()).map(Enum::name).collect(Collectors.joining(", "));
    }

    private static String scenarioNames() {
        return Arrays.stream(SimScenario.values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
