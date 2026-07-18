package me.mykindos.betterpvp.core.metal.casting;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.mykindos.betterpvp.core.Core;
import me.mykindos.betterpvp.core.item.ItemFactory;
import me.mykindos.betterpvp.core.metal.impl.CopperIngot;
import me.mykindos.betterpvp.core.metal.impl.GoldIngot;
import me.mykindos.betterpvp.core.metal.impl.IronIngot;
import me.mykindos.betterpvp.core.metal.impl.Runesteel;
import me.mykindos.betterpvp.core.metal.impl.Steel;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

@Singleton
public class CastingMoldBootstrap {

    @Inject private ItemFactory itemFactory;
    @Inject private CastingMoldRecipeRegistry recipeRegistry;
    @Inject private Steel.Ingot steelIngot;
    @Inject private Steel.Alloy steelAlloy;
    @Inject private Runesteel.Ingot runesteelIngot;
    @Inject private Runesteel.Alloy runesteelAlloy;
    @Inject private IronIngot.Ingot ironIngot;
    @Inject private IronIngot.Alloy ironAlloy;
    @Inject private CopperIngot.Ingot copperIngot;
    @Inject private CopperIngot.Alloy copperAlloy;
    @Inject private GoldIngot.Ingot goldIngot;
    @Inject private GoldIngot.Alloy goldAlloy;
    @Inject private IngotCastingMold ingotBase;

    private NamespacedKey key(String name) {
        return new NamespacedKey(JavaPlugin.getPlugin(Core.class), name);
    }

    public void register() {
        recipeRegistry.registerRecipe(key("iron"), new CastingMoldRecipe(ingotBase, 1000, ironAlloy, ironIngot, itemFactory));
        recipeRegistry.registerRecipe(key("copper"), new CastingMoldRecipe(ingotBase, 1000, copperAlloy, copperIngot, itemFactory));
        recipeRegistry.registerRecipe(key("gold"), new CastingMoldRecipe(ingotBase, 1000, goldAlloy, goldIngot, itemFactory));
        recipeRegistry.registerRecipe(key("steel"), new CastingMoldRecipe(ingotBase, 1000, steelAlloy, steelIngot, itemFactory));
        recipeRegistry.registerRecipe(key("runesteel"), new CastingMoldRecipe(ingotBase, 1000, runesteelAlloy, runesteelIngot, itemFactory));
    }

}
