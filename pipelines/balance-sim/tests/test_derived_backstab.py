"""Tests for the Backstab derivation.

These run against a local Spark session with hand-built rows rather than against the
lake, because the thing worth pinning is the *arithmetic*: a derived row has no
measured counterpart anywhere to check it against, so if the model drifts nothing
downstream will notice. The two source rows below reproduce real run-1 numbers -- a
7.0-damage weapon against a 29 HP assassin, five hits, 32 ticks -- so a change that
breaks the model breaks a case somebody can check by hand.
"""
from __future__ import annotations

import math

import pytest

from pyspark.sql.types import (
    ArrayType,
    BooleanType,
    DoubleType,
    IntegerType,
    LongType,
    MapType,
    StringType,
    StructField,
    StructType,
)

from jobs.common.audit import Auditor
from jobs.silver import derived, schemas

# The silver_result contract, declared rather than inferred. Inference cannot type a
# null column or an empty array, and half the interesting cases here are exactly
# those -- a matchup with no stddev, a target with no skills. Declaring it also means
# a column renamed in results.py fails a test instead of quietly not being asserted on.
RESULT_SCHEMA = StructType(
    [
        StructField("result_id", LongType()),
        StructField("run_id", LongType()),
        StructField("build_id", LongType()),
        StructField("target_role", StringType()),
        StructField("target_armor", StringType()),
        StructField("target_hp", DoubleType()),
        StructField("target_points", IntegerType()),
        StructField("target_skill_slots", schemas.SKILLS),
        StructField("target_skill_count", IntegerType()),
        StructField("target_role_alias_keys", ArrayType(StringType())),
        StructField("dmg_per_hit", DoubleType()),
        StructField("dps_sustained", DoubleType()),
        StructField("dps_burst", DoubleType()),
        StructField("ttk_s", DoubleType()),
        StructField("hits_to_kill", DoubleType()),
        StructField("energy_limited", BooleanType()),
        StructField("kills", LongType()),
        StructField("kill_rate", DoubleType()),
        StructField("iterations", IntegerType()),
        StructField("planned_iterations", IntegerType()),
        StructField("attacker_deaths", IntegerType()),
        StructField("energy_limited_iterations", IntegerType()),
        StructField("dmg_per_hit_stddev", DoubleType()),
        StructField("dps_p50", DoubleType()),
        StructField("dps_p90", DoubleType()),
        StructField("ttk_ticks_mean", DoubleType()),
        StructField("ttk_ticks_p50", DoubleType()),
        StructField("ttk_ticks_p90", DoubleType()),
        StructField("modifier_counts", MapType(StringType(), IntegerType())),
        StructField("activation_counts", MapType(StringType(), IntegerType())),
        StructField("energy_max", DoubleType()),
        StructField("energy_spent_per_duel", DoubleType()),
        StructField("energy_regen_custom_per_duel", DoubleType()),
        StructField("energy_regen_natural_per_duel", DoubleType()),
        StructField("extras_raw", StringType()),
        StructField("_pipeline_run_id", StringType()),
        StructField("total_damage", DoubleType()),
        StructField("swing_interval_ticks", DoubleType()),
        StructField("overkill", DoubleType()),
        StructField("overkill_fraction", DoubleType()),
        StructField("hits_per_burst_window", LongType()),
        StructField("provenance", StringType()),
        StructField("derived_skill", StringType()),
        StructField("derived_skill_level", IntegerType()),
        StructField("derived_bonus_per_hit", DoubleType()),
        StructField("derived_uptime", DoubleType()),
        StructField("source_result_id", LongType()),
    ]
)

BUILD_SCHEMA = StructType(
    [StructField("build_id", LongType()), StructField("role", StringType())]
)


@pytest.fixture()
def auditor(cfg):
    # fail_on_error stays on: a test that trips an expectation should fail loudly.
    return Auditor(cfg=cfg, run_id=-1, pipeline_run_id="pytest")


def _result_row(**overrides):
    """One measured matchup, shaped like silver_result."""
    row = dict(
        result_id=1,
        run_id=-1,
        build_id=10,
        target_role="ASSASSIN",
        target_armor="none",
        target_hp=29.0,
        target_points=0,
        target_skill_slots=[],
        target_skill_count=0,
        target_role_alias_keys=["ASSASSIN"],
        dmg_per_hit=7.0,
        dps_sustained=21.875,
        dps_burst=21.0,
        ttk_s=1.6,
        hits_to_kill=5.0,
        energy_limited=False,
        kills=1,
        kill_rate=1.0,
        iterations=1,
        planned_iterations=1,
        attacker_deaths=0,
        energy_limited_iterations=0,
        dmg_per_hit_stddev=None,
        dps_p50=21.875,
        dps_p90=21.875,
        ttk_ticks_mean=32.0,
        ttk_ticks_p50=32.0,
        ttk_ticks_p90=32.0,
        modifier_counts={},
        activation_counts={},
        energy_max=150.0,
        energy_spent_per_duel=0.0,
        energy_regen_custom_per_duel=50.0,
        energy_regen_natural_per_duel=0.0,
        extras_raw="{}",
        _pipeline_run_id="pytest",
        total_damage=35.0,
        swing_interval_ticks=8.0,
        overkill=6.0,
        overkill_fraction=6.0 / 7.0,
        hits_per_burst_window=3,
        provenance="measured",
        derived_skill=None,
        derived_skill_level=None,
        derived_bonus_per_hit=None,
        derived_uptime=None,
        source_result_id=None,
    )
    row.update(overrides)
    return row


def _frames(frame, rows, roles):
    results = frame(rows, RESULT_SCHEMA)
    builds = frame([{"build_id": bid, "role": role} for bid, role in roles.items()], BUILD_SCHEMA)
    return results, builds


BACKSTAB = derived.FlatPerHitSkill(
    name="Backstab",
    role="ASSASSIN",
    base_damage=1.5,
    increase_per_level=1.0,
    max_level=3,
    uptime=1.0,
    params_from_config=True,
)


def test_bonus_matches_the_skill_formula():
    """Backstab.java: damage + (level - 1) * damageIncreasePerLevel."""
    assert BACKSTAB.bonus(1) == pytest.approx(1.5)
    assert BACKSTAB.bonus(2) == pytest.approx(2.5)
    assert BACKSTAB.bonus(3) == pytest.approx(3.5)


def test_uptime_scales_the_bonus_linearly():
    half = derived.FlatPerHitSkill(**{**BACKSTAB.__dict__, "uptime": 0.5})
    assert half.bonus(3) == pytest.approx(1.75)


def test_derives_one_row_per_level_with_recomputed_ttk(frame, auditor):
    results, builds = _frames(frame, [_result_row()], {10: "ASSASSIN"})

    out = derived.apply_flat_per_hit(results, builds, BACKSTAB, auditor).orderBy("derived_skill_level")
    rows = out.collect()

    assert [r["derived_skill_level"] for r in rows] == [1, 2, 3]
    assert all(r["provenance"] == "derived" for r in rows)
    assert all(r["derived_skill"] == "Backstab" for r in rows)
    assert all(r["source_result_id"] == 1 for r in rows)

    for row in rows:
        bonus = BACKSTAB.bonus(row["derived_skill_level"])
        assert row["dmg_per_hit"] == pytest.approx(7.0 + bonus)
        # The engine kills on ceil(hp / dmg) hits, and TTK spans the gaps between
        # them -- so (hits - 1) intervals, not hits.
        expected_hits = math.ceil(29.0 / (7.0 + bonus))
        assert row["hits_to_kill"] == pytest.approx(expected_hits)
        assert row["ttk_s"] == pytest.approx((expected_hits - 1) * 8.0 / 20.0)
        # DuelOrchestrator's own identity, which silver checks on measured rows and
        # which the model must not break on derived ones.
        assert row["dps_sustained"] == pytest.approx(
            row["dmg_per_hit"] * row["hits_to_kill"] / row["ttk_s"]
        )
        # The swing rate is untouched, so the burst window still holds three hits.
        assert row["dps_burst"] == pytest.approx(3 * row["dmg_per_hit"])


def test_level_three_saves_a_whole_hit(frame, auditor):
    """29 HP at 7.0 needs five hits; at 10.5 it needs three. The point of the mart is
    that the saving is in whole swings, and a fractional DPS delta hides it."""
    results, builds = _frames(frame, [_result_row()], {10: "ASSASSIN"})
    rows = derived.apply_flat_per_hit(results, builds, BACKSTAB, auditor).collect()
    by_level = {r["derived_skill_level"]: r for r in rows}
    assert by_level[3]["hits_to_kill"] == pytest.approx(3)
    assert by_level[1]["hits_to_kill"] == pytest.approx(4)


def test_only_the_skills_own_role_gets_rows(frame, auditor):
    """Backstab is ASSASSIN PASSIVE_B. The class guard comes from the game, so a
    knight build must produce no derived row at all rather than a zero one."""
    rows = [_result_row(result_id=1, build_id=10), _result_row(result_id=2, build_id=11)]
    results, builds = _frames(frame, rows, {10: "ASSASSIN", 11: "KNIGHT"})

    out = derived.apply_flat_per_hit(results, builds, BACKSTAB, auditor)
    assert {r["source_result_id"] for r in out.collect()} == {1}


def test_timed_out_matchups_are_excluded(frame, auditor):
    """A matchup that never killed has no lethal-hit-bounded window, so there is no
    swing interval to re-derive against. Modelling one would invent the very thing
    the duel failed to establish."""
    rows = [
        _result_row(result_id=1, build_id=10),
        _result_row(result_id=2, build_id=10, ttk_s=None, swing_interval_ticks=None, kill_rate=0.0),
    ]
    results, builds = _frames(frame, rows, {10: "ASSASSIN"})

    out = derived.apply_flat_per_hit(results, builds, BACKSTAB, auditor)
    assert {r["source_result_id"] for r in out.collect()} == {1}


def test_non_linear_matchups_are_excluded_not_approximated(frame, auditor):
    """Where ceil(hp / dmg) does not reproduce the measured hits_to_kill, something
    other than a constant per-hit amount decided the fight, and adding a flat term to
    the average is not a model of it."""
    rows = [
        _result_row(result_id=1, build_id=10),
        # 29 HP at 7.0 should be 5 hits; 8 means a ramp, a DoT, or mitigation that
        # varies -- whatever it is, it is not flat.
        _result_row(result_id=2, build_id=10, hits_to_kill=8.0),
    ]
    results, builds = _frames(frame, rows, {10: "ASSASSIN"})

    out = derived.apply_flat_per_hit(results, builds, BACKSTAB, auditor)
    assert {r["source_result_id"] for r in out.collect()} == {1}


def test_derived_ids_cannot_collide_with_measured_ones(frame, auditor):
    """A derived row is not a row anyone can look up in sim_result, and an id that
    looks like one invites exactly that mistake."""
    results, builds = _frames(frame, [_result_row()], {10: "ASSASSIN"})
    ids = [r["result_id"] for r in derived.apply_flat_per_hit(results, builds, BACKSTAB, auditor).collect()]
    assert all(i < 0 for i in ids)
    assert len(set(ids)) == 3


def test_schema_matches_the_measured_rows(frame, auditor):
    """Derived rows are unioned into silver_result by name. A column added to one
    side and not the other fails at the union, which is late; this fails here."""
    results, builds = _frames(frame, [_result_row()], {10: "ASSASSIN"})
    out = derived.apply_flat_per_hit(results, builds, BACKSTAB, auditor)
    assert out.columns == results.columns
