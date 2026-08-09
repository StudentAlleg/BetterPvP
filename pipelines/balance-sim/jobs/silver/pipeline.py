"""Silver layer driver.

Order matters in one place only: the derived rows need the config dimension (for the
balance values) and the build dimension (for the role guard), so those are built
first. Everything else is independent.
"""
from __future__ import annotations

import logging

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F

from ..bronze import ingest as bronze
from ..common.audit import Auditor, referential, row_count_matches
from ..common.config import SILVER, Config
from . import builds as builds_job
from . import derived as derived_job
from . import dimensions as dim_job
from . import results as results_job

LOG = logging.getLogger(__name__)

# Written under lake/silver/<name>. run_id partitions everything except the config
# dimension, which is a snapshot of the live mirror rather than a property of a run.
DATASETS = [
    "silver_run",
    "silver_config_param",
    "silver_build",
    "silver_build_rune",
    "silver_build_weapon_alias",
    "silver_build_skill",
    "silver_result",
    "silver_result_target_alias",
    "silver_result_modifier",
    "silver_duel_diagnostic",
]


def run(spark: SparkSession, cfg: Config, auditor: Auditor, run_id: int) -> dict[str, int]:
    counts: dict[str, int] = {}

    run_dim = dim_job.build_run(spark, cfg, auditor, run_id)
    counts["silver_run"] = _write(cfg, auditor, "silver_run", run_dim, None, "bronze:sim_run")

    config_dim = dim_job.build_config_param(spark, cfg, auditor)
    counts["silver_config_param"] = _write(
        cfg, auditor, "silver_config_param", config_dim, None, "bronze:grafana_config"
    )
    # Reused by the derived transforms and small enough that re-reading parquet for
    # each lookup would be pure overhead.
    config_dim.cache()

    build_dim = builds_job.build(spark, cfg, auditor, run_id)
    build_dim.cache()
    counts["silver_build"] = _write(cfg, auditor, "silver_build", build_dim, "run_id", "bronze:sim_build")
    counts["silver_build_rune"] = _write(
        cfg, auditor, "silver_build_rune", builds_job.rune_bridge(build_dim), "run_id", "silver_build"
    )
    counts["silver_build_weapon_alias"] = _write(
        cfg,
        auditor,
        "silver_build_weapon_alias",
        builds_job.weapon_alias_bridge(build_dim),
        "run_id",
        "silver_build",
    )
    counts["silver_build_skill"] = _write(
        cfg, auditor, "silver_build_skill", builds_job.skill_bridge(build_dim), "run_id", "silver_build"
    )

    measured = results_job.build(spark, cfg, auditor, run_id)
    measured.cache()
    # Postgres enforces this with a foreign key, so it cannot be violated at the
    # source -- but bronze snapshots the two tables in separate reads, and a sweep that
    # was still writing when the ingest ran can land results whose build arrived after.
    # That is the torn-snapshot case the DAG's settled-run check exists to prevent, and
    # this is the check that says whether it worked.
    auditor.expect(
        referential(SILVER, "silver_result", "build_id", build_dim, "build_id"), measured
    )
    counts["silver_result_target_alias"] = _write(
        cfg,
        auditor,
        "silver_result_target_alias",
        results_job.target_alias_bridge(measured),
        "run_id",
        "silver_result",
    )
    counts["silver_result_modifier"] = _write(
        cfg,
        auditor,
        "silver_result_modifier",
        results_job.modifier_bridge(measured),
        "run_id",
        "silver_result",
    )

    all_results = _with_derived(cfg, auditor, measured, build_dim, config_dim)
    counts["silver_result"] = _write(
        cfg, auditor, "silver_result", all_results, "run_id", "bronze:sim_result + derived"
    )

    diagnostics = _diagnostics(spark, cfg, run_id)
    if diagnostics is None:
        # duelDiagnostics is off by default -- one row per duel where sim_result is
        # one per matchup -- so an absent table is the ordinary case, not a failure.
        LOG.info("no duel diagnostics for run %s; skipping silver_duel_diagnostic", run_id)
        counts["silver_duel_diagnostic"] = 0
    else:
        counts["silver_duel_diagnostic"] = _write(
            cfg, auditor, "silver_duel_diagnostic", diagnostics, "run_id", "bronze:sim_duel_diagnostic"
        )

    return counts


def _with_derived(
    cfg: Config,
    auditor: Auditor,
    measured: DataFrame,
    build_dim: DataFrame,
    config_dim: DataFrame,
) -> DataFrame:
    """Union of the sweep's own rows and every modelled overlay."""
    frames = [measured]
    section = cfg.derived_skills.get("backstab")
    if section:
        skill = derived_job.backstab_spec(section, config_dim)
        if skill:
            LOG.info(
                "deriving %s: +%.3f..%.3f per hit over levels 1..%d at uptime %.2f (params from %s)",
                skill.name,
                skill.bonus(1),
                skill.bonus(skill.max_level),
                skill.max_level,
                skill.uptime,
                "grafana_config" if skill.params_from_config else "pipeline fallbacks",
            )
            frames.append(derived_job.apply_flat_per_hit(measured, build_dim, skill, auditor))

    union = frames[0]
    for frame in frames[1:]:
        union = union.unionByName(frame)

    # The measured rows must survive the union untouched. A derivation that filters
    # or reshapes its input would be a silent data loss, and the count is the cheapest
    # test that says so.
    auditor.expect(
        row_count_matches(SILVER, "silver_result[measured]", measured.count()),
        union.where(F.col("provenance") == "measured"),
    )
    return union


def _diagnostics(spark: SparkSession, cfg: Config, run_id: int) -> DataFrame | None:
    """Per-duel diagnostics, typed.

    `detail` is dropped rather than parsed: it is a free-form JSON blob whose shape
    the engine changes as hypotheses change, and the typed columns beside it are the
    ones the residency and first-hit-race questions are actually asked of. Bronze
    still holds it whole for the sessions where somebody needs to open one.
    """
    raw = bronze.read_optional(spark, cfg, "sim_duel_diagnostic", run_id)
    if raw is None or raw.isEmpty():
        return None
    return (
        raw.select(
            F.col("id").alias("diagnostic_id"),
            "run_id",
            "build_id",
            "target_role",
            "target_armor",
            "iteration",
            "arena_index",
            "attacker_duels_fought",
            "defender_duels_fought",
            "attacker_revives",
            "defender_revives",
            "attacker_ever_died",
            "defender_ever_died",
            "outcome",
            "resolved_tick",
            "attacker_first_hit_tick",
            "defender_first_hit_tick",
            "attacker_hits",
            "defender_hits",
            F.col("attacker_damage").cast("double").alias("attacker_damage"),
            F.col("defender_damage").cast("double").alias("defender_damage"),
            F.col("attacker_start_health").cast("double").alias("attacker_start_health"),
            F.col("attacker_max_health").cast("double").alias("attacker_max_health"),
            F.col("defender_start_health").cast("double").alias("defender_start_health"),
            F.col("defender_max_health").cast("double").alias("defender_max_health"),
            F.col("attacker_end_health").cast("double").alias("attacker_end_health"),
            F.col("defender_end_health").cast("double").alias("defender_end_health"),
            "_pipeline_run_id",
        )
        # Who landed first. DUEL-DIAGNOSTICS.md is explicit that a 29 HP target dies
        # in five hits and the fight is decided inside thirty ticks, so this is often
        # the difference between "the skill changed the damage" and "the skill
        # changed the timing".
        .withColumn(
            "first_hit_lead_ticks",
            F.col("defender_first_hit_tick") - F.col("attacker_first_hit_tick"),
        )
        # The residency signal the table was created to expose: a pooled entity that
        # has died and been revived is the population the armour-residue bug lived in.
        .withColumn("attacker_is_fresh", F.col("attacker_duels_fought") == 0)
        .withColumn("defender_is_fresh", F.col("defender_duels_fought") == 0)
    )


def _write(
    cfg: Config,
    auditor: Auditor,
    name: str,
    df: DataFrame,
    partition_by: str | None,
    source: str,
) -> int:
    writer = df.write.mode("overwrite").format("parquet")
    if partition_by:
        writer = writer.partitionBy(partition_by)
    writer.save(cfg.dataset(SILVER, name))
    return auditor.record_dataset(SILVER, name, df, source)


def read(spark: SparkSession, cfg: Config, name: str, run_id: int | None = None) -> DataFrame:
    # mergeSchema, because a dataset partitioned by run_id accumulates partitions written
    # under different schemas: a run landed before a column existed does not have it, and
    # is never rewritten. Without this Spark picks one partition's footer arbitrarily and
    # a column is present or absent depending on which file it happened to read -- which
    # fails loudly on a good day and silently drops a column on a bad one.
    df = spark.read.option("mergeSchema", "true").parquet(cfg.dataset(SILVER, name))
    if run_id is not None and "run_id" in df.columns:
        df = df.where(F.col("run_id") == run_id)
    return df
