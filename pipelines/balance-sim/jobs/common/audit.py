"""Expectations, lineage, and the audit log.

The reason this project exists rather than more SQL in a dashboard is that the
transformations should be auditable. That means two things concretely:

1. Every dataset a layer writes records where it came from, what produced it, and
   how many rows survived -- so "this number changed" can always be traced to a
   layer rather than guessed at.
2. Every assumption a transform makes is an expectation that is checked, not a
   comment that is hoped for. A failed expectation stops the layer; it does not
   publish a mart that is quietly wrong.

Both land in the same table, `sim_gold_data_quality`, which is also a Grafana panel:
a run whose expectations failed should be visible next to the numbers it produced.
"""
from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql.types import (
    BooleanType,
    DoubleType,
    IntegerType,
    LongType,
    StringType,
    StructField,
    StructType,
    TimestampType,
)

from .config import Config

LOG = logging.getLogger(__name__)

# Declared rather than inferred, so an audit log written by a failing run -- which is
# the one worth reading -- has the same columns as one written by a healthy run.
AUDIT_SCHEMA = StructType(
    [
        StructField("realm", IntegerType()),
        StructField("run_id", LongType()),
        StructField("pipeline_run_id", StringType()),
        StructField("checked_at", TimestampType()),
        StructField("layer", StringType()),
        StructField("dataset", StringType()),
        StructField("check_name", StringType()),
        StructField("severity", StringType()),
        StructField("passed", BooleanType()),
        StructField("observed", DoubleType()),
        StructField("detail", StringType()),
    ]
)


class ExpectationFailed(RuntimeError):
    pass


@dataclass
class Check:
    """One expectation about one dataset."""

    name: str
    layer: str
    dataset: str
    # Returns (passed, observed, detail). Observed is the number the check turns on,
    # so a passing check is still worth reading -- "0 orphans" and "no orphan check
    # ran" are different states and the row distinguishes them.
    fn: Callable[[DataFrame], tuple[bool, float, str]]
    severity: str = "error"  # error | warn


@dataclass
class Auditor:
    cfg: Config
    run_id: int
    pipeline_run_id: str
    rows: list[dict] = field(default_factory=list)

    # -- lineage ------------------------------------------------------------

    def record_dataset(self, layer: str, dataset: str, df: DataFrame, source: str) -> int:
        count = df.count()
        self._append(
            layer=layer,
            dataset=dataset,
            check_name="row_count",
            severity="info",
            passed=True,
            observed=float(count),
            detail=f"produced from {source}",
        )
        LOG.info("[%s] %s: %s rows (from %s)", layer, dataset, count, source)
        return count

    # -- expectations -------------------------------------------------------

    def expect(self, check: Check, df: DataFrame) -> bool:
        passed, observed, detail = check.fn(df)
        self._append(
            layer=check.layer,
            dataset=check.dataset,
            check_name=check.name,
            severity=check.severity,
            passed=passed,
            observed=float(observed),
            detail=detail,
        )
        if passed:
            LOG.info("[%s] %s :: %s OK (%s)", check.layer, check.dataset, check.name, detail)
            return True

        message = f"[{check.layer}] {check.dataset} :: {check.name} FAILED -- {detail}"
        if check.severity == "error" and self.cfg.quality.get("fail_on_error", True):
            raise ExpectationFailed(message)
        LOG.warning(message)
        return False

    def expect_all(self, checks: list[Check], df: DataFrame) -> None:
        for check in checks:
            self.expect(check, df)

    # -- output -------------------------------------------------------------

    def _append(self, **kwargs) -> None:
        self.rows.append(
            dict(
                realm=self.cfg.realm,
                run_id=self.run_id,
                pipeline_run_id=self.pipeline_run_id,
                checked_at=datetime.now(timezone.utc),
                **kwargs,
            )
        )

    def to_frame(self, spark: SparkSession) -> DataFrame:
        """Materialise the log as a DataFrame, via a driver-written NDJSON file.

        Not `createDataFrame`, for two reasons. The mechanical one is that
        parallelising driver-local rows is the pipeline's only code path that needs a
        Python worker process -- every transform in it is a DataFrame expression the
        JVM evaluates -- so routing the audit log through it makes a Python-side
        environment problem able to destroy the record of what happened, which is
        precisely the moment the record matters most. The better reason is that the
        file is a durable artifact: if the session dies before publishing, the audit
        for the failed attempt is still on disk.
        """
        if not self.rows:
            self.rows.append(
                dict(
                    realm=self.cfg.realm,
                    run_id=self.run_id,
                    pipeline_run_id=self.pipeline_run_id,
                    checked_at=datetime.now(timezone.utc),
                    layer="pipeline",
                    dataset="-",
                    check_name="no_checks_ran",
                    severity="warn",
                    passed=False,
                    observed=0.0,
                    detail="the auditor was created and never used",
                )
            )

        staging = Path(self.cfg.lake_root) / "_audit_staging" / self.pipeline_run_id
        staging.mkdir(parents=True, exist_ok=True)
        target = staging / "audit.json"
        with open(target, "w", encoding="utf-8") as handle:
            for row in self.rows:
                payload = dict(row)
                payload["checked_at"] = payload["checked_at"].isoformat()
                handle.write(json.dumps(payload) + "\n")

        return spark.read.schema(AUDIT_SCHEMA).json(target.as_posix())


# ---------------------------------------------------------------------------
# Reusable expectation bodies. Each returns a Check factory so the dataset and
# layer travel with the check rather than being repeated at every call site.
# ---------------------------------------------------------------------------


def not_empty(layer: str, dataset: str) -> Check:
    def fn(df: DataFrame) -> tuple[bool, float, str]:
        n = df.count()
        return n > 0, n, f"{n} rows"

    return Check("not_empty", layer, dataset, fn)


def unique(layer: str, dataset: str, *cols: str) -> Check:
    def fn(df: DataFrame) -> tuple[bool, float, str]:
        total = df.count()
        distinct = df.select(*cols).distinct().count()
        dupes = total - distinct
        return dupes == 0, dupes, f"{dupes} duplicate rows on ({', '.join(cols)}) of {total}"

    return Check(f"unique[{'+'.join(cols)}]", layer, dataset, fn)


def non_null(layer: str, dataset: str, *cols: str) -> Check:
    from pyspark.sql import functions as F

    def fn(df: DataFrame) -> tuple[bool, float, str]:
        condition = None
        for col in cols:
            clause = F.col(col).isNull()
            condition = clause if condition is None else (condition | clause)
        bad = df.where(condition).count()
        return bad == 0, bad, f"{bad} rows null in ({', '.join(cols)})"

    return Check(f"non_null[{'+'.join(cols)}]", layer, dataset, fn)


def referential(layer: str, dataset: str, child_col: str, parent: DataFrame, parent_col: str) -> Check:
    """Every distinct child key has a parent.

    Both sides are renamed to distinct names before the join. Joining on
    `df[child_col] == parent[parent_col]` when the two columns share a name leaves
    Spark resolving an ambiguous reference against the projected frames' lineage, and
    whether it succeeds depends on the plan rather than on the data.
    """
    from pyspark.sql import functions as F

    def fn(df: DataFrame) -> tuple[bool, float, str]:
        child = df.select(F.col(child_col).alias("_child")).distinct()
        keys = parent.select(F.col(parent_col).alias("_parent")).distinct()
        orphans = child.join(keys, child["_child"] == keys["_parent"], "left_anti").count()
        return orphans == 0, orphans, f"{orphans} distinct {child_col} with no matching {parent_col}"

    return Check(f"fk[{child_col}]", layer, dataset, fn)


def row_count_matches(layer: str, dataset: str, expected: int) -> Check:
    """Conservation between layers. A silver transform that filters is fine; one
    that filters *by accident* -- a bad join key, a null in a grouping column -- is
    the single most common way a medallion pipeline goes quietly wrong."""

    def fn(df: DataFrame) -> tuple[bool, float, str]:
        n = df.count()
        return n == expected, n - expected, f"{n} rows, expected {expected}"

    return Check("row_count_conserved", layer, dataset, fn)
