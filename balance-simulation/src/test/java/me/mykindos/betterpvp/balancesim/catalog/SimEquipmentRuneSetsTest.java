package me.mykindos.betterpvp.balancesim.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rune combination enumeration, tested for its <em>sizes</em>.
 *
 * <p>This is the axis that decides how large a rune-varying sweep is, and it is multiplied by every
 * other axis. An off-by-one here either drops a whole combination size from the catalog or multiplies
 * the duel count past what will finish in an evening, and neither shows up afterwards: a build total
 * is a number nobody has anything to compare against. So the counts are asserted against the
 * closed-form binomial sums rather than against whatever the implementation happens to produce.
 */
class SimEquipmentRuneSetsTest {

    private static final List<String> TEN = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j");

    @Test
    @DisplayName("the empty set is always first, so a stopped sweep starts from the plain weapon")
    void emptySetFirst() {
        final List<List<String>> sets = SimEquipment.runeSets(TEN, 4);

        assertEquals(List.of(), sets.get(0),
                "the rune-less baseline must lead the enumeration -- every other set is read against it");
    }

    @Test
    @DisplayName("a melee weapon at the shipped 4-socket ceiling enumerates 386 sets")
    void meleeWeaponAtShippedCeiling() {
        // 1 + 10 + 45 + 120 + 210. The figure the FULL and EQUIPMENT tiers are sized against; if the
        // distributions ever raise maxSockets past 4 this assertion is not wrong, but the sweep it
        // implies grows by 252 sets per weapon and someone should have to look at that.
        assertEquals(386, SimEquipment.runeSets(TEN, 4).size());
    }

    @Test
    @DisplayName("an axe accepts one rune more, and pays 562 sets for it")
    void axeAcceptsForestwright() {
        final List<String> eleven = new java.util.ArrayList<>(TEN);
        eleven.add("forestwright");

        // 1 + 11 + 55 + 165 + 330. Worth pinning separately: the axe axis is 45% larger than the sword
        // axis off a single extra rune, which is the kind of asymmetry a capacity estimate misses.
        assertEquals(562, SimEquipment.runeSets(eleven, 4).size());
    }

    @Test
    @DisplayName("every set is distinct and within the ceiling")
    void setsAreDistinctAndBounded() {
        final List<List<String>> sets = SimEquipment.runeSets(TEN, 3);

        final Set<Set<String>> asSets = new HashSet<>();
        for (List<String> set : sets) {
            assertTrue(set.size() <= 3, "no set may exceed the socket ceiling, got " + set);
            assertEquals(set.size(), new HashSet<>(set).size(), "a rune may not repeat within a set: " + set);
            assertTrue(asSets.add(new HashSet<>(set)),
                    "combinations, not permutations -- " + set + " was enumerated twice under some order");
        }
        assertEquals(1 + 10 + 45 + 120, sets.size());
    }

    @Test
    @DisplayName("a ceiling above the rune count yields the full power set, not an error")
    void ceilingClampsToRuneCount() {
        final List<String> three = List.of("a", "b", "c");

        // A weapon that accepts fewer runes than it has sockets is the normal case for a weapon with
        // few applicable runes, not an edge case worth failing on.
        assertEquals(8, SimEquipment.runeSets(three, 9).size(), "2^3, every subset");
    }

    @Test
    @DisplayName("a zero ceiling leaves only the bare weapon")
    void zeroCeiling() {
        // What a sweep would get if socketCeiling were read off the registered item, every one of
        // which ships with maxSockets = 0. Asserted so that regression reads as "runes do nothing"
        // here rather than in a run's results.
        assertEquals(List.of(List.of()), SimEquipment.runeSets(TEN, 0));
    }
}
