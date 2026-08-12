"""Gold layer driver: build the marts, then publish them to the serving database.

Gold is written twice -- parquet in the lake and tables in Postgres. The parquet copy
is the auditable one (it is versioned by run and never mutated); the Postgres copy
exists because Grafana already has a datasource pointed at that database and the
alternative is a query engine nobody asked for.
"""
from __future__ import annotations

import logging

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F

from ..common import jdbc
from ..common.audit import Auditor, Check, not_empty, unique
from ..common.config import GOLD, Config
from ..silver import pipeline as silver
from . import marts

LOG = logging.getLogger(__name__)


def run(spark: SparkSession, cfg: Config, auditor: Auditor, run_id: int, publish: bool = True) -> dict[str, int]:
    counts: dict[str, int] = {}

    run_dim = silver.read(spark, cfg, "silver_run", run_id)
    builds = silver.read(spark, cfg, "silver_build", run_id)
    results = silver.read(spark, cfg, "silver_result", run_id)

    fact = marts.matchup(results, builds, run_dim)
    fact.cache()

    auditor.expect_all(
        [
            not_empty(GOLD, "matchup"),
            unique(GOLD, "matchup", "result_id"),
            _derived_rows_are_labelled(),
            _baseline_is_unique(),
            _roll_axis_moved_the_damage(),
            _freshness_is_labelled(),
            _carried_rows_are_reported(),
            _armour_tier_is_known(),
        ],
        fact,
    )

    contributions = marts.rune_contribution(fact)
    contributions.cache()

    # The synergy mart is a four-way join over the whole fact and is then read three
    # times -- once by its expectation, once by the write, once by the row count. Left
    # uncached that is three full recomputations, which on a 6.5-million-row run is most
    # of the layer's wall clock.
    synergy = marts.rune_set_synergy(fact, contributions).cache()
    auditor.expect(_attribution_is_bounded(), synergy)

    # Both contribution marts feed the interaction one, so the skill side is named here
    # rather than built inline: it would otherwise be computed twice, once for its own
    # table and once for the union.
    skill_contributions = marts.skill_contribution(fact).cache()
    effects = marts.effect_contribution(contributions, skill_contributions)
    interaction = marts.effect_interaction(effects).cache()
    auditor.expect(_interaction_is_qualified(), interaction)

    produced = {
        "run": run_dim,
        "matchup": fact,
        "weapon_axis": marts.weapon_axis(fact),
        "rune_contribution": contributions,
        "rune_set_synergy": synergy,
        "skill_contribution": skill_contributions,
        "effect_interaction": interaction,
    }

    for name, df in produced.items():
        _write(cfg, name, df)
        # Counted from the parquet that was just written rather than from the
        # DataFrame: for an uncached mart the latter recomputes the whole plan, and
        # counting what landed also proves the write happened.
        counts[name] = auditor.record_dataset(GOLD, name, read(spark, cfg, name, run_id), "silver")

    if publish:
        _publish(cfg, produced, run_id)

    return counts


def diff(spark: SparkSession, cfg: Config, auditor: Auditor, run_a: int, run_b: int, publish: bool = True) -> int:
    """Materialise a patch diff between two already-processed runs."""
    fact_a = read(spark, cfg, "matchup", run_a)
    fact_b = read(spark, cfg, "matchup", run_b)
    df = marts.run_diff(fact_a, fact_b).withColumn("diff_key", F.concat_ws("-", F.lit(run_a), F.lit(run_b)))

    (
        df.write.mode("overwrite")
        .format("parquet")
        .partitionBy("diff_key")
        .save(cfg.dataset(GOLD, "run_diff"))
    )
    count = auditor.record_dataset(GOLD, "run_diff", df, f"gold:matchup[{run_a}] vs gold:matchup[{run_b}]")

    overlaps = {row["overlap"]: row["n"] for row in df.groupBy("overlap").agg(F.count("*").alias("n")).collect()}
    LOG.info("run %s vs %s overlap: %s", run_a, run_b, overlaps)
    auditor.expect(_diff_overlap_is_meaningful(run_a, run_b), df)

    if publish:
        table = f"{cfg.warehouse['table_prefix']}run_diff"
        _replace_slice(cfg, df, table, f"diff_key = '{run_a}-{run_b}'")
    return count


# ---------------------------------------------------------------------------


def _write(cfg: Config, name: str, df: DataFrame) -> None:
    partition = "run_id" if "run_id" in df.columns else None
    writer = df.write.mode("overwrite").format("parquet")
    if partition:
        writer = writer.partitionBy(partition)
    writer.save(cfg.dataset(GOLD, name))


def _publish(cfg: Config, produced: dict[str, DataFrame], run_id: int) -> None:
    prefix = cfg.warehouse["table_prefix"]
    skip = set(cfg.warehouse.get("skip_publish") or [])
    for name, df in produced.items():
        if name in skip:
            LOG.info("skipping %s%s (listed in warehouse.skip_publish)", prefix, name)
            continue
        table = f"{prefix}{name}"
        LOG.info("publishing %s", table)
        _replace_slice(cfg, df, table, f"run_id = {run_id}")
    _refresh_views(cfg)


# Serving-layer roll-ups over the marts just written. They are the one thing in the
# warehouse this pipeline does not produce as a DataFrame -- they are defined in
# sql/gold_ddl.sql, because they exist to give a dashboard an access path rather than to
# state a new fact -- so publishing has to poke them or they silently describe the
# previous run. A stale materialised view is the worst failure mode available here: the
# panel renders, fast, with numbers that are simply old.
# Order matters: sim_gold_skill_damage reads sim_gold_weapon_damage, and gold_skill_damage
# reads grafana_config rather than the warehouse -- it is refreshed here because a config
# change and a sweep publish are the two things that can invalidate it, and only one of them
# has a hook.
_MATERIALIZED_VIEWS = ("sim_gold_tier_grid", "sim_gold_tier_extreme",
                       "sim_gold_weapon_damage", "gold_skill_damage",
                       "sim_gold_skill_damage", "gold_skill_coverage")


def _refresh_views(cfg: Config) -> None:
    for view in _MATERIALIZED_VIEWS:
        LOG.info("refreshing %s", view)
        # CONCURRENTLY keeps the view readable while it rebuilds, so a dashboard open
        # during a publish sees the old rows rather than an exclusive lock. It needs the
        # unique index gold_ddl.sql creates; without it, fall back to a plain refresh
        # rather than leaving the view stale.
        try:
            _execute(cfg, f"REFRESH MATERIALIZED VIEW CONCURRENTLY {view}")
        except Exception:
            LOG.warning("concurrent refresh of %s failed; falling back to a locking one", view)
            _execute(cfg, f"REFRESH MATERIALIZED VIEW {view}", ignore_missing=True)


def _replace_slice(cfg: Config, df: DataFrame, table: str, predicate: str) -> None:
    """Delete-then-append the slice this pipeline invocation owns.

    Spark's JDBC writer offers `overwrite` (truncates the whole table, so every other
    run's rows vanish) and `append` (so a re-run doubles them). Neither is what a
    per-run republish means. Deleting the predicate first and appending makes the
    write idempotent for the run and leaves every other run alone.
    """
    _execute(cfg, f"DELETE FROM {table} WHERE {predicate}", ignore_missing=True)
    jdbc.write(df, cfg, table, mode="append")


def _execute(cfg: Config, statement: str, ignore_missing: bool = False) -> None:
    """Run one statement through the JVM's JDBC driver.

    py4j rather than psycopg2 so the pipeline has exactly one database dependency --
    the driver Spark already loads -- and cannot end up with two connection
    configurations that drift apart.
    """
    from pyspark import SparkContext

    jvm = SparkContext._active_spark_context._jvm  # type: ignore[union-attr]
    wh = cfg.warehouse
    props = jvm.java.util.Properties()
    props.setProperty("user", wh["user"])
    props.setProperty("password", wh["password"])
    connection = jvm.java.sql.DriverManager.getConnection(wh["jdbc_url"], props)
    try:
        statement_obj = connection.createStatement()
        try:
            statement_obj.execute(statement)
        finally:
            statement_obj.close()
    except Exception as exc:  # noqa: BLE001 -- the missing-table case is expected on a first run
        if not ignore_missing:
            raise
        LOG.info("skipped '%s': %s", statement, exc)
    finally:
        connection.close()


def read(spark: SparkSession, cfg: Config, name: str, run_id: int | None = None) -> DataFrame:
    # mergeSchema, because a dataset partitioned by run_id accumulates partitions written
    # under different schemas: a run landed before a column existed does not have it, and
    # is never rewritten. Without this Spark picks one partition's footer arbitrarily and
    # a column is present or absent depending on which file it happened to read -- which
    # fails loudly on a good day and silently drops a column on a bad one.
    df = spark.read.option("mergeSchema", "true").parquet(cfg.dataset(GOLD, name))
    if run_id is not None and "run_id" in df.columns:
        df = df.where(F.col("run_id") == run_id)
    return df


def publish_audit(spark: SparkSession, cfg: Config, auditor: Auditor) -> None:
    """The audit log is itself a gold table. A run whose expectations failed should
    be readable next to the numbers it produced, not only in a console log that is
    gone by the time anyone asks."""
    df = auditor.to_frame(spark).withColumn("run_id", F.lit(auditor.run_id))
    df.write.mode("append").format("parquet").partitionBy("run_id").save(cfg.dataset(GOLD, "data_quality"))
    table = f"{cfg.warehouse['table_prefix']}data_quality"
    _execute(cfg, f"DELETE FROM {table} WHERE pipeline_run_id = '{auditor.pipeline_run_id}'", ignore_missing=True)
    jdbc.write(df, cfg, table, mode="append")


# ---------------------------------------------------------------------------


def _derived_rows_are_labelled() -> Check:
    """The invariant the whole derived-skill design rests on: a row is derived if and
    only if it names the skill it was derived for. If either direction breaks, a
    modelled figure can be averaged into a measured one and nothing on the row says
    so."""

    def fn(df: DataFrame) -> tuple[bool, float, str]:
        bad = df.where(
            ((F.col("provenance") == "derived") & F.col("derived_skill").isNull())
            | ((F.col("provenance") == "measured") & F.col("derived_skill").isNotNull())
        ).count()
        return bad == 0, bad, f"{bad} rows whose provenance and derived_skill disagree"

    return Check("provenance_labelling", GOLD, "matchup", fn)


def _baseline_is_unique() -> Check:
    """At most one bare (no-rune) row per baseline key.

    Both delta marts join every rune-carrying row to its bare counterpart on
    `marts.BASELINE_KEY`. If two bare rows share a key -- two weapon registry keys
    with identical stat profiles that the sweep did not dedupe, say -- the join fans
    out and every rune's contribution is silently counted twice. The mart would still
    look entirely reasonable; only its row count would be wrong, and nobody checks a
    row count they have no expectation for.
    """

    def fn(df: DataFrame) -> tuple[bool, float, str]:
        bare = df.where(F.col("rune_count") == 0)
        total = bare.count()
        distinct = bare.select(*marts.BASELINE_KEY).distinct().count()
        return total == distinct, total - distinct, f"{total} bare rows over {distinct} baseline keys"

    return Check("baseline_uniqueness", GOLD, "matchup", fn)


def _roll_axis_moved_the_damage() -> Check:
    """A sweep that varied the weapon roll should have measured different damage.

    `weapon_roll` selects a corner of the item's damage envelope, so within one weapon
    against one target the `min`, `base` and `max` rows must differ in `dmg_per_hit`.
    Run 1 reports 6.000 for all three corners of `champions:thornfang` (5 / 6 / 7) --
    the roll is recorded on the build row and the duel was fought with the base
    weapon regardless.

    That is worth an expectation rather than a footnote because of what it costs: the
    axis tripled the sweep and every `min` and `max` row is a duplicate of the `base`
    one, so a third of a 35-hour run measured nothing new. And it is invisible in the
    output, since triplicated rows average to exactly what the base rows alone would.

    A warning: on a sweep with no roll axis this is vacuously fine, and the pipeline's
    job is to report the condition rather than to refuse the data.
    """

    def fn(df: DataFrame) -> tuple[bool, float, str]:
        # Bare builds only, so a rune's damage cannot stand in for the weapon's.
        scope = df.where((F.col("provenance") == "measured") & (F.col("rune_count") == 0))
        grouped = scope.groupBy("weapon_key", "role", "target_role", "target_armor").agg(
            F.countDistinct("weapon_damage_applied").alias("distinct_applied"),
            F.countDistinct("dmg_per_hit").alias("distinct_measured"),
        )
        swept = grouped.where(F.col("distinct_applied") > 1)
        total = swept.count()
        if total == 0:
            return True, 0.0, "no weapon was swept at more than one roll"
        inert = swept.where(F.col("distinct_measured") == 1).count()
        share = inert / total
        return (
            inert == 0,
            share,
            f"{inert} of {total} weapon/target groups ({share:.1%}) were swept at several rolls "
            f"and measured identical damage at all of them -- those rows are duplicates",
        )

    return Check("roll_axis_effective", GOLD, "matchup", fn, "warn")


def _freshness_is_labelled() -> Check:
    """`freshness` and `measured_run_id` must agree.

    The same invariant `provenance_labelling` enforces for derived rows, for the other
    axis a row can be something other than what it looks like. A row labelled `measured`
    whose `measured_run_id` is another run is a row that will be read as fresh evidence
    for a change it predates -- which is precisely the mistake a delta sweep makes
    possible and nothing else would catch.
    """

    def fn(df: DataFrame) -> tuple[bool, float, str]:
        bad = df.where(
            ((F.col("freshness") == "measured") & (F.col("measured_run_id") != F.col("run_id")))
            | ((F.col("freshness") == "carried") & (F.col("measured_run_id") == F.col("run_id")))
            | ((F.col("freshness") == "unknown") & F.col("measured_run_id").isNotNull())
        ).count()
        return bad == 0, bad, f"{bad} rows whose freshness and measured_run_id disagree"

    return Check("freshness_labelling", GOLD, "matchup", fn)


def _carried_rows_are_reported() -> Check:
    """States what share of the run was carried forward rather than measured.

    Always passes -- carrying rows forward is the feature working, not a fault. It is a
    check rather than a log line because the audit table is where a reader goes to ask
    "what am I actually looking at", and "62% of this run was measured in July" belongs
    beside the numbers rather than in a console nobody kept.

    It does fail on one thing: a run that is entirely carried. That is a sweep which ran
    no duels at all, which means either the config genuinely did not move -- in which
    case the run is a duplicate of its baseline and should not have been started -- or
    the scope digests are not moving when they should, which would silently freeze every
    future delta sweep at today's numbers. Both are worth stopping for.
    """

    def fn(df: DataFrame) -> tuple[bool, float, str]:
        measured_rows = df.where(F.col("provenance") == "measured")
        total = measured_rows.count()
        if total == 0:
            return True, 0.0, "no measured rows"
        by_freshness = {
            row["freshness"]: row["n"]
            for row in measured_rows.groupBy("freshness").agg(F.count("*").alias("n")).collect()
        }
        unknown = by_freshness.get("unknown", 0)
        if unknown == total:
            # A run taken before change detection existed. Reported as its own case rather
            # than folded into "all measured": the rows may well be a carried-forward mix
            # and there is no longer any way to tell, which is a different statement from
            # "this run ran every one of these duels".
            return True, 0.0, f"all {total} rows predate change detection; freshness is unknown"
        carried = by_freshness.get("carried", 0)
        if carried == 0:
            return True, 0.0, f"all {total} rows were measured by this run"
        share = carried / total
        return (
            carried < total,
            share,
            f"{carried} of {total} rows ({share:.1%}) were carried forward from an earlier "
            f"run rather than measured by this one",
        )

    return Check("carried_forward_share", GOLD, "matchup", fn)


def _armour_tier_is_known() -> Check:
    """Every armoured row should say which rung of the ladder it was measured at.

    `target_armor` folds the set and its stat roll into one discriminator, and until the
    tier axis existed a role had exactly one armour set -- so every historical armoured
    row says `role_set` and nothing says which set that was. Silver deliberately leaves
    those tiers null rather than assuming they are today's tier 1, because a guess would
    place a historical row on a rung it was never measured at.

    A warning rather than an error, because those rows are not wrong, only unplaceable:
    they are still perfectly good for every comparison that does not cross tiers. What
    the check exists to prevent is a TTK-parity panel silently reading a run where most
    of the ladder is missing and reporting the remainder as the answer.
    """

    def fn(df: DataFrame) -> tuple[bool, float, str]:
        armoured = df.where((F.col("provenance") == "measured") & (F.col("target_armor") != "none"))
        total = armoured.count()
        if total == 0:
            return True, 0.0, "no armoured targets in this run"
        unknown = armoured.where(F.col("target_armor_tier").isNull()).count()
        share = unknown / total
        tiers = sorted(
            row["target_armor_tier"]
            for row in df.select("target_armor_tier").distinct().collect()
            if row["target_armor_tier"] is not None
        )
        return (
            unknown == 0,
            share,
            f"{unknown} of {total} armoured rows ({share:.1%}) have no tier; "
            f"tiers present: {tiers or 'none'}",
        )

    return Check("armour_tier_known", GOLD, "matchup", fn, "warn")


def _interaction_is_qualified() -> Check:
    """Every interaction verdict must say how much weapon variety is behind it.

    A correlation between an effect's value and its carrier's attack speed is only a
    finding if several carriers were measured. On a `MELEE` sweep there is one weapon,
    every correlation is null, and the mart correctly says "speed did not vary" -- the
    failure this guards is the row that carries a confident verdict anyway.

    So: a row claiming to compound or contend must be marked `correlation_reliable`,
    and a reliable row must have a non-null coefficient. The two together mean a panel
    can sort on the verdict without also having to re-derive whether to believe it.
    """

    def fn(df: DataFrame) -> tuple[bool, float, str]:
        verdicts = ("compounds with attack speed", "contends with attack speed")
        bad = df.where(
            (F.col("interaction").isin(*verdicts) & ~F.col("correlation_reliable"))
            | (F.col("correlation_reliable") & F.col("interaction").isin(*verdicts) & F.col("speed_corr").isNull())
        ).count()
        total = df.count()
        if total == 0:
            return True, 0.0, "no effects were measured against a baseline"
        reliable = df.where(F.col("correlation_reliable")).count()
        return (
            bad == 0,
            bad,
            f"{bad} unqualified verdicts; {reliable} of {total} effect rows have "
            f"3+ carrier weapons behind them",
        )

    return Check("interaction_qualified", GOLD, "effect_interaction", fn)


def _attribution_is_bounded() -> Check:
    """A rune set cannot have more members attributed than it has runes.

    Written after the mart reported 30 attributed members for a 2-rune set: the member
    sum was grouped by build, and a build is measured against every target, so every
    set's members were counted once per target. The synergy figure was wrong by a
    factor of the target count and looked entirely plausible -- a large negative
    number, which is a thing rune sets genuinely do. This is the bound that says so.
    """

    def fn(df: DataFrame) -> tuple[bool, float, str]:
        if df.isEmpty():
            return True, 0, "no multi-rune sets in this run"
        bad = df.where(F.col("runes_attributed") > F.col("rune_count")).count()
        complete = df.where(F.col("attribution_complete")).count()
        total = df.count()
        return (
            bad == 0,
            bad,
            f"{bad} sets attribute more members than they carry; "
            f"{complete} of {total} sets ({complete / total:.1%}) have every member measured alone",
        )

    return Check("rune_set_attribution", GOLD, "rune_set_synergy", fn)


def _diff_overlap_is_meaningful(run_a: int, run_b: int) -> Check:
    """A diff over a small overlap is a biased sample of the two runs, and the number
    that says so belongs beside the deltas rather than in a footnote."""

    def fn(df: DataFrame) -> tuple[bool, float, str]:
        total = df.count()
        both = df.where(F.col("overlap") == "both").count()
        share = both / total if total else 0.0
        return (
            share >= 0.5,
            share,
            f"{both} of {total} keys ({share:.2%}) are present in both run {run_a} and run {run_b}",
        )

    return Check("diff_overlap", GOLD, "run_diff", fn, "warn")
