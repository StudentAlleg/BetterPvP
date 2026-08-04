-- The absolute server tick a duel's trace is anchored on.
--
-- Every other tick in sim_trace is relative to the duel's first landed hit, deliberately, so that
-- repeat iterations of one matchup can be diffed against each other. That normalisation is exactly
-- what hides the suspected cause of the divergence that survived the Tormented Soil fix: periodic
-- skill machinery is scheduled against the global tick counter rather than against the duel.
-- UpdateEventExecutor keys its schedule on the delay value server-wide, and BukkitRunnable's
-- runTaskTimer counts from whenever it was scheduled, so a duel beginning on an odd global tick can
-- see a period-2 skill step one tick later than a duel beginning on an even one. Storing the anchor
-- in absolute terms makes the phase a column that can be grouped on rather than an inference.
ALTER TABLE sim_trace
    ADD COLUMN IF NOT EXISTS anchor_tick INTEGER NOT NULL DEFAULT -1;

ALTER TABLE sim_trace
    ALTER COLUMN anchor_tick DROP DEFAULT;
