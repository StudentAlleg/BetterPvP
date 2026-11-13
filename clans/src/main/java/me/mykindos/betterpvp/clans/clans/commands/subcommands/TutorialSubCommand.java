package me.mykindos.betterpvp.clans.clans.commands.subcommands;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.mykindos.betterpvp.clans.clans.ClanManager;
import me.mykindos.betterpvp.clans.clans.commands.ClanCommand;
import me.mykindos.betterpvp.clans.clans.commands.ClanSubCommand;
import me.mykindos.betterpvp.clans.clans.tutorial.ClanTutorialBook;
import me.mykindos.betterpvp.core.client.Client;
import me.mykindos.betterpvp.core.client.repository.ClientManager;
import me.mykindos.betterpvp.core.command.SubCommand;
import org.bukkit.entity.Player;

@Singleton
@SubCommand(ClanCommand.class)
public class TutorialSubCommand extends ClanSubCommand {

    @Inject
    public TutorialSubCommand(ClanManager clanManager, ClientManager clientManager) {
        super(clanManager, clientManager);
    }

    @Override
    public String getName() {
        return "tutorial";
    }

    @Override
    public String getDescription() {
        return "clans.command.tutorial.description";
    }

    @Override
    public void execute(Player player, Client client, String... args) {
        player.openBook(ClanTutorialBook.localizedBook(player));
    }

    @Override
    public boolean canExecuteWithoutClan() {
        return true;
    }
}
