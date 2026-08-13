"""Silver -- the build dimension and its two bridges.

`sim_build` carries three collections in JSONB (`runes`, `skills`, `weapon_aliases`)
and the questions asked of them are all "per element". Keeping them as arrays means
every consumer writes its own unnest; silver unnests once.

The `weapon_aliases` bridge is the one that changes an answer rather than a query
plan. The reduced tiers deduplicate the weapon axis by stat profile, so a row keyed
`champions:alligators_tooth` is also the measurement for `champions:magnetic_maul`
and `champions:rake`. Without the bridge a dashboard filtered to `magnetic_maul`
returns nothing, and "never swept" and "swept under another key" are opposite
conclusions that look identical. With it, the filter resolves.
"""
from __future__ import annotations

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F

from ..bronze import ingest as bronze
from ..common.audit import Auditor, Check, non_null, not_empty, unique
from ..common.config import SILVER, Config
from . import schemas


def build(spark: SparkSession, cfg: Config, auditor: Auditor, run_id: int) -> DataFrame:
    raw = bronze.read(spark, cfg, "sim_build", run_id)

    runes = F.from_json(F.col("runes"), schemas.STRING_ARRAY)
    aliases = F.from_json(F.col("weapon_aliases"), schemas.STRING_ARRAY)
    skills = F.from_json(F.col("skills"), schemas.SKILLS)

    df = (
        raw.withColumn("rune_keys", F.coalesce(runes, F.array()))
        .withColumn("alias_keys", F.coalesce(aliases, F.array()))
        .withColumn("skill_slots", F.coalesce(skills, F.array()))
        .select(
            F.col("id").alias("build_id"),
            F.col("run_id"),
            F.col("role"),
            F.col("weapon").alias("weapon_key"),
            F.col("weapon_roll"),
            F.col("weapon_slot"),
            F.col("booster"),
            F.col("fingerprint"),
            F.col("points_spent"),
            F.col("weapon_damage_base").cast("double").alias("weapon_damage_base"),
            F.col("weapon_damage_min").cast("double").alias("weapon_damage_min"),
            F.col("weapon_damage_max").cast("double").alias("weapon_damage_max"),
            F.col("weapon_attack_speed_base").cast("double").alias("weapon_attack_speed_base"),
            F.col("weapon_attack_speed_min").cast("double").alias("weapon_attack_speed_min"),
            F.col("weapon_attack_speed_max").cast("double").alias("weapon_attack_speed_max"),
            # Which figure the duel was actually fought at. weapon_damage_base stops
            # being the answer the moment the roll axis exists (V20260805_1), and a
            # MIN row and a MAX row of one weapon are otherwise indistinguishable.
            F.when(F.col("weapon_roll") == "min", F.col("weapon_damage_min"))
            .when(F.col("weapon_roll") == "max", F.col("weapon_damage_max"))
            .otherwise(F.col("weapon_damage_base"))
            .cast("double")
            .alias("weapon_damage_applied"),
            F.when(F.col("weapon_roll") == "min", F.col("weapon_attack_speed_min"))
            .when(F.col("weapon_roll") == "max", F.col("weapon_attack_speed_max"))
            .otherwise(F.col("weapon_attack_speed_base"))
            .cast("double")
            .alias("weapon_attack_speed_applied"),
            F.col("rune_keys"),
            F.size("rune_keys").alias("rune_count"),
            # A stable identity for a rune *set*, order-independent, so the same four
            # runes socketed in a different order are one group and not several.
            # Empty rather than null for the bare baseline: it is a set, and it is the
            # row every rune delta is measured against, so it must join like one.
            F.array_join(F.array_sort(F.col("rune_keys")), "+").alias("rune_set_key"),
            F.col("alias_keys").alias("weapon_alias_keys"),
            F.size("alias_keys").alias("weapon_alias_count"),
            F.col("skill_slots"),
            F.size("skill_slots").alias("skill_count"),
            # A stable identity for a skill *loadout*, order-independent, the same shape
            # `rune_set_key` has and for the same reason: it is what makes two rows
            # comparable. Empty rather than null on a skill-less build, because that is the
            # row skill deltas are measured against and it has to join like a value.
            #
            # The level is part of the identity. A SKILLS sweep varies the same skill across
            # levels 1..5, and those are five different builds -- collapsing them would
            # reintroduce, one level down, exactly the fan-out this key exists to stop.
            #
            # `allocated_level` rather than `effective_level`: allocation is the axis the
            # sweep varies, and the difference between the two is the booster, which is
            # already part of `weapon_profile_key`. Keying on effective would fold the
            # booster into two independent columns and make a build that differs only by
            # booster look like a build that differs by skill level.
            F.array_join(
                F.array_sort(
                    F.transform(
                        F.col("skill_slots"),
                        lambda s: F.concat_ws(":", s["skill"], s["allocated_level"].cast("string")),
                    )
                ),
                "+",
            ).alias("skill_set_key"),
            F.col("_pipeline_run_id"),
        )
        # A build's stat identity, independent of which registry key stood in for it.
        # Two weapons that dedupe to one profile share this, which is what makes
        # "what does this damage tier buy" a GROUP BY rather than a join.
        #
        # `booster` is part of the identity and its omission was a bug the gold
        # baseline-uniqueness expectation caught on run 1: `core:standard_sword` and
        # `core:booster_sword` carry identical damage and attack speed in the same
        # slot, so without it they collapsed to one profile -- and then two bare rows
        # shared a baseline key and every rune delta measured against that profile
        # was counted twice. They are genuinely different builds, for the reason
        # V20260731_1 gives about the slot: a booster raises the effective skill level
        # by one, and `SkillListener.getLevel` reads that off the held weapon.
        .withColumn(
            "weapon_profile_key",
            F.concat_ws(
                "|",
                F.col("weapon_slot"),
                F.when(F.col("booster"), F.lit("booster")).otherwise(F.lit("plain")),
                F.format_number(F.col("weapon_damage_min"), 3),
                F.format_number(F.col("weapon_damage_base"), 3),
                F.format_number(F.col("weapon_damage_max"), 3),
                F.format_number(F.col("weapon_attack_speed_base"), 3),
            ),
        )
    )

    auditor.expect_all(
        [
            not_empty(SILVER, "silver_build"),
            unique(SILVER, "silver_build", "build_id"),
            unique(SILVER, "silver_build", "run_id", "fingerprint"),
            non_null(SILVER, "silver_build", "build_id", "role", "weapon_key", "weapon_roll"),
            _alias_includes_self(),
            _roll_is_in_envelope(),
        ],
        df,
    )
    return df


def rune_bridge(builds: DataFrame) -> DataFrame:
    """One row per (build, socketed rune)."""
    return builds.select(
        "build_id",
        "run_id",
        F.posexplode("rune_keys").alias("socket_ordinal", "rune_key"),
    ).withColumn("socket_ordinal", F.col("socket_ordinal").cast("int"))


def weapon_alias_bridge(builds: DataFrame) -> DataFrame:
    """One row per (build, weapon key the build's measurement covers).

    `is_measured` separates the key the duel actually equipped from the keys the
    dedupe folded into it. They are the same measurement, but only one of them was
    observed, and a reader who wants to exclude inferred coverage needs to be able
    to.
    """
    return (
        builds.select(
            "build_id",
            "run_id",
            "weapon_key",
            F.explode("weapon_alias_keys").alias("covered_weapon_key"),
        )
        .withColumn("is_measured", F.col("covered_weapon_key") == F.col("weapon_key"))
        .select("build_id", "run_id", "covered_weapon_key", "is_measured")
    )


def skill_bridge(builds: DataFrame) -> DataFrame:
    """One row per (build, filled skill slot). Empty for the equipment tiers, which
    carry no skills at all -- that is why Backstab has to be derived."""
    return (
        builds.select("build_id", "run_id", F.explode("skill_slots").alias("s"))
        .select(
            "build_id",
            "run_id",
            F.col("s.skill").alias("skill_name"),
            F.col("s.slot").alias("skill_slot"),
            F.col("s.allocated_level").alias("allocated_level"),
            F.col("s.effective_level").alias("effective_level"),
        )
        # DESIGN.md open question 11: effective levels are written after the fact, so
        # on a non-COMPLETED run they can equal the allocation because nothing updated
        # them rather than because no booster applied. Surfacing the difference as a
        # column keeps that readable without re-reading the run's status.
        .withColumn("level_boost", F.col("effective_level") - F.col("allocated_level"))
    )


# ---------------------------------------------------------------------------
# Expectations specific to the build dimension.
# ---------------------------------------------------------------------------


def _alias_includes_self() -> Check:
    """V20260731_1 states the invariant: a row's alias list is every weapon key it
    covers, *this row's weapon first*. If it stops holding, the alias bridge starts
    dropping the one weapon that was genuinely measured."""

    def fn(df: DataFrame) -> tuple[bool, float, str]:
        bad = df.where(
            (F.size("weapon_alias_keys") > 0)
            & ~F.array_contains(F.col("weapon_alias_keys"), F.col("weapon_key"))
        ).count()
        return bad == 0, bad, f"{bad} builds whose alias list omits their own weapon"

    return Check("alias_includes_self", SILVER, "silver_build", fn)


def _roll_is_in_envelope() -> Check:
    """min <= base <= max, per weapon.

    A warning rather than an error, because the first thing it caught was not a
    pipeline defect but a game one: run 4 measured `champions:wind_blade` at
    min=7.0, base=6.0, max=8.0 -- a min above the base, and neither figure matching
    the 5.0/6.0/7.0 the repo's `items/weapon.yml` declares. The sweep was taken
    against a dev server whose plugin data folder had drifted.

    Failing the layer over that would refuse to process a run whose other 275,000
    builds are fine. Reporting it, naming the weapons, and letting the run through is
    the useful behaviour -- and it is the reason this check exists at all, since
    `weapon_damage_applied` picks a corner of the envelope and an inverted envelope
    makes that pick meaningless without changing anything a downstream aggregate
    could notice.
    """

    def fn(df: DataFrame) -> tuple[bool, float, str]:
        broken = df.where(
            (F.col("weapon_damage_min") > F.col("weapon_damage_base"))
            | (F.col("weapon_damage_base") > F.col("weapon_damage_max"))
        )
        bad = broken.count()
        if bad == 0:
            return True, 0, "min <= base <= max holds for every build"
        weapons = [row["weapon_key"] for row in broken.select("weapon_key").distinct().limit(10).collect()]
        return False, bad, f"{bad} builds violate min<=base<=max, on {', '.join(sorted(weapons))}"

    return Check("weapon_roll_envelope", SILVER, "silver_build", fn, "warn")
