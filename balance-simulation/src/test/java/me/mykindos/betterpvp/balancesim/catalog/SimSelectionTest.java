package me.mykindos.betterpvp.balancesim.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The selection decides which permutations exist, so a mistake here does not produce a wrong number
 * -- it produces a sweep that measured something other than what was asked for, and completes
 * normally while doing it. These are the cases that distinguish the two.
 */
class SimSelectionTest {

    private static SimSelection weapons(String... selectors) {
        return new SimSelection(Set.of(), List.of(selectors), List.of(), List.of(), Set.of(), List.of());
    }

    private static SimSelection skills(String... selectors) {
        return new SimSelection(Set.of(), List.of(), List.of(selectors), List.of(), Set.of(), List.of());
    }

    @Nested
    @DisplayName("matching is lenient about the form of an identifier")
    class Matching {

        @Test
        @DisplayName("a bare name matches a namespaced key")
        void bareNameMatchesNamespacedKey() {
            // Nobody types the namespace, and requiring it would make the flag unusable from chat.
            assertTrue(weapons("thornfang").admitsWeapon("champions:thornfang", List.of()));
            assertTrue(weapons("standard_sword").admitsWeapon("core:standard_sword", List.of()));
        }

        @Test
        @DisplayName("punctuation and case are not part of an identifier")
        void punctuationAndCaseAreIgnored() {
            // A skill's display name has spaces its config key does not, so all of these are one name.
            assertTrue(skills("Blood Shield").admitsSkill("Blood Shield"));
            assertTrue(skills("bloodshield").admitsSkill("Blood Shield"));
            assertTrue(skills("blood-shield").admitsSkill("Blood Shield"));
            assertTrue(skills("BLOOD_SHIELD").admitsSkill("Blood Shield"));
        }

        @Test
        @DisplayName("a name that is merely similar does not match")
        void similarNamesDoNotMatch() {
            // The leniency is about form, not about spelling. A typo must reach verify() and be
            // refused, not quietly select the nearest thing.
            assertFalse(weapons("thornfang").admitsWeapon("champions:thornfangs", List.of()));
            assertFalse(weapons("thorn").admitsWeapon("champions:thornfang", List.of()));
        }

        @Test
        @DisplayName("a trailing star is a prefix match")
        void trailingStarIsAPrefix() {
            final SimSelection family = weapons("reinforced*");
            assertTrue(family.admitsWeapon("champions:reinforced_blade", List.of()));
            assertTrue(family.admitsWeapon("champions:reinforced_axe", List.of()));
            assertFalse(family.admitsWeapon("champions:thornfang", List.of()));
        }

        @Test
        @DisplayName("a bare star is not a wildcard that matches everything")
        void bareStarIsNotAWildcard() {
            // An empty prefix would match every weapon, which is indistinguishable from having typed
            // no flag at all -- so a stray "*" would silently widen a sweep the admin meant to narrow.
            assertFalse(weapons("*").admitsWeapon("champions:thornfang", List.of()));
        }

        @Test
        @DisplayName("naming a folded-away weapon selects the row that stands for it")
        void aliasesAreMatched() {
            // The reduced tiers measure one representative per stat profile. "This weapon was never
            // swept" and "this weapon was swept under another key" are opposite answers, and matching
            // through the alias list is what gives the useful one.
            assertTrue(weapons("wind_blade")
                    .admitsWeapon("champions:thornfang", List.of("champions:thornfang", "champions:wind_blade")));
        }

        @Test
        @DisplayName("an empty selection admits everything")
        void emptyAdmitsEverything() {
            assertTrue(SimSelection.ALL.admitsWeapon("champions:anything", List.of()));
            assertTrue(SimSelection.ALL.admitsSkill("Anything"));
            assertTrue(SimSelection.ALL.admitsRune("core:anything"));
            assertTrue(SimSelection.ALL.isAll());
        }
    }

    @Nested
    @DisplayName("a selector that matches nothing is refused")
    class Verification {

        @Test
        @DisplayName("an unmatched selector throws and names what was available")
        void unmatchedSelectorThrows() {
            // The failure this exists for: --weapons=thornfangg enumerates an empty catalog, and an
            // empty sweep finishes in seconds and reports COMPLETED with nothing saying why.
            final IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> SimSelection.verify("--weapons", List.of("thornfangg"),
                            List.of("champions:thornfang", "core:standard_sword")));

            assertTrue(thrown.getMessage().contains("thornfangg"), "names the selector that failed");
            assertTrue(thrown.getMessage().contains("champions:thornfang"),
                    "lists what was available, so the intended name can be found");
        }

        @Test
        @DisplayName("one bad selector among good ones still refuses")
        void oneBadSelectorRefusesTheWholeSweep() {
            // Not "narrow to the ones that worked": the sweep would then measure a subset of what was
            // asked for and look entirely healthy.
            assertThrows(IllegalStateException.class,
                    () -> SimSelection.verify("--weapons", List.of("thornfang", "typo"),
                            List.of("champions:thornfang")));
        }

        @Test
        @DisplayName("an empty selector list verifies trivially")
        void emptySelectorListPasses() {
            assertDoesNotThrow(() -> SimSelection.verify("--weapons", List.of(), List.of()));
        }
    }

    @Nested
    @DisplayName("the canonical form is what keeps a narrowed run out of a full one")
    class Canonical {

        @Test
        @DisplayName("an unrestricted selection canonicalises to empty")
        void unrestrictedIsEmpty() {
            // So every run taken before this feature existed hashes exactly as it did, and stays a
            // resume candidate for itself.
            assertEquals("", SimSelection.ALL.canonical());
        }

        @Test
        @DisplayName("re-ordering a selector list is not a change")
        void orderIsNotPartOfIdentity() {
            // Reviewing a list and moving a name must not orphan a half-finished sweep, for the same
            // reason relevantSkills is hashed sorted.
            assertEquals(weapons("a", "b").canonical(), weapons("b", "a").canonical());
        }

        @Test
        @DisplayName("a narrowed selection is distinguishable from an unrestricted one")
        void narrowingChangesTheHash() {
            // The property config_hash relies on: without it, a --weapons=thornfang sitting would
            // find the full EQUIPMENT run as a resume candidate and write a handful of matchups into
            // a run that claims to cover the whole item space.
            assertFalse(weapons("thornfang").canonical().equals(SimSelection.ALL.canonical()));
            assertFalse(weapons("thornfang").canonical().equals(weapons("wind_blade").canonical()));
        }

        @Test
        @DisplayName("two axes with the same values are different selections")
        void axesAreNotInterchangeable() {
            // "--weapons=x" and "--runes=x" narrow different things and must not collide onto one hash.
            final SimSelection asWeapon = weapons("x");
            final SimSelection asRune = new SimSelection(Set.of(), List.of(), List.of(), List.of("x"), Set.of(), List.of());
            assertFalse(asWeapon.canonical().equals(asRune.canonical()));
        }
    }

    @Nested
    @DisplayName("role parsing")
    class Roles {

        @Test
        @DisplayName("a role name is accepted case-insensitively and normalised")
        void rolesAreCaseInsensitive() {
            assertEquals(Set.of("ASSASSIN"), SimSelection.parseRoles("assassin", "--roles"));
            assertEquals(Set.of("ASSASSIN"), SimSelection.parseRoles("Assassin", "--roles"));
        }

        @Test
        @DisplayName("a name that is not a role is refused rather than dropped")
        void badRoleThrows() {
            // A silently ignored role produces a WIDER sweep than was asked for, which is the failure
            // that costs hours rather than the one that costs seconds.
            final IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> SimSelection.parseRoles("assasin", "--roles"));
            assertTrue(thrown.getMessage().contains("assasin"));
        }

        @Test
        @DisplayName("a trailing comma does not become a selector that matches everything")
        void trailingCommaIsDropped() {
            assertEquals(List.of("a", "b"), SimSelection.parseList("a,b,"));
            assertEquals(List.of("a"), SimSelection.parseList(" a , "));
            assertEquals(List.of(), SimSelection.parseList(""));
            assertEquals(List.of(), SimSelection.parseList(null));
        }
    }
}
