package me.mykindos.betterpvp.core.client.achievements.test;

import com.google.inject.Singleton;
import java.util.List;
import me.mykindos.betterpvp.core.Core;
import me.mykindos.betterpvp.core.client.achievements.impl.IAchievement;
import me.mykindos.betterpvp.core.client.achievements.impl.SimpleAchievement;
import me.mykindos.betterpvp.core.client.achievements.types.SimpleAchievementType;
import me.mykindos.betterpvp.core.utilities.UtilMessage;
import me.mykindos.betterpvp.core.utilities.model.description.Description;
import me.mykindos.betterpvp.core.utilities.model.item.ItemView;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

@Singleton
public class MobKillsAchievement extends SimpleAchievementType {
    public MobKillsAchievement() {
        super(new NamespacedKey(JavaPlugin.getPlugin(Core.class), "mob_kills"), 10);
    }

    /**
     * Get the {@link Description} of an {@link IAchievement}
     *
     * @param achievement the {@link IAchievement} to describe
     * @return the {@link Description} of the {@link IAchievement}
     */
    @Override
    public Description getDescription(IAchievement achievement) {
        SimpleAchievement simpleAchievement = (SimpleAchievement) achievement;
        List<Component> lore = List.of(UtilMessage.deserialize("<green>%d</green>/<yellow>%d</yellow>", simpleAchievement.getProgress(), simpleAchievement.getGoal()));



        ItemView itemView = ItemView.builder()
                                .displayName(UtilMessage.deserialize("<dark_purple>Kill <yellow>%d</yellow> Mobs</dark_purple>", goal))
                                .material(Material.ZOMBIE_SPAWN_EGG)
                                .build();



        return null;
    }
}
