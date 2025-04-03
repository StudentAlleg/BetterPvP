package me.mykindos.betterpvp.core.command.brigadier.arguments.types;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import lombok.CustomLog;
import me.mykindos.betterpvp.core.client.Rank;
import me.mykindos.betterpvp.core.client.repository.ClientManager;
import me.mykindos.betterpvp.core.command.brigadier.arguments.BPvPArgumentType;
import me.mykindos.betterpvp.core.effects.EffectManager;
import me.mykindos.betterpvp.core.effects.EffectTypes;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

@CustomLog
@Singleton
public class PlayerNameArgumentType extends BPvPArgumentType<String, String> implements CustomArgumentType.Converted<String, String> {
    public static final DynamicCommandExceptionType UNKNOWN_PLAYER_EXCEPTION = new DynamicCommandExceptionType((name) -> new LiteralMessage("Unknown Player " + name));
    public static final DynamicCommandExceptionType INVALID_PLAYER_NAME_EXCEPTION = new DynamicCommandExceptionType((name) -> new LiteralMessage("Invalid Playername " + name));

    private final  EffectManager effectManager;
    private final ClientManager clientManager;
    @Inject
    protected PlayerNameArgumentType(EffectManager effectManager, ClientManager clientManager) {
        super("Player Name");
        this.effectManager = effectManager;
        this.clientManager = clientManager;
    }

    /**
     * Validates a name to be a valid {@link Player#getName()} with the regex
     * {@code ^[a-zA-Z0-9_]{1,16}$}
     * @param name the name to validate
     * @throws CommandSyntaxException if this is not a valid {@link Player} name
     */
    public static void validatePlayerName(String name) throws CommandSyntaxException {
        if (!name.matches("^[a-zA-Z0-9_]{1,16}$")) {
            throw INVALID_PLAYER_NAME_EXCEPTION.create(name);
        }
    }

    /**
     * Converts the value from the native type to the custom argument type.
     *
     * @param nativeType native argument provided value
     * @return converted value
     * @throws CommandSyntaxException if an exception occurs while parsing
     */
    @Override
    public @NotNull String convert(@NotNull String nativeType) throws CommandSyntaxException {
        validatePlayerName(nativeType);
        return nativeType;
    }

    /**
     * Gets the native type that this argument uses,
     * the type that is sent to the client.
     *
     * @return native argument type
     */
    @Override
    public @NotNull ArgumentType<String> getNativeType() {
        return StringArgumentType.word();
    }


    //This needs to be a static and manually added due to causing unknown conflicting problems with /c info and/or clan argument type
    public <S> @NotNull CompletableFuture<Suggestions> suggestions(@NotNull CommandContext<S> context, SuggestionsBuilder builder) {
        if (!(context.getSource() instanceof final CommandSourceStack sourceStack))
            return Suggestions.empty();

        final @Nullable Player executor = Bukkit.getPlayer(Objects.requireNonNull(sourceStack.getExecutor()).getUniqueId());
        Bukkit.getOnlinePlayers().stream()
                .filter(target -> executor == null ||
                        !clientManager.search().online(executor).hasRank(Rank.HELPER) ||
                        !effectManager.hasEffect(target, EffectTypes.VANISH, "commandVanish"))
                .map(Player::getName)
                .filter(name -> name.toLowerCase().contains(builder.getRemainingLowerCase()))
                .forEach(name -> {
                    log.info(name).submit();
                    builder.suggest(name);
                });
        return builder.buildFuture();
    }
}
