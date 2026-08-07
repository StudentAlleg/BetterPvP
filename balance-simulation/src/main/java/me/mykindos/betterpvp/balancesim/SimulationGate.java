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

    /**
     * Whether the sweep varies its concurrency to hold {@link #targetMsptMillis}.
     *
     * <p>Off by default, because it changes what a run is: with it on, {@code maxConcurrentDuels}
     * stops being the concurrency and becomes a ceiling the run may sit anywhere below. Both the flag
     * and the target are part of {@code config_hash} for that reason -- a governed run and a fixed
     * one are not the same measurement even at the same ceiling.
     *
     * <p>Note what it cannot do. Arenas are warmed up to {@code maxConcurrentDuels} before the first
     * duel and their chunks stay pinned for the whole run, so the per-tick chunk sweeps cost the same
     * whether the governor is using those arenas or not. Lowering the limit trims per-duel work only;
     * the ceiling itself is a fixed cost that is paid up front and never governed away. Set the
     * ceiling near what you actually intend to run rather than far above it.
     */
    @Inject
    @Config(path = "champions.simulation.adaptiveConcurrency", defaultValue = "false")
    private boolean adaptiveConcurrency;

    /**
     * Median tick time in milliseconds the governor holds the sweep under.
     *
     * <p>50 ms is 20 TPS exactly, so the 45 default leaves a little headroom for the controller to
     * settle in rather than sitting on the boundary.
     *
     * <p>This is a correctness knob more than a speed one. Throughput actually rises with
     * concurrency and merely saturates -- see {@code ConcurrencyGovernor} for the model and the
     * measured coefficients -- so holding 20 TPS gives up a little of it. What it buys is that
     * roughly 130 files across champions and core time their game logic with
     * {@code System.currentTimeMillis}, so a sweep running at 10 TPS elapses every wall-clock
     * cooldown in half the intended ticks and measures a game nobody plays. Raise it if a run's
     * purpose is throughput and its numbers are not going into a baseline.
     */
    @Inject
    @Config(path = "champions.simulation.targetMsptMillis", defaultValue = "45.0")
    private double targetMsptMillis;

    /**
     * Fewest duels the governor will reduce the sweep to.
     *
     * <p>A floor rather than zero so a sweep that cannot hold its budget keeps measuring slowly
     * instead of stopping. A run governed down to nothing would look identical to a wedged one: the
     * progress line simply stops advancing, with nothing saying why.
     */
    @Inject
    @Config(path = "champions.simulation.minConcurrentDuels", defaultValue = "16")
    private int minConcurrentDuels;

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
     * <p>Defaults to {@code REVIVE}, which keeps the {@code ServerPlayer} and never touches its level
     * registration at all. A duel kills its defender essentially every time, so this is paid once per
     * duel and used to be about a third of the sweep's cost. Over the same 864 duels: {@code
     * RESPAWN_NEW} 95.7 ms/duel, {@code RECYCLE} 54.0, {@code REVIVE} 40.1 -- and the last two produced
     * identical pipeline counts, so the saving is not bought by measuring less. The pool falls back to
     * {@code RESPAWN_NEW} on its own if the configured value stops working, so trying a cheaper one
     * costs a slower sweep rather than a run of nulls.
     */
    @Inject
    @Config(path = "champions.simulation.residentRecycleStrategy", defaultValue = "REVIVE")
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
     * The reviewed output of a skill relevance audit: comma-separated skill names that a sweep under
     * {@code SimSkillFilter.RELEVANT} is allowed to build permutations from.
     *
     * <p>Empty by default, and an empty value is a hard error for that filter rather than a silent
     * skill-less sweep. Populating it is a deliberate act: run {@code /simulate SKILLS --audit}, read
     * the artifact, disagree with what deserves disagreeing with, and paste the names in. Nothing
     * writes to this automatically, for the reason {@code SkillAuditReport} refuses to write config at
     * all -- an {@code INERT} verdict is a measurement of one scenario, not a permanent fact, and a
     * skill excluded by a robot stays excluded through every buff it later receives.
     *
     * <p>Part of {@code config_hash}, so two runs that swept different skill sets are never diffed as
     * though they swept the same one.
     */
    @Inject
    @Config(path = "champions.simulation.relevantSkills", defaultValue = "")
    private String relevantSkills;

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
     * Whether every duel writes a {@code sim_duel_diagnostic} row.
     *
     * <p>Off by default because it is one row per duel rather than per matchup, and a sweep runs
     * duels in the hundreds of thousands. On, it is the only place a single duel stays visible: every
     * figure on {@code sim_result} is a mean over the matchup's iterations, so a difference that
     * decides one fight is averaged away before it is stored.
     *
     * <p>Deliberately not part of {@code config_hash}. Observing a duel must not change what the duel
     * measures, and a diagnostic run that could not be diffed against the run it is explaining would
     * be useless for the one job it has.
     */
    @Inject
    @Config(path = "champions.simulation.duelDiagnostics", defaultValue = "false")
    private boolean duelDiagnostics;

    /**
     * Ceiling on how many diagnostic rows one run may write.
     *
     * <p>A stop rather than a sample: once the cap is reached the run stops recording and says so,
     * so the rows that exist are a contiguous prefix of the sweep rather than an arbitrary subset of
     * it. The residency hypothesis this table was built for is about how a duel's position in the
     * sweep affects its outcome, and a sampled table cannot answer a question about ordering.
     */
    @Inject
    @Config(path = "champions.simulation.duelDiagnosticsMaxRows", defaultValue = "50000")
    private int duelDiagnosticsMaxRows;

    /**
     * Whether every landed hit writes a {@code sim_trace} row.
     *
     * <p>Off by default and heavier than the duel diagnostics by roughly the number of hits in a
     * fight. Turn it on to locate a divergence, not to measure one: runs 168 and 169 proved the sweep
     * is not reproducible -- same build, same target, four repeats, different damage in 2.3% of
     * {@code MUTUAL} matchups and 5.2% of {@code ONE_WAY} ones -- and no per-duel row can say where
     * inside a fight two iterations parted, because the totals are what disagree.
     *
     * <p>Not part of {@code config_hash}, on the same reasoning as {@code duelDiagnostics}: observing
     * a duel must not change what it measures. That matters more here than there. The thing being
     * hunted is a timing difference, so a trace that perturbed timing would manufacture the very
     * effect it was switched on to find -- which is why the trace is assembled from the recording
     * after the duel has ended rather than emitted from the damage path as hits land.
     */
    @Inject
    @Config(path = "champions.simulation.hitTrace", defaultValue = "false")
    private boolean hitTrace;

    /**
     * Ceiling on how many trace rows one run may write.
     *
     * <p>A stop rather than a sample, exactly as {@code duelDiagnosticsMaxRows} is, and for a sharper
     * reason: a sampled trace is worthless. The comparison is between repeats of one matchup, so
     * dropping rows at random would leave iterations that cannot be diffed against each other at all,
     * and a truncated prefix at least yields whole duels.
     *
     * <p>Sized from measurement rather than estimate. A complete {@code SKILLS} sweep costs 161,287
     * rows on {@code ONE_WAY} (run 204); {@code MUTUAL} records the defender's hits too and its duels
     * run longer, and run 170 hit the old 200,000 ceiling partway through. Truncation is not a
     * neutral loss here -- it silently drops the tail of the catalog, so the divergence rate a capped
     * run reports is a floor rather than a measurement, and it is a floor with no marker in the data
     * saying which builds went unmeasured.
     *
     * <p>The default is therefore an order of magnitude above the largest sweep rather than close to
     * it. The rows are narrow and the writes are batched off-thread, so the cost of the headroom is
     * disk; the cost of running out of it is a re-run and a wrong number in between.
     */
    @Inject
    @Config(path = "champions.simulation.hitTraceMaxRows", defaultValue = "2000000")
    private int hitTraceMaxRows;

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
