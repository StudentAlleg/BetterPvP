package me.mykindos.betterpvp.core.client.achievements;

import java.util.HashSet;
import java.util.Set;
import lombok.CustomLog;
import me.mykindos.betterpvp.core.Core;
import me.mykindos.betterpvp.core.client.achievements.test.MobKillsAchievement;
import me.mykindos.betterpvp.core.client.achievements.types.IAchievementType;
import me.mykindos.betterpvp.core.framework.BPvPPlugin;

@CustomLog
public class AchievementTypes {
    private final static Set<IAchievementType> achievementTypes = new HashSet<>();

    public static MobKillsAchievement MOB_KILLS = (MobKillsAchievement) createAchievementType(BPvPPlugin.getPlugin(Core.class), MobKillsAchievement.class);

    public static IAchievementType createAchievementType(BPvPPlugin plugin, Class<? extends IAchievementType> clazz) {
        IAchievementType achievementType = plugin.getInjector().getInstance(clazz);
        plugin.getInjector().injectMembers(achievementType);
        log.info("Added AchievementType: {}", achievementType.getNamespacedKey().asString()).submit();
        achievementTypes.add(achievementType);
        return achievementType;
    }

}
