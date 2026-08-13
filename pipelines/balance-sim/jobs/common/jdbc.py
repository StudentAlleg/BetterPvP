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


def _partition_count(src: dict, table: str, n: int) -> int:
    """How many JDBC partitions to split a read into.

    Row count is only half the question; the other half is how WIDE the rows are, and
    ignoring it is what killed run 7. `sim_duel_diagnostic` holds 3,619,230 rows carrying
    4,071 MB of `detail` JSON. The old plan -- `min(read_partitions, n // 50_000)` -- gave
    it 32 partitions of ~113k rows, and 4 concurrent local tasks each buffering ~124 MB of
    JSON exhausted a 6 GB driver heap: `PSQLException: Ran out of memory retrieving query
    results`. Run 1 took the identical path and survived only because its diagnostics are
    173 MB.

    Note the old expression could not have been tuned out of the problem from the config
    alone: with 3.6M rows `n // 50_000` is 72, so `read_partitions` was the binding term and
    lowering a global rows-per-partition would have changed nothing. Both halves have to be
    per-table, which is why there are two overrides rather than one.
    """
    rows_per = int(
        (src.get("rows_per_partition_by_table") or {}).get(table, src.get("rows_per_partition", 50_000))
    )
    cap = int((src.get("read_partitions_by_table") or {}).get(table, src.get("read_partitions", 16)))
    # Ceiling division: a table just over a budget boundary gets the extra partition rather
    # than folding the remainder back into an already-full one.
    wanted = -(-n // max(1, rows_per))
    return max(1, min(cap, wanted))


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

    partitions = _partition_count(src, table, n)
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
