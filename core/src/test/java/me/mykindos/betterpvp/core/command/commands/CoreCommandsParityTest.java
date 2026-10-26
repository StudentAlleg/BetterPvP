package me.mykindos.betterpvp.core.command.commands;

import com.mojang.brigadier.tree.CommandNode;
import me.mykindos.betterpvp.core.command.brigadier.BrigadierCommand;
import me.mykindos.betterpvp.core.command.commands.admin.AdminMessageCommand;
import me.mykindos.betterpvp.core.command.commands.admin.AdminReplyCommand;
import me.mykindos.betterpvp.core.command.commands.admin.AdminVanishCommand;
import me.mykindos.betterpvp.core.command.commands.admin.BroadcastCommand;
import me.mykindos.betterpvp.core.command.commands.admin.FreezeCommand;
import me.mykindos.betterpvp.core.command.commands.admin.InvSeeCommand;
import me.mykindos.betterpvp.core.command.commands.admin.ItemInfoCommand;
import me.mykindos.betterpvp.core.command.commands.admin.KickCommand;
import me.mykindos.betterpvp.core.command.commands.admin.LogPurgeCommand;
import me.mykindos.betterpvp.core.command.commands.admin.SetSpawnCommand;
import me.mykindos.betterpvp.core.command.commands.admin.StaffChatCommand;
import me.mykindos.betterpvp.core.command.commands.general.DailyCommand;
import me.mykindos.betterpvp.core.command.commands.general.DiscordCommand;
import me.mykindos.betterpvp.core.command.commands.general.IgnoreCommand;
import me.mykindos.betterpvp.core.command.commands.general.KillCommand;
import me.mykindos.betterpvp.core.command.commands.general.ListCommand;
import me.mykindos.betterpvp.core.command.commands.general.MessageCommand;
import me.mykindos.betterpvp.core.command.commands.general.PingCommand;
import me.mykindos.betterpvp.core.command.commands.general.PlayTimeCommand;
import me.mykindos.betterpvp.core.command.commands.general.PlayerAdminCommand;
import me.mykindos.betterpvp.core.command.commands.general.ReplyCommand;
import me.mykindos.betterpvp.core.command.commands.general.ShowEffectsCommand;
import me.mykindos.betterpvp.core.command.commands.general.ShowItemCommand;
import me.mykindos.betterpvp.core.command.commands.general.SpawnCommand;
import me.mykindos.betterpvp.core.command.commands.qol.CenterSelfCommand;
import me.mykindos.betterpvp.core.command.commands.qol.ClearCommand;
import me.mykindos.betterpvp.core.command.commands.qol.GamemodeCommand;
import me.mykindos.betterpvp.core.command.commands.qol.HealCommand;
import me.mykindos.betterpvp.core.command.commands.qol.SetViewDistanceCommand;
import me.mykindos.betterpvp.core.command.commands.qol.TeleportCommand;
import me.mykindos.betterpvp.core.command.commands.qol.TeleportHereCommand;
import me.mykindos.betterpvp.core.command.commands.qol.TeleportPositionCommand;
import me.mykindos.betterpvp.core.command.commands.qol.TeleportWorldCommand;
import me.mykindos.betterpvp.core.command.commands.qol.WorldCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

@DisplayName("Core commands on Brigadier")
class CoreCommandsParityTest {

    private static BrigadierCommand instantiate(Class<? extends BrigadierCommand> type) throws ReflectiveOperationException {
        final Constructor<?> constructor = type.getDeclaredConstructors()[0];
        final Object[] args = new Object[constructor.getParameterCount()];
        for (int i = 0; i < args.length; i++) {
            args[i] = mock(constructor.getParameterTypes()[i], Answers.RETURNS_DEEP_STUBS);
        }
        return (BrigadierCommand) constructor.newInstance(args);
    }

    private static YamlConfiguration commandsConfig() throws Exception {
        try (InputStream stream = CoreCommandsParityTest.class.getResourceAsStream("/configs/permissions/commands.yml")) {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("CLANS-44 every migrated Core command keeps its legacy name, aliases and rank")
    void namesAliasesAndRanks() throws Exception {
        final YamlConfiguration config = commandsConfig();
        // The legacy name, aliases and effective rank of each command
        final Map<Class<? extends BrigadierCommand>, Object[]> expected = Map.ofEntries(
                Map.entry(DailyCommand.class, new Object[]{"daily", Set.of(), "PLAYER"}),
                Map.entry(DiscordCommand.class, new Object[]{"discord", Set.of(), "PLAYER"}),
                Map.entry(IgnoreCommand.class, new Object[]{"ignore", Set.of(), "PLAYER"}),
                Map.entry(KillCommand.class, new Object[]{"kill", Set.of(), "PLAYER"}),
                Map.entry(ListCommand.class, new Object[]{"list", Set.of(), "ADMIN"}),
                Map.entry(MessageCommand.class, new Object[]{"message", Set.of("m", "msg", "tell", "whisper", "w"), "PLAYER"}),
                Map.entry(PingCommand.class, new Object[]{"ping", Set.of(), "PLAYER"}),
                Map.entry(PlayTimeCommand.class, new Object[]{"playtime", Set.of("timeplayed"), "PLAYER"}),
                Map.entry(PlayerAdminCommand.class, new Object[]{"admin", Set.of("a"), "PLAYER"}),
                Map.entry(ReplyCommand.class, new Object[]{"reply", Set.of("r"), "PLAYER"}),
                Map.entry(ShowEffectsCommand.class, new Object[]{"showeffects", Set.of(), "PLAYER"}),
                Map.entry(ShowItemCommand.class, new Object[]{"showitem", Set.of(), "PLAYER"}),
                Map.entry(SpawnCommand.class, new Object[]{"spawn", Set.of(), "ADMIN"}),
                Map.entry(CenterSelfCommand.class, new Object[]{"centerself", Set.of(), "ADMIN"}),
                Map.entry(ClearCommand.class, new Object[]{"clear", Set.of(), "ADMIN"}),
                Map.entry(GamemodeCommand.class, new Object[]{"gamemode", Set.of("gm"), "ADMIN"}),
                Map.entry(HealCommand.class, new Object[]{"heal", Set.of(), "ADMIN"}),
                Map.entry(SetViewDistanceCommand.class, new Object[]{"setviewdistance", Set.of(), "ADMIN"}),
                Map.entry(TeleportCommand.class, new Object[]{"teleport", Set.of("tp"), "ADMIN"}),
                Map.entry(TeleportHereCommand.class, new Object[]{"teleporthere", Set.of("tphere"), "ADMIN"}),
                Map.entry(TeleportPositionCommand.class, new Object[]{"teleportposition", Set.of("tppos"), "ADMIN"}),
                Map.entry(TeleportWorldCommand.class, new Object[]{"teleportworld", Set.of("tpworld", "tpw"), "ADMIN"}),
                Map.entry(WorldCommand.class, new Object[]{"world", Set.of(), "ADMIN"}),
                Map.entry(AdminMessageCommand.class, new Object[]{"messageadmin", Set.of("ma"), "HELPER"}),
                Map.entry(AdminReplyCommand.class, new Object[]{"replyadmin", Set.of("ra"), "HELPER"}),
                Map.entry(AdminVanishCommand.class, new Object[]{"vanish", Set.of("v"), "ADMIN"}),
                Map.entry(BroadcastCommand.class, new Object[]{"broadcast", Set.of("bc"), "ADMIN"}),
                Map.entry(FreezeCommand.class, new Object[]{"freeze", Set.of("unfreeze"), "ADMIN"}),
                Map.entry(InvSeeCommand.class, new Object[]{"invsee", Set.of("openinv"), "ADMIN"}),
                Map.entry(ItemInfoCommand.class, new Object[]{"iteminfo", Set.of(), "ADMIN"}),
                Map.entry(KickCommand.class, new Object[]{"kick", Set.of(), "ADMIN"}),
                Map.entry(LogPurgeCommand.class, new Object[]{"logpurge", Set.of(), "DEVELOPER"}),
                Map.entry(SetSpawnCommand.class, new Object[]{"setspawn", Set.of(), "ADMIN"}),
                Map.entry(StaffChatCommand.class, new Object[]{"staffchat", Set.of("sc"), "TRIAL_MOD"}));

        for (Map.Entry<Class<? extends BrigadierCommand>, Object[]> entry : expected.entrySet()) {
            final BrigadierCommand command = instantiate(entry.getKey());
            final String name = (String) entry.getValue()[0];
            assertEquals(name, command.getName());
            assertEquals(entry.getValue()[1], command.getAliases(), name);
            assertTrue(config.isSet(name + ".requiredRank"), name + " has no commands.yml entry");
            assertEquals(entry.getValue()[2], config.getString(name + ".requiredRank"), name);
        }
    }

    @Test
    @DisplayName("CLANS-44 /gamemode keeps every legacy mode name and alias")
    void gamemodeLiterals() throws Exception {
        final Set<String> literals = instantiate(GamemodeCommand.class).define().build().getChildren().stream()
                .map(CommandNode::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("survival", "s", "0", "creative", "c", "1", "adventure", "a", "2", "spectator", "sp", "3"), literals);
    }
}
