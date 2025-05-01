package me.mykindos.betterpvp.core.client.achievements.types;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.NotNull;

@RequiredArgsConstructor
@Getter
public abstract class AchievementType implements IAchievementType {
    @NotNull
    protected final NamespacedKey namespacedKey;
}
