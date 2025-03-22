package me.mykindos.betterpvp.core.command.brigadier.arguments.types;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import lombok.CustomLog;
import me.mykindos.betterpvp.core.command.brigadier.arguments.BPvPArgumentType;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

@CustomLog
@Singleton
public class PlayerNameArgumentType extends BPvPArgumentType<String, String> implements CustomArgumentType.Converted<String, String> {
    public static final DynamicCommandExceptionType UNKNOWN_PLAYER_EXCEPTION = new DynamicCommandExceptionType((name) -> new LiteralMessage("Unknown Player " + name));
    public static final DynamicCommandExceptionType INVALID_PLAYER_NAME_EXCEPTION = new DynamicCommandExceptionType((name) -> new LiteralMessage("Invalid Playername " + name));

    @Inject
    protected PlayerNameArgumentType() {
        super("Player Name");
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


    /*@Override
    public <S> @NotNull CompletableFuture<Suggestions> listSuggestions(@NotNull CommandContext<S> context, SuggestionsBuilder builder) {
        Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().contains(builder.getRemainingLowerCase()))
                .forEach(name -> {
                    log.info(name).submit();
                    builder.suggest(name);
                });
        return builder.buildFuture();
    }*/
}
