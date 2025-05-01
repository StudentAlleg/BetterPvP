package me.mykindos.betterpvp.core.client.achievements.impl;

import me.mykindos.betterpvp.core.client.Client;
import me.mykindos.betterpvp.core.client.achievements.types.IAchievementType;

/**
 * Represents an achievement linked to a {@link Client}
 */
public interface IAchievement {
    /**
     * Get the type of this achievement
     * @return the {@link IAchievementType}
     */
    IAchievementType getType();

    /**
     * Gets the {@link Client} {@link IAchievement this} is for
     * @return the {@link Client}
     */
    Client getClient();

    /**
     * <p>Gets the completion percentage of this {@link IAchievement}</p>
     * <p>Ranges from {@code 0} (not completed) to {@code 1} (fully completed)</p>
     *
     * @return a {@code float} from {@code 0} to {@code 1}
     */
    float getCompletionPercentage();


}
