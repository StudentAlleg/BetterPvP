package me.mykindos.betterpvp.balancesim.world;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
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

    /** Arenas are laid out on a grid this many blocks apart, well beyond any skill's range. */
    private static final int ARENA_SPACING = 256;
    private static final int ARENA_Y = 64;
    /** Half-width of the square platform each duel is fought on. */
    private static final int PLATFORM_RADIUS = 8;
    /** How far each combatant spawns from the arena centre, so they start a couple of swings apart. */
    private static final int SPAWN_OFFSET = 2;

    private final SimulationGate gate;

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
    public SimWorldManager(SimulationGate gate) {
        this.gate = gate;
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
        return world;
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
        final int x = (index % columns) * ARENA_SPACING;
        final int z = (index / columns) * ARENA_SPACING;
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
     * <p>The platform is left standing: it is barrier blocks in a void world that the next duel
     * would only lay again, and the chunks stay loaded either way while the sweep is running.
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
        Bukkit.unloadWorld(world, false);
        world = null;
        // The pool describes blocks in a world that no longer exists. Keeping it would hand the
        // next run an index it believes is already built, in a freshly generated void.
        freeIndices.clear();
        prepared.clear();
        nextIndex = 0;
    }
}
