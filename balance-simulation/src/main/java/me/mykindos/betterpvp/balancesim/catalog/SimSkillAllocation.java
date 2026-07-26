package me.mykindos.betterpvp.balancesim.catalog;

/**
 * One slot of a simulated build.
 *
 * <p>Both levels are recorded because they differ. {@code allocatedLevel} is what a player
 * spends build points on and is capped at {@code Skill.getMaxLevel()}; {@code effectiveLevel}
 * is what the skill actually executes at, which {@code SkillListener.getLevel} raises by +1 for
 * a booster weapon and by any active {@code SkillBoostEffect} amplifier -- and does <em>not</em>
 * re-clamp to the max. Without both, a booster run and a non-booster run at the same allocation
 * are indistinguishable in the fingerprint yet produce different damage.
 *
 * <p>The engine must never compute {@code effectiveLevel} itself; it is read back from the real
 * accessor after the combatant is equipped. Deriving it here would reintroduce exactly the drift
 * this project exists to remove.
 *
 * @param skillName      the skill's {@code getName()}
 * @param slot           the skill's slot/type, e.g. {@code SWORD}, {@code AXE}, {@code PASSIVE_A}
 * @param allocatedLevel points the player spent, {@code 1..maxLevel}
 * @param effectiveLevel level observed through the real accessor at duel time
 */
public record SimSkillAllocation(String skillName, String slot, int allocatedLevel, int effectiveLevel) {
}
