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


-- ---------------------------------------------------------------------------
-- Tier grid. A materialised roll-up of sim_gold_matchup at the grain the gear-ladder
-- panels compare on, and the only object in this file the pipeline does not write.
--
-- Why it exists: the tiers dashboard's scatter is one point per comparison cell, and it
-- was computing that cell from the raw fact -- eight percentile_cont sorts over 3.6M
-- rows. A single one of those takes ~40s, so the panel exceeded Grafana's SQL timeout
-- every load and rendered as empty axes with no error text. The grid is 45k rows across
-- every run; reading it is a few milliseconds.
--
-- Grain is (run, attacker role, weapon, roll, rune count, target role, target armour).
-- Attacker role is IN the grain deliberately: a median cannot be re-derived from other
-- medians, so any panel that wants to collapse a dimension has to do it on a mean. The
-- mean columns are here for exactly that, and `duels` is the weight -- weighted mean
-- over this view reproduces AVG over the fact exactly.
--
-- Only `provenance = 'measured'` rows. Duels the attacker never won are kept rather than
-- filtered: a null ttk_s drops out of the TTK percentiles on its own, but its DPS is real
-- and throwing it away biases the DPS axis upward. `no_kill_rows` counts them per cell,
-- so a cell whose medians describe a subset says so instead of looking complete.
--
-- Refreshed by the gold pipeline after every publish. By hand:
--   REFRESH MATERIALIZED VIEW CONCURRENTLY sim_gold_tier_grid;
-- (CONCURRENTLY needs the unique index below, and it exists for that reason.)
-- ---------------------------------------------------------------------------
DROP MATERIALIZED VIEW IF EXISTS sim_gold_tier_grid;
CREATE MATERIALIZED VIEW sim_gold_tier_grid AS
SELECT run_id,
       role,
       weapon_key,
       weapon_slot,
       weapon_roll,
       rune_count,
       target_role,
       target_armor,
       target_armor_set,
       target_armor_tier,
       -- Run 1 named the armoured targets role_set / role_set_max and left
       -- target_armor_set and target_armor_tier null; run 7 renamed them reinforced /
       -- reinforced_max and populates both. Classifying by shape rather than by the
       -- literal name reads either, and survives the next rename.
       CASE
           WHEN target_armor = 'none' THEN 'none'
           WHEN right(target_armor, 4) = '_max' THEN 'tier1_max'
           ELSE 'tier1'
           END                                                                AS armor_class,
       COUNT(*)                                                               AS duels,
       COUNT(DISTINCT build_id)                                               AS builds,
       AVG(target_hp)                                                         AS target_hp,
       AVG(dmg_per_hit)                                                       AS dmg_per_hit_mean,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY dmg_per_hit)               AS dmg_per_hit,
       AVG(dps_sustained)                                                     AS dps_mean,
       percentile_cont(0.1) WITHIN GROUP (ORDER BY dps_sustained)             AS dps_p10,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY dps_sustained)             AS dps,
       percentile_cont(0.9) WITHIN GROUP (ORDER BY dps_sustained)             AS dps_p90,
       AVG(dps_burst)                                                         AS dps_burst_mean,
       AVG(ttk_s)                                                             AS ttk_mean,
       percentile_cont(0.1) WITHIN GROUP (ORDER BY ttk_s)                     AS ttk_p10,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY ttk_s)                     AS ttk,
       percentile_cont(0.9) WITHIN GROUP (ORDER BY ttk_s)                     AS ttk_p90,
       AVG(hits_to_kill)                                                      AS hits_to_kill_mean,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY hits_to_kill)              AS hits_to_kill,
       AVG(swing_interval_ticks)                                              AS swing_ticks_mean,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY swing_interval_ticks)      AS swing_ticks,
       AVG(overkill_fraction)                                                 AS overkill_frac_mean,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY overkill_fraction)         AS overkill_frac,
       AVG(kill_rate)                                                         AS kill_rate,
       -- Duels excluded from every figure above because the attacker never killed.
       -- Non-zero here means the cell's medians describe a subset of what was swept.
       COUNT(*) FILTER (WHERE ttk_s IS NULL)                                  AS no_kill_rows
FROM sim_gold_matchup
WHERE provenance = 'measured'
GROUP BY 1, 2, 3, 4, 5, 6, 7, 8, 9, 10;

-- Unique over the full grain, which is both the correctness statement and what
-- REFRESH MATERIALIZED VIEW CONCURRENTLY requires.
CREATE UNIQUE INDEX IF NOT EXISTS idx_gold_tier_grid_key
    ON sim_gold_tier_grid (run_id, role, weapon_key, weapon_roll, rune_count,
                           target_role, target_armor);
-- The scatter's access path: one run, filtered by roll / target / rune count.
CREATE INDEX IF NOT EXISTS idx_gold_tier_grid_scan
    ON sim_gold_tier_grid (run_id, weapon_roll, target_role, rune_count);
-- The ladder scan the Q2 and Q3 tables walk: one weapon, one rung.
CREATE INDEX IF NOT EXISTS idx_gold_tier_grid_ladder
    ON sim_gold_tier_grid (run_id, weapon_key, armor_class, weapon_roll, rune_count);


-- ---------------------------------------------------------------------------
-- sim_gold_tier_extreme -- the same roll-up, over the DERIVED half of the fact.
--
-- sim_gold_tier_grid deliberately keeps only provenance = 'measured', because a
-- rung of the gear ladder is a property of gear and averaging a skill bonus into
-- it would move the rung for reasons that have nothing to do with gear. But the
-- extremes of the ladder are exactly where the skill rows live: the sweep's
-- derived half is Backstab at levels 1-3, folded into dmg_per_hit as a flat
-- bonus per hit. On a 6.5 damage axe that bonus is worth more than a whole
-- weapon tier, which is not visible anywhere in the measured view.
--
-- Separate view rather than a wider grain on the grid: adding derived_skill_level
-- to the grid would quadruple it and silently change every panel that reads it,
-- and the skill question is asked by two panels, not by all of them.
DROP MATERIALIZED VIEW IF EXISTS sim_gold_tier_extreme;
CREATE MATERIALIZED VIEW sim_gold_tier_extreme AS
SELECT run_id,
       role,
       weapon_key,
       weapon_roll,
       rune_count,
       target_role,
       target_armor,
       CASE
           WHEN target_armor = 'none' THEN 'none'
           WHEN right(target_armor, 4) = '_max' THEN 'tier1_max'
           ELSE 'tier1'
           END                                                              AS armor_class,
       COALESCE(derived_skill, 'none')                                      AS skill,
       COALESCE(derived_skill_level, 0)                                     AS skill_level,
       COUNT(*)                                                             AS duels,
       AVG(target_hp)                                                       AS target_hp,
       AVG(derived_bonus_per_hit)                                           AS skill_bonus,
       AVG(derived_uptime)                                                  AS skill_uptime,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY dmg_per_hit)             AS dmg_per_hit,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY dps_sustained)           AS dps,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY ttk_s)                   AS ttk,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY hits_to_kill)            AS hits_to_kill,
       COUNT(*) FILTER (WHERE ttk_s IS NULL)                                AS no_kill_rows
FROM sim_gold_matchup
GROUP BY 1, 2, 3, 4, 5, 6, 7, 8, 9, 10;

CREATE UNIQUE INDEX IF NOT EXISTS idx_gold_tier_extreme_key
    ON sim_gold_tier_extreme (run_id, role, weapon_key, weapon_roll, rune_count,
                              target_role, target_armor, skill, skill_level);
CREATE INDEX IF NOT EXISTS idx_gold_tier_extreme_scan
    ON sim_gold_tier_extreme (run_id, weapon_roll, rune_count, skill_level, armor_class);


-- ---------------------------------------------------------------------------
-- sim_gold_weapon_damage -- one row per weapon x roll x rune set x skill state.
--
-- The substrate for the per-weapon damage distribution. Every rune permutation
-- the sweep ran is a row, so a box plot over this view is the real measured
-- spread of a weapon rather than a model of it: 15 weapons x 3 rolls x 1093
-- rune sets x 4 skill states.
--
-- Collapsed over target: dmg_per_hit and swing interval do not depend on who is
-- being hit (armour is pure health), so keeping target in the grain would
-- multiply the view by six and put six identical damage numbers in every box.
DROP MATERIALIZED VIEW IF EXISTS sim_gold_skill_damage;
DROP MATERIALIZED VIEW IF EXISTS sim_gold_weapon_damage;
CREATE MATERIALIZED VIEW sim_gold_weapon_damage AS
SELECT run_id,
       weapon_key,
       weapon_slot,
       weapon_roll,
       rune_set_key,
       MIN(runes)                                                   AS runes,
       rune_count,
       COALESCE(derived_skill, 'none')                              AS skill,
       COALESCE(derived_skill_level, 0)                             AS skill_level,
       AVG(derived_bonus_per_hit)                                   AS skill_bonus,
       COUNT(*)                                                     AS duels,
       COUNT(DISTINCT role)                                         AS roles,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY dmg_per_hit)     AS dmg_per_hit,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY dps_sustained)   AS dps,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY dps_burst)       AS dps_burst,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY swings_per_second)    AS swings_per_second,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY swing_interval_ticks) AS swing_ticks
FROM sim_gold_matchup
GROUP BY 1, 2, 3, 4, 5, 7, 8, 9;

CREATE UNIQUE INDEX IF NOT EXISTS idx_gold_weapon_damage_key
    ON sim_gold_weapon_damage (run_id, weapon_key, weapon_roll, rune_set_key, skill, skill_level);
CREATE INDEX IF NOT EXISTS idx_gold_weapon_damage_scan
    ON sim_gold_weapon_damage (run_id, weapon_roll, rune_count, skill_level);


-- ---------------------------------------------------------------------------
-- gold_skill_damage -- every damaging skill in the catalog, per level.
--
-- Parsed out of grafana_config rather than hand-listed. The existing
-- dps_all_valid_builds dashboard enumerates fourteen skills as a literal CROSS
-- JOIN of VALUES lists inside the panel SQL; the catalog holds roughly forty
-- damaging skills, and that list goes stale the moment a skill is added.
--
-- Damage keys are matched on the WHOLE final segment, never with LIKE. The
-- catalog contains baseDamage, baseDamageIncrease, baseDamageReduction,
-- baseDamageReduced, baseDamagePercent and baseExtraDamagePerBlock, and a
-- LIKE '%baseDamage%' would file a damage RESISTANCE as a damage source.
--
-- `delivery` is derived, not declared: a skill with a cooldown key is an ability
-- that fires on that cooldown, one without is a passive that rides every swing.
-- That rule reproduces the hand classification in dps_all_valid_builds exactly
-- on all fourteen skills it covers, which is the reason to trust it on the other
-- twenty-odd that it does not.
DROP MATERIALIZED VIEW IF EXISTS gold_skill_damage CASCADE;
CREATE MATERIALIZED VIEW gold_skill_damage AS
WITH kv AS (SELECT realm,
                   split_part(config_key, '.', 2)                       AS role,
                   split_part(config_key, '.', 3)                       AS skill,
                   split_part(config_key, '.', 4)                       AS param,
                   MAX(config_value::numeric)                           AS val
            FROM grafana_config
            WHERE plugin = 'Champions'
              AND config_file = 'skills/skills'
              AND config_key ~ '^skills\.[^.]+\.[^.]+\.[^.]+$'
              AND config_value ~ '^-?[0-9]+(\.[0-9]+)?$'
            GROUP BY 1, 2, 3, 4),
     agg AS (SELECT realm, role, skill,
                    -- Priority order, not a sum: these are alternative spellings of
                    -- "the damage this skill does", and several skills declare two.
                    COALESCE(MAX(val) FILTER (WHERE param = 'baseDamage'),
                             MAX(val) FILTER (WHERE param = 'damage'),
                             MAX(val) FILTER (WHERE param = 'baseMaxDamage'),
                             MAX(val) FILTER (WHERE param = 'baseDamagePerCharge'),
                             MAX(val) FILTER (WHERE param = 'damagePerSecond'),
                             MAX(val) FILTER (WHERE param = 'baseBonusDamage'),
                             MAX(val) FILTER (WHERE param = 'baseExtraDamage'),
                             MAX(val) FILTER (WHERE param = 'arrowDamage'))  AS base_damage,
                    COALESCE(MAX(val) FILTER (WHERE param = 'damageIncreasePerLevel'),
                             MAX(val) FILTER (WHERE param = 'maxDamageIncreasePerLevel'),
                             MAX(val) FILTER (WHERE param = 'damagePerLevel'),
                             MAX(val) FILTER (WHERE param = 'damageIncrementPerLevel'),
                             MAX(val) FILTER (WHERE param = 'maxDamageIncrementPerLevel'),
                             MAX(val) FILTER (WHERE param = 'bonusDamageIncreasePerLevel'),
                             MAX(val) FILTER (WHERE param = 'extraDamageIncreasePerLevel'),
                             MAX(val) FILTER (WHERE param = 'baseBonusDamageIncreasePerLevel'),
                             0)                                              AS per_level,
                    MAX(val) FILTER (WHERE param = 'cooldown')                AS cooldown,
                    COALESCE(MAX(val) FILTER (WHERE param = 'cooldownDecreasePerLevel'), 0)
                                                                              AS cd_per_level,
                    MAX(val) FILTER (WHERE param = 'maxlevel')                 AS max_level
             FROM kv GROUP BY 1, 2, 3)
SELECT a.realm,
       upper(a.role)                                       AS role,
       a.skill,
       lvl.level                                           AS skill_level,
       a.base_damage,
       a.per_level,
       a.base_damage + a.per_level * (lvl.level - 1)       AS damage,
       -- Floored at half a second: the linear decrease runs a few skills negative
       -- by level 5, and a negative cooldown is an infinite DPS.
       CASE WHEN a.cooldown IS NULL THEN NULL
            ELSE GREATEST(0.5, a.cooldown - a.cd_per_level * (lvl.level - 1)) END AS cooldown,
       CASE WHEN a.cooldown IS NULL THEN 'per_hit' ELSE 'on_cooldown' END       AS delivery,
       CASE WHEN a.cooldown IS NULL THEN NULL
            ELSE (a.base_damage + a.per_level * (lvl.level - 1))
                     / GREATEST(0.5, a.cooldown - a.cd_per_level * (lvl.level - 1)) END AS dps_added
FROM agg a
         CROSS JOIN LATERAL (SELECT generate_series(1, COALESCE(a.max_level::int, 5)) AS level) lvl
WHERE a.base_damage IS NOT NULL
  AND a.base_damage > 0;

CREATE UNIQUE INDEX IF NOT EXISTS idx_gold_skill_damage_key
    ON gold_skill_damage (realm, role, skill, skill_level);


-- ---------------------------------------------------------------------------
-- gold_compose_damage -- the game's damage composition rule, once.
--
-- Transcribed from DamageEvent.getModifiedDamage. That method walks four phases in
-- priority order, and the useful fact about it is that ORDER DOES NOT MATTER: each
-- phase is a sum or a product, so the priority sort only decides what the damage
-- log prints, never what the number is. That is what makes projection EXACT rather
-- than approximate -- a composed row is the number the game would have produced,
-- not an estimate of it.
--
-- The four phases collapse to:
--
--     final = (base * SUM(amplifying multipliers) + SUM(all flats)) * PROD(reductive multipliers)
--
-- with the multiplier sum defaulting to 1 when nothing amplifies. The asymmetry is
-- real and is in DamageOperator's own doc: amplifying multipliers stack ADDITIVELY
-- (the first multiplies, each later one adds base*operand, which sums to
-- base * SUM(operand)), while reductive ones stack MULTIPLICATIVELY. Amplifying and
-- reductive flats are two phases but both are additions onto the same running total,
-- so they fold into one sum here.
--
-- Why this is a function and not inlined: it is the join between what the sweep
-- measures and every projected row that never got a duel. Inlined twice it would
-- drift, and a drift here is silent -- every projected number stays plausible and
-- all of them are wrong.
--
-- IMMUTABLE so Postgres can fold it into an index or a generated column, and because
-- it genuinely is: same inputs, same damage, no catalog reads.
CREATE OR REPLACE FUNCTION gold_compose_damage(base            numeric,
                                               amp_mult_sum    numeric,
                                               amp_mult_count  integer,
                                               flat_sum        numeric,
                                               red_mult_prod   numeric)
    RETURNS numeric
    LANGUAGE sql
    IMMUTABLE
    PARALLEL SAFE
AS $$
    -- GREATEST(0, ...) mirrors the Math.max(0, damage) the method ends on: the pipeline
    -- clamps at zero rather than letting a reduction stack past it into healing.
SELECT GREATEST(0, (base * CASE WHEN COALESCE(amp_mult_count, 0) = 0
                                    THEN 1
                                    ELSE COALESCE(amp_mult_sum, 1) END
                        + COALESCE(flat_sum, 0))
                   * COALESCE(red_mult_prod, 1));
$$;

COMMENT ON FUNCTION gold_compose_damage(numeric, numeric, integer, numeric, numeric) IS
    'DamageEvent.getModifiedDamage as a closed form. Order-independent: every phase is a '
        'sum or a product, so priority affects only the damage log. Amplifying multipliers '
        'stack additively, reductive ones multiplicatively.';


-- ---------------------------------------------------------------------------
-- gold_ramp_total -- total bonus from a ramping per-hit skill over `hits` swings.
--
-- The third class of modifier, and the one that breaks the closed form in
-- gold_compose_damage's header. Combo Attack is the reference case: its operand is
-- not a property of (skill, level) at all, it is a function of how many times you
-- have already hit the same target. Hit 1 applies ZERO -- the modifier is added
-- before the counter increments -- hit 2 applies `inc`, hit 3 `2*inc`, capped at
-- `cap`. Averaging that into a single "damage" number is wrong in both directions:
-- too high for a two-hit kill, too low for a long one.
--
-- Still closed form, just over hit index rather than per hit. With j = i-1 running
-- 0..hits-1 the contribution is min(j*inc, cap), so the total is an arithmetic
-- series up to the cap and a rectangle after it:
--
--     t     = LEAST(hits - 1, floor(cap / inc))     -- last hit still ramping
--     total = inc * t * (t + 1) / 2 + cap * (hits - 1 - t)
--
-- Which matters because `hits` is exactly what varies across the weapon axis this
-- is meant to project over. A ramping skill is therefore NOT weapon-independent and
-- NOT a percentage: it is worth more on a weapon that needs more swings, which is
-- the opposite of how a flat bonus behaves relative to DPS.
--
-- Two things this deliberately does NOT model, because neither is arithmetic:
--
--   * The DURATION GATE. Combo Attack drops the ramp if `duration` elapses between
--     hits (1.0s, and no per-level increase). A weapon whose swing interval exceeds
--     that never ramps at all and this function must not be called for it. It is a
--     step, not a curve, and it lands where several weapons' swing intervals sit --
--     so it is a cliff the weapon axis can fall off, not a correction.
--   * TARGET SWITCHING and SKILL CANCELLATION. The ramp resets on a new target, and
--     ComboAttack.shouldCancelCombo drops it when the holder uses any SWORD/AXE/BOW,
--     toggle or interact skill. That is a skill-on-skill interaction, so a
--     ONE_AT_A_TIME sweep measures this skill's best case by construction.
CREATE OR REPLACE FUNCTION gold_ramp_total(inc numeric, cap numeric, hits integer)
    RETURNS numeric
    LANGUAGE sql
    IMMUTABLE
    PARALLEL SAFE
AS $$
SELECT CASE
           WHEN hits IS NULL OR hits < 1 OR inc IS NULL OR inc <= 0 OR cap IS NULL THEN 0
           ELSE (SELECT inc * t * (t + 1) / 2 + cap * (hits - 1 - t)
                 FROM (SELECT LEAST(hits - 1, floor(cap / inc)::int) AS t) x)
           END;
$$;

COMMENT ON FUNCTION gold_ramp_total(numeric, numeric, integer) IS
    'Total bonus from a ramping per-hit skill (Combo Attack) over n hits. First hit '
        'contributes zero. Caller must check the duration gate: a weapon swinging slower '
        'than the ramp window never ramps and this returns a number it should not.';


-- ---------------------------------------------------------------------------
-- sim_gold_skill_damage -- every skill against every measured weapon and rune set.
--
-- The per-skill counterpart of sim_gold_weapon_damage: one row per skill x level
-- x weapon damage signature, so a box plot over it shows what a skill is worth
-- across the whole equipment space instead of on one reference weapon.
--
-- The weapon and rune half is MEASURED -- it comes from the sweep -- and only the
-- skill half is modelled from config. That split is deliberate: the sim ran every
-- rune permutation and settles what a rune does, while it only ever modelled one
-- skill (Backstab), so config is the only source for the other forty.
--
-- Joined on the damage SIGNATURE (weapon, roll, damage, swing speed) rather than
-- on the rune set, because runes that touch neither damage nor speed produce an
-- identical row. Collapsing them first turns a 55k x 200 cross join into a 2k x
-- 200 one, and `rune_sets` keeps the count of what was folded together.
CREATE MATERIALIZED VIEW sim_gold_skill_damage AS
WITH sig AS (SELECT run_id, weapon_key, weapon_slot, weapon_roll,
                    ROUND(dmg_per_hit::numeric, 3)       AS weapon_dmg,
                    ROUND(swings_per_second::numeric, 4) AS sps,
                    -- Averaged, not grouped on. Rune sets that share a damage and a
                    -- swing rate can still differ slightly in measured DPS -- momentum
                    -- ramps within a duel -- and grouping on DPS would split a
                    -- signature into near-duplicates that mean nothing to a reader.
                    ROUND(AVG(dps)::numeric, 3)          AS weapon_dps,
                    MIN(rune_count)                      AS rune_count_min,
                    MAX(rune_count)                      AS rune_count_max,
                    COUNT(*)                             AS rune_sets,
                    MIN(rune_set_key)                    AS example_rune_set
             FROM sim_gold_weapon_damage
             WHERE skill_level = 0
             GROUP BY 1, 2, 3, 4, 5, 6)
SELECT s.run_id,
       s.weapon_key,
       s.weapon_slot,
       s.weapon_roll,
       s.weapon_dmg,
       s.weapon_dps,
       s.sps,
       s.rune_count_min,
       s.rune_count_max,
       s.rune_sets,
       s.example_rune_set,
       k.role,
       k.skill,
       k.skill_level,
       k.delivery,
       k.damage                                                   AS skill_damage,
       k.cooldown                                                 AS skill_cooldown,
       -- Composed through gold_compose_damage rather than added inline. Today every
       -- skill reaching here is a flat one, so this is literally weapon_dmg + damage --
       -- but the moment a percentage skill has measured coefficients it arrives as an
       -- amplifying multiplier instead, and the call site must already be the real rule
       -- rather than a `+` somebody has to remember to replace.
       CASE WHEN k.delivery = 'per_hit'
                THEN gold_compose_damage(s.weapon_dmg, NULL, 0, k.damage, NULL)
            ELSE s.weapon_dmg END                                 AS dmg_per_hit,
       CASE WHEN k.delivery = 'per_hit'
                THEN gold_compose_damage(s.weapon_dmg, NULL, 0, k.damage, NULL) * s.sps
            ELSE s.weapon_dps + k.damage / k.cooldown END         AS dps,
       CASE WHEN k.delivery = 'per_hit' THEN k.damage * s.sps
            ELSE k.damage / k.cooldown END                        AS dps_added
FROM sig s
         CROSS JOIN gold_skill_damage k;

CREATE UNIQUE INDEX IF NOT EXISTS idx_gold_skill_damage_x_key
    ON sim_gold_skill_damage (run_id, weapon_key, weapon_roll, weapon_dmg, sps,
                              role, skill, skill_level);
CREATE INDEX IF NOT EXISTS idx_gold_skill_damage_x_scan
    ON sim_gold_skill_damage (run_id, skill, skill_level, weapon_roll);


-- ---------------------------------------------------------------------------
-- gold_skill_coverage -- which damaging skills have actually been swept, and which
-- are only ever modelled from config.
--
-- The Sim -- Damage Distributions dashboard draws its skill half from
-- gold_skill_damage, which is parsed out of the config catalog rather than
-- measured. That is honest but it is not a plan: nothing on the dashboard says
-- WHICH skills a sweep could confirm cheaply and which have never been fought
-- with at all. This view is that missing column, and it is the thing to watch
-- while skill sweeps land -- a measured skill flips its status here.
--
-- Measured means a build row carried the skill, not that a config key exists for
-- it. sim_build.skills is the only record of what a duel was actually fought
-- with; a skill present in grafana_config and absent here was never equipped.
--
-- Levels are compared as a SET, not as a max. A skill swept only at level 1 is
-- not a measured skill -- the per-level slope is most of what a balance question
-- asks about -- so levels_measured and levels_declared are both kept and
-- coverage_pct is their ratio.
DROP MATERIALIZED VIEW IF EXISTS gold_skill_coverage;
CREATE MATERIALIZED VIEW gold_skill_coverage AS
WITH measured AS (SELECT b.role,
                         a ->> 'skill'                            AS skill,
                         (a ->> 'allocated_level')::int           AS skill_level,
                         COUNT(DISTINCT b.run_id)                 AS runs,
                         COUNT(*)                                 AS builds,
                         MAX((a ->> 'effective_level')::int)      AS effective_level_max
                  FROM sim_build b
                           CROSS JOIN LATERAL jsonb_array_elements(b.skills) a
                  GROUP BY 1, 2, 3),
     -- Rolled up to the skill so the per-level rows above can be counted rather
     -- than joined twice.
     measured_skill AS (SELECT role, skill,
                               COUNT(*)          AS levels_measured,
                               SUM(runs)         AS run_rows,
                               SUM(builds)       AS builds,
                               MIN(skill_level)  AS level_min,
                               MAX(skill_level)  AS level_max
                        FROM measured GROUP BY 1, 2),
     -- The flat half: skills gold_skill_damage could parse a damage number for.
     flat AS (SELECT realm, role, skill,
                     COUNT(*)                        AS levels_declared,
                     MIN(damage)                     AS damage_min,
                     MAX(damage)                     AS damage_max,
                     MIN(delivery)                   AS delivery,
                     'config-hint: flat'::text       AS scaling
              FROM gold_skill_damage GROUP BY 1, 2, 3),
     -- The percentage half, which gold_skill_damage drops on the floor.
     --
     -- Skills like Sacrifice and Fortify carry no damage number at all: they apply a
     -- SkillDamageModifier.Multiplier to whatever the weapon already did. gold_skill_damage
     -- filters on `base_damage IS NOT NULL`, so every one of them is absent from the
     -- modelled panels -- present in the game, invisible on the dashboard, which is the
     -- worst of the three states.
     --
     -- They are recovered here rather than in gold_skill_damage on purpose. A percentage
     -- skill's contribution is not a number this view can compute: it depends on the weapon
     -- it multiplies, so there is no honest `damage` column to put it in. What CAN be said
     -- is that it exists and has never been measured -- which is exactly what a coverage
     -- view is for, and is the strongest possible argument for measuring it.
     --
     -- MEASURED AGAINST GROUND TRUTH AND IT DOES NOT WORK. Scored against the eleven
     -- skills that actually construct a SkillDamageModifier.Multiplier, this config-key
     -- test gets precision 1/5 and recall 1/11: it finds only `sacrifice`, invents four
     -- (cleave, shieldsmash, recall, fastrecovery -- percentages that heal or split
     -- damage rather than scale it), files `defensivestance` and `riposte` as FLAT, and
     -- misses eight entirely because they carry no percentage-shaped config key at all.
     --
     -- Kept, demoted, and renamed to `config-hint: ...` rather than deleted, because a
     -- column that says "we do not know" is useful and one that says "flat" wrongly is
     -- not. Do not build anything on this value. The fix is the plugin exporting each
     -- skill's modifier class; until then the honest reading of this view is its
     -- MEASURED columns only.
     --
     -- Detected from config keys, which is a WEAKER test than the one the game uses. The
     -- reliable classifier is Java -- SkillDamageModifier.Multiplier vs .Flat -- and it is
     -- not exported to the warehouse. The key names here are idiosyncratic (basePercentage,
     -- percent, percentageOfDamage, multiplier -- roughly one skill each), so a percentage
     -- skill spelling its key a new way lands in neither half and vanishes again. Exporting
     -- the archetype from the plugin is the real fix; this makes the gap visible meanwhile.
     pct AS (SELECT DISTINCT
                    realm,
                    upper(split_part(config_key, '.', 2))       AS role,
                    split_part(config_key, '.', 3)              AS skill
             FROM grafana_config
             WHERE plugin = 'Champions' AND config_file = 'skills/skills'
               AND split_part(config_key, '.', 4) ~*
                   '^(base)?percent(age)?([A-Z].*)?$|^percentageOfDamage$|^multiplier$'),
     declared AS (SELECT * FROM flat
                  UNION ALL
                  SELECT p.realm, p.role, p.skill,
                         1                AS levels_declared,
                         NULL::numeric    AS damage_min,
                         NULL::numeric    AS damage_max,
                         'multiplier'     AS delivery,
                         'config-hint: percent' AS scaling
                  FROM pct p
                  WHERE NOT EXISTS (SELECT 1 FROM flat f
                                    WHERE f.realm = p.realm AND f.role = p.role
                                      AND f.skill = p.skill))
SELECT d.realm,
       d.role,
       d.skill,
       d.delivery,
       d.scaling,
       d.damage_min,
       d.damage_max,
       d.levels_declared,
       COALESCE(m.levels_measured, 0)                                       AS levels_measured,
       COALESCE(m.builds, 0)                                                AS builds,
       m.level_min,
       m.level_max,
       ROUND(100.0 * COALESCE(m.levels_measured, 0) / d.levels_declared, 1) AS coverage_pct,
       -- A percentage skill with no measurement is a harder gap than a flat one with no
       -- measurement: the flat skill at least has a config number the dashboard can model,
       -- while this one has nothing at all behind it. Given its own status so the two do not
       -- read as the same backlog item.
       CASE WHEN COALESCE(m.levels_measured, 0) > 0
                 AND m.levels_measured >= d.levels_declared        THEN 'measured'
            WHEN COALESCE(m.levels_measured, 0) > 0                THEN 'partial'
            WHEN d.scaling = 'config-hint: percent'                THEN 'unverified scaling'
            ELSE 'never measured' END                                       AS status,
       -- What a one-skill-at-a-time sweep of the gap would cost, in builds. The
       -- SKILLS tier emits one build per (level x weapon x rune set x target),
       -- and the first term is the only one this view knows -- the rest is the
       -- multiplier the dashboard's own note supplies. Kept as a level count so
       -- the arithmetic stays visible rather than baked into a wrong constant.
       d.levels_declared - COALESCE(m.levels_measured, 0)                    AS levels_to_sweep
FROM declared d
         LEFT JOIN measured_skill m ON m.role = d.role AND m.skill = d.skill;

CREATE UNIQUE INDEX IF NOT EXISTS idx_gold_skill_coverage_key
    ON gold_skill_coverage (realm, role, skill);
