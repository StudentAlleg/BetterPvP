package me.mykindos.betterpvp.balancesim.engine;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.balancesim.SimulationGate;
import me.mykindos.betterpvp.champions.champions.skills.Skill;
import me.mykindos.betterpvp.champions.champions.skills.data.SkillWeapons;
import me.mykindos.betterpvp.champions.champions.skills.types.ActiveToggleSkill;
import me.mykindos.betterpvp.core.Core;
import me.mykindos.betterpvp.core.components.champions.SkillType;
import me.mykindos.betterpvp.core.cooldowns.CooldownManager;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Casts everything the moment the game will let it, and holds a channel for a declared budget.
 *
 * <p>"Greedy" is the honest name for what this measures: the upper bound a build can produce when
 * every button is pressed as early as it can be. It is not a claim about how a good player plays --
 * saving a mobility skill, holding a counter for the right moment, and deciding when a channel is
 * worth standing still for are all skill expression the simulator deliberately does not model. That
 * gap is the point of design section 3.5: sim numbers are theoretical strength, live KDR is what
 * players actually achieve, and the difference between them is a balance signal rather than an error.
 *
 * <h2>What is decided here, and what is not</h2>
 * Only <em>when</em> a button is pressed. Whether anything happens is the real chain's answer: the
 * synthesised input reaches {@code SkillListener}, which consumes the cooldown, spends the energy and
 * applies the silence/stun/slow/liquid gates. Two reads of live state shape the timing, and both are
 * reads rather than reimplementations:
 * <ul>
 *   <li><b>Cooldown.</b> {@code CooldownManager.hasCooldown} -- skip a press that the chain would
 *       certainly refuse. Not a model of the cooldown; the cooldown is still started by the chain.</li>
 *   <li><b>Weapon slot.</b> {@code SkillWeapons.isHolding} -- {@code SkillListener.onSkillActivate}
 *       resolves an interact skill by the held weapon's slot, so an axe skill cannot fire while a sword
 *       is held. Pressing anyway would be harmless and a waste of an event dispatch per tick per
 *       combatant.</li>
 * </ul>
 * Energy is deliberately <em>not</em> read. The refusal is the measurement -- {@code SimRecorder}
 * observes it and sets {@code sim_result.energy_limited} -- and pre-empting it here would replace an
 * observation with an assumption.
 *
 * <h2>One body, one right hand</h2>
 * A build can carry a channel, an interact skill and a toggle at once, and a real player cannot press
 * them simultaneously. So a held archetype takes precedence: while a channel or charge is being held,
 * nothing else presses right click, and the hold is released when its budget expires. Toggles are
 * exempt, because the drop key is a different button.
 *
 * <h2>Archetypes</h2>
 * <ul>
 *   <li>{@code INTERACT}, {@code PREPARE} -- one right click when off cooldown. A prepare's damage is
 *       conditional on the next melee hit landing, which the duel supplies naturally; nothing here
 *       treats it as free.</li>
 *   <li>{@code CHANNEL}, {@code CHARGE} -- right click, then hold for
 *       {@code channelHoldTicks}. A charge usually fires itself before the budget runs out, so the
 *       budget is an upper bound there rather than a duration. The number is a <em>policy choice</em>
 *       and not a measurement, which is why it is recorded in {@code sim_run.scenario}: two runs with
 *       different hold budgets are not comparable for a channel build.</li>
 *   <li>{@code TOGGLE} -- one drop-key press. An {@code ActiveToggleSkill} is left on once it is on,
 *       because a second press turns it off; anything else is a one-shot and re-presses on cooldown.</li>
 *   <li>{@code PASSIVE} -- nothing. They fire from the duel's normal traffic, which is what phase 2
 *       already measured.</li>
 *   <li>{@code BOW} -- nothing, and {@code SimSkillFilter} keeps these out of the catalog entirely so no
 *       row ever claims one was measured.</li>
 * </ul>
 */
@Singleton
@CustomLog
public class GreedyRotationPolicy implements RotationPolicy {

    private final SimulationGate gate;
    private final SimInputs inputs;

    /**
     * Where a press is reported so it can be counted.
     *
     * <p>The press is the one part of an activation no listener can observe. A skill the rotation
     * never pressed and a skill the chain refused every time both end a duel having done nothing, and
     * only the count taken here separates them -- which is the distinction the relevance audit turns
     * on, since the first is a bug in this engine and the second is a fact about the game.
     */
    private final SimRecorder recorder;

    /**
     * Core's cooldown manager, pulled from Core's injector rather than injected.
     *
     * <p>Same hazard {@code SimStatePurge} documents: none of Core's singletons is bound in this
     * plugin's sibling injector, so a just-in-time binding here would build a second
     * {@code CooldownManager} that knows about no cooldowns at all -- and the rotation would press every
     * button every tick while the real chain refused, which looks like a slow build rather than a bug.
     */
    private final CooldownManager cooldownManager;

    @Inject
    public GreedyRotationPolicy(SimulationGate gate, SimInputs inputs, SimRecorder recorder) {
        this.gate = gate;
        this.inputs = inputs;
        this.recorder = recorder;
        this.cooldownManager = JavaPlugin.getPlugin(Core.class).getInjector().getInstance(CooldownManager.class);
    }

    @Override
    public void act(SimCombatant combatant, SimCombatant opponent, long tick) {
        final Player player = combatant.getPlayer();
        if (player == null || combatant.getDrivenSkills().isEmpty()) {
            return;
        }

        // A hold occupies the right hand, so it is resolved before anything else may press it.
        final SimCombatant.DrivenSkill held = combatant.heldSkill();
        if (held != null) {
            if (tick < held.getReleaseHoldAtTick()) {
                // Still channelling. Toggles use the drop key and are unaffected, so they are the only
                // thing that may still act this tick.
                driveToggles(combatant, player, tick);
                return;
            }
            inputs.endHold(player);
            held.released();
            held.attemptedAt(tick, retryIntervalTicks());
        }

        for (SimCombatant.DrivenSkill driven : combatant.getDrivenSkills()) {
            if (driven.getArchetype() == ActivationArchetype.TOGGLE) {
                driveToggle(driven, player, tick);
                continue;
            }
            if (!isPressable(driven, player, tick)) {
                continue;
            }
            switch (driven.getArchetype()) {
                case INTERACT, PREPARE -> {
                    notePress(player, driven);
                    inputs.rightClick(player);
                    driven.attemptedAt(tick, retryIntervalTicks());
                }
                case INTERACT_ENTITY -> {
                    // Needs a live opponent to click. A duel always has one, but the combatant can be
                    // mid-recycle, and clicking a dead or absent target is not an attempt worth
                    // counting -- it would inflate attempts against successes that never had a chance.
                    final Player targetPlayer = opponent == null ? null : opponent.getPlayer();
                    if (targetPlayer != null && targetPlayer.isValid()) {
                        notePress(player, driven);
                        inputs.rightClickEntity(player, targetPlayer);
                        driven.attemptedAt(tick, retryIntervalTicks());
                    }
                }
                case CHANNEL, CHARGE -> {
                    notePress(player, driven);
                    inputs.rightClick(player);
                    inputs.beginHold(player);
                    driven.holdUntil(tick + holdTicks());
                    // One hand: whatever else the build carries waits until this hold is released.
                    return;
                }
                default -> {
                    // PASSIVE needs no input; BOW cannot be driven and is filtered out of the catalog.
                }
            }
        }
    }

    /** Presses the drop key for every toggle in the build that wants pressing this tick. */
    private void driveToggles(SimCombatant combatant, Player player, long tick) {
        for (SimCombatant.DrivenSkill driven : combatant.getDrivenSkills()) {
            if (driven.getArchetype() == ActivationArchetype.TOGGLE) {
                driveToggle(driven, player, tick);
            }
        }
    }

    /**
     * Presses the drop key once for a toggle that is off and off cooldown.
     *
     * <p>The distinction that matters is between a toggle that <em>stays</em> on and one that is a
     * one-shot. An {@code ActiveToggleSkill} holds its state in {@code getActive()} and a second press
     * turns it off, so pressing it every retry interval would flicker it on and off and measure roughly
     * half of it. Everything else reaching {@code ToggleSkill.toggle} -- a
     * {@code CooldownToggleSkill}, say -- does its work and ends, so the cooldown is the only gate.
     */
    private void driveToggle(SimCombatant.DrivenSkill driven, Player player, long tick) {
        if (driven.getSkill() instanceof ActiveToggleSkill toggle
                && toggle.getActive().contains(player.getUniqueId())) {
            return;
        }
        if (!isPressable(driven, player, tick)) {
            return;
        }
        notePress(player, driven);
        inputs.dropKey(player);
        driven.attemptedAt(tick, retryIntervalTicks());
    }

    /**
     * Reports a press so the duel's ledger can count it.
     *
     * <p>Before the input rather than after, so a press whose synthesised event throws is still
     * counted as attempted -- an attempt that produced an exception is emphatically not a skill that
     * was never driven, and the audit reads those two the opposite way.
     *
     * <p>Note that a {@code PASSIVE} never reaches here: it has no button, and its attempt count is
     * therefore zero by construction rather than by failure. The audit reads the archetype alongside
     * the count for exactly this reason.
     */
    private void notePress(Player player, SimCombatant.DrivenSkill driven) {
        recorder.noteActivationAttempt(player.getUniqueId(), driven.getSkill().getName());
    }

    /**
     * Whether the rotation should press this skill's button on this tick.
     *
     * <p>The level check is the real accessor, so a skill whose effective level is zero -- not equipped
     * for the current role, or a passive in spectator -- is not pressed. That cannot happen for a
     * catalog build, and it is what would happen if it ever did.
     */
    private boolean isPressable(SimCombatant.DrivenSkill driven, Player player, long tick) {
        if (tick < driven.getNextAttemptTick()) {
            return false;
        }
        final Skill skill = driven.getSkill();
        final SkillType type = skill.getType();
        if ((type == SkillType.SWORD || type == SkillType.AXE || type == SkillType.BOW)
                && !SkillWeapons.isHolding(player, type)) {
            return false;
        }
        if (cooldownManager.hasCooldown(player, skill.getName())) {
            return false;
        }
        return skill.getLevel(player) > 0;
    }

    /** Floored at one so a misconfigured interval cannot make the rotation press every skill every tick. */
    private long retryIntervalTicks() {
        return Math.max(1, gate.getSkillRetryIntervalTicks());
    }

    /** Floored at one tick: a hold of zero would be a tap, which is a different input entirely. */
    private long holdTicks() {
        return Math.max(1, gate.getChannelHoldTicks());
    }
}
