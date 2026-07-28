package me.mykindos.betterpvp.balancesim.engine;

import lombok.CustomLog;
import lombok.Getter;
import me.mykindos.betterpvp.balancesim.catalog.SimBuildSpec;
import me.mykindos.betterpvp.champions.champions.roles.RoleManager;
import me.mykindos.betterpvp.core.client.Client;
import me.mykindos.betterpvp.core.components.champions.Role;
import me.mykindos.betterpvp.core.framework.simulation.SimulatedEntity;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A headless fake player, materialised through the <em>real</em> managers.
 *
 * <p>The point of driving real objects rather than a model is that ComboAttack's ramp,
 * Vengeance's counter and expiry, energy regen and cooldowns all execute as the actual
 * mechanic instances. So a combatant gets an ephemeral {@code Client}/{@code Gamer} (never
 * persisted, see {@link SimClientFactory}), a role through {@code RoleManager}, and a weapon --
 * the same path a real login takes, minus the database.
 *
 * <p>Unlike {@code HumanNPC}, which is deliberately packet-only and never added to the world,
 * a sim combatant is a real {@code ServerPlayer} added to the world with a dead-end connection
 * ({@link SimPlayer}), so the vanilla attack path, targeting and event pipeline actually engage.
 */
@Getter
@CustomLog
public class SimCombatant {

    private final UUID uuid;
    private final String name;
    private final SimBuildSpec build;

    /** The Bukkit view of the fake player, once spawned. */
    @Nullable
    private Player player;

    @Nullable
    private SimPlayer handle;

    @Nullable
    private Client client;

    public SimCombatant(UUID uuid, String name, SimBuildSpec build) {
        this.uuid = uuid;
        this.name = name;
        this.build = build;
    }

    /**
     * Spawns the backing {@code ServerPlayer} into the sim world and applies its client, role and
     * weapon through the real managers.
     *
     * <p>Ordering is deliberate and load-bearing:
     * <ol>
     *   <li>spawn the entity, so there is something to flag;</li>
     *   <li>flag it as simulated <em>before</em> anything else, so no later step can produce a
     *       persisted side effect that escapes the guards in the stats listeners;</li>
     *   <li>register the ephemeral client and its builds, because {@code RoleManager.equipRole}
     *       looks the client up via {@code search().online(player)} and the {@code RoleChangeEvent}
     *       it fires is read by the stat listeners, which need both;</li>
     *   <li>equip the role, which is what sets base health from {@code Role.getHealth()}.</li>
     * </ol>
     *
     * <p>Must be called on the main thread.
     *
     * @param context the managers and plugin handle this combatant is built through
     * @param at      where in the sim world to place it
     */
    public void spawn(SimContext context, Location at) {
        if (handle != null) {
            throw new IllegalStateException("Combatant " + name + " is already spawned");
        }

        final SimPlayer spawned = SimPlayer.spawn(uuid, name, at);
        this.handle = spawned;
        this.player = spawned.asBukkit();

        // Flag first: everything after this point can reach a listener, and the guards keyed off
        // this metadata are what keep a simulated fight out of the stats and leaderboard tables.
        SimulatedEntity.mark(player, context.plugin());

        // Registers the client *and* its builds. equipRole below fires RoleChangeEvent, whose
        // listeners read both, so neither can be deferred until after the role is set.
        this.client = context.clientFactory().create(player, name);

        final Role role = Role.valueOf(build.role());
        context.roleManager().equipRole(player, role);

        // Plain weapon for phase 1. The weapon axis -- including boosters, which raise a skill's
        // effective level through the real accessor -- is part of the phase 2 catalog sweep.
        context.roleManager().equipWeapons(player);
    }

    /**
     * Removes the fake player and drops its ephemeral client.
     *
     * <p>Despawn happens before the client is dropped so the sequence matches a real logout, and
     * the entity is discarded rather than killed so teardown emits no death event. Leaves no row
     * in {@code clients} because none was ever written.
     */
    public void despawn(SimContext context) {
        if (handle != null) {
            if (player != null) {
                context.roleManager().cleanUp(player);
            }
            handle.despawn();
            handle = null;
        }
        if (client != null) {
            context.clientFactory().destroy(client);
            client = null;
        }
        player = null;
    }

    /**
     * Swings at the opponent through the vanilla attack path.
     *
     * <p>{@code ServerPlayer.attack} is the same entry point a real click reaches, so the swing
     * runs the full chain -- vanilla damage calculation, {@code DamageEventProcessor}, every
     * registered passive and rune listener -- rather than injecting a damage number. The
     * simulator never computes damage itself; it only decides when to swing.
     */
    public void swingAt(SimCombatant opponent) {
        if (handle == null || opponent.handle == null) {
            return;
        }
        handle.attack(opponent.handle);
        // Vanilla scales damage by attack-strength charge. Resetting the ticker keeps every
        // simulated swing a fully-charged one, so swing timing is governed by the real
        // DEFAULT_DELAY / (1 + attackSpeed) cadence the orchestrator drives rather than by a
        // partial-charge multiplier the sim never intended to model.
        handle.resetAttackStrengthTicker();
    }

    /** Whether this combatant is spawned and still alive. */
    public boolean isAlive() {
        return player != null && !player.isDead() && player.getHealth() > 0;
    }

    /**
     * The managers a combatant needs to materialise itself. Passed in rather than injected so
     * {@link SimCombatant} stays a plain object the orchestrator creates per duel.
     *
     * @param plugin        owner of the simulated-entity metadata
     * @param clientFactory builds the ephemeral client
     * @param roleManager   applies role and weapons through the real code path
     */
    public record SimContext(Plugin plugin, SimClientFactory clientFactory, RoleManager roleManager) {
    }
}
