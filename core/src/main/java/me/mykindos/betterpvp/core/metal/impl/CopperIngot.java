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

public class CopperIngot {

    @Singleton
    public static class Alloy extends me.mykindos.betterpvp.core.recipe.smelting.Alloy {
        @Inject
        public Alloy(CopperIngot.Ingot ingot) {
            super("Copper Alloy", "copper", ingot, Color.fromRGB(184, 115, 51), 800f);
        }
    }

    @Singleton
    @ItemKey("core:copper_ingot")
    @FallbackItem(value = Material.COPPER_INGOT, keepRecipes = true)
    public static class Ingot extends BaseItem {
        @Inject
        public Ingot() {
            super(translatableName("core.item.copper_ingot.name"), ItemStack.of(Material.COPPER_INGOT), ItemGroup.MATERIAL, ItemRarity.COMMON);
        }
    }

}
