package me.mykindos.betterpvp.core.command.brigadier.arguments.types;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import me.mykindos.betterpvp.core.command.brigadier.arguments.BPvPArgumentType;
import org.jetbrains.annotations.NotNull;

@Singleton
public class PlayerNameArgumentType extends BPvPArgumentType<String, String> implements CustomArgumentType.Converted<String, String> {
    public static final DynamicCommandExceptionType UNKNOWN_PLAYER_EXCEPTION = new DynamicCommandExceptionType((name) -> new LiteralMessage("Unknown Player " + name));
    public static final DynamicCommandExceptionType INVALID_PLAYER_NAME_EXCEPTION = new DynamicCommandExceptionType((name) -> new LiteralMessage("Invalid Playername " + name));

    @Inject
    protected PlayerNameArgumentType() {
        super("Player Name");
    }

    //TODO change to other type, and return compltable future that messages sender if client not found
    /**
     * Converts the value from the native type to the custom argument type.
     *
     * @param nativeType native argument provided value
     * @return converted value
     * @throws CommandSyntaxException if an exception occurs while parsing
     */
    @Override
    public @NotNull String convert(String nativeType) throws CommandSyntaxException {
        if (!nativeType.matches("^[a-zA-Z0-9_]{1,16}$")) {
            throw INVALID_PLAYER_NAME_EXCEPTION.create(nativeType);
        }
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
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().contains(builder.getRemainingLowerCase()))
                .forEach(builder::suggest);
        return builder.buildFuture();
    }*/
}
