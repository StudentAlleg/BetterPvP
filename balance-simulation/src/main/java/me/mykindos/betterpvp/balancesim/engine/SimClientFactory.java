package me.mykindos.betterpvp.balancesim.engine;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.balancesim.catalog.SimBuildSpec;
import me.mykindos.betterpvp.balancesim.catalog.SimSkillAllocation;
import me.mykindos.betterpvp.champions.Champions;
import me.mykindos.betterpvp.champions.champions.builds.BuildManager;
import me.mykindos.betterpvp.champions.champions.builds.GamerBuilds;
import me.mykindos.betterpvp.champions.champions.builds.RoleBuild;
import me.mykindos.betterpvp.champions.champions.skills.ChampionsSkillManager;
import me.mykindos.betterpvp.champions.champions.skills.Skill;
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
 *   <li>A simulated client must never report as loaded: {@code ClientManager.getOnline()} filters on
 *       it, so that is what keeps simulated clients out of every "for each online client" sweep on the
 *       server. Phase 2 got this for free, because the inherited {@code Client.isLoaded()} asks
 *       {@code Bukkit.getPlayer(uuid)} and a fake player was in neither of {@code PlayerList}'s player
 *       structures. Phase 3 put a fighting combatant into the lookup map so that active skills can
 *       resolve their holders ({@link SimPlayer}), so {@link SimClient} now overrides
 *       {@code isLoaded()} to false outright. Either way it was never sufficient on its own -- the
 *       periodic property and stat flushes iterate the loaded set rather than the online one, which is
 *       the other thing {@link SimClient} closes off.</li>
 * </ul>
 */
@Singleton
@CustomLog
public class SimClientFactory {

    private final ClientManager clientManager;
    private final BuildManager buildManager;
    private final ChampionsSkillManager skillManager;

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
        final var championsInjector = JavaPlugin.getPlugin(Champions.class).getInjector();
        this.buildManager = championsInjector.getInstance(BuildManager.class);
        this.skillManager = championsInjector.getInstance(ChampionsSkillManager.class);
    }

    /**
     * Builds an ephemeral client for a combatant, publishes it to the manager's cache, and gives
     * it the builds the champions listeners assume every player has.
     *
     * @param player the spawned fake player this client belongs to
     * @param name   the combatant's name, only used in logs
     * @param build  the loadout this combatant is measuring; its skills are written into the
     *               matching role's {@code RoleBuild}
     * @return the registered client
     */
    public Client create(Player player, String name, SimBuildSpec build) {
        final UUID uuid = player.getUniqueId();
        final long id = SnowflakeIdGenerator.ID_GENERATOR.nextId();
        final Gamer gamer = new SimGamer(id, player);
        // SimClient, not Client: a plain one would queue property and stat writes keyed on a
        // clients row that does not exist. See that class for why the block lives there.
        final Client client = new SimClient(id, gamer, uuid.toString(), name, Rank.PLAYER);

        // load() only puts the client into the cache. Deliberately not clientManager.save(), and
        // deliberately not the loadOnline() path, either of which would reach the database.
        clientManager.load(client);
        registerBuilds(client, uuid, build);
        return client;
    }

    /**
     * Registers one build per role, with the spec's skills written into the role it belongs to.
     *
     * <p>{@code BuildManager.loadBuilds} is unusable here on two counts: it reads the player's
     * builds out of the database, and its {@code loadDefaultBuilds} fallback both <em>writes</em>
     * the generated builds back ({@code BuildRepository.save}) and equips an arbitrary skill
     * loadout. The first pollutes the database the simulator is supposed to only ever append
     * results to; the second would silently overwrite the loadout the sweep is measuring.
     *
     * <p>A build exists for <em>every</em> role, not just the one being measured, because
     * {@code SkillStatListener} and {@code SkillListener.onRoleChange} look the map up by role name
     * on every role change and a missing entry is the {@code NoSuchElement}/NPE they cannot
     * survive -- and the combatant passes through a second role on spawn (see
     * {@code SimCombatant.equipRole}). Only the spec's own role is populated; the rest stay empty,
     * so {@code getActiveSkills()} on them iterates nothing.
     *
     * <p>Points are taken as they are spent, so {@code RoleBuild.getPoints()} reports the same
     * remaining budget a real player's build would. Nothing in the damage path reads it, but the
     * build menus and validators do, and leaving it at 12 on a fully-spent build would make the
     * fake player's state a thing no real player could be in.
     */
    private void registerBuilds(Client client, UUID uuid, SimBuildSpec spec) {
        final Role specRole = Role.valueOf(spec.role());
        final GamerBuilds builds = new GamerBuilds(client);
        for (Role role : Role.values()) {
            final RoleBuild build = new RoleBuild(client.getId(), uuid, role, 1);
            build.setActive(true);
            if (role == specRole) {
                applySkills(build, spec);
            }
            builds.getBuilds().add(build);
            // Keyed by role name: this is the map SkillStatListener reads on every kill, death and
            // role change, and a missing entry there is the NoSuchElement/NPE it cannot survive.
            builds.getActiveBuilds().put(role.getName(), build);
        }
        buildManager.addObject(uuid, builds);
    }

    /**
     * Writes the spec's allocation onto a {@link RoleBuild} through {@code setSkill}, the same
     * mutator the build menu uses.
     *
     * <p>The {@code Skill} instances come from {@code ChampionsSkillManager}, so a build holds the
     * live Guice singletons the listeners dispatch against -- a copy would be equal to nothing and
     * {@code Skill.getSkill} compares by identity through {@code equals}.
     */
    private void applySkills(RoleBuild build, SimBuildSpec spec) {
        for (SimSkillAllocation allocation : spec.skills()) {
            final Skill skill = skillManager.getObject(allocation.skillName()).orElseThrow(() ->
                    new IllegalStateException("Catalog named a skill that is not registered: "
                            + allocation.skillName()));
            build.setSkill(skill.getType(), skill, allocation.allocatedLevel());
            build.takePoints(allocation.allocatedLevel());
        }
    }

    /**
     * Drops a combatant's client and builds at teardown.
     *
     * <p>{@code ClientManager.unload} refuses while {@code Bukkit.getPlayer(uuid)} is non-null, and
     * since phase 3 a <em>fighting</em> combatant is resolvable that way ({@link SimPlayer}). So this
     * is only correct after {@code SimCombatant.despawn} has dropped the lookup registration, which is
     * the order it calls them in; reverse them and the client silently survives in the manager's cache
     * until the next duel on that slot overwrites it.
     */
    public void destroy(Client client) {
        buildManager.removeObject(client.getUniqueId());
        clientManager.unload(client);
    }
}
