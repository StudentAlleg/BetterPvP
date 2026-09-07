-- Which allocated levels of a skill the sweep could not tell apart.
--
-- The problem this solves is legibility, not cost. A SKILLS sweep measures every level of every
-- skill, and for a great many of them the levels are indistinguishable in the fight: on run 13,
-- 35 of the 66 role x skill combinations swept were flat across every level they reached --
-- Resilience, Defensive Aura, Wreath and Threatening Shout at all five, Arctic Armour, Blizzard and
-- Immolate at all three. The per-skill curve drew five equal bars each for those, and the skills
-- whose levels DO buy something were lost among them.
--
-- WHY THIS IS A VIEW OVER MEASUREMENTS RATHER THAN A CHECK AGAINST CONFIG.
--
-- The tempting implementation is to read skills/skills.yml, look for the *PerLevel keys, and call a
-- skill flat when they are all zero. That is a second source of truth for what a skill does, and
-- this project exists to remove those -- the same inference has already been scored on this
-- dataset for a related question and got precision 1/5 and recall 1/11 (see sim_trace.modifiers).
--
-- Tormented Soil is the worked example of it failing in both directions at once. Its
-- damageIncreasePerLevel is 0.0, so a config reading calls its damage flat; but
-- cooldownDecreasePerLevel is 2.0 and rangeIncreasePerLevel is 0.5, so the same reading says the
-- levels differ. Only a measurement settles which of those reaches the numbers, and it does: the
-- skill comes out as four bands, 1 / 2-3 / 4 / 5. The shorter cooldown is real and the flat damage
-- key was the wrong thing to look at. This view asks the rows.
--
-- The consequence to keep in mind: this is a statement about what the sweep MEASURED, not about
-- what the skill IS. A level that buys something the scenario cannot exercise -- a defensive tier
-- under ONE_WAY, a bow archetype the engine cannot drive -- reads as flat here and is flat in this
-- data. That is the correct answer to "is this row worth showing separately" and the wrong answer
-- to "is this level worth having", and only the first question is being asked.

CREATE OR REPLACE VIEW sim_skill_level_band AS

-- One row per (skill, level, matchup) on the single-skill builds only.
--
-- Restricted to one filled slot because that is the only case where a level is attributable at all:
-- on a FULL build several skills contribute to one damage figure and there is no way to say which
-- of them a level moved. The jsonb_typeof guard is not decoration -- sim_build.skills defaults to
-- '{}', so a row that never had an allocation written holds an object and jsonb_array_length would
-- raise on it.
WITH allocation AS (SELECT res.run_id,
                          bld.role,
                          s ->> 'skill'                                            AS skill,
                          (s ->> 'allocated_level')::int                           AS allocated_level,
                          -- The matchup a level is compared within. Two levels must be compared
                          -- against the same weapon, the same runes, the same roll and the same
                          -- defender, or the difference being measured is the loadout's.
                          bld.weapon,
                          bld.runes,
                          bld.weapon_roll,
                          res.target_role,
                          res.target_armor,
                          res.dmg_per_hit,
                          -- The run's own measured noise on this row. Used as the tolerance below
                          -- rather than a constant: the sweep is not bit-reproducible, so a fixed
                          -- epsilon is either tighter than the noise (nothing ever collapses) or
                          -- looser than a real difference (something real gets hidden). The row
                          -- states how much it wobbled; that is the number to beat.
                          COALESCE((res.extras ->> 'dmg_per_hit_stddev')::numeric, 0) AS dmg_stddev,
                          -- Ticks rather than ttk_s because ticks are the clock the duel is
                          -- actually measured on; seconds are derived from them.
                          (res.extras ->> 'ttk_ticks_mean')::numeric                AS ttk_ticks
                   FROM sim_result res
                            JOIN sim_build bld ON bld.id = res.build_id
                            CROSS JOIN LATERAL jsonb_array_elements(bld.skills) AS s
                   WHERE jsonb_typeof(bld.skills) = 'array'
                     AND jsonb_array_length(bld.skills) = 1),

     -- Whether each level is indistinguishable from the one below it, across every matchup the two
     -- share. Compared against the level below rather than against level 1 so that a skill which is
     -- flat up to 3 and then jumps reads as two bands rather than as four separate levels.
     paired AS (SELECT cur.run_id,
                       cur.role,
                       cur.skill,
                       cur.allocated_level,
                       -- The share of shared matchups that agree, rather than whether all of them
                       -- do.
                       --
                       -- "All of them" is the rule this started with and it does not survive the
                       -- scale: a level pair here is compared across 1,404 matchups, and the TTK
                       -- noise measured below lands on about 1% of them, so a single outlier
                       -- anywhere splits the band and at 1,404 draws one is close to certain. Leech
                       -- is the worked example -- byte-identical damage at every level, agreeing on
                       -- all but a handful of matchups, drawn as five separate bars.
                       --
                       -- The threshold is read off the data rather than picked. Across run 13's 186
                       -- adjacent-level pairs the agreement rate is sharply bimodal: 93 pairs agree
                       -- on every single matchup and 54 disagree on more than half of them, with
                       -- only 39 anywhere in between. A real level effect is systematic and a noisy
                       -- one is not, and the gap between those two populations is wide enough that
                       -- any cut in [0.95, 1.0) gives nearly the same answer. 0.99 is the one
                       -- chosen, because it is also where the TTK noise rate sits, so one number is
                       -- doing both jobs.
                       --
                       -- agreement_rate is kept as a column rather than consumed by the comparison,
                       -- so a band that only just formed and a band that formed unanimously are
                       -- distinguishable. A threshold that hides how close its calls were is a
                       -- threshold nobody can argue with.
                       --
                       -- Each term is COALESCEd to a definite answer before being counted: an
                       -- incomparable pair must count as a difference, not be skipped, or a handful
                       -- of readable matchups among many unreadable ones would carry the band alone.
                       AVG(CASE WHEN
                               COALESCE(ABS(cur.dmg_per_hit - prv.dmg_per_hit)
                                            -- One ULP of NUMERIC(10,3) as the floor, for the rows
                                            -- where damage came out deterministic and the stddev is
                                            -- genuinely 0.
                                            <= GREATEST(cur.dmg_stddev, prv.dmg_stddev, 0.001),
                                        cur.dmg_per_hit IS NULL AND prv.dmg_per_hit IS NULL)
                                   -- One server tick, the granularity the duel is timed at, or 5% of
                                   -- the longer duel, whichever is larger.
                                   --
                                   -- The relative term is the one number in this view that is a
                                   -- judgement rather than a measurement, so here is the measurement
                                   -- it was calibrated against. Over the 69,834 adjacent-level pairs
                                   -- of run 13 whose damage per hit was byte-identical -- pairs where
                                   -- the level provably changed nothing about the swing -- the
                                   -- relative TTK difference was exactly 0 at the median, at p90 and
                                   -- at p95, 3.2% at p99, and 42.9% at worst. So TTK is reproducible
                                   -- almost always and occasionally is not, and a tolerance of one
                                   -- tick alone splits bands on that tail: Leech measured 33, 37, 34
                                   -- and 34 ticks across four levels of one matchup at identical
                                   -- damage, and drew four bars for what is one measurement.
                                   --
                                   -- 5% clears p99 while staying far under the effects worth seeing.
                                   -- What it costs is a level that moves TTK by less than 5% while
                                   -- leaving damage per hit byte-identical -- which is a level that
                                   -- bought almost nothing, and is the case this view exists to fold.
                                   -- Below about 20 ticks the one-tick floor is the wider of the two
                                   -- and binds instead.
                                   --
                                   -- Both NULL means both timed out, which is agreement; one NULL
                                   -- means one level killed and the other did not, which is the
                                   -- largest difference there is.
                                   AND COALESCE(ABS(cur.ttk_ticks - prv.ttk_ticks)
                                                    <= GREATEST(1, 0.05 * GREATEST(cur.ttk_ticks,
                                                                                   prv.ttk_ticks)),
                                                cur.ttk_ticks IS NULL AND prv.ttk_ticks IS NULL)
                               THEN 1.0 ELSE 0.0 END)                  AS agreement_rate,
                       COUNT(*)                                        AS matchups_compared
                FROM allocation cur
                         JOIN allocation prv
                              ON prv.run_id = cur.run_id
                                  AND prv.role = cur.role
                                  AND prv.skill = cur.skill
                                  AND prv.allocated_level = cur.allocated_level - 1
                                  AND prv.weapon = cur.weapon
                                  AND prv.runes = cur.runes
                                  AND prv.weapon_roll = cur.weapon_roll
                                  AND prv.target_role = cur.target_role
                                  AND prv.target_armor = cur.target_armor
                GROUP BY 1, 2, 3, 4),

     levels AS (SELECT DISTINCT run_id, role, skill, allocated_level FROM allocation),

     -- A running count of the boundaries crossed so far, which numbers the contiguous runs of
     -- indistinguishable levels. A level with no predecessor row to compare against -- level 1, or a
     -- level whose neighbour was never measured on any shared matchup -- opens a new band, because
     -- "not compared" is not evidence of sameness.
     banded AS (SELECT lvl.run_id,
                       lvl.role,
                       lvl.skill,
                       lvl.allocated_level,
                       COALESCE(p.matchups_compared, 0)                        AS matchups_compared,
                       p.agreement_rate,
                       SUM(CASE WHEN COALESCE(p.agreement_rate, 0) >= 0.99 THEN 0 ELSE 1 END)
                       OVER (PARTITION BY lvl.run_id, lvl.role, lvl.skill
                           ORDER BY lvl.allocated_level)                       AS band
                FROM levels lvl
                         LEFT JOIN paired p
                                   ON p.run_id = lvl.run_id
                                       AND p.role = lvl.role
                                       AND p.skill = lvl.skill
                                       AND p.allocated_level = lvl.allocated_level)

SELECT run_id,
       role,
       skill,
       allocated_level,
       band,
       matchups_compared,
       -- How unanimously this level was folded onto the one below it: 1.0 when every shared matchup
       -- agreed, just over 0.99 when the band only formed because the threshold allowed it, NULL for
       -- a level with nothing below it to compare against. The number behind the call, kept so the
       -- call can be argued with.
       agreement_rate,
       -- The weakest agreement anywhere in this band. A band of three levels is only as sound as the
       -- shakiest join in it, and reading the per-level rate alone would miss that.
       MIN(agreement_rate) OVER w                                      AS band_min_agreement,
       MIN(allocated_level) OVER w                                     AS band_min_level,
       MAX(allocated_level) OVER w                                     AS band_max_level,
       COUNT(*) OVER w                                                 AS levels_in_band,
       -- The label a panel groups and renders by: '3' for a level that stands alone, '1-5' for a
       -- run of levels the sweep could not separate. Rendered here rather than in each panel so the
       -- dashboards cannot drift from each other about what a band is.
       CASE
           WHEN (MIN(allocated_level) OVER w) = (MAX(allocated_level) OVER w)
               THEN (MIN(allocated_level) OVER w)::text
           ELSE (MIN(allocated_level) OVER w)::text || '-' || (MAX(allocated_level) OVER w)::text
           END                                                         AS band_label,
       -- How many bands this skill has in this run. 1 means the level axis bought nothing measurable
       -- anywhere, which is the row a balance pass wants to see. Read as MAX rather than
       -- COUNT(DISTINCT ...) because window functions do not take DISTINCT in Postgres -- and it is
       -- the same number either way: band is a running count of boundaries crossed, so it starts at
       -- 1 and rises by one per band.
       MAX(band) OVER (PARTITION BY run_id, role, skill)               AS bands_for_skill
FROM banded
WINDOW w AS (PARTITION BY run_id, role, skill, band);

COMMENT ON VIEW sim_skill_level_band IS
    'Contiguous runs of allocated levels a run could not measurably tell apart, per skill, from '
        'single-skill builds only. Two levels are in one band when at least 99% of the matchups they '
        'share agree on damage per hit (within the rows own measured stddev) and on TTK (within one '
        'tick or 5%, whichever is wider); agreement_rate carries how unanimous each call was. Panels '
        'group by band_label so a flat skill renders one row instead of five. A statement about '
        'what was measured, not about what the skill is.';
