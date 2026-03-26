package me.mykindos.betterpvp.core.resourcepack;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.mykindos.betterpvp.core.Core;
import me.mykindos.betterpvp.core.client.events.ClientJoinEvent;
import me.mykindos.betterpvp.core.config.Config;
import me.mykindos.betterpvp.core.framework.updater.UpdateEvent;
import me.mykindos.betterpvp.core.listener.BPvPListener;
import me.mykindos.betterpvp.core.localization.keys.CoreTranslationKeys;
import me.mykindos.betterpvp.core.utilities.UtilMessage;
import me.mykindos.betterpvp.core.utilities.UtilServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.util.Ticks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

@Singleton
@BPvPListener
public class ResourcePackListener implements Listener {

    private final ResourcePackHandler resourcePackHandler;

    private static final Title.Times TIME = Title.Times.times(Ticks.duration(0), Ticks.duration(10), Ticks.duration(0));
    @Inject
    @Config(path = "core.resourcepack.enabled", defaultValue = "true")
    private boolean enabled;


    @Inject
    public ResourcePackListener(ResourcePackHandler resourcePackHandler) {
        this.resourcePackHandler = resourcePackHandler;
    }

    @EventHandler
    public void onClientLogin(ClientJoinEvent event) {
        if (!enabled) return;
        UtilServer.runTaskAsync(JavaPlugin.getPlugin(Core.class), () -> {
            Player player = event.getPlayer();
            ResourcePack mainPack = resourcePackHandler.getResourcePack("main").join();
            if (mainPack == null) return;

            Component message = UtilMessage.translate(player, CoreTranslationKeys.RESOURCE_PACK_REQUIRED_MESSAGE);
            player.setResourcePack(mainPack.getUuid(), mainPack.getUrl(), mainPack.getHashBytes(), message, true);
        });


    }

    @EventHandler
    public void onTexturepackStatus(PlayerResourcePackStatusEvent event) {
        if (!enabled) return;

        ResourcePack mainPack = resourcePackHandler.getResourcePack("main").join();
        if (mainPack == null) return;

        if (event.getID().equals(mainPack.getUuid())) {
            if (event.getStatus() == PlayerResourcePackStatusEvent.Status.DECLINED) {
                event.getPlayer().kick(UtilMessage.translate(event.getPlayer(), CoreTranslationKeys.RESOURCE_PACK_KICK_DECLINED));
            } else if (event.getStatus() == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {
                event.getPlayer().kick(UtilMessage.translate(event.getPlayer(), CoreTranslationKeys.RESOURCE_PACK_KICK_FAILED_DOWNLOAD));
            }
        }

    }

    @EventHandler
    public void onMoveWhileLoading(PlayerMoveEvent event) {
        if (!enabled) return;
        if (event.getPlayer().getResourcePackStatus() != PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
            event.setCancelled(true);
        }
    }

    @UpdateEvent(delay = 300)
    public void sendResourcePackTitle() {
        if (!enabled) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getResourcePackStatus() != PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
                player.showTitle(Title.title(
                        UtilMessage.translate(player, CoreTranslationKeys.RESOURCE_PACK_TITLE),
                        UtilMessage.translate(player, CoreTranslationKeys.RESOURCE_PACK_SUBTITLE),
                        TIME));
            }
        }
    }
}
