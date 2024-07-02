package me.mykindos.betterpvp.core.wiki.menus.buttons;

import lombok.Setter;
import me.mykindos.betterpvp.core.client.Client;
import me.mykindos.betterpvp.core.menu.Windowed;
import me.mykindos.betterpvp.core.utilities.UtilServer;
import me.mykindos.betterpvp.core.utilities.model.item.ItemView;
import me.mykindos.betterpvp.core.wiki.menus.CategoryWikiMenu;
import me.mykindos.betterpvp.core.wiki.menus.event.WikiFetchEvent;
import me.mykindos.betterpvp.core.wiki.types.IStaticWikiable;
import me.mykindos.betterpvp.core.wiki.types.WikiCategory;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.jetbrains.annotations.NotNull;
import xyz.xenondevs.invui.item.Item;
import xyz.xenondevs.invui.item.ItemProvider;
import xyz.xenondevs.invui.item.impl.AbstractItem;

import java.util.ArrayList;
import java.util.List;

public class WikiCategoryButton extends AbstractItem {
    private final WikiCategory category;
    private final Player player;
    private final Client client;
    @Setter
    private Windowed parent;

    /**
     *
     * @param category the category this button represents

     */
    public WikiCategoryButton(Player player, Client client, WikiCategory category, Windowed parent) {
        this.category = category;
        this.player = player;
        this.client = client;
        this.parent = parent;
    }
    @Override
    public ItemProvider getItemProvider() {
        ItemView.ItemViewBuilder itemViewBuilder = ItemView.builder().material(category.getMaterial()).customModelData(category.getModelData())
                .displayName(MiniMessage.miniMessage().deserialize(category.getTitle()))
                .frameLore(true);
        for (String line : category.getDescription()) {
            itemViewBuilder.lore(MiniMessage.miniMessage().deserialize(line));
        }
        return itemViewBuilder.build();

    }

    public void handleClick(@NotNull ClickType clickType, @NotNull Player player, @NotNull InventoryClickEvent event) {
        //find all the relevant buttons
        final WikiFetchEvent wikiFetchEvent = new WikiFetchEvent(category, player, client);
        UtilServer.callEvent(event);

        List<Item> items = new ArrayList<>();
        wikiFetchEvent.getWikiables().forEach(iWikiable -> {
            if (iWikiable instanceof IStaticWikiable staticWikiable) {
                items.add(new StaticWikiDescriptionButton())
            }
        });
        new CategoryWikiMenu()
        //Not used
    }
}
