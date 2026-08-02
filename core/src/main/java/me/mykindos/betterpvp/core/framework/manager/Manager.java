package me.mykindos.betterpvp.core.framework.manager;

import lombok.CustomLog;
import lombok.Getter;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A keyed store of objects, keyed by {@code T}.
 *
 * <p>This class used to carry a {@code getObject(UUID)} overload that did
 * {@code objects.get(identifier.toString())}, as a convenience for the many subclasses keyed by a
 * UUID's string form. It was removed: it silently assumed {@code T} was {@link String}, so on a
 * subclass keyed by anything else it compiled fine, bound in preference to {@code getObject(T)} as
 * the more specific overload, and then returned empty for every lookup. Subclasses that want to be
 * looked up by UUID should declare {@code T} as {@code UUID}; the rest convert at the call site,
 * where the cost is visible.
 */
@CustomLog
public abstract class Manager<T, R> {

    @Getter
    protected final Map<T, R> objects = new ConcurrentHashMap<>();

    public void addObject(T identifier, R object){
        objects.put(identifier, object);
    }

    public Optional<R> getObject(T identifier){
        return Optional.ofNullable(objects.get(identifier));
    }

    public void removeObject(T identifier) {
        objects.remove(identifier);
    }

}
