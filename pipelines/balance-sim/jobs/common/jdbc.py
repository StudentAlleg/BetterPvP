"""JDBC read/write against the BetterPvP Postgres.

Reads are split on the source table's primary key. sim_result carries millions of
rows for a single EQUIPMENT run, and an unpartitioned JDBC read pulls all of it
through one connection into one Spark task -- which is both slow and the usual cause
of a driver OOM on a machine that has plenty of memory.
"""
from __future__ import annotations

import logging

from pyspark.sql import DataFrame, SparkSession

from .config import Config

LOG = logging.getLogger(__name__)


def _opts(conn: dict) -> dict[str, str]:
    return {
        "url": conn["jdbc_url"],
        "user": conn["user"],
        "password": conn["password"],
        "driver": "org.postgresql.Driver",
    }


def bounds(spark: SparkSession, cfg: Config, table: str, key: str, where: str) -> tuple[int, int, int]:
    """(lower, upper, count) of the key column over the slice about to be read.

    Read as its own query rather than inferred, because JDBC's partitioning takes
    the bounds as inputs: guessing them wide wastes empty tasks and guessing them
    narrow silently drops nothing (Spark widens the outer predicates) but skews
    every partition into the first task.
    """
    query = f"(SELECT MIN({key}) AS lo, MAX({key}) AS hi, COUNT(*) AS n FROM {table} WHERE {where}) b"
    row = spark.read.format("jdbc").options(**_opts(cfg.source), dbtable=query).load().first()
    if row is None or row["n"] == 0:
        return 0, 0, 0
    return int(row["lo"]), int(row["hi"]), int(row["n"])


def read(
    spark: SparkSession,
    cfg: Config,
    table: str,
    *,
    key: str = "id",
    where: str = "TRUE",
    columns: str = "*",
) -> DataFrame:
    """Partitioned read of one source table."""
    src = cfg.source
    lo, hi, n = bounds(spark, cfg, table, key, where)
    if n == 0:
        LOG.warning("%s: no rows match %s", table, where)

    partitions = max(1, min(int(src.get("read_partitions", 16)), max(1, n // 50_000) or 1))
    options = dict(
        _opts(src),
        dbtable=f"(SELECT {columns} FROM {table} WHERE {where}) s",
        fetchsize=str(src.get("fetch_size", 10_000)),
    )
    if n > 0 and hi > lo:
        options.update(
            partitionColumn=key,
            lowerBound=str(lo),
            upperBound=str(hi),
            numPartitions=str(partitions),
        )
    LOG.info("reading %s (%s rows, %s partitions)", table, n, partitions)
    return spark.read.format("jdbc").options(**options).load()


def write(df: DataFrame, cfg: Config, table: str, *, mode: str = "overwrite") -> None:
    """Publish a gold mart to the serving database.

    `truncate` keeps the table's own definition -- indexes, grants, and the
    comments in sql/gold_ddl.sql -- across a republish. Without it Spark drops and
    recreates, and every index a dashboard depends on goes with it.
    """
    wh = cfg.warehouse
    (
        df.write.format("jdbc")
        .options(**_opts(wh), dbtable=table, truncate="true", batchsize="10000")
        .mode(mode)
        .save()
    )
