package me.mykindos.betterpvp.core.framework.hat;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.google.inject.Inject;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import me.mykindos.betterpvp.core.Core;
import me.mykindos.betterpvp.core.listener.BPvPListener;
import me.mykindos.betterpvp.core.utilities.UtilServer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Collections;

@BPvPListener
public class HatProtocol implements Listener {

    @Inject
    private Core plugin;

    public void broadcast(Player wearer, boolean others) {
        // We broadcast player's helmet because RemapperOut maps it to the hat item after
        final org.bukkit.inventory.ItemStack vanillaItem = wearer.getInventory().getHelmet();
        ItemStack helmet;
        try {
            helmet = SpigotConversionUtil.fromBukkitItemStack(vanillaItem);
        } catch (Exception e) {
            helmet = ItemStack.EMPTY;
        }

        if (others) {
            // Others, including self
            final WrapperPlayServerEntityEquipment packet = new WrapperPlayServerEntityEquipment(
                    wearer.getEntityId(),
                    Collections.singletonList(new Equipment(EquipmentSlot.HELMET, helmet))
            );

            for (Player player : wearer.getTrackedBy()) {
                sendPacket(player, packet);
            }
        }

        // Just send to self
        wearer.updateInventory();
        final WrapperPlayServerSetSlot packet2 = new WrapperPlayServerSetSlot(0,
                0, // Allows changing player inventory
                5,
                helmet);
        sendPacket(wearer, packet2);
    }

    /**
     * Sends a packet to a player that packetevents knows about, and does nothing for one it does not.
     *
     * <p>{@code getUser} returns null for any Player that never came through a real login: a
     * balance-simulator combatant is a {@code ServerPlayer} on an embedded channel, so it equips a
     * helmet like anyone else and reaches this code, with no packetevents user behind it. The
     * five-tick delay on {@code onArmor} widens the window further -- by the time it runs, even a
     * real player may have disconnected.
     *
     * <p>Nothing is lost by skipping: this is a cosmetic re-send of a helmet slot, and its audience
     * is a client. An entity with no connection has no client to show it to.
     */
    private static void sendPacket(Player player, PacketWrapper<?> packet) {
        final User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
        if (user != null) {
            user.sendPacket(packet);
        }
    }

    @EventHandler
    public void onArmor(PlayerArmorChangeEvent event) {
        if (event.getSlotType() != PlayerArmorChangeEvent.SlotType.HEAD) {
            return; // Skip non-head
        }

        UtilServer.runTaskLater(plugin, () -> broadcast(event.getPlayer(), true), 5L);
    }

}