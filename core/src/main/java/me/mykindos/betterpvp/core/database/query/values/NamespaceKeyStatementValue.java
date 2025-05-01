package me.mykindos.betterpvp.core.database.query.values;

import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.NotNull;

/**
 * A statement that represents a {@link NamespacedKey}
 */
public class NamespaceKeyStatementValue extends StringStatementValue {


    public NamespaceKeyStatementValue(@NotNull NamespacedKey namespacedKey) {
        super(namespacedKey.asString());
    }
}
