-- Which corner of its roll envelope the build's weapon was actually instantiated at.
--
-- V20260731_1 added weapon_damage_min/base/max and said, correctly at the time, that the min/max pair
-- "describes what the item could roll, not what it was" -- nothing in the sweep instantiated a weapon
-- at a rolled value, so every row was the base corner and the column would have been a constant.
--
-- The roll axis makes that false. A sweep can now equip a weapon with every stat at the bottom of its
-- band or the top of it, so weapon_damage_base is no longer the damage the duel was fought with and
-- there is nothing else on the row that says which figure was. Without this column a MIN row and a MAX
-- row of the same weapon are indistinguishable while reporting very different TTK, which is worse than
-- not sweeping the axis at all.
--
-- The armour roll deliberately has no column of its own: it rides on sim_result.target_armor, which is
-- already the free-form armour discriminator every dashboard groups by ('none' against 'role_set', now
-- also 'role_set_max'). Adding a parallel column would mean joining it in everywhere that string is
-- already understood.

ALTER TABLE sim_build
    -- 'base' | 'min' | 'max', matching SimStatRoll.id(). Defaulted rather than nullable: every row
    -- written before the axis existed was taken at the configured values, so 'base' is not a
    -- placeholder for unknown here -- it is the true value, and backfilling it keeps old and new rows
    -- comparable in one GROUP BY.
    ADD COLUMN IF NOT EXISTS weapon_roll TEXT NOT NULL DEFAULT 'base';

-- Paired with the damage index from V20260731_1, because the roll is only meaningful alongside the
-- figure it selects: "damage tier x roll" is the grouping the axis exists to make answerable.
CREATE INDEX IF NOT EXISTS idx_sim_build_weapon_roll
    ON sim_build (run_id, weapon_roll, weapon_damage_base);
