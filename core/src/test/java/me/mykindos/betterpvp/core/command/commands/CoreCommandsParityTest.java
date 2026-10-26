package me.mykindos.betterpvp.core.command.commands;

import com.mojang.brigadier.tree.CommandNode;
import me.mykindos.betterpvp.core.command.brigadier.BrigadierCommand;
import me.mykindos.betterpvp.core.command.commands.admin.AdminMessageCommand;
import me.mykindos.betterpvp.core.command.commands.admin.AdminReplyCommand;
import me.mykindos.betterpvp.core.command.commands.admin.AdminVanishCommand;
import me.mykindos.betterpvp.core.command.commands.admin.BroadcastCommand;
import me.mykindos.betterpvp.core.command.commands.admin.CoreCommand;
import me.mykindos.betterpvp.core.command.commands.admin.FreezeCommand;
import me.mykindos.betterpvp.core.command.commands.admin.InvSeeCommand;
import me.mykindos.betterpvp.core.command.commands.admin.ItemInfoCommand;
import me.mykindos.betterpvp.core.command.commands.admin.KickCommand;
import me.mykindos.betterpvp.core.command.commands.admin.LogPurgeCommand;
import me.mykindos.betterpvp.core.command.commands.admin.SetSpawnCommand;
import me.mykindos.betterpvp.core.command.commands.admin.StaffChatCommand;
import me.mykindos.betterpvp.core.command.commands.admin.zone.ZoneCommand;
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
import me.mykindos.betterpvp.core.framework.display.command.DisplayCommand;
import me.mykindos.betterpvp.core.framework.display.command.DisplayRotateSubCommand;
import me.mykindos.betterpvp.core.world.schematic.command.StructureCommand;
import me.mykindos.betterpvp.core.world.site.command.SiteCommand;
import me.mykindos.betterpvp.core.world.site.command.SiteLeaveCommand;
import me.mykindos.betterpvp.core.chat.commands.SilenceChatCommand;
import me.mykindos.betterpvp.core.chat.filter.commands.ChatFilterCommand;
import me.mykindos.betterpvp.core.chat.filter.commands.ShadowFilterCommand;
import me.mykindos.betterpvp.core.combat.combatlog.commands.LogoutCommand;
import me.mykindos.betterpvp.core.resourcepack.commands.LoadPackCommand;
import me.mykindos.betterpvp.core.resourcepack.commands.UnloadPackCommand;
import me.mykindos.betterpvp.core.settings.commands.SettingsCommand;
import me.mykindos.betterpvp.core.stats.command.LeaderboardCommand;
import me.mykindos.betterpvp.core.tips.commands.SendTipCommand;
import me.mykindos.betterpvp.core.tips.commands.TipCommand;
import me.mykindos.betterpvp.core.client.achievements.commands.AchievementsCommand;
import me.mykindos.betterpvp.core.client.profile.ProfileCommand;
import me.mykindos.betterpvp.core.client.punishments.rules.commands.RuleCommand;
import me.mykindos.betterpvp.core.item.command.ItemsCommand;
import me.mykindos.betterpvp.core.item.command.MockItemCommand;
import me.mykindos.betterpvp.core.item.command.ReforgeCommand;
import me.mykindos.betterpvp.core.item.command.RefreshItemCacheCommand;
import me.mykindos.betterpvp.core.loot.command.LootCommand;
import me.mykindos.betterpvp.core.scene.command.NPCCommand;
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
                Map.entry(CoreCommand.class, new Object[]{"core", Set.of(), "ADMIN"}),
                Map.entry(ZoneCommand.class, new Object[]{"zone", Set.of(), "ADMIN"}),
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

    @Test
    @DisplayName("CLANS-44 /core keeps reload and publishmanifest")
    void coreLiterals() throws Exception {
        final Set<String> literals = instantiate(CoreCommand.class).define().build().getChildren().stream()
                .map(CommandNode::getName)
                .collect(Collectors.toSet());
        assertEquals(Set.of("reload", "publishmanifest"), literals);
    }

    @Test
    @DisplayName("CLANS-45 every migrated Core feature command keeps its legacy name, aliases and rank")
    void featureNamesAliasesAndRanks() throws Exception {
        final YamlConfiguration config = commandsConfig();
        // The legacy name, aliases and effective rank of each command
        final Map<Class<? extends BrigadierCommand>, Object[]> expected = Map.ofEntries(
                Map.entry(SiteCommand.class, new Object[]{"site", Set.of(), "ADMIN"}),
                Map.entry(SiteLeaveCommand.class, new Object[]{"leavesite", Set.of(), "PLAYER"}),
                Map.entry(StructureCommand.class, new Object[]{"structure", Set.of("struct"), "ADMIN"}),
                Map.entry(DisplayCommand.class, new Object[]{"display", Set.of(), "ADMIN"}),
                Map.entry(SilenceChatCommand.class, new Object[]{"silencechat", Set.of(), "MODERATOR"}),
                Map.entry(ChatFilterCommand.class, new Object[]{"chatfilter", Set.of(), "ADMIN"}),
                Map.entry(ShadowFilterCommand.class, new Object[]{"shadowfilter", Set.of(), "ADMIN"}),
                Map.entry(LogoutCommand.class, new Object[]{"logout", Set.of(), "PLAYER"}),
                Map.entry(SettingsCommand.class, new Object[]{"settings", Set.of("prefs"), "PLAYER"}),
                Map.entry(LeaderboardCommand.class, new Object[]{"leaderboard", Set.of("lb", "top"), "PLAYER"}),
                Map.entry(TipCommand.class, new Object[]{"tip", Set.of("tips"), "PLAYER"}),
                Map.entry(SendTipCommand.class, new Object[]{"sendtip", Set.of(), "ADMIN"}),
                Map.entry(LoadPackCommand.class, new Object[]{"loadpack", Set.of(), "ADMIN"}),
                Map.entry(UnloadPackCommand.class, new Object[]{"unloadpack", Set.of(), "ADMIN"}),
                Map.entry(ItemsCommand.class, new Object[]{"items", Set.of("recipes", "recipe"), "PLAYER"}),
                Map.entry(ReforgeCommand.class, new Object[]{"reforge", Set.of(), "ADMIN"}),
                Map.entry(RefreshItemCacheCommand.class, new Object[]{"refreshitemcache", Set.of(), "ADMIN"}),
                Map.entry(MockItemCommand.class, new Object[]{"mockitem", Set.of(), "ADMIN"}),
                Map.entry(LootCommand.class, new Object[]{"loot", Set.of("droptable", "loottable"), "PLAYER"}),
                Map.entry(NPCCommand.class, new Object[]{"npc", Set.of(), "ADMIN"}),
                Map.entry(AchievementsCommand.class, new Object[]{"achievements", Set.of(), "PLAYER"}),
                Map.entry(ProfileCommand.class, new Object[]{"profile", Set.of(), "PLAYER"}),
                Map.entry(RuleCommand.class, new Object[]{"rules", Set.of(), "PLAYER"}));

        for (Map.Entry<Class<? extends BrigadierCommand>, Object[]> entry : expected.entrySet()) {
            final BrigadierCommand command = instantiate(entry.getKey());
            final String name = (String) entry.getValue()[0];
            assertEquals(name, command.getName());
            assertEquals(entry.getValue()[1], command.getAliases(), name);
            assertTrue(config.isSet(name + ".requiredRank"), name + " has no commands.yml entry");
            assertEquals(entry.getValue()[2], config.getString(name + ".requiredRank"), name);
        }
        for (String child : new String[]{"site.list", "site.create", "site.tp", "site.delete", "structure.save",
                "structure.paste", "structure.layers", "structure.undo", "display.summon", "display.transform.rotate",
                "chatfilter.add", "chatfilter.list", "shadowfilter.remove", "shadowfilter.reload", "npc.spawn", "npc.remove", "npc.list"}) {
            assertEquals("ADMIN", config.getString(child + ".requiredRank"), child);
        }
    }

    @Test
    @DisplayName("CLANS-45 /display transform rotate keeps both rotation sides and both modes")
    void displayRotateLiterals() throws Exception {
        final CommandNode<?> rotate = instantiate(DisplayRotateSubCommand.class).define().build();
        for (String side : new String[]{"left", "right"}) {
            final Set<String> modes = rotate.getChild(side).getChildren().stream()
                    .map(CommandNode::getName)
                    .collect(Collectors.toSet());
            assertEquals(Set.of("set", "add"), modes, side);
        }
    }
}
