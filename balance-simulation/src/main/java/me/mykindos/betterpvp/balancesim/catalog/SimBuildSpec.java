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
 * @param skills       one entry per filled slot
 * @param pointsSpent  sum of allocated levels, bounded by {@code RoleBuild.points} (12)
 * @param booster      whether the weapon is a skill booster (from {@code SkillWeapons.isBooster})
 * @param fingerprint  stable hash over the allocated configuration, for cross-run diffing
 */
public record SimBuildSpec(String role,
                           String weaponKey,
                           String armorSetId,
                           List<String> runeKeys,
                           List<SimSkillAllocation> skills,
                           int pointsSpent,
                           boolean booster,
                           String fingerprint) {
}
