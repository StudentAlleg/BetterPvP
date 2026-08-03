-- Per-hit trace, written only when champions.simulation.hitTrace is on.
--
-- Runs 168 and 169 closed the armour-residue bug and uncovered what it had been masking: the sweep
-- is not reproducible. The same build, against the same target, over four repeat iterations, returns
-- different damage in 2.3% of MUTUAL matchups and 5.2% of ONE_WAY ones. Break Fall -- a passive that
-- cannot touch a duel -- diverges in hit count against its own baseline in 35 of 864 duels.
--
-- sim_duel_diagnostic can prove the divergence exists but not where it starts, because it is one row
-- per duel: it holds the totals, and the totals are the thing that disagrees. What the diagnostics do
-- say is that attacker_first_hit_tick is always identical and the divergence appears later, so the
-- first differing event is somewhere inside the fight and nothing before the fight is to blame.
--
-- This table is that inside. One row per landed hit, ordered on the duel's own tick axis, so two
-- iterations of one matchup can be diffed straight down until the first row that disagrees.
--
-- sim_trace already existed for roughly this purpose and has never held a single row in any run.
-- Rather than add a second table beside an empty one, it is widened here into the shape the diff
-- actually needs. The columns it lacked are the ones that identify *which* duel a row belongs to:
-- build_id alone cannot separate four iterations of the same matchup, which is precisely the
-- comparison being made.

-- Widening an empty table, so NOT NULL needs no backfill. Defaults are declared anyway and then
-- dropped, so the statement is safe if a row ever does appear between deploys.
ALTER TABLE sim_trace
    ADD COLUMN IF NOT EXISTS target_role  TEXT    NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS target_armor TEXT    NOT NULL DEFAULT '',
    -- Which iteration of the matchup this hit belongs to. The whole point of the table: repeats of
    -- one matchup are the comparison, and without this they are indistinguishable.
    ADD COLUMN IF NOT EXISTS iteration    INTEGER NOT NULL DEFAULT 0,
    -- Carried so a divergence can be checked against residency without joining back to the
    -- diagnostics. If two iterations differ and they ran on different platforms, that is a different
    -- suspect from two that differ on the same one.
    ADD COLUMN IF NOT EXISTS arena_index  INTEGER NOT NULL DEFAULT -1,
    -- Server ticks since the duel's first landed hit -- the same anchor sim_result measures TTK from,
    -- so a trace row and the row it explains share an axis. t_ms stays as the column the original
    -- table declared and is derived from this at write time; tick is the authoritative one, because
    -- the engine's clock is ticks and a millisecond figure invites comparison against a wall clock
    -- that nothing here runs on.
    ADD COLUMN IF NOT EXISTS tick         INTEGER NOT NULL DEFAULT 0,
    -- Ordering within a tick. Two hits can land on the same tick and the diff has to be stable, or
    -- it reports a divergence that is only a difference in insertion order.
    ADD COLUMN IF NOT EXISTS seq          INTEGER NOT NULL DEFAULT 0,
    -- 'attacker' or 'defender'. Under MUTUAL both sides land hits and only one of them is the build
    -- being measured; under ONE_WAY the defender never appears.
    ADD COLUMN IF NOT EXISTS actor        TEXT    NOT NULL DEFAULT '',
    -- Pre-mitigation damage beside the post-mitigation amount already on the table. A divergence in
    -- raw damage is a different bug from one where raw agrees and final does not: the first is the
    -- swing, the second is the mitigation pipeline.
    ADD COLUMN IF NOT EXISTS raw_amount   NUMERIC(10, 3);

ALTER TABLE sim_trace
    ALTER COLUMN target_role DROP DEFAULT,
    ALTER COLUMN target_armor DROP DEFAULT,
    ALTER COLUMN iteration DROP DEFAULT,
    ALTER COLUMN arena_index DROP DEFAULT,
    ALTER COLUMN tick DROP DEFAULT,
    ALTER COLUMN seq DROP DEFAULT,
    ALTER COLUMN actor DROP DEFAULT;

-- The diff's own access path: everything that identifies a duel, then the order within it. A
-- divergence hunt reads whole duels in tick order and compares them pairwise, so this is the index
-- that query wants and the old (run_id, build_id, t_ms) one is not.
CREATE INDEX IF NOT EXISTS idx_sim_trace_duel
    ON sim_trace (run_id, build_id, target_role, target_armor, iteration, tick, seq);
