"""Entry point for every layer.

    python -m jobs.cli all      --run-id 1
    python -m jobs.cli bronze   --run-id 1
    python -m jobs.cli silver   --run-id 1
    python -m jobs.cli gold     --run-id 1 [--no-publish]
    python -m jobs.cli diff     --run-a 1 --run-b 4
    python -m jobs.cli diff     --run-a -11 --run-b 15   # against the standing baseline
    python -m jobs.cli runs                      # what is available to process

A NEGATIVE --run-a or --run-b names the standing baseline for a realm and scenario
instead of a sweep: -11 is realm 1 one_way, which is runs 7, 12 and 14 unioned. The ids
come from sim_gold_baseline_run. This is usually the side you want -- the baseline has
never been a single run, so naming one compares against a third of it.

One process per layer is the supported shape, because that is what the Airflow DAG
submits and a layer that only works when the previous one is in the same JVM is a
layer that cannot be retried on its own.
"""
from __future__ import annotations

import argparse
import logging
import sys
import uuid

from .bronze import ingest as bronze
from .common import jdbc
from .common.audit import Auditor, ExpectationFailed
from .common.config import load
from .common.spark import session
from .gold import pipeline as gold
from .silver import pipeline as silver

LOG = logging.getLogger("balance-sim")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="balance-sim", description=__doc__)
    parser.add_argument("layer", choices=["all", "bronze", "silver", "gold", "diff", "runs"])
    parser.add_argument("--run-id", type=int, help="sim_run.id to process")
    parser.add_argument(
        "--run-a", type=int,
        help="baseline side for diff; negative means the standing baseline (see sim_gold_baseline_run)")
    parser.add_argument(
        "--run-b", type=int,
        help="candidate side for diff; negative means the standing baseline")
    parser.add_argument("--conf", default=None, help="path to pipeline.yml")
    parser.add_argument(
        "--pipeline-run-id",
        default=None,
        help="idempotency token for this invocation; Airflow passes its run id so a "
        "retried task republishes rather than duplicates",
    )
    parser.add_argument("--no-publish", action="store_true", help="build gold marts but do not write Postgres")
    parser.add_argument("--log-level", default="INFO")
    args = parser.parse_args(argv)

    logging.basicConfig(
        level=getattr(logging, args.log_level.upper()),
        format="%(asctime)s %(levelname)-5s %(name)s :: %(message)s",
    )

    cfg = load(args.conf)
    pipeline_run_id = args.pipeline_run_id or f"local-{uuid.uuid4().hex[:12]}"
    spark = session(cfg, args.layer)

    if args.layer == "runs":
        jdbc.read(
            spark,
            cfg,
            "sim_run",
            key="id",
            where=f"realm = {cfg.realm}",
            columns="id, status, trigger, scenario->>'scope' AS scope, "
            "scenario->>'scenario' AS scenario, started_at, finished_at, config_hash",
        ).orderBy("id").show(50, truncate=False)
        return 0

    if args.layer == "diff":
        if args.run_a is None or args.run_b is None:
            parser.error("diff needs --run-a and --run-b")
        auditor = Auditor(cfg=cfg, run_id=args.run_b, pipeline_run_id=pipeline_run_id)
        try:
            gold.diff(spark, cfg, auditor, args.run_a, args.run_b, publish=not args.no_publish)
        finally:
            gold.publish_audit(spark, cfg, auditor)
        return 0

    if args.run_id is None:
        parser.error(f"{args.layer} needs --run-id")

    auditor = Auditor(cfg=cfg, run_id=args.run_id, pipeline_run_id=pipeline_run_id)
    layers = ["bronze", "silver", "gold"] if args.layer == "all" else [args.layer]

    status = 0
    try:
        for layer in layers:
            LOG.info("=== %s :: run %s ===", layer, args.run_id)
            if layer == "bronze":
                bronze.ingest(spark, cfg, auditor, args.run_id)
            elif layer == "silver":
                silver.run(spark, cfg, auditor, args.run_id)
            else:
                gold.run(spark, cfg, auditor, args.run_id, publish=not args.no_publish)
    except ExpectationFailed as exc:
        # An expectation failing is a result, not a crash: the audit rows explaining
        # it are written before the process exits, or the one thing that would have
        # said why is the thing that got lost.
        LOG.error("%s", exc)
        status = 2
    finally:
        try:
            gold.publish_audit(spark, cfg, auditor)
        except Exception:  # noqa: BLE001
            LOG.exception("could not publish the audit log")
        spark.stop()

    return status


if __name__ == "__main__":
    sys.exit(main())
