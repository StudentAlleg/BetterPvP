package me.mykindos.betterpvp.core.client.achievements.types;

import java.sql.SQLException;
import javax.sql.rowset.CachedRowSet;
import lombok.CustomLog;
import me.mykindos.betterpvp.core.client.Client;
import me.mykindos.betterpvp.core.client.achievements.impl.IAchievement;
import me.mykindos.betterpvp.core.client.achievements.impl.SimpleAchievement;
import me.mykindos.betterpvp.core.database.Database;
import me.mykindos.betterpvp.core.database.query.Statement;
import me.mykindos.betterpvp.core.database.query.values.NamespaceKeyStatementValue;
import me.mykindos.betterpvp.core.database.query.values.UuidStatementValue;
import me.mykindos.betterpvp.core.utilities.UtilMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

/**
 * Represents a simple achievement, one that is completed when reaching a desired goal, with
 * non unique counting method (i.e. kill 10 mobs would be simple, kill 10 different mobs would not be)
 */
@CustomLog
public abstract class SimpleAchievementType extends AchievementType {
    protected final double goal;

    public SimpleAchievementType(NamespacedKey namespacedKey, double goal) {
        super(namespacedKey);
        this.goal = goal;
    }

    @Override
    public SimpleAchievement cast(IAchievement achievement) {
        return (SimpleAchievement) achievement;
    }

    @Override
    public void notify(Player player, IAchievement achievement) {
        SimpleAchievement simpleAchievement = (SimpleAchievement) achievement;
        if (!simpleAchievement.isAlreadyCompleted() && simpleAchievement.getCompletionPercentage() >= 1.0f) {
            handleCompletion(player, simpleAchievement);
        } else if (!simpleAchievement.isAlreadyCompleted()) {
            sendProgressMessage(player, simpleAchievement);
        }
    }

    //todo java docs (for allow override)
    public void sendProgressMessage(Player player, SimpleAchievement achievement) {
        Component component = UtilMessage.deserialize("Progress: <green>%d</green>% ").append(getDescription(achievement).getIcon().get().displayName())
                .append(getDescription(achievement).getIcon().get().displayName());
        UtilMessage.message(player, "Achievement", component);
    }

    public void handleCompletion(Player player, SimpleAchievement achievement) {
        Component component = UtilMessage.deserialize("<gold>Congratulations!</gold> You have achieved: ")
                .append(getDescription(achievement).getIcon().get().displayName());
        UtilMessage.message(player, "Achievement", component);
        achievement.setAlreadyCompleted(true);
    }

    /**
     * Load the {@link IAchievement} from the {@link Database}
     *
     * @param client   the {@link Client} this {@link IAchievement} is for
     * @param database the {@link Database} this {@link IAchievement} is loaded from
     * @return the loaded {@link IAchievement}
     */
    @Override
    public SimpleAchievement load(Client client, Database database) {
        String query = "SELECT Progress FROM SimpleAchievements WHERE Client = ? AND Identifier = ?";
        Statement statement = new Statement(query,
                    new UuidStatementValue(client.getUniqueId()),
                    new NamespaceKeyStatementValue(getNamespacedKey())
                );
        try (CachedRowSet results = database.executeQuery(statement)) {
            if (results.first()) {
                double progress = results.getDouble(1);
                return new SimpleAchievement(this, client, progress, this.goal);
            }
        } catch (SQLException exception) {
            log.error("Error loading simple achievement {} for {}: ", getNamespacedKey().asString(), client.getName(), exception).submit();
        }
        //todo new player
        return new SimpleAchievement(this, client, 0, this.goal);
    }

    /**
     * Saves this {@link IAchievement} to the {@link Database}
     *
     * @param achievement the {@link IAchievement}
     * @param database    the {@link Database} to save the {@link IAchievement} to
     */
    @Override
    public void save(IAchievement achievement, Database database) {
        SimpleAchievement simpleAchievement = (SimpleAchievement) achievement;
        //todo
    }
}
