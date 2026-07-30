package me.mykindos.betterpvp.balancesim.engine;

import me.mykindos.betterpvp.core.client.Client;
import me.mykindos.betterpvp.core.client.Rank;
import me.mykindos.betterpvp.core.client.gamer.Gamer;
import me.mykindos.betterpvp.core.client.stats.StatContainer;
import me.mykindos.betterpvp.core.client.stats.impl.IStat;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link Client} that can never reach the database.
 *
 * <p>A simulated client is published into {@code ClientManager}'s cache but has no row in
 * {@code clients} -- that is the whole point, see {@link SimClientFactory}. Every table keyed on
 * {@code clients.id} is therefore a foreign key violation waiting to happen, and two of them are on
 * paths the simulator unavoidably walks:
 *
 * <ul>
 *   <li>{@code saveProperty} fires {@code ClientPropertyUpdateEvent}, which {@code ClientListener}
 *       turns into a queued upsert into {@code client_properties};</li>
 *   <li>{@code statContainer.incrementStat} marks the stat dirty, and the 2-minute flush in
 *       {@code ClientListener.processStatUpdates} writes every dirty stat of every <em>loaded</em>
 *       client into {@code client_stats}. Note {@code loaded}, not {@code online}: the
 *       {@code isLoaded()} filter that keeps simulated clients out of {@code getOnline()} does not
 *       apply here, so being absent from the player list is not enough to stay out of that flush.
 *       {@code RoleManager.equipRole} increments a {@code RoleStat} on every spawn, so a sweep
 *       dirties one per combatant.</li>
 * </ul>
 *
 * <p>Both are closed here rather than by guarding the call sites, for the same reason the kill
 * suppression lives in {@code SimRecorder}: the alternative is teaching core's client, stat and
 * property code what a simulated player is, and the design's one hard rule is that no simulation
 * awareness leaks into the game modules.
 *
 * <p>Losing the stats is not a side effect to be tolerated -- it is the intent. Design open
 * question 4 requires simulated fights to be invisible to stats and leaderboards, and the
 * measurements the simulator actually reports come from {@code SimRecorder}, not from
 * {@code StatContainer}.
 */
public class SimClient extends Client {

    private final StatContainer statContainer = new SimStatContainer(this);

    public SimClient(long id, @NotNull Gamer gamer, @NotNull String uuid, @NotNull String name, @NotNull Rank rank) {
        super(id, gamer, uuid, name, rank);
    }

    /**
     * Never loaded, whatever the player list says.
     *
     * <p>The inherited implementation answers {@code Bukkit.getPlayer(uuid) != null}, and phase 3
     * made that true: a combatant is registered in {@code PlayerList.playersByUUID} so the skills
     * that resolve their holders by UUID can find it ({@link SimPlayer}). That would have quietly
     * put simulated clients back into {@code ClientManager.getOnline()}, which is the filter a
     * dozen "for each online client" sweeps rely on -- chat channels, rank reporters, capacity
     * reporting, the player list command.
     *
     * <p>So the two facts are separated here: the <em>entity</em> is resolvable, because the game's
     * combat code needs it to be, and the <em>client</em> is not loaded, because it has no row and
     * nothing that writes one should ever see it.
     */
    @Override
    public boolean isLoaded() {
        return false;
    }

    /**
     * Writes the property to memory only.
     *
     * <p>{@code putSilent} is the property map's own escape hatch -- the one
     * {@code putProperty(key, value, true)} uses -- so the value stays readable through
     * {@code getProperty} while no update event, and therefore no queued query, is produced.
     */
    @Override
    public void saveProperty(String key, Object object) {
        properties.putSilent(key, object);
    }

    /**
     * Shadows the inherited container with one that records nothing.
     *
     * <p>The superclass builds its own in its constructor and nothing outside {@code Client} reads
     * that field directly, so overriding the accessor is enough to redirect every caller --
     * including {@code ClientSQLLayer.getStatUpdates}, which asks for {@code getChangedStats()} and
     * will now always find it empty.
     */
    @Override
    public StatContainer getStatContainer() {
        return statContainer;
    }

    private static class SimStatContainer extends StatContainer {

        SimStatContainer(Client client) {
            super(client);
        }

        /**
         * Drops the stat instead of recording it.
         *
         * <p>This is the single choke point: the {@code double} overload delegates here, and the
         * dirty set this would have added to is the only thing the flush reads. Nothing is written
         * to the in-memory stat map either, so no {@code StatPropertyUpdateEvent} fires and the
         * listeners downstream of it -- leaderboards, achievements, progression -- never see a
         * simulated fight.
         */
        @Override
        public void incrementStat(@Nullable IStat stat, long amount) {
            // Intentionally empty.
        }
    }
}
