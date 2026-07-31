package me.mykindos.betterpvp.core.utilities.search;

import lombok.CustomLog;
import me.mykindos.betterpvp.core.client.exception.ClientNotLoadedException;
import me.mykindos.betterpvp.core.framework.simulation.SimulatedEntity;
import me.mykindos.betterpvp.core.utilities.model.manager.PlayerManager;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

@CustomLog
public class SearchEngineBase<T> {

    private final Function<@Nullable UUID, Optional<T>> onlineSearch;
    private final Function<@Nullable UUID, Optional<T>> offlineUuidSearch;
    private final Function<@Nullable String, Optional<T>> offlineNameSearch;

    public SearchEngineBase(Function<UUID, Optional<T>> onlineSearch,
                            Function<UUID, Optional<T>> offlineUuidSearch,
                            Function<String, Optional<T>> offlineNameSearch) {
        this.onlineSearch = onlineSearch;
        this.offlineUuidSearch = offlineUuidSearch;
        this.offlineNameSearch = offlineNameSearch;
    }

    /**
     * Get a loaded client by {@link UUID}.
     *
     * @param uuid The {@link UUID} of the client.
     * @return The client.
     */
    public Optional<T> online(final UUID uuid) {
        return this.onlineSearch.apply(uuid);
    }

    /**
     * Get a loaded client by {@link CommandSender} instance, who must be an instance of player.
     * This client must have already been loaded, otherwise something went wrong.
     * See {@link #online(Player)} for more information.
     *
     * @param sender The player to search for.
     * @return The client.
     */
    public T online(@NotNull final CommandSender sender) {
        return this.online((Player) sender);
    }

    /**
     * Get a loaded client by {@link Player} instance.
     * If the player does not have an associated client, they will be kicked.
     * This client must have already been loaded, otherwise something went wrong.
     *
     * @param player The player to search for.
     * @return The client.
     */
    public T online(@NotNull final Player player) {
        Optional<T> online = this.online(player.getUniqueId());

        return online.orElseThrow(() -> {
            log.warn(PlayerManager.RETRIEVE_ERROR_FORMAT_SERVER, player.getName()).submit();
            // Never kick a simulated combatant. The kick is a recovery for a real session that has
            // desynced from its stored data -- disconnect, reconnect, reload. A balance-simulator
            // combatant has no session to recover: it is a resident entity owned by a pool, and
            // kicking it destroys the arena slot mid-sweep and fans PlayerQuitEvent out to three
            // dozen listeners that were never meant to see one, each of which then fails on the
            // same missing client. That turns one stray damage event into a cascading run failure.
            //
            // The exception is still thrown, so callers cannot silently treat a missing client as a
            // present one. Callers that legitimately tolerate absence should ask for the Optional.
            if (!SimulatedEntity.isSimulated(player)) {
                player.kick(Component.text(PlayerManager.LOAD_ERROR_FORMAT_ENTITY));
            }
            return new ClientNotLoadedException(player);
        });
    }

    /**
     * Get a loaded client by name.
     *
     * @param playerName The name of the player to search for.
     * @return The client.
     */
    public Optional<T> online(@Nullable final String playerName) {
        if (playerName == null) {
            return Optional.empty();
        }

        final Player found = Bukkit.getPlayerExact(playerName);
        if (found == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(this.online(found));
    }

    /**
     * Search for a loaded client by {@link UUID}, otherwise load from database.
     *
     * @param uuid The {@link UUID} of the client to search for.
     */
    public CompletableFuture<Optional<T>> offline(@Nullable final UUID uuid) {
        final Optional<T> clientOnline = this.online(uuid);
        if (clientOnline.isPresent()) {
            return CompletableFuture.completedFuture(clientOnline);
        }
        return CompletableFuture.supplyAsync(() -> {
            return this.offlineUuidSearch.apply(uuid);
        }).exceptionally(throwable -> {
            log.error("Error searching for client by UUID", throwable).submit();
            return Optional.empty();
        });
    }

    /**
     * Search for an online client by exact name, otherwise load from database.
     *
     * @param playerName     The name of the player to search for.
     */
    public CompletableFuture<Optional<T>> offline(@Nullable final String playerName) {
        final Optional<T> clientOnline = this.online(playerName);
        if (clientOnline.isPresent()) {
            return CompletableFuture.completedFuture(clientOnline);
        }

        return CompletableFuture.supplyAsync(() -> {
            return this.offlineNameSearch.apply(playerName);
        }).exceptionally(throwable -> {
            log.error("Error searching for client by name", throwable).submit();
            return Optional.empty();
        });

    }

    /**
     * Search for an online client with advanced name search. Advanced name search will ensure name
     * completion is used, it will search for partial matches.
     * <p>
     * Example: <p></p>
     * "ReyB" finds player "ReyBot"
     * "Bot" finds player "ReyBot"
     * "a" will return 2 matches for players "James" and "Mary"
     *
     * @param playerName The name of the player to search for.
     * @return The collection of client search results.
     */
    public Collection<T> advancedOnline(@Nullable final String playerName) {
        if (playerName == null) {
            return Collections.emptyList();
        }

        final Optional<T> clientOnline = this.online(playerName);

        if (clientOnline.isPresent()) {
            return Collections.singleton(clientOnline.get());
        }

        return Bukkit.getOnlinePlayers().stream()
                .filter(player -> player.getName().toLowerCase().startsWith(playerName.toLowerCase()))
                .map(this::online)
                .toList();
    }

    /**
     * Search for an online client with advanced name search. Advanced name search will ensure name
     * completion is used, it will search for partial matches. Otherwise, load from database.
     * See {@link #advancedOnline(String)} for more information.
     * Example:
     * "ReyB" finds player "ReyBot"
     * "Bot" finds player "ReyBot"
     * "a" will return 2 matches for players "James" and "Mary"
     *
     * @param playerName     The name of the player to search for.
     */
    //public CompletableFuture<Collection<T>> advancedOffline(@Nullable final String playerName, boolean async) {
    //    return CompletableFuture.supplyAsync(() -> {
    //        final Collection<T> onlineResult = this.advancedOnline(playerName);
    //        if (!onlineResult.isEmpty()) {
    //            return onlineResult;
    //        }
//
    //        return this.offline(playerName, async).join();
    //    });
    //
    //}

}
