package me.mykindos.betterpvp.balancesim.commands;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.mykindos.betterpvp.balancesim.BalanceSimulation;
import me.mykindos.betterpvp.balancesim.SimulationGate;
import me.mykindos.betterpvp.balancesim.listeners.BalanceSimulationListenerLoader;
import me.mykindos.betterpvp.core.client.Client;
import me.mykindos.betterpvp.core.client.Rank;
import me.mykindos.betterpvp.core.command.Command;
import me.mykindos.betterpvp.core.command.IConsoleCommand;
import me.mykindos.betterpvp.core.command.SubCommand;
import me.mykindos.betterpvp.core.framework.annotations.WithReflection;
import me.mykindos.betterpvp.core.utilities.UtilMessage;
import me.mykindos.betterpvp.core.utilities.model.Reloadable;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Parent for the module's admin commands, matching the {@code /champions}, {@code /clans} and
 * {@code /progression} pattern: the parent does nothing on its own and exists so subcommands have
 * somewhere to hang.
 *
 * <p>The sweep itself stays on its own top-level {@code /simulate} command rather than moving under
 * here, because it is the one thing a dev runs constantly.
 */
@Singleton
@WithReflection
public class BalanceSimCommand extends Command implements IConsoleCommand {

    public BalanceSimCommand() {
        aliases.add("bsim");
    }

    @Override
    public String getName() {
        return "balancesim";
    }

    @Override
    public String getDescription() {
        return "balancesim.command.balancesim.description";
    }

    @Override
    public void execute(Player player, Client client, String... args) {
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
    }

    @Override
    public boolean informInsufficientRank() {
        return true;
    }

    @Override
    public Rank getRequiredRank() {
        return Rank.ADMIN;
    }

    @Override
    public String getArgumentType(int arg) {
        return arg == 1 ? ArgumentType.SUBCOMMAND.name() : ArgumentType.NONE.name();
    }

    /**
     * Re-reads the module config and re-injects it.
     *
     * <p>Reloading matters more here than in most modules: every knob the simulator is
     * parameterised by lives in {@link SimulationGate} as an injected {@code @Config} field, so
     * until {@code plugin.reload()} walks the bindings and re-injects them, an edited config.yml
     * has no effect at all -- including {@code enabled}, which is how simulation gets switched on
     * for a dev session without a restart.
     */
    @Singleton
    @SubCommand(BalanceSimCommand.class)
    @WithReflection
    private static class ReloadCommand extends Command implements IConsoleCommand {

        @Inject
        private BalanceSimulation plugin;

        @Inject
        private BalanceSimulationCommandLoader commandLoader;

        @Inject
        private BalanceSimulationListenerLoader listenerLoader;

        @Override
        public String getName() {
            return "reload";
        }

        @Override
        public String getDescription() {
            return "balancesim.command.reload.description";
        }

        @Override
        public void execute(Player player, Client client, String... args) {
            execute(player, args);
        }

        @Override
        public void execute(CommandSender sender, String[] args) {
            plugin.reload();
            plugin.getReloadables().forEach(Reloadable::reload);
            commandLoader.reload(plugin.getClass().getPackageName());

            UtilMessage.message(sender, "core.prefix.command", "balancesim.command.reload.success");
        }
    }
}
