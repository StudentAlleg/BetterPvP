package me.mykindos.betterpvp.balancesim.catalog;

import me.mykindos.betterpvp.core.item.component.impl.stat.ItemStat;
import me.mykindos.betterpvp.core.item.component.impl.stat.StatContainerComponent;
import me.mykindos.betterpvp.core.item.component.impl.stat.StatTypes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stat roll, tested on the half of the container that survives an item stack.
 *
 * <p>These assertions are about placement, not arithmetic. The roll axis measured nothing for its
 * first three runs while computing every value correctly: it wrote them into the container's
 * <em>base</em> stats, and {@code StatContainerSerializer} writes only modifier stats onto an item
 * and reads base stats back off the registered {@code BaseItem}. So the rolled numbers were right,
 * present in the container, visible to any test that asked the container what it held -- and thrown
 * away the moment the item became an {@code ItemStack} for a combatant to hold.
 *
 * <p>That is why these tests assert which <em>list</em> a rolled stat lands in rather than only what
 * its value is. A test written the obvious way would have passed throughout the whole defect.
 */
@DisplayName("SimStatRoll")
class SimStatRollTest {

    /** A weapon's damage as {@code weapon.yml} configures it: base 6, band 5..7. */
    private static StatContainerComponent weaponDamage() {
        return new StatContainerComponent(
                List.of(new ItemStat<>(StatTypes.MELEE_DAMAGE, 6.0, 5.0, 7.0)), List.of());
    }

    private static double meleeDamage(StatContainerComponent container) {
        return container.getStat(StatTypes.MELEE_DAMAGE).orElseThrow().getValue();
    }

    @Nested
    @DisplayName("lands in the serialized half")
    class Placement {

        @Test
        @DisplayName("MIN and MAX write a modifier stat")
        void cornersBecomeModifiers() {
            for (SimStatRoll roll : List.of(SimStatRoll.MIN, SimStatRoll.MAX)) {
                final StatContainerComponent rolled = roll.apply(weaponDamage());
                assertEquals(1, rolled.getModifierStats().size(),
                        roll + " must write exactly one modifier, or the roll is lost on the stack");
                assertEquals(StatTypes.MELEE_DAMAGE, rolled.getModifierStats().getFirst().getType());
            }
        }

        @Test
        @DisplayName("the base stat is left as configured")
        void baseIsUntouched() {
            // The item should still describe where it came from. The modifier overrides it for every
            // read, so keeping it costs nothing and a diff against the registry stays meaningful.
            final StatContainerComponent rolled = SimStatRoll.MAX.apply(weaponDamage());
            assertEquals(1, rolled.getBaseStats().size());
            assertEquals(6.0, rolled.getBaseStats().getFirst().getValue());
        }

        @Test
        @DisplayName("one stat type never appears twice")
        void noDuplicateTypes() {
            // A modifier already overrides its base stat, so emitting a rolled copy of that base stat
            // as well would leave which value the game reads down to list order.
            final StatContainerComponent reforged = new StatContainerComponent(
                    List.of(new ItemStat<>(StatTypes.MELEE_DAMAGE, 6.0, 5.0, 7.0)),
                    List.of(new ItemStat<>(StatTypes.MELEE_DAMAGE, 6.5, 5.0, 7.0)));
            final StatContainerComponent rolled = SimStatRoll.MAX.apply(reforged);
            assertEquals(1, rolled.getModifierStats().size(),
                    "an already-overridden stat must not be rolled twice");
        }
    }

    @Nested
    @DisplayName("values")
    class Values {

        @Test
        @DisplayName("the corners are the ends of the band")
        void cornersAreTheBand() {
            assertEquals(5.0, meleeDamage(SimStatRoll.MIN.apply(weaponDamage())));
            assertEquals(6.0, meleeDamage(weaponDamage()));
            assertEquals(7.0, meleeDamage(SimStatRoll.MAX.apply(weaponDamage())));
        }

        @Test
        @DisplayName("the three rolls of one item are three different numbers")
        void rollsDiffer() {
            // The whole assertion the axis exists to satisfy, and the one run 1 violated: thornfang
            // rolls 5 / 6 / 7 and measured 6.000 at all three.
            final double min = meleeDamage(SimStatRoll.MIN.apply(weaponDamage()));
            final double base = meleeDamage(weaponDamage());
            final double max = meleeDamage(SimStatRoll.MAX.apply(weaponDamage()));
            assertTrue(min < base && base < max, "expected min < base < max, got " + min + " " + base + " " + max);
        }

        @Test
        @DisplayName("BASE hands back the identical container")
        void baseIsIdentity() {
            // Not merely equal: a sweep that does not vary the axis must produce byte-identical items
            // to one taken before the axis existed, or every stored BASE baseline stops comparing.
            final StatContainerComponent container = weaponDamage();
            assertSame(container, SimStatRoll.BASE.apply(container));
        }
    }
}
