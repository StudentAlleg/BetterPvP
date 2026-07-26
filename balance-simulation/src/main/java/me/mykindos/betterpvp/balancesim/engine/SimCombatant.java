package me.mykindos.betterpvp.balancesim.engine;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import me.mykindos.betterpvp.balancesim.catalog.SimBuildSpec;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A headless fake player, materialised through the <em>real</em> managers.
 *
 * <p>The point of driving real objects rather than a model is that ComboAttack's ramp,
 * Vengeance's counter and expiry, energy regen and cooldowns all execute as the actual
 * mechanic instances. So a combatant gets an ephemeral {@code Client}/{@code Gamer} (never
 * persisted), a role through {@code RoleManager}, a build through {@code BuildManager}, and a
 * weapon through {@code ItemFactory} -- the same path a real login takes, minus the database.
 *
 * <p>Unlike {@code HumanNPC}, which is deliberately packet-only and never added to the world,
 * a sim combatant needs a real {@code ServerPlayer} <em>added to the world</em> (with a no-op
 * connection) so the vanilla attack path, targeting and event pipeline actually engage. There
 * is no client connection, so no packets need sending.
 *
 * <p><b>Scaffolding.</b> {@link #spawn()} and {@link #despawn()} are unimplemented; see
 * {@code docs/balance-simulation/DESIGN.md} section 3.2 and open question 2 (how far the
 * {@code ClientManager} join flow can be bypassed -- client load is async and DB-backed, so
 * join listeners must either not fire for sim players or be short-circuited by the gate).
 */
@Getter
@RequiredArgsConstructor
public class SimCombatant {

    private final UUID uuid;
    private final SimBuildSpec build;

    /** The Bukkit view of the fake player, once spawned. */
    @Nullable
    private Player player;

    /**
     * Spawns the backing {@code ServerPlayer} into the sim world and applies role, build,
     * weapon and runes through the real managers.
     *
     * <p>TODO(phase 1): NMS {@code ServerPlayer} with a no-op connection, added to the world;
     * ephemeral client registration; loadout application. After equipping, read each skill's
     * level back through the real accessor and write it into the build's
     * {@code effectiveLevel} -- never compute it here.
     */
    public void spawn() {
        throw new UnsupportedOperationException("SimCombatant.spawn is not implemented yet");
    }

    /**
     * Removes the fake player and drops its ephemeral client.
     *
     * <p>TODO(phase 1): must leave no row in {@code clients} and no residual per-UUID combat,
     * effect or cooldown state.
     */
    public void despawn() {
        throw new UnsupportedOperationException("SimCombatant.despawn is not implemented yet");
    }
}
