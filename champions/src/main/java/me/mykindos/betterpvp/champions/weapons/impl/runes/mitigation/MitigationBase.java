package me.mykindos.betterpvp.champions.weapons.impl.runes.mitigation;

import me.mykindos.betterpvp.champions.Champions;
import me.mykindos.betterpvp.champions.weapons.impl.runes.Rune;
import me.mykindos.betterpvp.champions.weapons.impl.runes.RuneNamespacedKeys;
import me.mykindos.betterpvp.champions.weapons.impl.runes.SingleStatRune;
import me.mykindos.betterpvp.core.utilities.UtilMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;


public abstract class MitigationBase extends SingleStatRune {

    protected MitigationBase(Champions plugin, String key) {
        super(plugin, key);
    }

    @Override
    public List<Component> getRuneLoreDescription(ItemMeta itemMeta) {
        double roll = getRollFromMeta(itemMeta);
        return List.of(UtilMessage.deserialize("<gray>Decreases negative effect duration by <green>%.1f%%", roll));
    }

    @Override
    public List<Component> getItemLoreDescription(PersistentDataContainer pdc, ItemStack itemStack) {
        int tier = pdc.getOrDefault(RuneNamespacedKeys.TIER, PersistentDataType.INTEGER, 0);
        double roll = pdc.getOrDefault(getAppliedNamespacedKey(), PersistentDataType.DOUBLE, 0.0);
        return List.of(UtilMessage.deserialize("%s <gray>- Decreases negative effect duration by <green>%.1f%%", getStarPrefix(tier),  roll));
    }

    @Override
    public String[] getItemFilter() {
        return Rune.ARMOUR_FILTER;
    }

    @Override
    public String getCategory() {
        return "armour";
    }

    @Override
    public NamespacedKey getAppliedNamespacedKey() {
        return RuneNamespacedKeys.MITIGATION;
    }



}
