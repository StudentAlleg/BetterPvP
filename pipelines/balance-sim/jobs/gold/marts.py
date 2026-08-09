"""Gold -- the marts a dashboard is allowed to read.

The rule for this layer is that a panel gets a `SELECT ... WHERE run_id = $run`
with at most a `GROUP BY`. Anything harder than that -- and in particular anything
that joins a row to a *baseline* row of the same run -- happens here, once, in Spark.

That is the specific thing the old dashboards got wrong. "What is this rune worth"
is a self-join of the result table against its own no-rune row on five matching
columns, and written as a Grafana panel it is both slow and impossible to review.
Written here it is one shuffle, it runs once per sweep, and the join key is a
declared constant that the expectations check.

Six marts:

* `run`                -- one row per sweep, the header every other mart is filtered by.
* `matchup`            -- the wide fact: build x target, every axis flattened. Drill-down.
* `weapon_axis`        -- what a weapon profile is worth, across the rune sets it can hold.
* `rune_contribution`  -- what one rune is worth, against the bare row of the same weapon.
* `rune_set_synergy`   -- what a rune *set* is worth beyond the sum of its parts.
* `skill_contribution` -- what a skill is worth, measured or derived, per level.
* `effect_contribution` -- runes and skills in one shape, so both can be asked one question.
* `effect_interaction` -- whether an effect compounds or contends with the weapon carrying it.
"""
from __future__ import annotations

from pyspark.sql import Column, DataFrame
from pyspark.sql import functions as F
from pyspark.sql.window import Window

# What makes two rows comparable. Everything except the rune set and the skill: a
# rune's contribution is only meaningful against a row that differs from it in the
# rune alone, and the derived-skill columns are here so a Backstab row is compared
# against the Backstab-at-the-same-level baseline rather than against a bare one.
BASELINE_KEY = [
    "run_id",
    "role",
    "weapon_profile_key",
    "weapon_roll",
    "target_role",
    "target_armor",
    "provenance",
    "derived_skill",
    "derived_skill_level",
]


def _null_safe_join(left: DataFrame, right: DataFrame, keys: list[str]) -> Column:
    """`derived_skill` is null on every measured row, and `a.col = b.col` is never
    true for two nulls. Joining measured rows on it with plain equality silently
    produces zero matches -- a mart that is empty rather than wrong, which is the
    kind of bug that survives review."""
    condition = None
    for key in keys:
        clause = left[key].eqNullSafe(right[key])
        condition = clause if condition is None else (condition & clause)
    return condition


# ---------------------------------------------------------------------------


def matchup(results: DataFrame, builds: DataFrame, run: DataFrame) -> DataFrame:
    """The wide fact. One row per (build, target, provenance variant).

    Denormalised on purpose. Grafana's template variables filter on weapon, rune
    count, roll, role and target in every combination, and each of those being a
    join is how the previous generation of dashboards became too slow to use.
    """
    build_cols = builds.select(
        "build_id",
        "role",
        "weapon_key",
        "weapon_roll",
        "weapon_slot",
        "weapon_profile_key",
        "weapon_damage_applied",
        "weapon_damage_base",
        "weapon_damage_min",
        "weapon_damage_max",
        "weapon_attack_speed_applied",
        "booster",
        "fingerprint",
        "points_spent",
        "rune_keys",
        "rune_count",
        "rune_set_key",
        "weapon_alias_keys",
        "skill_count",
    )
    run_cols = run.select(
        "run_id", "realm", "scope", "scenario", "config_hash", "engine_version", "status", "is_complete"
    )

    return (
        results.join(build_cols, "build_id")
        .join(run_cols, "run_id")
        .select(
            "run_id",
            "realm",
            "scope",
            "scenario",
            "config_hash",
            "engine_version",
            "status",
            "is_complete",
            "result_id",
            "build_id",
            "fingerprint",
            "role",
            "weapon_key",
            "weapon_slot",
            "weapon_profile_key",
            "weapon_roll",
            "weapon_damage_applied",
            "weapon_damage_base",
            "weapon_damage_min",
            "weapon_damage_max",
            "weapon_attack_speed_applied",
            "booster",
            "points_spent",
            "skill_count",
            "rune_count",
            "rune_set_key",
            F.array_join("rune_keys", ", ").alias("runes"),
            F.array_join("weapon_alias_keys", ", ").alias("weapon_aliases"),
            "target_role",
            "target_armor",
            # The armour ladder beside the discriminator. `target_armor` stays the join
            # key everywhere -- BASELINE_KEY included -- because it is what identifies a
            # measurement; these two are for grouping across rungs, which is what a
            # "same combat time at every tier" comparison is.
            "target_armor_set",
            "target_armor_tier",
            "target_hp",
            "target_skill_count",
            "dmg_per_hit",
            "dps_sustained",
            "dps_burst",
            "dps_p50",
            "dps_p90",
            "ttk_s",
            "ttk_ticks_mean",
            "hits_to_kill",
            "total_damage",
            "overkill",
            "overkill_fraction",
            "swing_interval_ticks",
            "kill_rate",
            "kills",
            "attacker_deaths",
            "iterations",
            "dmg_per_hit_stddev",
            "energy_limited",
            "energy_spent_per_duel",
            "provenance",
            "derived_skill",
            "derived_skill_level",
            "derived_bonus_per_hit",
            "derived_uptime",
            "source_result_id",
            # measured / carried / unknown. A `--changed` sweep is a whole run made of
            # rows of two ages, and this is the only column that says which is which --
            # so a panel showing a suspicious number can be asked whether the duels
            # behind it were actually run this time.
            "freshness",
            "measured_run_id",
            "config_scope_hash",
        )
        # Effective swings per second, which is the axis "attack speed" actually
        # means once DelayData is tick-quantised. Reported rather than left implicit
        # because two weapons of equal DPS at different swing rates are different
        # weapons to a player and identical ones to a DPS column.
        .withColumn(
            "swings_per_second",
            F.when(F.col("swing_interval_ticks") > 0, F.lit(20.0) / F.col("swing_interval_ticks")),
        )
    )


def weapon_axis(fact: DataFrame) -> DataFrame:
    """What a weapon profile is worth, summarised across every rune set it carried.

    The spread columns are the point. A profile whose DPS barely moves between its
    bare row and its best rune set is a weapon runes do not help; one with a wide
    spread is a weapon whose balance is really the runes' balance. Neither is
    visible in a mean.
    """
    return (
        fact.groupBy(
            "run_id",
            "role",
            "weapon_profile_key",
            "weapon_slot",
            "weapon_roll",
            "target_role",
            "target_armor",
            "provenance",
            "derived_skill",
            "derived_skill_level",
        )
        .agg(
            # min rather than first: `first` over a group has no defined order, so the
            # label on a profile would change between runs of the same data and a patch
            # diff would show a weapon renaming itself.
            F.min("weapon_key").alias("representative_weapon"),
            F.countDistinct("weapon_key").alias("weapon_keys_measured"),
            # Constant within a profile by construction -- the profile key is built from
            # these figures -- so any aggregate picks the same value, and min keeps it
            # deterministic if that ever stops being true.
            F.min("weapon_damage_applied").alias("weapon_damage_applied"),
            F.min("weapon_attack_speed_applied").alias("weapon_attack_speed_applied"),
            F.count("*").alias("matchups"),
            F.countDistinct("rune_set_key").alias("rune_sets"),
            F.min("dps_sustained").alias("dps_min"),
            F.avg("dps_sustained").alias("dps_mean"),
            F.expr("percentile_approx(dps_sustained, 0.5)").alias("dps_p50"),
            F.max("dps_sustained").alias("dps_max"),
            F.min("ttk_s").alias("ttk_min"),
            F.avg("ttk_s").alias("ttk_mean"),
            F.max("ttk_s").alias("ttk_max"),
            F.avg("hits_to_kill").alias("hits_to_kill_mean"),
            F.min("hits_to_kill").alias("hits_to_kill_min"),
            F.max("hits_to_kill").alias("hits_to_kill_max"),
            F.avg("kill_rate").alias("kill_rate_mean"),
        )
        .withColumn("dps_spread", F.col("dps_max") - F.col("dps_min"))
        .withColumn(
            "dps_spread_pct",
            F.when(F.col("dps_min") > 0, (F.col("dps_max") - F.col("dps_min")) / F.col("dps_min")),
        )
    )


def rune_contribution(fact: DataFrame) -> DataFrame:
    """Marginal value of a single rune, against the bare row of the same everything.

    Only `rune_count == 1` rows are attributed. A build carrying several runes cannot
    be decomposed afterwards -- that is DESIGN.md's stated reason for giving runes
    their own tier -- so attributing a four-rune row to its four runes would be
    inventing a decomposition. Sets get their own mart below, as sets.
    """
    singles = fact.where(F.col("rune_count") == 1).withColumn(
        "rune_key", F.element_at(F.split(F.col("rune_set_key"), r"\+"), 1)
    )
    baseline = fact.where(F.col("rune_count") == 0).select(
        *BASELINE_KEY,
        F.col("dps_sustained").alias("base_dps"),
        F.col("dps_burst").alias("base_dps_burst"),
        F.col("ttk_s").alias("base_ttk_s"),
        F.col("dmg_per_hit").alias("base_dmg_per_hit"),
        F.col("hits_to_kill").alias("base_hits_to_kill"),
        F.col("kill_rate").alias("base_kill_rate"),
    )

    joined = singles.join(baseline, _null_safe_join(singles, baseline, BASELINE_KEY), "inner")

    return joined.select(
        *[singles[key] for key in BASELINE_KEY],
        singles["weapon_key"],
        singles["weapon_damage_applied"],
        # The carrier's speed, so "is this effect worth more on a fast weapon" is
        # answerable without joining back to the fact. Declared rather than measured:
        # swings_per_second is null on a row that never killed, and an effect's
        # interaction is least visible exactly where the fight did not resolve.
        singles["weapon_attack_speed_applied"],
        singles["swings_per_second"],
        singles["rune_key"],
        singles["build_id"],
        singles["dps_sustained"],
        singles["dps_burst"],
        singles["ttk_s"],
        singles["dmg_per_hit"],
        singles["hits_to_kill"],
        singles["kill_rate"],
        baseline["base_dps"],
        baseline["base_dps_burst"],
        baseline["base_ttk_s"],
        baseline["base_dmg_per_hit"],
        baseline["base_hits_to_kill"],
        baseline["base_kill_rate"],
    ).select(
        "*",
        (F.col("dps_sustained") - F.col("base_dps")).alias("dps_delta"),
        F.when(
            F.col("base_dps") > 0, (F.col("dps_sustained") - F.col("base_dps")) / F.col("base_dps")
        ).alias("dps_delta_pct"),
        (F.col("ttk_s") - F.col("base_ttk_s")).alias("ttk_delta"),
        (F.col("dmg_per_hit") - F.col("base_dmg_per_hit")).alias("dmg_per_hit_delta"),
        (F.col("base_hits_to_kill") - F.col("hits_to_kill")).alias("hits_saved"),
    )


def rune_set_synergy(fact: DataFrame, contributions: DataFrame) -> DataFrame:
    """Whether a rune set is worth more or less than its runes measured separately.

    This is the question neither the sweep nor the old dashboards could answer, and
    the reason it is worth a mart: the sweep measures sets and measures singles, but
    the comparison between them is a join it does not perform. A positive
    `synergy_dps` means the set compounds; a negative one means the runes contend
    for the same term in the damage pipeline and stacking them is a trap.

    `runes_attributed` is load-bearing. A set whose members were not all measured
    individually has an incomplete sum, and the difference would read as synergy.
    """
    sets = fact.where(F.col("rune_count") > 1)
    baseline = fact.where(F.col("rune_count") == 0).select(
        *BASELINE_KEY, F.col("dps_sustained").alias("base_dps"), F.col("ttk_s").alias("base_ttk_s")
    )
    set_vs_base = (
        sets.join(baseline, _null_safe_join(sets, baseline, BASELINE_KEY), "inner")
        .select(
            *[sets[key] for key in BASELINE_KEY],
            # The grain is one row per (build, target), not per build: a build is
            # measured against every target in the sweep. Grouping the member sum by
            # build_id alone therefore folds every target together and reports a
            # 2-rune set as having 30 members attributed -- which is what it did
            # before result_id became the key here.
            sets["result_id"],
            sets["build_id"],
            sets["weapon_key"],
            sets["rune_set_key"],
            sets["rune_count"],
            sets["dps_sustained"],
            sets["ttk_s"],
            baseline["base_dps"],
            baseline["base_ttk_s"],
        )
        .withColumn("set_dps_delta", F.col("dps_sustained") - F.col("base_dps"))
        .withColumn("set_ttk_delta", F.col("ttk_s") - F.col("base_ttk_s"))
    )

    exploded = set_vs_base.select(
        *BASELINE_KEY,
        "result_id",
        F.explode(F.split(F.col("rune_set_key"), r"\+")).alias("rune_key"),
    )
    singles = contributions.select(*BASELINE_KEY, "rune_key", "dps_delta")
    summed = (
        exploded.join(
            singles,
            _null_safe_join(exploded, singles, BASELINE_KEY) & (exploded["rune_key"] == singles["rune_key"]),
            "left",
        )
        .groupBy(exploded["result_id"])
        .agg(
            F.sum("dps_delta").alias("sum_individual_dps_delta"),
            F.count("dps_delta").alias("runes_attributed"),
        )
    )

    return (
        set_vs_base.join(summed, "result_id", "left")
        .withColumn(
            "synergy_dps", F.col("set_dps_delta") - F.coalesce(F.col("sum_individual_dps_delta"), F.lit(0.0))
        )
        .withColumn("runes_attributed", F.coalesce(F.col("runes_attributed"), F.lit(0)))
        .withColumn("attribution_complete", F.col("runes_attributed") == F.col("rune_count"))
    )


def skill_contribution(fact: DataFrame) -> DataFrame:
    """What a skill is worth per level, measured or derived, against the same
    equipment carrying no skill.

    One mart for both provenances on purpose. The whole reason Backstab is modelled
    rather than dropped is so it can sit in the same table as the skills the sweep
    did measure -- and the `provenance` column is what stops that being a lie.
    """
    skilled = fact.where(F.col("derived_skill").isNotNull() | (F.col("skill_count") > 0))
    if not skilled.take(1):
        return skilled.select(
            *BASELINE_KEY, "rune_set_key", F.lit(None).cast("double").alias("dps_delta")
        ).limit(0)

    base_key = [k for k in BASELINE_KEY if k not in {"provenance", "derived_skill", "derived_skill_level"}]
    join_key = base_key + ["rune_set_key"]

    baseline = fact.where(
        (F.col("provenance") == "measured") & (F.col("skill_count") == 0)
    ).select(
        *join_key,
        F.col("dps_sustained").alias("base_dps"),
        F.col("ttk_s").alias("base_ttk_s"),
        F.col("dmg_per_hit").alias("base_dmg_per_hit"),
        F.col("hits_to_kill").alias("base_hits_to_kill"),
    )

    joined = skilled.join(baseline, _null_safe_join(skilled, baseline, join_key), "inner")

    return (
        joined.select(
            *[skilled[key] for key in join_key],
            skilled["provenance"],
            skilled["weapon_key"],
            skilled["weapon_damage_applied"],
            skilled["weapon_attack_speed_applied"],
            skilled["swings_per_second"],
            skilled["build_id"],
            F.coalesce(skilled["derived_skill"], F.lit("(measured build)")).alias("skill_name"),
            skilled["derived_skill_level"].alias("skill_level"),
            skilled["derived_bonus_per_hit"],
            skilled["derived_uptime"],
            skilled["dps_sustained"],
            skilled["ttk_s"],
            skilled["dmg_per_hit"],
            skilled["hits_to_kill"],
            baseline["base_dps"],
            baseline["base_ttk_s"],
            baseline["base_dmg_per_hit"],
            baseline["base_hits_to_kill"],
        )
        .withColumn("dps_delta", F.col("dps_sustained") - F.col("base_dps"))
        .withColumn(
            "dps_delta_pct",
            F.when(F.col("base_dps") > 0, (F.col("dps_sustained") - F.col("base_dps")) / F.col("base_dps")),
        )
        .withColumn("ttk_delta", F.col("ttk_s") - F.col("base_ttk_s"))
        .withColumn("hits_saved", F.col("base_hits_to_kill") - F.col("hits_to_kill"))
    )


def effect_contribution(runes: DataFrame, skills: DataFrame) -> DataFrame:
    """Runes and skills in one shape, so an interaction question can be asked once.

    A rune and a skill are different things to equip and the same thing to measure:
    each is something a build carries, each has a marginal DPS delta against an
    otherwise identical build without it, and each is a candidate for compounding or
    contending with the weapon underneath it. Keeping them in separate marts means
    "which of our effects scale with attack speed" has to be asked twice and the two
    answers compared by eye.

    `effect_kind` keeps them distinguishable, because they are not interchangeable as
    design levers -- a rune is a drop and a skill is twelve points -- and an average
    taken across both would be an average over two different currencies.
    """
    rune_rows = runes.select(
        "run_id",
        "role",
        "weapon_profile_key",
        "weapon_key",
        "weapon_roll",
        "weapon_damage_applied",
        "weapon_attack_speed_applied",
        "swings_per_second",
        "target_role",
        "target_armor",
        "provenance",
        F.lit("rune").alias("effect_kind"),
        F.col("rune_key").alias("effect_key"),
        F.lit(None).cast("int").alias("effect_level"),
        "dps_delta",
        "dps_delta_pct",
        "ttk_delta",
        "hits_saved",
    )
    skill_rows = skills.select(
        "run_id",
        "role",
        "weapon_profile_key",
        "weapon_key",
        "weapon_roll",
        "weapon_damage_applied",
        "weapon_attack_speed_applied",
        "swings_per_second",
        "target_role",
        "target_armor",
        "provenance",
        F.lit("skill").alias("effect_kind"),
        F.col("skill_name").alias("effect_key"),
        F.col("skill_level").cast("int").alias("effect_level"),
        "dps_delta",
        "dps_delta_pct",
        "ttk_delta",
        "hits_saved",
    )
    return rune_rows.unionByName(skill_rows)


def effect_interaction(effects: DataFrame) -> DataFrame:
    """Whether an effect is worth more on a fast weapon, a hard-hitting one, or neither.

    This is the "do certain things not mix well together" question, and the shape it
    takes here follows from where attack speed actually varies. Within one weapon it
    barely does: `SimStatRoll` moves every stat to the same corner at once, so a MIN
    row is a slower *and* weaker weapon and the two cannot be separated. Across weapons
    it varies a lot. So the axis is the carrier weapon, and the question is whether an
    effect's marginal value tracks how fast that weapon swings.

    An on-hit effect fires once per swing, so its DPS contribution should rise with
    swings per second -- a positive `speed_corr` is that effect compounding with attack
    speed. A flat percentage buff should be indifferent to swing rate and track weapon
    damage instead. An effect that is *negatively* correlated with speed is contending
    with the weapon for the same term in the damage pipeline, which is the trap worth
    finding: it is a combination the item screen encourages and the numbers punish.

    **Both correlations are reported, and reading only one of them is a mistake.**
    Fast weapons are not a random sample of weapons -- they hit softer, because that is
    how weapons are balanced -- so speed and damage are themselves correlated across
    the catalog, and an effect that really tracks damage will show a spurious negative
    speed correlation. `damage_corr` beside it is what separates the two, and neither
    number means anything on its own.

    `weapons` is the guard `attribution_complete` is on the synergy mart: a correlation
    over two weapons is not a finding, and without the count on the row nothing says so.
    """
    grouped = effects.groupBy(
        "run_id", "effect_kind", "effect_key", "effect_level", "provenance", "target_role", "target_armor"
    ).agg(
        F.count("*").alias("matchups"),
        F.countDistinct("weapon_profile_key").alias("weapons"),
        F.avg("dps_delta").alias("dps_delta_mean"),
        F.avg("dps_delta_pct").alias("dps_delta_pct_mean"),
        F.avg("ttk_delta").alias("ttk_delta_mean"),
        F.avg("hits_saved").alias("hits_saved_mean"),
        # The two axes an effect can be tracking, assembled from their parts rather
        # than taken from F.corr. Under ANSI mode corr raises DIVIDE_BY_ZERO on a group
        # whose carrier never varied -- which is not an edge case here but the normal
        # state of a MELEE sweep, where there is one weapon and the correct answer is
        # "unknown". covar_samp and stddev_samp are defined on a constant column (both
        # zero), so the division can be guarded explicitly below.
        F.covar_samp("dps_delta", "weapon_attack_speed_applied").alias("_cov_speed"),
        F.covar_samp("dps_delta", "weapon_damage_applied").alias("_cov_damage"),
        F.stddev_samp("dps_delta").alias("_sd_delta"),
        F.stddev_samp("weapon_attack_speed_applied").alias("_sd_speed"),
        F.stddev_samp("weapon_damage_applied").alias("_sd_damage"),
        F.min("weapon_attack_speed_applied").alias("slowest_carrier"),
        F.max("weapon_attack_speed_applied").alias("fastest_carrier"),
    )

    def correlation(covariance: str, spread: str) -> Column:
        """Pearson's r, null wherever it is undefined rather than zero or an error.

        A zero spread on either side means the group has nothing to correlate: one
        weapon, or an effect worth exactly the same everywhere. Null says that; 0.0
        would claim the axes were measured and found unrelated, which is a different
        and much stronger statement.
        """
        return F.when(
            (F.col(spread) > 0) & (F.col("_sd_delta") > 0),
            F.col(covariance) / (F.col(spread) * F.col("_sd_delta")),
        )

    return (
        grouped.withColumn("speed_corr", correlation("_cov_speed", "_sd_speed"))
        .withColumn("damage_corr", correlation("_cov_damage", "_sd_damage"))
        .drop("_cov_speed", "_cov_damage", "_sd_delta", "_sd_speed", "_sd_damage")
        # A correlation over one or two weapons is noise wearing a number's clothes.
        # Kept rather than filtered, and labelled, for the reason the synergy mart keeps
        # its incomplete attributions: a missing row and an unreliable row look identical
        # once something drops one of them.
        .withColumn("correlation_reliable", F.col("weapons") >= 3)
        .withColumn(
            "interaction",
            F.when(~(F.col("weapons") >= 3), F.lit("too few weapons"))
            .when(F.col("speed_corr").isNull(), F.lit("speed did not vary"))
            # The threshold is a reporting convention, not a significance test. It exists
            # so a panel can sort by something other than a raw coefficient; the columns
            # it is derived from are on the row for anyone who wants to disagree with it.
            .when(F.col("speed_corr") > 0.3, F.lit("compounds with attack speed"))
            .when(F.col("speed_corr") < -0.3, F.lit("contends with attack speed"))
            .otherwise(F.lit("indifferent to attack speed")),
        )
        # Which axis the effect tracks more strongly. An effect whose damage correlation
        # dominates is not an attack-speed interaction at all, however its speed_corr
        # reads -- that is the confound above, named on the row so it cannot be missed.
        .withColumn(
            "tracks",
            F.when(F.col("speed_corr").isNull() | F.col("damage_corr").isNull(), F.lit("unknown"))
            .when(F.abs(F.col("speed_corr")) > F.abs(F.col("damage_corr")), F.lit("attack speed"))
            .otherwise(F.lit("weapon damage")),
        )
    )


def run_diff(fact_a: DataFrame, fact_b: DataFrame) -> DataFrame:
    """Run A against run B, matched on the build fingerprint and the target.

    Reports the three overlap classes separately rather than inner-joining and
    reporting the deltas. A build present in one run and not the other contributes no
    delta, and a large non-overlap means the deltas that remain are a biased sample --
    which is exactly the caveat an inner join deletes.
    """
    key = ["fingerprint", "target_role", "target_armor", "provenance", "derived_skill", "derived_skill_level"]
    left = fact_a.select(
        *key,
        F.col("run_id").alias("run_a"),
        F.col("role"),
        F.col("weapon_key"),
        F.col("rune_set_key"),
        F.col("dps_sustained").alias("dps_a"),
        F.col("ttk_s").alias("ttk_a"),
        F.col("dmg_per_hit").alias("dmg_a"),
        F.col("kill_rate").alias("kill_rate_a"),
    )
    right = fact_b.select(
        *key,
        F.col("run_id").alias("run_b"),
        F.col("dps_sustained").alias("dps_b"),
        F.col("ttk_s").alias("ttk_b"),
        F.col("dmg_per_hit").alias("dmg_b"),
        F.col("kill_rate").alias("kill_rate_b"),
    )

    joined = left.join(right, _null_safe_join(left, right, key), "full_outer")
    return (
        joined.select(
            *[F.coalesce(left[k], right[k]).alias(k) for k in key],
            left["run_a"],
            right["run_b"],
            left["role"],
            left["weapon_key"],
            left["rune_set_key"],
            left["dps_a"],
            right["dps_b"],
            left["ttk_a"],
            right["ttk_b"],
            left["dmg_a"],
            right["dmg_b"],
            left["kill_rate_a"],
            right["kill_rate_b"],
        )
        .withColumn(
            "overlap",
            F.when(left["run_a"].isNull(), F.lit("only_b"))
            .when(right["run_b"].isNull(), F.lit("only_a"))
            .otherwise(F.lit("both")),
        )
        .withColumn("dps_delta", F.col("dps_b") - F.col("dps_a"))
        .withColumn(
            "dps_delta_pct", F.when(F.col("dps_a") > 0, (F.col("dps_b") - F.col("dps_a")) / F.col("dps_a"))
        )
        .withColumn("ttk_delta", F.col("ttk_b") - F.col("ttk_a"))
        .withColumn(
            "rank_by_abs_dps_delta",
            F.row_number().over(Window.orderBy(F.abs(F.col("dps_delta")).desc_nulls_last())),
        )
    )
