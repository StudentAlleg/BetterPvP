package me.mykindos.betterpvp.balancesim.engine;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * A real, world-resident {@code ServerPlayer} with nobody on the other end of the socket.
 *
 * <p>This is the opposite of {@code HumanNPC}/{@code HumanNMS}, which are deliberately
 * <em>packet-only</em> and never added to the world because they only need to be looked at. A
 * simulation combatant needs the reverse: it has no viewer at all, but it must be a genuine
 * entity in the level so the vanilla attack path, damage pipeline, targeting, effect ticking and
 * cooldowns all engage on it. Anything less and the simulator would be measuring a model again,
 * which is the entire thing this project exists to avoid.
 *
 * <h2>Why it is not registered with {@code PlayerList}</h2>
 * The obvious way to add a player is {@code PlayerList.placeNewPlayer}, but that is the login
 * path: it fires {@code PlayerJoinEvent}, which kicks off the async, database-backed client load
 * and would write a row to {@code clients} for every fake combatant. Instead the entity is added
 * straight to the level with {@link ServerLevel#addNewPlayer}, and its ephemeral
 * {@code Client}/{@code Gamer} is registered directly into the {@code ClientManager} cache by
 * {@link SimClientFactory}. That is enough for the real managers, because
 * {@code clientManager.search().online(player)} resolves out of that cache rather than through
 * {@code Bukkit.getPlayer}.
 *
 * <p>Staying out of the player list is also the isolation win: a fake player is absent from
 * {@code Bukkit.getOnlinePlayers()}, so every per-player loop on the server -- sidebars, action
 * bars, tab list, broadcasts, scoreboard updates -- skips it without needing to be taught about
 * simulation.
 *
 * <h2>The connection</h2>
 * A {@code ServerPlayer} with a null {@code connection} NPEs the moment anything tries to send it
 * a packet, and the combat pipeline does exactly that (damage indicators, effect feedback). So it
 * gets a real {@link ServerGamePacketListenerImpl} over a {@code Connection} bound to an
 * {@link EmbeddedChannel}: packets are written, encoded and dropped into an in-memory buffer that
 * nothing ever reads. Sends are therefore harmless no-ops rather than crashes.
 */
public class SimPlayer extends ServerPlayer {

    private SimPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, ClientInformation.createDefault());
    }

    /**
     * Creates a fake player, attaches its dead-end connection and adds it to the world at
     * {@code location}.
     *
     * <p>Must be called on the main thread: it mutates level entity state.
     *
     * @param uuid     the combatant's identity, which every per-UUID system keys off
     * @param name     display name, only ever seen in logs
     * @param location where in the sim world to place it, including the facing it starts on
     * @return the spawned fake player
     */
    public static SimPlayer spawn(UUID uuid, String name, Location location) {
        final MinecraftServer server = MinecraftServer.getServer();
        final ServerLevel level = ((CraftWorld) location.getWorld()).getHandle();
        final GameProfile profile = new GameProfile(uuid, name);

        final SimPlayer player = new SimPlayer(server, level, profile);
        player.setPos(location.getX(), location.getY(), location.getZ());
        player.setYRot(location.getYaw());
        player.setXRot(location.getPitch());
        // Head yaw is what the server uses for "is the target in front of me" checks, so it has to
        // agree with the body yaw or the first swing can miss for no visible reason.
        player.setYHeadRot(location.getYaw());

        // A Connection is a Netty channel handler; constructing an EmbeddedChannel around it runs
        // the handler-added/channel-active callbacks that populate Connection.channel, which is
        // what packet sends need in order to be silently swallowed instead of NPEing.
        // Fully qualified: ServerPlayer inherits WaypointTransmitter.Connection into scope, which
        // shadows the network Connection an import would otherwise bind.
        final net.minecraft.network.Connection connection =
                new net.minecraft.network.Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        player.connection = new ServerGamePacketListenerImpl(server, connection, player,
                CommonListenerCookie.createInitial(profile, false));

        level.addNewPlayer(player);
        return player;
    }

    /** The Bukkit view, which is what every BetterPvP manager and listener actually works with. */
    public Player asBukkit() {
        return getBukkitEntity();
    }

    /**
     * Removes the fake player from the world.
     *
     * <p>{@code DISCARDED} rather than {@code KILLED}: a kill would run the death path and emit a
     * {@code PlayerDeathEvent}, which is a persistence trigger. Teardown must be silent.
     */
    public void despawn() {
        remove(Entity.RemovalReason.DISCARDED);
    }
}
