-- The armour tier axis.
--
-- Until now a role had exactly one armour set. SimEquipment picked, per slot, the lowest-sorting
-- registered piece carrying that role's RoleArmorComponent -- so a second tier would either have
-- replaced the first or been ignored entirely depending on how its key happened to alphabetise, and
-- the sweep would have reported a complete armour axis in both cases. target_armor therefore only
-- ever held 'none' or 'role_set' (plus its _min / _max roll variants), and "tier 0 vs tier 1 vs
-- tier 2 combat time" had nowhere to live.
--
-- target_armor keeps its meaning: set and roll folded into one discriminator, which is what every
-- stored row and every dashboard already groups by. These two columns split the set back out
-- beside it.
--
-- Why a column rather than parsing the discriminator in SQL. A tier comparison groups by tier, and
-- the mapping from set id to tier is an ordering by durability that only the sweep can compute --
-- it depends on the pieces' live HEALTH stats, not on the string. A dashboard deriving it with a
-- CASE would be a second source of truth that silently stops agreeing the moment a set is added
-- between them.

ALTER TABLE sim_result
    -- The set alone, roll suffix stripped: 'none', 'reinforced'. Nullable because every row written
    -- before this migration has a set that was never recorded -- see the backfill below for the
    -- only case where it can be recovered.
    ADD COLUMN IF NOT EXISTS target_armor_set  TEXT,
    -- 0 for bare, then 1 upwards by the set's summed HEALTH. Nullable rather than DEFAULT 0: a
    -- pre-existing armoured row is at an unknown tier, and defaulting it to 0 would place it on the
    -- ladder beside the bare rows it is meant to be compared against.
    ADD COLUMN IF NOT EXISTS target_armor_tier INTEGER;

-- The one part of the backfill that is knowable. 'none' meant tier 0 then and means tier 0 now, so
-- those rows join the ladder correctly. Armoured rows are deliberately left NULL: 'role_set' named
-- whichever set the role had at the time, and this migration cannot know that it was the same set
-- that is tier 1 today. Silver labels those 'unknown' rather than guessing.
UPDATE sim_result
SET target_armor_set = 'none', target_armor_tier = 0
WHERE target_armor = 'none'
  AND target_armor_set IS NULL;

-- The access path for the ladder query: every tier of one target role within one run, which is
-- exactly what a TTK-parity comparison scans.
CREATE INDEX IF NOT EXISTS idx_sim_result_run_tier
    ON sim_result (run_id, target_role, target_armor_tier);
