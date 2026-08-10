package me.mykindos.betterpvp.balancesim.catalog;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which weapon tiers the sweep refuses to measure.
 *
 * <p>Worth a test despite being a set membership check, because the thing it is easy to get wrong is
 * not the mechanism but the mapping. The registry names the stone tier {@code crude_*} and the
 * wooden tier {@code rustic_*}, so a reader asked to drop "basic and rustic" weapons has to resolve
 * two names that suggest the wrong materials -- and an off-by-one-tier mistake would quietly delete
 * iron from the sweep and keep stone, which no aggregate would reveal.
 */
class SimEquipmentWeaponTierTest {

    @Test
    @DisplayName("the wooden tier is excluded, whatever the registry calls it")
    void woodenIsExcluded() {
        assertTrue(SimEquipment.isExcludedTier(Material.WOODEN_SWORD));
        assertTrue(SimEquipment.isExcludedTier(Material.WOODEN_AXE));
    }

    @Test
    @DisplayName("the stone tier is excluded, whatever the registry calls it")
    void stoneIsExcluded() {
        assertTrue(SimEquipment.isExcludedTier(Material.STONE_SWORD));
        assertTrue(SimEquipment.isExcludedTier(Material.STONE_AXE));
    }

    /**
     * The tier immediately above the cut. Named explicitly because it is the failure that would cost
     * a sweep: iron is the default weapon every phase 1 row was measured with, and excluding it
     * would leave the baseline without the item the rest of the run is compared against.
     */
    @Test
    @DisplayName("iron, the default weapon's tier, survives the cut")
    void ironIsKept() {
        assertFalse(SimEquipment.isExcludedTier(Material.IRON_SWORD));
        assertFalse(SimEquipment.isExcludedTier(Material.IRON_AXE));
    }

    @Test
    @DisplayName("every tier above stone is kept")
    void higherTiersAreKept() {
        assertFalse(SimEquipment.isExcludedTier(Material.DIAMOND_SWORD));
        assertFalse(SimEquipment.isExcludedTier(Material.DIAMOND_AXE));
        assertFalse(SimEquipment.isExcludedTier(Material.NETHERITE_SWORD));
        assertFalse(SimEquipment.isExcludedTier(Material.NETHERITE_AXE));
    }

    /**
     * Gold is the booster tier -- {@code booster_sword} is a {@code GOLDEN_SWORD} -- and its 32
     * durability makes it look like the flimsiest thing in the registry. It is not a low tier, it is
     * a different axis, and the sweep measures it.
     */
    @Test
    @DisplayName("gold is a booster, not a low tier, and is kept")
    void goldIsKept() {
        assertFalse(SimEquipment.isExcludedTier(Material.GOLDEN_SWORD));
        assertFalse(SimEquipment.isExcludedTier(Material.GOLDEN_AXE));
    }
}
