package me.mykindos.betterpvp.core.client.achievements.types;

import me.mykindos.betterpvp.core.client.Client;
import me.mykindos.betterpvp.core.client.achievements.impl.IAchievement;
import me.mykindos.betterpvp.core.database.Database;
import me.mykindos.betterpvp.core.utilities.model.description.Description;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

/**
 * Represents a type of achievement
 */
public interface IAchievementType {
    /**
     * Get the {@link NamespacedKey} for this achievement
     * @return the {@link NamespacedKey}
     */
    NamespacedKey getNamespacedKey();

    /**
     * Get the {@link Description} of an {@link IAchievement}
     * @param achievement the {@link IAchievement} to describe
     * @return the {@link Description} of the {@link IAchievement}
     */
    Description getDescription(IAchievement achievement);

    /**
     * Notify the player that something has changed with this achievement
     * @param player the {@link Player} that changed something about their achievment
     * @param achievement the modified {@link IAchievement}
     */
    void notify(Player player, IAchievement achievement);

    /**
     * Casts the {@link IAchievement to the best subtype}
     * @param achievement the {@link IAchievement}
     * @return the casted achievement
     */
    IAchievement cast(IAchievement achievement);

    /**
     * Load the {@link IAchievement} from the {@link Database}
     * @param client the {@link Client} this {@link IAchievement} is for
     * @param database the {@link Database} this {@link IAchievement} is loaded from
     * @return the loaded {@link IAchievement}
     */
    IAchievement load(Client client, Database database);

    /**
     * Saves this {@link IAchievement} to the {@link Database}
     * @param achievement the {@link IAchievement}
     * @param database the {@link Database} to save the {@link IAchievement} to
     */
    void save(IAchievement achievement, Database database);
}
