-- Per-permutation config fingerprints, so a sweep can measure only what a balance change moved.
--
-- NEXTSTEPS item 6. sim_run.config_hash already answers "did anything move", which is the right
-- question for deciding whether two runs are comparable and the wrong one for deciding whether a
-- single build needs re-measuring: it covers every balance value in the game, so one changed skill
-- cooldown moves it and a sweep keyed on it alone would re-run the entire catalog. Run 1 is 4.3
-- million matchups and 35 hours of wall clock, so "re-run everything" is not a delta strategy.
--
-- These columns are the per-permutation answer. config_scope_hash covers exactly the config a row
-- depended on -- its weapon, its runes, its skills, its armour and its role's base health, and
-- nothing else -- so a run started with --changed can compare its freshly enumerated catalog against
-- a baseline run row by row and re-measure precisely the permutations whose own inputs moved.
--
-- See SimConfigDigest for how a scope is delimited, and BalanceCatalog.buildScopeHash for what is
-- deliberately left out of one (the weapon roll, the allocated level) and why each omission errs
-- towards re-measuring rather than towards carrying a stale row forward.

ALTER TABLE sim_build
    -- Digest over the attacker half: weapon + runes + skills + role base health.
    --
    -- Nullable rather than backfilled. Every run taken before this column existed has NULL here, and
    -- NULL is exactly right: those rows carry no claim about what they depended on, so they can never
    -- be matched as unchanged and a delta sweep against them re-measures everything. That is the
    -- correct behaviour for a baseline whose provenance is unknown, and it happens by itself.
    ADD COLUMN IF NOT EXISTS config_scope_hash TEXT;

ALTER TABLE sim_result
    -- Digest over the whole matchup: the build's scope, the target's scope, and the measurement
    -- terms that decide what a duel means (scenario, iterations, timeout, channel hold budget,
    -- engine version). All of it, because a row measured at one iteration must not be carried into
    -- a ten-iteration run as though the two were the same measurement.
    ADD COLUMN IF NOT EXISTS config_scope_hash TEXT,
    -- Which run actually ran the duels behind this row.
    --
    -- Equal to run_id for a row this run measured, and the older run's id for one carried forward
    -- unchanged. A delta run is deliberately a WHOLE run -- it copies the unchanged rows in rather
    -- than holding only the changed ones -- so that every existing dashboard, every gold mart and
    -- "latest run" keep working with no union step and no special case. This column is what keeps
    -- that honest: it is the difference between "measured today" and "measured in July and still
    -- valid", and without it a carried row would be indistinguishable from a fresh one.
    ADD COLUMN IF NOT EXISTS measured_run_id BIGINT REFERENCES sim_run (id) ON DELETE SET NULL;

-- The delta plan's lookup: given a baseline run, every matchup it holds and what that matchup
-- depended on. Covering the hash keeps the scan off the heap for a run in the millions of rows.
CREATE INDEX IF NOT EXISTS idx_sim_result_run_scope
    ON sim_result (run_id, config_scope_hash);
CREATE INDEX IF NOT EXISTS idx_sim_build_run_scope
    ON sim_build (run_id, config_scope_hash);
-- "How much of this run was actually measured" is the first question asked of a delta run, and it
-- is asked of every row of a multi-million-row table.
CREATE INDEX IF NOT EXISTS idx_sim_result_measured_run
    ON sim_result (run_id, measured_run_id);

-- ---------------------------------------------------------------------------

-- What a --changed sweep intends to cover, staged so the comparison against the baseline happens
-- in the database rather than on the server heap.
--
-- The obvious implementation is to read the baseline run's matchups into a Map and diff them in
-- Java, which is what the resume path does with findMeasuredMatchups. That does not survive this
-- table's actual scale: run 1 holds 4.3 million results, and a HashMap of matchup key to hash for
-- it is on the order of a gigabyte -- inside a Paper server's heap, next to a sweep that is about
-- to spawn hundreds of fake players. The plan goes the other way instead: the freshly enumerated
-- catalog is written here in batches, Postgres joins it against the baseline, and the only thing
-- read back is the set of matchups that actually need measuring -- which for a delta is small,
-- because that is the entire point of a delta.
--
-- UNLOGGED because it is derivable scratch: it is rebuilt from the catalog on every --changed run
-- and is worthless after the run plans, so paying WAL for it would be paying to make a temporary
-- table crash-safe. Not a TEMP table, because those are per-connection and the pooled async
-- context does not promise one connection across the several statements planning takes.
CREATE TABLE IF NOT EXISTS sim_delta_plan
(
    run_id            BIGINT NOT NULL REFERENCES sim_run (id) ON DELETE CASCADE,
    -- The attacker build, by the same fingerprint sim_build carries.
    fingerprint       TEXT   NOT NULL,
    target_role       TEXT   NOT NULL,
    target_armor      TEXT   NOT NULL,
    -- What this matchup depends on, as the freshly read config says it is now. A baseline row whose
    -- stored hash equals this is still a true measurement and is carried forward; anything else is
    -- re-measured.
    config_scope_hash TEXT   NOT NULL
);

-- The join key for the carry-forward, and for reading back what is left to measure.
CREATE INDEX IF NOT EXISTS idx_sim_delta_plan_run
    ON sim_delta_plan (run_id, fingerprint, target_role, target_armor);
