package me.mykindos.betterpvp.balancesim.audit;

import me.mykindos.betterpvp.balancesim.catalog.SimBuildSpec;
import me.mykindos.betterpvp.balancesim.catalog.SimSkillAllocation;
import me.mykindos.betterpvp.balancesim.catalog.SimTargetSpec;
import me.mykindos.betterpvp.balancesim.engine.ActivationArchetype;
import me.mykindos.betterpvp.balancesim.engine.SimEnergyLedger;
import me.mykindos.betterpvp.balancesim.engine.SimMeasurement;
import me.mykindos.betterpvp.balancesim.engine.SimSkillLedger;
import me.mykindos.betterpvp.champions.champions.skills.ChampionsSkillManager;
import me.mykindos.betterpvp.champions.champions.skills.Skill;
import me.mykindos.betterpvp.champions.champions.skills.types.BuffSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.CrowdControlSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.DamageSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.DefensiveSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.EnergyChannelSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.EnergySkill;
import me.mykindos.betterpvp.champions.champions.skills.types.MovementSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.OffensiveSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.PassiveSkill;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides, from measurement alone, which skills can move a one-way damage or energy result.
 *
 * <p>The lists this produces are the point of the exercise. Organising ~150 skills by hand is slow
 * and the result is not checkable; measuring them is neither. But the output is a <em>proposal</em>
 * for human review, never a config change: see {@link SkillRelevanceBucket} for why the
 * inert/undrivable split has to survive all the way to the artifact, and
 * {@code docs/balance-simulation/DASHBOARD-PARITY-NOTES.md} for the workflow this sits in.
 *
 * <h2>How a verdict is reached</h2>
 * The {@code SKILLS} scope measures one skill at a time, so each row differs from a baseline row --
 * same role, same weapon, same target, same run -- in exactly one skill. The delta between them is
 * that skill's contribution, and no modelling is involved in obtaining it.
 *
 * <h2>Why energy is a first-class dimension</h2>
 * A damage-only audit gets two real skills wrong, in opposite directions:
 * <ul>
 *   <li>{@code Null Blade} siphons energy on melee hit and deals no damage. Its TTK delta is zero.</li>
 *   <li>{@code Energy Pool} raises maximum energy and moves none. Its every flow is zero too.</li>
 * </ul>
 * Both change what a build can do, so both are checked against the energy accounting as well as the
 * damage figures, and a skill relevant on energy alone is labelled as such rather than merged in.
 *
 * <h2>The limit this cannot get past on its own</h2>
 * An energy skill only converts into damage when the build is <em>energy constrained</em>, and the
 * baseline it is measured against carries no skills at all, so it never spends energy. A lone
 * {@code Energy Pool} therefore has nothing to enable and correctly measures as energy-relevant but
 * damage-inert. That is an honest verdict for the tier being swept, not a defect -- but it means the
 * offensive list should not be read as "these skills cannot ever matter", only as "these skills
 * cannot matter <em>alone</em>". The artifact says so.
 */
public final class SkillRelevanceAudit {

    private final ChampionsSkillManager skillManager;
    private final AuditThresholds thresholds;

    /** Baseline (no skills) results, keyed by everything else that identifies the matchup. */
    private final Map<MatchupKey, SimMeasurement.Aggregate> baselines = new HashMap<>();

    /** Single-skill results, held until {@link #classify()} because a baseline may arrive later. */
    private final List<Observation> observations = new ArrayList<>();

    /** Builds carrying more than one skill, counted so the artifact can say they were skipped. */
    private int multiSkillBuilds;

    public SkillRelevanceAudit(ChampionsSkillManager skillManager, AuditThresholds thresholds) {
        this.skillManager = skillManager;
        this.thresholds = thresholds;
    }

    /**
     * The thresholds these verdicts were reached under.
     *
     * <p>Exposed so the artifact reports the thresholds that were actually applied rather than
     * re-deriving them from the defaults. The two are the same today, and would silently stop being
     * the same the moment thresholds become configurable -- at which point every artifact would
     * document a threshold its verdicts had not been judged against.
     */
    public AuditThresholds thresholds() {
        return thresholds;
    }

    /**
     * Files one measured matchup.
     *
     * <p>Only skill-less and single-skill builds are usable. A build carrying several skills folds
     * their contributions into one figure that cannot be decomposed afterwards, which is the same
     * reason {@code SimScope.SKILLS} exists as its own tier -- so those are counted and dropped
     * rather than attributed to an arbitrary one of their skills.
     */
    public void observe(SimBuildSpec build, SimTargetSpec target, SimMeasurement.Aggregate aggregate) {
        final MatchupKey key = MatchupKey.of(build, target);
        if (build.skills().isEmpty()) {
            baselines.put(key, aggregate);
            return;
        }
        if (build.skills().size() > 1) {
            multiSkillBuilds++;
            return;
        }
        observations.add(new Observation(key, build.skills().get(0), build.role(), aggregate));
    }

    /** Whether anything usable was collected. A sweep with no baselines cannot be audited at all. */
    public boolean hasBaselines() {
        return !baselines.isEmpty();
    }

    public int multiSkillBuildsSkipped() {
        return multiSkillBuilds;
    }

    /** Verdicts for every skill observed, worst-news-first: undrivable, then inert, then relevant. */
    public List<SkillVerdict> classify() {
        final Map<String, List<Observation>> bySkill = new LinkedHashMap<>();
        for (Observation observation : observations) {
            bySkill.computeIfAbsent(observation.allocation().skillName(), k -> new ArrayList<>())
                    .add(observation);
        }

        final List<SkillVerdict> verdicts = new ArrayList<>(bySkill.size());
        bySkill.forEach((skillName, group) -> verdicts.add(verdict(skillName, group)));
        verdicts.sort(Comparator
                .comparingInt((SkillVerdict verdict) -> verdict.bucket().ordinal()).reversed()
                .thenComparing(SkillVerdict::role)
                .thenComparing(SkillVerdict::skillName));
        return List.copyOf(verdicts);
    }

    private SkillVerdict verdict(String skillName, List<Observation> group) {
        final Skill skill = skillManager.getObject(skillName).orElse(null);
        final ActivationArchetype archetype =
                skill == null ? ActivationArchetype.PASSIVE : ActivationArchetype.of(skill);

        int attempts = 0;
        int successes = 0;
        int declined = 0;
        int iterations = 0;
        int compared = 0;
        double bestTtkDelta = 0;
        double bestDpsDelta = 0;
        double bestEnergyDelta = 0;
        Integer bestLevel = null;
        boolean significant = false;

        for (Observation observation : group) {
            final SimSkillLedger.SkillActivation activation =
                    observation.aggregate().activations().get(skillName);
            if (activation != null) {
                attempts += activation.attempts();
                successes += activation.successes();
                declined += activation.declined();
            }
            iterations += observation.aggregate().iterations();

            final SimMeasurement.Aggregate baseline = baselines.get(observation.key());
            if (baseline == null) {
                continue;
            }
            compared++;

            // Positive means the skill killed faster than the bare build -- an offensive contribution.
            final double ttkDelta = delta(baseline.ttkSeconds(), observation.aggregate().ttkSeconds(), true);
            final double dpsDelta = delta(baseline.dpsSustained(), observation.aggregate().dpsSustained(), false);
            final double energyDelta = energyDelta(baseline.energy(), observation.aggregate().energy());

            if (Math.abs(ttkDelta) > Math.abs(bestTtkDelta)) {
                bestTtkDelta = ttkDelta;
            }
            if (Math.abs(dpsDelta) > Math.abs(bestDpsDelta)) {
                bestDpsDelta = dpsDelta;
            }
            if (Math.abs(energyDelta) > Math.abs(bestEnergyDelta)) {
                bestEnergyDelta = energyDelta;
            }

            // Significance is judged per matchup, not against the aggregate best. A skill that only
            // matters versus armoured targets is relevant, and testing the pooled figure would let a
            // strong effect on one target be averaged below the threshold by the others.
            if (thresholds.isSignificant(baseline, observation.aggregate(), ttkDelta, dpsDelta, energyDelta)) {
                significant = true;
                bestLevel = observation.allocation().allocatedLevel();
            }
        }

        final SkillRelevanceBucket bucket = bucket(archetype, successes, significant, thresholds);
        return new SkillVerdict(skillName,
                group.get(0).role(),
                archetype,
                markers(skill),
                bucket,
                bestLevel,
                bestTtkDelta,
                bestDpsDelta,
                bestEnergyDelta,
                attempts,
                successes,
                declined,
                compared,
                iterations);
    }

    /**
     * The verdict, given what fired and what moved.
     *
     * <p>A passive is exempt from the firing test because it has no button: nothing presses it, so its
     * success count is zero however well it works. That exemption is a real weakness -- a passive
     * whose listener never runs is indistinguishable here from one that runs and does nothing, and
     * both land in {@link SkillRelevanceBucket#INERT}. The artifact says so rather than papering over
     * it; closing it properly needs per-skill damage attribution, which the pipeline's reason labels
     * could support but do not yet.
     *
     * <p>A driven skill that fired but fired rarely is reported undrivable rather than inert. An inert
     * verdict is a claim that the skill had its chance and did nothing, and two successes across a
     * sweep do not support that claim -- the honest reading is that the engine barely drove it, which
     * is a harness problem. {@link SkillVerdict#evidence()} prints the count, so the reviewer can see
     * which of the two they are looking at.
     */
    private static SkillRelevanceBucket bucket(ActivationArchetype archetype,
                                               int successes,
                                               boolean significant,
                                               AuditThresholds thresholds) {
        if (significant) {
            return SkillRelevanceBucket.RELEVANT;
        }
        if (archetype != ActivationArchetype.PASSIVE && successes < thresholds.minSuccesses()) {
            return SkillRelevanceBucket.UNDRIVABLE;
        }
        return SkillRelevanceBucket.INERT;
    }

    /**
     * Difference between a baseline figure and a measured one, or zero when either is missing.
     *
     * <p>A missing figure is not a zero-valued one: a matchup that never resolved has no TTK, and
     * treating that as "the same as baseline" would classify a skill that made the fight
     * <em>unwinnable</em> as inert. Both sides must have measured something for the comparison to mean
     * anything.
     */
    private static double delta(@Nullable Double baseline, @Nullable Double measured, boolean lowerIsBetter) {
        if (baseline == null || measured == null) {
            return 0;
        }
        return lowerIsBetter ? baseline - measured : measured - baseline;
    }

    /**
     * The largest movement in the energy economy this skill is responsible for.
     *
     * <p>Only the four flows a skill can cause are compared. Natural regeneration and natural
     * degeneration are excluded deliberately: both scale with how long the duel lasted, so a skill
     * that merely killed faster would show a large "energy delta" that says nothing about energy.
     * Maximum capacity is included because it is the only trace {@code Energy Pool} leaves anywhere.
     */
    private static double energyDelta(@Nullable SimEnergyLedger.EnergyUse baseline,
                                      @Nullable SimEnergyLedger.EnergyUse measured) {
        if (measured == null) {
            return 0;
        }
        final double baseSpent = baseline == null ? 0 : baseline.spentOnSkills();
        final double baseDrained = baseline == null ? 0 : baseline.drainedCustom();
        final double baseRegen = baseline == null ? 0 : baseline.regenCustom();
        final double baseMax = baseline == null ? 0 : baseline.maxEnergy();

        double worst = measured.spentOnSkills() - baseSpent;
        worst = larger(worst, measured.drainedCustom() - baseDrained);
        worst = larger(worst, measured.regenCustom() - baseRegen);
        worst = larger(worst, measured.maxEnergy() - baseMax);
        return worst;
    }

    private static double larger(double current, double candidate) {
        return Math.abs(candidate) > Math.abs(current) ? candidate : current;
    }

    /**
     * The marker interfaces the skill declares, so the artifact can expose disagreements between what
     * a skill is labelled and what it was measured doing.
     *
     * <p>This is half the value of running the audit at all: a skill measured relevant that carries no
     * {@code DamageSkill} or {@code EnergySkill} marker is a champions-side labelling bug, and a
     * {@code DamageSkill} measured inert is either broken or undrivable. Neither is visible from the
     * markers alone.
     */
    private static String markers(@Nullable Skill skill) {
        if (skill == null) {
            return "unresolved";
        }
        final List<String> markers = new ArrayList<>();
        if (skill instanceof DamageSkill) {
            markers.add("Damage");
        }
        if (skill instanceof EnergySkill) {
            markers.add("Energy");
        }
        if (skill instanceof EnergyChannelSkill) {
            markers.add("EnergyChannel");
        }
        if (skill instanceof OffensiveSkill) {
            markers.add("Offensive");
        }
        if (skill instanceof DefensiveSkill) {
            markers.add("Defensive");
        }
        if (skill instanceof BuffSkill) {
            markers.add("Buff");
        }
        if (skill instanceof CrowdControlSkill) {
            markers.add("CrowdControl");
        }
        if (skill instanceof MovementSkill) {
            markers.add("Movement");
        }
        if (skill instanceof PassiveSkill) {
            markers.add("Passive");
        }
        return markers.isEmpty() ? "none" : String.join("+", markers);
    }

    /** A single-skill result, held until every baseline has been seen. */
    private record Observation(MatchupKey key,
                               SimSkillAllocation allocation,
                               String role,
                               SimMeasurement.Aggregate aggregate) {
    }

    /**
     * Everything except the skill that identifies a matchup.
     *
     * <p>The weapon is part of the key because the booster {@code +1} makes the same allocation a
     * different build, and the target is because a skill can matter against armour and not against a
     * bare role. Runes are included for completeness; the {@code SKILLS} tier does not vary them.
     */
    private record MatchupKey(String role,
                              String weaponKey,
                              List<String> runeKeys,
                              String targetRole,
                              String targetArmor) {

        private static MatchupKey of(SimBuildSpec build, SimTargetSpec target) {
            return new MatchupKey(build.role(), build.weaponKey(), List.copyOf(build.runeKeys()),
                    target.role(), target.armorSetId());
        }
    }
}
