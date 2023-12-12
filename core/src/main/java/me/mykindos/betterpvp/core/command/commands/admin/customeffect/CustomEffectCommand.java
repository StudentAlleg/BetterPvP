package me.mykindos.betterpvp.core.command.commands.admin.customeffect;

import com.google.inject.Singleton;
import me.mykindos.betterpvp.core.client.Client;
import me.mykindos.betterpvp.core.client.Rank;
import me.mykindos.betterpvp.core.command.Command;
import me.mykindos.betterpvp.core.command.SubCommand;
import me.mykindos.betterpvp.core.effects.EffectType;
import me.mykindos.betterpvp.core.utilities.UtilMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

@Singleton
public class CustomEffectCommand extends Command {

    @Singleton
    @Override
    public String getName() {
        return "customeffect";
    }

    @Override
    public String getDescription() {
        return "Apply/remove a custom effect to a player";
    }

    @Override
    public void execute(Player player, Client client, String... args) {
        UtilMessage.simpleMessage(player, "<yellow>Usage:</yellow> /customeffect <give|clear>");
    }

    @Override
    public String getArgumentType(int arg) {
        if (arg == 1) {
            return ArgumentType.SUBCOMMAND.name();
        }
        return ArgumentType.NONE.name();
    }
}
