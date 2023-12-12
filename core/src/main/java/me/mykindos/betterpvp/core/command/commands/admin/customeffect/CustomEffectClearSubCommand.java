package me.mykindos.betterpvp.core.command.commands.admin.customeffect;

import com.google.inject.Singleton;
import me.mykindos.betterpvp.core.client.Client;
import me.mykindos.betterpvp.core.client.Rank;
import me.mykindos.betterpvp.core.command.SubCommand;
import me.mykindos.betterpvp.core.effects.EffectType;
import me.mykindos.betterpvp.core.utilities.UtilMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

@Singleton
@SubCommand(CustomEffectCommand.class)
public class CustomEffectClearSubCommand extends CustomEffectSubCommand {
    @Override
    public String getName() {
        return "clear";
    }

    @Override
    public String getDescription() {
        return "Clear effects from a player";
    }

    @Override
    public void execute(Player player, Client client, String... args) {
        if (args.length < 1) {
            UtilMessage.simpleMessage(player, "<yellow>Usage:</yellow> /customeffect clear <player> [effect]");
            return;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            UtilMessage.message(player, "Core", UtilMessage.deserialize("<yellow>%s</yellow> is not a valid target (does not exist)", args[0]));
            return;
        }

        if (args.length < 2) {
            effectManager.removeAllEffects(target);
            Component message = UtilMessage.deserialize("<yellow>%s</yellow> removed all effects from <yellow>%s</yellow>", player.getName(), target.getName());
            clientManager.sendMessageToRank("Effect", message, Rank.HELPER);
            return;
        }
        EffectType effect;
        try {
            effect = EffectType.valueOf(args[1].toUpperCase());
        } catch (IllegalArgumentException e) {
            UtilMessage.message(player, "Core", UtilMessage.deserialize("<yellow>%s</yellow> is not a valid effect", args[1]));
            return;
        }

        effectManager.removeEffect(target, effect);
        Component message = UtilMessage.deserialize("<yellow>%s</yellow> removed <white>%s</white> from <yellow>%s</yellow>", player.getName(), effect.name(), target.getName());
        clientManager.sendMessageToRank("Effect", message, Rank.HELPER);
    }

    @Override
    public String getArgumentType(int arg) {
        if (arg == 1) {
            return ArgumentType.PLAYER.name();
        }
        if (arg == 2) {
            return "EFFECT";
        }
        return ArgumentType.NONE.name();
    }
}