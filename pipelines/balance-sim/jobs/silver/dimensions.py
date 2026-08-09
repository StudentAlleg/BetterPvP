"""Silver dimensions -- the run, and the balance values it was taken against.

`silver_run` flattens `sim_run.scenario` into columns, because every gold mart
groups or filters on scope/scenario/iterations and a JSON extraction per panel is
the sort of thing the old hand-written SQL dashboards were made of.

`silver_config_param` is the config mirror keyed for lookup: one row per config leaf,
with the `skills/skills` file's `skills.<role>.<skill>.<param>` convention split into
its parts. That split is what lets the derived-skill transforms ask for
("assassin", "backstab", "baseDamage") instead of matching on a dotted string.
"""
from __future__ import annotations

import logging

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F

from ..bronze import ingest as bronze
from ..common.audit import Auditor, non_null, not_empty, unique
from ..common.config import SILVER, Config
from . import schemas

LOG = logging.getLogger(__name__)


def build_run(spark: SparkSession, cfg: Config, auditor: Auditor, run_id: int) -> DataFrame:
    raw = bronze.read(spark, cfg, "sim_run")
    scenario = F.from_json(F.col("scenario"), schemas.SCENARIO)

    df = (
        raw.where(F.col("id") == run_id)
        .withColumn("s", scenario)
        .select(
            F.col("id").alias("run_id"),
            F.col("realm"),
            F.col("started_at"),
            F.col("finished_at"),
            F.col("status"),
            F.col("trigger"),
            F.col("engine_version"),
            F.col("config_hash"),
            F.col("s.balance_config").alias("balance_config_hash"),
            F.col("s.scope").alias("scope"),
            F.col("s.scenario").alias("scenario"),
            F.col("s.rotation").alias("rotation"),
            F.col("s.actives").alias("actives"),
            F.col("s.iterations").alias("iterations"),
            F.col("s.timeout_s").alias("duel_timeout_s"),
            F.col("s.skill_filter").alias("skill_filter"),
            F.col("s.relevant_skills").alias("relevant_skill_count"),
            F.col("s.channel_hold_ticks").alias("channel_hold_ticks"),
            F.col("s.phase").alias("engine_phase"),
            # Kept whole so a knob added to sim_run.scenario by a future engine
            # version is still readable from silver before this file knows of it.
            F.col("scenario").alias("scenario_raw"),
            # A CANCELLED run is a legitimate object of study -- the EQUIPMENT sweeps
            # were stopped deliberately -- but it is a prefix of an enumeration, and
            # DESIGN.md is explicit that a prefix is a biased sample. Flagging it on
            # the dimension means every mart inherits the caveat.
            (F.col("status") == F.lit("COMPLETED")).alias("is_complete"),
            (
                F.unix_timestamp("finished_at") - F.unix_timestamp("started_at")
            ).cast("double").alias("wall_clock_s"),
            F.col("_pipeline_run_id"),
            F.col("_ingested_at"),
        )
    )

    auditor.expect_all(
        [
            not_empty(SILVER, "silver_run"),
            unique(SILVER, "silver_run", "run_id"),
            non_null(SILVER, "silver_run", "run_id", "config_hash", "scope"),
        ],
        df,
    )
    return df


def build_config_param(spark: SparkSession, cfg: Config, auditor: Auditor) -> DataFrame:
    """The balance-value dimension, from exactly one config snapshot.

    Picking the snapshot is the whole subtlety. `grafana_config` is a mirror of the
    live YAML rather than a run-scoped table, so bronze keeps every snapshot it has
    ever taken, partitioned by the pipeline invocation that took it. Reading the
    dataset whole therefore returns one row per (key x snapshot) and every lookup
    downstream becomes ambiguous -- which is not hypothetical: it is what the
    uniqueness expectation caught the first time this ran against a lake with four
    snapshots in it.

    Prefer this invocation's own snapshot; fall back to the newest one when silver is
    run as a separate process from bronze, which is the normal local case. Under
    Airflow both tasks carry the same run id and the first branch always wins.

    Split rather than stored dotted because the consumers are lookups by
    (role, skill, param) and by (item, param). A `LIKE 'skills.assassin.backstab.%'`
    over 1,200 rows is cheap; it is the *ambiguity* that costs -- `skills.assassin.
    combo_attack.maxDamage` and `skills.assassin.combo.attack_maxDamage` are
    indistinguishable to a prefix match and not to a split.
    """
    raw = bronze.read(spark, cfg, "grafana_config")
    snapshot = auditor.pipeline_run_id
    if raw.where(F.col("snapshot_id") == snapshot).isEmpty():
        newest = raw.orderBy(F.col("_ingested_at").desc()).select("snapshot_id").first()
        snapshot = newest["snapshot_id"] if newest else snapshot
        LOG.info("no config snapshot for this invocation; using the newest, %s", snapshot)
    raw = raw.where(F.col("snapshot_id") == snapshot).drop("snapshot_id")

    parts = F.split(F.col("config_key"), r"\.")

    df = raw.select(
        F.col("realm"),
        F.col("plugin"),
        F.col("config_file"),
        F.col("config_key"),
        F.col("config_value"),
        # skills/skills keys are skills.<role>.<skill>.<param>; item files are
        # <category>.<item>.<param...>. Only the first shape is decomposed, because
        # only it has a stable arity.
        F.when(F.col("config_file") == "skills/skills", parts.getItem(1)).alias("role_key"),
        F.when(F.col("config_file") == "skills/skills", parts.getItem(2)).alias("entity_key"),
        F.when(
            F.col("config_file") == "skills/skills",
            F.array_join(F.slice(parts, 4, F.size(parts) - 3), "."),
        ).alias("param_key"),
        # Config values are stored as text and most of them are not numbers --
        # `enabled: true`, world names, material keys. A null here is the right
        # answer, so this is try_cast: under ANSI mode (Spark 4's default) a plain
        # cast raises on the first `true` it meets and takes the whole layer with it.
        F.expr("try_cast(config_value AS DOUBLE)").alias("numeric_value"),
        F.col("updated_at"),
        F.col("_pipeline_run_id"),
    )

    auditor.expect_all(
        [
            not_empty(SILVER, "silver_config_param"),
            unique(SILVER, "silver_config_param", "realm", "plugin", "config_file", "config_key"),
        ],
        df,
    )
    return df


def skill_param(config: DataFrame, role: str, skill: str, param: str, fallback: float) -> float:
    """Single numeric balance value, or the fallback.

    Collected to the driver on purpose: these are scalars that parameterise a
    transform, and broadcasting a one-row frame into an expression is more machinery
    than a number needs. The fallback is reported by the caller so a missing config
    row is visible rather than silently equal to the default.
    """
    row = (
        config.where(
            (F.lower(F.col("role_key")) == role.lower())
            & (F.lower(F.col("entity_key")) == skill.lower())
            & (F.col("param_key") == param)
        )
        .select("numeric_value")
        .first()
    )
    if row is None or row["numeric_value"] is None:
        return fallback
    return float(row["numeric_value"])
