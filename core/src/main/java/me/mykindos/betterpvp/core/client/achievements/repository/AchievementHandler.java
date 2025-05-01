package me.mykindos.betterpvp.core.client.achievements.repository;

import com.google.common.collect.ConcurrentHashMultiset;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.mykindos.betterpvp.core.client.achievements.impl.IAchievement;
import me.mykindos.betterpvp.core.database.Database;
import me.mykindos.betterpvp.core.framework.updater.UpdateEvent;
import me.mykindos.betterpvp.core.listener.BPvPListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;

@BPvPListener
@Singleton
public class AchievementHandler implements Listener {

    private final ConcurrentHashMultiset<IAchievement> pendingAchievementUpdates = ConcurrentHashMultiset.create();

    private final Database database;
    @Inject
    public AchievementHandler(Database database) {
        this.database = database;
    }

    //todo add ways to queue achievements for updating
    public void queueUpdate(IAchievement achievement) {
        //todo notify in game
        pendingAchievementUpdates.add(achievement);
    }

    @UpdateEvent
    public void processUpdates() {
        for (IAchievement achievement : pendingAchievementUpdates) {
            achievement.getType().save(achievement, database);
        }
        pendingAchievementUpdates.clear();
    }

    @EventHandler
    public void onClose(PluginDisableEvent event) {
        processUpdates();
    }
}
