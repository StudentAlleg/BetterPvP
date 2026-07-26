package me.mykindos.betterpvp.balancesim.engine;

/**
 * The distinct ways a skill is activated, and therefore the distinct inputs the rotation policy
 * has to synthesise.
 *
 * <p>There is no single "use skill" entry point in {@code champions/.../skills/types/} -- each
 * archetype's listener consumes a different event. A greedy "cast when off cooldown" policy is
 * well defined for {@link #INTERACT} and {@link #TOGGLE} but not for the others, which is the
 * bulk of phase 3's scope.
 */
public enum ActivationArchetype {

    /** {@code InteractSkill}: a single right-click. */
    INTERACT,

    /**
     * {@code PrepareSkill}: right-click arms the skill, the next landed melee hit resolves it.
     * Its damage is conditional on a hit landing and must not be counted as free DPS.
     */
    PREPARE,

    /**
     * {@code ToggleSkill} and friends: driven by the drop key via
     * {@code SkillListener.onDrop} -> {@code PlayerUseToggleSkillEvent}. Note that listener
     * filters out inventory-originated drops, so the synthesised event has to look like a real
     * world drop.
     */
    TOGGLE,

    /**
     * {@code ChannelSkill} / {@code EnergyChannelSkill}: right-click <em>held</em> and ticked
     * for as long as it is held. Needs a declared hold duration -- "cast when off cooldown"
     * says nothing about how long to hold.
     */
    CHANNEL,

    /** {@code ChargeSkill}: charge accumulates over time and releases at a threshold. */
    CHARGE,

    /**
     * {@code BowChargeSkill} / {@code PrepareArrowSkill}: bow draw and release, which needs a
     * real projectile through the vanilla bow path rather than a synthesised damage call.
     */
    BOW,

    /** Passives, which need no input at all -- they fire from the duel's normal traffic. */
    PASSIVE
}
