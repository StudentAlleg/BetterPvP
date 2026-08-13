"""Tests for the gold marts' delta logic.

Every one of these is a join of the fact against a baseline slice of itself, which is
the class of transform that fails *quietly*: a wrong key produces a mart that is empty
or over-counted rather than one that errors, and neither is visible in the output. The
over-counting case is not hypothetical -- `rune_set_synergy` shipped with the member sum
grouped by build rather than by matchup and reported a 2-rune set as having 30 members,
so the synergy figure was wrong by a factor of the target count and looked like an
ordinary large negative number.
"""
from __future__ import annotations

import pytest
from pyspark.sql.types import (
    BooleanType,
    DoubleType,
    IntegerType,
    LongType,
    StringType,
    StructField,
    StructType,
)

from jobs.gold import marts

# The subset of gold `matchup` the delta marts actually read. Narrower than the real
# fact on purpose: a test that had to spell all sixty columns would be rewritten every
# time one was added, and would stop being read.
FACT_SCHEMA = StructType(
    [
        StructField("run_id", LongType()),
        StructField("result_id", LongType()),
        StructField("build_id", LongType()),
        StructField("role", StringType()),
        StructField("weapon_key", StringType()),
        StructField("weapon_profile_key", StringType()),
        StructField("weapon_roll", StringType()),
        StructField("weapon_damage_applied", DoubleType()),
        # The two axes effect_interaction correlates a contribution against. Carried on
        # the fact rather than joined back in, so the contribution marts can be read on
        # their own -- which is also what lets them be unioned into one effect table.
        StructField("weapon_attack_speed_applied", DoubleType()),
        StructField("swings_per_second", DoubleType()),
        StructField("target_role", StringType()),
        StructField("target_armor", StringType()),
        StructField("rune_count", IntegerType()),
        StructField("rune_set_key", StringType()),
        StructField("skill_count", IntegerType()),
        StructField("skill_set_key", StringType()),
        StructField("dmg_per_hit", DoubleType()),
        StructField("dps_sustained", DoubleType()),
        StructField("dps_burst", DoubleType()),
        StructField("ttk_s", DoubleType()),
        StructField("hits_to_kill", DoubleType()),
        StructField("kill_rate", DoubleType()),
        StructField("provenance", StringType()),
        StructField("derived_skill", StringType()),
        StructField("derived_skill_level", IntegerType()),
        StructField("derived_bonus_per_hit", DoubleType()),
        StructField("derived_uptime", DoubleType()),
        StructField("energy_limited", BooleanType()),
    ]
)


def _fact_row(**overrides):
    row = dict(
        run_id=1,
        result_id=1,
        build_id=1,
        role="ASSASSIN",
        weapon_key="champions:alligators_tooth",
        weapon_profile_key="none|6.000|7.000|8.000|0.000",
        weapon_roll="base",
        weapon_damage_applied=7.0,
        weapon_attack_speed_applied=0.0,
        swings_per_second=3.125,
        target_role="ASSASSIN",
        target_armor="none",
        rune_count=0,
        rune_set_key="",
        skill_count=0,
        skill_set_key="",
        dmg_per_hit=7.0,
        dps_sustained=21.875,
        dps_burst=21.0,
        ttk_s=1.6,
        hits_to_kill=5.0,
        kill_rate=1.0,
        provenance="measured",
        derived_skill=None,
        derived_skill_level=None,
        derived_bonus_per_hit=None,
        derived_uptime=None,
        energy_limited=False,
    )
    row.update(overrides)
    return row


# One bare baseline, one single-rune build worth +1 damage per hit, and one two-rune set
# carrying that rune plus a second worth +2. All against the same target, so all three
# share a baseline key.
BASELINE = _fact_row(result_id=1, build_id=1)
ONE_RUNE = _fact_row(
    result_id=2, build_id=2, rune_count=1, rune_set_key="core:brutality",
    dmg_per_hit=8.0, dps_sustained=26.667, ttk_s=1.2, hits_to_kill=4.0,
)
OTHER_RUNE = _fact_row(
    result_id=3, build_id=3, rune_count=1, rune_set_key="core:emerald",
    dmg_per_hit=9.0, dps_sustained=27.0, ttk_s=1.2, hits_to_kill=4.0,
)
RUNE_SET = _fact_row(
    result_id=4, build_id=4, rune_count=2, rune_set_key="core:brutality+core:emerald",
    dmg_per_hit=10.0, dps_sustained=37.5, ttk_s=0.8, hits_to_kill=3.0,
)


def test_rune_contribution_is_measured_against_the_matching_bare_row(frame):
    fact = frame([BASELINE, ONE_RUNE], FACT_SCHEMA)
    rows = marts.rune_contribution(fact).collect()

    assert len(rows) == 1
    row = rows[0]
    assert row["rune_key"] == "core:brutality"
    assert row["base_dps"] == pytest.approx(21.875)
    assert row["dps_delta"] == pytest.approx(26.667 - 21.875, abs=1e-3)
    assert row["dmg_per_hit_delta"] == pytest.approx(1.0)
    # The figure a designer feels. 5 hits down to 4 is a whole swing.
    assert row["hits_saved"] == pytest.approx(1.0)


def test_multi_rune_builds_are_not_attributed_to_their_runes(frame):
    """A build carrying several runes cannot be decomposed afterwards -- DESIGN.md's
    stated reason for giving runes their own sweep tier. Inventing a decomposition in
    the mart would undo that reasoning."""
    fact = frame([BASELINE, RUNE_SET], FACT_SCHEMA)
    assert marts.rune_contribution(fact).isEmpty()


def test_a_rune_with_no_bare_counterpart_is_dropped_not_zeroed(frame):
    """Without a baseline there is no delta. Reporting zero would claim the rune does
    nothing, which is a different and much worse statement than saying nothing."""
    fact = frame([ONE_RUNE], FACT_SCHEMA)
    assert marts.rune_contribution(fact).isEmpty()


def test_measured_rows_join_despite_null_derived_columns(frame):
    """`derived_skill` is null on every measured row and `a = b` is never true for two
    nulls, so a plain equality join over the baseline key silently matches nothing.
    This is the eqNullSafe regression test."""
    fact = frame([BASELINE, ONE_RUNE], FACT_SCHEMA)
    assert marts.rune_contribution(fact).count() == 1


def test_derived_rows_compare_against_the_derived_baseline(frame):
    """A Backstab row must be measured against the Backstab-at-the-same-level bare row,
    not against a measured one -- otherwise the rune's delta absorbs the skill's."""
    derived_base = _fact_row(
        result_id=-101, build_id=1, provenance="derived", derived_skill="Backstab",
        derived_skill_level=1, derived_bonus_per_hit=1.5, derived_uptime=1.0,
        dmg_per_hit=8.5, dps_sustained=28.333, ttk_s=1.2, hits_to_kill=4.0,
    )
    derived_rune = _fact_row(
        result_id=-201, build_id=2, rune_count=1, rune_set_key="core:brutality",
        provenance="derived", derived_skill="Backstab", derived_skill_level=1,
        derived_bonus_per_hit=1.5, derived_uptime=1.0,
        dmg_per_hit=9.5, dps_sustained=31.667, ttk_s=1.2, hits_to_kill=4.0,
    )
    fact = frame([BASELINE, ONE_RUNE, derived_base, derived_rune], FACT_SCHEMA)

    by_provenance = {r["provenance"]: r for r in marts.rune_contribution(fact).collect()}
    assert set(by_provenance) == {"measured", "derived"}
    assert by_provenance["derived"]["base_dps"] == pytest.approx(28.333, abs=1e-3)
    assert by_provenance["measured"]["base_dps"] == pytest.approx(21.875)


def test_a_rune_is_measured_against_the_baseline_carrying_the_same_skill(frame):
    """The regression test for the failure that stopped run 12 in gold.

    On a SKILLS sweep the bare builds differ *only* by the skill they carry. Without
    `skill_set_key` in the baseline key they all collapse onto one baseline, and the
    join fans out: one rune row matches every skill's bare row, so a single rune is
    reported once per skill and each delta is measured against the wrong build.

    Two skills, each with a bare row and a one-rune row, and the rune is worth exactly
    +1 dmg/hit under both. The right answer is two contribution rows, each against its
    own baseline. The pre-fix behaviour was four rows, two of them nonsense.
    """
    frailty_base = _fact_row(
        result_id=10, build_id=10, skill_count=1, skill_set_key="Frailty:3",
        dmg_per_hit=9.0, dps_sustained=28.125, ttk_s=1.4, hits_to_kill=4.0,
    )
    frailty_rune = _fact_row(
        result_id=11, build_id=11, skill_count=1, skill_set_key="Frailty:3",
        rune_count=1, rune_set_key="core:brutality",
        dmg_per_hit=10.0, dps_sustained=31.25, ttk_s=1.3, hits_to_kill=4.0,
    )
    sacrifice_base = _fact_row(
        result_id=20, build_id=20, skill_count=1, skill_set_key="Sacrifice:3",
        dmg_per_hit=7.5, dps_sustained=23.4375, ttk_s=1.5, hits_to_kill=5.0,
    )
    sacrifice_rune = _fact_row(
        result_id=21, build_id=21, skill_count=1, skill_set_key="Sacrifice:3",
        rune_count=1, rune_set_key="core:brutality",
        dmg_per_hit=8.5, dps_sustained=26.5625, ttk_s=1.4, hits_to_kill=5.0,
    )
    fact = frame([frailty_base, frailty_rune, sacrifice_base, sacrifice_rune], FACT_SCHEMA)

    rows = {r["skill_set_key"]: r for r in marts.rune_contribution(fact).collect()}
    assert len(rows) == 2, "the rune fanned out across skill loadouts"
    assert rows["Frailty:3"]["base_dps"] == pytest.approx(28.125)
    assert rows["Sacrifice:3"]["base_dps"] == pytest.approx(23.4375)
    # Same rune, same marginal value, measured correctly under both skills.
    for row in rows.values():
        assert row["dmg_per_hit_delta"] == pytest.approx(1.0)


def test_a_skill_is_still_measured_against_a_skill_less_baseline(frame):
    """The other half of the same change, and the one it could easily have broken.

    `skill_contribution` compares a skilled row to an *unskilled* one, so
    `skill_set_key` must be excluded from its join key. Had it been left in, a
    'Frailty:3' row would look for a skill-less baseline that also reads 'Frailty:3',
    match nothing, and the mart would come out empty rather than wrong.
    """
    skilled = _fact_row(
        result_id=30, build_id=30, skill_count=1, skill_set_key="Frailty:3",
        dmg_per_hit=9.0, dps_sustained=28.125, ttk_s=1.4, hits_to_kill=4.0,
    )
    fact = frame([BASELINE, skilled], FACT_SCHEMA)

    rows = marts.skill_contribution(fact).collect()
    assert len(rows) == 1
    assert rows[0]["base_dps"] == pytest.approx(21.875), "did not find the bare baseline"
    assert rows[0]["dps_delta"] == pytest.approx(28.125 - 21.875, abs=1e-3)


def test_run_diff_identifies_rows_present_only_in_run_b(frame):
    """A full outer join taking the descriptors from the left side alone leaves every
    only_b row with a null role, weapon and skill set. On the 1-vs-7 diff that was
    4,312,263 rows saying only that run B added *something*."""
    from pyspark.sql import functions as F

    only_in_b = _fact_row(
        result_id=40, build_id=40, run_id=2, skill_count=1, skill_set_key="Frailty:3",
        rune_count=1, rune_set_key="core:brutality", weapon_key="champions:thornfang",
    )
    # fingerprint is not in the narrowed schema; stand it in from the weapon key, as the
    # overlap test above does. The two rows must not share one, or nothing is only_b.
    a = frame([BASELINE], FACT_SCHEMA).withColumn("fingerprint", F.col("weapon_key"))
    b = frame([only_in_b], FACT_SCHEMA).withColumn("fingerprint", F.col("weapon_key"))
    diff = marts.run_diff(a, b)

    rows = {r["overlap"]: r for r in diff.collect()}
    assert set(rows) == {"only_a", "only_b"}
    added = rows["only_b"]
    assert added["role"] == "ASSASSIN"
    assert added["weapon_key"] == "champions:thornfang"
    assert added["skill_set_key"] == "Frailty:3"


def test_synergy_counts_each_set_member_exactly_once(frame):
    fact = frame([BASELINE, ONE_RUNE, OTHER_RUNE, RUNE_SET], FACT_SCHEMA)
    contributions = marts.rune_contribution(fact)

    rows = marts.rune_set_synergy(fact, contributions).collect()
    assert len(rows) == 1
    row = rows[0]
    assert row["runes_attributed"] == 2
    assert row["attribution_complete"] is True
    assert row["set_dps_delta"] == pytest.approx(37.5 - 21.875)
    # (26.667 - 21.875) + (27.0 - 21.875)
    assert row["sum_individual_dps_delta"] == pytest.approx(9.917, abs=1e-3)
    assert row["synergy_dps"] == pytest.approx(row["set_dps_delta"] - 9.917, abs=1e-3)


def test_synergy_members_are_not_recounted_per_target(frame):
    """The regression this file opens with. The same build measured against two targets
    is two matchups, and each must attribute its own two members -- not four, and not
    two per target folded into one row."""
    second_target = dict(target_role="KNIGHT", target_armor="none")
    fact = frame(
        [
            BASELINE,
            ONE_RUNE,
            OTHER_RUNE,
            RUNE_SET,
            _fact_row(result_id=11, build_id=1, **second_target),
            _fact_row(result_id=12, build_id=2, rune_count=1, rune_set_key="core:brutality",
                      dmg_per_hit=8.0, dps_sustained=26.667, ttk_s=1.2, hits_to_kill=4.0,
                      **second_target),
            _fact_row(result_id=13, build_id=3, rune_count=1, rune_set_key="core:emerald",
                      dmg_per_hit=9.0, dps_sustained=27.0, ttk_s=1.2, hits_to_kill=4.0,
                      **second_target),
            _fact_row(result_id=14, build_id=4, rune_count=2,
                      rune_set_key="core:brutality+core:emerald",
                      dmg_per_hit=10.0, dps_sustained=37.5, ttk_s=0.8, hits_to_kill=3.0,
                      **second_target),
        ],
        FACT_SCHEMA,
    )
    rows = marts.rune_set_synergy(fact, marts.rune_contribution(fact)).collect()

    assert len(rows) == 2
    assert {r["target_role"] for r in rows} == {"ASSASSIN", "KNIGHT"}
    assert all(r["runes_attributed"] == 2 for r in rows)
    assert all(r["attribution_complete"] for r in rows)


def test_incomplete_attribution_is_flagged(frame):
    """A set whose member was never measured alone has an incomplete sum, and the
    shortfall would read as synergy. The flag is what makes the mart safe to average."""
    fact = frame([BASELINE, ONE_RUNE, RUNE_SET], FACT_SCHEMA)
    rows = marts.rune_set_synergy(fact, marts.rune_contribution(fact)).collect()

    assert len(rows) == 1
    assert rows[0]["runes_attributed"] == 1
    assert rows[0]["attribution_complete"] is False


def test_run_diff_reports_overlap_rather_than_dropping_it(frame):
    """A build in one run and not the other contributes no delta. Inner-joining would
    delete exactly the fact that says the remaining deltas are a biased sample."""
    a = frame(
        [
            _fact_row(run_id=1, result_id=1, weapon_key="w1"),
            _fact_row(run_id=1, result_id=2, build_id=9, weapon_key="w2"),
        ],
        FACT_SCHEMA,
    )
    b = frame([_fact_row(run_id=2, result_id=1, weapon_key="w1", dps_sustained=25.0)], FACT_SCHEMA)

    # fingerprint is not in the narrowed schema, so stand it in from the weapon key --
    # the mart only requires that the column exists and identifies a build.
    from pyspark.sql import functions as F

    a = a.withColumn("fingerprint", F.col("weapon_key"))
    b = b.withColumn("fingerprint", F.col("weapon_key"))

    rows = marts.run_diff(a, b).collect()
    overlap = {r["fingerprint"]: r for r in rows}
    assert overlap["w1"]["overlap"] == "both"
    assert overlap["w1"]["dps_delta"] == pytest.approx(25.0 - 21.875)
    assert overlap["w2"]["overlap"] == "only_a"
    assert overlap["w2"]["dps_delta"] is None


# ---------------------------------------------------------------------------
# effect_interaction
#
# The mart answers "does this effect compound with attack speed", and the whole risk in
# it is answering when it should not. A correlation over one carrier weapon is a number
# that looks exactly like a finding, so these tests are mostly about the cases where the
# right output is a refusal.
# ---------------------------------------------------------------------------


def _effect_row(**overrides):
    row = dict(
        run_id=1,
        role="ASSASSIN",
        weapon_profile_key="p1",
        weapon_key="w1",
        weapon_roll="base",
        weapon_damage_applied=7.0,
        weapon_attack_speed_applied=0.0,
        swings_per_second=3.0,
        target_role="ASSASSIN",
        target_armor="none",
        provenance="measured",
        effect_kind="rune",
        effect_key="core:brutality",
        effect_level=None,
        dps_delta=1.0,
        dps_delta_pct=0.05,
        ttk_delta=-0.1,
        hits_saved=0.5,
    )
    row.update(overrides)
    return row


def _carriers(deltas_by_speed, **overrides):
    """One row per carrier weapon, at a given speed and delta."""
    return [
        _effect_row(
            weapon_profile_key=f"p{i}",
            weapon_key=f"w{i}",
            weapon_attack_speed_applied=speed,
            weapon_damage_applied=7.0,
            dps_delta=delta,
            **overrides,
        )
        for i, (speed, delta) in enumerate(deltas_by_speed)
    ]


EFFECT_SCHEMA = StructType(
    [
        StructField("run_id", LongType()),
        StructField("role", StringType()),
        StructField("weapon_profile_key", StringType()),
        StructField("weapon_key", StringType()),
        StructField("weapon_roll", StringType()),
        StructField("weapon_damage_applied", DoubleType()),
        StructField("weapon_attack_speed_applied", DoubleType()),
        StructField("swings_per_second", DoubleType()),
        StructField("target_role", StringType()),
        StructField("target_armor", StringType()),
        StructField("provenance", StringType()),
        StructField("effect_kind", StringType()),
        StructField("effect_key", StringType()),
        StructField("effect_level", IntegerType()),
        StructField("dps_delta", DoubleType()),
        StructField("dps_delta_pct", DoubleType()),
        StructField("ttk_delta", DoubleType()),
        StructField("hits_saved", DoubleType()),
    ]
)


def test_a_single_carrier_weapon_yields_no_verdict(frame):
    """One weapon means no axis, and the mart must say so rather than produce a number.

    This is the normal state of a MELEE sweep, not an edge case -- and before the
    correlation was computed from its parts it was not merely wrong here, it raised
    DIVIDE_BY_ZERO under ANSI mode and took the whole layer down.
    """
    # Three rows, one profile key: several measurements of one carrier are still one
    # carrier, and the count that decides the verdict is of weapons, not of rows.
    effects = frame([_effect_row(dps_delta=d) for d in (1.0, 1.2, 0.9)], EFFECT_SCHEMA)
    row = marts.effect_interaction(effects).collect()[0]

    assert row["weapons"] == 1
    assert row["correlation_reliable"] is False
    assert row["interaction"] == "too few weapons"
    assert row["speed_corr"] is None, "a constant axis must be null, not 0.0"
    assert row["tracks"] == "unknown"


def test_an_effect_worth_more_on_faster_weapons_compounds(frame):
    """The finding the mart exists to surface."""
    effects = frame(
        _carriers([(-0.2, 1.0), (0.0, 2.0), (0.2, 3.0), (0.4, 4.0)]), EFFECT_SCHEMA
    )
    row = marts.effect_interaction(effects).collect()[0]

    assert row["weapons"] == 4
    assert row["correlation_reliable"] is True
    assert row["speed_corr"] == pytest.approx(1.0)
    assert row["interaction"] == "compounds with attack speed"


def test_an_effect_worth_less_on_faster_weapons_contends(frame):
    """The trap: a combination the item screen encourages and the numbers punish."""
    effects = frame(
        _carriers([(-0.2, 4.0), (0.0, 3.0), (0.2, 2.0), (0.4, 1.0)]), EFFECT_SCHEMA
    )
    row = marts.effect_interaction(effects).collect()[0]

    assert row["speed_corr"] == pytest.approx(-1.0)
    assert row["interaction"] == "contends with attack speed"


def test_an_effect_tracking_damage_is_named_as_such(frame):
    """The confound, which is the reason both coefficients are on the row.

    Fast weapons hit softer because that is how weapons are balanced, so speed and
    damage are correlated across the catalog and an effect that really scales with
    damage shows a *negative* speed correlation. Reading speed_corr alone would call
    this a contention; `tracks` is what stops that.
    """
    rows = [
        _effect_row(weapon_profile_key=f"p{i}", weapon_key=f"w{i}",
                    weapon_attack_speed_applied=speed, weapon_damage_applied=damage,
                    dps_delta=delta)
        for i, (speed, damage, delta) in enumerate(
            [(0.4, 4.0, 1.0), (0.2, 6.0, 1.5), (0.0, 8.0, 2.0), (-0.2, 10.0, 2.5)]
        )
    ]
    row = marts.effect_interaction(frame(rows, EFFECT_SCHEMA)).collect()[0]

    assert row["speed_corr"] == pytest.approx(-1.0)
    assert row["damage_corr"] == pytest.approx(1.0)
    # Equal magnitudes tie to damage, which is the conservative call: an attack-speed
    # interaction is the stronger claim and should need to win outright.
    assert row["tracks"] == "weapon damage"


def test_two_carriers_are_still_too_few(frame):
    """Three is the floor. Two points always correlate perfectly and never mean it."""
    effects = frame(_carriers([(0.0, 1.0), (0.4, 2.0)]), EFFECT_SCHEMA)
    row = marts.effect_interaction(effects).collect()[0]

    assert row["weapons"] == 2
    assert row["correlation_reliable"] is False
    assert row["interaction"] == "too few weapons"


def test_runes_and_skills_stay_distinguishable(frame):
    """Different currencies. A rune is a drop; a skill is twelve points."""
    effects = frame(
        _carriers([(0.0, 1.0), (0.2, 2.0), (0.4, 3.0)])
        + _carriers([(0.0, 5.0), (0.2, 6.0), (0.4, 7.0)], effect_kind="skill",
                    effect_key="Blood Shield"),
        EFFECT_SCHEMA,
    )
    kinds = {row["effect_kind"]: row for row in marts.effect_interaction(effects).collect()}

    assert set(kinds) == {"rune", "skill"}
    assert kinds["skill"]["dps_delta_mean"] == pytest.approx(6.0)
    assert kinds["rune"]["dps_delta_mean"] == pytest.approx(2.0)
