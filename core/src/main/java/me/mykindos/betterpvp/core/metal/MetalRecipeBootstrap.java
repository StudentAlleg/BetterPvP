package me.mykindos.betterpvp.core.metal;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import me.mykindos.betterpvp.core.item.ItemFactory;
import me.mykindos.betterpvp.core.item.impl.Blackroot;
import me.mykindos.betterpvp.core.metal.impl.CopperIngot;
import me.mykindos.betterpvp.core.metal.impl.FissureQuartz;
import me.mykindos.betterpvp.core.metal.impl.GoldIngot;
import me.mykindos.betterpvp.core.metal.impl.IronIngot;
import me.mykindos.betterpvp.core.metal.impl.Runesteel;
import me.mykindos.betterpvp.core.metal.impl.Steel;
import me.mykindos.betterpvp.core.recipe.smelting.AlloyRegistry;
import me.mykindos.betterpvp.core.recipe.smelting.SmeltingRecipeBuilder;
import me.mykindos.betterpvp.core.recipe.smelting.SmeltingRecipeRegistry;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;

@Singleton
public class MetalRecipeBootstrap {

    @Inject private ItemFactory itemFactory;
    @Inject private SmeltingRecipeRegistry recipeRegistry;
    @Inject private AlloyRegistry alloyRegistry;
    @Inject private Steel.Alloy steel;
    @Inject private Runesteel.Alloy runesteel;
    @Inject private Runesteel.Fragment runebloodOre;
    @Inject private FissureQuartz.Item fissureQuartz;
    @Inject private Blackroot blackroot;
    @Inject private IronIngot.Alloy ironAlloy;
    @Inject private CopperIngot.Alloy copperAlloy;
    @Inject private GoldIngot.Alloy goldAlloy;

    public void register() {
        // Iron
        final SmeltingRecipeBuilder ironBuilder = new SmeltingRecipeBuilder();
        ironBuilder.setPrimaryResult(ironAlloy, 1000);
        ironBuilder.addIngredient(itemFactory.getFallbackItem(Material.RAW_IRON), 1);
        recipeRegistry.registerRecipe(new NamespacedKey("core", "iron"), ironBuilder.build(itemFactory));
        alloyRegistry.registerAlloy(ironAlloy);

        // Copper
        final SmeltingRecipeBuilder copperBuilder = new SmeltingRecipeBuilder();
        copperBuilder.setPrimaryResult(copperAlloy, 1000);
        copperBuilder.addIngredient(itemFactory.getFallbackItem(Material.RAW_COPPER), 1);
        recipeRegistry.registerRecipe(new NamespacedKey("core", "copper"), copperBuilder.build(itemFactory));
        alloyRegistry.registerAlloy(copperAlloy);

        // Gold
        final SmeltingRecipeBuilder goldBuilder = new SmeltingRecipeBuilder();
        goldBuilder.setPrimaryResult(goldAlloy, 1000);
        goldBuilder.addIngredient(itemFactory.getFallbackItem(Material.RAW_GOLD), 1);
        recipeRegistry.registerRecipe(new NamespacedKey("core", "gold"), goldBuilder.build(itemFactory));
        alloyRegistry.registerAlloy(goldAlloy);

        // Steel
        final SmeltingRecipeBuilder steelBuilder = new SmeltingRecipeBuilder();
        steelBuilder.setPrimaryResult(steel, 1000);
        steelBuilder.addIngredient(itemFactory.getFallbackItem(Material.IRON_INGOT), 10);
        steelBuilder.addIngredient(itemFactory.getFallbackItem(Material.COAL_BLOCK), 2);
        recipeRegistry.registerRecipe(new NamespacedKey("core", "steel"), steelBuilder.build(itemFactory));
        alloyRegistry.registerAlloy(steel);

        // Runesteel
        final SmeltingRecipeBuilder runesteelBuilder = new SmeltingRecipeBuilder();
        runesteelBuilder.setPrimaryResult(runesteel, 500);
        runesteelBuilder.addIngredient(blackroot, 4);
        runesteelBuilder.addIngredient(runebloodOre, 1);
        runesteelBuilder.addIngredient(fissureQuartz, 2);
        recipeRegistry.registerRecipe(new NamespacedKey("core", "runesteel"), runesteelBuilder.build(itemFactory));
        alloyRegistry.registerAlloy(runesteel);
    }

}
