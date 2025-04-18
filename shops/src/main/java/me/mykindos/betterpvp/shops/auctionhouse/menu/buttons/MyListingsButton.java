package me.mykindos.betterpvp.shops.auctionhouse.menu.buttons;

import me.mykindos.betterpvp.core.inventory.item.ItemProvider;
import me.mykindos.betterpvp.core.inventory.item.impl.controlitem.ControlItem;
import me.mykindos.betterpvp.core.utilities.model.item.ItemView;
import me.mykindos.betterpvp.shops.auctionhouse.AuctionManager;
import me.mykindos.betterpvp.shops.auctionhouse.menu.AuctionHouseMenu;
import me.mykindos.betterpvp.shops.auctionhouse.menu.AuctionListingMenu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemFlag;
import org.jetbrains.annotations.NotNull;

public class MyListingsButton extends ControlItem<AuctionHouseMenu> {

    private final AuctionManager auctionManager;

    public MyListingsButton(AuctionManager auctionManager) {
        this.auctionManager = auctionManager;
    }

    @Override
    public ItemProvider getItemProvider(AuctionHouseMenu gui) {
        return ItemView.builder().material(Material.PLAYER_HEAD)
                .flag(ItemFlag.HIDE_ATTRIBUTES)
                .flag(ItemFlag.HIDE_ADDITIONAL_TOOLTIP)
                .displayName(Component.text("View my Listings", NamedTextColor.GREEN))
                .lore(Component.text("Click to view all of your listed auctions", NamedTextColor.GRAY))
                .build();
    }

    @Override
    public void handleClick(@NotNull ClickType clickType, @NotNull Player player, @NotNull InventoryClickEvent event) {
        new AuctionListingMenu(auctionManager, new AuctionHouseMenu(auctionManager), player,
                auction -> auction.getSeller().equals(player.getUniqueId())).show(player);
    }
}
