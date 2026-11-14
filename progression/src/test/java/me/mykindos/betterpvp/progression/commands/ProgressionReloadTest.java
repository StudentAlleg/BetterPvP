package me.mykindos.betterpvp.progression.commands;

import com.mojang.brigadier.context.CommandContext;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import me.mykindos.betterpvp.core.utilities.UtilMessage;
import me.mykindos.betterpvp.progression.commands.loader.BrigadierProgressionCommandLoader;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@DisplayName("/progression reload")
class ProgressionReloadTest {

    @Test
    @DisplayName("CLANS-57 AC1 reloads the Brigadier commands, then resends each online player's command tree")
    @SuppressWarnings("unchecked")
    void reloadsBrigadierCommandsAndResendsTrees() throws Exception {
        final Constructor<?> constructor = ProgressionCommand.class.getDeclaredConstructors()[0];
        final Object[] args = new Object[constructor.getParameterCount()];
        for (int i = 0; i < args.length; i++) {
            args[i] = mock(constructor.getParameterTypes()[i], Answers.RETURNS_DEEP_STUBS);
        }
        final ProgressionCommand command = (ProgressionCommand) constructor.newInstance(args);
        final BrigadierProgressionCommandLoader loader = argOf(args, BrigadierProgressionCommandLoader.class);

        final Player first = mock(Player.class);
        final Player second = mock(Player.class);
        final CommandSourceStack source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(mock(CommandSender.class));
        final CommandContext<CommandSourceStack> context = mock(CommandContext.class);
        when(context.getSource()).thenReturn(source);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<UtilMessage> ignored = mockStatic(UtilMessage.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(first, second));
            command.define().build().getChild("reload").getCommand().run(context);
        }

        final InOrder order = inOrder(loader, first, second);
        order.verify(loader).reload();
        order.verify(first).updateCommands();
        order.verify(second).updateCommands();
    }

    private static <T> T argOf(Object[] args, Class<T> type) {
        for (Object arg : args) {
            if (type.isInstance(arg)) {
                return type.cast(arg);
            }
        }
        throw new AssertionError("No " + type.getSimpleName() + " constructor argument");
    }
}
