"""Silver -- skills the sweep structurally cannot measure, modelled here instead.

Backstab is the case that motivates the layer. It is an assassin passive that adds a
flat amount to a melee hit, but only when the attacker and the target face within 60
degrees of each other -- i.e. from behind. The orchestrator's duels are head-on, so
the condition never holds and the skill contributes exactly zero to every row in the
sweep. Measuring it would need a positioning axis the engine does not have.

The honest alternative is not to leave it out. It is to model it *here*, where the
model is one function in version control that anyone can read, rather than in a
dashboard's SQL where DESIGN.md §1 says the last generation of these went to die.
Three properties make that safe:

* **Provenance is a column.** Every row this module emits carries
  `provenance='derived'`, the skill, the level, the bonus, and the assumed uptime.
  Nothing downstream can average a modelled figure into a measured one by accident,
  and the assumption travels with the number instead of living in a comment.
* **The parameters come from the game's own config**, read out of the
  `grafana_config` mirror that bronze snapshotted, not from constants typed here.
  A balance change to `skills.assassin.backstab.baseDamage` moves these rows.
* **The model is closed-form only where the closed form is exact.** A flat per-hit
  addition changes damage per hit and nothing else -- not attack speed, not the
  number of swings in a burst window -- so TTK follows from the same arithmetic the
  engine used. Rows where that arithmetic does not reproduce the measured
  `hits_to_kill` are excluded rather than approximated, and the exclusion is counted.

What this cannot model is uptime. `uptime=1.0` means "every hit lands from behind",
which is the upper bound. It is a parameter, it is on every row, and no gold mart
presents a derived figure without it.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass

from pyspark.sql import DataFrame
from pyspark.sql import functions as F

from ..common.audit import Auditor, Check
from ..common.config import SILVER
from .dimensions import skill_param
from .results import BURST_WINDOW_TICKS, TICKS_PER_SECOND

LOG = logging.getLogger(__name__)


@dataclass(frozen=True)
class FlatPerHitSkill:
    """A skill whose whole effect is `+x` damage on every qualifying melee hit.

    Backstab is one; it is written as a shape rather than as a special case because
    several passives in champions have exactly this form and the next one to need
    deriving should be a config entry, not another module.
    """

    name: str
    role: str  # the Role the skill belongs to; only builds of that role get rows
    base_damage: float
    increase_per_level: float
    max_level: int
    uptime: float
    # Recorded so a reader can tell a value read from the config mirror from one that
    # fell back to conf/pipeline.yml because the mirror had no row.
    params_from_config: bool

    def bonus(self, level: int) -> float:
        return (self.base_damage + (level - 1) * self.increase_per_level) * self.uptime


def backstab_spec(cfg_section: dict, config_params: DataFrame) -> FlatPerHitSkill | None:
    """Resolve Backstab's parameters from the config mirror, falling back to the
    pipeline config only where the mirror is silent.

    Note the live YAML carries both `increasePerLevel` (which `Backstab.java` reads)
    and a stale `damageIncreasePerLevel` (which nothing reads). The name used here is
    the one in the code, deliberately -- picking the other would produce a plausible
    number that is wrong, which is the worst available outcome.
    """
    if not cfg_section.get("enabled", True):
        return None

    role = cfg_section.get("role", "ASSASSIN")
    base = skill_param(config_params, role, "backstab", "baseDamage", float("nan"))
    step = skill_param(config_params, role, "backstab", "increasePerLevel", float("nan"))
    cap = skill_param(config_params, role, "backstab", "maxlevel", float("nan"))

    from_config = not any(v != v for v in (base, step, cap))  # NaN check
    if not from_config:
        LOG.warning("backstab: config mirror incomplete, using pipeline fallbacks")
        base = cfg_section["fallback_base_damage"]
        step = cfg_section["fallback_increase_per_level"]
        cap = cfg_section["fallback_max_level"]

    return FlatPerHitSkill(
        name="Backstab",
        role=role,
        base_damage=float(base),
        increase_per_level=float(step),
        max_level=int(cap),
        uptime=float(cfg_section.get("uptime", 1.0)),
        params_from_config=from_config,
    )


def apply_flat_per_hit(
    results: DataFrame,
    builds: DataFrame,
    skill: FlatPerHitSkill,
    auditor: Auditor,
) -> DataFrame:
    """Emit one derived matchup row per (eligible measured row x skill level).

    The eligible set is deliberately narrow:

    * the build's role is the skill's role -- Backstab is assassin-only, and the
      class guard is part of the game rather than a filter chosen here;
    * the matchup killed, so there is a lethal-hit-bounded window to re-derive
      against. A timed-out matchup has no swing interval and modelling one would be
      inventing the very thing the row failed to establish;
    * the measured row satisfies `ceil(hp / dmg) == hits_to_kill`. Where it does not,
      something other than a constant per-hit amount decided the fight -- a ramp, a
      damage-over-time, a mitigation that varies with health -- and adding a flat
      term to the average is not a model of it.
    """
    eligible = (
        results.where(F.col("provenance") == "measured")
        .join(builds.select("build_id", "role"), "build_id")
        .where(F.col("role") == skill.role)
        .where(
            F.col("ttk_s").isNotNull()
            & (F.col("ttk_s") > 0)
            & F.col("swing_interval_ticks").isNotNull()
            & (F.col("dmg_per_hit") > 0)
        )
    )

    modellable = eligible.withColumn(
        "model_reproduces_source",
        F.abs(F.ceil(F.col("target_hp") / F.col("dmg_per_hit")) - F.col("hits_to_kill")) < F.lit(1e-9),
    )
    auditor.expect(_model_coverage(skill), modellable)

    base = modellable.where(F.col("model_reproduces_source")).drop(
        "model_reproduces_source", "role"
    )

    levels = F.array(*[F.lit(level) for level in range(1, skill.max_level + 1)])
    bonuses = F.array(*[F.lit(skill.bonus(level)) for level in range(1, skill.max_level + 1)])

    derived = (
        base.withColumn("lvl", F.explode(levels))
        .withColumn("derived_bonus_per_hit", F.element_at(bonuses, F.col("lvl")))
        # -- the model ---------------------------------------------------
        .withColumn("new_dmg_per_hit", F.col("dmg_per_hit") + F.col("derived_bonus_per_hit"))
        .withColumn("new_hits_to_kill", F.ceil(F.col("target_hp") / F.col("new_dmg_per_hit")).cast("double"))
        .withColumn(
            "new_ttk_ticks",
            (F.col("new_hits_to_kill") - F.lit(1.0)) * F.col("swing_interval_ticks"),
        )
        .withColumn("new_ttk_s", F.col("new_ttk_ticks") / F.lit(TICKS_PER_SECOND))
        .withColumn("new_total_damage", F.col("new_hits_to_kill") * F.col("new_dmg_per_hit"))
        .withColumn(
            "new_dps_sustained",
            # A one-hit kill has a zero-length window and no sustained rate to quote.
            # The burst figure is the whole of what happened, and reporting it as an
            # infinite DPS instead would poison every aggregate it lands in.
            F.when(F.col("new_ttk_s") > 0, F.col("new_total_damage") / F.col("new_ttk_s")),
        )
        .withColumn(
            "new_dps_burst",
            # The swing rate is untouched, so the same number of hits lands in the
            # burst window and the window's damage scales exactly with per-hit damage.
            F.col("hits_per_burst_window")
            * F.col("new_dmg_per_hit")
            / (F.lit(BURST_WINDOW_TICKS) / F.lit(TICKS_PER_SECOND)),
        )
    )

    return (
        derived.withColumn("source_result_id", F.col("result_id"))
        # Derived rows get an id outside the source table's space so the two can be
        # unioned and still keyed. Negative, and offset by level, because a derived
        # row is not a row anyone can look up in Postgres and an id that looks like
        # one invites exactly that mistake.
        .withColumn("result_id", -(F.col("result_id") * F.lit(100) + F.col("lvl")))
        .withColumn("dmg_per_hit", F.col("new_dmg_per_hit"))
        .withColumn("hits_to_kill", F.col("new_hits_to_kill"))
        .withColumn("ttk_s", F.col("new_ttk_s"))
        .withColumn("ttk_ticks_mean", F.col("new_ttk_ticks"))
        .withColumn("ttk_ticks_p50", F.col("new_ttk_ticks"))
        .withColumn("ttk_ticks_p90", F.col("new_ttk_ticks"))
        .withColumn("total_damage", F.col("new_total_damage"))
        .withColumn("dps_sustained", F.col("new_dps_sustained"))
        .withColumn("dps_p50", F.col("new_dps_sustained"))
        .withColumn("dps_p90", F.col("new_dps_sustained"))
        .withColumn("dps_burst", F.col("new_dps_burst"))
        .withColumn("overkill", F.col("total_damage") - F.col("target_hp"))
        .withColumn("overkill_fraction", F.col("overkill") / F.col("dmg_per_hit"))
        .withColumn("provenance", F.lit("derived"))
        .withColumn("derived_skill", F.lit(skill.name))
        .withColumn("derived_skill_level", F.col("lvl").cast("int"))
        .withColumn("derived_uptime", F.lit(skill.uptime))
        .drop(
            "lvl",
            "new_dmg_per_hit",
            "new_hits_to_kill",
            "new_ttk_ticks",
            "new_ttk_s",
            "new_total_damage",
            "new_dps_sustained",
            "new_dps_burst",
        )
        .select(*results.columns)
    )


def _model_coverage(skill: FlatPerHitSkill) -> Check:
    """What share of the eligible matchups the flat model actually reproduces.

    A warning rather than an error: on the equipment tiers, which carry no skills,
    this is 100% and a drop would be news. On a tier with ramping skills in the build
    it legitimately will not be, and the right response is to read the number, not to
    fail the run.
    """

    def fn(df: DataFrame) -> tuple[bool, float, str]:
        total = df.count()
        if total == 0:
            return True, 0.0, f"no {skill.role} matchups eligible for the {skill.name} model"
        ok = df.where(F.col("model_reproduces_source")).count()
        share = ok / total
        return (
            share >= 0.999,
            share,
            f"{ok} of {total} eligible matchups ({share:.4%}) reproduce hits_to_kill from a "
            f"constant per-hit amount; the rest are excluded from the {skill.name} derivation",
        )

    return Check(f"derived_model_coverage[{skill.name}]", SILVER, "silver_result_derived", fn, "warn")
