package me.mykindos.betterpvp.champions.champions.commands.menu;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.mykindos.betterpvp.champions.champions.roles.RoleManager;
import me.mykindos.betterpvp.core.components.champions.Role;
import me.mykindos.betterpvp.core.menu.Windowed;
import me.mykindos.betterpvp.core.utilities.model.item.ItemView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.jetbrains.annotations.NotNull;
import xyz.xenondevs.invui.gui.AbstractGui;

@Singleton
public class KitMenu extends AbstractGui implements Windowed {

    @Inject
    public KitMenu(RoleManager roleManager) {
        super(9, 4);

        int[] start = new int[]{0, 1, 2, 3, 5, 6, 7, 8};
        int count = 0;
        for (Role role : roleManager.getRoles()) {
            Component name = Component.text(role.getName(), NamedTextColor.GREEN);
            setItem(start[count], new KitButton(getItem(role.getHelmet(), name), role));
            setItem(start[count] + 9, new KitButton(getItem(role.getChestplate(), name), role));
            setItem(start[count] + 18, new KitButton(getItem(role.getLeggings(), name), role));
            setItem(start[count] + 27, new KitButton(getItem(role.getBoots(), name), role));
            count++;
        }
    }

    private static ItemView getItem(Material role, Component name) {
        return ItemView.builder().material(role).flag(ItemFlag.HIDE_ATTRIBUTES).displayName(name).build();
    }

    @NotNull
    @Override
    public Component getTitle() {
        return Component.text("Select a kit");
    }
}
