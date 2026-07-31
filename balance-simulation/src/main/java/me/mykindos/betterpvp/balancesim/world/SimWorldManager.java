package me.mykindos.betterpvp.balancesim.world;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.balancesim.BalanceSimulation;
import me.mykindos.betterpvp.balancesim.SimulationGate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Owns the dedicated void world that duels run in, and hands out arena slots inside it.
 *
 * <p>A separate world rather than a far-away corner of an existing one: world-scanning
 * listeners can then be excluded by world name instead of by distance heuristics, and nothing
 * a movement skill or projectile does can collide with real terrain.
 *
 * <p><b>Scaffolding.</b> World creation and teardown are implemented, arena allocation is not.
 */
@Singleton
@CustomLog
public class SimWorldManager {

    /**
     * Arenas are laid out on a grid this many blocks apart, well beyond any skill's range.
     *
     * <p>Sized against the world's view distance rather than against nothing. 64 blocks is four
     * chunks, and {@link #shrinkTrackingDistances} pins view and simulation distance at 2, so no
     * duel can see or tick another. The old 256 was chosen when arenas were allocated per duel and
     * isolation was the only concern; now that {@code SimCombatantPool} keeps combatants resident,
     * the live arena count is a multiple of the concurrency rather than equal to it, and every live
     * arena holds its chunks loaded. At 64 the same number of platforms occupies a sixteenth of the
     * area, which is the difference between a sweep touching a few hundred chunks and the sprawl
     * that exhausted the heap before arenas were recycled at all.
     */
    private static final int ARENA_SPACING = 64;
    private static final int ARENA_Y = 64;

    /**
     * Offset from the grid step to the middle of a chunk, so an arena sits inside one chunk rather
     * than on the corner where four meet.
     *
     * <p>{@link #ARENA_SPACING} is a multiple of 16, so an unshifted centre lands on a chunk
     * boundary and a square platform around it straddles a 2x2 block of chunks. Every ground probe
     * near the middle of the arena then has a chance of crossing into a neighbouring chunk, which is
     * the difference between a block read that hits the cache and one that loads a chunk.
     */
    private static final int CHUNK_CENTRE_OFFSET = 8;

    /**
     * Half-width of the square platform each duel is fought on.
     *
     * <p>Sized so that everything a skill is likely to probe lands on solid ground. It was 8, giving
     * a 17x17 pad, and the profile of run 142 is what that cost: in a void world a probe that misses
     * the pad finds nothing solid beneath it, so {@code UtilLocation.getClosestSurfaceBlock} walks
     * its full height budget through empty chunks and {@code UtilBlock.isGrounded} reads four corners
     * that are not there. Every one of those reads was a {@code getBlockState} on a chunk that had to
     * be loaded and generated first, synchronously, on the main thread.
     *
     * <p>16 covers the arena's whole centre chunk and eight blocks into each neighbour, at a
     * one-off cost of 33x33 rather than 17x17 barrier placements per slot.
     */
    private static final int PLATFORM_RADIUS = 16;

    /**
     * How many chunks either side of an arena's own chunk are pinned loaded while a sweep runs.
     *
     * <p>Deliberately equal to the view distance {@link #shrinkTrackingDistances} sets, so pinning
     * adds no chunk that the combatants standing there were not already keeping loaded. The ticket
     * does not widen the resident set; it stops it <em>churning</em>.
     *
     * <p>That churn was the whole cost. Without a ticket a chunk is held only by the fake players
     * near it, so it unloads and reloads as combatants die, respawn and are recycled -- and every
     * reload runs a full chunk status change, which re-registers every entity in the chunk with
     * every player in the level. In run 142 that path,
     * {@code ChunkEntitySlices.updateStatus -> entityStatusChange -> onTrackingStart ->
     * ChunkMap.addEntity -> TrackedEntity.updatePlayers}, was 52% of the entire server thread,
     * because {@code updatePlayers} is linear in resident players and 600 of them were resident.
     */
    private static final int ARENA_TICKET_CHUNK_RADIUS = 2;

    /** How far each combatant spawns from the arena centre, so they start a couple of swings apart. */
    private static final int SPAWN_OFFSET = 2;

    private final SimulationGate gate;

    /**
     * The plugin the chunk tickets are held under, so they can be released as a set at teardown.
     *
     * <p>Plugin tickets rather than {@code setForceLoaded}: force-load is world state that survives
     * a crash and would leave a dev server pinning a sim world's chunks forever, whereas a plugin
     * ticket dies with the plugin whatever happens to this class.
     */
    private final BalanceSimulation plugin;

    /**
     * Arena indices whose duel has finished, ready to be handed out again.
     *
     * <p>A {@link LinkedHashSet} rather than a queue so releasing the same slot twice is harmless.
     * Two duels sharing a platform would be silent corruption -- combatants within swinging range
     * of the wrong opponent -- so the structure refuses the duplicate rather than trusting every
     * caller to release exactly once.
     */
    private final Set<Integer> freeIndices = new LinkedHashSet<>();

    /** Indices whose platform has already been laid, so the blocks are placed once per slot. */
    private final Set<Integer> prepared = new HashSet<>();

    private int nextIndex;

    @Nullable
    private World world;

    @Inject
    public SimWorldManager(SimulationGate gate, BalanceSimulation plugin) {
        this.gate = gate;
        this.plugin = plugin;
    }

    /**
     * Returns the sim world, creating it if this is the first call.
     *
     * <p>Must be called on the main thread -- {@link Bukkit#createWorld} is not thread safe.
     *
     * @throws IllegalStateException if the simulation gate is closed
     */
    public World getOrCreate() {
        if (!gate.isEnabled()) {
            throw new IllegalStateException("Simulation is disabled; refusing to create the sim world");
        }
        if (world != null) {
            return world;
        }

        final World existing = Bukkit.getWorld(gate.getWorldName());
        if (existing != null) {
            world = existing;
            return world;
        }

        log.info("Creating simulation void world '{}'", gate.getWorldName()).submit();
        world = new WorldCreator(gate.getWorldName())
                .generator(new VoidChunkGenerator())
                .type(WorldType.FLAT)
                .generateStructures(false)
                .createWorld();

        if (world == null) {
            throw new IllegalStateException("Failed to create simulation world " + gate.getWorldName());
        }

        world.setAutoSave(false);
        shrinkTrackingDistances(world);
        return world;
    }

    /**
     * Collapses the world's view and simulation distances to the minimum.
     *
     * <p>Nobody is looking at a sim combatant: {@link me.mykindos.betterpvp.balancesim.engine.SimPlayer}
     * writes its packets into an {@code EmbeddedChannel} that nothing reads, and the fake players
     * are absent from {@code Bukkit.getOnlinePlayers()} so no real client tracks them either. The
     * default distances therefore buy nothing and cost a great deal: Moonrise keeps a per-player
     * area map whose update cost scales with the square of the view distance, and the sweep adds
     * and removes two players from the level for every duel it runs.
     *
     * <p>A 641-second profile of a 128-concurrency sweep put 6.2% of the entire server thread in
     * {@code NearbyPlayers.addPlayer} under {@code SimPlayer.spawn} and a further 7.2% in
     * {@code NearbyPlayers.removePlayer} under {@code SimPlayer.despawn} -- 13% of all main-thread
     * time spent maintaining a view of the world that has no viewer. At the default distance of 10
     * each add or remove walks 21x21 chunks; at 2 it walks 5x5.
     *
     * <p>Two rather than one because the arenas are {@link #ARENA_SPACING} blocks apart -- four
     * chunks -- so the smallest workable radius still leaves each duel isolated, and combatants
     * remain in a ticking chunk, which they must for the damage pipeline to engage at all.
     *
     * <p>Note that this is the only safe lever for shrinking chunk tracking. The obvious bigger
     * hammer, {@code -DPaper.MaxViewDistance}, is not: Moonrise sizes
     * {@code ParallelSearchRadiusIteration}'s table at {@code MAX_VIEW_DISTANCE + 3} but indexes it
     * with a chunk-generation neighbour radius that has nothing to do with view distance and reaches
     * 10. Setting the property below 8 therefore hard-crashes the chunk system the first time any
     * chunk generates -- which, in a freshly created sim world, is immediately.
     */
    private void shrinkTrackingDistances(World simWorld) {
        simWorld.setViewDistance(2);
        simWorld.setSimulationDistance(2);
        simWorld.setSendViewDistance(2);
    }

    /**
     * Whether the given world is the simulation world. Listeners that must not observe sim
     * activity (stats persistence, leaderboards, world scanners) gate on this.
     */
    public boolean isSimWorld(@Nullable World candidate) {
        return candidate != null && candidate.getName().equals(gate.getWorldName());
    }

    /**
     * The centre of the {@code index}-th arena, on a grid so concurrent duels cannot reach
     * each other.
     */
    public Location arenaCentre(int index) {
        final int columns = 32;
        // Shifted to the middle of a chunk rather than the corner four of them share -- see
        // CHUNK_CENTRE_OFFSET. The grid step is unchanged, so arenas stay ARENA_SPACING apart.
        final int x = (index % columns) * ARENA_SPACING + CHUNK_CENTRE_OFFSET;
        final int z = (index / columns) * ARENA_SPACING + CHUNK_CENTRE_OFFSET;
        return new Location(getOrCreate(), x + 0.5, ARENA_Y, z + 0.5);
    }

    /**
     * Takes an arena for a duel that is starting, reusing one a finished duel has released.
     *
     * <p>Slots must be recycled rather than allocated per duel. Each index is a fresh 256-block
     * step across the world, so numbering them by duel walked the sweep into virgin terrain
     * forever: an 864-duel run spanned roughly 8000x7000 blocks, and every arena it ever built
     * stayed resident because the platform blocks kept the chunks loaded. That is what exhausted
     * the heap -- the result rows are flushed to the database in batches and never amounted to
     * more than a few hundred kilobytes. It also cost a synchronous chunk generation on the main
     * thread for every single duel, which is what the watchdog caught mid-{@code getBlockAt}.
     *
     * <p>Bounded by concurrency instead, the live arena count never exceeds the number of duels in
     * flight, so a sweep of any length touches the same handful of chunks it did in its first
     * second.
     */
    public ArenaSlot acquireArena() {
        final Integer recycled = freeIndices.isEmpty() ? null : freeIndices.iterator().next();
        if (recycled != null) {
            freeIndices.remove(recycled);
        }
        return prepareArena(recycled != null ? recycled : nextIndex++);
    }

    /**
     * Returns a finished duel's arena to the pool.
     *
     * <p>The platform is left standing, and its chunk tickets with it: it is barrier blocks in a
     * void world that the next duel would only lay again, and the slot is about to be handed out
     * again anyway. Dropping the ticket here would mean unloading a chunk that is reloaded seconds
     * later, which is the churn {@link #pinChunks} exists to prevent.
     */
    public void releaseArena(ArenaSlot slot) {
        freeIndices.add(slot.index());
    }

    /**
     * Lays a solid platform for the {@code index}-th arena and returns the two spawn points on
     * it, facing each other. Must be called on the main thread ({@link Block#setType} touches
     * world state).
     *
     * <p>The platform is barrier blocks so nothing renders and nothing can be mined, one layer
     * below the spawn Y. The two combatants start {@link #SPAWN_OFFSET} blocks either side of the
     * centre along the X axis, each yawed to look at the other, so the very first melee swing has
     * a valid target without the orchestrator having to path them together first.
     *
     * <p>The block loop runs only the first time a slot is used. A recycled arena is still handed
     * back as a freshly built {@link ArenaSlot}: the spawn points are mutable {@code Location}s,
     * and handing the same instances to successive duels would let anything that moved one corrupt
     * every later duel on that platform.
     */
    public ArenaSlot prepareArena(int index) {
        final Location centre = arenaCentre(index);
        final World simWorld = centre.getWorld();

        if (prepared.add(index)) {
            // Pinned before a single block is touched. Laying the platform is itself thousands of
            // block writes across nine chunks, and without the ticket the first of them loads a
            // chunk that a later one may find unloaded again.
            pinChunks(simWorld, centre);

            final int floorY = ARENA_Y - 1;
            final int cx = centre.getBlockX();
            final int cz = centre.getBlockZ();
            for (int dx = -PLATFORM_RADIUS; dx <= PLATFORM_RADIUS; dx++) {
                for (int dz = -PLATFORM_RADIUS; dz <= PLATFORM_RADIUS; dz++) {
                    final Block block = simWorld.getBlockAt(cx + dx, floorY, cz + dz);
                    if (block.getType() != Material.BARRIER) {
                        block.setType(Material.BARRIER, false);
                    }
                }
            }
        }

        // West combatant looks east (yaw -90), east combatant looks west (yaw 90).
        final Location west = new Location(simWorld, centre.getX() - SPAWN_OFFSET, ARENA_Y, centre.getZ(), -90f, 0f);
        final Location east = new Location(simWorld, centre.getX() + SPAWN_OFFSET, ARENA_Y, centre.getZ(), 90f, 0f);
        return new ArenaSlot(index, centre, west, east);
    }

    /**
     * Holds the chunks around an arena loaded for as long as the plugin is enabled.
     *
     * <p>Once per slot, at the point the platform is laid, because arenas are recycled rather than
     * retired -- a slot handed back by {@code releaseArena} will be handed straight out again, so
     * releasing its ticket in between would reintroduce exactly the load/unload cycle this exists to
     * stop. They are released together in {@link #teardown()}.
     *
     * <p>{@code addPluginChunkTicket} is idempotent per (chunk, plugin), so a slot rebuilt after a
     * teardown re-pins without accumulating anything.
     */
    private void pinChunks(World simWorld, Location centre) {
        final int chunkX = centre.getBlockX() >> 4;
        final int chunkZ = centre.getBlockZ() >> 4;
        for (int dx = -ARENA_TICKET_CHUNK_RADIUS; dx <= ARENA_TICKET_CHUNK_RADIUS; dx++) {
            for (int dz = -ARENA_TICKET_CHUNK_RADIUS; dz <= ARENA_TICKET_CHUNK_RADIUS; dz++) {
                simWorld.addPluginChunkTicket(chunkX + dx, chunkZ + dz, plugin);
            }
        }
    }

    /**
     * One duel's fighting space: the platform centre and the two facing spawn points.
     *
     * @param index  the arena's grid index
     * @param centre platform centre
     * @param spawnA the first combatant's spawn, looking at {@code spawnB}
     * @param spawnB the second combatant's spawn, looking at {@code spawnA}
     */
    public record ArenaSlot(int index, Location centre, Location spawnA, Location spawnB) {
    }

    /**
     * Unloads the sim world without saving. Called on plugin disable and at run teardown so a
     * reload does not inherit a half-built arena.
     */
    public void teardown() {
        if (world == null) {
            return;
        }
        log.info("Unloading simulation world '{}'", world.getName()).submit();
        // Before the unload, not after: a world holding plugin tickets will not unload, and after
        // the call there is no World left to release them against.
        world.removePluginChunkTickets(plugin);
        Bukkit.unloadWorld(world, false);
        world = null;
        // The pool describes blocks in a world that no longer exists. Keeping it would hand the
        // next run an index it believes is already built, in a freshly generated void.
        freeIndices.clear();
        prepared.clear();
        nextIndex = 0;
    }
}
