package me.mykindos.betterpvp.core.item.component;

import org.bukkit.NamespacedKey;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public abstract class AbstractItemComponent implements ItemComponent {

    /**
     * Interned keys, one per distinct component key string.
     *
     * <p>Components are constructed constantly rather than shared: every {@code ItemInstance} copies
     * each of its {@code BaseItem}'s components, and every deserializer builds a fresh component off
     * the stack. Each of those constructions built a new {@link NamespacedKey}, and that constructor
     * validates the namespace and the key character by character. An 817-second sim profile put
     * 0.87% of the entire server thread in {@code Key.checkNamespace}/{@code checkValue} reached
     * from this constructor -- revalidating the same handful of literals millions of times.
     *
     * <p>Keys are immutable and value-equal, so sharing one instance across components is
     * indistinguishable from allocating per component. The set of distinct keys is bounded by the
     * number of component classes, so this map does not grow with item or player count.
     */
    private static final Map<String, NamespacedKey> KEY_CACHE = new ConcurrentHashMap<>();

    private final NamespacedKey namespacedKey;

    protected AbstractItemComponent(String key) {
        this.namespacedKey = KEY_CACHE.computeIfAbsent(key, k -> new NamespacedKey("betterpvp", k));
    }

    @Override
    public @NotNull NamespacedKey getNamespacedKey() {
        return namespacedKey;
    }


    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;

        AbstractItemComponent that = (AbstractItemComponent) o;
        return namespacedKey.equals(that.namespacedKey);
    }

    @Override
    public int hashCode() {
        // namespacedkey has different hashCodes despite being equal, so we combine the namespace and key
        return namespacedKey.getKey().hashCode() + namespacedKey.getNamespace().hashCode();
    }
}
