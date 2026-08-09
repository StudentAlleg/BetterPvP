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
 * <p><b>Armour is a ladder, not a flag.</b> {@link #armorSetId()} folds the set and its stat roll
 * into one discriminator, which is what {@code target_armor} has always held and what every
 * dashboard groups by. {@link #armorSet()} and {@link #armorTier()} split the set back out beside
 * it, because "does tier 2 kill in the same time as tier 1" is a comparison <em>between</em> values
 * of that discriminator, and a dashboard that had to parse a tier out of a free-form string in SQL
 * is a dashboard that will eventually disagree with the sweep about what it measured.
 *
 * @param role        {@code Role} enum name
 * @param armorSetId  identifier of the equipped armour set and roll, or {@code "none"}
 * @param hp          role base health + sum of armour HEALTH stats
 * @param skills      the defender's build, one entry per filled slot (may be empty for a
 *                    plain-role target)
 * @param pointsSpent sum of the defender's allocated levels, bounded by {@code RoleBuild.points}
 * @param roleAliases every role this target's measurement covers, {@link #role()} first. A
 *                    single-element list when nothing was collapsed into it
 * @param armorSet    the set alone, with the roll suffix stripped -- {@code "reinforced"},
 *                    or {@code "none"}
 * @param armorTier   0 for bare, then 1 upwards by the set's durability
 * @param configScopeHash digest of exactly the config this target's durability depends on -- its
 *                    armour pieces and its role's base health, and nothing else. What a delta sweep
 *                    compares to decide whether a stored matchup still describes this target; see
 *                    {@code SimConfigDigest}. Empty when the digest was unavailable, which compares
 *                    equal to nothing and therefore re-measures
 */
public record SimTargetSpec(String role,
                            String armorSetId,
                            double hp,
                            List<SimSkillAllocation> skills,
                            int pointsSpent,
                            List<String> roleAliases,
                            String armorSet,
                            int armorTier,
                            String configScopeHash) {

    public SimTargetSpec {
        roleAliases = List.copyOf(roleAliases);
    }

    /** A target standing only for its own role, which is every target of an un-reduced sweep. */
    public SimTargetSpec(String role,
                         String armorSetId,
                         double hp,
                         List<SimSkillAllocation> skills,
                         int pointsSpent,
                         String armorSet,
                         int armorTier,
                         String configScopeHash) {
        this(role, armorSetId, hp, skills, pointsSpent, List.of(role), armorSet, armorTier, configScopeHash);
    }

    /**
     * A target whose set, tier and config scope were never computed -- for tests and for the
     * un-fingerprinted path.
     *
     * <p>The tier defaults to 0 rather than guessing from the id, because a wrong tier is worse than
     * an absent one: it would place a row on the ladder at a rung it was not measured at.
     */
    public SimTargetSpec(String role,
                         String armorSetId,
                         double hp,
                         List<SimSkillAllocation> skills,
                         int pointsSpent) {
        this(role, armorSetId, hp, skills, pointsSpent, List.of(role), armorSetId, 0, "");
    }

    /** Whether this target's measurement was reused for roles other than its own. */
    public boolean isCollapsed() {
        return roleAliases.size() > 1;
    }
}
