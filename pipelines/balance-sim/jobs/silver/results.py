"""Silver -- the matchup fact.

Three jobs here:

1. Flatten `extras`. Every figure that qualifies a row lives in it -- the iteration
   count behind the mean, the kill rate that says what `ttk_s` averages over, the
   energy ledger, the per-modifier breakdown -- and a dashboard that has to reach
   into JSON for those tends not to.

2. Recover the engagement's *shape*, not just its totals. `swing_interval_ticks` is
   the one figure the sweep does not store and every downstream model needs: it is
   what lets a change in damage-per-hit be turned back into a TTK without re-running
   the duel. It is exact rather than assumed, because the engine defines
   `dps_sustained = total / ttk_s` over the window from the first landed hit to the
   lethal one, so the interval falls straight out of `ttk_ticks / (hits - 1)`.

3. Make the reductions explicit. `target_role_aliases` collapses unarmoured
   skill-less defenders of equal health across roles; like the weapon aliases in
   builds.py, that is coverage a filter must be able to resolve.
"""
from __future__ import annotations

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F

from ..bronze import ingest as bronze
from ..common.audit import Auditor, Check, non_null, not_empty, unique
from ..common.config import SILVER, Config
from . import schemas

TICKS_PER_SECOND = 20.0
# DuelOrchestrator.burstDps sums the best one-second window; BURST_WINDOW_TICKS is 20.
BURST_WINDOW_TICKS = 20


def optional(df: DataFrame, name: str, dtype: str):
    """The column if bronze has it, otherwise a typed null under the same name.

    Bronze is an immutable archive rather than a mirror of today's schema: a partition
    landed in July does not have a column added in August, and re-ingesting it would
    not change that because the source rows are NULL there too. Silver therefore has to
    read the union of every schema bronze has ever had, or it could only process runs
    newer than itself -- which would make the archive useless for exactly the historical
    comparison it exists to support.

    Typed rather than `lit(None)` so the silver schema is identical either way, and a
    reader cannot tell a partition that predates a column from one that has it empty by
    looking at the shape of the data. That is deliberate: the two mean the same thing.
    """
    if name in df.columns:
        return F.col(name).cast(dtype).alias(name)
    return F.lit(None).cast(dtype).alias(name)


def build(spark: SparkSession, cfg: Config, auditor: Auditor, run_id: int) -> DataFrame:
    raw = bronze.read(spark, cfg, "sim_result", run_id)

    extras = F.from_json(F.col("extras"), schemas.EXTRAS)
    target_skills = F.from_json(F.col("target_skills"), schemas.SKILLS)
    target_aliases = F.from_json(F.col("target_role_aliases"), schemas.STRING_ARRAY)

    df = (
        raw.withColumn("x", extras)
        .withColumn("target_skill_slots", F.coalesce(target_skills, F.array()))
        .withColumn("target_role_alias_keys", F.coalesce(target_aliases, F.array()))
        .select(
            F.col("id").alias("result_id"),
            F.col("run_id"),
            F.col("build_id"),
            F.col("target_role"),
            F.col("target_armor"),
            # The armour ladder, split back out of `target_armor` -- which folds the set
            # and its stat roll into one discriminator and always has. Read through
            # `optional` for the same reason as the freshness columns below: runs taken
            # before the tier axis existed have neither.
            optional(raw, "target_armor_set", "string"),
            optional(raw, "target_armor_tier", "int"),
            F.col("target_hp").cast("double").alias("target_hp"),
            F.col("target_points"),
            F.col("target_skill_slots"),
            F.size("target_skill_slots").alias("target_skill_count"),
            F.col("target_role_alias_keys"),
            # -- measured outcome ------------------------------------------
            F.col("dmg_per_hit").cast("double").alias("dmg_per_hit"),
            F.col("dps_sustained").cast("double").alias("dps_sustained"),
            F.col("dps_burst").cast("double").alias("dps_burst"),
            F.col("ttk_s").cast("double").alias("ttk_s"),
            F.col("hits_to_kill").cast("double").alias("hits_to_kill"),
            F.col("energy_limited"),
            # -- extras ----------------------------------------------------
            F.col("x.kills").alias("kills"),
            F.col("x.kill_rate").alias("kill_rate"),
            F.col("x.iterations").alias("iterations"),
            F.col("x.planned_iterations").alias("planned_iterations"),
            F.col("x.attacker_deaths").alias("attacker_deaths"),
            F.col("x.energy_limited_iterations").alias("energy_limited_iterations"),
            F.col("x.dmg_per_hit_stddev").alias("dmg_per_hit_stddev"),
            F.col("x.dps_p50").alias("dps_p50"),
            F.col("x.dps_p90").alias("dps_p90"),
            F.col("x.ttk_ticks_mean").alias("ttk_ticks_mean"),
            F.col("x.ttk_ticks_p50").alias("ttk_ticks_p50"),
            F.col("x.ttk_ticks_p90").alias("ttk_ticks_p90"),
            F.col("x.reasons").alias("modifier_counts"),
            F.col("x.activations").alias("activation_counts"),
            F.col("x.energy.max_energy").alias("energy_max"),
            F.col("x.energy.spent_on_skills_per_duel").alias("energy_spent_per_duel"),
            F.col("x.energy.regen_custom_per_duel").alias("energy_regen_custom_per_duel"),
            F.col("x.energy.regen_natural_per_duel").alias("energy_regen_natural_per_duel"),
            F.col("extras").alias("extras_raw"),
            # -- freshness -------------------------------------------------
            # Read through `optional` because bronze is an archive, not a mirror: a
            # partition landed before these columns existed does not have them, and
            # re-ingesting it would not give it them either -- the source rows have
            # been NULL there since the migration. Failing on a historical partition
            # would mean silver could only ever process runs newer than its own
            # schema, which is the opposite of what an immutable bronze is for.
            # Which run actually ran the duels. A `--changed` sweep carries every
            # matchup whose own config did not move forward from a baseline rather
            # than re-measuring it, so a run can hold rows measured months apart.
            # Null on every run taken before that existed, which reads correctly as
            # "unknown" rather than as "carried".
            optional(raw, "measured_run_id", "long"),
            # The digest of exactly the config this matchup depended on. Not used to
            # decide anything here -- the sim already did -- but it is what makes the
            # decision auditable after the fact: two rows with the same scope hash
            # were measured against the same weapon, runes, skills and armour, and
            # that is checkable from the warehouse rather than only from the log.
            optional(raw, "config_scope_hash", "string"),
            F.col("_pipeline_run_id"),
        )
        # measured (this run ran the duels) / carried (an earlier run did, and nothing
        # it depended on has changed since) / unknown (predates the column). Named
        # rather than left as an id comparison because every consumer wants the three
        # cases, and re-deriving `measured_run_id = run_id` in each panel is how the
        # meaning drifts.
        .withColumn(
            "freshness",
            F.when(F.col("measured_run_id").isNull(), F.lit("unknown"))
            .when(F.col("measured_run_id") == F.col("run_id"), F.lit("measured"))
            .otherwise(F.lit("carried")),
        )
        # The set is always recoverable from the discriminator, because stripping the
        # roll suffix is a string operation and not a lookup: a pre-tier row that says
        # `role_set_max` was wearing the set called `role_set`. So legacy rows keep a
        # usable grouping key even though nothing recorded the set separately at the time.
        .withColumn(
            "target_armor_set",
            F.coalesce(
                F.col("target_armor_set"),
                F.regexp_replace(F.col("target_armor"), r"_(min|max)$", ""),
            ),
        )
        # The tier is not recoverable, and is deliberately left null rather than guessed.
        # `role_set` named whichever set the role had at the time, and this pipeline
        # cannot know it was the same set that ranks tier 1 today -- a guess would put a
        # historical row on a rung it was never measured at, which is precisely the error
        # a TTK-parity comparison is least able to survive. Bare is the exception: `none`
        # meant tier 0 then and means tier 0 now.
        .withColumn(
            "target_armor_tier",
            F.when(F.col("target_armor") == "none", F.lit(0)).otherwise(F.col("target_armor_tier")),
        )
        # -- engagement shape ---------------------------------------------
        .withColumn("total_damage", F.col("dmg_per_hit") * F.col("hits_to_kill"))
        .withColumn(
            "swing_interval_ticks",
            F.when(
                F.col("hits_to_kill") > 1,
                F.col("ttk_ticks_mean") / (F.col("hits_to_kill") - F.lit(1.0)),
            ),
        )
        # Damage past zero on the lethal blow. A build that overkills by 60% of a hit
        # is one balance change away from needing a whole extra swing, and that
        # cliff is invisible in TTK until it is crossed.
        .withColumn("overkill", F.col("total_damage") - F.col("target_hp"))
        .withColumn(
            "overkill_fraction",
            F.when(F.col("dmg_per_hit") > 0, F.col("overkill") / F.col("dmg_per_hit")),
        )
        # How many swings land inside the burst window at this attack speed. Constant
        # under any change that does not touch attack speed, which is what makes a
        # derived burst figure a scaling rather than a re-simulation.
        .withColumn(
            "hits_per_burst_window",
            F.when(
                F.col("swing_interval_ticks") > 0,
                F.floor((F.lit(BURST_WINDOW_TICKS) - 1) / F.col("swing_interval_ticks")) + 1,
            ),
        )
        # Every row from the sweep is an observation. Derived rows added later carry
        # the same schema and 'derived', and nothing downstream has to remember which
        # dataset it came from.
        .withColumn("provenance", F.lit("measured"))
        .withColumn("derived_skill", F.lit(None).cast("string"))
        .withColumn("derived_skill_level", F.lit(None).cast("int"))
        .withColumn("derived_bonus_per_hit", F.lit(None).cast("double"))
        .withColumn("derived_uptime", F.lit(None).cast("double"))
        .withColumn("source_result_id", F.lit(None).cast("long"))
    )

    auditor.expect_all(
        [
            not_empty(SILVER, "silver_result"),
            unique(SILVER, "silver_result", "result_id"),
            unique(SILVER, "silver_result", "build_id", "target_role", "target_armor"),
            non_null(SILVER, "silver_result", "result_id", "build_id", "target_role", "target_hp"),
            _dps_identity(cfg),
            _kill_accounting(),
            _burst_window_reconstruction(),
        ],
        df,
    )
    return df


def target_alias_bridge(results: DataFrame) -> DataFrame:
    """One row per (result, target role the result covers)."""
    return (
        results.select(
            "result_id",
            "run_id",
            "target_role",
            F.explode("target_role_alias_keys").alias("covered_target_role"),
        )
        .withColumn("is_measured", F.col("covered_target_role") == F.col("target_role"))
        .select("result_id", "run_id", "covered_target_role", "is_measured")
    )


def modifier_bridge(results: DataFrame) -> DataFrame:
    """The pipeline's own damage-modifier breakdown, one row per (result, modifier).

    This is the honest version of the stacked "damage contribution" bar the old SQL
    dashboards derived by hand: it is what the real `DamageEvent` reported, counted
    per hit, rather than a re-derivation of what it should have reported.
    """
    return (
        results.where(F.col("modifier_counts").isNotNull() & (F.size(F.map_keys("modifier_counts")) > 0))
        .select(
            "result_id",
            "run_id",
            "build_id",
            F.explode("modifier_counts").alias("modifier_name", "hit_count"),
        )
        .withColumn("hit_count", F.col("hit_count").cast("int"))
    )


# ---------------------------------------------------------------------------
# Expectations. Both of these test claims the engine's own comments make, which is
# the point: if a claim in DESIGN.md stops being true, the pipeline should say so
# rather than quietly compute on it.
# ---------------------------------------------------------------------------


def _dps_identity(cfg: Config) -> Check:
    """DuelOrchestrator: "dmg_per_hit * hits_to_kill / ttk_s == dps_sustained".

    Everything derived here -- the swing interval, and therefore every re-derived TTK
    -- rests on that identity holding. Checked only on rows that killed, since a
    timed-out matchup has no lethal hit to bound the window.
    """
    epsilon = float(cfg.quality.get("dps_identity_epsilon", 0.01))

    def fn(df: DataFrame) -> tuple[bool, float, str]:
        scope = df.where(
            (F.col("ttk_s") > 0) & F.col("dps_sustained").isNotNull() & F.col("dmg_per_hit").isNotNull()
        )
        total = scope.count()
        if total == 0:
            return True, 0, "no killing matchups to check"
        bad = scope.where(
            F.abs(F.col("dmg_per_hit") * F.col("hits_to_kill") / F.col("ttk_s") - F.col("dps_sustained"))
            > F.lit(epsilon)
        ).count()
        return bad == 0, bad, f"{bad} of {total} killing rows violate the identity at eps={epsilon}"

    return Check("dps_identity", SILVER, "silver_result", fn)


def _burst_window_reconstruction() -> Check:
    """hits_per_burst_window * dmg_per_hit == dps_burst, on rows that killed.

    The derived-skill model scales burst DPS by the per-hit damage on the strength of
    this identity, so it is worth stating rather than assuming. It holds exactly when
    every hit in the window did the same damage -- true on the deterministic equipment
    tiers, and not guaranteed once a build carries a ramp or a crit. Hence a warning:
    the number is how much of the run the derived burst figure can be trusted on.
    """

    def fn(df: DataFrame) -> tuple[bool, float, str]:
        scope = df.where(F.col("hits_per_burst_window").isNotNull() & F.col("dps_burst").isNotNull())
        total = scope.count()
        if total == 0:
            return True, 0.0, "no rows with a reconstructable burst window"
        ok = scope.where(
            F.abs(F.col("hits_per_burst_window") * F.col("dmg_per_hit") - F.col("dps_burst")) <= F.lit(0.01)
        ).count()
        share = ok / total
        return share >= 0.99, share, f"{ok} of {total} rows ({share:.2%}) reconstruct dps_burst exactly"

    return Check("burst_window_reconstruction", SILVER, "silver_result", fn, "warn")


def _kill_accounting() -> Check:
    """kills + attacker_deaths <= iterations. They do not have to sum to it -- a duel
    can time out with both alive -- but exceeding it would mean a duel was counted
    twice, which is the failure mode a resumed sweep could plausibly produce."""

    def fn(df: DataFrame) -> tuple[bool, float, str]:
        bad = df.where(
            F.coalesce(F.col("kills"), F.lit(0)) + F.coalesce(F.col("attacker_deaths"), F.lit(0))
            > F.coalesce(F.col("iterations"), F.lit(0))
        ).count()
        return bad == 0, bad, f"{bad} rows where kills + attacker_deaths exceeds iterations"

    return Check("kill_accounting", SILVER, "silver_result", fn)
