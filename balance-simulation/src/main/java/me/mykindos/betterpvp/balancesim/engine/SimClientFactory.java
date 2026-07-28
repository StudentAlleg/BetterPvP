package me.mykindos.betterpvp.balancesim.engine;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.champions.Champions;
import me.mykindos.betterpvp.champions.champions.builds.BuildManager;
import me.mykindos.betterpvp.champions.champions.builds.GamerBuilds;
import me.mykindos.betterpvp.champions.champions.builds.RoleBuild;
import me.mykindos.betterpvp.core.client.Client;
import me.mykindos.betterpvp.core.client.Rank;
import me.mykindos.betterpvp.core.client.gamer.Gamer;
import me.mykindos.betterpvp.core.client.repository.ClientManager;
import me.mykindos.betterpvp.core.components.champions.Role;
import me.mykindos.betterpvp.core.utilities.SnowflakeIdGenerator;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

/**
 * Gives a fake player the {@code Client}/{@code Gamer} pair the real managers require, without
 * touching the database.
 *
 * <p>This is the answer to design open question 2 -- how much of the {@code ClientManager} join
 * flow can be bypassed. It turns out: all of it. The normal login path
 * ({@code loadOnline} -> {@code ClientSQLLayer.getAndUpdate} / {@code create}) exists to fetch or
 * insert a persisted row, but nothing downstream depends on having been through it. What the rest
 * of the codebase actually calls is {@code clientManager.search().online(player)}, and that
 * resolves through {@code PlayerManager.getStoredExact} -- a lookup in the in-memory Caffeine
 * cache. So publishing a hand-built {@code Client} into that cache with
 * {@link ClientManager#load} makes it indistinguishable from a logged-in one to
 * {@code RoleManager}, {@code BuildManager}, the skill listeners and the damage pipeline, while
 * never going near {@code ClientSQLLayer}.
 *
 * <p>Two consequences worth stating, because they are load-bearing rather than incidental:
 * <ul>
 *   <li>No {@code save} call is made anywhere here, so no row appears in {@code clients} or
 *       {@code gamers}. {@code ClientSQLLayer.create} would have written one; that is precisely
 *       why the client is built directly instead of asked for.</li>
 *   <li>{@code Client.isLoaded()} tests {@code Bukkit.getPlayer(uuid)}, and a fake player is not
 *       in the player list ({@link SimPlayer}), so it reports false. That is desirable, not a
 *       defect: {@code ClientManager.getOnline()} filters on it, so simulated clients are
 *       invisible to every "for each online client" sweep on the server. It is <em>not</em>
 *       sufficient on its own, though -- the periodic property and stat flushes iterate the
 *       loaded set rather than the online one, which is what {@link SimClient} closes off.</li>
 * </ul>
 */
@Singleton
@CustomLog
public class SimClientFactory {

    private final ClientManager clientManager;
    private final BuildManager buildManager;

    @Inject
    public SimClientFactory(ClientManager clientManager) {
        this.clientManager = clientManager;
        // Pulled from Champions' injector rather than injected, matching RoleSelectorManager and
        // HotBarLayoutManager in the game module. BuildManager depends on the Champions plugin
        // instance, which is bound only inside Champions' own child injector; this one is a
        // sibling under Core, so injecting it here makes Guice try to construct a second Champions
        // ("Plugin already initialized!"). Binding Champions locally would fix the crash and
        // introduce a worse bug -- a second BuildManager singleton, so builds registered for a
        // fake player would land in a different map from the one the champions listeners read.
        this.buildManager = JavaPlugin.getPlugin(Champions.class).getInjector().getInstance(BuildManager.class);
    }

    /**
     * Builds an ephemeral client for a combatant, publishes it to the manager's cache, and gives
     * it the builds the champions listeners assume every player has.
     *
     * @param player the spawned fake player this client belongs to
     * @param name   the combatant's name, only used in logs
     * @return the registered client
     */
    public Client create(Player player, String name) {
        final UUID uuid = player.getUniqueId();
        final long id = SnowflakeIdGenerator.ID_GENERATOR.nextId();
        final Gamer gamer = new SimGamer(id, player);
        // SimClient, not Client: a plain one would queue property and stat writes keyed on a
        // clients row that does not exist. See that class for why the block lives there.
        final Client client = new SimClient(id, gamer, uuid.toString(), name, Rank.PLAYER);

        // load() only puts the client into the cache. Deliberately not clientManager.save(), and
        // deliberately not the loadOnline() path, either of which would reach the database.
        clientManager.load(client);
        registerBuilds(client, uuid);
        return client;
    }

    /**
     * Registers an empty build per role, so {@code buildManager.getObject(uuid)} resolves.
     *
     * <p>{@code BuildManager.loadBuilds} is unusable here on two counts: it reads the player's
     * builds out of the database, and its {@code loadDefaultBuilds} fallback both <em>writes</em>
     * the generated builds back ({@code BuildRepository.save}) and equips a real skill loadout.
     * Either would break phase 1 -- the first pollutes the database the simulator is supposed to
     * only ever append results to, the second silently arms Sever, Leap, Vengeance and the rest on
     * combatants whose whole point is to measure plain melee.
     *
     * <p>So the builds are constructed directly and left skill-less, which matches what
     * {@code BalanceCatalog} enumerates for phase 1: {@code RoleBuild.getActiveSkills()} returns an
     * empty list, so the stat listeners iterate nothing rather than dereferencing null.
     *
     * <p>TODO(phase 2): populate these from the matchup's {@code SimBuildSpec} instead of leaving
     * them empty, which is what makes the catalog's skill axis actually reach the pipeline.
     */
    private void registerBuilds(Client client, UUID uuid) {
        final GamerBuilds builds = new GamerBuilds(client);
        for (Role role : Role.values()) {
            final RoleBuild build = new RoleBuild(client.getId(), uuid, role, 1);
            build.setActive(true);
            builds.getBuilds().add(build);
            // Keyed by role name: this is the map SkillStatListener reads on every kill, death and
            // role change, and a missing entry there is the NoSuchElement/NPE it cannot survive.
            builds.getActiveBuilds().put(role.getName(), build);
        }
        buildManager.addObject(uuid.toString(), builds);
    }

    /**
     * Drops a combatant's client and builds at teardown.
     *
     * <p>{@code ClientManager.unload} refuses while {@code Bukkit.getPlayer(uuid)} is non-null,
     * which never applies to a fake player, so it is safe here -- but it is called only after the
     * entity is despawned so the ordering matches a real logout.
     */
    public void destroy(Client client) {
        buildManager.removeObject(client.getUuid());
        clientManager.unload(client);
    }
}
