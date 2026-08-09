-- Serving-layer tables for the balance-simulation medallion pipeline.
--
-- Spark's JDBC writer will create these itself on a first publish, but only as
-- untyped, unindexed heaps. Applying this file first gives the marts their indexes
-- and their column comments, and the publisher writes with `truncate=true` so a
-- republish keeps them.
--
-- Run once against the same database the sim plugin writes to:
--   psql -h localhost -p 5002 -U user -d betterpvp -f sql/gold_ddl.sql
--
-- The pipeline is the only writer. Nothing in the Paper plugin touches sim_gold_*.

-- ---------------------------------------------------------------------------
-- Run header. Every other mart is filtered by run_id, and every dashboard's first
-- variable is a pick from this table.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sim_gold_run
(
    run_id               BIGINT PRIMARY KEY,
    realm                INTEGER     NOT NULL,
    started_at           TIMESTAMPTZ,
    finished_at          TIMESTAMPTZ,
    status               TEXT,
    trigger              TEXT,
    engine_version       TEXT,
    config_hash          TEXT,
    balance_config_hash  TEXT,
    scope                TEXT,
    scenario             TEXT,
    rotation             TEXT,
    actives              BOOLEAN,
    iterations           INTEGER,
    duel_timeout_s       DOUBLE PRECISION,
    skill_filter         TEXT,
    relevant_skill_count INTEGER,
    channel_hold_ticks   INTEGER,
    engine_phase         INTEGER,
    scenario_raw         TEXT,
    -- A CANCELLED sweep is a prefix of an enumeration, and DESIGN.md is explicit
    -- that a prefix is a biased sample. Carried on the header so no mart can be read
    -- without it being available.
    is_complete          BOOLEAN,
    wall_clock_s         DOUBLE PRECISION,
    _pipeline_run_id     TEXT,
    _ingested_at         TIMESTAMPTZ
);

-- ---------------------------------------------------------------------------
-- The wide fact. One row per (build, target, provenance variant), every axis
-- flattened so a panel filter is a WHERE and never a join.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sim_gold_matchup
(
    run_id                      BIGINT           NOT NULL,
    realm                       INTEGER,
    scope                       TEXT,
    scenario                    TEXT,
    config_hash                 TEXT,
    engine_version              TEXT,
    status                      TEXT,
    is_complete                 BOOLEAN,
    -- Negative for derived rows: they have no counterpart in sim_result and an id
    -- that looks like one invites a lookup that cannot succeed.
    result_id                   BIGINT           NOT NULL,
    build_id                    BIGINT           NOT NULL,
    fingerprint                 TEXT,
    role                        TEXT,
    weapon_key                  TEXT,
    weapon_slot                 TEXT,
    weapon_profile_key          TEXT,
    weapon_roll                 TEXT,
    weapon_damage_applied       DOUBLE PRECISION,
    weapon_damage_base          DOUBLE PRECISION,
    weapon_damage_min           DOUBLE PRECISION,
    weapon_damage_max           DOUBLE PRECISION,
    weapon_attack_speed_applied DOUBLE PRECISION,
    booster                     BOOLEAN,
    points_spent                INTEGER,
    skill_count                 INTEGER,
    rune_count                  INTEGER,
    rune_set_key                TEXT,
    runes                       TEXT,
    -- Every weapon key this row's measurement covers. The reduced tiers dedupe the
    -- weapon axis by stat profile, so "not in weapon_key" and "never swept" are
    -- different statements.
    weapon_aliases              TEXT,
    target_role                 TEXT,
    target_armor                TEXT,
    -- The armour ladder. target_armor folds the set and its stat roll into one
    -- discriminator and stays the identity of a measurement; these two are what a
    -- comparison *across* rungs groups by. target_armor_tier is null on rows measured
    -- before the tier axis existed -- see the armour_tier_known expectation.
    target_armor_set            TEXT,
    target_armor_tier           INTEGER,
    target_hp                   DOUBLE PRECISION,
    target_skill_count          INTEGER,
    dmg_per_hit                 DOUBLE PRECISION,
    dps_sustained               DOUBLE PRECISION,
    dps_burst                   DOUBLE PRECISION,
    dps_p50                     DOUBLE PRECISION,
    dps_p90                     DOUBLE PRECISION,
    ttk_s                       DOUBLE PRECISION,
    ttk_ticks_mean              DOUBLE PRECISION,
    hits_to_kill                DOUBLE PRECISION,
    total_damage                DOUBLE PRECISION,
    -- Damage past zero on the lethal blow, and that as a fraction of one hit. A
    -- build overkilling by most of a swing is one balance change from needing an
    -- extra one, and TTK does not show the cliff until it is crossed.
    overkill                    DOUBLE PRECISION,
    overkill_fraction           DOUBLE PRECISION,
    swing_interval_ticks        DOUBLE PRECISION,
    swings_per_second           DOUBLE PRECISION,
    kill_rate                   DOUBLE PRECISION,
    kills                       BIGINT,
    attacker_deaths             INTEGER,
    iterations                  INTEGER,
    dmg_per_hit_stddev          DOUBLE PRECISION,
    energy_limited              BOOLEAN,
    energy_spent_per_duel       DOUBLE PRECISION,
    -- 'measured' or 'derived'. Never mix them in an aggregate without saying so.
    provenance                  TEXT             NOT NULL,
    derived_skill               TEXT,
    derived_skill_level         INTEGER,
    derived_bonus_per_hit       DOUBLE PRECISION,
    -- What share of hits the derived skill was assumed to apply on. 1.0 is the upper
    -- bound, not an estimate of play.
    derived_uptime              DOUBLE PRECISION,
    source_result_id            BIGINT,
    -- measured: this run ran the duels. carried: an earlier run did, and nothing this
    -- matchup depends on has changed since, so a `--changed` sweep copied the row in
    -- rather than paying for it again. unknown: the run predates change detection.
    --
    -- A delta run is deliberately a WHOLE run, so that "latest run" and every mart
    -- keep working with no union step -- which means a run's rows can be of two ages
    -- and nothing else on them would say so. This column is that.
    freshness                   TEXT,
    measured_run_id             BIGINT,
    -- Digest of exactly the config this matchup depended on. Two rows sharing it were
    -- measured against the same weapon, runes, skills and armour, so the decision to
    -- carry a row forward is checkable from the warehouse rather than only from a log.
    config_scope_hash           TEXT
);

CREATE INDEX IF NOT EXISTS idx_gold_matchup_run ON sim_gold_matchup (run_id, provenance);
-- "How much of this run was actually measured" is the first question asked of a delta.
CREATE INDEX IF NOT EXISTS idx_gold_matchup_freshness ON sim_gold_matchup (run_id, freshness);
CREATE INDEX IF NOT EXISTS idx_gold_matchup_role_target
    ON sim_gold_matchup (run_id, role, target_role, target_armor);
-- The ladder scan: every tier of one target role, which is what a TTK-parity panel reads.
CREATE INDEX IF NOT EXISTS idx_gold_matchup_tier
    ON sim_gold_matchup (run_id, target_role, target_armor_tier);
CREATE INDEX IF NOT EXISTS idx_gold_matchup_weapon
    ON sim_gold_matchup (run_id, weapon_profile_key, weapon_roll);
CREATE INDEX IF NOT EXISTS idx_gold_matchup_fingerprint ON sim_gold_matchup (fingerprint);
CREATE INDEX IF NOT EXISTS idx_gold_matchup_runes ON sim_gold_matchup (run_id, rune_count, rune_set_key);

-- ---------------------------------------------------------------------------
-- Weapon axis. What a stat profile is worth across the rune sets it can carry.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sim_gold_weapon_axis
(
    run_id                      BIGINT NOT NULL,
    role                        TEXT,
    weapon_profile_key          TEXT,
    weapon_slot                 TEXT,
    weapon_roll                 TEXT,
    target_role                 TEXT,
    target_armor                TEXT,
    provenance                  TEXT,
    derived_skill               TEXT,
    derived_skill_level         INTEGER,
    representative_weapon       TEXT,
    weapon_keys_measured        BIGINT,
    weapon_damage_applied       DOUBLE PRECISION,
    weapon_attack_speed_applied DOUBLE PRECISION,
    matchups                    BIGINT,
    rune_sets                   BIGINT,
    dps_min                     DOUBLE PRECISION,
    dps_mean                    DOUBLE PRECISION,
    dps_p50                     DOUBLE PRECISION,
    dps_max                     DOUBLE PRECISION,
    ttk_min                     DOUBLE PRECISION,
    ttk_mean                    DOUBLE PRECISION,
    ttk_max                     DOUBLE PRECISION,
    hits_to_kill_mean           DOUBLE PRECISION,
    hits_to_kill_min            DOUBLE PRECISION,
    hits_to_kill_max            DOUBLE PRECISION,
    kill_rate_mean              DOUBLE PRECISION,
    -- How much of a weapon's strength is the weapon and how much is what it is
    -- socketed with. A narrow spread means runes do not help this profile.
    dps_spread                  DOUBLE PRECISION,
    dps_spread_pct              DOUBLE PRECISION
);

CREATE INDEX IF NOT EXISTS idx_gold_weapon_axis_run
    ON sim_gold_weapon_axis (run_id, target_role, target_armor);

-- ---------------------------------------------------------------------------
-- Rune contribution. One rune against the bare row of the same weapon, roll,
-- attacker role and target. Only rune_count = 1 rows are attributed -- a multi-rune
-- build cannot be decomposed after the fact.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sim_gold_rune_contribution
(
    run_id                BIGINT NOT NULL,
    role                  TEXT,
    weapon_profile_key    TEXT,
    weapon_roll           TEXT,
    target_role           TEXT,
    target_armor          TEXT,
    provenance            TEXT,
    derived_skill         TEXT,
    derived_skill_level   INTEGER,
    weapon_key            TEXT,
    weapon_damage_applied DOUBLE PRECISION,
    -- The carrier's speed, so "is this effect worth more on a fast weapon" is answerable
    -- from this table alone. Declared rather than measured: swings_per_second is null on
    -- a row that never killed, and an interaction is least visible where the fight did
    -- not resolve. Both are carried; sim_gold_effect_interaction correlates on the first.
    weapon_attack_speed_applied DOUBLE PRECISION,
    swings_per_second     DOUBLE PRECISION,
    rune_key              TEXT,
    build_id              BIGINT,
    dps_sustained         DOUBLE PRECISION,
    dps_burst             DOUBLE PRECISION,
    ttk_s                 DOUBLE PRECISION,
    dmg_per_hit           DOUBLE PRECISION,
    hits_to_kill          DOUBLE PRECISION,
    kill_rate             DOUBLE PRECISION,
    base_dps              DOUBLE PRECISION,
    base_dps_burst        DOUBLE PRECISION,
    base_ttk_s            DOUBLE PRECISION,
    base_dmg_per_hit      DOUBLE PRECISION,
    base_hits_to_kill     DOUBLE PRECISION,
    base_kill_rate        DOUBLE PRECISION,
    dps_delta             DOUBLE PRECISION,
    dps_delta_pct         DOUBLE PRECISION,
    ttk_delta             DOUBLE PRECISION,
    dmg_per_hit_delta     DOUBLE PRECISION,
    -- Whole swings the rune removes from the kill. The figure a designer actually
    -- feels, and the one a fractional DPS delta hides.
    hits_saved            DOUBLE PRECISION
);

CREATE INDEX IF NOT EXISTS idx_gold_rune_contribution_run
    ON sim_gold_rune_contribution (run_id, rune_key);

-- ---------------------------------------------------------------------------
-- Rune set synergy. A set's own delta against the sum of its members' deltas.
-- Neither the sweep nor the old dashboards could answer this: the sweep measures
-- both sides and never joins them.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sim_gold_rune_set_synergy
(
    -- The grain: one row per (build, target), same as the fact. Not per build -- a
    -- build is measured against every target, and summing a set's members per build
    -- rather than per matchup over-counts by the number of targets.
    result_id                BIGINT,
    build_id                 BIGINT,
    run_id                   BIGINT NOT NULL,
    role                     TEXT,
    weapon_profile_key       TEXT,
    weapon_roll              TEXT,
    target_role              TEXT,
    target_armor             TEXT,
    provenance               TEXT,
    derived_skill            TEXT,
    derived_skill_level      INTEGER,
    weapon_key               TEXT,
    rune_set_key             TEXT,
    rune_count               INTEGER,
    dps_sustained            DOUBLE PRECISION,
    ttk_s                    DOUBLE PRECISION,
    base_dps                 DOUBLE PRECISION,
    base_ttk_s               DOUBLE PRECISION,
    set_dps_delta            DOUBLE PRECISION,
    set_ttk_delta            DOUBLE PRECISION,
    sum_individual_dps_delta DOUBLE PRECISION,
    -- Positive: the set compounds. Negative: the runes contend for the same term in
    -- the damage pipeline and stacking them is a trap.
    synergy_dps              DOUBLE PRECISION,
    runes_attributed         BIGINT,
    -- False when a member rune was never measured on its own here, which makes the
    -- sum incomplete and the synergy figure an artefact. Filter on it.
    attribution_complete     BOOLEAN
);

CREATE INDEX IF NOT EXISTS idx_gold_rune_set_synergy_run
    ON sim_gold_rune_set_synergy (run_id, rune_set_key);

-- ---------------------------------------------------------------------------
-- Skill contribution, measured and derived side by side. provenance is what keeps
-- that honest.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sim_gold_skill_contribution
(
    run_id                BIGINT NOT NULL,
    role                  TEXT,
    weapon_profile_key    TEXT,
    weapon_roll           TEXT,
    target_role           TEXT,
    target_armor          TEXT,
    rune_set_key          TEXT,
    provenance            TEXT,
    weapon_key            TEXT,
    weapon_damage_applied DOUBLE PRECISION,
    -- The carrier's speed, so "is this effect worth more on a fast weapon" is answerable
    -- from this table alone. Declared rather than measured: swings_per_second is null on
    -- a row that never killed, and an interaction is least visible where the fight did
    -- not resolve. Both are carried; sim_gold_effect_interaction correlates on the first.
    weapon_attack_speed_applied DOUBLE PRECISION,
    swings_per_second     DOUBLE PRECISION,
    build_id              BIGINT,
    skill_name            TEXT,
    skill_level           INTEGER,
    derived_bonus_per_hit DOUBLE PRECISION,
    derived_uptime        DOUBLE PRECISION,
    dps_sustained         DOUBLE PRECISION,
    ttk_s                 DOUBLE PRECISION,
    dmg_per_hit           DOUBLE PRECISION,
    hits_to_kill          DOUBLE PRECISION,
    base_dps              DOUBLE PRECISION,
    base_ttk_s            DOUBLE PRECISION,
    base_dmg_per_hit      DOUBLE PRECISION,
    base_hits_to_kill     DOUBLE PRECISION,
    dps_delta             DOUBLE PRECISION,
    dps_delta_pct         DOUBLE PRECISION,
    ttk_delta             DOUBLE PRECISION,
    hits_saved            DOUBLE PRECISION
);

CREATE INDEX IF NOT EXISTS idx_gold_skill_contribution_run
    ON sim_gold_skill_contribution (run_id, skill_name, skill_level);

-- ---------------------------------------------------------------------------
-- Patch diff, materialised per pair rather than joined in a panel.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sim_gold_run_diff
(
    fingerprint           TEXT,
    target_role           TEXT,
    target_armor          TEXT,
    provenance            TEXT,
    derived_skill         TEXT,
    derived_skill_level   INTEGER,
    run_a                 BIGINT,
    run_b                 BIGINT,
    role                  TEXT,
    weapon_key            TEXT,
    rune_set_key          TEXT,
    dps_a                 DOUBLE PRECISION,
    dps_b                 DOUBLE PRECISION,
    ttk_a                 DOUBLE PRECISION,
    ttk_b                 DOUBLE PRECISION,
    dmg_a                 DOUBLE PRECISION,
    dmg_b                 DOUBLE PRECISION,
    kill_rate_a           DOUBLE PRECISION,
    kill_rate_b           DOUBLE PRECISION,
    -- 'both', 'only_a', 'only_b'. Reported rather than inner-joined away: a large
    -- non-overlap means the remaining deltas are a biased sample.
    overlap               TEXT,
    dps_delta             DOUBLE PRECISION,
    dps_delta_pct         DOUBLE PRECISION,
    ttk_delta             DOUBLE PRECISION,
    rank_by_abs_dps_delta INTEGER,
    diff_key              TEXT
);

CREATE INDEX IF NOT EXISTS idx_gold_run_diff_key ON sim_gold_run_diff (diff_key, overlap);

-- ---------------------------------------------------------------------------
-- The audit log. Row counts per dataset per layer, plus every expectation and its
-- observed value. This is the "auditable" half of the medallion promise: a figure
-- that looks wrong is traceable to the layer that produced it, and an assumption
-- that stopped holding has a row saying when.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sim_gold_data_quality
(
    realm           INTEGER,
    run_id          BIGINT,
    -- Idempotency token for one pipeline invocation. Airflow passes its run id.
    pipeline_run_id TEXT,
    checked_at      TIMESTAMPTZ,
    layer           TEXT,
    dataset         TEXT,
    check_name      TEXT,
    severity        TEXT,
    passed          BOOLEAN,
    observed        DOUBLE PRECISION,
    detail          TEXT
);

CREATE INDEX IF NOT EXISTS idx_gold_dq_run ON sim_gold_data_quality (run_id, checked_at DESC);
CREATE INDEX IF NOT EXISTS idx_gold_dq_failures ON sim_gold_data_quality (passed, severity);

-- ---------------------------------------------------------------------------
-- Whether an effect compounds or contends with the weapon carrying it.
--
-- The "do these two things mix" question. Attack speed barely varies within a weapon
-- -- SimStatRoll moves every stat to the same corner at once, so a MIN row is slower
-- AND weaker and the two cannot be separated -- so the axis is the carrier weapon and
-- the question is whether an effect's marginal value tracks how fast that weapon
-- swings. An on-hit effect fires once per swing and should compound; a flat percentage
-- buff should track weapon damage instead.
--
-- speed_corr and damage_corr are both here because reading either alone is a mistake:
-- fast weapons hit softer by construction, so speed and damage are correlated across
-- the catalog and an effect that really tracks damage shows a spurious negative speed
-- correlation. `tracks` names which one dominates.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sim_gold_effect_interaction
(
    run_id                BIGINT NOT NULL,
    -- 'rune' or 'skill'. Not interchangeable as design levers -- a rune is a drop and a
    -- skill is twelve points -- so they are never averaged together.
    effect_kind           TEXT,
    effect_key            TEXT,
    -- Null for runes, which have no level.
    effect_level          INTEGER,
    provenance            TEXT,
    target_role           TEXT,
    target_armor          TEXT,
    matchups              BIGINT,
    -- Distinct carrier weapons behind the correlation. Under 3 and the coefficient is
    -- noise, which correlation_reliable states rather than leaving to the reader.
    weapons               BIGINT,
    dps_delta_mean        DOUBLE PRECISION,
    dps_delta_pct_mean    DOUBLE PRECISION,
    ttk_delta_mean        DOUBLE PRECISION,
    hits_saved_mean       DOUBLE PRECISION,
    speed_corr            DOUBLE PRECISION,
    damage_corr           DOUBLE PRECISION,
    slowest_carrier       DOUBLE PRECISION,
    fastest_carrier       DOUBLE PRECISION,
    correlation_reliable  BOOLEAN,
    -- compounds / contends / indifferent / too few weapons / speed did not vary.
    interaction           TEXT,
    -- 'attack speed' or 'weapon damage': which axis the effect tracks more strongly.
    tracks                TEXT
);

CREATE INDEX IF NOT EXISTS idx_gold_effect_interaction_run
    ON sim_gold_effect_interaction (run_id, effect_kind, effect_key);
-- The panel's own access path: reliable verdicts first, strongest interaction first.
CREATE INDEX IF NOT EXISTS idx_gold_effect_interaction_verdict
    ON sim_gold_effect_interaction (run_id, correlation_reliable, interaction);
