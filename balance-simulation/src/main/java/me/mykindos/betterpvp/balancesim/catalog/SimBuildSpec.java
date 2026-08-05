package me.mykindos.betterpvp.balancesim.catalog;

import java.util.List;

/**
 * A single point in the permutation space: role x skills-at-levels x weapon x runes.
 *
 * <p>Maps 1:1 onto a {@code sim_build} row. Every spec here is a build a real player can
 * actually configure, which is what lets live per-build data join straight onto
 * {@link #fingerprint()} with no interpolation.
 *
 * @param role         {@code Role} enum name
 * @param weaponKey    {@code ItemRegistry} key of the equipped weapon
 * @param armorSetId   {@code SimEquipment.NO_ARMOR} or {@code SimEquipment.ROLE_ARMOR}. Armour is
 *                     effective HP rather than mitigation, so on an <em>attacker</em> it changes
 *                     nothing about the damage measured and is always {@code none}; the field
 *                     exists because a defender is spawned from a spec too, and its armour is what
 *                     {@code sim_result.target_armor} and {@code target_hp} describe.
 * @param runeKeys     rune/gem item keys applied to the loadout
 * @param weaponRoll   where in its band the weapon's stats sit. {@link SimStatRoll#BASE} for every
 *                     sweep taken before the roll axis existed, and for any tier that does not vary
 *                     it -- so a stored row without the column reads correctly as the base roll it
 *                     was. {@link #weapon()} carries all three figures either way; this says which
 *                     of them the duel was actually fought with
 * @param skills       one entry per filled slot
 * @param pointsSpent  sum of allocated levels, bounded by {@code RoleBuild.points} (12)
 * @param booster      whether the weapon is a skill booster (from {@code SkillWeapons.isBooster})
 * @param fingerprint  stable hash over the allocated configuration, for cross-run diffing
 * @param weapon       the equipped weapon's measurable identity, denormalised onto the row so a
 *                     result carries the damage figures it was taken under rather than requiring
 *                     the config of the day to be reconstructed
 * @param weaponAliases every weapon key this build's measurement covers, {@link #weaponKey()}
 *                      first. Longer than one element only when the weapon axis was reduced to
 *                      distinct profiles; see {@code SimEquipment.distinctMeleeWeapons}
 */
public record SimBuildSpec(String role,
                           String weaponKey,
                           String armorSetId,
                           List<String> runeKeys,
                           SimStatRoll weaponRoll,
                           List<SimSkillAllocation> skills,
                           int pointsSpent,
                           boolean booster,
                           String fingerprint,
                           SimWeaponProfile weapon,
                           List<String> weaponAliases) {

    public SimBuildSpec {
        weaponAliases = List.copyOf(weaponAliases);
    }

    /**
     * The damage the weapon actually deals at this build's roll.
     *
     * <p>Denormalised figures on {@link #weapon()} describe the item's whole envelope; this picks the
     * corner of it the duel was fought at, so a dashboard does not have to re-implement the choice.
     */
    public double effectiveDamage() {
        return switch (weaponRoll) {
            case MIN -> weapon.damageMin();
            case BASE -> weapon.damageBase();
            case MAX -> weapon.damageMax();
        };
    }

    /** Whether this build's measurement was reused for weapons other than the one it names. */
    public boolean isWeaponCollapsed() {
        return weaponAliases.size() > 1;
    }
}
