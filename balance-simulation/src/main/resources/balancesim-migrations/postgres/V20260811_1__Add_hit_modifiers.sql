-- The modifier stack behind each landed hit.
--
-- Every damage number this project has ever stored is an OUTCOME: sim_result keeps a duel's
-- damage per hit, sim_trace keeps a single hit's raw and final amount. None of them keep the
-- one thing that explains the number -- which modifiers applied, in which operator, at which
-- operand. That gap is why the dashboards had to model skills from config instead of reading
-- them, and modelling from config has now been measured and does not work: scored against the
-- eleven skills that actually construct a SkillDamageModifier.Multiplier, config-key inference
-- gets precision 1/5 and recall 1/11, filing Defensive Stance and Riposte as flat and missing
-- eight outright.
--
-- With this column a skill's contribution is READ rather than inferred. DamageEvent's
-- composition is closed form --
--
--     final = (base * SUM(amplifying multipliers) + SUM(flats)) * PROD(reductive multipliers)
--
-- -- and order-independent, because every phase is a sum or a product and the priority sort
-- only decides what the damage log prints. So once the operands are stored, the whole
-- skill x weapon x rune cross is arithmetic over an existing EQUIPMENT sweep rather than duels
-- nobody can afford to run.
--
-- JSONB on sim_trace rather than a child table, for two reasons. The identity of a hit is
-- already six columns wide here (build, target role, target armour, iteration, tick, seq) and a
-- child table would repeat all of them per modifier; and a hit carries a handful of modifiers,
-- so the array is small and jsonb_array_elements unnests it as cheaply as a join would. The
-- volume argument matters: this table already runs to 96 million rows in a single sweep.
--
-- Shape, one object per applied modifier:
--   {"source":"Combo Attack","operator":"FLAT","operand":2.0,"priority":200,
--    "type":"ABILITY","reductive":false}
--
-- `reductive` is stored rather than derived even though ModifierResult.isReductive computes it
-- from the operand (FLAT <= 0, MULTIPLIER < 1.0). Storing what the game decided is the whole
-- point of measuring instead of modelling: if that rule ever changes, rows written before the
-- change still say what the pipeline actually did at the time.
--
-- Nullable with no default. Rows written before this column existed genuinely do not know their
-- stack, and an empty array would claim the hit had no modifiers -- which is a measurement, and
-- a wrong one. NULL means "not captured", '[]' means "captured, and there were none".
ALTER TABLE sim_trace
    ADD COLUMN IF NOT EXISTS modifiers JSONB;

COMMENT ON COLUMN sim_trace.modifiers IS
    'Applied damage modifiers for this hit, one object per modifier: source, operator '
        '(FLAT|MULTIPLIER), operand, priority, type, reductive. NULL means not captured; '
        '[] means captured and empty.';

-- Skills are looked up by name across a whole run, which is a scan of a very large table
-- otherwise. A GIN index over the array makes "every hit where Combo Attack applied" an index
-- lookup, and jsonb_path_ops is the smaller, faster operator class -- it only supports
-- containment (@>), which is the only predicate this column is queried with.
CREATE INDEX IF NOT EXISTS idx_sim_trace_modifiers
    ON sim_trace USING GIN (modifiers jsonb_path_ops);
