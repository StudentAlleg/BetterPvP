-- Per-duel diagnostics, written only when champions.simulation.duelDiagnostics is on.
--
-- Exists to answer one question that sim_result cannot: run 164 showed that adding a skill
-- which provably does nothing -- a pure passive with no button, or Blood Compass, which is a
-- compass -- flipped a WARLOCK vs ASSASSIN/none matchup from 10/10 attacker wins to 0/10
-- attacker deaths, with damage fully deterministic (dmg_per_hit_stddev = 0 on every row).
-- Something outside the build decides that fight, and every figure on sim_result is a mean
-- over ten duels, so the deciding difference is averaged away before it is ever stored.
--
-- One row per duel rather than per matchup, for that reason. The typed columns are the ones a
-- hypothesis is tested against directly; detail carries the rest, the way sim_result.extras does.

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
