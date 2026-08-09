"""Airflow DAG -- diff two processed runs.

The workflow NOTES.md calls the core one: a balance change ships with a before/after,
not with gut feel. Both runs must already have a gold `matchup` mart, which is what
makes this a separate DAG rather than a task on the medallion one -- the baseline is
usually weeks older than the candidate.

    airflow dags trigger balance_sim_diff --conf '{"run_a": 1, "run_b": 4}'
"""
from __future__ import annotations

import os
from datetime import timedelta

from airflow import DAG
from airflow.providers.apache.spark.operators.spark_submit import SparkSubmitOperator
from airflow.utils.dates import days_ago

PROJECT_DIR = os.environ.get("BALANCE_SIM_HOME", "/opt/balance-sim")

with DAG(
    dag_id="balance_sim_diff",
    description="Patch diff between two processed balance-simulation runs",
    default_args={"owner": "balance", "retries": 1, "retry_delay": timedelta(minutes=5)},
    schedule=None,
    start_date=days_ago(1),
    catchup=False,
    tags=["balance", "simulation", "medallion"],
    params={"run_a": None, "run_b": None},
) as dag:
    SparkSubmitOperator(
        task_id="diff",
        application=f"{PROJECT_DIR}/jobs/cli.py",
        name="balance-sim-diff",
        packages="org.postgresql:postgresql:42.7.4",
        env_vars={"PYTHONPATH": PROJECT_DIR},
        application_args=[
            "diff",
            "--run-a",
            "{{ dag_run.conf['run_a'] }}",
            "--run-b",
            "{{ dag_run.conf['run_b'] }}",
            "--conf",
            f"{PROJECT_DIR}/conf/pipeline.yml",
            "--pipeline-run-id",
            "{{ run_id }}",
        ],
    )
