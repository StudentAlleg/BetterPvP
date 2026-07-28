-- Balance simulation output tables.
--
-- Rows are produced by driving the real combat pipeline with headless fake players, not by
-- modelling it -- see docs/balance-simulation/DESIGN.md. Grafana reads these directly, so
-- the schema is shaped for "latest run" and "run A vs run B" queries rather than for
-- normalisation.

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

CREATE TABLE IF NOT EXISTS sim_build
(
    id           BIGSERIAL PRIMARY KEY,
    run_id       BIGINT   NOT NULL REFERENCES sim_run (id) ON DELETE CASCADE,
    role         TEXT     NOT NULL,
    weapon       TEXT     NOT NULL,
    runes        JSONB    NOT NULL DEFAULT '[]'::JSONB,
    -- Per slot: {skill, allocated_level, effective_level}. Both levels are stored because
    -- a booster weapon pushes the effective level past maxLevel -- two builds with the same
    -- allocation but different weapons produce different damage (DESIGN.md 3.1).
    skills       JSONB    NOT NULL DEFAULT '{}'::JSONB,
    -- Sum of allocated levels; <= RoleBuild.points (12).
    points_spent INTEGER  NOT NULL,
    -- Derived from the weapon, denormalised so dashboards can filter on it directly.
    booster      BOOLEAN  NOT NULL DEFAULT FALSE,
    -- Stable hash over (role, weapon, runes, allocated levels). Joins a build across runs,
    -- and joins simulated builds to live per-build player data.
    fingerprint  TEXT     NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sim_build_run ON sim_build (run_id);
CREATE INDEX IF NOT EXISTS idx_sim_build_fingerprint ON sim_build (fingerprint);
CREATE UNIQUE INDEX IF NOT EXISTS idx_sim_build_run_fingerprint
    ON sim_build (run_id, fingerprint);

-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS sim_result
(
    id             BIGSERIAL PRIMARY KEY,
    run_id         BIGINT         NOT NULL REFERENCES sim_run (id) ON DELETE CASCADE,
    build_id       BIGINT         NOT NULL REFERENCES sim_build (id) ON DELETE CASCADE,
    target_role    TEXT           NOT NULL,
    target_armor   TEXT           NOT NULL,
    -- Role base health + sum of armor HEALTH stats. Armor is effective HP here, not a
    -- mitigation multiplier -- see DESIGN.md open question 1.
    target_hp      NUMERIC(10, 2) NOT NULL,
    -- The defender's build: per slot {skill, allocated_level, effective_level}. The target is a
    -- full combatant, so its DefensiveSkill passives and resistance effects change the outcome;
    -- recording the allocation keeps a result self-describing and lets matchups with the same
    -- attacker but different defender skills be told apart.
    target_skills  JSONB          NOT NULL DEFAULT '{}'::JSONB,
    -- Sum of the defender's allocated levels; <= RoleBuild.points (12).
    target_points  INTEGER        NOT NULL DEFAULT 0,
    dmg_per_hit    NUMERIC(10, 3),
    dps_sustained  NUMERIC(10, 3),
    dps_burst      NUMERIC(10, 3),
    ttk_s          NUMERIC(10, 3),
    hits_to_kill   NUMERIC(10, 3),
    -- True when the rotation stalled on energy rather than on cooldowns.
    energy_limited BOOLEAN        NOT NULL DEFAULT FALSE,
    -- Percentiles across Monte-Carlo iterations, modifier breakdown, iteration count.
    extras         JSONB          NOT NULL DEFAULT '{}'::JSONB
);

CREATE INDEX IF NOT EXISTS idx_sim_result_run ON sim_result (run_id);
CREATE INDEX IF NOT EXISTS idx_sim_result_build ON sim_result (build_id);

-- ---------------------------------------------------------------------------

-- Per-hit drill-down. Only written for builds explicitly flagged for tracing, because a
-- full sweep would produce orders of magnitude more rows here than in sim_result.
CREATE TABLE IF NOT EXISTS sim_trace
(
    id       BIGSERIAL PRIMARY KEY,
    run_id   BIGINT NOT NULL REFERENCES sim_run (id) ON DELETE CASCADE,
    build_id BIGINT NOT NULL REFERENCES sim_build (id) ON DELETE CASCADE,
    t_ms     BIGINT NOT NULL,
    event    TEXT   NOT NULL,
    amount   NUMERIC(10, 3)
);

CREATE INDEX IF NOT EXISTS idx_sim_trace_run_build ON sim_trace (run_id, build_id, t_ms);
