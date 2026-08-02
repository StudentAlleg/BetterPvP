package me.mykindos.betterpvp.core.client.stats.listeners;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.core.client.repository.ClientManager;
import me.mykindos.betterpvp.core.client.stats.StatContainer;
import me.mykindos.betterpvp.core.client.stats.impl.core.MinecraftStat;
import me.mykindos.betterpvp.core.framework.simulation.SimulatedEntity;
import me.mykindos.betterpvp.core.listener.BPvPListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerStatisticIncrementEvent;

@BPvPListener
@Singleton
@CustomLog
public class MinecraftStatListener implements Listener {
    private final ClientManager clientManager;
    @Inject
    public MinecraftStatListener(ClientManager clientManager) {
        this.clientManager = clientManager;
    }


    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMinecraftStat(final PlayerStatisticIncrementEvent event) {
        // Vanilla awards DEATHS, MOB_KILLS and PLAYER_KILLS from inside ServerPlayer.die, so a
        // simulated death raises this three times per duel -- for both sides, since the killer is
        // credited too. Nothing here is worth recording for a fake player (SimClient's container
        // discards it), and the lookup throws once the ephemeral client is gone.
        if (SimulatedEntity.isSimulated(event.getPlayer())) {
            return;
        }

        final StatContainer container = clientManager.search().online(event.getPlayer()).getStatContainer();
        final int delta = event.getNewValue() - event.getPreviousValue();

        final MinecraftStat minecraftStat = MinecraftStat.fromEvent(event);

        container.incrementStat(minecraftStat, delta);
    }

}
