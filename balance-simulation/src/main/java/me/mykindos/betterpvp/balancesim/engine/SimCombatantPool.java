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

    /**
     * How many barren duels a non-default recycle strategy is allowed before the run gives up on it.
     *
     * <p>Small, because each one is a duel that measured nothing and a {@code sim_result} row that has
     * to be thrown away, and the signal is unambiguous well before this: a strategy that does not
     * restore fightability fails on essentially every slot that has hosted a death, so at 300
     * concurrent this threshold is reached within seconds of the first wave of deaths. Not one, because
     * a single barren duel is also what a genuinely unkillable matchup looks like.
     */
    private static final int DEMOTE_AFTER_BARREN_DUELS = 25;

    /** Every slot ever created, in use or not, so teardown can reach them all. */
    private final List<Slot> allSlots = new ArrayList<>();

    /**
     * How many individual residents have been discarded and respawned rather than reused.
     *
     * <p>Not zero on a healthy sweep -- since a death costs the resident that died, roughly one per
     * duel that ends in a kill is the expected rate. See {@link #replace} for the number it must stay
     * well under.
     */
    private int replacedSlots;

    /**
     * How many residents were recovered in place -- revived or recycled -- rather than respawned.
     *
     * <p>The number the experiment is for. On {@code RESPAWN_NEW} it stays zero; on a strategy that
     * works it should be roughly one per duel and {@link #replacedSlots} should be near zero.
     */
    private int revivedResidents;

    /** Duels that measured nothing on a slot that had hosted a death. The evidence for {@link #demote}. */
    private int barrenCondemnations;

    /**
     * The strategy in force for this run, resolved once by {@link #strategy()} and cleared by
     * {@link #drain()}. Null before the first release of a run.
     */
    private ResidentRecycleStrategy strategy;

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
     * Returns a finished duel's slot to quarantine, replacing whichever combatant lost.
     *
     * <p>Since phase 3 stopped intercepting lethal blows a death is the <em>normal</em> end of a
     * duel, so this is on the hot path rather than a warning case. It has to happen here, on the tick
     * the duel resolves, and not lazily when the slot is next handed out:
     * {@code LivingEntity.tickDeath} removes a dead entity from the level 20 ticks after the kill, so
     * a corpse that waits for its next duel simply stops being a resident.
     *
     * <h2>Why a combatant that died is replaced rather than revived</h2>
     * It used to be revived in place, by unsetting the fields vanilla sets on death. That is a claim
     * about someone else's post-death state, and when the claim is incomplete the failure is silent:
     * the entity reads as perfectly alive and simply cannot be hurt again. Two runs measured what that
     * costs. Run 140 revived three fields and 95.6% of its duels timed out having landed no hit at all.
     * Run 141 revived the whole surface anyone here could name -- pose and dimensions out of the
     * 0.2x0.2 {@code DYING} box, combat tracker, last-hurt-by, the hurt and invulnerability counters,
     * absorption, fire, freeze -- and still came out at 49.2%, in the exact alternating pattern of a
     * slot that works for one duel after its residents are fresh and never again.
     *
     * <p>What run 141 did establish is the useful half: a <em>new</em> entity is always fightable, and
     * one that has died is not, whatever is done to it. So a death now costs the resident that died,
     * and only that one -- in {@code ONE_WAY} that is the defender, so the attacker stays resident and
     * the arena keeps its chunks. That is roughly a quarter of the spawn traffic the pool replaced,
     * paid to get numbers that are real rather than a fast sweep of nulls.
     *
     * <p>The residual cause is still unknown, and it is worth finding: whatever survives a death on
     * that entity survives every field named above, and is cleared only by a fresh identity. Until it
     * is, {@link SimPlayer#isFightable()} is the test and replacement is the remedy.
     *
     * <h2>The barren tripwire</h2>
     * {@code measuredNothing} is set by a duel that ran its whole timeout without landing a hit. It is
     * the symptom itself rather than a proxy for it, which is what made it the thing that caught the
     * failed revive when every state check said the combatant was fine. It should now never fire, so
     * it is kept: it is the only detector here that does not depend on already knowing what goes wrong.
     *
     * <p>It is gated on the slot having hosted a death ({@link Slot#hostedDeath}), so a build that
     * genuinely cannot land a hit inside the timeout does not condemn a slot it never poisoned.
     * Without that gate an unkillable target would respawn its arena every duel and give back the
     * whole cost residency exists to save.
     *
     * @param measuredNothing whether the duel just fought here ended on the timeout with no hit landed
     */
    public void release(Slot slot, long tick, boolean measuredNothing) {
        if (measuredNothing && slot.hostedDeath) {
            barrenCondemnations++;
            // Once, on the first one, before the evidence is despawned below. Both sides, because
            // which of them is poisoned has never actually been established -- a ONE_WAY duel only
            // kills the defender, so the defender is the suspect, but "the attacker stopped being
            // able to swing" produces exactly the same barren row and has never been ruled out.
            //
            // Captured here but not logged until after the replacement below, because the number is
            // meaningless on its own. Run 147's probe read zero health lost, which looked conclusive
            // until DamageEventProcessor turned out to cancel every vanilla damage event and reapply
            // the damage through BetterPvP's own path -- so "no health lost inside the call" is also
            // what a perfectly healthy combatant may well report. The fresh replacement is the control
            // that says which of those this is.
            final String poisonedState = barrenCondemnations == 1
                    ? slot.attacker.describeCombatState() + "\n  defender: " + slot.defender.describeCombatState()
                    : null;
            final String poisonedProbe = poisonedState == null ? null : slot.defender.probeDamage(slot.attacker);

            if (strategy() == ResidentRecycleStrategy.RESPAWN_NEW) {
                warnOnce("A duel on arena {} measured nothing at all, on a slot whose dead resident had"
                        + " already been respawned. A fresh entity is supposed to be fightable by"
                        + " construction, so something poisons a slot that outlives its residents --"
                        + " which is not what runs 140 to 142 showed. Further occurrences are counted,"
                        + " not logged.", slot.arena.index());
            } else if (barrenCondemnations >= DEMOTE_AFTER_BARREN_DUELS) {
                demote(slot.arena.index());
            }
            slot.attacker = replace(slot, slot.attacker, "atk", slot.arena.spawnA(), UUID.randomUUID());
            slot.defender = replace(slot, slot.defender, "def", slot.arena.spawnB(), UUID.randomUUID());
            slot.hostedDeath = false;

            // The control, on the same tick, in the same arena, through the same pipeline, differing
            // only in being a combatant that has never died. Run 149 had it refuse damage exactly as
            // the poisoned one did, and refuse it before the vanilla damage event was even raised --
            // which condemns the probe's surroundings rather than either combatant, since this same
            // fresh entity goes straight back into service and fights measurable duels. So its own
            // state is now dumped alongside the poisoned pair's, on equal terms: if the control reads
            // healthy here and still cannot be hurt here, the difference is teardown, not death.
            if (poisonedProbe != null) {
                log.warn("First barren duel under strategy {}, on arena {}. Neither combatant's own"
                                + " state explains this on its face, so here is all of it, plus what"
                                + " happens when each of them is hurt directly rather than swung at."
                                + "\n  attacker: {}"
                                + "\n  poisoned probe: {}"
                                + "\n  control  state: {}"
                                + "\n  control  probe: {}  (a freshly spawned replacement, never died)",
                        strategy(), slot.arena.index(), poisonedState, poisonedProbe,
                        slot.defender.describeCombatState(),
                        slot.defender.probeDamage(slot.attacker)).submit();
            }
        } else {
            // Read before recovering, not inferred from it afterwards. This used to ask whether the
            // resident had been swapped for a different object, which is only the same question under
            // the two RESPAWN strategies: REVIVE and RECYCLE hand back the object they were given, so
            // the flag stayed false forever and took the barren tripwire and the demotion with it.
            // Run 144 spent 564 of 864 duels barren under RECYCLE without the pool noticing once.
            final boolean died = !slot.attacker.isFightable() || !slot.defender.isFightable();

            // Each side independently: a ONE_WAY duel only ever kills the defender, and replacing the
            // attacker too would double a cost that is already the dominant one.
            slot.attacker = replaceIfSpent(slot, slot.attacker, "atk", slot.arena.spawnA());
            slot.defender = replaceIfSpent(slot, slot.defender, "def", slot.arena.spawnB());
            slot.hostedDeath |= died;
        }
        slot.releasedAtTick = tick;
        quarantined.add(slot);
    }

    /**
     * Restores a resident that can no longer fight, by whichever means {@link #strategy()} names, and
     * leaves a healthy one alone.
     *
     * <p>{@link SimPlayer#isFightable()} is a necessary condition rather than a sufficient one -- run
     * 141's poisoned defenders passed it -- but every combatant that fails it is certainly finished,
     * and since phase 3 the ordinary way to fail it is to have lost the duel.
     *
     * <p>An entity that has already left the level can only be respawned, whatever the strategy says:
     * there is nothing left to revive or re-add. That is the case the 20-tick {@code tickDeath} window
     * produces when a sweep stalls between the kill and this call.
     */
    private SimPlayer replaceIfSpent(Slot slot, SimPlayer resident, String side, Location at) {
        if (resident.isFightable()) {
            return resident;
        }
        final ResidentRecycleStrategy active = resident.isRemoved()
                ? ResidentRecycleStrategy.RESPAWN_NEW
                : strategy();
        switch (active) {
            case REVIVE -> {
                resident.reviveIfDead();
                revivedResidents++;
                return resident;
            }
            case RECYCLE -> {
                resident.recycle(at);
                revivedResidents++;
                return resident;
            }
            case RESPAWN_SAME_UUID -> {
                return replace(slot, resident, side, at, resident.getUUID());
            }
            default -> {
                return replace(slot, resident, side, at, UUID.randomUUID());
            }
        }
    }

    /**
     * The strategy this run is using, resolved once and then fixed.
     *
     * <p>Read from the config on first use rather than per release, so a {@code balancesim reload}
     * during a sweep cannot change how residents are recycled halfway through it -- the same reason
     * {@code SweepSettings} exists. An unrecognised name falls back loudly: silently running a
     * different experiment from the configured one is the one outcome that would waste a whole run.
     */
    private ResidentRecycleStrategy strategy() {
        if (strategy == null) {
            final String configured = gate.getResidentRecycleStrategy();
            strategy = ResidentRecycleStrategy.parse(configured).orElse(null);
            if (strategy == null) {
                log.warn("champions.simulation.residentRecycleStrategy is {}, which is not a strategy;"
                        + " using RESPAWN_NEW. Valid values: REVIVE, RECYCLE, RESPAWN_SAME_UUID,"
                        + " RESPAWN_NEW", configured).submit();
                strategy = ResidentRecycleStrategy.RESPAWN_NEW;
            } else if (strategy != ResidentRecycleStrategy.REVIVE) {
                // Only when the run is not on the default. All four values work since run 151, so this
                // is no longer a warning that something experimental is in play -- it is a note that
                // the sweep is paying more than it has to, and why its timings will not line up with
                // the last one's.
                log.info("Sim residents will be recovered by {} rather than the default REVIVE, which"
                        + " costs more per duel: RESPAWN_NEW measured 95.7 ms/duel, RECYCLE 54.0 and"
                        + " REVIVE 40.1 over the same 864. The pool still falls back to RESPAWN_NEW if"
                        + " the barren timeout count starts climbing", strategy).submit();
            }
        }
        return strategy;
    }

    /**
     * Gives up on the configured strategy and respawns for the rest of the run.
     *
     * <p>The barren tripwire is the only evidence that a strategy does not work, and it is evidence
     * after the fact -- the duel that detected it measured nothing. A handful of those is an acceptable
     * price for finding out; a whole run of them is run 140 again. So the first few are tolerated and
     * then the run demotes itself to the value that is known to work, which turns a failed experiment
     * into a sweep that is merely slower than it could have been.
     */
    private void demote(int arenaIndex) {
        if (strategy == ResidentRecycleStrategy.RESPAWN_NEW) {
            return;
        }
        log.warn("Recycle strategy {} has now produced {} duels that measured nothing (latest on arena"
                        + " {}), so it does not restore a dead combatant to a fightable state. Falling"
                        + " back to RESPAWN_NEW for the rest of this run; the rows already written for"
                        + " those duels carry NULL damage and should be discarded.",
                strategy, barrenCondemnations, arenaIndex).submit();
        strategy = ResidentRecycleStrategy.RESPAWN_NEW;
    }

    /**
     * Discards one resident and spawns its replacement on the same platform.
     *
     * <p>Counted because the number is what says how much residency is still buying. One per death is
     * the expected rate and is the price of the section above; approaching two per duel would mean the
     * pool has quietly reverted to spawning a fresh pair each time, which is the 43.8% of a profile
     * this class exists to avoid and would otherwise show up only as an unexplained drop in duels per
     * second.
     */
    private SimPlayer replace(Slot slot, SimPlayer resident, String side, Location at, UUID uuid) {
        resident.despawn();
        replacedSlots++;
        return spawnResident(slot.arena.index(), side, at, uuid);
    }

    /**
     * Logs the first occurrence of a run and nothing after it.
     *
     * <p>At several hundred duels a minute a line per occurrence would bury the sweep's own progress,
     * and the useful signal is that it is happening at all -- the magnitude is {@link #drain()}'s job.
     */
    private void warnOnce(String message, Object... args) {
        if (replacedSlots == 0) {
            log.warn(message, args).submit();
        }
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
        log.info("Drained {} resident sim combatants across {} arenas. Strategy {}: {} residents"
                        + " recovered in place, {} respawned. A respawn count near one per duel is the"
                        + " cost RESPAWN_NEW is expected to pay; on any other strategy it should be"
                        + " near zero, and is not if the run demoted itself.",
                        allSlots.size() * 2, allSlots.size(), strategy, revivedResidents, replacedSlots)
                .submit();
        allSlots.clear();
        quarantined.clear();
        replacedSlots = 0;
        revivedResidents = 0;
        barrenCondemnations = 0;
        strategy = null;
    }

    private Slot createSlot() {
        final SimWorldManager.ArenaSlot arena = worldManager.acquireArena();
        final Slot slot = new Slot(arena,
                spawnResident(arena.index(), "atk", arena.spawnA()),
                spawnResident(arena.index(), "def", arena.spawnB()));
        allSlots.add(slot);
        return slot;
    }

    /**
     * Spawns one resident and flags it before anything else can observe it.
     *
     * <p>Marking happens here rather than per duel because with a pooled entity there is no longer
     * a moment between the entity existing and the pipeline being able to reach it -- a resident
     * sits in a ticking chunk from the instant it is added to the level, so the flag the
     * persistence guards read has to be set in the same breath as the spawn.
     *
     * <p>Lookup registration is deliberately <em>not</em> done here. It is per duel
     * ({@code SimCombatant.spawn}/{@code despawn}) rather than per resident, so that between duels a
     * slot's combatants are unresolvable again -- which restores the phase 2 isolation for the whole
     * idle period, lets {@code ClientManager.unload} actually drop the ephemeral client, and means
     * {@code EnergyService.tick} discards the energy entry so the next duel starts on a full bar
     * rather than the last one's remainder.
     */
    private SimPlayer spawnResident(int arenaIndex, String side, Location at) {
        return spawnResident(arenaIndex, side, at, UUID.randomUUID());
    }

    private SimPlayer spawnResident(int arenaIndex, String side, Location at, UUID uuid) {
        final String name = "sim_" + side + "_" + arenaIndex;
        final SimPlayer player = SimPlayer.spawn(uuid, name, at);
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

        /**
         * Whether a duel fought here has ever killed one of this slot's residents.
         *
         * <p>The gate on the barren tripwire. A slot that has never hosted a death cannot be carrying
         * post-death residue, so a duel that measures nothing on it is a statement about the build
         * being measured rather than about the arena.
         */
        private boolean hostedDeath;

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
