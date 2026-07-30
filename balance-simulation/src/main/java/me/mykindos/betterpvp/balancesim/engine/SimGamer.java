package me.mykindos.betterpvp.balancesim.engine;

import me.mykindos.betterpvp.core.client.gamer.Gamer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * A {@link Gamer} that knows which fake player it belongs to.
 *
 * <p>{@code Gamer.getPlayer()} resolves through {@code Bukkit.getPlayer(uuid)}, which returns null
 * for a simulation combatant because it was deliberately never registered with {@code PlayerList}
 * (see {@link SimPlayer}). Listeners reasonably treat a null there as impossible -- both
 * {@code RoleStatListener.onUpdate} and {@code SkillStatListener.incrementStats} wrap it in
 * {@code Objects.requireNonNull} -- so every one of them threw during a sweep.
 *
 * <p>Overriding the accessor fixes all of those call sites at once, and does it without giving up
 * the isolation that keeping fake players out of the player list buys: {@code Client.isLoaded()}
 * asks {@code Bukkit.getPlayer} <em>directly</em> rather than going through the gamer, so a
 * simulated client still reports as not loaded and stays out of {@code ClientManager.getOnline()}.
 *
 * <p>Phase 3 registered a <em>fighting</em> combatant in {@code PlayerList.playersByUUID}, so the
 * inherited lookup would now succeed while a duel is running. The override stays regardless: it is
 * also correct between duels, when a resident is deliberately unregistered again, and it makes the
 * gamer's answer independent of a registration whose whole point is to be narrow.
 */
public class SimGamer extends Gamer {

    private final Player player;

    public SimGamer(long id, Player player) {
        super(id, player.getUniqueId().toString());
        this.player = player;
    }

    @Override
    public @Nullable Player getPlayer() {
        return player;
    }

    /**
     * Writes the property to memory only, never to the database.
     *
     * <p>{@code Gamer} registers itself as a listener on its own property map, so an ordinary
     * {@code saveProperty} fires {@code GamerPropertyUpdateEvent}, and {@code GamerStatListener}
     * turns that into a queued upsert into {@code gamer_properties}. That queue is flushed wholesale
     * every two minutes and is not filtered by online state, so a simulated gamer's row reaches
     * Postgres and violates {@code gamer_properties_client_fkey} -- there is no {@code clients} row
     * for a client that was deliberately never persisted. {@code RoleManager.equipRole} saves
     * {@code CURRENT_ROLE} on every combatant spawn, so a sweep queues two of these per duel.
     *
     * <p>The damage is not confined to the simulation: the flush batches every pending property
     * write in one transaction, so a single bad row aborts the whole batch and real players' writes
     * are lost with it.
     *
     * <p>{@code putSilent} is the map's own escape hatch -- the same one {@code putProperty(key,
     * value, true)} uses -- so the value is still readable through {@code getProperty}, which is all
     * {@code RoleManager.getRole} needs.
     */
    @Override
    public void saveProperty(String key, Object object) {
        properties.putSilent(key, object);
    }
}
