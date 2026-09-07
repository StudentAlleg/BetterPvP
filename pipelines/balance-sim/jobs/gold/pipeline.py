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
        # After the publish, because _publish is what refreshes the views these read. The
        # baseline is the one thing in the warehouse that is ALSO implemented in the plugin,
        # so it is the one thing that can drift without either side changing.
        auditor.expect(_baseline_is_current(spark, cfg), fact)
        auditor.expect(_baseline_agrees_with_engine(spark, cfg), fact)

    return counts


def diff(spark: SparkSession, cfg: Config, auditor: Auditor, run_a: int, run_b: int, publish: bool = True) -> int:
    """Materialise a patch diff between two already-processed runs.

    Either side may be a NEGATIVE run id, which names the standing baseline for a
    (realm, scenario) rather than a sweep -- -11 is realm 1 one_way, the union of runs 7,
    12 and 14, and it is the side an incremental change usually wants to be measured
    against. See `_diff_side`.

    The join is on canonical fight identity, not on `sim_build.fingerprint`, so the
    canonical views have to be current. They are refreshed by `_publish`; a diff run
    against a warehouse that has never published will read whatever they last held.
    """
    # 608,088 rows across every run, at build grain -- `query` would pull all of it through
    # one connection into one task. Bucketed on build_id, which is already a number and is
    # uniform by construction.
    canon_build = jdbc.query_wide(
        spark,
        cfg,
        "SELECT build_id, canonical_weapon_key, canonical_skill_key, bands_known, "
        "band_contested FROM sim_gold_canonical_build",
        on="build_id::text",
    ).cache()
    band_canon = jdbc.query(
        spark,
        cfg,
        "SELECT realm, scenario, role, skill, allocated_level, canonical_level "
        "FROM sim_skill_band_canon",
    ).cache()

    fact_a = _diff_side(spark, cfg, run_a, canon_build, band_canon)
    fact_b = _diff_side(spark, cfg, run_b, canon_build, band_canon)
    # '_vs_' rather than '-'. A baseline side is a NEGATIVE id, and '-11-15' splits into
    # ('', '11', '15') -- every reader of the key would take the wrong ends, and the
    # dashboard's run-pair picker would render "run  ->  run 11". The separator has to be
    # something that cannot occur inside an integer.
    df = marts.run_diff(fact_a, fact_b).withColumn(
        "diff_key", F.concat_ws("_vs_", F.lit(run_a), F.lit(run_b))
    )
    # A DERIVED row on the candidate side of a BASELINE diff is not a coverage gap, and
    # counting it as one is a lie the overlap number told for every baseline diff so far.
    # `sim_gold_baseline_cell` is measured-only -- it has no provenance column and holds no
    # modelled rows at all -- so a derived row cannot match a baseline no matter how right it
    # is. Run 14 against baseline -11 is the whole story: 18,972 keys, 17,568 of them measured
    # and every single one matched, and the 1,404 reported as "only in B" were exactly its
    # derived rows.
    #
    # Marked rather than dropped. Filtering them out here would make 1,404 rows vanish between
    # the fact and the diff with nothing saying where they went, and "why is this smaller than
    # the run" is a question the next reader should not have to answer twice. They stay, they
    # say what they are, and `_diff_overlap_is_meaningful` leaves them out of its denominator.
    baseline_diff = run_a < 0 or run_b < 0
    df = df.withColumn(
        "baseline_incomparable",
        F.lit(baseline_diff)
        & (F.col("provenance") == F.lit("derived"))
        & (F.col("overlap") != F.lit("both")),
    )

    path = cfg.dataset(GOLD, "run_diff")
    (
        df.write.mode("overwrite")
        .format("parquet")
        .partitionBy("diff_key")
        .save(path)
    )
    # Read the diff BACK before anything else touches it. `df` is a lazy plan over two JDBC
    # reads and a full outer join; the parquet write does not feed back into it, so the audit
    # count, the overlap tally, both checks and the publish would each re-run that join from
    # the database -- five complete passes over 2.4 million rows. The run that proved the
    # canonical keys work took twenty minutes doing exactly that and was killed for memory
    # before it reached the publish, which left the warehouse holding the previous, wrong
    # result. Re-reading costs one columnar scan per action instead, and unlike .cache() it
    # does not ask a machine that just ran out of memory to hold the frame in it.
    df = spark.read.parquet(path)
    count = auditor.record_dataset(GOLD, "run_diff", df, f"gold:matchup[{run_a}] vs gold:matchup[{run_b}]")

    overlaps = {row["overlap"]: row["n"] for row in df.groupBy("overlap").agg(F.count("*").alias("n")).collect()}
    LOG.info("run %s vs %s overlap: %s", run_a, run_b, overlaps)
    if not overlaps.get("both"):
        _name_the_disjoint_key(fact_a, fact_b, run_a, run_b)
    auditor.expect(_diff_overlap_is_meaningful(run_a, run_b), df)
    auditor.expect(_diff_identity_is_canonical(run_a, run_b), df)

    if publish:
        table = f"{cfg.warehouse['table_prefix']}run_diff"
        _replace_slice(cfg, df, table, f"diff_key = '{run_a}_vs_{run_b}'")
    return count


# ---------------------------------------------------------------------------


def _name_the_disjoint_key(fact_a: DataFrame, fact_b: DataFrame, run_a: int, run_b: int) -> None:
    """Nothing matched. Say WHICH key column is to blame, per column.

    Zero overlap has two very different causes and the row counts cannot tell them apart. It
    is a legitimate answer -- a cross-scenario pair shares no fight by construction, and so
    does a diff of two genuinely disjoint scopes. It is also what a key that means two
    different things on the two sides looks like, and that one is a bug.

    This exists because the second cause shipped. `sim_gold_canonical_build` spelled the rune
    set as raw jsonb (`["core:scorching"]`) while every matchup fact spells it the way silver
    does (`core:scorching`), and the target key rendered 29 health as `hp:29.00` in Postgres
    against `hp:29.0` in Spark. A baseline diff against one of its OWN contributing runs came
    back 2,381,265 only_a and 18,972 only_b -- a full, successful, completely empty run, with
    nothing in the log naming a column.

    A column whose two sides share no value AT ALL, while other columns share plenty, is not
    a scope difference. It is a spelling difference, and it is named here.

    Run only on the zero-overlap path, so the ten aggregations it costs are paid exactly when
    there is nothing else to go on.
    """
    LOG.error("run %s vs %s matched NOTHING -- checking each key column for a shared vocabulary", run_a, run_b)
    for column in marts.CANONICAL_DIFF_KEY:
        left = fact_a.select(F.col(column).alias("v")).distinct()
        right = fact_b.select(F.col(column).alias("v")).distinct()
        n_left, n_right = left.count(), right.count()
        shared = left.intersect(right).count()
        verdict = "DISJOINT" if shared == 0 and n_left and n_right else "ok"
        LOG.error(
            "  %-24s A=%-8s B=%-8s shared=%-8s %s", column, n_left, n_right, shared, verdict
        )


def _diff_side(
    spark: SparkSession, cfg: Config, run: int, canon_build: DataFrame, band_canon: DataFrame
) -> DataFrame:
    """One side of a diff, as a canonically-identified matchup fact.

    A positive id is a sweep: its parquet matchup, put through `marts.canonical_identity`.

    A negative id is the standing baseline -- the pseudo-run from sim_gold_baseline_run,
    -(realm * 10 + scenario ordinal) -- and it is read from `sim_gold_canonical_cell`,
    which is ALREADY at canonical grain. That is the point of allowing it. The full
    baseline for realm 1 one_way is not one run, it is runs 7 (EQUIPMENT), 12 and 14
    (SKILLS) unioned, and until now a diff could only name a single run_id -- so the only
    available comparison against "the baseline" was against whichever third of it the
    reader happened to pick.

    Two honest limitations on the baseline side, both structural rather than oversights.
    It carries only MEASURED rows: sim_gold_baseline_cell has no provenance column because
    the standing baseline is what was actually duelled, and a modelled row is not that. And
    it has no kill_rate, which sim_gold_canonical_cell does not aggregate. Both come back
    null, and a delta against null is null -- reported as absent rather than as zero.
    """
    if run >= 0:
        fact = read(spark, cfg, "matchup", run)
        return marts.canonical_identity(fact, canon_build, band_canon)

    LOG.info("diff side %s is the standing baseline, read from sim_gold_canonical_cell", run)
    # 2,381,265 rows for realm 1 one_way (of 2,540,205 canonical rows overall). Bucketed on
    # the canonical weapon key, the widest axis of the fold and so the one a hash spreads
    # most evenly -- measured at 7k to 313k per bucket over 16, which is uneven but is not
    # the single serialised task the unbucketed read would be.
    cell = jdbc.query_wide(
        spark,
        cfg,
        "SELECT c.realm, c.scenario, c.role, c.canonical_weapon_key, c.rune_set_key, "
        "       c.canonical_skill_key, c.canonical_target_key, c.bands_known, "
        "       c.band_contested, c.sample_fingerprint, "
        "       array_to_string(c.weapon_aliases, ', ')      AS weapon_key, "
        "       array_to_string(c.skill_set_aliases, ', ')   AS skill_set_key, "
        "       array_to_string(c.target_role_aliases, ', ') AS target_role, "
        "       c.dmg_per_hit, c.dps_sustained, c.ttk_s "
        f"  FROM sim_gold_canonical_cell c "
        f"  JOIN sim_gold_baseline_run r ON r.realm = c.realm AND r.scenario = c.scenario "
        f" WHERE r.run_id = {int(run)}",
        on="canonical_weapon_key",
    )

    return (
        cell
        # Read back out of the canonical key rather than assumed. A folded row is unarmoured
        # by construction -- the fold only fires when the armour IS none -- but an UNFOLDED
        # row keeps its 'ROLE/armour' key, and hardcoding 'none' would have labelled every
        # armoured baseline row as bare. It is a descriptor, not part of the join, so this
        # was wrong without being loud.
        .withColumn(
            "target_armor",
            F.when(F.col("canonical_target_key").startswith("hp:"), F.lit("none")).otherwise(
                F.split(F.col("canonical_target_key"), "/").getItem(1)
            ),
        )
        .withColumn("provenance", F.lit("measured"))
        .withColumn("derived_skill", F.lit(None).cast("string"))
        .withColumn("canonical_derived_level", F.lit(None).cast("int"))
        .withColumn("kill_rate", F.lit(None).cast("double"))
        .withColumnRenamed("sample_fingerprint", "fingerprint")
        .withColumn("run_id", F.lit(run).cast("bigint"))
        # The cell view stores its averages as NUMERIC, which Spark reads as DECIMAL; the
        # sweep side is DOUBLE. Left alone, the coalesces in run_diff would be comparing
        # two numeric types across a full outer join, so the cast is done once here rather
        # than being left to whatever Spark picks per column.
        .withColumn("dmg_per_hit", F.col("dmg_per_hit").cast("double"))
        .withColumn("dps_sustained", F.col("dps_sustained").cast("double"))
        .withColumn("ttk_s", F.col("ttk_s").cast("double"))
    )


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
# sim_gold_skill_modifier is last and is the odd one out: it reads sim_trace and sim_build
# directly rather than a published mart, because the hit trace has no path through
# bronze/silver/gold at all -- it lands in bronze and stops. Refreshing it here is what gives
# that table its only consumer, and the view guards its own cost by restricting the scan to
# runs whose builds actually carried a skill.
# ORDER MATTERS. sim_gold_damage_point and sim_gold_skill_damage are both built FROM
# sim_gold_weapon_damage, so refreshing them before it would rebuild them against the
# previous run's rows and publish a chart that silently disagrees with the permutation grain
# it was exploded from.
_MATERIALIZED_VIEWS = ("sim_gold_tier_grid", "sim_gold_tier_extreme",
                       # The standing baseline comes FIRST, and that ordering is load-bearing
                       # rather than tidy. sim_gold_weapon_damage now unions the baseline in
                       # under a synthetic negative run_id (via sim_gold_baseline_matchup, a
                       # plain view needing no refresh), so refreshing the damage views before
                       # the baseline would publish this run's numbers against LAST refresh's
                       # baseline -- the two halves of the same chart one run apart.
                       #
                       # The cell view spans EVERY completed run rather than the one being
                       # published, so it is the one view here whose content changes even when
                       # this run's own marts do not. The lane view reads the cell view.
                       "sim_gold_baseline_cell", "sim_gold_baseline_lane",
                       # The canonical fold, deepest-first. The build view carries the weapon
                       # and level folds at build grain; the cell view reads BOTH it and the
                       # baseline above, so it comes last of the three or it re-keys the
                       # PREVIOUS refresh's baseline and every alias list it publishes
                       # describes a union one run out of date. sim_skill_band_canon is not
                       # here on purpose: it is a plain view over sim_skill_level_band, also a
                       # plain view, so both are current by construction.
                       "sim_gold_canonical_build", "sim_gold_canonical_cell",
                       "sim_gold_weapon_damage", "sim_gold_damage_point",
                       # density and composition both read damage_point, after their parent.
                       "sim_gold_damage_density", "sim_gold_damage_composition",
                       "gold_skill_damage",
                       "sim_gold_skill_damage", "gold_skill_coverage",
                       "sim_gold_skill_modifier", "sim_gold_modifier_expectation",
                       "sim_gold_ambient_modifier",
                       # The composed multi-skill chain, and its order is load-bearing three ways.
                       # skill_effect reads sim_trace directly (like skill_modifier above, the hit
                       # trace has no path through bronze/silver). composed_skillset reads
                       # skill_effect. composed_fit then reads composed_skillset back -- it measures
                       # the composition against the single-skill rows it was derived from, so it
                       # MUST come last or it grades the previous refresh's arithmetic.
                       "sim_gold_skill_effect", "sim_gold_composed_skillset",
                       "sim_gold_composed_fit")


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


# The rule the plugin applies when it carries a matchup forward, written the way the plugin
# writes it: a correlated lateral that takes the newest measurement of ONE matchup. The view
# states the same rule as a ROW_NUMBER window over everything at once. Two formulations of one
# rule, in two languages, in two repositories -- which is exactly the arrangement that agrees
# on the day it is written and silently stops agreeing later.
#
# Restricted to CONTESTED cells, and that restriction is exact rather than a sample. A cell
# only one run measured resolves to that run under any recency rule whatsoever, so it cannot
# distinguish the two implementations. Every cell where they COULD disagree has
# times_measured > 1, so checking those checks all of them -- 17,568 rows today instead of
# 4,119,084, for the same guarantee.
_ENGINE_RULE_SQL = """
SELECT COUNT(*)                                                        AS contested,
       COUNT(*) FILTER (WHERE c.supplied_by_run_id <> src.run_id)      AS disagreed,
       COALESCE(MIN(c.supplied_by_run_id) FILTER
                (WHERE c.supplied_by_run_id <> src.run_id), 0)         AS example_view_run,
       COALESCE(MIN(src.run_id) FILTER
                (WHERE c.supplied_by_run_id <> src.run_id), 0)         AS example_engine_run
FROM sim_gold_baseline_cell c
CROSS JOIN LATERAL (
    SELECT r.run_id
    FROM sim_result r
    JOIN sim_build ob ON ob.id = r.build_id
    JOIN sim_run run ON run.id = r.run_id
    WHERE ob.fingerprint = c.fingerprint
      AND r.target_role = c.target_role
      AND r.target_armor = c.target_armor
      AND r.config_scope_hash = c.config_scope_hash
      AND run.realm = c.realm
      AND run.status IN ('COMPLETED', 'CANCELLED')
      AND run.scenario ->> 'scenario' = c.scenario
    ORDER BY run.started_at DESC, run.id DESC
    LIMIT 1
) src
WHERE c.contested
"""

# Whether the materialised baseline has been refreshed since the runs it is supposed to
# describe. A stale matview is invisible to the rule check above -- both sides would agree
# perfectly about a baseline that is missing a run entirely.
_BASELINE_CURRENCY_SQL = """
WITH newest AS (
    SELECT DISTINCT ON (realm, scenario ->> 'scenario')
           id, realm, scenario ->> 'scenario' AS scenario
    FROM sim_run
    WHERE status IN ('COMPLETED', 'CANCELLED')
    ORDER BY realm, scenario ->> 'scenario', started_at DESC, id DESC
)
SELECT COUNT(*)                                            AS lanes,
       COUNT(*) FILTER (WHERE b.cells IS NULL)             AS missing,
       COALESCE(MIN(n.id) FILTER (WHERE b.cells IS NULL), 0) AS example_run
FROM newest n
         LEFT JOIN (SELECT realm, scenario, supplied_by_run_id, COUNT(*) AS cells
                    FROM sim_gold_baseline_cell
                    GROUP BY 1, 2, 3) b
                   ON b.realm = n.realm AND b.scenario = n.scenario
                       AND b.supplied_by_run_id = n.id
-- A run with no hashed results can never supply a baseline cell and its absence is correct,
-- so it is not evidence of staleness. Run 1 predates config_scope_hash entirely.
WHERE EXISTS (SELECT 1 FROM sim_result r
              WHERE r.run_id = n.id AND r.config_scope_hash IS NOT NULL)
"""


def _baseline_agrees_with_engine(spark: SparkSession, cfg: Config) -> Check:
    """`sim_gold_baseline_cell` resolves every contested cell the way the plugin would.

    The warehouse and the plugin now implement the same baseline rule independently -- the
    view so a dashboard can show what the baseline is, `SimResultRepository` so a delta sweep
    can carry from it. Nothing structural keeps them in step: someone tightening the view's
    status filter, or reordering its recency tiebreak, or adding scope back to its partition,
    would produce a dashboard that describes a baseline no sweep will ever use. The failure is
    silent in both directions, which is the argument for checking it on every publish rather
    than trusting the two comments to be read together.
    """

    def fn(_: DataFrame) -> tuple[bool, float, str]:
        row = jdbc.query(spark, cfg, _ENGINE_RULE_SQL).first()
        if row is None or row["contested"] == 0:
            # No cell has been measured twice yet, so the two rules have not been asked a
            # question they could answer differently. Reported rather than passed silently:
            # "agrees everywhere" and "was never tested" are different states.
            return True, 0.0, "no contested cells yet; the two rules are untested, not agreed"
        disagreed = int(row["disagreed"])
        contested = int(row["contested"])
        if disagreed == 0:
            return True, 0.0, (
                f"all {contested} contested cells resolve to the same run in the view and "
                f"under the plugin's carry rule"
            )
        return False, float(disagreed), (
            f"{disagreed} of {contested} contested cells disagree: the view supplies them from "
            f"run {row['example_view_run']}, the plugin's carry rule would take run "
            f"{row['example_engine_run']}. sim_gold_baseline_cell and "
            f"SimResultRepository.carryForwardResults have drifted apart"
        )

    return Check("baseline_agrees_with_engine", GOLD, "baseline_cell", fn)


def _baseline_is_current(spark: SparkSession, cfg: Config) -> Check:
    """The baseline view has been refreshed since the newest run in each lane.

    Guards the blind spot in the check above: two implementations of a rule agree perfectly
    about a baseline that is simply out of date. The newest run in a lane always supplies at
    least its own cells -- nothing can supersede it -- so a newest run holding zero cells means
    the matview has not been rebuilt since that run published.
    """

    def fn(_: DataFrame) -> tuple[bool, float, str]:
        row = jdbc.query(spark, cfg, _BASELINE_CURRENCY_SQL).first()
        if row is None or row["lanes"] == 0:
            return True, 0.0, "no lane has a run carrying config scope hashes yet"
        missing = int(row["missing"])
        lanes = int(row["lanes"])
        if missing == 0:
            return True, 0.0, f"the newest run in each of {lanes} lane(s) supplies baseline cells"
        return False, float(missing), (
            f"{missing} of {lanes} lanes have a newest run (e.g. {row['example_run']}) supplying "
            f"no baseline cells, so sim_gold_baseline_cell is stale against it"
        )

    return Check("baseline_is_current", GOLD, "baseline_cell", fn)


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
        # Measured against the SMALLER side, not against the union. The question this check
        # exists to ask is whether the two runs overlap enough for their deltas to mean
        # anything, and dividing by the union answers a different one: it reports the size
        # RATIO between the sides as though it were a coverage problem. A baseline diff is
        # exactly where those come apart -- baseline -11 holds 2,393,406 keys and run 14
        # holds 18,972, so even a perfect run 14 that matched every single one of its own
        # rows would score 0.79% against the union and fail. Measured against itself it
        # scores 92.6% (17,568 of 18,972), and the 1,404 it misses are not a shortfall
        # either: they are exactly run 14's DERIVED rows, which the baseline structurally
        # cannot hold because sim_gold_baseline_cell is measured-only. Every one of run
        # 14's 17,568 measured rows matched. What a small side cannot cover is the large
        # side's scope, and that is not a defect; it is what diffing an increment against
        # a baseline IS.
        # Rows the baseline structurally cannot hold are not part of the question. See the
        # `baseline_incomparable` comment in `diff`; without this the check marks a run down
        # for carrying derived rows, which every SKILLS run does by design.
        comparable = df.where(~F.coalesce(F.col("baseline_incomparable"), F.lit(False)))
        both = comparable.where(F.col("overlap") == "both").count()
        only_a = comparable.where(F.col("overlap") == "only_a").count()
        only_b = comparable.where(F.col("overlap") == "only_b").count()
        smaller = min(both + only_a, both + only_b)
        share = both / smaller if smaller else 0.0
        return (
            share >= 0.5,
            share,
            f"{both} of the smaller side's {smaller} keys ({share:.2%}) are present in both "
            f"run {run_a} and run {run_b} ({both + only_a} vs {both + only_b} keys in total)",
        )

    return Check("diff_overlap", GOLD, "run_diff", fn, "warn")


def _diff_identity_is_canonical(run_a: int, run_b: int) -> Check:
    """How much of the overlap is the same fight under two different spellings.

    This is the audit of the switch away from `sim_build.fingerprint`. Every row it counts
    is a row the old key would have reported TWICE -- once as "only in A" and once as
    "only in B" -- turning a re-spelling into a disappearance plus an appearance, which
    reads exactly like a balance change and is not one.

    It warns rather than fails, and high is not bad. A large share means the two runs
    enumerate the same fights under different names, which is what an equipment-scope
    change looks like and is precisely the case this exists to make visible. Zero on two
    runs that enumerated identically is the other correct answer. What would be a problem
    is a number that moves when nothing about the scope changed.
    """

    def fn(df: DataFrame) -> tuple[bool, float, str]:
        both = df.where(F.col("overlap") == "both")
        matched = both.count()
        respelled = both.where(F.col("spelling_changed")).count()
        share = respelled / matched if matched else 0.0
        return (
            True,
            share,
            f"{respelled} of {matched} matched rows ({share:.2%}) are the same fight under a "
            f"different fingerprint in run {run_a} and run {run_b}; a fingerprint join would "
            f"have reported each of them as a removal AND an addition",
        )

    return Check("diff_respelled", GOLD, "run_diff", fn, "warn")
