package me.mykindos.betterpvp.balancesim;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.Getter;
import me.mykindos.betterpvp.core.config.Config;

/**
 * Hard gate on every simulation entry point, plus the knobs a run is parameterised by.
 *
 * <p>The simulator spawns fake players into the real combat pipeline and writes to the real
 * database, so it must only ever run on dev/staging. Isolation is layered: the primary
 * mechanism is that this plugin's jar is absent from production deploys, and this flag is the
 * second layer for the case where it is present anyway.
 *
 * <p>Every command, scheduler and reload hook checks {@link #isEnabled()} before doing any
 * work. Default is {@code false}.
 */
@Singleton
@Getter
public class SimulationGate {

    @Inject
    @Config(path = "champions.simulation.enabled", defaultValue = "false")
    private boolean enabled;

    @Inject
    @Config(path = "champions.simulation.worldName", defaultValue = "bpvp_sim")
    private String worldName;

    @Inject
    @Config(path = "champions.simulation.iterations", defaultValue = "100")
    private int iterations;

    @Inject
    @Config(path = "champions.simulation.maxConcurrentDuels", defaultValue = "64")
    private int maxConcurrentDuels;

    @Inject
    @Config(path = "champions.simulation.duelTimeoutSeconds", defaultValue = "30.0")
    private double duelTimeoutSeconds;

    /**
     * Which slice of the permutation space {@code /simulate} sweeps when the command is given no
     * argument. See {@code SimScope}; defaults to the cheapest tier so an accidental invocation
     * cannot start a multi-hour sweep.
     */
    @Inject
    @Config(path = "champions.simulation.scope", defaultValue = "MELEE")
    private String scope;

    /**
     * Whether a duel drives one side or both. See {@code SimScenario}; defaults to the one-way
     * measurement every phase 1 and 2 row was taken with, so an existing dashboard keeps meaning what
     * it meant.
     */
    @Inject
    @Config(path = "champions.simulation.scenario", defaultValue = "ONE_WAY")
    private String scenario;

    /**
     * How long the rotation holds right click for a channel or charge skill, in ticks.
     *
     * <p>This is a <em>policy choice</em> rather than a measurement, and the only one in the engine: a
     * channel produces damage for as long as it is held, so its DPS is whatever this says it is. Two
     * runs with different budgets are not comparable for a channel build, which is why the value is
     * written into {@code sim_run.scenario} alongside the scope.
     *
     * <p>Two seconds by default -- long enough for the energy drain of an {@code EnergyChannelSkill} to
     * bind (a 30/s channel against a 150 pool empties in five seconds) and short enough that a channel
     * build is not simply measured as "stands still for the whole fight", which no player does while
     * being hit.
     */
    @Inject
    @Config(path = "champions.simulation.channelHoldTicks", defaultValue = "40")
    private int channelHoldTicks;

    /**
     * How often the rotation may re-press a button that did not fire, in ticks.
     *
     * <p>A rate limit on attempts, not a model of anything. Every press dispatches a real event through
     * the whole listener chain, and at 128 concurrent duels pressing every skill every tick is tens of
     * thousands of dispatches a second that produce no extra measurement -- the chain's answer does not
     * change within a few ticks. Low enough that a skill still comes off cooldown promptly.
     */
    @Inject
    @Config(path = "champions.simulation.skillRetryIntervalTicks", defaultValue = "5")
    private int skillRetryIntervalTicks;

    /**
     * How a slot's resident is made fit to fight again after it has died. See
     * {@code ResidentRecycleStrategy}, which documents what each value tests and why the question is
     * still open.
     *
     * <p>Defaults to the only value proven to produce real measurements, which is also the most
     * expensive one -- a duel kills its defender essentially every time, so this is paid once per duel
     * and is about a third of the sweep's cost. The cheaper values are experiments; the pool falls back
     * to {@code RESPAWN_NEW} on its own if the configured one does not work, so trying one costs a
     * slower sweep rather than a run of nulls.
     */
    @Inject
    @Config(path = "champions.simulation.residentRecycleStrategy", defaultValue = "RESPAWN_NEW")
    private String residentRecycleStrategy;

    /**
     * Ceiling on how many builds a scope may enumerate. A scope over this is refused with its
     * count rather than truncated: a prefix of an enumeration is a biased sample, and nothing on
     * the resulting rows would say so.
     */
    @Inject
    @Config(path = "champions.simulation.maxBuilds", defaultValue = "5000")
    private int maxBuilds;

    /**
     * Which skills the catalog is allowed to build permutations from. See {@code SimSkillFilter}.
     *
     * <p>Defaults to {@code OFFENSIVE}, which is {@code OFFENSIVE_PASSIVES} widened to include the
     * actives phase 3's rotation policy can now drive. It is still a narrow setting, because the skill
     * axis is combinatorial across six slots: the unfiltered {@code FULL} space is tens of millions of
     * builds and exhausts the heap while being enumerated, long before a single duel runs.
     */
    @Inject
    @Config(path = "champions.simulation.skillFilter", defaultValue = "OFFENSIVE")
    private String skillFilter;

    /**
     * How many duels may be set up in a single tick.
     *
     * <p>Setting a duel up is expensive and strictly main-thread: two {@code ServerPlayer}s are
     * spawned, roles equipped, builds registered and armour events fired, on the order of tens of
     * milliseconds each. Filling the whole concurrency budget in one tick therefore stalls the
     * server for as long as the batch takes -- which is also what made every duel in a batch start
     * at a different real time, the aliasing that first showed up as TTK variance across identical
     * matchups. Spreading setup over ticks keeps the tick loop responsive and the arrivals even.
     */
    @Inject
    @Config(path = "champions.simulation.duelSetupsPerTick", defaultValue = "4")
    private int duelSetupsPerTick;

    /**
     * How often a sweep reports progress to the log and to whoever started it.
     *
     * <p>A sweep is long and otherwise silent -- duels run in real time -- so without this there
     * is no way to tell a healthy long run from a wedged one, and no basis for deciding whether to
     * let it finish. Floored at one second by the orchestrator regardless of what is configured.
     */
    @Inject
    @Config(path = "champions.simulation.progressIntervalSeconds", defaultValue = "15.0")
    private double progressIntervalSeconds;

    /**
     * How long a combatant is held out of service after a duel before it may fight another.
     *
     * <p>Zero by default, because the state this used to wait out is now cleared outright.
     * Combatants are resident rather than respawned per duel ({@code SimCombatantPool}), so a duel
     * inherits the entity the previous one used along with every {@code WeakHashMap<Player, ?>} entry
     * champions' passives filed under it; the quarantine existed only to sit out the timers those
     * entries expire on. {@code SimCombatant.despawn} now unequips every skill from the combatant
     * instead, which drops the same state immediately and deterministically -- so waiting buys
     * nothing and costs a great deal, since a slot out of service for 20 seconds after a ~7-second
     * duel means roughly three times the concurrency in resident, ticking entities.
     *
     * <p>Kept as a knob rather than deleted, as the diagnostic it is: if a sweep is suspected of
     * carrying state between duels on a slot, raising this to 400 restores the old wait-it-out
     * behaviour, and a difference in the results says the residue is real and some skill's
     * {@code invalidatePlayer} is incomplete.
     */
    @Inject
    @Config(path = "champions.simulation.combatantQuarantineTicks", defaultValue = "0")
    private int combatantQuarantineTicks;

    /**
     * Ceiling on how many combatants may be resident in the sim world at once.
     *
     * <p>With the quarantine at its default of zero a slot is back in service the tick its duel ends,
     * so residency settles at roughly the concurrency and this ceiling does not bind. It bound
     * before: a slot is out of service for {@code quarantine / duelDuration} times as long as it is
     * in use, so at 256 concurrent duels, the old 20-second quarantine and the observed ~7.3-second
     * mean duel, steady state was roughly 1,900 combatants -- every one of them ticking and keeping
     * its platform's chunks loaded.
     *
     * <p>When the cap is reached the sweep waits for a slot instead of growing. That is deliberate:
     * a sweep that runs slightly slower is a bounded cost, whereas unbounded arena sprawl is what
     * exhausted the heap before arenas were recycled at all. Default leaves headroom over the
     * figure above so it does not bind at the default concurrency.
     */
    @Inject
    @Config(path = "champions.simulation.maxResidentCombatants", defaultValue = "2048")
    private int maxResidentCombatants;

}
