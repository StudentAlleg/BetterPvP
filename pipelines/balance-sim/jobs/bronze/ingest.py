"""Bronze -- faithful landing of what the simulator wrote.

Bronze does not clean, cast, explode or reinterpret anything. JSONB columns land as
the strings JDBC hands back; a column the plugin wrote as NUMERIC(10,3) lands as a
decimal of that precision. The only additions are ingest metadata.

That discipline is what makes the layer worth having. The sim tables are live: the
plugin adds migrations, a resumed sweep appends rows to a run that already has some,
and `sim_run.status` flips under a reader. A bronze snapshot pins the exact bytes a
silver transform was computed from, so a gold figure that looks wrong can be traced
to either the transform or the source, and the two can be told apart.

grafana_config is ingested alongside, because the derived-skill transforms in silver
read balance values from it. It is not run-scoped -- it is a mirror of the live YAML
that the sync service overwrites -- so it is snapshotted per pipeline run instead,
and the snapshot a run used is the one its derived rows can be re-derived from.
"""
from __future__ import annotations

import logging
from datetime import datetime, timezone

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F

from ..common import jdbc
from ..common.audit import Auditor, not_empty, unique
from ..common.config import BRONZE, Config

LOG = logging.getLogger(__name__)

# Run-scoped tables, in dependency order. sim_trace is included even though no run
# has ever written to it: an empty ingest is a fact worth recording, and the day it
# stops being empty the pipeline should already carry it.
RUN_SCOPED = {
    "sim_build": "id",
    "sim_result": "id",
    "sim_duel_diagnostic": "id",
    "sim_trace": "id",
}


def _stamp(df: DataFrame, pipeline_run_id: str, source: str) -> DataFrame:
    return (
        df.withColumn("_ingested_at", F.lit(datetime.now(timezone.utc)).cast("timestamp"))
        .withColumn("_pipeline_run_id", F.lit(pipeline_run_id))
        .withColumn("_source_table", F.lit(source))
    )


def ingest(
    spark: SparkSession,
    cfg: Config,
    auditor: Auditor,
    run_id: int,
) -> dict[str, int]:
    """Land one sim_run and everything hanging off it."""
    counts: dict[str, int] = {}
    realm = cfg.realm

    run = jdbc.read(spark, cfg, "sim_run", key="id", where=f"id = {run_id} AND realm = {realm}")
    run = _stamp(run, auditor.pipeline_run_id, "sim_run")
    auditor.expect(not_empty(BRONZE, "sim_run"), run)
    _write(run, cfg, "sim_run", partition_by=None)
    counts["sim_run"] = auditor.record_dataset(BRONZE, "sim_run", run, "jdbc:sim_run")

    skip_ingest = set(cfg.source.get("skip_ingest") or ())
    for table, key in RUN_SCOPED.items():
        if table in skip_ingest:
            # Recorded as a skip rather than as zero rows: "nobody asked for this table" and
            # "this run produced none" are different facts, and a 0 in the audit log would
            # read as the second one.
            LOG.info("skipping %s (listed in source.skip_ingest)", table)
            continue
        df = jdbc.read(spark, cfg, table, key=key, where=f"run_id = {run_id}")
        df = _stamp(df, auditor.pipeline_run_id, table)
        _write(df, cfg, table, partition_by="run_id")
        counts[table] = auditor.record_dataset(BRONZE, table, df, f"jdbc:{table}")
        if counts[table]:
            auditor.expect(unique(BRONZE, table, key), df)

    # Not run-scoped: a config mirror snapshot, tagged with the pipeline run that
    # took it so a derived row's inputs stay reproducible.
    config = jdbc.read(
        spark,
        cfg,
        "grafana_config",
        key="realm",
        where=f"realm = {realm}",
        columns="realm, plugin, config_file, config_key, config_value, updated_at",
    )
    config = _stamp(config, auditor.pipeline_run_id, "grafana_config").withColumn(
        "snapshot_id", F.lit(auditor.pipeline_run_id)
    )
    _write(config, cfg, "grafana_config", partition_by="snapshot_id")
    counts["grafana_config"] = auditor.record_dataset(
        BRONZE, "grafana_config", config, "jdbc:grafana_config"
    )
    auditor.expect(not_empty(BRONZE, "grafana_config"), config)
    auditor.expect(
        unique(BRONZE, "grafana_config", "realm", "plugin", "config_file", "config_key"), config
    )

    return counts


def _write(df: DataFrame, cfg: Config, name: str, partition_by: str | None) -> None:
    writer = df.write.mode("overwrite").format("parquet")
    if partition_by:
        writer = writer.partitionBy(partition_by)
    writer.save(cfg.dataset(BRONZE, name))


def read(spark: SparkSession, cfg: Config, name: str, run_id: int | None = None) -> DataFrame:
    # mergeSchema, because a dataset partitioned by run_id accumulates partitions written
    # under different schemas: a run landed before a column existed does not have it, and
    # is never rewritten. Without this Spark picks one partition's footer arbitrarily and
    # a column is present or absent depending on which file it happened to read -- which
    # fails loudly on a good day and silently drops a column on a bad one.
    df = spark.read.option("mergeSchema", "true").parquet(cfg.dataset(BRONZE, name))
    if run_id is not None and "run_id" in df.columns:
        df = df.where(F.col("run_id") == run_id)
    return df


def read_optional(spark: SparkSession, cfg: Config, name: str, run_id: int | None = None) -> DataFrame | None:
    """`read`, but None for a table that landed empty.

    A 0-row write partitioned by run_id produces no partition directories and no
    parquet footer, so a later read fails with UNABLE_TO_INFER_SCHEMA. That is
    correct behaviour from Spark and the wrong thing to propagate: `sim_trace` and
    `sim_duel_diagnostic` are both written only when a config flag is on, so "this
    table is empty" is the normal case and not an error.
    """
    try:
        return read(spark, cfg, name, run_id)
    except Exception as exc:  # noqa: BLE001 -- AnalysisException, but also a bare missing path
        LOG.info("%s: nothing to read from bronze (%s)", name, type(exc).__name__)
        return None
