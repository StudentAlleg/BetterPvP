package me.mykindos.betterpvp.balancesim.engine;

import lombok.Getter;
import me.mykindos.betterpvp.champions.champions.skills.Skill;
import me.mykindos.betterpvp.champions.champions.skills.types.BowChargeSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.ChannelSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.ChargeSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.InteractSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.PrepareArrowSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.PrepareSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.ToggleSkill;

/**
 * The distinct ways a skill is activated, and therefore the distinct inputs the rotation policy
 * has to synthesise.
 *
 * <p>There is no single "use skill" entry point in {@code champions/.../skills/types/} -- each
 * archetype's listener consumes a different event, and that is the whole of design open question 3.
 * {@link #of(Skill)} is the answer: it reads the archetype off the skill's own type hierarchy, so a
 * skill is driven the way its listener expects rather than the way a table in this plugin claims.
 *
 * <h2>Order matters</h2>
 * The hierarchy is not a partition. {@code PrepareSkill implements InteractSkill};
 * {@code ChargeSkill extends ChannelSkill} and its subclasses implement {@code InteractSkill} too;
 * {@code PrepareArrowSkill extends PrepareSkill} and {@code BowChargeSkill extends ChargeSkill}. So
 * {@link #of(Skill)} tests from most specific to least, and each case names the input that actually
 * drives that skill rather than the most general one it would also answer to.
 */
@Getter
public enum ActivationArchetype {

    /** Passives, which need no input at all -- they fire from the duel's normal traffic. */
    PASSIVE(true),

    /** {@code InteractSkill}: a single right-click. */
    INTERACT(true),

    /**
     * {@code PrepareSkill}: right-click arms the skill, the next landed melee hit resolves it.
     * Its damage is conditional on a hit landing and must not be counted as free DPS.
     */
    PREPARE(true),

    /**
     * {@code ToggleSkill} and friends: driven by the drop key via
     * {@code SkillListener.onDrop} -> {@code PlayerUseToggleSkillEvent}. Note it filters out
     * inventory-originated drops, so the synthesised event has to look like a real world drop.
     */
    TOGGLE(true),

    /**
     * {@code ChannelSkill} / {@code EnergyChannelSkill}: right-click <em>held</em> and ticked
     * for as long as it is held. Needs a declared hold duration -- "cast when off cooldown"
     * says nothing about how long to hold, so {@code SimulationGate.channelHoldTicks} supplies one
     * and it is recorded in the run's scenario JSON, because it is a policy choice and not a
     * measurement.
     */
    CHANNEL(true),

    /**
     * {@code ChargeSkill}: charge accumulates while right click is held and releases at a threshold.
     * Driven like {@link #CHANNEL}; the difference is that the skill decides when it fires, so the
     * hold budget is an upper bound rather than the duration.
     */
    CHARGE(true),

    /**
     * {@code BowChargeSkill} / {@code PrepareArrowSkill}: bow draw and release.
     *
     * <p><b>Not supported.</b> These need a real {@code Arrow} through the vanilla bow path --
     * {@code EntityShootBowEvent} carries the projectile the skill then tracks, and the damage
     * arrives when the arrow hits, which depends on flight time and on the two combatants being far
     * enough apart for a bow to be the right weapon at all. Every piece of that is a genuine second
     * scenario (a ranged engagement at a distance), not an input to synthesise, so a bow skill is
     * excluded by {@code SimSkillFilter} rather than driven badly and reported as measured.
     */
    BOW(false);

    /**
     * Whether this engine can actually exercise the archetype.
     *
     * <p>{@code SimSkillFilter} reads this to keep unexercisable skills out of the catalog entirely.
     * The distinction matters because an unexercisable skill does not measure as absent -- it measures
     * as an <em>empty slot</em> while its {@code sim_build} row claims a skill, which is a wrong number
     * rather than a missing one.
     */
    private final boolean supported;

    ActivationArchetype(boolean supported) {
        this.supported = supported;
    }

    /**
     * The archetype {@code skill} is activated through.
     *
     * <p>Read from the type hierarchy rather than from a name table, so a new skill is classified
     * correctly the moment it is written and a skill that changes archetype cannot silently keep being
     * driven the old way.
     */
    public static ActivationArchetype of(Skill skill) {
        if (skill instanceof BowChargeSkill || skill instanceof PrepareArrowSkill) {
            return BOW;
        }
        if (skill instanceof ChargeSkill) {
            return CHARGE;
        }
        if (skill instanceof ChannelSkill) {
            return CHANNEL;
        }
        // Before PREPARE and INTERACT: an ActiveToggleSkill is a ToggleSkill, and the drop key is the
        // only input that reaches either.
        if (skill instanceof ToggleSkill) {
            return TOGGLE;
        }
        if (skill instanceof PrepareSkill) {
            return PREPARE;
        }
        if (skill instanceof InteractSkill) {
            return INTERACT;
        }
        return PASSIVE;
    }

    /** Whether driving this archetype requires holding right click rather than tapping it. */
    public boolean isHeld() {
        return this == CHANNEL || this == CHARGE;
    }

    /** Whether an activation only pays off when a melee hit lands, so its damage is conditional. */
    public boolean isConditionalOnHit() {
        return this == PREPARE;
    }
}
