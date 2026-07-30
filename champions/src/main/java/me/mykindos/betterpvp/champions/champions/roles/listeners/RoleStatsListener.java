package me.mykindos.betterpvp.champions.champions.roles.listeners;

import com.google.inject.Inject;
import me.mykindos.betterpvp.champions.champions.roles.RoleManager;
import me.mykindos.betterpvp.core.combat.damagelog.DamageLog;
import me.mykindos.betterpvp.core.combat.damagelog.DamageLogManager;
import me.mykindos.betterpvp.core.components.champions.Role;
import me.mykindos.betterpvp.core.framework.simulation.SimulatedEntity;
import me.mykindos.betterpvp.core.listener.BPvPListener;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

@BPvPListener
public class RoleStatsListener implements Listener {

    private final RoleManager roleManager;
    private final DamageLogManager damageLogManager;

    @Inject
    public RoleStatsListener(RoleManager roleManager, DamageLogManager damageLogManager) {
        this.roleManager = roleManager;
        this.damageLogManager = damageLogManager;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player killed = event.getPlayer();
        DamageLog lastDamaged = damageLogManager.getLastDamager(killed);
        if (lastDamaged == null) return;
        if (!(lastDamaged.getDamager() instanceof Player killer)) return;

        // The balance simulator fights fake players through the real pipeline, so a simulated duel
        // reaches this listener exactly like a real one -- and this is the one place a death writes
        // role matchup data to the database. A sweep is hundreds of thousands of duels of whatever
        // matchups the scope happened to enumerate, which would swamp the real player data the
        // role_kill_death dashboards read. Guarded here rather than in the simulator because the
        // write lives here; see docs/balance-simulation/DESIGN.md open question 4.
        if (SimulatedEntity.isSimulated(killed) || SimulatedEntity.isSimulated(killer)) return;

        Role killedRole = roleManager.getRole(killed);
        Role killerRole = roleManager.getRole(killer);
        roleManager.getRepository().saveKillDeathData(killedRole, killerRole);
    }
}
