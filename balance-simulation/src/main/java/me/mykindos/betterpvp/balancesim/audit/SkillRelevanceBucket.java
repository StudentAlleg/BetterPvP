package me.mykindos.betterpvp.balancesim.audit;

/**
 * What the audit concluded about a skill, and therefore what should be done about it.
 *
 * <p>Three buckets rather than the obvious two. A skill that measures identically to an empty slot
 * has two completely different explanations, and collapsing them is the failure this whole audit
 * exists to prevent: {@link #INERT} is a finding about the game, {@link #UNDRIVABLE} is a bug in
 * this engine. Excluding an undrivable skill from future sweeps would bake a harness gap into
 * config permanently, and nothing downstream would ever say so.
 */
public enum SkillRelevanceBucket {

    /**
     * Fired, and moved something the sweep measures -- damage, time to kill, or the energy economy.
     * Keep it in the permutation space.
     */
    RELEVANT,

    /**
     * Fired enough times to be trusted, and moved nothing beyond the noise threshold. A real
     * finding: on the evidence of this run the skill cannot affect a one-way damage measurement, so
     * it is a candidate for the offensive exclusion list.
     *
     * <p>Still requires human review before it reaches config. "Moved nothing measurable in this
     * scenario" is not the same claim as "does nothing", and a skill whose whole value is crowd
     * control or repositioning lands here correctly while being far from useless in a real fight.
     */
    INERT,

    /**
     * Never fired. Not a balance result at all -- the rotation pressed its button and the chain
     * refused every time, or there was no button to press.
     *
     * <p>Belongs on a bug list for the rotation policy, not on an exclusion list. The bow archetypes
     * are the one documented, deliberate case; anything else here is a defect.
     */
    UNDRIVABLE
}
