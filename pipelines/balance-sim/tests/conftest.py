"""Shared test fixtures.

`frame()` builds a DataFrame from driver-local rows via a temporary NDJSON file
rather than `SparkSession.createDataFrame`, for the same reason `Auditor.to_frame`
does: parallelising local Python objects is the only thing in this project that needs
a PySpark *Python worker* subprocess, and every transform under test is a DataFrame
expression the JVM evaluates on its own. Keeping the tests on the JVM path means they
exercise what production exercises, and they keep working on a machine where the
Python worker cannot start (see README, "Python workers").
"""
from __future__ import annotations

import itertools
import json
from pathlib import Path

import pytest

from jobs.common.config import load


@pytest.fixture(scope="session")
def cfg():
    return load()


@pytest.fixture(scope="session")
def spark(cfg):
    from jobs.common.spark import session

    spark = session(cfg, "tests")
    yield spark
    spark.stop()


@pytest.fixture()
def frame(spark, tmp_path):
    """Each call gets its OWN file, and that is load-bearing.

    The name used to be derived from the row count and the schema, so two calls with the
    same number of rows and the same schema wrote to one path -- and because
    `spark.read.json` is lazy, BOTH DataFrames then read whatever the second call left
    there. A test comparing two runs would silently compare a run against itself: the
    diff came back all `both` with zero deltas, which is a plausible-looking result and
    not an error. A counter is enough, since the fixture is function-scoped.
    """
    counter = itertools.count()

    def build(rows: list[dict], schema):
        target = Path(tmp_path) / f"rows-{next(counter)}.json"
        with open(target, "w", encoding="utf-8") as handle:
            for row in rows:
                handle.write(json.dumps(row) + "\n")
        return spark.read.schema(schema).json(target.as_posix())

    return build
