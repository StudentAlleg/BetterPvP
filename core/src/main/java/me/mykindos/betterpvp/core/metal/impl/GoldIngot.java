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

public class GoldIngot {

    @Singleton
    public static class Alloy extends me.mykindos.betterpvp.core.recipe.smelting.Alloy {
        @Inject
        public Alloy(GoldIngot.Ingot ingot) {
            super("Gold Alloy", "gold", ingot, Color.fromRGB(255, 215, 0), 800f);
        }
    }

    @Singleton
    @ItemKey("core:gold_ingot")
    @FallbackItem(value = Material.GOLD_INGOT, keepRecipes = true)
    public static class Ingot extends BaseItem {
        @Inject
        public Ingot() {
            super(translatableName("core.item.gold_ingot.name"), ItemStack.of(Material.GOLD_INGOT), ItemGroup.MATERIAL, ItemRarity.COMMON);
        }
    }

}
