package me.mykindos.betterpvp.balancesim.engine;

import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.core.Core;
import me.mykindos.betterpvp.core.combat.delay.DamageDelayManager;
import me.mykindos.betterpvp.core.cooldowns.CooldownManager;
import me.mykindos.betterpvp.core.effects.EffectManager;
import me.mykindos.betterpvp.core.interaction.state.InteractionStateManager;
import me.mykindos.betterpvp.core.interaction.tracker.ActiveInteractionTracker;
import me.mykindos.betterpvp.core.interaction.tracker.HoldTracker;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Drops every scrap of per-UUID state a combatant left behind in Core's long-lived managers.
 *
 * <h2>Why this has to exist</h2>
 * A real player's per-UUID state is torn down by {@code PlayerQuitEvent} handlers -- there are
 * three dozen of them across core and champions. A {@link SimPlayer} never fires one: it is
 * deliberately absent from the {@code PlayerList} ({@link SimPlayer}), so it is removed from the
 * level as an entity and no quit ever happens. Every manager keyed on its UUID therefore keeps
 * that entry forever, and because each duel mints two fresh random UUIDs, "forever" means the
 * maps grow by two per duel for the length of the sweep.
 *
 * <p>That is not merely a memory leak. The managers below are swept by {@code @UpdateEvent}
 * methods that walk their whole map every tick, so a cost that should be proportional to the
 * duels in flight becomes proportional to the duels ever run, and a sweep's throughput decays as
 * the reciprocal of its own progress. A full 105k-duel run measured this directly: it started at
 * roughly 16 duels/s and was down to 0.35 duels/s by the halfway mark, with 27 of 29 watchdog
 * thread dumps caught inside {@code EffectListener.onUpdate}.
 *
 * <h2>Why the managers' own cleanup is not enough</h2>
 * Each of these self-cleans on a condition a departed fake player never satisfies:
 * <ul>
 *   <li><b>Effects.</b> {@code EffectListener.processEffectsForEntity} drops an effect whose
 *       entity has vanished only if the effect has <em>expired</em>, and a permanent one never
 *       does. {@code AssassinListener.checkRoleBuffs} hands every assassin a permanent SPEED with
 *       a negative length, and removes it only in the {@code else} branch of a loop over
 *       {@code roleManager.getLivingEntities()} -- which the combatant has already left by the
 *       time it is despawned. {@code EffectManager.removeAllEffects} is no help either: it
 *       explicitly skips permanent effects, because for a real player they are meant to survive a
 *       logout and be re-applied on join. Hence {@code removeObject}, which drops the entity's
 *       whole entry unconditionally.</li>
 *   <li><b>Cooldowns.</b> {@code CooldownManager.processCooldowns} expires the individual
 *       cooldowns but never removes the outer per-UUID entry once its map is empty, so an
 *       otherwise-empty {@code ConcurrentHashMap} per combatant is walked every 100 ms.</li>
 *   <li><b>Interaction trackers.</b> Cleared by {@code InteractionListener.onPlayerQuit}, which is
 *       exactly the event that does not fire. {@code HoldTracker.onHold} additionally re-executes
 *       held-item interactions for every tracked entity each tick; a stale entry pointing at an
 *       arena whose chunk has since unloaded turns that into a synchronous chunk load on the main
 *       thread, which is what the last watchdog dump of the run caught.</li>
 * </ul>
 *
 * <h2>Why the managers come from Core's injector</h2>
 * Same hazard {@code DuelOrchestrator} documents for {@code RoleManager}. This plugin's injector
 * is a sibling of Champions' under Core, and none of these singletons is explicitly bound in it;
 * every one of their dependencies resolves from Core, so Guice is free to satisfy them with a
 * just-in-time binding placed <em>here</em>. That would hand this class a second, empty
 * {@code EffectManager} while {@code EffectListener} kept sweeping the first -- a purge that
 * silently purges nothing, which is the worst shape this bug could take, since the symptom is
 * indistinguishable from the bug being unfixed.
 */
@Singleton
@CustomLog
public class SimStatePurge {

    private final EffectManager effectManager;
    private final CooldownManager cooldownManager;
    private final HoldTracker holdTracker;
    private final InteractionStateManager stateManager;
    private final ActiveInteractionTracker activeInteractionTracker;
    private final DamageDelayManager damageDelayManager;

    public SimStatePurge() {
        final var coreInjector = JavaPlugin.getPlugin(Core.class).getInjector();
        this.effectManager = coreInjector.getInstance(EffectManager.class);
        this.cooldownManager = coreInjector.getInstance(CooldownManager.class);
        this.holdTracker = coreInjector.getInstance(HoldTracker.class);
        this.stateManager = coreInjector.getInstance(InteractionStateManager.class);
        this.activeInteractionTracker = coreInjector.getInstance(ActiveInteractionTracker.class);
        this.damageDelayManager = coreInjector.getInstance(DamageDelayManager.class);
    }

    /**
     * Forgets everything keyed on {@code uuid}.
     *
     * <p>Unconditional rather than conditional: a combatant's UUID belongs to one arena slot for
     * the length of the sweep and nothing outside a duel reads state under it, so there is nothing
     * anything could legitimately want after the duel ends, and no case where leaving an entry
     * behind is correct.
     *
     * <p>Since {@link SimCombatantPool} made combatants resident, this is load-bearing rather than
     * merely tidy. The entity is no longer discarded between duels, so anything left under its
     * identity is inherited by the next fight on that platform: what used to be a leak that slowed
     * a sweep down is now a bias that changes what the sweep reports.
     *
     * <p>Must be called on the main thread -- the interaction trackers' removal paths can run an
     * interaction's end-of-life callback, which touches world state.
     *
     * @param uuid   the combatant leaving the duel
     * @param entity its entity, when still available. Damage delays are keyed by entity pair rather
     *               than by UUID, so they can only be reached through the object; a null skips just
     *               that step.
     */
    public void purge(UUID uuid, @Nullable Entity entity) {
        final String key = uuid.toString();
        effectManager.removeObject(key);
        cooldownManager.removeObject(key);
        holdTracker.removeActor(uuid);
        stateManager.removeActor(uuid);
        activeInteractionTracker.removeActor(uuid);
        if (entity != null) {
            // Otherwise the delay recorded against the last hit of a duel outlives it, and the
            // opening swings of the next duel on the slot are rejected -- which reads as a slower
            // build rather than as a stale entry.
            damageDelayManager.clearDelaysForEntity(entity);
        }
    }
}
