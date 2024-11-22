package me.mykindos.betterpvp.champions.weapons.impl;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.champions.Champions;
import me.mykindos.betterpvp.core.combat.weapon.Weapon;
import me.mykindos.betterpvp.core.combat.weapon.types.CooldownWeapon;
import me.mykindos.betterpvp.core.combat.weapon.types.InteractWeapon;
import me.mykindos.betterpvp.core.effects.EffectManager;
import me.mykindos.betterpvp.core.effects.EffectTypes;
import me.mykindos.betterpvp.core.effects.events.EffectClearEvent;
import me.mykindos.betterpvp.core.items.BPvPItem;
import me.mykindos.betterpvp.core.utilities.UtilInventory;
import me.mykindos.betterpvp.core.utilities.UtilMessage;
import me.mykindos.betterpvp.core.utilities.UtilServer;
import me.mykindos.betterpvp.core.utilities.UtilSound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.recipe.CraftingBookCategory;

import java.util.ArrayList;
import java.util.List;

@Singleton
@CustomLog
public class PurificationPotion extends Weapon implements InteractWeapon, CooldownWeapon {

    private final EffectManager effectManager;
    private double duration;

    @Inject
    public PurificationPotion(Champions champions, EffectManager effectManager) {
        super(champions, "purification_potion");
        this.effectManager = effectManager;
    }

    @Override
    public void loadWeapon(BPvPItem item) {
        super.loadWeapon(item);
        ShapelessRecipe shapelessRecipe = getShapelessRecipe(1, "shapeless",
                ItemStack.of(Material.ENCHANTED_GOLDEN_APPLE),
                ItemStack.of(Material.ENCHANTED_GOLDEN_APPLE),
                ItemStack.of(Material.ENCHANTED_GOLDEN_APPLE),
                ItemStack.of(Material.ENCHANTED_GOLDEN_APPLE),
                ItemStack.of(Material.GLASS_BOTTLE),
                ItemStack.of(Material.ENCHANTED_GOLDEN_APPLE),
                ItemStack.of(Material.ENCHANTED_GOLDEN_APPLE),
                ItemStack.of(Material.ENCHANTED_GOLDEN_APPLE),
                ItemStack.of(Material.ENCHANTED_GOLDEN_APPLE)
        );
        shapelessRecipe.setCategory(CraftingBookCategory.MISC);
        Bukkit.removeRecipe(shapelessRecipe.getKey());
        Bukkit.addRecipe(shapelessRecipe);
        getRecipeKeys().add(shapelessRecipe.getKey());
        ShapedRecipe shapedRecipe = getShapedRecipe("*G*", "GBG", "*G*");
        shapedRecipe.setIngredient('G', Material.GOLDEN_APPLE);
        shapedRecipe.setIngredient('B', Material.GLASS_BOTTLE);
        Bukkit.removeRecipe(shapedRecipe.getKey());
        Bukkit.addRecipe(shapedRecipe);
        getRecipeKeys().add(shapedRecipe.getKey());
        log.warn(shapedRecipe.getResult().toString()).submit();
    }

    @Override
    public void activate(Player player) {
        UtilMessage.message(player, "Item",
                Component.text("You consumed a ", NamedTextColor.GRAY).append(getName().color(NamedTextColor.YELLOW)));
        UtilSound.playSound(player, Sound.ENTITY_GENERIC_DRINK, 1f, 1f, false);
        UtilSound.playSound(player.getWorld(), player.getLocation(), Sound.ENTITY_GENERIC_DRINK, 0.8f, 1.2f);
        UtilInventory.remove(player, getMaterial(), 1);

        this.effectManager.addEffect(player, EffectTypes.IMMUNE, (long) (duration * 1000L));

        player.setFireTicks(0);
        UtilServer.callEvent(new EffectClearEvent(player));
    }

    @Override
    public List<Component> getLore(ItemMeta itemMeta) {
        List<Component> lore = new ArrayList<>();
        lore.add(UtilMessage.deserialize("<gray>Cleanses negative effects"));
        return lore;
    }

    @Override
    public boolean canUse(Player player) {
        return isHoldingWeapon(player);
    }

    @Override
    public double getCooldown() {
        return cooldown;
    }

    @Override
    public void loadWeaponConfig() {
        duration = getConfig("duration", 1.5, Double.class);
    }

    @Override
    public boolean showCooldownOnItem() {
        return true;
    }
}