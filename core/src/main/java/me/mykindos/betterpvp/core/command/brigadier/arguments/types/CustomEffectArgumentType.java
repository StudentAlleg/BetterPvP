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
import io.papermc.paper.command.brigadier.argument.CustomArgumentType;
import me.mykindos.betterpvp.core.command.brigadier.arguments.BPvPArgumentType;
import me.mykindos.betterpvp.core.effects.EffectType;
import me.mykindos.betterpvp.core.effects.EffectTypes;

import java.util.concurrent.CompletableFuture;

@Singleton
public class CustomEffectArgumentType extends BPvPArgumentType<EffectType, String> implements CustomArgumentType.Converted<EffectType, String> {
    public static final DynamicCommandExceptionType UNKNOWN_EFFECT_EXCEPTION = new DynamicCommandExceptionType((name) -> new LiteralMessage("Unknown Effect " + name));

    @Inject
    protected CustomEffectArgumentType() {
        super("Custom Effect");
    }

    /**
     * Converts the value from the native type to the custom argument type.
     *
     * @param nativeType native argument provided value
     * @return converted value
     * @throws CommandSyntaxException if an exception occurs while parsing
     */
    @Override
    public EffectType convert(String nativeType) throws CommandSyntaxException {
        return EffectTypes.getEffectTypeByName(nativeType).orElseThrow(() -> UNKNOWN_EFFECT_EXCEPTION.create(nativeType));
    }

    /**
     * Gets the native type that this argument uses,
     * the type that is sent to the client.
     *
     * @return native argument type
     */
    @Override
    public ArgumentType<String> getNativeType() {
        return StringArgumentType.word();
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        EffectTypes.getEffectTypes().stream()
                .map(EffectType::getName)
                .filter(name -> name.toLowerCase().contains(builder.getRemainingLowerCase()))
                .forEach(builder::suggest);
        return builder.buildFuture();
    }
}
