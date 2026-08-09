package me.mykindos.betterpvp.balancesim.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The armour set id, tested as the two-way string contract it is.
 *
 * <p>{@code target_armor} folds a set and its stat roll into one discriminator, and that string is
 * the identity of a measurement: it is what {@code sim_result} stores, what every dashboard groups
 * by, what {@code run_diff} joins on and what a delta sweep's baseline is matched through. Which
 * makes the pair of functions that build it and take it apart load-bearing in a way that is easy to
 * miss -- a round trip that loses the roll would quietly merge three rows into one, and one that
 * mangles the set would make every historical row unjoinable.
 *
 * <p>The tier ordering is not tested here. It is a sort over the pieces' live {@code HEALTH} stats,
 * so it needs a registry and a running server; what can be pinned down without one is that the ids
 * survive the trip.
 */
@DisplayName("SimEquipment armour set ids")
class SimEquipmentArmorIdTest {

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        @DisplayName("the base roll leaves the set id alone")
        void baseRollIsBare() {
            // Not a formatting preference. Every row written before the roll axis existed says
            // "role_set" with no suffix, and those rows are still the baseline a delta sweep carries
            // forward from -- so the default roll has to keep producing exactly that string.
            assertEquals("reinforced", SimEquipment.armorSetId("reinforced", SimStatRoll.BASE));
            assertEquals("reinforced", SimEquipment.setOf("reinforced"));
            assertEquals(SimStatRoll.BASE, SimEquipment.rollOf("reinforced"));
        }

        @Test
        @DisplayName("min and max survive being written and read back")
        void cornersRoundTrip() {
            for (SimStatRoll roll : new SimStatRoll[]{SimStatRoll.MIN, SimStatRoll.MAX}) {
                final String id = SimEquipment.armorSetId("reinforced", roll);
                assertEquals("reinforced", SimEquipment.setOf(id), "set lost for " + roll);
                assertEquals(roll, SimEquipment.rollOf(id), "roll lost for " + roll);
            }
        }

        @Test
        @DisplayName("the three rolls of one set are three distinct ids")
        void rollsAreDistinct() {
            final String base = SimEquipment.armorSetId("reinforced", SimStatRoll.BASE);
            final String min = SimEquipment.armorSetId("reinforced", SimStatRoll.MIN);
            final String max = SimEquipment.armorSetId("reinforced", SimStatRoll.MAX);
            assertNotEquals(base, min);
            assertNotEquals(base, max);
            assertNotEquals(min, max);
        }

        @Test
        @DisplayName("a set whose own name contains an underscore keeps all of it")
        void underscoresInSetNameSurvive() {
            // The suffix is stripped from the end rather than split on, so a multi-word set token is
            // not truncated at its first underscore. "role_set" is exactly this case, and it is the
            // one every pre-tier row in the database carries.
            final String id = SimEquipment.armorSetId("role_set", SimStatRoll.MAX);
            assertEquals("role_set", SimEquipment.setOf(id));
            assertEquals(SimStatRoll.MAX, SimEquipment.rollOf(id));
        }
    }

    @Nested
    @DisplayName("legacy ids")
    class Legacy {

        @Test
        @DisplayName("the pre-tier ids still parse to what they meant")
        void preTierIdsParse() {
            // These four strings are the entire armour vocabulary of every run taken before the tier
            // axis. If any of them stopped parsing, those runs would stop being comparable to new
            // ones -- which is most of the value of having kept them.
            assertEquals(SimEquipment.ROLE_ARMOR, SimEquipment.setOf(SimEquipment.ROLE_ARMOR));
            assertEquals(SimEquipment.ROLE_ARMOR, SimEquipment.setOf("role_set_min"));
            assertEquals(SimEquipment.ROLE_ARMOR, SimEquipment.setOf("role_set_max"));
            assertEquals(SimStatRoll.MIN, SimEquipment.rollOf("role_set_min"));
            assertEquals(SimStatRoll.MAX, SimEquipment.rollOf("role_set_max"));
        }

        @Test
        @DisplayName("bare armour is not a set and has no roll")
        void bareIsUnchanged() {
            assertEquals(SimEquipment.NO_ARMOR, SimEquipment.setOf(SimEquipment.NO_ARMOR));
            assertEquals(SimStatRoll.BASE, SimEquipment.rollOf(SimEquipment.NO_ARMOR));
        }
    }
}
