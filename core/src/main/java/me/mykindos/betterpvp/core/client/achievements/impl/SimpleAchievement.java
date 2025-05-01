package me.mykindos.betterpvp.core.client.achievements.impl;

import lombok.Getter;
import lombok.Setter;
import me.mykindos.betterpvp.core.client.Client;
import me.mykindos.betterpvp.core.client.achievements.types.SimpleAchievementType;

@Getter
@Setter
public class SimpleAchievement extends Achievement {
    private double progress;
    private final double goal;
    private boolean alreadyCompleted;

    public SimpleAchievement(SimpleAchievementType type, Client client, double progress, double goal) {
        super(type, client);
        this.progress = progress;
        this.goal = goal;
        alreadyCompleted = this.getCompletionPercentage() >= 1.0f;
    }

    /**
     * <p>Gets the completion percentage of this {@link IAchievement}</p>
     * <p>Ranges from {@code 0} (not completed) to {@code 1} (fully completed)</p>
     *
     * @return a {@code float} from {@code 0} to {@code 1}
     */
    @Override
    public float getCompletionPercentage() {
        return (float) (progress/goal);
    }
}
