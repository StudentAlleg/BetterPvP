package me.mykindos.betterpvp.balancesim.catalog;

import java.util.List;

/**
 * The defender side of a matchup.
 *
 * <p>Armour is an effective-HP stat, not a mitigation multiplier: {@code ArmorItem} contributes
 * {@code StatTypes.HEALTH}, and nothing in the codebase ever registers a
 * {@code ModifierType.ARMOR} damage modifier. So durability is role base HP plus the summed
 * armour HEALTH stats, and armour sets are part of the permutation space rather than a
 * reduction curve. Mitigation that does exist -- {@code EffectTypes.RESISTANCE} and
 * {@code DefensiveSkill} implementors -- is applied by the real pipeline during the duel and
 * shows up in the recorder's modifier breakdown, never as an assumption here.
 *
 * <p><b>The target has skills too.</b> A defender is a full combatant driven through the real
 * pipeline, not an inert HP bag: its {@code DefensiveSkill} passives, counters and damage-
 * reduction effects only fire if the fake player actually has the build equipped. Two matchups
 * with the same attacker but different defender skills produce different TTK, so the allocation
 * is part of what identifies a result and is recorded on the row rather than assumed away. Each
 * entry mirrors an attacker {@link SimSkillAllocation}: allocated level is what the player spent
 * ({@code 1..maxLevel}); effective level is what the skill executed at, read back through the
 * real accessor after the combatant is equipped and never computed here.
 *
 * @param role        {@code Role} enum name
 * @param armorSetId  identifier of the equipped armour set, or {@code "none"}
 * @param hp          role base health + sum of armour HEALTH stats
 * @param skills      the defender's build, one entry per filled slot (may be empty for a
 *                    plain-role target)
 * @param pointsSpent sum of the defender's allocated levels, bounded by {@code RoleBuild.points}
 */
public record SimTargetSpec(String role,
                            String armorSetId,
                            double hp,
                            List<SimSkillAllocation> skills,
                            int pointsSpent) {
}
