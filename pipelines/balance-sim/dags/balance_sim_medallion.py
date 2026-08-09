"""Airflow DAG -- bronze, silver, gold for one simulation run.

Triggered rather than scheduled. A sweep is started by hand with `/simulate` and
takes anywhere from minutes to a day and a half (run 1 took 35 hours), so there is no
cadence to put on a cron. What there is instead is a sensor: the DAG waits for
`sim_run.status` to leave RUNNING before it ingests, because bronze's whole promise
is that it snapshots a settled source.

    airflow dags trigger balance_sim_medallion --conf '{"run_id": 1}'

The `check_run_is_settled` task accepts CANCELLED as well as COMPLETED. A stopped
sweep is a prefix of an enumeration and therefore a biased sample -- but it is a
biased sample of real measurements, the EQUIPMENT runs were all stopped deliberately,
and refusing to process them would make the pipeline useless for the data that exists.
`sim_gold_run.is_complete` carries the caveat forward instead.
"""
from __future__ import annotations

import os
from datetime import timedelta

from airflow import DAG
from airflow.exceptions import AirflowSkipException
from airflow.operators.python import PythonOperator
from airflow.providers.apache.spark.operators.spark_submit import SparkSubmitOperator
from airflow.utils.dates import days_ago

PROJECT_DIR = os.environ.get("BALANCE_SIM_HOME", "/opt/balance-sim")
POSTGRES_PACKAGE = "org.postgresql:postgresql:42.7.4"

DEFAULT_ARGS = {
    "owner": "balance",
    "retries": 1,
    "retry_delay": timedelta(minutes=5),
    "depends_on_past": False,
}


def _layer(dag: DAG, layer: str, extra: list[str] | None = None) -> SparkSubmitOperator:
    """One task per layer, each its own spark-submit.

    Separate applications rather than one long-lived session on purpose: a gold
    rebuild after a mart definition changes should not have to re-ingest 4.3 million
    rows over JDBC, and a layer that can only run inside the previous one's JVM
    cannot be retried on its own.

    `--pipeline-run-id` is Airflow's own run id. It is the idempotency token the
    publisher deletes on before appending, so a retried task republishes its slice
    rather than doubling it.
    """
    return SparkSubmitOperator(
        task_id=layer,
        dag=dag,
        application=f"{PROJECT_DIR}/jobs/cli.py",
        name=f"balance-sim-{layer}",
        packages=POSTGRES_PACKAGE,
        # cli.py is `python -m jobs.cli`; submitting the module's file directly needs
        # the project root on the path for the relative imports to resolve.
        env_vars={"PYTHONPATH": PROJECT_DIR},
        application_args=[
            layer,
            "--run-id",
            "{{ dag_run.conf['run_id'] }}",
            "--conf",
            f"{PROJECT_DIR}/conf/pipeline.yml",
            "--pipeline-run-id",
            "{{ run_id }}",
            *(extra or []),
        ],
        conf={
            "spark.sql.sources.partitionOverwriteMode": "dynamic",
            "spark.sql.session.timeZone": "UTC",
        },
    )


def check_run_is_settled(**context) -> None:
    """Refuse to ingest a sweep that is still writing.

    A RUNNING sweep appends `sim_result` rows continuously, so bronze would land a
    torn snapshot: the build dimension complete, the fact half-written, and the
    expectations passing because a partial run is internally consistent. That is the
    worst shape a data-quality failure can take.
    """
    from airflow.providers.postgres.hooks.postgres import PostgresHook

    run_id = int(context["dag_run"].conf["run_id"])
    hook = PostgresHook(postgres_conn_id="betterpvp_postgres")
    row = hook.get_first("SELECT status FROM sim_run WHERE id = %s", parameters=(run_id,))
    if row is None:
        raise ValueError(f"sim_run {run_id} does not exist")
    status = row[0]
    if status == "RUNNING":
        raise AirflowSkipException(f"sim_run {run_id} is still RUNNING; nothing settled to ingest")
    context["ti"].xcom_push(key="status", value=status)


with DAG(
    dag_id="balance_sim_medallion",
    description="Bronze/silver/gold for one balance-simulation run",
    default_args=DEFAULT_ARGS,
    schedule=None,
    start_date=days_ago(1),
    catchup=False,
    max_active_runs=1,
    tags=["balance", "simulation", "medallion"],
    params={"run_id": None},
) as dag:
    settled = PythonOperator(task_id="check_run_is_settled", python_callable=check_run_is_settled)

    bronze = _layer(dag, "bronze")
    silver = _layer(dag, "silver")
    gold = _layer(dag, "gold")

    settled >> bronze >> silver >> gold
