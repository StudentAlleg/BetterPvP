package me.mykindos.betterpvp.shops.auctionhouse.listeners;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.mykindos.betterpvp.core.chat.events.ChatSentEvent;
import me.mykindos.betterpvp.core.client.Client;
import me.mykindos.betterpvp.core.client.repository.ClientManager;
import me.mykindos.betterpvp.core.framework.updater.UpdateEvent;
import me.mykindos.betterpvp.core.listener.BPvPListener;
import me.mykindos.betterpvp.core.utilities.UtilMessage;
import me.mykindos.betterpvp.core.utilities.UtilServer;
import me.mykindos.betterpvp.shops.Shops;
import me.mykindos.betterpvp.shops.auctionhouse.Auction;
import me.mykindos.betterpvp.shops.auctionhouse.AuctionManager;
import me.mykindos.betterpvp.shops.auctionhouse.events.AuctionBuyEvent;
import me.mykindos.betterpvp.shops.auctionhouse.events.AuctionCancelEvent;
import me.mykindos.betterpvp.shops.auctionhouse.events.AuctionCreateEvent;
import me.mykindos.betterpvp.shops.auctionhouse.menu.AuctionHouseMenu;
import me.mykindos.betterpvp.shops.auctionhouse.menu.AuctionListingMenu;
import me.mykindos.betterpvp.shops.shops.shopkeepers.ShopkeeperManager;
import me.mykindos.betterpvp.shops.shops.shopkeepers.types.IShopkeeper;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Iterator;
import java.util.Optional;

@BPvPListener
@Singleton
public class AuctionListener implements Listener {

    private final ClientManager clientManager;
    private final AuctionManager auctionManager;
    private final ShopkeeperManager shopkeeperManager;

    @Inject
    public AuctionListener(ClientManager clientManager, AuctionManager auctionManager, ShopkeeperManager shopkeeperManager) {
        this.clientManager = clientManager;
        this.auctionManager = auctionManager;
        this.shopkeeperManager = shopkeeperManager;
    }

    @UpdateEvent(delay = 10000)
    public void processAuctions() {
        Iterator<Auction> auctionIterator = auctionManager.getActiveAuctions().iterator();
        while (auctionIterator.hasNext()) {
            Auction auction = auctionIterator.next();
            if (auction.isDelivered()) {
                auctionIterator.remove();
                continue;
            }
            if (auction.hasExpired() || auction.isCancelled()) {
                if (auctionManager.deliverAuction(auction.getSeller(), auction)) {
                    auctionIterator.remove();
                }
            } else if ((auction.isSold())) {
                if (auction.getTransaction() != null) {
                    if (auctionManager.deliverAuction(auction.getTransaction().getBuyer(), auction)) {
                        auctionIterator.remove();
                    }
                }
            }
        }
    }

    @EventHandler
    public void onAuctionBuy(AuctionBuyEvent event) {
        Client client = clientManager.search().online(event.getPlayer());
        if (client.getGamer().getBalance() < event.getAuction().getSellPrice()) {
            event.cancel("You do not have enough money to purchase this item.");
            return;
        }

        if (!auctionManager.getActiveAuctions().contains(event.getAuction())) {
            event.cancel("This auction no longer exists.");
        }
    }

    @EventHandler
    public void onAuctionCreate(AuctionCreateEvent event) {
        if (event.getAuction().getSellPrice() <= 0) {
            event.cancel("You must set a sell price greater than $0.");
            return;
        }

        if (event.getAuction().getSellPrice() > 1_000_000_000) {
            event.cancel("You cannot set a sell price greater than $1,000,000,000.");
            return;
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() == EquipmentSlot.OFF_HAND) return;
        if (!(event.getRightClicked() instanceof LivingEntity target)) return;


        Optional<IShopkeeper> shopkeeperOptional = shopkeeperManager.getObject(target.getUniqueId());
        shopkeeperOptional.ifPresent(shopkeeper -> {
            if (shopkeeper.getShopkeeperName().toLowerCase().contains("auction")) {
                new AuctionHouseMenu(auctionManager).show(event.getPlayer());
            }
        });
    }

    @EventHandler
    public void onCancel(AuctionCancelEvent event) {
        if (event.getAuction().isDelivered()) {
            event.cancel("Could not cancel auction as it has already been delivered.");
        }
    }

    @EventHandler
    public void onChat(ChatSentEvent event) {
        Player player = event.getPlayer();

        String message = PlainTextComponentSerializer.plainText().serialize(event.getMessage());
        if (message.startsWith("/")) return;

        if (player.hasMetadata("auction-search")) {
            event.setCancelled(true);
            UtilServer.runTask(JavaPlugin.getPlugin(Shops.class), () -> {
                player.removeMetadata("auction-search", JavaPlugin.getPlugin(Shops.class));
                new AuctionListingMenu(auctionManager, new AuctionHouseMenu(auctionManager), player,
                        auction -> PlainTextComponentSerializer.plainText().serialize(auction.getItemStack().displayName()).toLowerCase().contains(message.toLowerCase()))
                        .show(player);
            });

        }
    }

    @EventHandler
    public void onMoveCancelSearch(PlayerMoveEvent event) {
        if (event.hasChangedBlock()) {
            if (event.getPlayer().hasMetadata("auction-search")) {
                UtilMessage.simpleMessage(event.getPlayer(), "Auction House", "Search cancelled.");
                event.getPlayer().removeMetadata("auction-search", JavaPlugin.getPlugin(Shops.class));
            }
        }
    }
}
