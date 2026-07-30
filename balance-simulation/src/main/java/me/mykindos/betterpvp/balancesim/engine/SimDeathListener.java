package me.mykindos.betterpvp.balancesim.engine;

import com.google.inject.Singleton;
import me.mykindos.betterpvp.core.framework.simulation.SimulatedEntity;
import me.mykindos.betterpvp.core.listener.BPvPListener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Keeps a simulated death from leaving anything behind.
 *
 * <p>Phase 3 stopped intercepting lethal blows, so the loser of a duel really dies -- which is the
 * point, because the on-death mechanics that produce value ({@code SoulHarvestAbility},
 * {@code BloodBarrier}, {@code Riposte}, {@code SeismicSlam}, {@code MagneticAxe}) are all actives
 * the rotation policy can now cast, and every one of them hangs off {@code PlayerDeathEvent}. What
 * is <em>not</em> wanted is the vanilla housekeeping around it, and all of it is side effects on a
 * world and a log rather than on the measurement:
 *
 * <ul>
 *   <li><b>Drops.</b> A death scatters the combatant's whole loadout -- weapon, four armour pieces,
 *       a bow and a stack of arrows for a ranger -- as item entities on the arena platform. At a
 *       hundred thousand duels that is millions of entities in the sim world, each one ticking,
 *       each one a candidate for the next resident on that platform to pick up and fight the
 *       following duel with. Keeping the inventory is not a fidelity loss: {@code SimPlayer}
 *       already clears and re-equips it per duel, so what the corpse was holding is discarded
 *       either way.</li>
 *   <li><b>Death messages.</b> Core's {@code DeathListener} builds a message per death and fans it
 *       out to every online player. Nulling the message here is belt-and-braces -- core's guard is
 *       the one that stops the fan-out -- and costs nothing.</li>
 *   <li><b>Experience.</b> Dropped orbs are the same entity problem as dropped items, without even
 *       a mechanic reading them.</li>
 * </ul>
 *
 * <p>{@code HIGHEST} rather than {@code MONITOR} because champions' own {@code DeathListener} runs at
 * {@code MONITOR} and re-drops the inventory by hand unless {@code getKeepInventory()} is already
 * true. Setting the flag here is what makes that listener return, so the ordering is load-bearing
 * rather than incidental.
 *
 * <p>Everything here is gated on {@link SimulatedEntity}, so on a server where the gate is closed and
 * no combatant is ever spawned the listener is inert despite being registered.
 */
@Singleton
@BPvPListener
public class SimDeathListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (!SimulatedEntity.isSimulated(event.getPlayer())) {
            return;
        }
        event.setKeepInventory(true);
        event.getDrops().clear();
        event.setKeepLevel(true);
        event.setDroppedExp(0);
        event.deathMessage(null);
    }
}
