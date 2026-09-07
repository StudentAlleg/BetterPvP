package me.mykindos.betterpvp.balancesim.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The claim {@link SimWeaponProfile#slotAgnostic()} makes: that on a skill-less duel a sword and an
 * identically statted axe are one weapon.
 *
 * <p>Tested rather than asserted in a comment because it is a statement that two permutations are the
 * same measurement, which is the kind of claim that should fail a test rather than quietly produce
 * rows nobody can distinguish afterwards -- the same reason
 * {@code BalanceCatalog.collapseByDurability} is package-private instead of private.
 *
 * <p>The figures below are the shipped ones. {@code standard_sword} and {@code standard_axe} both
 * carry damage 6.0 [5.0-7.0] at attack speed 0.0 [-0.25-0.25], and {@code booster_sword} and
 * {@code booster_axe} carry the same numbers again with the booster flag set, so the reduction is
 * four keys onto one rather than a hypothetical.
 */
class SimWeaponProfileSlotAgnosticTest {

    private static final List<String> RUNES = List.of("core:rune_a", "core:rune_b");

    private static SimWeaponProfile weapon(boolean booster, String slot, List<String> runes) {
        return new SimWeaponProfile(6.0, 5.0, 7.0, 0.0, -0.25, 0.25, booster, slot, runes);
    }

    @Test
    @DisplayName("the full profile keeps a sword and an identical axe apart")
    void fullProfileSeparatesSlots() {
        assertNotEquals(weapon(false, "SWORD", RUNES), weapon(false, "AXE", RUNES));
    }

    @Test
    @DisplayName("dropping the slot folds a sword onto an identical axe")
    void slotAgnosticFoldsSlots() {
        assertEquals(weapon(false, "SWORD", RUNES).slotAgnostic(),
                weapon(false, "AXE", RUNES).slotAgnostic());
    }

    /**
     * The booster {@code +1} is applied inside
     * {@code if (SkillWeapons.isHolding(player, getType()) && SkillWeapons.hasBooster(player))}, so it
     * needs a skill to be reading it. With no skills allocated a booster sword is its plain
     * counterpart, which is what folds all four shipped keys onto one representative rather than two.
     */
    @Test
    @DisplayName("dropping the slot also drops the booster, folding all four shipped keys onto one")
    void slotAgnosticFoldsBoosters() {
        assertEquals(weapon(false, "SWORD", RUNES).slotAgnostic(),
                weapon(true, "SWORD", RUNES).slotAgnostic());
        assertEquals(weapon(false, "SWORD", RUNES).slotAgnostic(),
                weapon(true, "AXE", RUNES).slotAgnostic());
    }

    /**
     * The reduction drops exactly two fields and nothing else. Damage is the one that would be
     * catastrophic and invisible: folding two damage tiers together would report one tier's numbers
     * under the other's aliases, and every downstream figure would be a plausible wrong answer.
     */
    @Test
    @DisplayName("everything a skill-less duel can read stays in the key")
    void statsStillSeparate() {
        assertNotEquals(weapon(false, "SWORD", RUNES).slotAgnostic(),
                new SimWeaponProfile(7.0, 6.0, 8.0, 0.0, -0.25, 0.25, false, "SWORD", RUNES)
                        .slotAgnostic());
        assertNotEquals(weapon(false, "SWORD", RUNES).slotAgnostic(),
                new SimWeaponProfile(6.0, 5.0, 7.0, 0.1, -0.25, 0.25, false, "SWORD", RUNES)
                        .slotAgnostic());
    }

    /**
     * Two weapons accepting different runes have different rune axes whether or not a skill is
     * allocated, so folding them would silently drop rune combinations from the sweep. That reasoning
     * is untouched by the skill-less premise, so the rune list stays in the shorter key too.
     */
    @Test
    @DisplayName("the rune axis survives the reduction")
    void runesStillSeparate() {
        assertNotEquals(weapon(false, "SWORD", RUNES).slotAgnostic(),
                weapon(false, "AXE", List.of("core:rune_a")).slotAgnostic());
    }
}
