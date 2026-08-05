package me.mykindos.betterpvp.balancesim.world;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import me.mykindos.betterpvp.balancesim.BalanceSimulation;
import me.mykindos.betterpvp.balancesim.SimulationGate;
import me.mykindos.betterpvp.core.utilities.UtilServer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Difficulty;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.entity.SpawnCategory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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
     * <p>Four chunks. <strong>Set empirically -- widening it has been tried and measured, and it lost
     * on every number that was checked.</strong> Read the rest of this comment before changing it.
     *
     * <p>The path this constant is usually reasoned about is spawn tracking.
     * {@code ChunkTickConstants.PLAYER_SPAWN_TRACK_RANGE} is a hardcoded 8 chunks: every chunk keeps
     * a list of players within 128 blocks, and {@code ChunkMap.collectSpawningChunks} walks
     * player-ticking chunks against that list every tick, calling {@code isChunkNearPlayer} on each.
     * Nothing {@link #disableNaturalSpawning} sets can reach it -- the collection runs outside the
     * {@code SPAWN_MOBS} check in {@code ServerChunkCache.tickChunks}, and the radius is a constant
     * rather than a function of {@code mobSpawnRange} or view distance.
     *
     * <h2>What was measured</h2>
     *
     * <p>The argument for widening is that a sparser grid puts fewer players in each chunk's list,
     * so each {@code isChunkNearPlayer} scan is shorter. That is true and it is not what happens to
     * the total. Spacing was raised to 144 -- nine chunks, so no two arenas share anything -- and a
     * profile of the resulting run came out worse on both halves of the product:
     *
     * <ul>
     *   <li>{@code collectSpawningChunks} (which is essentially all {@code isChunkNearPlayer}) went
     *       from about 4.3% of the server thread at 64 to about 6.7% of simulation time at 144. The
     *       shorter per-chunk scan did not pay for itself, plausibly because the predicate answers
     *       "is <em>any</em> player near" and returns on the first hit -- which is immediate on a
     *       dense grid and a full miss-scan on a sparse one. That mechanism is inferred, not proven;
     *       the two percentages are the part that was actually observed.</li>
     *   <li>Resident chunks went from roughly 6,000 to 22,780. Simulation distance 2 gives each arena
     *       a 5x5 ticking footprint and view distance a wider loaded one; at a four-chunk step those
     *       overlap and dedupe, at a nine-chunk step they stop touching. Most of that difference is
     *       loaded-not-ticking, so it costs warm-up generation and memory rather than per-tick
     *       sweeps, but {@code iterateTickingChunksFaster} pays for part of it every tick.</li>
     * </ul>
     *
     * <p>So: do not widen this on the strength of an {@code isChunkNearPlayer} percentage alone. The
     * numbers that decide it are that percentage <em>and</em> the resident chunk count, together, from
     * a run at each spacing.
     *
     * <p>Unrelated to any of the above: the original 256 came down to 64 because arena <em>count</em>
     * was unbounded -- an index per duel, never reused -- and exhausted the heap.
     * {@link #acquireArena} recycling slots fixed that independently of spacing.
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

    /** Indices whose chunks are resident and whose platform has been laid. */
    private final Set<Integer> prepared = new HashSet<>();

    /**
     * Indices whose chunk loads are in flight, so a second {@link #prepareAsync} for the same slot
     * does not start a duplicate set of loads while the first is still landing.
     */
    private final Set<Integer> preparing = new HashSet<>();

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

        // A sim world left on disk by an earlier run is loaded by the server at startup, before this
        // class ever sees it. Tuning has to be reapplied on adoption rather than only at creation:
        // view distance, spawn limits and the difficulty are all world state that came back from the
        // region folder at its default, so a server that had run a sweep before would silently
        // profile as though none of this existed.
        final World existing = Bukkit.getWorld(gate.getWorldName());
        if (existing != null) {
            world = existing;
            world.setAutoSave(false);
            shrinkTrackingDistances(world);
            disableNaturalSpawning(world);
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
        disableNaturalSpawning(world);
        return world;
    }

    /**
     * Stops the world attempting natural mob spawns, which it can never complete.
     *
     * <p>The arenas are barrier platforms in a void world, so every spawn candidate the server
     * generates is rejected by {@code NaturalSpawner.isValidSpawnPostitionForType}. The rejection is
     * not the expensive part -- reaching it is. {@code spawnCategoryForPosition} calls
     * {@code EntityGetter.getNearestPlayer} first, and that walks {@code level.players()} linearly.
     * Every fake player in every live arena is in that list, so the per-tick cost is chunks x spawn
     * attempts x resident combatants: quadratic in sweep concurrency, for a result that is always
     * "nothing spawned".
     *
     * <p>A 603-second profile put 43% of the entire server thread in this path, 36.8% of it in
     * {@code getNearestPlayer} alone -- more than the damage pipeline the sweep exists to measure.
     *
     * <p>The gamerule and the spawn limits are both needed, and they cut the path at different
     * depths. {@link GameRules#SPAWN_MOBS} stops the category loop inside
     * {@code NaturalSpawner.spawnForChunk}; zeroing the limits empties the spawn state that loop
     * is built from, so nothing downstream finds a category to spawn for.
     * {@link SpawnCategory#MISC} is excluded because {@link World#setSpawnLimit} rejects it.
     *
     * <p>Neither reaches {@code ChunkMap.collectSpawningChunks}, and no world setting does. In
     * {@code ServerChunkCache.tickChunks} that call sits <em>outside</em> the
     * {@code SPAWN_MOBS && (spawnEnemies || spawnFriendlies)} branch, so it walks every
     * player-ticking chunk every tick whatever this method does; the earlier claim here that the
     * zeroed limits stopped it was wrong, and a later profile still found 4.3% of the server
     * thread underneath it. It is not addressed here at all: as {@link #ARENA_SPACING} explains,
     * the total is (players x chunks within 8 of a player) however the grid is laid out, so the
     * only real levers on it are the resident player count and the resident chunk count.
     *
     * <p>Peaceful difficulty is belt and braces: it also suppresses the hostile-mob paths that do
     * not consult the spawn state at all, and no sim damage comes from mobs.
     */
    private void disableNaturalSpawning(World simWorld) {
        simWorld.setGameRule(GameRules.SPAWN_MOBS, false);
        simWorld.setDifficulty(Difficulty.PEACEFUL);
        for (SpawnCategory category : SpawnCategory.values()) {
            if (category == SpawnCategory.MISC) {
                continue;
            }
            simWorld.setSpawnLimit(category, 0);
        }
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
     * <p>Slots must be recycled rather than allocated per duel. Each index is a fresh
     * {@link #ARENA_SPACING} step across the world, so numbering them by duel walked the sweep into virgin terrain
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
    public @Nullable ArenaSlot acquireArena() {
        final Integer recycled = freeIndices.isEmpty() ? null : freeIndices.iterator().next();
        if (recycled != null) {
            freeIndices.remove(recycled);
            return slotAt(recycled);
        }

        // Never built here. Preparing an arena means generating up to
        // (2 * ARENA_TICKET_CHUNK_RADIUS + 1)^2 chunks, and the only synchronous way to do that is
        // getChunkAt, which blocks the main thread inside ServerChunkCache.syncLoad until the
        // generator finishes. That is what put single ticks into the seconds: a 1439-second profile
        // measured a 14.2-second worst tick against a 50.8ms median, with 51 seconds of syncLoad
        // underneath acquireArena. A sweep that stalls the tick it is measuring cannot measure it.
        //
        // So growth past the warmed set is a request, not a build. The caller already has a
        // "nothing available this tick" path -- SimCombatantPool.acquire is documented to return
        // null and SweepLoop.fill breaks on it -- and postponing a duel by a tick costs nothing,
        // because the matchup is not polled until a slot is in hand.
        final int index = nextIndex;
        if (!prepared.contains(index)) {
            prepareAsync(index);
            return null;
        }
        nextIndex++;
        return slotAt(index);
    }

    /**
     * Builds every arena a sweep can ask for, before the sweep starts.
     *
     * <p>The chunk generation has to happen somewhere, and the only question is whether it lands
     * inside the measurement. Doing it up front costs a warmup that is not being timed; doing it
     * lazily costs multi-second stalls scattered through the run, which is both slower overall and
     * ruins the tick-rate signal the sweep exists to produce.
     *
     * <p>Sized to the sweep's concurrency because that is the ceiling on arenas in flight -- a
     * finished duel's slot goes back to {@link #freeIndices} and is handed straight out again, so
     * a run of any length reuses this set rather than growing past it.
     *
     * @param arenaCount how many arenas to build, normally the sweep's max concurrent duels
     * @return a future completing on the main thread once every arena is ready to duel on
     */
    public CompletableFuture<Void> warmUp(int arenaCount) {
        final CompletableFuture<?>[] pending = new CompletableFuture<?>[arenaCount];
        for (int index = 0; index < arenaCount; index++) {
            pending[index] = prepareAsync(index);
        }
        // nextIndex is deliberately not advanced. It is the next index to hand out, not the next
        // to build, and warming a slot is exactly what makes it handable -- moving it here would
        // step the sweep straight past every arena this method just built.
        return CompletableFuture.allOf(pending);
    }

    /**
     * Loads the {@code index}-th arena's chunks off the main thread, then lays its platform on it.
     *
     * <p>{@link World#getChunkAtAsync} rather than {@link World#getChunkAt}: both generate the
     * chunk if it does not exist, but only the former hands the work to the chunk system's threads
     * and calls back when it lands. The platform loop and the ticket registration still run on the
     * main thread -- they touch world state -- but by then every chunk they touch is resident, so
     * neither can fault one in.
     *
     * @return a future completing once the arena is ready, already complete if it is
     */
    private CompletableFuture<Void> prepareAsync(int index) {
        if (prepared.contains(index)) {
            return CompletableFuture.completedFuture(null);
        }
        if (!preparing.add(index)) {
            // Loads already in flight for this slot; the first call will lay the platform.
            return CompletableFuture.completedFuture(null);
        }

        final Location centre = arenaCentre(index);
        final World simWorld = centre.getWorld();
        final int chunkX = centre.getBlockX() >> 4;
        final int chunkZ = centre.getBlockZ() >> 4;

        final List<CompletableFuture<Chunk>> loads = new ArrayList<>();
        for (int dx = -ARENA_TICKET_CHUNK_RADIUS; dx <= ARENA_TICKET_CHUNK_RADIUS; dx++) {
            for (int dz = -ARENA_TICKET_CHUNK_RADIUS; dz <= ARENA_TICKET_CHUNK_RADIUS; dz++) {
                loads.add(simWorld.getChunkAtAsync(chunkX + dx, chunkZ + dz, true));
            }
        }

        final CompletableFuture<Void> ready = new CompletableFuture<>();
        CompletableFuture.allOf(loads.toArray(new CompletableFuture[0])).whenComplete((ignored, throwable) -> {
            // Back onto the main thread: the callback fires on whichever thread completed the last
            // load, and everything below this line touches world state.
            UtilServer.runTask(plugin, () -> {
                preparing.remove(index);
                if (throwable != null) {
                    log.error("Failed loading chunks for sim arena {}", index, throwable).submit();
                    ready.completeExceptionally(throwable);
                    return;
                }
                // A teardown between the load starting and landing leaves this holding a World that
                // is no longer the sim world -- unloaded, and about to be regenerated from scratch
                // if another run starts. Building into it would pin chunks in a dead world and lay
                // a platform the next run cannot see, so the load is simply dropped.
                if (world != simWorld) {
                    ready.complete(null);
                    return;
                }
                buildArena(index, centre, simWorld);
                ready.complete(null);
            });
        });
        return ready;
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
     * Pins and lays the {@code index}-th arena's platform. Must be called on the main thread, with
     * the arena's chunks already resident ({@link Block#setType} touches world state, and a block
     * write into an absent chunk is the synchronous load this class exists to avoid).
     *
     * <p>The platform is barrier blocks so nothing renders and nothing can be mined, one layer
     * below the spawn Y.
     */
    private void buildArena(int index, Location centre, World simWorld) {
        if (!prepared.add(index)) {
            return;
        }

        // Pinned before a single block is touched, so the chunks the async load just brought in
        // cannot be unloaded again between here and the last block write.
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

    /**
     * The {@code index}-th arena's spawn points, for a slot whose platform is already laid.
     *
     * <p>A recycled arena is handed back as a freshly built {@link ArenaSlot} rather than a stored
     * one: the spawn points are mutable {@code Location}s, and handing the same instances to
     * successive duels would let anything that moved one corrupt every later duel on that platform.
     */
    private ArenaSlot slotAt(int index) {
        final Location centre = arenaCentre(index);
        final World simWorld = centre.getWorld();
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
        // Any load still in flight will call back into a world that is gone. Clearing here means
        // buildArena's prepared.add is the only thing that could act on it, and that runs against
        // the new world's arenaCentre -- so a late callback rebuilds a slot rather than corrupting
        // one, which is the harmless half of the race.
        preparing.clear();
        nextIndex = 0;
    }
}
