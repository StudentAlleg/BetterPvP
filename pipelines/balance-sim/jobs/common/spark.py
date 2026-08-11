"""Spark session construction.

Local[*] by default. The session is built once per CLI invocation and passed down,
rather than fetched from a module-level singleton, so a test can hand a job its own
session without the job reaching around it.
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

from pyspark.sql import SparkSession

from .config import PROJECT_ROOT, Config

# Kept in one place because the bronze reader and the gold publisher both need it and
# a mismatch between them is a class of failure that only shows up at write time.
POSTGRES_PACKAGE = "org.postgresql:postgresql:42.7.4"


def session(cfg: Config, app_suffix: str | None = None) -> SparkSession:
    spark_cfg = cfg.spark

    # Pin the worker interpreter to the one running the driver. Left unset, PySpark
    # launches workers via whatever `python3` resolves to on PATH, and on a machine
    # with more than one Python that is frequently a different minor version -- which
    # fails as `SocketException: Connection reset` from a worker that died during
    # handshake, naming neither Python nor the version mismatch.
    os.environ.setdefault("PYSPARK_PYTHON", sys.executable)
    os.environ.setdefault("PYSPARK_DRIVER_PYTHON", sys.executable)
    name = spark_cfg.get("app_name", "balance-sim")
    if app_suffix:
        name = f"{name}-{app_suffix}"

    builder = (
        SparkSession.builder.appName(name)
        .master(spark_cfg.get("master", "local[*]"))
        .config("spark.driver.memory", spark_cfg.get("driver_memory", "4g"))
        .config("spark.sql.shuffle.partitions", spark_cfg.get("shuffle_partitions", 64))
        # Idempotent re-runs. Without this, writing one run's partition with
        # mode=overwrite truncates the whole dataset and every other run's rows
        # disappear -- the failure mode is silent and only visible as a shrinking
        # table, so it is set globally rather than per write.
        .config("spark.sql.sources.partitionOverwriteMode", "dynamic")
        # Timestamps round-trip through parquet as microseconds; the sim writes
        # TIMESTAMPTZ and the analysis is entirely relative, so UTC everywhere.
        .config("spark.sql.session.timeZone", "UTC")
    )

    # Escape hatch for whatever the next fat column needs. Anything under `spark.options`
    # in pipeline.yml is passed straight through, so tuning a write does not mean editing
    # this file -- and the reason for each value lives next to the value, in the yaml.
    for key, value in (spark_cfg.get("options") or {}).items():
        builder = builder.config(key, value)

    jar = spark_cfg.get("postgres_jar")
    if jar:
        # Resolved against the project root rather than the working directory: the
        # Airflow worker submits from wherever it happens to be, and a relative jar
        # path that silently does not exist surfaces as "No suitable driver", which
        # names neither the jar nor the path.
        path = Path(jar)
        if not path.is_absolute():
            path = (PROJECT_ROOT / path).resolve()
        if not path.exists():
            raise FileNotFoundError(f"spark.postgres_jar does not exist: {path}")
        # extraClassPath rather than spark.jars. `spark.jars` routes the jar through
        # Spark's file-fetch machinery, which chmods what it downloads and therefore
        # needs winutils.exe on Windows -- so a local-mode driver that has the jar
        # sitting right there fails to start over a permission call it does not need.
        # Putting it on the classpath directly works identically in local mode and on
        # a cluster whose workers already have the file.
        classpath = str(path)
        builder = builder.config("spark.driver.extraClassPath", classpath).config(
            "spark.executor.extraClassPath", classpath
        )
    else:
        builder = builder.config("spark.jars.packages", POSTGRES_PACKAGE)

    spark = builder.getOrCreate()
    spark.sparkContext.setLogLevel(spark_cfg.get("log_level", "WARN"))
    return spark
