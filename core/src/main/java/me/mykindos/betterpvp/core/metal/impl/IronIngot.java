package me.mykindos.betterpvp.core.metal.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.mykindos.betterpvp.core.item.BaseItem;
import me.mykindos.betterpvp.core.item.FallbackItem;
import me.mykindos.betterpvp.core.item.ItemGroup;
import me.mykindos.betterpvp.core.item.ItemKey;
import me.mykindos.betterpvp.core.item.ItemRarity;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public class IronIngot {

    @Singleton
    public static class Alloy extends me.mykindos.betterpvp.core.recipe.smelting.Alloy {
        @Inject
        public Alloy(IronIngot.Ingot ingot) {
            super("Iron Alloy", "iron", ingot, Color.fromRGB(168, 168, 168), 800f);
        }
    }

    @Singleton
    @ItemKey("core:iron_ingot")
    @FallbackItem(value = Material.IRON_INGOT, keepRecipes = true)
    public static class Ingot extends BaseItem {
        @Inject
        public Ingot() {
            super(translatableName("core.item.iron_ingot.name"), ItemStack.of(Material.IRON_INGOT), ItemGroup.MATERIAL, ItemRarity.COMMON);
        }
    }

}
