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

# What makes two rows comparable. Everything except the rune set: a rune's contribution
# is only meaningful against a row that differs from it in the rune alone, and the
# derived-skill columns are here so a Backstab row is compared against the
# Backstab-at-the-same-level baseline rather than against a bare one.
#
# `skill_set_key` is here for the same reason `booster` is part of `weapon_profile_key`,
# and it was added after the same expectation caught the same class of bug. On run 12 --
# the first SKILLS sweep -- the bare builds differ ONLY by the skill they carry, so
# without it 1,584 bare builds collapsed onto 28 distinct keys instead of 528, and
# `baseline_uniqueness` failed with 24,861 bare rows over 1,332 keys. The join it guards
# would have fanned out ~19x AND matched each rune against a different skill's baseline.
#
# Note this is the MEASURED loadout, which `derived_skill` is not: derived_skill names the
# skill a row was *modelled* for and is null on every measured row. They are different
# facts and both are needed.
BASELINE_KEY = [
    "run_id",
    "role",
    "weapon_profile_key",
    "weapon_roll",
    "target_role",
    "target_armor",
    "provenance",
    "skill_set_key",
    "derived_skill",
    "derived_skill_level",
]

# BASELINE_KEY minus the columns that identify what a row is being compared FOR rather
# than what makes it comparable. A skill's contribution is measured against a build with
# no skill, so the skill columns cannot be in the join or the baseline never matches --
# with `skill_set_key` in it, a skilled row ('frailty:3') would look for a baseline row
# carrying 'frailty:3' and no skills, which does not exist, and the mart would come out
# empty rather than wrong. Same argument the derived_* columns were already excluded on.
CONTRAST_KEY = [
    key for key in BASELINE_KEY
    if key not in {"provenance", "skill_set_key", "derived_skill", "derived_skill_level"}
]


# Prefix stamped onto the right-hand frame's shared columns before a self-join. See
# `_contrast_join` for why it has to exist at all.
_RIGHT = "__r_"


def _contrast_join(
    left: DataFrame,
    right: DataFrame,
    keys: list[str],
    how: str = "inner",
    carry: list[str] | None = None,
) -> tuple[DataFrame, DataFrame]:
    """Join a frame to a baseline derived from the SAME frame, without the two sides
    collapsing into each other. Returns `(joined, right)` -- the caller must read the
    right-hand side through the returned frame, not the one it passed in.

    Two things are going wrong at once here, and only one of them is obvious.

    NULLS. `derived_skill` is null on every measured row, and `a.col = b.col` is never
    true for two nulls, so a plain equality join drops every measured row and yields a
    mart that is empty rather than wrong. Hence `eqNullSafe` throughout.

    AMBIGUITY, which is the one that actually shipped. Every caller builds its baseline
    by FILTERING the same fact it is about to join to, so both sides of the join are the
    same logical plan and their columns carry the same expression ids. `right[key]` then
    resolves to the LEFT side's attribute: Spark says so out loud --

        WARN Column: Constructing trivially true equals predicate, 'role == role'.
                     Perhaps you need to use aliases.

    -- and that warning was printed once per key, on every run, for months. The join
    condition degenerates to `left.k <=> left.k`, which is trivially true, and column
    references chosen afterwards resolve to whichever side the analyzer picked.

    Honesty about what this did and did not fix: no published number moved. Spark's
    DeduplicateRelations was evidently rescuing these plans, and a row-by-row check of
    run 14 found all 4,680 `skill_contribution` rows already agreeing with the fact. This
    is a latent-hazard fix -- the analyzer was being trusted to disambiguate something the
    query had no business leaving ambiguous, and `run_diff` in particular coalesced
    `left[k]` against a `right[k]` that could resolve back to the left, which is how an
    `only_b` row loses its identity. It was NOT the cause of the near-zero measured skill
    deltas: those are real, and `skill_contribution` was right about them. Tormented Soil
    is an AXE skill and does nothing on the swords the skill-less baseline was swept with,
    so within a matched cell it correctly contributes zero.

    Aliasing the frames does not fix it (the ambiguity is in the expression ids, not the
    names), so the fix is structural: rename the right-hand frame's shared columns, which
    forces fresh expression ids through `Alias`. After this the two sides share no ids at
    all, there is nothing left to resolve ambiguously, and Spark stops warning.

    `carry` names columns that are shared but are NOT part of the condition -- they get the
    same rename so the caller can still tell the two sides apart when reading them back.
    """
    for key in keys + list(carry or []):
        right = right.withColumnRenamed(key, _RIGHT + key)

    condition: Column | None = None
    for key in keys:
        clause = left[key].eqNullSafe(right[_RIGHT + key])
        condition = clause if condition is None else (condition & clause)

    return left.join(right, condition, how), right


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
        "skill_set_key",
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
            # The measured loadout, and part of BASELINE_KEY. On an EQUIPMENT sweep this is
            # empty on every row and the key behaves exactly as it did before it existed.
            "skill_set_key",
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

    joined, baseline = _contrast_join(singles, baseline, BASELINE_KEY)

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
    set_joined, baseline = _contrast_join(sets, baseline, BASELINE_KEY)
    set_vs_base = (
        set_joined
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
    # `contributions` is rune_contribution's output, which descends from this same fact, so
    # the member-sum join is a self-join too and needs the same disambiguation.
    member_joined, _ = _contrast_join(exploded, singles, BASELINE_KEY + ["rune_key"], "left")
    summed = (
        member_joined
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

    join_key = CONTRAST_KEY + ["rune_set_key"]

    baseline = fact.where(
        (F.col("provenance") == "measured") & (F.col("skill_count") == 0)
    ).select(
        *join_key,
        F.col("dps_sustained").alias("base_dps"),
        F.col("ttk_s").alias("base_ttk_s"),
        F.col("dmg_per_hit").alias("base_dmg_per_hit"),
        F.col("hits_to_kill").alias("base_hits_to_kill"),
    )

    joined, baseline = _contrast_join(skilled, baseline, join_key)

    return (
        joined.select(
            *[skilled[key] for key in join_key],
            skilled["provenance"],
            skilled["weapon_key"],
            skilled["weapon_damage_applied"],
            skilled["weapon_attack_speed_applied"],
            skilled["swings_per_second"],
            skilled["build_id"],
            # A MEASURED skill names itself, which this mart used to make impossible.
            #
            # `skill_name` was `coalesce(derived_skill, '(measured build)')`, and derived_skill
            # is only ever set for a modelled skill -- so every skill the sweep actually fought
            # with collapsed into one placeholder bucket. Run 14 is the case that shows the
            # cost: it swept exactly two skills, and the mart reported a single
            # '(measured build)' row of 4,680 matchups averaging -0.006 DPS, with Tormented
            # Soil's amplification and Blood Barrier's silence averaged into each other and
            # neither recoverable. The mart named skill_contribution could not attribute a
            # contribution to a skill.
            #
            # skill_set_key is the identity, and it is already on the fact: builds.py writes it
            # as '<skill>:<allocated_level>' joined on '+'. It is deliberately NOT in
            # CONTRAST_KEY -- that is what lets a skilled row find a skill-less baseline -- but
            # nothing stopped carrying it into the output, which is the half that was missing.
            #
            # Split only for a single-slot build. A multi-skill build has several candidate
            # explanations for one delta and this mart cannot choose between them, so it says
            # so rather than naming the first slot and implying an attribution it has not made.
            F.coalesce(
                skilled["derived_skill"],
                F.when(skilled["skill_count"] == 1,
                       F.split(skilled["skill_set_key"], ":").getItem(0)),
                F.lit("(multi-skill build)"),
            ).alias("skill_name"),
            F.coalesce(
                skilled["derived_skill_level"],
                F.when(skilled["skill_count"] == 1,
                       F.split(skilled["skill_set_key"], ":").getItem(1).cast("int")),
            ).alias("skill_level"),
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


# What a diff considers to be "the same fight". This is the canonical identity from
# sql/gold_ddl.sql, not the build fingerprint, and the swap is the point of
# `canonical_identity` below.
#
# `realm` and `scenario` are in the key, which the fingerprint version did not have, and
# that changes what a cross-scenario diff reports. Deliberate: runs 12 and 13 enumerate an
# IDENTICAL build space and differ in nothing but scenario, and they land on a different
# measured band for 85 of 252 levels -- a MUTUAL defender fights back, so the same build
# against the same target is not the same fight. Diffing across scenarios used to produce
# deltas; it now produces no overlap, and the `diff_overlap` expectation warns about it.
# No overlap is the correct answer to a comparison that was never valid.
#
# `rune_set_key` is unfolded on purpose. Nothing in BASELINE claims two rune sets are
# equivalent, so folding them here would be inventing an equivalence rather than carrying
# one through.
CANONICAL_DIFF_KEY = [
    "realm",
    "scenario",
    "role",
    "canonical_weapon_key",
    "rune_set_key",
    "canonical_skill_key",
    "canonical_target_key",
    "provenance",
    "derived_skill",
    "canonical_derived_level",
]

# Carried across the join so a row that exists on one side only still says what it is.
# `role` and `rune_set_key` are NOT here: they moved into the key, a column cannot be
# both, and duplicating one is how the coalesce starts reading a column against itself.
# The target columns moved the other way -- canonical_target_key identifies the
# measurement, and target_role is what a reader wants to see on the row.
_DIFF_DESCRIPTORS = [
    "weapon_key",
    "skill_set_key",
    "target_role",
    "target_armor",
    "bands_known",
    "band_contested",
]


def canonical_identity(fact: DataFrame, canon_build: DataFrame, band_canon: DataFrame) -> DataFrame:
    """Attach the canonical fight identity to a matchup fact.

    BASELINE is FULL with three lossless reductions applied -- weapons deduplicated by
    stat profile, targets collapsed by health, skills limited to relevantSkills -- and each
    is a claim that two permutations are ONE measurement. All three run at enumeration,
    inside a single sweep. `sim_build.fingerprint` hashes the weapon KEY, the literal
    allocated levels and the target ROLE, none of which survives those reductions, so it is
    not canonical across sweeps and a diff joined on it compares spellings.

    The weapon and level folds are NOT recomputed here. They are read from
    `sim_gold_canonical_build`, the single definition of build identity: a second copy of an
    identity rule is how two consumers quietly stop agreeing about identity, and the level
    fold in particular is an election over every run in the database that Spark has no
    business re-running per diff.

    ONE rule is written twice, and it is named rather than hidden. The target fold is
    expressed below in Spark and again in sql/gold_ddl.sql on sim_gold_canonical_cell,
    because the SQL copy folds a cell of the standing baseline and this one folds a matchup
    row, and there is no shape both can read. `_canonical_target_key` states its three
    conditions; if the two drift, they disagree about how many cells exist, which the
    canonical-fold panel reports directly.
    """
    canon = canon_build.select(
        "build_id",
        "canonical_weapon_key",
        "canonical_skill_key",
        F.col("bands_known").alias("_c_bands_known"),
        F.col("band_contested").alias("_c_band_contested"),
    )

    # Derived rows carry their skill level in their own column rather than in
    # sim_build.skills -- they are modelled, not enumerated, so there is no build to have
    # banded -- and leaving them unbanded would key five spellings of one derived Backstab
    # against a banded measured one. Same election, applied to the other column.
    bands = band_canon.select(
        F.col("realm").alias("_b_realm"),
        F.col("scenario").alias("_b_scenario"),
        F.col("role").alias("_b_role"),
        F.col("skill").alias("_b_skill"),
        F.col("allocated_level").alias("_b_level"),
        F.col("canonical_level").alias("_b_canonical_level"),
    )

    joined = fact.join(canon, "build_id", "left").join(
        bands,
        F.col("realm").eqNullSafe(F.col("_b_realm"))
        & F.col("scenario").eqNullSafe(F.col("_b_scenario"))
        & F.col("role").eqNullSafe(F.col("_b_role"))
        & F.col("derived_skill").eqNullSafe(F.col("_b_skill"))
        & F.col("derived_skill_level").eqNullSafe(F.col("_b_level")),
        "left",
    )

    return (
        joined.withColumn("canonical_target_key", _canonical_target_key())
        # Falls back to the ALLOCATED level when no band was measured, which is the
        # conservative direction: an unbanded level stays its own row rather than being
        # folded onto a neighbour on no evidence.
        .withColumn(
            "canonical_derived_level",
            F.coalesce(F.col("_b_canonical_level"), F.col("derived_skill_level")),
        )
        .withColumn("bands_known", F.coalesce(F.col("_c_bands_known"), F.lit(True)))
        .withColumn("band_contested", F.coalesce(F.col("_c_band_contested"), F.lit(False)))
        .drop("_b_realm", "_b_scenario", "_b_role", "_b_skill", "_b_level", "_b_canonical_level")
        .drop("_c_bands_known", "_c_band_contested")
    )


def _canonical_target_key() -> Column:
    """The target folded to its health total, where BASELINE's premise for doing so holds.

    Straight from BalanceCatalog.collapseByDurability, and each of the three conditions
    alone would break the fold. Under MUTUAL the defender fights back and its role stops
    being a health total. Armour contributes stats beyond the HEALTH sum that `durability`
    adds up, so two sets agreeing on health could still differ elsewhere. A defender
    carrying skills has DefensiveSkill passives that fire on being hit.

    Where the premise does not hold the target keeps its own key, so widening the sweep
    un-folds this rather than quietly producing wrong rows.
    """
    return F.when(
        (F.col("target_armor") == F.lit("none"))
        & (F.coalesce(F.col("target_skill_count"), F.lit(0)) == F.lit(0))
        & (F.col("scenario") == F.lit("one_way")),
        # format_string, not concat. A bare concat renders 29 health as 'hp:29.0' where the
        # SQL copy of this rule renders 'hp:29.00', and two keys differing in the last
        # character join to nothing -- which is exactly what the first baseline diff did,
        # matching 0 rows between a baseline and one of its own contributing runs. Three
        # decimals on both sides.
        #
        # The target ROLE is appended whenever the attacker carries skills -- the SQL copy of
        # this rule in sql/gold_ddl.sql does the same, and the two must render byte-identical
        # strings or the diff matches nothing. A class sets health and the available skill
        # pool, so a skill-less unarmoured defender should be fully described by the health
        # already in this key; measured at source grain the sim is exactly that deterministic
        # for a fixed role (1,635,798 groups of (build, target_hp), zero damage variation).
        # Across roles it is not -- 1,029 of 86,568 multi-role groups differ in dmg_per_hit at
        # identical health with no armour and no defender skills. Only builds WITH skills
        # showed the resulting disagreement, so skill-less ones keep the fold.
        F.concat(
            F.format_string("hp:%.3f", F.col("target_hp")),
            F.when(
                F.col("canonical_skill_key").isNotNull(),
                # coalesce, because a concat with a null role yields a NULL KEY, and a null
                # key silently drops the row out of every join it was meant to take part in.
                F.concat(F.lit("@"), F.coalesce(F.col("target_role"), F.lit("?"))),
            ).otherwise(F.lit("")),
        ),
    ).otherwise(F.concat_ws("/", F.col("target_role"), F.col("target_armor")))


def run_diff(fact_a: DataFrame, fact_b: DataFrame) -> DataFrame:
    """Run A against run B, matched on canonical fight identity.

    Reports the three overlap classes separately rather than inner-joining and
    reporting the deltas. A build present in one run and not the other contributes no
    delta, and a large non-overlap means the deltas that remain are a biased sample --
    which is exactly the caveat an inner join deletes.

    Both sides must have been through `canonical_identity` first (or be the standing
    baseline, which is canonical by construction). Matching on `sim_build.fingerprint` --
    which this did until now -- makes a re-spelling indistinguishable from a
    disappearance, and those are opposite conclusions. The concrete case: the
    slot-agnostic weapon fold changes the fingerprint of every skill-less build, run 7
    enumerated five spellings of the 6.000 [5.000-7.000] profile and the next equipment
    sweep enumerates one, so a fingerprint diff of that pair reports four fifths of the
    weapon axis as "only in A" -- a scope change wearing the costume of a balance change,
    with nothing on the row to say so.

    Both fingerprints are still carried, one per side, and `spelling_changed` marks the
    rows that are the same fight under two names. That column is the audit of this change:
    on a pair of runs that enumerated identically it is false everywhere, and where it is
    true it names exactly the rows the old key would have double-counted.
    """
    key = CANONICAL_DIFF_KEY
    # What the row IS, as opposed to what it measured. Carried from both sides and
    # coalesced below, because this is a FULL OUTER join: taking them from the left alone
    # left every `only_b` row with a null role, weapon and rune set -- 4,312,263 of them on
    # the 1-vs-7 diff, every one an unidentifiable "run B added something". The overlap
    # classes are the point of this mart, so the class that exists only on one side has to
    # be the one that still describes itself.
    descriptors = _DIFF_DESCRIPTORS
    left = fact_a.select(
        *key,
        *descriptors,
        F.col("fingerprint").alias("fingerprint_a"),
        F.col("run_id").alias("run_a"),
        F.col("dps_sustained").alias("dps_a"),
        F.col("ttk_s").alias("ttk_a"),
        F.col("dmg_per_hit").alias("dmg_a"),
        F.col("kill_rate").alias("kill_rate_a"),
    )
    right = fact_b.select(
        *key,
        *descriptors,
        F.col("fingerprint").alias("fingerprint_b"),
        F.col("run_id").alias("run_b"),
        F.col("dps_sustained").alias("dps_b"),
        F.col("ttk_s").alias("ttk_b"),
        F.col("dmg_per_hit").alias("dmg_b"),
        F.col("kill_rate").alias("kill_rate_b"),
    )

    # Both sides carry `key` AND `descriptors` under the same names, and on a self-diff
    # (a run against itself, or two facts read from one frame) they would carry the same
    # expression ids too -- at which point the coalesces below silently read one side
    # twice and every `only_b` row loses its identity again. Disambiguate both lists.
    joined, right = _contrast_join(left, right, key, "full_outer", carry=descriptors)
    return (
        joined.select(
            *[F.coalesce(left[k], right[_RIGHT + k]).alias(k) for k in key],
            left["run_a"],
            right["run_b"],
            left["fingerprint_a"],
            right["fingerprint_b"],
            *[F.coalesce(left[k], right[_RIGHT + k]).alias(k) for k in descriptors],
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
        # The same fight under two names. Only meaningful on an overlapping row -- a row
        # present on one side has no second spelling to differ from -- so it is null
        # rather than false there, which keeps a COUNT of it honest.
        .withColumn(
            "spelling_changed",
            F.when(
                F.col("fingerprint_a").isNotNull() & F.col("fingerprint_b").isNotNull(),
                F.col("fingerprint_a") != F.col("fingerprint_b"),
            ),
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
