package me.mykindos.betterpvp.champions.stats.command;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import me.mykindos.betterpvp.champions.champions.roles.RoleManager;
import me.mykindos.betterpvp.champions.stats.impl.ChampionsFilter;
import me.mykindos.betterpvp.champions.stats.impl.ChampionsFilterManager;
import me.mykindos.betterpvp.champions.stats.repository.ChampionsStatsRepository;
import me.mykindos.betterpvp.core.client.Client;
import me.mykindos.betterpvp.core.client.repository.ClientManager;
import me.mykindos.betterpvp.core.combat.stats.impl.GlobalCombatStatsRepository;
import me.mykindos.betterpvp.core.combat.stats.model.CombatData;
import me.mykindos.betterpvp.core.command.Command;
import me.mykindos.betterpvp.core.utilities.UtilMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;

@Singleton
@Slf4j
public class CombatCommand extends Command {

    @Inject
    private GlobalCombatStatsRepository globalRepository;

    @Inject
    private ChampionsStatsRepository championsRepository;

    @Inject
    private RoleManager roleManager;

    @Inject
    private ClientManager clientManager;

    @Inject
    private ChampionsFilterManager championsFilterManager;

    @Override
    public String getName() {
        return "combat";
    }

    @Override
    public String getDescription() {
        return "View combat stats for a player";
    }

    @Override
    public void execute(Player player, Client client, String... args) {
        if (args.length > 2) {
            UtilMessage.message(player, "Combat", "Usage: <alt2>/combat [role] [player]");
            return;
        }

        if (args.length > 1) {
            clientManager.search(player).advancedOffline(args[1], result -> {
                run(player, result.iterator().next(), args);
            });
        } else {
            run(player, client, args);
        }
    }

    private void run(Player caster, Client target, String[] args) {
        CompletableFuture<CombatData> loaded;
        final ChampionsFilter filter;
        try {
            if (args.length == 0) {
                filter = roleManager.getObject(target.getUniqueId()).map(championsFilterManager::getFromRole).orElse(championsFilterManager.getObject("NONE").orElseThrow());
                loaded = championsRepository.getDataAsync(target.getUniqueId()).thenApply(roleStats -> roleStats.getCombatData(filter));
            } else {
                filter = championsFilterManager.getObject(args[0]).orElseThrow();
                if (championsFilterManager.isGlobal(filter)) {
                    // For some reason needs to be cast to CombatData even though it's a subtype?
                    loaded = globalRepository.getDataAsync(target.getUniqueId()).thenApply(global -> global);
                } else {
                    loaded = championsRepository.getDataAsync(target.getUniqueId()).thenApply(roleStats -> roleStats.getCombatData(filter));
                }
            }
        } catch (NoSuchElementException exception) {
            UtilMessage.message(caster, "Combat", "Invalid role.");
            return;
        }

        if (!loaded.isDone()) {
            UtilMessage.message(caster, "Combat", "Retrieving player data...");
        }

        final String targetName = target.getName();
        loaded.whenComplete((data, throwable) -> {
            if (throwable != null) {
                UtilMessage.message(caster, "Combat", "There was an error retrieving this player data.");
                log.error("There was an error retrieving player data for {}", targetName, throwable);
                return;
            }

            UtilMessage.message(caster, "Combat", "Combat data for <alt2>%s</alt2>:", targetName);
            UtilMessage.message(caster, "Combat", "Type: <alt>%s", filter.getName());
            for (Component component : data.getDescription()) {
                UtilMessage.message(caster, "Combat", component);
            }
        });
    }

    @Override
    public List<String> processTabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return championsFilterManager.getObjects().keySet().stream().toList();
        } else if (args.length == 2) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(name -> name.toLowerCase().contains(args[1].toLowerCase())).toList();
        }
        return List.of();
    }
}
