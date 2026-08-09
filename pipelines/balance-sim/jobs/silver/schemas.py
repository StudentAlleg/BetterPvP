"""Declared schemas for the JSONB columns bronze lands as strings.

Parsing these with an explicit schema rather than schema inference is deliberate.
Inference reads the data to decide the type, so a run where every `dmg_per_hit_stddev`
happens to be null infers a different type from one where it is not, and two runs
then cannot be unioned for a patch diff. It also silently accepts a key that has been
renamed upstream, by inferring the new one and leaving the old one null.

Anything not declared here stays reachable: silver keeps the original JSON string in
`extras_raw` / `scenario_raw`, so a key added by a future engine version is not lost
before somebody notices it exists.
"""
from __future__ import annotations

from pyspark.sql.types import (
    ArrayType,
    BooleanType,
    DoubleType,
    IntegerType,
    LongType,
    MapType,
    StringType,
    StructField,
    StructType,
)

# sim_run.scenario -- the knobs the sweep was invoked with. `balance_config` is a
# digest string (SimConfigDigest), not a nested document.
SCENARIO = StructType(
    [
        StructField("phase", IntegerType()),
        StructField("scope", StringType()),
        StructField("scenario", StringType()),
        StructField("rotation", StringType()),
        StructField("actives", BooleanType()),
        StructField("iterations", IntegerType()),
        StructField("timeout_s", DoubleType()),
        StructField("skill_filter", StringType()),
        StructField("relevant_skills", IntegerType()),
        StructField("channel_hold_ticks", IntegerType()),
        StructField("balance_config", StringType()),
    ]
)

# sim_result.extras.energy -- the attacker's energy ledger for the matchup.
ENERGY = StructType(
    [
        StructField("max_energy", DoubleType()),
        StructField("min_energy", DoubleType()),
        StructField("iterations_observed", IntegerType()),
        StructField("regen_custom_per_duel", DoubleType()),
        StructField("regen_natural_per_duel", DoubleType()),
        StructField("drained_custom_per_duel", DoubleType()),
        StructField("drained_natural_per_duel", DoubleType()),
        StructField("spent_on_skills_per_duel", DoubleType()),
    ]
)

# sim_result.extras -- percentiles across Monte-Carlo iterations plus the outcome
# counts that qualify ttk_s. `reasons` is the pipeline's own modifier breakdown:
# modifier name -> how many hits carried it.
EXTRAS = StructType(
    [
        StructField("kills", LongType()),
        StructField("kill_rate", DoubleType()),
        StructField("iterations", IntegerType()),
        StructField("planned_iterations", IntegerType()),
        StructField("attacker_deaths", IntegerType()),
        StructField("energy_limited_iterations", IntegerType()),
        StructField("dmg_per_hit_stddev", DoubleType()),
        StructField("dps_p50", DoubleType()),
        StructField("dps_p90", DoubleType()),
        StructField("ttk_ticks_mean", DoubleType()),
        StructField("ttk_ticks_p50", DoubleType()),
        StructField("ttk_ticks_p90", DoubleType()),
        StructField("reasons", MapType(StringType(), IntegerType())),
        StructField("activations", MapType(StringType(), IntegerType())),
        StructField("energy", ENERGY),
    ]
)

# sim_build.skills / sim_result.target_skills -- see SimSkillAllocation.
SKILLS = ArrayType(
    StructType(
        [
            StructField("skill", StringType()),
            StructField("slot", StringType()),
            StructField("allocated_level", IntegerType()),
            StructField("effective_level", IntegerType()),
        ]
    )
)

# sim_build.runes / sim_build.weapon_aliases / sim_result.target_role_aliases --
# flat string arrays of registry keys.
STRING_ARRAY = ArrayType(StringType())
