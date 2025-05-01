package me.mykindos.betterpvp.core.client.achievements.test;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.mykindos.betterpvp.core.client.Client;
import me.mykindos.betterpvp.core.client.achievements.AchievementTypes;
import me.mykindos.betterpvp.core.client.achievements.impl.SimpleAchievement;
import me.mykindos.betterpvp.core.client.achievements.repository.AchievementHandler;
import me.mykindos.betterpvp.core.client.repository.ClientManager;
import me.mykindos.betterpvp.core.combat.death.events.CustomDeathEvent;
import me.mykindos.betterpvp.core.listener.BPvPListener;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

@Singleton
@BPvPListener
public class MobKillsListener implements Listener {
    private final ClientManager clientManager;
    private final AchievementHandler achievementHandler;

    @Inject
    public MobKillsListener(ClientManager clientManager, AchievementHandler achievementHandler) {
        this.clientManager = clientManager;
        this.achievementHandler = achievementHandler;
    }

    @EventHandler
    public void onKill(CustomDeathEvent event) {
        final LivingEntity killed = event.getKilled();
        if (killed instanceof Player) return;
        if (!(event.getKiller() instanceof Player player)) return;

        final Client client = clientManager.search().online(player);
        SimpleAchievement achievement = AchievementTypes.MOB_KILLS.cast(client.getAchievement(AchievementTypes.MOB_KILLS));
        achievement.setProgress(achievement.getProgress() + 1);
        achievementHandler.queueUpdate(achievement);
        //todo queue this for update
    }
}
