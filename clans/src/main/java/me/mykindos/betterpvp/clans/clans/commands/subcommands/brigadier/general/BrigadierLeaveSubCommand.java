package me.mykindos.betterpvp.clans.clans.commands.subcommands.brigadier.general;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import me.mykindos.betterpvp.clans.clans.Clan;
import me.mykindos.betterpvp.clans.clans.ClanManager;
import me.mykindos.betterpvp.clans.clans.commands.BrigadierClansCommand;
import me.mykindos.betterpvp.clans.clans.commands.subcommands.brigadier.BrigadierClanSubCommand;
import me.mykindos.betterpvp.clans.clans.events.MemberLeaveClanEvent;
import me.mykindos.betterpvp.clans.commands.arguments.exceptions.ClanArgumentException;
import me.mykindos.betterpvp.core.client.repository.ClientManager;
import me.mykindos.betterpvp.core.command.brigadier.BrigadierSubCommand;
import me.mykindos.betterpvp.core.components.clans.data.ClanMember;
import me.mykindos.betterpvp.core.menu.impl.ConfirmationMenu;
import me.mykindos.betterpvp.core.utilities.UtilServer;
import org.bukkit.entity.Player;

import java.util.Optional;

@Singleton
@BrigadierSubCommand(BrigadierClansCommand.class)
public class BrigadierLeaveSubCommand extends BrigadierClanSubCommand {

    @Inject
    protected BrigadierLeaveSubCommand(ClientManager clientManager, ClanManager clanManager) {
        super(clientManager, clanManager);
    }

    @Override
    protected ClanMember.MemberRank requiredMemberRank() {
        return ClanMember.MemberRank.RECRUIT;
    }

    /**
     * Used in retrieving path for config options
     *
     * @return the name of this command
     */
    @Override
    public String getName() {
        return "leave";
    }

    /**
     * Gets the description of this command, used in registration
     *
     * @return the description
     */
    @Override
    public String getDescription() {
        return "Leaves your current clan";
    }

    /**
     * Define the command, using normal rank based permissions
     * Requires sender to have required rank and executor to be a player
     *
     * @return the builder to be used in Build
     */
    @Override
    public LiteralArgumentBuilder<CommandSourceStack> define() {
        return Commands.literal(getName())
                .executes(context -> {
                    final Player executor = getPlayerFromExecutor(context);
                    final Clan executorClan = getClanByExecutor(context);

                    final Optional<ClanMember> leaderOptional = executorClan.getLeader();
                    //skip leader check if sender is administrating, there is no leader, or this is an admin clan
                    if (!senderIsAdministrating(context.getSource())
                            && leaderOptional.isPresent()
                            && !executorClan.isAdmin()) {
                        final ClanMember leader = leaderOptional.get();
                        if (leader.equals(executorClan.getMember(executor.getUniqueId()))) {
                            throw ClanArgumentException.LEADER_CANNOT_LEAVE.create();
                        }
                    }

                    final Optional<Clan> locationClanOptional = this.clanManager.getClanByLocation(executor.getLocation());
                    if (locationClanOptional.isPresent()) {
                        final Clan locationClan = locationClanOptional.get();
                        if (executorClan.isEnemy(locationClan)) {
                            throw ClanArgumentException.CANNOT_LEAVE_IN_ENEMY_TERRITORY.create();
                        }
                    }

                    //TODO attribute and show sender instead of executor
                    new ConfirmationMenu("Are you sure you want to leave your clan?", success -> {
                        if (success) {
                            UtilServer.callEvent(new MemberLeaveClanEvent(executor, executorClan));
                        }
                    }).show(executor);
                    return Command.SINGLE_SUCCESS;
                });
    }

    @Override
    public boolean requirement(CommandSourceStack source) {
        boolean passesPrevious = super.requirement(source);
        if (!passesPrevious) return false;

        //allow administrating clients to run command
        if (senderIsAdministrating(source)) return true;

        if (!(source.getSender() instanceof final Player player)) return false;

        //prevent normal clan leaders from leaving their clans
        final Optional<Clan> clanOptional = clanManager.getClanByPlayer(player);
        if (clanOptional.isEmpty()) return false;
        final Clan clan = clanOptional.get();

        return !clan.getMember(player.getUniqueId()).hasRank(ClanMember.MemberRank.LEADER);
    }
}
