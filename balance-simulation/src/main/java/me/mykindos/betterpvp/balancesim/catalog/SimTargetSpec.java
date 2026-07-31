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
 * <p><b>Roles can collapse.</b> An unarmoured defender carrying no skills that never swings back is,
 * to the attacker, nothing but a quantity of health -- so two roles with the same base health produce
 * the same duel and only one of them needs measuring. {@link #roleAliases()} names the ones this spec
 * stands for. The collapse is a property of the sweep, not of the roles: it is only applied where the
 * premise holds (see {@code BalanceCatalog.enumerateTargets}), and the alias list keeps it legible
 * from the row, because "no row for KNIGHT" and "KNIGHT was measured as ASSASSIN's equal" are
 * different facts and a dashboard must not have to guess which it is looking at.
 *
 * @param role        {@code Role} enum name
 * @param armorSetId  identifier of the equipped armour set, or {@code "none"}
 * @param hp          role base health + sum of armour HEALTH stats
 * @param skills      the defender's build, one entry per filled slot (may be empty for a
 *                    plain-role target)
 * @param pointsSpent sum of the defender's allocated levels, bounded by {@code RoleBuild.points}
 * @param roleAliases every role this target's measurement covers, {@link #role()} first. A
 *                    single-element list when nothing was collapsed into it
 */
public record SimTargetSpec(String role,
                            String armorSetId,
                            double hp,
                            List<SimSkillAllocation> skills,
                            int pointsSpent,
                            List<String> roleAliases) {

    public SimTargetSpec {
        roleAliases = List.copyOf(roleAliases);
    }

    /** A target standing only for its own role, which is every target of an un-reduced sweep. */
    public SimTargetSpec(String role,
                         String armorSetId,
                         double hp,
                         List<SimSkillAllocation> skills,
                         int pointsSpent) {
        this(role, armorSetId, hp, skills, pointsSpent, List.of(role));
    }

    /** Whether this target's measurement was reused for roles other than its own. */
    public boolean isCollapsed() {
        return roleAliases.size() > 1;
    }
}
