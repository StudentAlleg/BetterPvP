-- Balance simulation output tables.
--
-- Rows are produced by driving the real combat pipeline with headless fake players, not by
-- modelling it -- see docs/balance-simulation/DESIGN.md. Grafana reads these directly, so
-- the schema is shaped for "latest run" and "run A vs run B" queries rather than for
-- normalisation.
--
-- This file is the whole schema. It was consolidated from nine incremental migrations, so a
-- column added later reads here as though it had always existed and the file describes the
-- shape rather than the route to it. Flyway is configured with validateOnMigrate(false) and
-- calls repair() before migrate(), so a database that applied the original sequence sees this
-- version as already installed and the superseded versions as deleted; a fresh database gets
-- this and nothing else. The consequence to remember: THIS FILE NEVER RUNS AGAIN on an
-- existing database. Editing it changes what new databases get and nothing else -- a genuine
-- schema change still needs its own new migration beside it.

CREATE TABLE IF NOT EXISTS sim_run
(
    id             BIGSERIAL PRIMARY KEY,
    realm          INTEGER     NOT NULL,
    started_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    finished_at    TIMESTAMPTZ,
    -- What kicked the run off: COMMAND, SCHEDULED.
    trigger        TEXT        NOT NULL,
    -- Bumped whenever the engine's measurement semantics change, so old runs are not
    -- silently diffed against new ones.
    engine_version TEXT        NOT NULL,
    -- sha256 over every config value that contributed to the run. Two runs are only
    -- comparable when this differs for the reason you think it does.
    config_hash    TEXT        NOT NULL,
    -- Scope/iteration/tier knobs the run was invoked with.
    scenario       JSONB       NOT NULL DEFAULT '{}'::JSONB,
    status         TEXT        NOT NULL DEFAULT 'RUNNING'
);

CREATE INDEX IF NOT EXISTS idx_sim_run_realm_started
    ON sim_run (realm, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_sim_run_config_hash
    ON sim_run (config_hash);

-- ---------------------------------------------------------------------------

-- One attacker permutation. The weapon columns are denormalised on purpose: sim_build.weapon
-- is a registry key and the damage behind it lives in the item config the run was taken
-- under, which is exactly what will have changed by the time anyone diffs two runs. Copying
-- the figures onto the row makes it self-describing.
CREATE TABLE IF NOT EXISTS sim_build
(
    id                       BIGSERIAL PRIMARY KEY,
    run_id                   BIGINT         NOT NULL REFERENCES sim_run (id) ON DELETE CASCADE,
    role                     TEXT           NOT NULL,
    weapon                   TEXT           NOT NULL,
    runes                    JSONB          NOT NULL DEFAULT '[]'::JSONB,
    -- Per slot: {skill, allocated_level, effective_level}. Both levels are stored because
    -- a booster weapon pushes the effective level past maxLevel -- two builds with the same
    -- allocation but different weapons produce different damage (DESIGN.md 3.1).
    skills                   JSONB          NOT NULL DEFAULT '{}'::JSONB,
    -- Sum of allocated levels; <= RoleBuild.points (12).
    points_spent             INTEGER        NOT NULL,
    -- Derived from the weapon, denormalised so dashboards can filter on it directly.
    booster                  BOOLEAN        NOT NULL DEFAULT FALSE,
    -- Stable hash over (role, weapon, runes, allocated levels). Joins a build across runs,
    -- and joins simulated builds to live per-build player data.
    fingerprint              TEXT           NOT NULL,

    -- The configured centre of the weapon's damage band. MeleeDamageStatHandler calls
    -- event.setDamage(stat.getValue()), so this is what a 'base'-roll duel swung for.
    weapon_damage_base       NUMERIC(10, 3),
    -- The roll envelope from the item's damage.min/damage.max config. Two weapons with equal
    -- base and different envelopes are distinct profiles and are never folded together.
    weapon_damage_min        NUMERIC(10, 3),
    weapon_damage_max        NUMERIC(10, 3),
    weapon_attack_speed_base NUMERIC(10, 3),
    weapon_attack_speed_min  NUMERIC(10, 3),
    weapon_attack_speed_max  NUMERIC(10, 3),
    -- The skill slot the weapon serves when held, or 'none'. Part of the dedupe key: a sword
    -- and an axe with identical numbers drive different builds, because Skill.getLevel
    -- requires isHolding.
    weapon_slot              TEXT,
    -- Every weapon key this row's measurement covers, this row's weapon first. The BASELINE
    -- and LOADOUT tiers deduplicate the weapon axis by stat profile, so one measured row can
    -- cover permutations that have no row of their own; without this list a dashboard cannot
    -- tell "never swept" from "swept under another key", and those are opposite conclusions.
    -- A single-element array on the un-reduced tiers.
    weapon_aliases           JSONB          NOT NULL DEFAULT '[]'::JSONB,
    -- Which corner of the envelope the weapon was actually instantiated at: 'base' | 'min' |
    -- 'max', matching SimStatRoll.id(). Without it a MIN row and a MAX row of the same weapon
    -- are indistinguishable while reporting very different TTK. 'base' is the true value for a
    -- sweep that did not vary the axis, not a placeholder for unknown, which is why it
    -- defaults rather than being nullable.
    weapon_roll              TEXT           NOT NULL DEFAULT 'base',

    -- Digest over the attacker half -- weapon + runes + skills + role base health -- so a
    -- --changed sweep can decide per build whether it needs re-measuring. sim_run.config_hash
    -- answers "did anything move", which is the right question for comparing two runs and the
    -- wrong one here: it covers every balance value in the game, so one changed cooldown would
    -- re-run the entire catalog. See SimConfigDigest, and BalanceCatalog.buildScopeHash for
    -- what is deliberately left out of a scope and why each omission errs towards
    -- re-measuring. NULL means "carries no claim about its inputs", which is correct for a run
    -- that predates change detection and makes a delta sweep against it re-measure everything.
    config_scope_hash        TEXT
);

CREATE INDEX IF NOT EXISTS idx_sim_build_run ON sim_build (run_id);
CREATE INDEX IF NOT EXISTS idx_sim_build_fingerprint ON sim_build (fingerprint);
CREATE UNIQUE INDEX IF NOT EXISTS idx_sim_build_run_fingerprint
    ON sim_build (run_id, fingerprint);
-- Dashboards group by weapon damage far more often than they filter on a single weapon key,
-- since "what does this damage tier buy" is the question the weapon axis exists to answer.
CREATE INDEX IF NOT EXISTS idx_sim_build_weapon_damage
    ON sim_build (run_id, weapon_damage_base);
-- The roll is only meaningful alongside the figure it selects: "damage tier x roll" is the
-- grouping the axis exists to make answerable.
CREATE INDEX IF NOT EXISTS idx_sim_build_weapon_roll
    ON sim_build (run_id, weapon_roll, weapon_damage_base);
-- The delta plan's lookup: given a baseline run, what each of its builds depended on.
CREATE INDEX IF NOT EXISTS idx_sim_build_run_scope
    ON sim_build (run_id, config_scope_hash);

-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS sim_result
(
    id                  BIGSERIAL PRIMARY KEY,
    run_id              BIGINT         NOT NULL REFERENCES sim_run (id) ON DELETE CASCADE,
    build_id            BIGINT         NOT NULL REFERENCES sim_build (id) ON DELETE CASCADE,
    target_role         TEXT           NOT NULL,
    -- The armour set and its stat roll folded into one discriminator: 'none', 'role_set',
    -- 'role_set_max'. Every stored row and every dashboard groups by this; the two columns
    -- below split it back out without changing its meaning.
    target_armor        TEXT           NOT NULL,
    -- Role base health + sum of armor HEALTH stats. Armor is effective HP here, not a
    -- mitigation multiplier -- see DESIGN.md open question 1.
    target_hp           NUMERIC(10, 2) NOT NULL,
    -- The defender's build: per slot {skill, allocated_level, effective_level}. The target is a
    -- full combatant, so its DefensiveSkill passives and resistance effects change the outcome;
    -- recording the allocation keeps a result self-describing and lets matchups with the same
    -- attacker but different defender skills be told apart.
    target_skills       JSONB          NOT NULL DEFAULT '{}'::JSONB,
    -- Sum of the defender's allocated levels; <= RoleBuild.points (12).
    target_points       INTEGER        NOT NULL DEFAULT 0,
    -- Every target role this result covers, target_role first. Longer than one element only
    -- where an unarmoured skill-less defender was shared between roles of equal health -- a
    -- reduction that is only sound in a ONE_WAY sweep, which is why it is recorded per result
    -- rather than per run.
    target_role_aliases JSONB          NOT NULL DEFAULT '[]'::JSONB,
    -- The armour set alone, roll suffix stripped: 'none', 'reinforced'.
    target_armor_set    TEXT,
    -- 0 for bare, then 1 upwards by the set's summed HEALTH. Nullable rather than defaulted:
    -- an armoured row whose set was never recorded is at an unknown rung, and defaulting it to
    -- 0 would place it on the ladder beside the bare rows it is meant to be compared against.
    -- The mapping from set id to tier is an ordering by live HEALTH stats that only the sweep
    -- can compute, which is why it is a column rather than a CASE in a dashboard -- a second
    -- source of truth would silently stop agreeing the moment a set was added between them.
    target_armor_tier   INTEGER,

    dmg_per_hit         NUMERIC(10, 3),
    dps_sustained       NUMERIC(10, 3),
    dps_burst           NUMERIC(10, 3),
    ttk_s               NUMERIC(10, 3),
    hits_to_kill        NUMERIC(10, 3),
    -- True when the rotation stalled on energy rather than on cooldowns.
    energy_limited      BOOLEAN        NOT NULL DEFAULT FALSE,
    -- Percentiles across Monte-Carlo iterations, modifier breakdown, iteration count.
    extras              JSONB          NOT NULL DEFAULT '{}'::JSONB,

    -- Digest over the whole matchup: the build's scope, the target's scope, and the
    -- measurement terms that decide what a duel means (scenario, iterations, timeout, channel
    -- hold budget, engine version). All of it, because a row measured at one iteration must
    -- not be carried into a ten-iteration run as though the two were the same measurement.
    config_scope_hash   TEXT,
    -- Which run actually ran the duels behind this row: equal to run_id for a freshly measured
    -- row, and the older run's id for one carried forward unchanged. A delta run is
    -- deliberately a WHOLE run -- it copies the unchanged rows in rather than holding only the
    -- changed ones -- so every dashboard, every gold mart and "latest run" keep working with
    -- no union step. This column is what keeps that honest: without it a carried row is
    -- indistinguishable from a fresh one.
    measured_run_id     BIGINT REFERENCES sim_run (id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_sim_result_run ON sim_result (run_id);
CREATE INDEX IF NOT EXISTS idx_sim_result_build ON sim_result (build_id);
CREATE INDEX IF NOT EXISTS idx_sim_result_run_scope
    ON sim_result (run_id, config_scope_hash);
-- "How much of this run was actually measured" is the first question asked of a delta run, and
-- it is asked of every row of a multi-million-row table.
CREATE INDEX IF NOT EXISTS idx_sim_result_measured_run
    ON sim_result (run_id, measured_run_id);
-- The access path for the ladder query: every tier of one target role within one run, which is
-- exactly what a TTK-parity comparison scans.
CREATE INDEX IF NOT EXISTS idx_sim_result_run_tier
    ON sim_result (run_id, target_role, target_armor_tier);

-- ---------------------------------------------------------------------------

-- Per-hit trace, written only when champions.simulation.hitTrace is on.
--
-- One row per landed hit, ordered on the duel's own tick axis, so two iterations of one
-- matchup can be diffed straight down until the first row that disagrees. That is what it
-- exists for: the sweep is not bit-reproducible -- the same build against the same target over
-- four repeat iterations returns different damage in 2.3% of MUTUAL matchups and 5.2% of
-- ONE_WAY ones -- and sim_duel_diagnostic can prove the divergence exists but not where it
-- starts, because it holds the totals and the totals are the thing that disagrees.
--
-- Scale is the thing to keep in mind before switching it on: a full EQUIPMENT sweep is tens of
-- millions of rows here, against thousands in sim_result.
CREATE TABLE IF NOT EXISTS sim_trace
(
    id           BIGSERIAL PRIMARY KEY,
    run_id       BIGINT  NOT NULL REFERENCES sim_run (id) ON DELETE CASCADE,
    build_id     BIGINT  NOT NULL REFERENCES sim_build (id) ON DELETE CASCADE,
    -- The identity of a hit needs all of these: build_id alone cannot separate four iterations
    -- of the same matchup, which is precisely the comparison being made.
    target_role  TEXT    NOT NULL,
    target_armor TEXT    NOT NULL,
    iteration    INTEGER NOT NULL,
    -- Carried so a divergence can be checked against residency without joining back to the
    -- diagnostics. Two iterations that differ on different platforms are a different suspect
    -- from two that differ on the same one.
    arena_index  INTEGER NOT NULL,
    -- Server ticks since the duel's first landed hit -- the same anchor sim_result measures TTK
    -- from, so a trace row and the row it explains share an axis. This is the authoritative
    -- clock; t_ms is derived from it at write time and kept because the original table declared
    -- it, but a millisecond figure invites comparison against a wall clock nothing here runs on.
    tick         INTEGER NOT NULL,
    -- Ordering within a tick. Two hits can land on the same tick and the diff has to be stable,
    -- or it reports a divergence that is only a difference in insertion order.
    seq          INTEGER NOT NULL,
    -- The absolute server tick the duel's trace is anchored on. Every other tick here is
    -- relative to the first landed hit, deliberately -- and that normalisation is what hides a
    -- suspected cause of divergence: periodic skill machinery is scheduled against the global
    -- tick counter rather than against the duel (UpdateEventExecutor keys its schedule on the
    -- delay value server-wide), so a duel beginning on an odd global tick can see a period-2
    -- skill step one tick later than one beginning on an even tick. Storing the anchor makes
    -- that phase a column to group on rather than an inference.
    anchor_tick  INTEGER NOT NULL,
    t_ms         BIGINT  NOT NULL,
    -- 'attacker' or 'defender'. Under MUTUAL both sides land hits and only one of them is the
    -- build being measured; under ONE_WAY the defender never appears.
    actor        TEXT    NOT NULL,
    event        TEXT    NOT NULL,
    -- Post-mitigation damage.
    amount       NUMERIC(10, 3),
    -- Pre-mitigation damage. A divergence in raw damage is a different bug from one where raw
    -- agrees and final does not: the first is the swing, the second is the mitigation pipeline.
    raw_amount   NUMERIC(10, 3),
    -- The modifier stack behind the hit, one object per applied modifier:
    --   {"source":"Combo Attack","operator":"FLAT","operand":2.0,"priority":200,
    --    "type":"ABILITY","reductive":false}
    --
    -- Every other damage number in this schema is an OUTCOME -- what the hit came to -- and
    -- none of them say why. Without this a skill's contribution has to be modelled from config,
    -- and modelling it from config has been measured and does not work: scored against the
    -- eleven skills that actually construct a SkillDamageModifier.Multiplier, config-key
    -- inference gets precision 1/5 and recall 1/11, filing Defensive Stance and Riposte as flat
    -- and missing eight outright.
    --
    -- With the operands stored, a skill's contribution is read rather than inferred, and
    -- DamageEvent's composition --
    --
    --     final = (base * SUM(amplifying multipliers) + SUM(flats)) * PROD(reductive multipliers)
    --
    -- -- is closed form and order-independent, so the whole skill x weapon x rune cross becomes
    -- arithmetic over an existing sweep rather than duels nobody can afford to run.
    --
    -- JSONB here rather than a child table: the identity of a hit is already six columns wide,
    -- a child table would repeat all six per modifier, and a hit carries only a handful.
    -- `reductive` is stored rather than derived even though ModifierResult.isReductive computes
    -- it from the operand (FLAT <= 0, MULTIPLIER < 1.0) -- storing what the game decided is the
    -- point of measuring instead of modelling.
    --
    -- Nullable with no default: NULL means "not captured", '[]' means "captured, and there were
    -- none". An empty array on an uncaptured row would be a measurement, and a wrong one.
    modifiers    JSONB
);

COMMENT ON COLUMN sim_trace.modifiers IS
    'Applied damage modifiers for this hit, one object per modifier: source, operator '
        '(FLAT|MULTIPLIER), operand, priority, type, reductive. NULL means not captured; '
        '[] means captured and empty.';

-- The diff's own access path: everything that identifies a duel, then the order within it. A
-- divergence hunt reads whole duels in tick order and compares them pairwise.
CREATE INDEX IF NOT EXISTS idx_sim_trace_duel
    ON sim_trace (run_id, build_id, target_role, target_armor, iteration, tick, seq);
CREATE INDEX IF NOT EXISTS idx_sim_trace_run_build ON sim_trace (run_id, build_id, t_ms);
-- Skills are looked up by name across a whole run, which is a scan of a very large table
-- otherwise. jsonb_path_ops is the smaller, faster operator class -- it supports only
-- containment (@>), which is the only predicate this column is queried with.
CREATE INDEX IF NOT EXISTS idx_sim_trace_modifiers
    ON sim_trace USING GIN (modifiers jsonb_path_ops);

-- ---------------------------------------------------------------------------

-- Per-duel diagnostics, written only when champions.simulation.duelDiagnostics is on.
--
-- Exists to answer one question sim_result cannot: run 164 showed that adding a skill which
-- provably does nothing -- a pure passive with no button, or Blood Compass, which is a compass
-- -- flipped a WARLOCK vs ASSASSIN/none matchup from 10/10 attacker wins to 0/10 attacker
-- deaths, with damage fully deterministic (dmg_per_hit_stddev = 0 on every row). Something
-- outside the build decides that fight, and every figure on sim_result is a mean over ten
-- duels, so the deciding difference is averaged away before it is ever stored.
--
-- One row per duel rather than per matchup, for that reason. The typed columns are the ones a
-- hypothesis is tested against directly; detail carries the rest, the way sim_result.extras
-- does.
CREATE TABLE IF NOT EXISTS sim_duel_diagnostic
(
    id                      BIGSERIAL PRIMARY KEY,
    run_id                  BIGINT         NOT NULL REFERENCES sim_run (id) ON DELETE CASCADE,
    build_id                BIGINT         NOT NULL REFERENCES sim_build (id) ON DELETE CASCADE,
    target_role             TEXT           NOT NULL,
    target_armor            TEXT           NOT NULL,
    -- Which iteration of the matchup this was. Ten duels reduce to one sim_result row; this is
    -- the only place they stay distinguishable.
    iteration               INTEGER        NOT NULL,

    -- Residency. The pool reuses ServerPlayers across duels and revives them when they die
    -- (SimCombatantPool), and the catalog emits skill-less baselines first -- so a baseline
    -- systematically fights fresher residents than the skill builds it is subtracted from. If
    -- that is what decides these fights, it shows up as outcome correlating with these columns
    -- and with nothing about the build.
    arena_index             INTEGER        NOT NULL,
    attacker_duels_fought   INTEGER        NOT NULL,
    defender_duels_fought   INTEGER        NOT NULL,
    attacker_revives        INTEGER        NOT NULL,
    defender_revives        INTEGER        NOT NULL,
    attacker_ever_died      BOOLEAN        NOT NULL,
    defender_ever_died      BOOLEAN        NOT NULL,

    -- DEFENDER_KILLED, ATTACKER_KILLED, TIMEOUT, BARREN.
    outcome                 TEXT           NOT NULL,
    resolved_tick           INTEGER,

    -- The race. Under MUTUAL the defender acts and swings before the attacker on every tick
    -- (DuelOrchestrator.advance), and a 29 HP target dies in five hits, so the fight is decided
    -- inside about thirty ticks. Whoever lands first almost certainly wins, which makes these
    -- two columns the difference between "the skill changed the damage" and "the skill changed
    -- the timing".
    attacker_first_hit_tick INTEGER,
    defender_first_hit_tick INTEGER,
    attacker_hits           INTEGER        NOT NULL,
    defender_hits           INTEGER        NOT NULL,
    attacker_damage         NUMERIC(10, 3) NOT NULL,
    defender_damage         NUMERIC(10, 3) NOT NULL,

    -- Start-of-duel durability, read off the live entity after everything is equipped. A build
    -- carrying one no-op passive should be indistinguishable from a bare one here; if it is not,
    -- the fight was decided at setup rather than in combat.
    attacker_start_health   NUMERIC(10, 3),
    attacker_max_health     NUMERIC(10, 3),
    defender_start_health   NUMERIC(10, 3),
    defender_max_health     NUMERIC(10, 3),
    attacker_end_health     NUMERIC(10, 3),
    defender_end_health     NUMERIC(10, 3),

    -- Setup snapshots per side (attributes, energy, held item, tracked skills, effects), the
    -- residue probe taken before setup, the per-combatant damage funnel, the full two-way hit
    -- timeline and every skill press. See SimDuelDiagnostic for the shape.
    detail                  JSONB          NOT NULL DEFAULT '{}'::JSONB
);

CREATE INDEX IF NOT EXISTS idx_sim_duel_diag_run_build
    ON sim_duel_diagnostic (run_id, build_id);
CREATE INDEX IF NOT EXISTS idx_sim_duel_diag_run_outcome
    ON sim_duel_diagnostic (run_id, outcome);
-- The residency correlation is the first query this table exists to serve.
CREATE INDEX IF NOT EXISTS idx_sim_duel_diag_run_arena
    ON sim_duel_diagnostic (run_id, arena_index, attacker_duels_fought);

-- ---------------------------------------------------------------------------

-- What a --changed sweep intends to cover, staged so the comparison against the baseline
-- happens in the database rather than on the server heap.
--
-- The obvious implementation is to read the baseline run's matchups into a Map and diff them in
-- Java, which is what the resume path does with findMeasuredMatchups. That does not survive
-- this table's actual scale: run 1 holds 4.3 million results, and a HashMap of matchup key to
-- hash for it is on the order of a gigabyte -- inside a Paper server's heap, next to a sweep
-- that is about to spawn hundreds of fake players. The plan goes the other way instead: the
-- freshly enumerated catalog is written here in batches, Postgres joins it against the
-- baseline, and the only thing read back is the set of matchups that actually need measuring --
-- which for a delta is small, because that is the entire point of a delta.
--
-- UNLOGGED is deliberate but is NOT declared here: this table pre-dates the note and exists as
-- a logged table on deployed databases, and switching it would diverge new installs from old.
-- It is derivable scratch, rebuilt from the catalog on every --changed run and worthless after
-- planning, so it is a candidate for ALTER TABLE ... SET UNLOGGED in its own migration. Not a
-- TEMP table either way, because those are per-connection and the pooled async context does not
-- promise one connection across the several statements planning takes.
CREATE TABLE IF NOT EXISTS sim_delta_plan
(
    run_id            BIGINT NOT NULL REFERENCES sim_run (id) ON DELETE CASCADE,
    -- The attacker build, by the same fingerprint sim_build carries.
    fingerprint       TEXT   NOT NULL,
    target_role       TEXT   NOT NULL,
    target_armor      TEXT   NOT NULL,
    -- What this matchup depends on, as the freshly read config says it is now. A baseline row
    -- whose stored hash equals this is still a true measurement and is carried forward;
    -- anything else is re-measured.
    config_scope_hash TEXT   NOT NULL
);

-- The join key for the carry-forward, and for reading back what is left to measure.
CREATE INDEX IF NOT EXISTS idx_sim_delta_plan_run
    ON sim_delta_plan (run_id, fingerprint, target_role, target_armor);
