package me.mykindos.betterpvp.balancesim.engine;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.CustomLog;
import lombok.Getter;
import me.mykindos.betterpvp.balancesim.BalanceSimulation;
import me.mykindos.betterpvp.balancesim.SimulationGate;
import me.mykindos.betterpvp.balancesim.world.SimWorldManager;
import me.mykindos.betterpvp.core.framework.simulation.SimulatedEntity;
import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Keeps combatants resident in the sim world between duels instead of spawning a fresh pair for
 * each one.
 *
 * <h2>Why</h2>
 * Spawning and despawning was 43.8% of a 299-second profile of a 256-concurrency sweep -- more
 * than the 33% spent on the combat the sweep exists to measure. The cost is entirely in the
 * server's bookkeeping for a player entering and leaving a level:
 * <ul>
 *   <li>{@code NearbyPlayers.addPlayer}/{@code removePlayer}, 21.4%. Moonrise maintains six
 *       per-player area maps and four of their radii are fixed constants, not the world's view
 *       distance: {@code GENERAL} is {@code MoonriseConstants.MAX_VIEW_DISTANCE + 1} = 33 chunks,
 *       then 10, 8 and 3. That is roughly 5,300 chunk entries walked per add and again per remove,
 *       which is why shrinking the world's view distance to 2 barely moved it -- that setting
 *       reaches only two of the six maps, worth 25 chunks each.</li>
 *   <li>{@code ServerPlayer.<init>}, 14.2%, almost all of it {@code PlayerAdvancements.load}
 *       registering a criterion listener for every advancement in the game (11.3%) and
 *       {@code locateStatsFile} stat-ing the player data directory (2.6%). Paper caches both on the
 *       {@code ServerPlayer} instance rather than in a UUID map, so a fresh entity always pays full
 *       price no matter what UUID it is given.</li>
 *   <li>The chunk ticket churn a player entering and leaving provokes, most of
 *       {@code TicketStorage.purgeStaleTickets}, 5.8%.</li>
 * </ul>
 *
 * <h2>Why residents are pinned to an arena</h2>
 * A parked combatant cannot be stored somewhere out of the way and teleported in, because a
 * cross-chunk move is a full remove-and-re-add of all six area maps -- exactly the cost being
 * avoided. So a resident stays on the platform it was born on, and a slot is the arena and its two
 * combatants together. Reusing a slot moves each combatant a couple of blocks within its own
 * chunk, which the area maps ignore.
 *
 * <h2>Inherited state, and why the wait is gone</h2>
 * Reusing an entity means reusing the key that every {@code WeakHashMap<Player, ?>} in champions
 * holds its per-player combat state under. Seven passives carry state that would otherwise survive
 * into the next duel on that platform -- Bloodlust, Vengeance, Swordsmanship, Deflection, Thorns,
 * ComboAttack and Fortitude -- whose only cleanup in production was the entity becoming garbage,
 * which is precisely what residency prevents.
 *
 * <p>All seven now override {@code Skill.invalidatePlayer}, and {@code SimCombatant.despawn} calls
 * it for every registered skill as a duel ends. So the state is dropped outright rather than waited
 * out, and a slot is fit to fight again the tick it is returned.
 *
 * <p>It used to be waited out. Each of the seven expires on a timer, swept by an
 * {@code @UpdateEvent} method or a scheduled task, so a slot was held out of service until the
 * longest window had certainly elapsed -- 400 ticks against a measured worst case of 9 seconds,
 * Bloodlust at max level. That cost {@code quarantine / duelDuration} times the concurrency in
 * residents, each an entity that ticks and a platform whose chunks stay loaded, and it made
 * throughput track how fast the quarantine drained rather than how fast duels finished.
 * {@code combatantQuarantineTicks} survives at zero as the diagnostic described on that config: set
 * it back and see whether the numbers move.
 *
 * <p>The ceiling stays regardless. Past the cap the sweep waits for a slot rather than growing: a
 * sweep that runs slightly slower is a fixed cost, and unbounded arena sprawl is the failure that
 * exhausted the heap before arenas were recycled at all.
 */
@Singleton
@CustomLog
public class SimCombatantPool {

    private final SimulationGate gate;
    private final SimWorldManager worldManager;
    private final BalanceSimulation plugin;

    /**
     * Slots whose quarantine may have elapsed, oldest release first.
     *
     * <p>A queue rather than a scan: releases happen in time order, so the head is always the
     * slot closest to being eligible, and a miss on the head means every other slot is a miss too.
     * That keeps {@link #acquire} constant-time on the tick loop's hot path.
     */
    private final Deque<Slot> quarantined = new ArrayDeque<>();

    /** Every slot ever created, in use or not, so teardown can reach them all. */
    private final List<Slot> allSlots = new ArrayList<>();

    @Inject
    public SimCombatantPool(SimulationGate gate, SimWorldManager worldManager, BalanceSimulation plugin) {
        this.gate = gate;
        this.worldManager = worldManager;
        this.plugin = plugin;
    }

    /**
     * Hands out a slot whose combatants are ready to fight, creating one if the pool is below its
     * ceiling.
     *
     * @param tick the sweep's current tick, against which quarantine is measured. At the default
     *             quarantine of zero the head of the queue is always eligible, so this only decides
     *             anything when the knob has been turned back up.
     * @return a ready slot, or {@code null} when every resident is still quarantined and the pool
     *         is at its ceiling. The caller must treat that as "not this tick" rather than as an
     *         error, and must not have consumed a matchup before asking.
     */
    @Nullable
    public Slot acquire(long tick) {
        final Slot head = quarantined.peek();
        if (head != null && tick - head.releasedAtTick >= gate.getCombatantQuarantineTicks()) {
            quarantined.poll();
            return head;
        }
        if (allSlots.size() * 2 < gate.getMaxResidentCombatants()) {
            return createSlot();
        }
        return null;
    }

    /**
     * Returns a finished duel's slot to quarantine.
     *
     * <p>A combatant that actually died is not recycled. Lethal blows are intercepted before they
     * land so no fake player should ever reach zero health, but a dead {@code ServerPlayer} cannot
     * be revived without going through the respawn path, and quietly reusing one would measure a
     * corpse. The slot's residents are discarded and replaced instead, which costs the old
     * spawn price for that slot only.
     */
    public void release(Slot slot, long tick) {
        if (slot.attacker.isDeadOrDying() || slot.defender.isDeadOrDying()) {
            log.warn("Sim combatant on arena {} died despite lethal-blow interception; replacing"
                    + " the slot's residents rather than reusing them", slot.arena.index()).submit();
            replaceResidents(slot);
        }
        slot.releasedAtTick = tick;
        quarantined.add(slot);
    }

    /** Number of combatants currently resident in the sim world, for progress reporting. */
    public int residentCount() {
        return allSlots.size() * 2;
    }

    /**
     * Total outbound packets held by every resident's dead-end channel.
     *
     * <p>Expected to be zero on every call -- see {@code SimPlayer.PacketSink}. It is reported
     * anyway because the failure it detects has no other symptom until the heap is gone: an
     * unretained packet queue is invisible, and a retained one looks exactly like a healthy sweep
     * until the GC cannot keep up. A non-zero value here means the sink is missing or bypassed, and
     * the run should be stopped rather than trusted.
     */
    public long queuedPacketBacklog() {
        long queued = 0;
        for (Slot slot : allSlots) {
            queued += slot.attacker.queuedPacketCount() + slot.defender.queuedPacketCount();
        }
        return queued;
    }

    /**
     * Despawns every resident and forgets the pool.
     *
     * <p>Called at the end of a run and on plugin disable. Not optional: residents are entities in
     * a world that is about to be unloaded, and the pool's slots describe arenas that will not
     * exist after {@link SimWorldManager#teardown()}.
     */
    public void drain() {
        for (Slot slot : allSlots) {
            slot.attacker.despawn();
            slot.defender.despawn();
            worldManager.releaseArena(slot.arena);
        }
        log.info("Drained {} resident sim combatants", allSlots.size() * 2).submit();
        allSlots.clear();
        quarantined.clear();
    }

    private Slot createSlot() {
        final SimWorldManager.ArenaSlot arena = worldManager.acquireArena();
        final Slot slot = new Slot(arena,
                spawnResident(arena.index(), "atk", arena.spawnA()),
                spawnResident(arena.index(), "def", arena.spawnB()));
        allSlots.add(slot);
        return slot;
    }

    private void replaceResidents(Slot slot) {
        slot.attacker.despawn();
        slot.defender.despawn();
        slot.attacker = spawnResident(slot.arena.index(), "atk", slot.arena.spawnA());
        slot.defender = spawnResident(slot.arena.index(), "def", slot.arena.spawnB());
    }

    /**
     * Spawns one resident and flags it before anything else can observe it.
     *
     * <p>Marking happens here rather than per duel because with a pooled entity there is no longer
     * a moment between the entity existing and the pipeline being able to reach it -- a resident
     * sits in a ticking chunk from the instant it is added to the level, so the flag the
     * persistence guards read has to be set in the same breath as the spawn.
     */
    private SimPlayer spawnResident(int arenaIndex, String side, Location at) {
        final String name = "sim_" + side + "_" + arenaIndex;
        final SimPlayer player = SimPlayer.spawn(UUID.randomUUID(), name, at);
        SimulatedEntity.mark(player.asBukkit(), plugin);
        return player;
    }

    /**
     * One arena and the two combatants that live on it.
     *
     * <p>Mutable in its residents because a slot outlives them: a death forces a replacement pair
     * onto the same platform rather than stranding the arena.
     */
    @Getter
    public static final class Slot {

        private final SimWorldManager.ArenaSlot arena;
        private SimPlayer attacker;
        private SimPlayer defender;
        private long releasedAtTick;

        private Slot(SimWorldManager.ArenaSlot arena, SimPlayer attacker, SimPlayer defender) {
            this.arena = arena;
            this.attacker = attacker;
            this.defender = defender;
            // A slot created mid-sweep has never been fought on, so nothing can have left state
            // behind on it. Dating its release to the sweep's origin makes it eligible immediately
            // without needing a second "never used" state for acquire to test.
            this.releasedAtTick = Long.MIN_VALUE / 2;
        }
    }
}
