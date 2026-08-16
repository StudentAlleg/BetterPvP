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
    -- The MEASURED skill loadout, 'skill:level' pairs sorted and joined by '+', empty on a
    -- skill-less build. Part of the baseline key: on a SKILLS sweep the bare builds differ
    -- only by this, so without it they all collapse onto one baseline and every delta join
    -- fans out. Distinct from derived_skill, which names the skill a row was MODELLED for.
    skill_set_key               TEXT,
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
    -- Part of the baseline key: this rune's delta was measured against the bare row
    -- carrying the SAME skill loadout, not against a bare row carrying any loadout.
    skill_set_key         TEXT,
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
    -- Part of the baseline key; see sim_gold_rune_contribution.
    skill_set_key            TEXT,
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
    -- What the row IS, coalesced across both sides of the full outer join. Taking these
    -- from run A alone left every only_b row unidentifiable.
    role                  TEXT,
    weapon_key            TEXT,
    rune_set_key          TEXT,
    skill_set_key         TEXT,
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
-- sim_gold_baseline_cell -- the standing baseline, as a UNION OF MEASUREMENTS
-- rather than as a chosen run.
--
-- The question this answers is "what does the game look like right now", and the reason it
-- is a view rather than a run id is that no single run has ever measured the whole game.
-- Run 7 swept equipment across 241,290 builds and equipped no skills; runs 12-14 swept
-- skills across at most 5 weapons. Any rule that picks ONE run as the baseline throws away
-- whichever half it did not pick.
--
-- THE TRAP THIS EXISTS TO REMOVE. The engine's own baseline rule (SimResultRepository
-- .findBaselineRun) is newest-COMPLETED-wins within a (realm, scope, scenario) lane. In the
-- SKILLS/one_way lane that currently selects run 14 -- 17,568 cells, three weapons, two
-- skills -- over run 12, which measured 250,920 cells across 51 skills. A delta sweep
-- launched today would carry forward 0.4% of what has actually been measured and re-run the
-- rest, and nothing in the logs would call that a mistake. Newest is not widest.
--
-- WHY SCOPE IS NOT IN THE KEY, which is the whole design change. `scope` describes what a
-- sweep VARIED, not what a cell IS. A measurement of (build fingerprint, target) is that
-- measurement whether the sweep that produced it was enumerating weapons or skills, so
-- keying the baseline on scope keeps two runs apart that should compose. Dropping it is
-- what lets runs 7, 12, 13 and 14 be one baseline instead of three.
--
-- WHY SCENARIO IS. A MUTUAL measurement is not a substitute for a ONE_WAY one -- the
-- defender fights back, so the numbers mean different things. Runs 12 and 13 enumerate an
-- IDENTICAL build space (250,920 cells each) and differ only in scenario; collapsing them
-- would silently overwrite one with the other on every cell. Scenario stays in the key.
--
-- Rows without a config_scope_hash are excluded, which drops run 1 entirely (0 of its
-- 4,343,220 rows have one). That is correct rather than unfortunate: the hash is what a
-- delta run compares against to decide whether a cell is still valid, so a cell without one
-- can never be carried forward and does not belong in a baseline.
--
-- THE AUTHORITATIVE DROP STACK FOR THE WHOLE DAMAGE CHAIN IS HERE, deepest-first, because
-- the damage views now read the baseline rather than only sim_gold_matchup:
--
--   density -> composition -> damage_point -> skill_damage -> weapon_damage
--           -> baseline_matchup -> baseline_run -> baseline_lane -> baseline_cell
--
-- Postgres refuses to drop a view something else depends on, so this order is what lets the
-- file be re-run from the top. Do not add a drop for any of these anywhere else -- a second
-- stack that disagrees with this one is how the file stopped being re-runnable before.
DROP MATERIALIZED VIEW IF EXISTS sim_gold_damage_density;
DROP MATERIALIZED VIEW IF EXISTS sim_gold_damage_composition;
DROP MATERIALIZED VIEW IF EXISTS sim_gold_damage_point;
DROP MATERIALIZED VIEW IF EXISTS sim_gold_skill_damage;
DROP MATERIALIZED VIEW IF EXISTS sim_gold_weapon_damage;
DROP VIEW IF EXISTS sim_gold_baseline_matchup;
DROP VIEW IF EXISTS sim_gold_baseline_run;
DROP MATERIALIZED VIEW IF EXISTS sim_gold_baseline_lane;
DROP MATERIALIZED VIEW IF EXISTS sim_gold_baseline_cell;
CREATE MATERIALIZED VIEW sim_gold_baseline_cell AS
WITH ranked AS (
    SELECT run.realm,
           run.scenario ->> 'scenario'                       AS scenario,
           run.scenario ->> 'scope'                          AS measured_scope,
           b.fingerprint,
           r.target_role,
           r.target_armor,
           -- TWO different runs, and conflating them is a bug waiting for the first delta
           -- sweep. `supplied_by` is the run whose sim_result row won recency and is what a
           -- carry would copy FROM. `measured_run_id` is the run whose duels originally
           -- produced the number, which survives any number of carries via the source row's
           -- own value -- exactly the COALESCE carryForwardResults writes. They are equal on
           -- every row today only because no delta run has ever executed.
           r.run_id                                          AS supplied_by_run_id,
           COALESCE(r.measured_run_id, r.run_id)             AS measured_run_id,
           run.started_at                                    AS supplied_at,
           run.config_hash,
           run.engine_version,
           r.config_scope_hash,
           b.role,
           b.weapon                                          AS weapon_key,
           -- Bronze stores runes and skills as jsonb; the '+'-joined set keys are a gold
           -- construction and are not available this far upstream. Their text form is a
           -- stable set identity here because builds.py emits both arrays sorted, and set
           -- identity is all the lane view counts.
           b.runes::text                                     AS rune_set_key,
           NULLIF(b.skills::text, '{}')                      AS skill_set_key,
           r.dmg_per_hit,
           r.dps_sustained,
           r.ttk_s,
           r.hits_to_kill,
           -- Newest measurement of a cell wins. Ties broken on run id so the choice is
           -- deterministic across refreshes -- an unstable baseline would make every diff
           -- built on it unreproducible.
           ROW_NUMBER() OVER (
               PARTITION BY run.realm, run.scenario ->> 'scenario',
                   b.fingerprint, r.target_role, r.target_armor
               ORDER BY run.started_at DESC, r.run_id DESC)  AS recency,
           COUNT(*) OVER (
               PARTITION BY run.realm, run.scenario ->> 'scenario',
                   b.fingerprint, r.target_role, r.target_armor) AS times_measured
    FROM sim_result r
             JOIN sim_build b ON b.id = r.build_id
             JOIN sim_run run ON run.id = r.run_id
    WHERE run.status IN ('COMPLETED', 'CANCELLED')
      AND r.config_scope_hash IS NOT NULL
)
SELECT realm, scenario, measured_scope, fingerprint, target_role, target_armor,
       supplied_by_run_id, measured_run_id, supplied_at, config_hash, engine_version,
       config_scope_hash,
       role, weapon_key, rune_set_key, skill_set_key,
       dmg_per_hit, dps_sustained, ttk_s, hits_to_kill,
       times_measured,
       -- A cell measured more than once is a cell an older run also covered. Not an error --
       -- it is exactly what re-measuring after a change looks like -- but it is the set a
       -- drift check should read, because those are the cells where two runs can disagree.
       times_measured > 1 AS contested
FROM ranked
WHERE recency = 1;

CREATE UNIQUE INDEX IF NOT EXISTS idx_gold_baseline_cell_key
    ON sim_gold_baseline_cell (realm, scenario, fingerprint, target_role, target_armor);
CREATE INDEX IF NOT EXISTS idx_gold_baseline_cell_run
    ON sim_gold_baseline_cell (supplied_by_run_id);
CREATE INDEX IF NOT EXISTS idx_gold_baseline_cell_scope
    ON sim_gold_baseline_cell (realm, scenario, config_scope_hash);

COMMENT ON MATERIALIZED VIEW sim_gold_baseline_cell IS
    'The standing baseline: one row per (realm, scenario, build fingerprint, target), '
        'supplied by whichever run measured it most recently. A union of runs rather than a '
        'chosen run, because no single sweep has measured the whole game -- run 7 covers '
        'equipment with no skills, runs 12-14 cover skills on at most five weapons. Scope is '
        'deliberately NOT in the key (it describes what a sweep varied, not what a cell is); '
        'scenario is (a MUTUAL measurement is not a ONE_WAY one). config_scope_hash is what a '
        'delta sweep compares against to decide a cell is still valid.';


-- ---------------------------------------------------------------------------
-- sim_gold_baseline_lane -- what the baseline is made of, and what the engine would
-- pick instead.
--
-- The decision panel. One row per (realm, scenario, contributing run): how many cells that
-- run still supplies, how many it has had superseded, and whether the engine's own
-- newest-wins rule would select it. Reading it against sim_gold_baseline_cell is how the
-- narrow-newest-run trap becomes visible before a sweep is launched rather than after.
CREATE MATERIALIZED VIEW sim_gold_baseline_lane AS
WITH supplied AS (
    SELECT realm, scenario, supplied_by_run_id, measured_scope,
           COUNT(*)                                  AS cells_supplied,
           COUNT(*) FILTER (WHERE contested)         AS cells_contested,
           COUNT(DISTINCT fingerprint)               AS builds,
           COUNT(DISTINCT weapon_key)                AS weapons,
           COUNT(DISTINCT NULLIF(skill_set_key, '')) AS skill_sets,
           COUNT(DISTINCT rune_set_key)              AS rune_sets,
           MAX(supplied_at)                          AS supplied_at,
           COUNT(DISTINCT measured_run_id)           AS origin_runs
    FROM sim_gold_baseline_cell
    GROUP BY 1, 2, 3, 4
),
-- The engine's rule, replicated exactly so the two can be compared rather than assumed to
-- agree: newest COMPLETED (then CANCELLED) run within a (realm, scope, scenario) lane.
engine_pick AS (
    SELECT id AS run_id,
           ROW_NUMBER() OVER (
               PARTITION BY realm, scenario ->> 'scope', scenario ->> 'scenario'
               ORDER BY (status = 'COMPLETED') DESC, started_at DESC, id DESC) = 1
               AS is_engine_baseline
    FROM sim_run
    WHERE status IN ('COMPLETED', 'CANCELLED')
)
SELECT s.realm,
       s.scenario,
       s.measured_scope,
       s.supplied_by_run_id,
       s.supplied_at,
       s.origin_runs,
       s.cells_supplied,
       s.cells_contested,
       s.builds, s.weapons, s.skill_sets, s.rune_sets,
       -- Share of the whole baseline this run is carrying. The number that makes the trap
       -- obvious: the run the engine would diff against supplies 0.4% of it.
       s.cells_supplied::numeric
           / NULLIF(SUM(s.cells_supplied) OVER (PARTITION BY s.realm, s.scenario), 0)
                                                                    AS share_of_baseline,
       COALESCE(e.is_engine_baseline, false)                         AS is_engine_baseline
FROM supplied s
         LEFT JOIN engine_pick e ON e.run_id = s.supplied_by_run_id
ORDER BY s.realm, s.scenario, s.cells_supplied DESC;

CREATE UNIQUE INDEX IF NOT EXISTS idx_gold_baseline_lane_key
    ON sim_gold_baseline_lane (realm, scenario, supplied_by_run_id);

COMMENT ON MATERIALIZED VIEW sim_gold_baseline_lane IS
    'What the standing baseline is made of: one row per run still supplying cells to it, '
        'with the share it carries and whether the engine''s newest-wins rule would select '
        'it as THE baseline for a delta sweep. Where is_engine_baseline sits on a run with a '
        'small share_of_baseline, a delta run launched today would re-measure everything the '
        'other runs already know.';


-- ---------------------------------------------------------------------------
-- sim_gold_baseline_run -- the standing baseline, addressable as if it were a run.
--
-- sim_gold_baseline_cell settles WHAT the baseline is. This makes it SELECTABLE. Every
-- damage view, every dashboard variable and every panel is keyed on run_id, so a baseline
-- that is not a run id is a baseline no chart can be pointed at -- which is exactly what
-- the dashboard showed: a run picker listing runs 1, 7, 12, 13, 14 and no way to ask for
-- the thing the whole baseline design was built to produce.
--
-- The id is NEGATIVE and derived, never stored: -(realm * 10 + scenario ordinal). Negative
-- because sim_run.id is a positive identity column, so the two spaces can never collide and
-- a stray join to sim_run returns nothing rather than the wrong run. Derived because a
-- stored id would need a migration and a writer, and the baseline is a projection of what
-- has already been measured -- it has no existence of its own to record.
--
-- ONE PSEUDO-RUN PER (realm, scenario), not one overall. Scenario is in the baseline key
-- for the reason given above -- a MUTUAL measurement is not a substitute for a ONE_WAY one
-- -- and collapsing the two here would undo that at the last step and put both in one box.
CREATE OR REPLACE VIEW sim_gold_baseline_run AS
WITH lane AS (
    SELECT c.realm,
           c.scenario,
           COUNT(*)                                              AS cells,
           COUNT(DISTINCT c.fingerprint)                         AS builds,
           COUNT(DISTINCT c.measured_run_id)                     AS origin_runs,
           -- The runs the baseline is standing on, named. A reader picking "baseline" is
           -- entitled to know it is reading runs 7 + 12 + 14 and not one sweep.
           string_agg(DISTINCT c.measured_run_id::text, '+'
                      ORDER BY c.measured_run_id::text)          AS origin_run_ids,
           array_agg(DISTINCT c.measured_run_id)                 AS origin_run_array,
           MAX(c.supplied_at)                                    AS newest_supplied_at,
           MIN(c.supplied_at)                                    AS oldest_supplied_at,
           COUNT(DISTINCT c.engine_version)                      AS engine_versions
    FROM sim_gold_baseline_cell c
    GROUP BY 1, 2
),
-- BALANCE CONFIG, NOT sim_run.config_hash, and the difference is the whole point of this
-- CTE. config_hash digests the SWEEP -- its scope, its build space, its parameters -- so it
-- is different on every run by construction: runs 7, 12, 13 and 14 carry four distinct
-- hashes while all four were swept against balance config 3dd5fc5c9dc758ff. Counting
-- config_hash here made the picker label every baseline "configs BLENDED", which is a
-- warning that fires always and therefore means nothing.
--
-- scenario->>'balance_config' is the digest of the skill and item config the DAMAGE came
-- from, which is the thing a reader needs to know is uniform before diffing against the
-- baseline. Read off the small set of contributing runs rather than off the 4.1M cells.
cfg AS (
    SELECT l.realm, l.scenario,
           COUNT(DISTINCT r.scenario ->> 'balance_config')       AS balance_configs,
           string_agg(DISTINCT r.scenario ->> 'balance_config', ', ')
                                                                 AS balance_config_ids
    FROM lane l
             JOIN sim_run r ON r.id = ANY (l.origin_run_array)
    GROUP BY 1, 2
)
SELECT -(l.realm * 10 + CASE l.scenario WHEN 'one_way' THEN 1 WHEN 'mutual' THEN 2
                                        ELSE 9 END)              AS run_id,
       l.realm,
       l.scenario,
       l.cells,
       l.builds,
       l.origin_runs,
       l.origin_run_ids,
       l.newest_supplied_at,
       l.oldest_supplied_at,
       -- Config AGREEMENT, not config identity. When this is > 1 the baseline composes runs
       -- swept against different balance configs, so it is a blend of more than one version
       -- of the game and a diff against it is comparing to more than one thing.
       c.balance_configs,
       l.engine_versions,
       -- Appended last rather than beside balance_configs, and that is not cosmetic: this
       -- view sits under sim_gold_baseline_matchup and therefore under the whole damage
       -- chain, so it can only ever be changed by CREATE OR REPLACE -- which appends columns
       -- and refuses to reorder or rename them. Dropping it to tidy the column order would
       -- mean dropping five materialized views and rebuilding ~25 minutes of them.
       c.balance_config_ids
FROM lane l
         JOIN cfg c ON c.realm = l.realm AND c.scenario = l.scenario;

COMMENT ON VIEW sim_gold_baseline_run IS
    'The standing baseline addressed as a run: one synthetic negative run_id per (realm, '
        'scenario), so a dashboard keyed on run_id can select the baseline the same way it '
        'selects a sweep. The id is derived -(realm*10 + scenario ordinal), never stored, '
        'and negative so it can never collide with sim_run.id. balance_configs > 1 means the '
        'baseline blends runs swept under different SKILL/ITEM configs -- note that is '
        'scenario->>''balance_config'', not sim_run.config_hash, which digests the sweep and '
        'differs on every run by construction.';


-- ---------------------------------------------------------------------------
-- sim_gold_baseline_matchup -- the baseline's rows, shaped like the fact.
--
-- The bridge that lets sim_gold_weapon_damage aggregate the baseline with exactly the SQL
-- it already uses for a run. sim_gold_baseline_cell resolves WHICH measurement wins each
-- cell but carries only the four numbers the delta machinery needs; the damage views need
-- the roll, the swing speed, the burst figure and the rune names, which live on the fact.
-- So the cell resolves the choice and this projects the chosen fact row.
--
-- MEASURED ONLY, and that is a real exclusion worth stating. sim_gold_matchup also holds
-- `derived` rows -- the modelled Backstab overlay, 1.77M of them on run 7 alone -- and the
-- baseline drops every one. sim_gold_baseline_cell is built from sim_result, which is duels
-- that were actually fought, so a modelled row has no cell to be chosen for. The
-- consequence is visible and intended: on the baseline the weapon axis carries a measured
-- skill or no skill, never a modelled one. A baseline that mixed the two would be a claim
-- about the game supported half by measurement and half by arithmetic over config.
--
-- A PLAIN VIEW, not materialized. Every row here is already stored twice -- once in
-- sim_gold_matchup and once as a key in sim_gold_baseline_cell -- and materializing 4.1M
-- more copies to feed one aggregate that runs on refresh would spend ~2 GB to save a join
-- nothing reads interactively.
CREATE OR REPLACE VIEW sim_gold_baseline_matchup AS
SELECT r.run_id,
       c.realm,
       c.scenario,
       c.supplied_by_run_id,
       c.measured_run_id,
       c.config_scope_hash,
       c.times_measured,
       c.contested,
       m.fingerprint, m.role,
       m.weapon_key, m.weapon_slot, m.weapon_profile_key, m.weapon_roll,
       m.rune_set_key, m.runes, m.rune_count,
       m.skill_count, m.skill_set_key,
       m.derived_skill, m.derived_skill_level, m.derived_bonus_per_hit,
       m.target_role, m.target_armor, m.target_hp,
       m.dmg_per_hit, m.dps_sustained, m.dps_burst,
       m.swings_per_second, m.swing_interval_ticks,
       m.ttk_s, m.hits_to_kill, m.kill_rate
FROM sim_gold_baseline_cell c
         JOIN sim_gold_baseline_run r ON r.realm = c.realm AND r.scenario = c.scenario
         JOIN sim_gold_matchup m
              ON m.run_id = c.supplied_by_run_id
                  AND m.fingerprint = c.fingerprint
                  AND m.target_role = c.target_role
                  AND m.target_armor = c.target_armor
                  AND m.provenance = 'measured';

COMMENT ON VIEW sim_gold_baseline_matchup IS
    'The standing baseline projected back onto the fact: each winning cell joined to the '
        'sim_gold_matchup row that produced it, stamped with the synthetic baseline run_id. '
        'Lets the damage views aggregate the baseline with the same SQL they use for a run. '
        'Measured rows only -- the modelled overlay has no cell to be chosen for, so on the '
        'baseline a weapon carries a measured skill or none.';


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
--
-- SWEPT RUNS AND THE STANDING BASELINE, in one view, distinguished only by run_id. The
-- baseline arrives as sim_gold_baseline_matchup under a negative synthetic id, so every
-- view built on this one -- damage_point, density, composition, skill_damage -- can serve
-- the baseline without a line of new code, and a panel selects it by changing $run. That
-- is the whole reason the baseline was projected back onto the fact rather than given its
-- own parallel set of damage views, which would have been five more views that could
-- disagree with these five.
--
-- The cost is honest and worth naming: the baseline's rows are largely run 7's rows
-- again, so this view and everything downstream carry them twice. It buys a baseline that
-- is selectable everywhere instead of readable in one panel.
--
-- Drops for this view and its dependents live in ONE stack, at sim_gold_baseline_cell.
CREATE MATERIALIZED VIEW sim_gold_weapon_damage AS
WITH source AS (
    SELECT run_id, weapon_key, weapon_slot, weapon_roll, rune_set_key, runes, rune_count,
           derived_skill, derived_skill_level, derived_bonus_per_hit,
           skill_count, skill_set_key, role,
           dmg_per_hit, dps_sustained, dps_burst, swings_per_second, swing_interval_ticks
    FROM sim_gold_matchup
    UNION ALL
    SELECT run_id, weapon_key, weapon_slot, weapon_roll, rune_set_key, runes, rune_count,
           derived_skill, derived_skill_level, derived_bonus_per_hit,
           skill_count, skill_set_key, role,
           dmg_per_hit, dps_sustained, dps_burst, swings_per_second, swing_interval_ticks
    FROM sim_gold_baseline_matchup
)
SELECT run_id,
       weapon_key,
       weapon_slot,
       weapon_roll,
       rune_set_key,
       MIN(runes)                                                   AS runes,
       rune_count,
       -- A MEASURED skill has to reach this axis, and used to not.
       --
       -- This was `COALESCE(derived_skill, 'none')`, and derived_skill is set only on a
       -- MODELLED row -- so every skill the sweep actually fought with fell through to
       -- 'none' and was averaged into the skill-less bucket. Run 14 is the case that
       -- shows the damage: all 17,568 of its duels, Blood Barrier and Tormented Soil
       -- included, sat under skill = 'none', so the weapon and rune axes could neither
       -- show a measured skill nor exclude one from the bare baseline they compare to.
       --
       -- skill_set_key is the measured identity and is already on the fact. Split only
       -- for a single-slot build: a multi-skill row has several candidate explanations
       -- for one number and naming the first slot would imply an attribution this view
       -- has not made. Same rule as marts.skill_contribution, deliberately.
       CASE WHEN derived_skill IS NOT NULL THEN derived_skill
            WHEN skill_count = 1 THEN split_part(skill_set_key, ':', 1)
            WHEN skill_count > 1 THEN '(multi-skill build)'
            ELSE 'none' END                                         AS skill,
       CASE WHEN derived_skill IS NOT NULL THEN derived_skill_level
            WHEN skill_count = 1 THEN NULLIF(split_part(skill_set_key, ':', 2), '')::int
            ELSE 0 END                                              AS skill_level,
       AVG(derived_bonus_per_hit)                                   AS skill_bonus,
       COUNT(*)                                                     AS duels,
       COUNT(DISTINCT role)                                         AS roles,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY dmg_per_hit)     AS dmg_per_hit,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY dps_sustained)   AS dps,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY dps_burst)       AS dps_burst,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY swings_per_second)    AS swings_per_second,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY swing_interval_ticks) AS swing_ticks
FROM source
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
                         -- NORMALISED to the config key's namespace, and this is the whole
                         -- reason the view reported "never measured" for every skill of run
                         -- 12: sim_build stores a skill's DISPLAY name ('Block Toss') and
                         -- grafana_config stores its squashed key ('blocktoss'), so the join
                         -- below could never match and the view could only ever say no.
                         -- Undetectable until a run finally measured a skill.
                         lower(regexp_replace(a ->> 'skill', '[^a-zA-Z0-9]', '', 'g')) AS skill,
                         (a ->> 'allocated_level')::int           AS skill_level,
                         -- After the grouped columns: GROUP BY below is positional.
                         MIN(a ->> 'skill')                       AS skill_display,
                         COUNT(DISTINCT b.run_id)                 AS runs,
                         COUNT(*)                                 AS builds,
                         MAX((a ->> 'effective_level')::int)      AS effective_level_max
                  FROM sim_build b
                           CROSS JOIN LATERAL jsonb_array_elements(b.skills) a
                  GROUP BY 1, 2, 3),
     -- Rolled up to the skill so the per-level rows above can be counted rather
     -- than joined twice.
     measured_skill AS (SELECT role, skill,
                               MIN(skill_display) AS skill_display,
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
SELECT COALESCE(d.realm, (SELECT MIN(realm) FROM grafana_config))     AS realm,
       COALESCE(d.role, m.role)                                      AS role,
       COALESCE(m.skill_display, d.skill)                            AS skill,
       COALESCE(d.delivery, 'measured only')                         AS delivery,
       COALESCE(d.scaling, 'no config damage key')                   AS scaling,
       d.damage_min,
       d.damage_max,
       COALESCE(d.levels_declared, 0)                                AS levels_declared,
       COALESCE(m.levels_measured, 0)                                       AS levels_measured,
       COALESCE(m.builds, 0)                                                AS builds,
       m.level_min,
       m.level_max,
       CASE WHEN COALESCE(d.levels_declared, 0) = 0 THEN NULL
            ELSE ROUND(100.0 * COALESCE(m.levels_measured, 0) / d.levels_declared, 1)
            END                                                              AS coverage_pct,
       -- A percentage skill with no measurement is a harder gap than a flat one with no
       -- measurement: the flat skill at least has a config number the dashboard can model,
       -- while this one has nothing at all behind it. Given its own status so the two do not
       -- read as the same backlog item.
       -- 'measured, undeclared' is its own status rather than folded into 'measured':
       -- config has no damage key for the skill, so nothing about it can be modelled and the
       -- measured rows are the ONLY evidence there is. That is a different reading from a
       -- skill where config and the sweep agree.
       CASE WHEN d.skill IS NULL                                  THEN 'measured, undeclared'
            WHEN COALESCE(m.levels_measured, 0) > 0
                 AND m.levels_measured >= d.levels_declared        THEN 'measured'
            WHEN COALESCE(m.levels_measured, 0) > 0                THEN 'partial'
            WHEN d.scaling = 'config-hint: percent'                THEN 'unverified scaling'
            ELSE 'never measured' END                                       AS status,
       -- What a one-skill-at-a-time sweep of the gap would cost, in builds. The
       -- SKILLS tier emits one build per (level x weapon x rune set x target),
       -- and the first term is the only one this view knows -- the rest is the
       -- multiplier the dashboard's own note supplies. Kept as a level count so
       -- the arithmetic stays visible rather than baked into a wrong constant.
       GREATEST(COALESCE(d.levels_declared, 0) - COALESCE(m.levels_measured, 0), 0)
                                                                            AS levels_to_sweep
FROM declared d
         FULL JOIN measured_skill m ON m.role = d.role AND m.skill = d.skill;

CREATE UNIQUE INDEX IF NOT EXISTS idx_gold_skill_coverage_key
    ON gold_skill_coverage (realm, role, skill);


-- ---------------------------------------------------------------------------
-- sim_gold_skill_modifier -- what a skill actually did to a hit, read rather than inferred.
--
-- The view the whole modifier-capture effort exists to serve, and the first consumer
-- sim_trace has ever had. Every other damage figure in this warehouse is an OUTCOME --
-- dmg_per_hit, dps, ttk -- and none of them say WHY. This one reads the operand the game
-- applied, from the stack recorded on the hit itself.
--
-- Why it had to be measured. Inferring a skill's scaling from its config keys was tried and
-- scored: against the eleven skills that really construct a SkillDamageModifier.Multiplier,
-- config-key inference gets precision 1/5 and recall 1/11. Run 12 shows why it could never
-- have worked -- Fortify and Blood Barrier are REDUCTIVE multipliers, which no "does the key
-- look like a percent" rule can see; Overwhelm applies 185 distinct operands over one sweep,
-- so it is neither flat nor percent; and Combo Attack's operand is a function of how many
-- times the holder has already hit, not of (skill, level) at all.
--
-- Attribution is sound only where the build fills at most one skill slot, which is exactly
-- what the SKILLS scope enumerates. A build carrying six skills produces six candidate
-- explanations for the same hit and this view would have to guess between them, so those
-- builds are excluded rather than approximated.
--
-- Scope guard. sim_trace is the largest table in the system -- a full EQUIPMENT sweep is tens
-- of millions of rows, all of them from builds with no skills at all. `skill_runs` restricts
-- the scan to runs that actually measured a skill, so this view costs what run 12 costs
-- rather than what the archive costs.
-- Dependents first. sim_gold_modifier_expectation, _ambient_modifier and the plain view
-- _modifier_unmatched all read sim_gold_skill_modifier, and Postgres refuses to drop a view
-- something depends on -- so without these three lines this file cannot be re-run from the
-- top at all. It could not, until the re-run was actually attempted.
DROP VIEW IF EXISTS sim_gold_modifier_unmatched;
DROP MATERIALIZED VIEW IF EXISTS sim_gold_ambient_modifier;
DROP MATERIALIZED VIEW IF EXISTS sim_gold_modifier_expectation;
DROP MATERIALIZED VIEW IF EXISTS sim_gold_skill_modifier;
CREATE MATERIALIZED VIEW sim_gold_skill_modifier AS
WITH skill_runs AS (
    -- Runs where at least one build carried a skill. Everything else in sim_trace is an
    -- equipment sweep and has nothing to attribute.
    SELECT DISTINCT run_id
    FROM sim_build
    WHERE jsonb_array_length(skills) > 0
),
build_skill AS (
    -- The one filled slot, or the skill-less baseline. Baselines are kept rather than
    -- filtered: they are what shows that a rune or an effect applies with no skill present,
    -- which is the comparison every skill row is read against.
    SELECT b.id                                        AS build_id,
           b.run_id,
           b.role,
           b.weapon,
           b.booster,
           COALESCE(b.skills -> 0 ->> 'skill', '(no skill)')            AS skill_name,
           COALESCE((b.skills -> 0 ->> 'allocated_level')::int, 0)      AS skill_level,
           COALESCE((b.skills -> 0 ->> 'effective_level')::int, 0)      AS effective_level
    FROM sim_build b
    JOIN skill_runs USING (run_id)
    WHERE jsonb_array_length(b.skills) <= 1
),
applied AS (
    -- One row per (hit, modifier). `actor` is CARRIED rather than filtered on, and that is the
    -- whole point of running a MUTUAL sweep.
    --
    -- This used to read `WHERE t.actor = 'attacker'`, on the reasoning that a defender-dealt hit
    -- carries the defender's modifiers and so says nothing about the build being measured. That
    -- is false here, and measurably so: `sim_result.target_skills` is empty on all 250,920 rows
    -- of BOTH run 12 and run 13, so the target carries no skills and every skill modifier on
    -- every hit -- whoever swung -- belongs to the one build under test.
    --
    -- `sim_trace.actor` is written as `attackerId.equals(hit.damager()) ? ATTACKER : DEFENDER`,
    -- so it names who DEALT the hit, and `build_id` is always the measured (attacking-side)
    -- build. That makes the two cases mean something precise:
    --
    --   actor = 'attacker'  the holder dealt this hit    -> an OUTGOING effect
    --   actor = 'defender'  the holder received this hit -> an INCOMING effect
    --
    -- Filtering to 'attacker' therefore did not remove noise, it removed the entire incoming
    -- half of every defensive skill.
    SELECT t.run_id,
           t.build_id,
           t.target_role,
           t.target_armor,
           t.actor,
           m ->> 'source'             AS modifier_source,
           m ->> 'operator'           AS operator,
           m ->> 'type'               AS modifier_type,
           (m ->> 'reductive')::bool  AS reductive,
           (m ->> 'operand')::numeric AS operand
    FROM sim_trace t
    JOIN skill_runs USING (run_id)
    CROSS JOIN LATERAL jsonb_array_elements(t.modifiers) m
    WHERE t.modifiers IS NOT NULL
),
agg AS (
    SELECT s.run_id,
           s.role,
           s.skill_name,
           s.skill_level,
           -- '(none)' rather than NULL: these columns are the view's natural key, and the
           -- unique index REFRESH CONCURRENTLY needs cannot be built on nullable grain.
           COALESCE(a.modifier_source, '(none)')  AS modifier_source,
           COALESCE(a.operator, '(none)')         AS operator,
           COALESCE(a.modifier_type, '(none)')    AS modifier_type,
           COALESCE(a.reductive, false)           AS reductive,
           -- COUNT of the operand, not of the row: a silent skill must report 0 hits, and
           -- COUNT(*) over a left join would report 1.
           COUNT(a.operand)                      AS hits,
           -- The two halves `direction` is read from. Counted with FILTER rather than split into
           -- separate rows so the grain -- and the unique index on it -- stays exactly as it was.
           COUNT(a.operand) FILTER (WHERE a.actor = 'attacker') AS hits_outgoing,
           COUNT(a.operand) FILTER (WHERE a.actor = 'defender') AS hits_incoming,
           COUNT(DISTINCT s.build_id)            AS builds,
           COUNT(DISTINCT a.target_role)         AS targets,
           COUNT(DISTINCT a.operand)             AS operand_values,
           MIN(a.operand)                        AS operand_min,
           MAX(a.operand)                        AS operand_max,
           AVG(a.operand)                        AS operand_avg,
           -- The typical value, which is what a reader wants for a modifier that is constant
           -- and is the honest middle for one that is not.
           percentile_cont(0.5) WITHIN GROUP (ORDER BY a.operand) AS operand_median
    -- LEFT, so a skill the sweep equipped and that never touched the damage pipeline still
    -- gets a row. That absence is a finding, not a gap: 42 of run 12's 51 skills produced no
    -- damage modifier at all, and a view that only listed the nine that did would make the
    -- other forty-two look unmeasured rather than measured-and-silent.
    FROM build_skill s
    LEFT JOIN applied a ON a.build_id = s.build_id
    GROUP BY 1, 2, 3, 4, 5, 6, 7, 8
)
SELECT agg.*,
       -- The classification config inference could not make.
       --
       -- RAMPING first, because it is the one that invalidates the others: a modifier taking
       -- several operand values at a FIXED level is not a property of (skill, level), so
       -- calling it flat or percent would be wrong however the operand is distributed.
       -- Deliberately partitioned WITHOUT `reductive`, so Combo Attack -- which records 0.0 on
       -- the first hit of a chain and 1..4 afterwards -- classifies as one ramping modifier
       -- rather than as a flat one and a ramping one.
       CASE
           WHEN MAX(agg.operand_values) OVER w > 1 THEN 'ramping'
           WHEN agg.operator = 'MULTIPLIER'        THEN 'percent'
           ELSE 'flat'
           END AS scaling_class,
       -- Which side of the damage a modifier acts on, MEASURED rather than inferred.
       --
       -- The obvious shortcut is to read it off `reductive` -- reductive means defence, so it
       -- applies to incoming damage. Fortify disproves that: it reduces incoming AND outgoing
       -- damage by design, so the same reductive operand belongs on both axes. A projection that
       -- inferred direction from `reductive` would drop Fortify's outgoing half and overstate a
       -- Fortify build's DPS by 10-30%.
       --
       -- ONE_WAY cannot answer the question. Only the measured build ever swings, so every row
       -- has hits_incoming = 0 and 'outgoing' would be an artefact of the scenario rather than a
       -- property of the skill. That case is named rather than guessed: a run with no incoming
       -- hits AT ALL reports `outgoing (unverified)`, and the fix is a MUTUAL sweep.
       CASE
           WHEN agg.hits = 0                                              THEN NULL
           WHEN agg.hits_outgoing > 0 AND agg.hits_incoming > 0           THEN 'symmetric'
           WHEN agg.hits_incoming > 0                                     THEN 'incoming'
           WHEN MAX(agg.hits_incoming) OVER (PARTITION BY agg.run_id) = 0 THEN 'outgoing (unverified)'
           ELSE 'outgoing'
           END AS direction,
       -- Whether allocating another level actually changes the operand. A skill whose bars do
       -- not move here is one whose levels buy nothing the damage pipeline can see.
       (MIN(agg.operand_min) OVER w) IS DISTINCT FROM (MAX(agg.operand_max) OVER w)
           AS scales_with_level,
       MIN(agg.operand_min) OVER w AS operand_min_all_levels,
       MAX(agg.operand_max) OVER w AS operand_max_all_levels,
       -- Whether the modifier is the build's own skill or something else that rode the same
       -- hit -- a rune, a potion effect, the weapon. Both are worth seeing: a skill row with
       -- no self-sourced modifier is a skill that never touched the damage.
       (agg.modifier_source = agg.skill_name) AS is_own_skill
FROM agg
WINDOW w AS (PARTITION BY agg.run_id, agg.skill_name, agg.modifier_source,
                          agg.operator, agg.modifier_type);

-- REFRESH CONCURRENTLY needs this, and the grain is the natural key.
CREATE UNIQUE INDEX IF NOT EXISTS idx_gold_skill_modifier_key
    ON sim_gold_skill_modifier (run_id, role, skill_name, skill_level,
                                modifier_source, operator, modifier_type, reductive);
CREATE INDEX IF NOT EXISTS idx_gold_skill_modifier_scan
    ON sim_gold_skill_modifier (run_id, scaling_class, skill_name);

COMMENT ON MATERIALIZED VIEW sim_gold_skill_modifier IS
    'Measured damage modifiers per skill and level, read from sim_trace.modifiers rather than '
        'inferred from config. scaling_class is flat | percent | ramping; ramping means the '
        'operand varies at a fixed level, so it is not a property of (skill, level). direction '
        'is outgoing | incoming | symmetric, measured from which side dealt the hit -- it cannot '
        'be inferred from reductive, because Fortify reduces both. A ONE_WAY run reports '
        'outgoing (unverified) since it never observes an incoming hit. Attribution is only '
        'sound for builds filling at most one skill slot.';


-- ---------------------------------------------------------------------------
-- sim_gold_run_health -- every run the pipeline has ATTEMPTED, and how it went.
--
-- Exists because of a blind spot that hid a real failure. Every sim_* dashboard picks its
-- run from `sim_gold_run`, which is a PUBLISHED mart: a row lands there only if the gold
-- layer completed. But `publish_audit` runs in a `finally`, so a run whose gold layer was
-- halted by a failing expectation still writes its data_quality rows -- it just never
-- becomes selectable. The run picker therefore excludes precisely the runs someone needs
-- to look at, and the data-quality panel is unreachable for exactly the runs that failed.
--
-- Run 12 is the case in point: 48 expectation rows including the `error` that stopped it,
-- and no entry in `sim_gold_run` at all.
--
-- Deliberately a PLAIN view, not materialized. The whole value is that a run which failed
-- ten seconds ago is visible now; a materialized view would need the refresh that the
-- failure just prevented from running.
--
-- Driven from sim_gold_data_quality rather than sim_run, because the question is "what has
-- the PIPELINE seen", not "what has the simulator produced". A sweep nobody has processed
-- has no health to report.
CREATE OR REPLACE VIEW sim_gold_run_health AS
WITH latest AS (
    -- The current result of each individual check, not of each invocation. A retried run
    -- writes a second set of expectation rows under a new pipeline_run_id, and counting
    -- both would double every total and let a fixed failure keep showing as a failure.
    --
    -- The grain is (layer, dataset, check_name) rather than the whole invocation, because
    -- not every invocation runs every check. `jobs.cli diff` writes its audit rows under
    -- run_id = run_b -- layer 'gold', dataset 'run_diff' -- so a diff is the most recent
    -- invocation touching that run without having re-run anything else. Keying on the
    -- invocation let those two rows supersede the full pipeline's, and run 7 reported
    -- "2 checks" when it had run 60.
    --
    -- Consequence worth knowing: a check that is deleted from the pipeline keeps its last
    -- recorded result here until the run is reprocessed. Stale beats absent -- the
    -- alternative silently drops history.
    SELECT DISTINCT ON (run_id, layer, dataset, check_name)
           run_id, realm, layer, dataset, check_name, severity, passed, checked_at
    FROM sim_gold_data_quality
    ORDER BY run_id, layer, dataset, check_name, checked_at DESC
),
checks AS (
    SELECT q.run_id,
           q.realm,
           MAX(q.checked_at)                                                  AS last_checked_at,
           COUNT(*)                                                           AS checks_total,
           COUNT(*) FILTER (WHERE NOT q.passed)                               AS checks_failed,
           COUNT(*) FILTER (WHERE NOT q.passed AND q.severity = 'error')      AS errors,
           COUNT(*) FILTER (WHERE NOT q.passed AND q.severity = 'warn')       AS warnings,
           -- The failing layer is the useful triage field: silver warnings are routine,
           -- a gold error means nothing published.
           MIN(q.layer) FILTER (WHERE NOT q.passed AND q.severity = 'error')  AS blocked_at_layer,
           string_agg(DISTINCT q.check_name, ', ')
                   FILTER (WHERE NOT q.passed AND q.severity = 'error')       AS blocking_checks,
           string_agg(DISTINCT q.check_name, ', ')
                   FILTER (WHERE NOT q.passed AND q.severity = 'warn')        AS warning_checks
    FROM latest q
    GROUP BY 1, 2
)
SELECT c.run_id,
       c.realm,
       r.started_at,
       r.finished_at,
       r.status                                    AS sim_status,
       r.scenario ->> 'scope'                      AS scope,
       r.scenario ->> 'scenario'                   AS scenario,
       c.last_checked_at,
       c.checks_total,
       c.checks_failed,
       c.errors,
       c.warnings,
       c.blocked_at_layer,
       c.blocking_checks,
       c.warning_checks,
       -- Whether the run made it into the marts every other dashboard reads. This is the
       -- column that explains an empty panel: `false` here means the panel is not broken,
       -- the data was never published.
       (g.run_id IS NOT NULL)                      AS published,
       CASE
           WHEN c.errors > 0   THEN 'blocked'
           WHEN g.run_id IS NULL THEN 'unpublished'
           WHEN c.warnings > 0 THEN 'warnings'
           ELSE 'clean'
           END                                     AS health
FROM checks c
         LEFT JOIN sim_run r ON r.id = c.run_id
         LEFT JOIN sim_gold_run g ON g.run_id = c.run_id;

COMMENT ON VIEW sim_gold_run_health IS
    'Every run the pipeline has attempted, with its expectation results and whether it '
        'published. Plain view, not materialized: a run blocked by a failing gold '
        'expectation must be visible without the refresh that failure prevented. health is '
        'blocked | unpublished | warnings | clean.';


-- ---------------------------------------------------------------------------
-- sim_gold_modifier_expectation -- measured operand vs the one config declares.
--
-- The join nothing had made. sim_gold_skill_modifier reads what the game DID to a hit;
-- gold_skill_damage parses what config DECLARES. Both existed, neither was compared to
-- the other, and the cost of that gap is that a measured operand carries no scale: a
-- reader looking at Tormented Soil's flat 1.33 and Blood Barrier's flat 0.7 has no way
-- to tell that the first is exactly what config asks for and the second is a correct
-- number landing on the wrong side of the fight.
--
-- THE KEY NAME DOES NOT DECIDE THE FORMULA -- the construction does, and the two do not
-- agree. Void declares `baseDamageReduction = 2.0` and builds `Flat(-2.0)`; Agility
-- declares the same key and builds `Multiplier(1 - x)`. A first cut of this view read the
-- formula off the key and reported Void as off by exactly -1.0 at every level, which is
-- the signature of applying `1 - x` to something that was never a multiplier. So the shape
-- comes from the MEASURED operator and only the magnitude comes from config:
--
--   operator = MULTIPLIER, increase key    1 + x    Tormented Soil, Frailty
--   operator = MULTIPLIER, reduction key   1 - x    Agility, Defensive Stance,
--                                                   Vanguards Might, Blood Barrier
--   operator = FLAT,       reduction key     -x     Void, Break Fall, Level Field
--
-- Reduction keys are spelled four ways across the catalog -- baseDamageReduction,
-- damageReduction, baseDamageReduced -- and missing one is silent: Blood Barrier uses
-- `damageReduction`, so it simply failed to join and vanished from the view rather than
-- reporting a mismatch. Absence is the failure mode to watch here, which is what
-- `unmatched_skills` at the bottom of this file exists to make visible.
--
-- Deflection and Combo Attack are expected to MISS and that is the view earning its keep:
-- their operand is a function of accumulated charge or hit streak, not of (skill, level),
-- so no config row can predict it. They report 'ramping' rather than a discrepancy,
-- because a view that flagged them as wrong would be crying wolf forever.
--
-- The verdict worth building this for is 'agrees, wrong axis': config predicts the operand
-- exactly AND the measured direction is not the one the skill's targeting implies. That is
-- the shape of the ally-targeting defect -- the arithmetic was never wrong, the fight was.
-- 'agrees, both axes' is its weaker cousin, for a one-sided skill measured as symmetric;
-- Fortify is symmetric by design, so this is a flag to read rather than a failure.
DROP MATERIALIZED VIEW IF EXISTS sim_gold_modifier_expectation;
CREATE MATERIALIZED VIEW sim_gold_modifier_expectation AS
WITH kv AS (
    -- Same parse as gold_skill_damage, kept local rather than shared: that view filters to
    -- base_damage IS NOT NULL and so drops every pure-multiplier skill, which is precisely
    -- the population this one is about.
    SELECT realm,
           split_part(config_key, '.', 3)  AS skill,
           split_part(config_key, '.', 4)  AS param,
           MAX(config_value::numeric)      AS val
    FROM grafana_config
    WHERE plugin = 'Champions'
      AND config_file = 'skills/skills'
      AND config_key ~ '^skills\.[^.]+\.[^.]+\.[^.]+$'
      AND config_value ~ '^-?[0-9]+(\.[0-9]+)?$'
    GROUP BY 1, 2, 3
),
declared AS (
    SELECT realm, skill,
           MAX(val) FILTER (WHERE param IN ('baseDamageIncrease',
                                            'baseDamagePercent'))                AS amp_base,
           COALESCE(MAX(val) FILTER (WHERE param IN ('damageIncreasePerLevel',
                                                     'damagePercentIncreasePerLevel')), 0)
                                                                                 AS amp_per_level,
           MAX(val) FILTER (WHERE param IN ('baseDamageReduction',
                                            'damageReduction',
                                            'baseDamageReduced'))                AS red_base,
           COALESCE(MAX(val) FILTER (WHERE param IN ('damageReductionIncreasePerLevel',
                                                     'damageReductionPerLevel',
                                                     'damagedReducedPerLevel')), 0)
                                                                                 AS red_per_level,
           -- Levels come from the skill's own declaration, not a hardcoded 5. Void caps at 3.
           COALESCE(MAX(val) FILTER (WHERE param = 'maxlevel'), 5)               AS max_level
    FROM kv GROUP BY 1, 2
),
-- The declared magnitude per level, still unsigned and still unshaped. Turning it into an
-- operand needs the operator, which lives on the measured row.
declared_level AS (
    SELECT d.realm, d.skill, lvl.level AS skill_level,
           d.amp_base IS NOT NULL                                       AS amplifying,
           CASE WHEN d.amp_base IS NOT NULL
                    THEN d.amp_base + d.amp_per_level * (lvl.level - 1)
                ELSE d.red_base + d.red_per_level * (lvl.level - 1) END  AS magnitude
    FROM declared d
             CROSS JOIN LATERAL (SELECT generate_series(1, d.max_level::int) AS level) lvl
    WHERE COALESCE(d.amp_base, d.red_base) IS NOT NULL
),
joined AS (
    SELECT m.run_id,
           m.role,
           m.skill_name,
           m.skill_level,
           m.modifier_source,
           m.operator,
           m.scaling_class,
           m.direction,
           m.hits,
           m.hits_outgoing,
           m.hits_incoming,
           m.operand_median,
           CASE
               WHEN m.operator = 'MULTIPLIER' AND e.amplifying     THEN 1 + e.magnitude
               WHEN m.operator = 'MULTIPLIER' AND NOT e.amplifying THEN 1 - e.magnitude
               WHEN e.amplifying                                   THEN     e.magnitude
               ELSE                                                        -e.magnitude
               END AS expected_operand,
           CASE
               WHEN m.operator = 'MULTIPLIER' AND e.amplifying     THEN 'Multiplier(1 + increase)'
               WHEN m.operator = 'MULTIPLIER' AND NOT e.amplifying THEN 'Multiplier(1 - reduction)'
               WHEN e.amplifying                                   THEN 'Flat(+increase)'
               ELSE                                                     'Flat(-reduction)'
               END AS basis,
           -- What the skill's targeting implies, which is the axis the measurement is checked
           -- against. A reduction the holder applies to itself is incoming; an increase it
           -- applies to whoever it hits is outgoing.
           CASE WHEN e.amplifying THEN 'outgoing' ELSE 'incoming' END AS expected_direction
    FROM sim_gold_skill_modifier m
             JOIN declared_level e
                  ON e.skill = lower(regexp_replace(m.skill_name, '[^a-zA-Z0-9]', '', 'g'))
                      AND e.skill_level = m.skill_level
    -- Only the skill's own modifier is checked against the skill's own config. A rune or a
    -- potion effect riding the same hit is a real row in sim_gold_skill_modifier and has no
    -- business being compared to this skill's declared numbers.
    WHERE m.is_own_skill
)
SELECT j.*,
       j.operand_median - j.expected_operand AS operand_delta,
       CASE
           -- Named before it is compared: an operand that is not a property of (skill, level)
           -- has nothing to compare against, and saying so beats reporting a false miss.
           WHEN j.scaling_class = 'ramping'
               THEN 'ramping -- not predictable from config'
           WHEN j.hits = 0
               THEN 'silent -- never reached the damage pipeline'
           -- Half a percent of the operand, floored, so a level-5 multiplier is not judged by
           -- the same absolute tolerance as a level-1 one.
           WHEN abs(j.operand_median - j.expected_operand)
                    > GREATEST(0.005, abs(j.expected_operand) * 0.005)
               THEN 'differs from config'
           -- A ONE_WAY run never observes an incoming hit, so its direction is an artefact of
           -- the scenario and cannot contradict anything.
           WHEN j.direction IS NULL OR j.direction LIKE 'outgoing (unverified)%'
               THEN 'agrees with config'
           WHEN j.direction = 'symmetric' AND j.expected_direction <> 'symmetric'
               THEN 'agrees, both axes'
           WHEN j.direction <> j.expected_direction
               THEN 'agrees, wrong axis'
           ELSE 'agrees with config'
           END AS verdict
FROM joined j;

CREATE UNIQUE INDEX IF NOT EXISTS idx_gold_modifier_expectation_key
    ON sim_gold_modifier_expectation (run_id, role, skill_name, skill_level,
                                      modifier_source, operator);
CREATE INDEX IF NOT EXISTS idx_gold_modifier_expectation_verdict
    ON sim_gold_modifier_expectation (run_id, verdict);

COMMENT ON MATERIALIZED VIEW sim_gold_modifier_expectation IS
    'Measured damage operand vs the one config declares, per skill and level. The formula '
        'shape comes from the MEASURED operator and only the magnitude from config, because '
        'the key name does not decide the construction -- Void and Agility declare the same '
        'baseDamageReduction and build Flat(-x) and Multiplier(1-x) respectively. verdict is '
        'agrees with config | differs from config | agrees, wrong axis | agrees, both axes | '
        'ramping | silent. "agrees, wrong axis" means the arithmetic is right and the damage '
        'landed on the wrong side of the fight, which is what an ally-targeted buff hitting '
        'the opponent looks like.';


-- ---------------------------------------------------------------------------
-- sim_gold_modifier_unmatched -- skills the expectation view could not check.
--
-- The companion the view above needs to be trustworthy, because its failure mode is
-- ABSENCE. Blood Barrier spells its key `damageReduction` rather than `baseDamageReduction`,
-- so the first cut of the join simply did not match it -- and a skill that is missing looks
-- exactly like a skill that is fine. Nothing on a dashboard would have said otherwise.
--
-- Plain view: it is small, it is read after a config change as often as after a sweep, and
-- being one refresh stale is precisely the state it exists to catch.
CREATE OR REPLACE VIEW sim_gold_modifier_unmatched AS
SELECT m.run_id,
       m.role,
       m.skill_name,
       m.skill_level,
       m.operator,
       m.direction,
       m.hits,
       m.operand_median,
       CASE WHEN m.hits = 0 THEN 'silent -- nothing to reconcile'
            ELSE 'no reduction/increase key in config for this skill' END AS reason
FROM sim_gold_skill_modifier m
WHERE m.is_own_skill
  AND NOT EXISTS (SELECT 1
                  FROM sim_gold_modifier_expectation x
                  WHERE x.run_id = m.run_id
                    AND x.skill_name = m.skill_name
                    AND x.skill_level = m.skill_level
                    AND x.modifier_source = m.modifier_source);

COMMENT ON VIEW sim_gold_modifier_unmatched IS
    'Skills carrying a measured own-skill modifier that sim_gold_modifier_expectation could '
        'not reconcile, because config declares no damage increase or reduction key it '
        'recognises. Exists because that view fails by absence, and a missing skill is '
        'indistinguishable from a passing one.';

-- ---------------------------------------------------------------------------
-- sim_gold_ambient_modifier -- modifiers that are NOT a property of the build.
--
-- The axis the warehouse did not have. Everything else here models a modifier as something
-- a build CARRIES, so a projection can walk (weapon x skill x rune) and compose. A large and
-- growing class of modifiers does not work that way: it belongs to the SITUATION, and can
-- land on any build at all regardless of what that build brings.
--
--   Tormented Soil    a zone. Its onDamage handler asks only where the DAMAGEE is standing
--                     and never whether the damager carries the skill, so anyone who fights
--                     inside it takes 1.33x -- including a build with no damage skills.
--   Blood Barrier     ShieldData is written for the caster AND for every nearby ally, so a
--                     player who never took the skill can carry its 0.7.
--   Arctic Armour     resistance granted by someone else's aura.
--   Vulnerability     a debuff an opponent places on YOU. Measured here at 1.1.
--
-- Membership is MEASURED, not declared: a row appears here when a modifier was recorded on a
-- hit from a build that does not carry its source. That is a fact about the trace, so the view
-- cannot go stale against a skill list, and a skill that gains ally-application shows up the
-- next time it is swept without anyone editing SQL.
--
-- What it is FOR. A projected row is currently `base x own modifiers`. These are the terms that
-- have to be applied on top of any such row, as an overlay with an uptime rather than as a
-- build attribute -- which is why `hit_share` is here: it is the fraction of the run's hits the
-- modifier actually touched, and it is the honest coefficient to project with. A projection
-- that folds an ambient modifier into a build's own stack attributes someone else's zone to
-- that build's kit.
--
-- CAVEAT, and it is the reason to read hit_share rather than trust it: runs 12 and 13 predate
-- the EntityProperty.ENEMY fix, so an ally-targeted buff could land on the opponent, and arena
-- reuse let one duel's zone survive into the next. Both inflate ambient reach. Re-measure after
-- a rebuilt-plugin sweep before projecting from these numbers.
DROP MATERIALIZED VIEW IF EXISTS sim_gold_ambient_modifier;
CREATE MATERIALIZED VIEW sim_gold_ambient_modifier AS
WITH skill_keys AS (
    SELECT DISTINCT split_part(config_key, '.', 3) AS skill
    FROM grafana_config
    WHERE plugin = 'Champions' AND config_file = 'skills/skills'
),
-- Rune names as the sweep spells them ('core:brutality'), so an equipment-sourced modifier
-- ('Rune of Brutality') can be told from a genuinely ambient one. Matched on containment
-- because the two namespaces disagree about prefixes, not about the noun.
--
-- SCOPED TO THE RUNS THIS VIEW COVERS, which is not a detail. Unscoped, the lateral walks
-- every build in the archive -- runs 1 and 7 are EQUIPMENT sweeps of millions of rune-bearing
-- builds -- to produce a list of maybe a dozen distinct nouns, and the refresh does not
-- finish in two minutes. Restricted to the skill runs it is 20,910 builds and instant.
rune_keys AS (
    SELECT DISTINCT regexp_replace(lower(split_part(r #>> '{}', ':', 2)),
                                   '[^a-z0-9]', '', 'g') AS rune
    FROM sim_build b CROSS JOIN LATERAL jsonb_array_elements(b.runes) r
    WHERE b.run_id IN (SELECT DISTINCT run_id FROM sim_gold_skill_modifier)
),
run_hits AS (
    SELECT run_id, SUM(hits) AS hits_in_run
    FROM sim_gold_skill_modifier WHERE hits > 0 GROUP BY 1
),
foreign_mods AS (
    SELECT m.run_id,
           m.modifier_source,
           m.operator,
           m.modifier_type,
           m.reductive,
           lower(regexp_replace(m.modifier_source, '[^a-zA-Z0-9]', '', 'g')) AS source_key,
           m.skill_name,
           m.hits,
           m.direction,
           m.operand_min,
           m.operand_max,
           m.operand_median
    FROM sim_gold_skill_modifier m
    WHERE NOT m.is_own_skill
      AND m.hits > 0
)
SELECT f.run_id,
       f.modifier_source,
       f.operator,
       f.modifier_type,
       f.reductive,
       -- Where the modifier comes from, which decides whether a projection already has it.
       -- Equipment is ALREADY modelled by the rune marts; skill and effect are not.
       CASE
           WHEN EXISTS (SELECT 1 FROM rune_keys r WHERE f.source_key LIKE '%' || r.rune || '%')
               THEN 'equipment'
           WHEN EXISTS (SELECT 1 FROM skill_keys s WHERE s.skill = f.source_key)
               THEN 'skill (someone else''s)'
           ELSE 'effect'
           END                                            AS source_class,
       COUNT(DISTINCT f.skill_name)                       AS carrier_skills,
       string_agg(DISTINCT f.skill_name, ', ')            AS carried_by,
       string_agg(DISTINCT f.direction, ', ')             AS directions,
       SUM(f.hits)                                        AS hits,
       -- The coefficient to project with. Not a probability of the modifier existing -- it is
       -- the share of measured hits it actually touched, under this sweep's conditions.
       SUM(f.hits)::numeric / NULLIF(MAX(rh.hits_in_run), 0) AS hit_share,
       MIN(f.operand_min)                                 AS operand_min,
       MAX(f.operand_max)                                 AS operand_max,
       -- Weighted by hits, so a modifier seen 40k times on one operand is not averaged flat
       -- against one seen twice on another.
       SUM(f.operand_median * f.hits) / NULLIF(SUM(f.hits), 0) AS operand_weighted
FROM foreign_mods f
         JOIN run_hits rh ON rh.run_id = f.run_id
GROUP BY 1, 2, 3, 4, 5, 6;

CREATE UNIQUE INDEX IF NOT EXISTS idx_gold_ambient_modifier_key
    ON sim_gold_ambient_modifier (run_id, modifier_source, operator, modifier_type, reductive);

COMMENT ON MATERIALIZED VIEW sim_gold_ambient_modifier IS
    'Damage modifiers measured on builds that do not carry their source -- a zone, an ally''s '
        'aura or shield, an opponent''s debuff. These belong to the situation rather than to '
        'the build, so a projection must apply them as an overlay weighted by hit_share, not '
        'fold them into the build''s own modifier stack. source_class separates equipment '
        '(already modelled by the rune marts) from skill and effect (not modelled anywhere '
        'else). Measured under runs that predate the EntityProperty.ENEMY fix, so reach is '
        'overstated until a rebuilt-plugin sweep re-measures it.';





-- ---------------------------------------------------------------------------
-- sim_gold_damage_point -- one row per PLOTTABLE POINT, on whichever axis groups it.
--
-- The chart this backs asks one question -- what does a build hit for, and what made it
-- that -- and lets the reader regroup the same points by weapon, by skill or by rune.
-- Regrouping must not change which points are in the chart or what they say about
-- themselves; it changes only what goes on the category axis.
--
-- WHY THE THREE EXISTING VIEWS COULD NOT DO THIS. sim_gold_weapon_damage, _rune_damage and
-- _skill_damage are three grains, and a panel per grain is three charts that merely look
-- alike. The skill one is the clearest failure: it aggregates runes away into
-- rune_count_min/max, rune_sets and example_rune_set, so a point on the skill axis cannot
-- say WHICH runes produced it -- exactly the composition a reader hovers to find. Worse, the
-- three hovers were written separately and disagree about what a point is made of.
--
-- LONG FORM, and that is the whole design. One row per (permutation x grouping axis), with
-- `group_axis` naming the axis and `group_key` the category. A panel then reads
--
--     WHERE run_id = $run AND group_axis = '$group_by'
--
-- which is one index range scan and no joins, explodes or CASE-per-axis at dashboard time.
-- Switching the grouping re-reads the same view instead of running different SQL, so the
-- three groupings cannot drift apart -- they are literally the same rows.
--
-- A permutation appears ONCE on the weapon axis, ONCE on the skill axis, and once per rune
-- it carries on the rune axis. That asymmetry is correct rather than a bug: a four-rune
-- build genuinely is evidence about four runes, and the same build's damage is one fact
-- about one weapon. It does mean rune-axis boxes hold more points than weapon-axis boxes
-- and the two counts should not be compared.
--
-- THE COMPOSITION IS COLUMNS, NOT A PRECOMPUTED STRING, and that was measured rather than
-- assumed. Building the hover text into the view made it 1298 MB -- five times every other
-- gold view combined -- to save 1.0s on a 680,976-row read. The wrong trade: the same read
-- ships 197 MB of hover text to the browser, so the bottleneck is the payload, not the
-- concatenation. The panel samples per group instead and builds the string over the few
-- thousand rows that survive, which is free.
--
-- Built on sim_gold_weapon_damage, which is the true permutation grain and the only one of
-- the three that names a measured skill. Every axis therefore reports the same numbers as
-- the weapon axis by construction, not by two queries agreeing.
-- Dropped in the single stack at sim_gold_baseline_cell, not here: this view now sits
-- under the baseline, so a drop order local to it would be wrong.
CREATE MATERIALIZED VIEW sim_gold_damage_point AS
WITH bare AS (
    -- Same weapon, roll and skill state carrying NO runes. The subtrahend for a rune's
    -- marginal value: without it a rune's box is mostly a picture of the weapon under it.
    SELECT run_id, weapon_key, weapon_roll, skill, skill_level,
           dmg_per_hit AS bare_dmg_per_hit, dps AS bare_dps
    FROM sim_gold_weapon_damage
    WHERE rune_count = 0
),
unskilled AS (
    -- Same weapon, roll and rune set carrying NO skill. The subtrahend for a skill's
    -- marginal value, and the reason a skill that does nothing on this weapon reads as zero
    -- rather than as the weapon's damage.
    SELECT run_id, weapon_key, weapon_roll, rune_set_key,
           dmg_per_hit AS unskilled_dmg_per_hit, dps AS unskilled_dps
    FROM sim_gold_weapon_damage
    WHERE skill_level = 0
),
naked AS (
    -- The weapon with NEITHER runes NOR skill. A THIRD subtrahend, and the one that turns
    -- this view from "what is this permutation next to its neighbour" into "what is this
    -- permutation MADE OF".
    --
    -- `bare` and `unskilled` are marginals held in context -- each answers what one part is
    -- worth GIVEN the rest of the build. Neither can say how the whole number divides,
    -- because both already contain the weapon. This one is the floor everything is measured
    -- from, and with it every term of the decomposition is derivable per permutation:
    --
    --   rune_effect  = (dps - skill_dps_delta) - weapon_base     [= unskilled - naked]
    --   skill_effect = (dps - rune_dps_delta)  - weapon_base     [= bare      - naked]
    --   interaction  = dps - weapon_base - rune_effect - skill_effect
    --
    -- ONE stored column rather than four, because the other three are exact arithmetic over
    -- columns already here and this view is 2M+ rows. Three doubles saved per row is not
    -- worth three doubles of storage per row when the subtraction costs nothing on the few
    -- thousand rows a panel actually reads.
    SELECT run_id, weapon_key, weapon_roll,
           dmg_per_hit AS naked_dmg_per_hit, dps AS naked_dps
    FROM sim_gold_weapon_damage
    WHERE rune_count = 0 AND skill_level = 0
),
perm AS (
    SELECT w.run_id,
           w.weapon_key,
           REPLACE(REPLACE(w.weapon_key, 'core:', ''), 'champions:', '') AS weapon_name,
           w.weapon_slot,
           w.weapon_roll,
           w.rune_set_key,
           w.rune_count,
           COALESCE(NULLIF(REPLACE(w.runes, 'core:', ''), ''), 'none')   AS rune_names,
           w.skill,
           w.skill_level,
           w.duels,
           w.dmg_per_hit,
           w.dps,
           w.dps_burst,
           w.swings_per_second,
           w.swing_ticks,
           b.bare_dmg_per_hit,
           b.bare_dps,
           u.unskilled_dmg_per_hit,
           u.unskilled_dps,
           w.dmg_per_hit - b.bare_dmg_per_hit AS rune_dmg_delta,
           w.dps         - b.bare_dps         AS rune_dps_delta,
           w.dmg_per_hit - u.unskilled_dmg_per_hit AS skill_dmg_delta,
           w.dps         - u.unskilled_dps         AS skill_dps_delta,
           n.naked_dps                             AS weapon_base,
           n.naked_dmg_per_hit                     AS weapon_base_dph
    FROM sim_gold_weapon_damage w
             LEFT JOIN bare b
                       ON b.run_id = w.run_id AND b.weapon_key = w.weapon_key
                           AND b.weapon_roll = w.weapon_roll
                           AND b.skill = w.skill AND b.skill_level = w.skill_level
             LEFT JOIN unskilled u
                       ON u.run_id = w.run_id AND u.weapon_key = w.weapon_key
                           AND u.weapon_roll = w.weapon_roll
                           AND u.rune_set_key = w.rune_set_key
             LEFT JOIN naked n
                       ON n.run_id = w.run_id AND n.weapon_key = w.weapon_key
                           AND n.weapon_roll = w.weapon_roll
)
SELECT 'weapon'::text AS group_axis, d.weapon_name AS group_key,
       d.run_id, d.weapon_name, d.weapon_roll, d.rune_names, d.rune_count,
       d.skill, d.skill_level, d.duels,
       d.dmg_per_hit, d.dps, d.dps_burst, d.swings_per_second, d.swing_ticks,
       d.rune_dmg_delta, d.rune_dps_delta, d.skill_dmg_delta, d.skill_dps_delta,
       d.weapon_base, d.weapon_base_dph
FROM perm d
UNION ALL
SELECT 'skill', CASE WHEN d.skill_level = 0 THEN 'No skill'
                     ELSE d.skill || ' ' || d.skill_level END,
       d.run_id, d.weapon_name, d.weapon_roll, d.rune_names, d.rune_count,
       d.skill, d.skill_level, d.duels,
       d.dmg_per_hit, d.dps, d.dps_burst, d.swings_per_second, d.swing_ticks,
       d.rune_dmg_delta, d.rune_dps_delta, d.skill_dmg_delta, d.skill_dps_delta,
       d.weapon_base, d.weapon_base_dph
FROM perm d
UNION ALL
-- One row per rune the permutation carries. A build with four runes is evidence about four
-- runes and appears in four boxes.
SELECT 'rune', REPLACE(REPLACE(r.rune, 'core:', ''), 'champions:', ''),
       d.run_id, d.weapon_name, d.weapon_roll, d.rune_names, d.rune_count,
       d.skill, d.skill_level, d.duels,
       d.dmg_per_hit, d.dps, d.dps_burst, d.swings_per_second, d.swing_ticks,
       d.rune_dmg_delta, d.rune_dps_delta, d.skill_dmg_delta, d.skill_dps_delta,
       d.weapon_base, d.weapon_base_dph
FROM perm d
         CROSS JOIN LATERAL unnest(string_to_array(d.rune_set_key, '+')) AS r(rune)
WHERE d.rune_count > 0
UNION ALL
-- Bare permutations get their own box on the rune axis rather than vanishing from it. It is
-- the reference the other boxes are read against, and a rune axis without it invites the
-- reader to compare runes only to each other.
SELECT 'rune', 'No runes',
       d.run_id, d.weapon_name, d.weapon_roll, d.rune_names, d.rune_count,
       d.skill, d.skill_level, d.duels,
       d.dmg_per_hit, d.dps, d.dps_burst, d.swings_per_second, d.swing_ticks,
       d.rune_dmg_delta, d.rune_dps_delta, d.skill_dmg_delta, d.skill_dps_delta,
       d.weapon_base, d.weapon_base_dph
FROM perm d
WHERE d.rune_count = 0;

-- UNIQUE, and it has to be: REFRESH MATERIALIZED VIEW CONCURRENTLY requires a unique index
-- and silently is not available without one. Before this existed the pipeline logged
-- "concurrent refresh of sim_gold_damage_point failed; falling back to a locking one" and
-- took an exclusive lock for the whole rebuild -- a dashboard open during a publish blocked
-- rather than reading the old rows, which is the entire thing CONCURRENTLY buys.
--
-- These eight columns are the permutation's full identity. rune_names rather than
-- rune_set_key because that is what the view carries, and group_key is needed in its own
-- right: on the rune axis one permutation appears once per rune it holds, so the axis
-- category is part of what makes a row distinct. Verified unique over all 2,394,120 rows.
--
-- Replaces the non-unique (run_id, group_axis, group_key) scan index, which is a prefix of
-- this one and therefore redundant -- range scans on the prefix use this index just as well.
CREATE UNIQUE INDEX IF NOT EXISTS idx_gold_damage_point_key
    ON sim_gold_damage_point (run_id, group_axis, group_key, weapon_name, weapon_roll,
                              rune_names, skill, skill_level);
CREATE INDEX IF NOT EXISTS idx_gold_damage_point_filter
    ON sim_gold_damage_point (run_id, group_axis, weapon_roll, rune_count, skill_level);

COMMENT ON MATERIALIZED VIEW sim_gold_damage_point IS
    'One row per plottable point per grouping axis -- the backing view for the damage '
        'distribution chart. Long form: group_axis is one of weapon/skill/rune and group_key '
        'is the category, so regrouping the chart is an index range scan rather than '
        'different SQL. Every point carries its FULL composition (weapon, roll, every rune '
        'by name, skill and level) as COLUMNS, so a point on the skill axis can still say '
        'which runes made it -- which sim_gold_skill_damage could not. Marginals '
        'are against the same weapon bare (rune_dps_delta) and the same weapon with no skill '
        '(skill_dps_delta). weapon_base is the same weapon with NEITHER, which makes the full '
        'four-term composition derivable per permutation rather than only per category: '
        'rune_effect = (dps - skill_dps_delta) - weapon_base, skill_effect = (dps - '
        'rune_dps_delta) - weapon_base, interaction = the remainder.';


-- ---------------------------------------------------------------------------
-- sim_gold_damage_composition -- what the number is MADE OF, not merely what carried it.
--
-- sim_gold_damage_point names a permutation's parts in a hover. It does not say how much of
-- the damage each part is responsible for, and that is the question a balance pass actually
-- asks. This view decomposes every permutation's DPS into four terms that sum to it exactly:
--
--   weapon_base   the same weapon at the same roll with NO runes and NO skill
--   rune_effect   (weapon + runes, no skill)  - weapon_base
--   skill_effect  (weapon + skill, no runes)  - weapon_base
--   interaction   whatever is left over
--
-- THE INTERACTION TERM IS THE INTERESTING ONE and the reason this is four columns rather
-- than three. The damage model is `final = (base x sum(amplifying) + sum(flats)) x
-- prod(reductive)`, which is not additive, so measuring a rune alone and a skill alone and
-- adding them does NOT reproduce the build that carries both. On run 1 the residual runs
-- +0.70 / +1.04 / +1.46 DPS at Backstab 1 / 2 / 3: a per-hit skill bonus compounds with the
-- attack speed the runes bought, and it compounds harder the bigger the bonus. A three-term
-- decomposition would have silently buried that in one of the other terms.
--
-- The four terms sum to the measured DPS by construction, because `interaction` is defined as
-- the residual rather than modelled. That makes the stack honest -- it can never disagree
-- with the fact -- at the cost of the residual absorbing any measurement noise too.
--
-- MEANS, not medians, and that is forced. Medians of four components do not sum to the median
-- of the total, so a median stack would draw bars that do not add up to the number beside
-- them. Read sim_gold_damage_density for the shape of a distribution; this view is for its
-- budget.
--
-- BUILT ON sim_gold_damage_point, not on sim_gold_weapon_damage a second time. This view
-- used to re-derive the whole decomposition from three self-joins of weapon_damage, which
-- was the same arithmetic damage_point already does -- two implementations of one rule, free
-- to drift, and they would have drifted silently because nothing compared them. damage_point
-- now carries weapon_base (the weapon with neither runes nor skill), so the other three terms
-- are exact subtractions over columns that are already there, and this view is what it should
-- always have been: an aggregate.
--
-- The row set is unchanged because the WHERE reproduces what the three INNER JOINs did --
-- a permutation missing any of its three reference points is excluded rather than carried
-- with a NULL term that would break the sum.
-- Dropped in the single stack at sim_gold_baseline_cell, not here: this view now sits
-- under the baseline, so a drop order local to it would be wrong.
CREATE MATERIALIZED VIEW sim_gold_damage_composition AS
WITH term AS (
    SELECT run_id, group_axis, group_key, weapon_roll, dps, dmg_per_hit,
           weapon_base,
           dps - skill_dps_delta - weapon_base          AS rune_effect,
           dps - rune_dps_delta  - weapon_base          AS skill_effect,
           -- The residual, written as the identity rather than as (dps - the other three) so
           -- it is visibly the same expression the header describes.
           dps - weapon_base
               - (dps - skill_dps_delta - weapon_base)
               - (dps - rune_dps_delta  - weapon_base)  AS interaction,
           weapon_base_dph,
           dmg_per_hit - skill_dmg_delta - weapon_base_dph AS rune_effect_dph,
           dmg_per_hit - rune_dmg_delta  - weapon_base_dph AS skill_effect_dph,
           dmg_per_hit - weapon_base_dph
               - (dmg_per_hit - skill_dmg_delta - weapon_base_dph)
               - (dmg_per_hit - rune_dmg_delta  - weapon_base_dph) AS interaction_dph
    FROM sim_gold_damage_point
    WHERE weapon_base IS NOT NULL
      AND rune_dps_delta IS NOT NULL
      AND skill_dps_delta IS NOT NULL
)
SELECT run_id, group_axis, group_key, weapon_roll,
       COUNT(*)                    AS permutations,
       AVG(dps)                    AS dps,
       AVG(weapon_base)            AS weapon_base,
       AVG(rune_effect)            AS rune_effect,
       AVG(skill_effect)           AS skill_effect,
       AVG(interaction)            AS interaction,
       AVG(dmg_per_hit)            AS dmg_per_hit,
       AVG(weapon_base_dph)        AS weapon_base_dph,
       AVG(rune_effect_dph)        AS rune_effect_dph,
       AVG(skill_effect_dph)       AS skill_effect_dph,
       AVG(interaction_dph)        AS interaction_dph,
       -- What share of the final number each part is responsible for. The column a reader
       -- sorts by when the question is "where is this build's damage actually coming from".
       AVG(weapon_base)  / NULLIF(AVG(dps), 0) AS weapon_share,
       AVG(rune_effect)  / NULLIF(AVG(dps), 0) AS rune_share,
       AVG(skill_effect) / NULLIF(AVG(dps), 0) AS skill_share,
       AVG(interaction)  / NULLIF(AVG(dps), 0) AS interaction_share
FROM term
GROUP BY 1, 2, 3, 4;

CREATE UNIQUE INDEX IF NOT EXISTS idx_gold_damage_composition_key
    ON sim_gold_damage_composition (run_id, group_axis, group_key, weapon_roll);

COMMENT ON MATERIALIZED VIEW sim_gold_damage_composition IS
    'DPS decomposed into weapon_base + rune_effect + skill_effect + interaction, which sum to '
        'the measured DPS exactly because interaction is the residual rather than a model. '
        'The residual is not noise: the damage pipeline is multiplicative, so a per-hit skill '
        'bonus compounds with rune-bought attack speed and the term grows with both. Means '
        'rather than medians, because medians of components do not sum to the median of the '
        'total. Long form on group_axis, matching sim_gold_damage_point.';


-- ---------------------------------------------------------------------------
-- sim_gold_damage_density -- the actual shape of each distribution, over EVERY point.
--
-- A box plot reports five numbers and hides everything between them. Two categories with the
-- same quartiles can be one tight mode and two far-apart clusters, and for balance work that
-- difference is the finding -- a bimodal weapon is one that plays as two different weapons
-- depending on what is on it. The chart cannot show that from a box, and it cannot show it
-- from a sample either.
--
-- WHY THIS EXISTS RATHER THAN JUST RAISING THE SAMPLE. sim_gold_damage_point's panel samples
-- because 680,976 points is ~197 MB of hover text into a browser. Binning server-side inverts
-- that trade completely: the density is computed over EVERY point, exactly, and what crosses
-- the wire is ~40 rows per category. Exact and small, rather than approximate and large.
--
-- Bin edges are shared across every category within a (run, group_axis, metric), so the
-- categories are directly comparable -- per-category edges would make two histograms that
-- cannot be read against each other. 40 bins over the observed range.
-- Dropped in the single stack at sim_gold_baseline_cell, not here: this view now sits
-- under the baseline, so a drop order local to it would be wrong.
CREATE MATERIALIZED VIEW sim_gold_damage_density AS
WITH points AS (
    SELECT run_id, group_axis, group_key, weapon_roll, 'dps' AS metric, dps AS val
    FROM sim_gold_damage_point WHERE dps IS NOT NULL
    UNION ALL
    SELECT run_id, group_axis, group_key, weapon_roll, 'dmg_per_hit', dmg_per_hit
    FROM sim_gold_damage_point WHERE dmg_per_hit IS NOT NULL
),
scale AS (
    SELECT run_id, group_axis, metric, MIN(val) AS lo, MAX(val) AS hi
    FROM points GROUP BY 1, 2, 3
),
binned AS (
    SELECT p.run_id, p.group_axis, p.group_key, p.weapon_roll, p.metric,
           s.lo, s.hi,
           -- width_bucket returns 1..40 inside the range and 0 / 41 outside it; the range is
           -- the observed min/max so only the maximum itself lands in 41, and LEAST folds it
           -- back into the top bin rather than into a phantom 41st.
           LEAST(width_bucket(p.val, s.lo, s.hi, 40), 40) AS bin,
           COUNT(*) AS points
    FROM points p
             JOIN scale s ON s.run_id = p.run_id AND s.group_axis = p.group_axis
                                 AND s.metric = p.metric
    WHERE s.hi > s.lo
    GROUP BY 1, 2, 3, 4, 5, 6, 7, 8
)
SELECT run_id, group_axis, group_key, weapon_roll, metric, bin,
       lo + (hi - lo) * (bin - 1) / 40.0                  AS bin_lo,
       lo + (hi - lo) * bin / 40.0                        AS bin_hi,
       lo + (hi - lo) * (bin - 0.5) / 40.0                AS bin_mid,
       points,
       -- Normalised WITHIN the category, so a rare weapon's shape is readable next to a
       -- common one. Comparing heights across categories is a different question and needs
       -- `points`, which is why both are here.
       points::numeric / SUM(points) OVER (
           PARTITION BY run_id, group_axis, group_key, weapon_roll, metric) AS share
FROM binned;

-- Unique for the same reason as sim_gold_damage_point's: without it CONCURRENTLY is
-- unavailable and the refresh takes an exclusive lock. The grain is one row per bin of a
-- category, which is exactly these six columns.
CREATE UNIQUE INDEX IF NOT EXISTS idx_gold_damage_density_key
    ON sim_gold_damage_density (run_id, group_axis, group_key, weapon_roll, metric, bin);
CREATE INDEX IF NOT EXISTS idx_gold_damage_density_scan
    ON sim_gold_damage_density (run_id, group_axis, metric, weapon_roll);

COMMENT ON MATERIALIZED VIEW sim_gold_damage_density IS
    'Exact histogram of every permutation, 40 bins per (run, group_axis, metric) with edges '
        'shared across categories so they can be read against each other. Exists because a '
        'box hides bimodality and a sampled scatter cannot prove it: binning server-side '
        'computes the density over EVERY point and ships ~40 rows per category. `share` is '
        'normalised within a category for shape; `points` is the raw count for mass.';
