package me.mykindos.betterpvp.balancesim.audit;

import me.mykindos.betterpvp.balancesim.catalog.SimBuildSpec;
import me.mykindos.betterpvp.balancesim.catalog.SimSkillAllocation;
import me.mykindos.betterpvp.balancesim.catalog.SimStatRoll;
import me.mykindos.betterpvp.balancesim.catalog.SimTargetSpec;
import me.mykindos.betterpvp.balancesim.catalog.SimWeaponProfile;
import me.mykindos.betterpvp.balancesim.engine.SimEnergyLedger;
import me.mykindos.betterpvp.balancesim.engine.SimMeasurement;
import me.mykindos.betterpvp.balancesim.engine.SimSkillLedger;
import me.mykindos.betterpvp.champions.champions.skills.ChampionsSkillManager;
import me.mykindos.betterpvp.champions.champions.skills.Skill;
import me.mykindos.betterpvp.champions.champions.skills.types.InteractSkill;
import me.mykindos.betterpvp.champions.champions.skills.types.PassiveSkill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Covers the classifier's decisions, with particular attention to the ones a damage-only audit gets
 * wrong. Every case here corresponds to a real skill.
 */
class SkillRelevanceAuditTest {

    private static final String ROLE = "MAGE";
    private static final String WEAPON = "iron_sword";

    /**
     * The one weapon every build here carries. Held constant because the audit keys a matchup on the
     * weapon: a build measured on a different one is a different baseline, not a comparable row.
     */
    private static final SimWeaponProfile WEAPON_PROFILE =
            new SimWeaponProfile(6.0, 5.0, 7.0, 0.0, -0.25, 0.25, false, "SWORD", List.of());
    private static final AuditThresholds THRESHOLDS = AuditThresholds.defaults();

    private ChampionsSkillManager skillManager;

    @BeforeEach
    void setUp() {
        skillManager = mock(ChampionsSkillManager.class);
    }

    @Test
    @DisplayName("a skill that shortens time to kill is relevant")
    void damageSkillIsRelevant() {
        final SkillRelevanceAudit audit = audit("Fireball", passiveSkill());
        audit.observe(baselineBuild(), target(), aggregate(4.0, 20.0, null, Map.of()));
        audit.observe(skillBuild("Fireball", 3), target(),
                aggregate(2.5, 32.0, null, fired("Fireball", 10)));

        final SkillVerdict verdict = only(audit);
        assertEquals(SkillRelevanceBucket.RELEVANT, verdict.bucket());
        assertEquals(3, verdict.bestLevel());
        assertEquals(1.5, verdict.bestTtkDelta(), 1e-6);
    }

    @Test
    @DisplayName("a skill that changes nothing measurable is inert")
    void unmovingSkillIsInert() {
        final SkillRelevanceAudit audit = audit("Taunt", passiveSkill());
        audit.observe(baselineBuild(), target(), aggregate(4.0, 20.0, null, Map.of()));
        // Inside both the absolute and relative thresholds on every dimension.
        audit.observe(skillBuild("Taunt", 1), target(),
                aggregate(4.02, 20.1, null, fired("Taunt", 10)));

        assertEquals(SkillRelevanceBucket.INERT, only(audit).bucket());
    }

    @Test
    @DisplayName("Null Blade: siphons energy, deals no damage -- relevant on energy alone")
    void energySiphonIsRelevantWithoutDamage() {
        final SkillRelevanceAudit audit = audit("Null Blade", passiveSkill());
        audit.observe(baselineBuild(), target(),
                aggregate(4.0, 20.0, energy(0, 0, 0, 150), Map.of()));
        // Identical damage figures; the only difference is 9 energy siphoned per duel, which is
        // exactly what Null Blade does at level 1.
        audit.observe(skillBuild("Null Blade", 1), target(),
                aggregate(4.0, 20.0, energy(0, 0, 9, 150), fired("Null Blade", 10)));

        final SkillVerdict verdict = only(audit);
        assertEquals(SkillRelevanceBucket.RELEVANT, verdict.bucket());
        assertTrue(verdict.isEnergyOnly(), "should be flagged as relevant on energy alone");
        assertEquals(9.0, verdict.energyDelta(), 1e-6);
    }

    @Test
    @DisplayName("Energy Pool: raises max energy, moves none -- still relevant")
    void maxEnergyIncreaseIsRelevant() {
        final SkillRelevanceAudit audit = audit("Energy Pool", passiveSkill());
        audit.observe(baselineBuild(), target(),
                aggregate(4.0, 20.0, energy(0, 0, 0, 150), Map.of()));
        // No flow at all: the entire effect is 30 more capacity.
        audit.observe(skillBuild("Energy Pool", 1), target(),
                aggregate(4.0, 20.0, energy(0, 0, 0, 180), fired("Energy Pool", 10)));

        final SkillVerdict verdict = only(audit);
        assertEquals(SkillRelevanceBucket.RELEVANT, verdict.bucket());
        assertTrue(verdict.isEnergyOnly());
        assertEquals(30.0, verdict.energyDelta(), 1e-6);
    }

    @Test
    @DisplayName("a driven skill the chain never let through is undrivable, not inert")
    void neverFiredIsUndrivable() {
        final SkillRelevanceAudit audit = audit("Blizzard", interactSkill());
        audit.observe(baselineBuild(), target(), aggregate(4.0, 20.0, null, Map.of()));
        // Pressed 40 times, never once allowed through.
        audit.observe(skillBuild("Blizzard", 2), target(),
                aggregate(4.0, 20.0, null,
                        Map.of("Blizzard", new SimSkillLedger.SkillActivation(40, 0, 0, 0, 40, 0))));

        final SkillVerdict verdict = only(audit);
        assertEquals(SkillRelevanceBucket.UNDRIVABLE, verdict.bucket());
        assertTrue(verdict.evidence().contains("NEVER FIRED"));
    }

    @Test
    @DisplayName("a passive is never called undrivable, because it has no button to press")
    void passiveWithNoActivationsIsInertNotUndrivable() {
        final SkillRelevanceAudit audit = audit("Bloodlust", passiveSkill());
        audit.observe(baselineBuild(), target(), aggregate(4.0, 20.0, null, Map.of()));
        audit.observe(skillBuild("Bloodlust", 1), target(), aggregate(4.0, 20.0, null, Map.of()));

        assertEquals(SkillRelevanceBucket.INERT, only(audit).bucket());
    }

    @Test
    @DisplayName("a skill that makes an unwinnable fight winnable is relevant despite no TTK delta")
    void turningATimeoutIntoAKillIsRelevant() {
        final SkillRelevanceAudit audit = audit("Immolate", passiveSkill());
        // The baseline never killed, so it has no TTK at all -- every numeric delta is zero.
        audit.observe(baselineBuild(), target(), aggregate(null, 2.0, null, Map.of()));
        audit.observe(skillBuild("Immolate", 5), target(),
                aggregate(8.0, 9.0, null, fired("Immolate", 6)));

        assertEquals(SkillRelevanceBucket.RELEVANT, only(audit).bucket());
    }

    @Test
    @DisplayName("a skill relevant against armour only is still relevant")
    void relevantAgainstOneTargetOnly() {
        final SkillRelevanceAudit audit = audit("Shatter", passiveSkill());
        final SimTargetSpec bare = new SimTargetSpec("KNIGHT", "none", 40, List.of(), 0);
        final SimTargetSpec armoured = new SimTargetSpec("KNIGHT", "role", 60, List.of(), 0);

        audit.observe(baselineBuild(), bare, aggregate(4.0, 20.0, null, Map.of()));
        audit.observe(baselineBuild(), armoured, aggregate(9.0, 20.0, null, Map.of()));
        // Nothing against a bare target, a full second against armour. Pooling the two would average
        // the effect below the threshold.
        audit.observe(skillBuild("Shatter", 4), bare, aggregate(4.0, 20.0, null, fired("Shatter", 8)));
        audit.observe(skillBuild("Shatter", 4), armoured, aggregate(8.0, 24.0, null, fired("Shatter", 8)));

        assertEquals(SkillRelevanceBucket.RELEVANT, only(audit).bucket());
    }

    @Test
    @DisplayName("builds carrying several skills are skipped rather than attributed to one of them")
    void multiSkillBuildsAreSkipped() {
        final SkillRelevanceAudit audit = audit("Fireball", passiveSkill());
        audit.observe(baselineBuild(), target(), aggregate(4.0, 20.0, null, Map.of()));
        final SimBuildSpec pair = build(List.of(new SimSkillAllocation("Fireball", "SWORD", 2, 2),
                        new SimSkillAllocation("Blizzard", "AXE", 2, 2)),
                4, "fp-pair");
        audit.observe(pair, target(), aggregate(1.0, 90.0, null, Map.of()));

        assertEquals(1, audit.multiSkillBuildsSkipped());
        assertTrue(audit.classify().isEmpty(), "a multi-skill build must produce no verdict");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SkillRelevanceAudit audit(String skillName, Skill skill) {
        when(skillManager.getObject(anyString())).thenReturn(Optional.empty());
        when(skillManager.getObject(skillName)).thenReturn(Optional.of(skill));
        return new SkillRelevanceAudit(skillManager, THRESHOLDS);
    }

    /** Mockito's extra-interfaces support is what makes {@code instanceof} classification testable. */
    private static Skill passiveSkill() {
        return mock(Skill.class, withSettings().extraInterfaces(PassiveSkill.class));
    }

    private static Skill interactSkill() {
        return mock(Skill.class, withSettings().extraInterfaces(InteractSkill.class));
    }

    private static SkillVerdict only(SkillRelevanceAudit audit) {
        final List<SkillVerdict> verdicts = audit.classify();
        assertEquals(1, verdicts.size(), "expected exactly one verdict, got " + verdicts);
        return verdicts.get(0);
    }

    private static SimTargetSpec target() {
        return new SimTargetSpec("KNIGHT", "none", 40, List.of(), 0);
    }

    private static SimBuildSpec baselineBuild() {
        return build(List.of(), 0, "fp-baseline");
    }

    private static SimBuildSpec skillBuild(String skillName, int level) {
        return build(List.of(new SimSkillAllocation(skillName, "SWORD", level, level)),
                level, "fp-" + skillName + "-" + level);
    }

    /**
     * A build on the fixed test weapon, differing from its siblings only in the skills it carries.
     *
     * <p>Which is the whole premise of the audit under test: a verdict is the delta between two rows
     * that agree on everything except one skill, so every helper here has to hold the weapon -- and
     * therefore its profile and alias list -- constant. Routing them through one place is what stops a
     * future field from being filled in differently per call site and quietly breaking the matchup key.
     */
    private static SimBuildSpec build(List<SimSkillAllocation> skills, int points, String fingerprint) {
        // The roll is held constant alongside the weapon, for the reason the javadoc gives: a verdict
        // is a delta between rows agreeing on everything but one skill, and two rolls of one weapon
        // deal different damage.
        return new SimBuildSpec(ROLE, WEAPON, "none", List.of(), SimStatRoll.DEFAULT, skills, points,
                false, fingerprint, WEAPON_PROFILE, List.of(WEAPON), "");
    }

    private static Map<String, SimSkillLedger.SkillActivation> fired(String skillName, int successes) {
        return Map.of(skillName, new SimSkillLedger.SkillActivation(successes, successes, 0, 0, 0, 0));
    }

    /**
     * @param spentOnSkills energy the rotation's own casts cost
     * @param drainedCustom energy taken by a mechanic
     * @param regenCustom   energy granted by a mechanic -- Null Blade's siphon lands here
     * @param maxEnergy     capacity, which is the only thing Energy Pool moves
     */
    private static SimEnergyLedger.EnergyUse energy(double spentOnSkills,
                                                    double drainedCustom,
                                                    double regenCustom,
                                                    double maxEnergy) {
        return new SimEnergyLedger.EnergyUse(spentOnSkills, drainedCustom, 0, 0, regenCustom,
                0, maxEnergy);
    }

    private static SimMeasurement.Aggregate aggregate(Double ttkSeconds,
                                                      Double dpsSustained,
                                                      SimEnergyLedger.EnergyUse energy,
                                                      Map<String, SimSkillLedger.SkillActivation> activations) {
        return new SimMeasurement.Aggregate(4.0, dpsSustained, dpsSustained, ttkSeconds, 5.0,
                false, activations, energy, 10, "{}");
    }
}
