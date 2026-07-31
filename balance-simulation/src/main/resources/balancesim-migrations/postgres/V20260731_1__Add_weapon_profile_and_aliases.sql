-- Weapon stat profile on the build, and the alias lists the reduced sweep tiers produce.
--
-- Two separate needs, both of which the existing schema answers only by inference:
--
-- 1. What a weapon actually hit for. sim_build.weapon is a registry key, and the damage behind it
--    lives in the item config the run was taken under -- which is exactly what will have changed by
--    the time anyone diffs two runs. Denormalising the figures makes a row self-describing.
--
-- 2. What a row stands for. The BASELINE and LOADOUT tiers deduplicate the weapon axis by stat
--    profile and collapse skill-less unarmoured targets of equal health, so one measured row now
--    covers permutations that have no row of their own. Without the alias lists a dashboard cannot
--    tell "this weapon was never swept" from "this weapon was swept under another key", and those
--    are opposite conclusions.

ALTER TABLE sim_build
    -- The figure the pipeline applies: MeleeDamageStatHandler calls event.setDamage(stat.getValue()),
    -- and ItemFactory.create rolls nothing, so base is what every duel in the sweep swung for.
    ADD COLUMN IF NOT EXISTS weapon_damage_base NUMERIC(10, 3),
    -- The roll envelope from the item's damage.min/damage.max config. Recorded rather than measured:
    -- nothing in the sweep currently instantiates a weapon at a rolled value, so these describe what
    -- the item could be, not what it was. Two weapons with equal base and different envelopes are
    -- still distinct profiles and are never folded together.
    ADD COLUMN IF NOT EXISTS weapon_damage_min NUMERIC(10, 3),
    ADD COLUMN IF NOT EXISTS weapon_damage_max NUMERIC(10, 3),
    ADD COLUMN IF NOT EXISTS weapon_attack_speed_base NUMERIC(10, 3),
    ADD COLUMN IF NOT EXISTS weapon_attack_speed_min NUMERIC(10, 3),
    ADD COLUMN IF NOT EXISTS weapon_attack_speed_max NUMERIC(10, 3),
    -- The skill slot the weapon serves when held, or 'none'. Part of the dedupe key: a sword and an
    -- axe with identical numbers drive different builds, because Skill.getLevel requires isHolding.
    ADD COLUMN IF NOT EXISTS weapon_slot TEXT,
    -- Every weapon key this row's measurement covers, this row's weapon first. A single-element array
    -- on the un-reduced tiers, where each weapon was measured in its own right.
    ADD COLUMN IF NOT EXISTS weapon_aliases JSONB NOT NULL DEFAULT '[]'::JSONB;

ALTER TABLE sim_result
    -- Every target role this result covers, target_role first. Longer than one element only where an
    -- unarmoured skill-less defender was shared between roles of equal health -- a reduction that is
    -- only sound in a ONE_WAY sweep, which is why it is recorded per result rather than per run.
    ADD COLUMN IF NOT EXISTS target_role_aliases JSONB NOT NULL DEFAULT '[]'::JSONB;

-- Dashboards group by weapon damage far more often than they filter on a single weapon key, since
-- "what does this damage tier buy" is the question the weapon axis exists to answer.
CREATE INDEX IF NOT EXISTS idx_sim_build_weapon_damage
    ON sim_build (run_id, weapon_damage_base);
