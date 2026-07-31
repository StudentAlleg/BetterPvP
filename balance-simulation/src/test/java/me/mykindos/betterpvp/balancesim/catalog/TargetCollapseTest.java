package me.mykindos.betterpvp.balancesim.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The target reduction is a claim that two permutations are the <em>same</em> measurement, not two
 * similar ones -- so the conditions it rests on are worth failing a test over rather than discovering
 * from rows that turned out not to be interchangeable after all.
 *
 * <p>Every case here is one of the three premises in
 * {@code BalanceCatalog.collapseByDurability}, plus the alias bookkeeping that keeps the reduction
 * legible from the data.
 */
class TargetCollapseTest {

    @Test
    @DisplayName("unarmoured skill-less roles of equal health collapse onto one target")
    void equalHealthCollapses() {
        final List<SimTargetSpec> collapsed = BalanceCatalog.collapseByDurability(
                List.of(bare("ASSASSIN", 40), bare("KNIGHT", 40), bare("MAGE", 30)),
                SimScenario.ONE_WAY);

        assertEquals(2, collapsed.size(), "two roles at 40 health are one measurement");
        assertEquals(List.of("ASSASSIN", "KNIGHT"), collapsed.get(0).roleAliases());
        assertTrue(collapsed.get(0).isCollapsed());
        assertEquals(List.of("MAGE"), collapsed.get(1).roleAliases());
        assertFalse(collapsed.get(1).isCollapsed(), "a role sharing health with nothing stands alone");
    }

    @Test
    @DisplayName("a MUTUAL defender fights back, so its role is not reducible to a health total")
    void mutualKeepsEveryTarget() {
        final List<SimTargetSpec> targets = List.of(bare("ASSASSIN", 40), bare("KNIGHT", 40));
        final List<SimTargetSpec> collapsed =
                BalanceCatalog.collapseByDurability(targets, SimScenario.MUTUAL);

        assertEquals(targets, collapsed, "nothing may be folded when the defender acts");
    }

    @Test
    @DisplayName("armoured targets are never folded, even at identical health")
    void armouredNeverCollapses() {
        // durability() sums only StatTypes.HEALTH, so two armour sets agreeing on health can still
        // differ in every other stat their pieces carry -- equal HP would not mean equal duel.
        final List<SimTargetSpec> targets = List.of(
                new SimTargetSpec("ASSASSIN", SimEquipment.ROLE_ARMOR, 60, List.of(), 0),
                new SimTargetSpec("KNIGHT", SimEquipment.ROLE_ARMOR, 60, List.of(), 0));

        assertEquals(targets, BalanceCatalog.collapseByDurability(targets, SimScenario.ONE_WAY));
    }

    @Test
    @DisplayName("a defender carrying skills is never folded, even bare and at identical health")
    void skilledDefenderNeverCollapses() {
        // Guards the axis the design plans rather than one that exists: a defender's DefensiveSkill
        // passives fire on being hit, so its build is not reducible to a health total either.
        final SimTargetSpec skilled = new SimTargetSpec("KNIGHT", SimEquipment.NO_ARMOR, 40,
                List.of(new SimSkillAllocation("Bulwark", "PASSIVE_A", 1, 1)), 1);
        final List<SimTargetSpec> targets = List.of(bare("ASSASSIN", 40), skilled);

        assertEquals(targets, BalanceCatalog.collapseByDurability(targets, SimScenario.ONE_WAY));
    }

    @Test
    @DisplayName("the surviving target keeps its own role first in the alias list")
    void survivorLeadsItsOwnAliases() {
        // The row still describes the duel that was actually fought, so target_role has to stay the
        // role that was measured -- the aliases say who else it stands for, not who it became.
        final List<SimTargetSpec> collapsed = BalanceCatalog.collapseByDurability(
                List.of(bare("MAGE", 30), bare("RANGER", 30)), SimScenario.ONE_WAY);

        assertEquals(1, collapsed.size());
        assertEquals("MAGE", collapsed.get(0).role());
        assertEquals("MAGE", collapsed.get(0).roleAliases().get(0));
    }

    private static SimTargetSpec bare(String role, double hp) {
        return new SimTargetSpec(role, SimEquipment.NO_ARMOR, hp, List.of(), 0);
    }
}
