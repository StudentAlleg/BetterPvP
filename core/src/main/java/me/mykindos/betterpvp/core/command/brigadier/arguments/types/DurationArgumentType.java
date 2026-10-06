package me.mykindos.betterpvp.core.command.brigadier.arguments.types;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import me.mykindos.betterpvp.core.command.brigadier.arguments.BPvPArgumentType;
import org.jetbrains.annotations.NotNull;

/**
 * A length of time typed as a number and a unit, such as {@code 30m} or {@code 2d}, or {@code perm}. Converts to
 * milliseconds, with {@code perm} as -1. Offers no suggestions, so the client shows the argument name as a hint.
 */
@Singleton
public class DurationArgumentType extends BPvPArgumentType<Long, String> implements CustomArgumentType.Converted<@NotNull Long, @NotNull String> {

    @Inject
    protected DurationArgumentType() {
        super("Duration");
    }

    @Override
    public @NotNull Long convert(@NotNull String nativeType) throws CommandSyntaxException {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public @NotNull ArgumentType<String> getNativeType() {
        return StringArgumentType.word();
    }
}
