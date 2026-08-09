# Balance simulation — medallion pipeline

**Status:** built 2026-08-07, running against runs 1 and 4 (EQUIPMENT, `one_way`).
**Code:** [`pipelines/balance-sim/`](../../pipelines/balance-sim/).
**Covers:** NEXTSTEPS.MD items 4 (*"a full medallion, auditable pipeline to get actual
data"*) and 5 (*"display it in a useful way"*), plus the Backstab note at the bottom of
that file.

The simulator's job ends when a duel's outcome is a row in `sim_result`. This is what
happens to the row afterwards.

---

## 1. Why a pipeline and not more SQL

The three `sim_*` dashboards read Postgres directly, and for what they do — "what did
this sweep measure" — that is correct and should stay. The questions that motivated
this project are a different shape:

- *What is this rune worth?* → this row against the **no-rune row of the same weapon,
  roll, attacker role and target**.
- *Is a rune set worth more than its parts?* → a set's delta against the **sum of its
  members' individual deltas**.
- *What would Backstab add?* → nothing the sweep contains, because Backstab only fires
  from behind and the duels are head-on.
- *Did this balance change help?* → run A against run B, matched on build fingerprint.

Every one of those is a join of the fact table against a *baseline slice of itself*, or
a value the sweep does not hold at all. Written as Grafana panels they are slow, and
worse, unreviewable: `grafana/DASHBOARD_NOTES.md` is the record of what happened last
time this repo derived mechanics in SQL — a ComboAttack ramp re-solved as a quadratic,
Vengeance approximated with an equal-attack-rate assumption, ~200KB of CTEs that
nobody could safely change. DESIGN.md §1 calls that the third copy of combat truth.
The simulator removed it from the *mechanics*. This removes it from the *analysis*.

So: the joins happen once per sweep, in Spark, in files under version control with
tests. The dashboard gets `SELECT … WHERE run_id = $run` and at most a `GROUP BY`.
**If a panel needs a self-join, the mart is missing a column** — that is the rule that
keeps this from becoming the thing it replaced.

The second reason is auditability, and it is not a slogan here. Every transform's
assumptions are checked expectations, every dataset records its row count and its
source, and all of it lands in `sim_gold_data_quality`, which is a panel on the
dashboard rather than a log file. Section 5 lists the three real defects the
expectations caught within an hour of first running.

## 2. Tool choices

| Tool | Used for | Why |
|---|---|---|
| **Apache Spark** (PySpark 4.0) | every transform | The work is joins and aggregations over millions of rows, which is exactly what it is for, and the transformations stay readable as dataframes rather than as SQL strings. |
| **Apache Airflow** | orchestration | Two triggered DAGs, one per-layer task, retries per layer. |
| **Parquet on local FS** | the lake | Columnar, partitioned by run, and nothing to operate. |
| **Postgres** | serving gold | Grafana already has a datasource pointed at it. A parquet query engine would be a second thing to run for no gain. |
| **Apache Beam** | *not used yet* | Deliberate. See below. |

**On Beam.** It is the right tool for NEXTSTEPS item 3 — *"get this data off of real
players/combat"* — and the wrong one for this. Beam's value is one pipeline definition
that runs batch or streaming; live combat telemetry is an unbounded stream with
windowing and late-arriving events, which is its home ground. A simulation sweep is a
finished, bounded, immutable table that gets read exactly once. Adding Beam here would
mean a portability layer and a runner between the code and Spark, buying nothing this
workload needs. Deferring it also costs nothing later: **silver is the join point.** If
live per-build combat rows land in a `silver_live_result` with the same grain and the
same `fingerprint` key, every gold mart works on them unchanged, and only that one
ingest is streaming. That is the "sim vs live comparison" stretch goal in
`balance-simulation/NOTES.md`, and this layout is what makes it a new dataset rather
than a new pipeline.

## 3. The layers

### Bronze — a faithful snapshot

`sim_run`, `sim_build`, `sim_result`, `sim_duel_diagnostic`, `sim_trace`, plus a
snapshot of `grafana_config`. No cleaning, no casting, no unnesting; JSONB lands as the
strings JDBC returns. The only additions are `_ingested_at`, `_pipeline_run_id`,
`_source_table`.

The discipline earns its keep because the source is *live*: the plugin adds migrations,
a resumed sweep appends rows to a run that already has some, and `sim_run.status` flips
under a reader. Pinning the bytes a transform ran on is what lets a wrong gold figure
be attributed to the transform or to the source, rather than argued about.

`grafana_config` is snapshotted rather than run-scoped, because it is a mirror of the
live YAML that the sync service overwrites. The derived-skill transforms read their
balance values from it, so the snapshot a run used is the one its derived rows are
reproducible from.

### Silver — typed, unnested, and shaped

Four things happen.

**JSONB becomes columns**, against declared schemas rather than inferred ones. Schema
inference would type `dmg_per_hit_stddev` differently in a run where it happens to be
all-null, and two runs would then not union for a patch diff. Anything undeclared stays
reachable in `extras_raw` / `scenario_raw`, so a key a future engine version adds is
not lost before somebody notices it exists.

**Collections become bridges.** `runes`, `weapon_aliases`, `target_role_aliases`,
`skills` each get a row-per-element table. The alias bridges change answers rather than
query plans: the reduced tiers dedupe the weapon axis by stat profile, so a row keyed
`champions:alligators_tooth` *is* the measurement for `magnetic_maul` and `rake`.
Without the bridge a dashboard filtered to `magnetic_maul` returns nothing, and "never
swept" and "swept under another key" are opposite conclusions that look identical.

**The engagement's shape is recovered.** The one figure the sweep does not store and
every model needs is the **swing interval**, and it is exact rather than assumed:
`DuelOrchestrator` defines `dps_sustained` over the window from the first landed hit to
the lethal one, so `swing_interval_ticks = ttk_ticks / (hits_to_kill - 1)`. Alongside
it, `overkill_fraction` — damage past zero on the killing blow as a share of one hit,
which is the cliff TTK hides, since a build wasting most of a swing is one nerf away
from needing a whole extra one and `hits_to_kill` will not move until it does.

**Skills the sweep cannot measure are modelled.** Below.

### Gold — marts, and only marts

| Mart | Grain | Answers |
|---|---|---|
| `run` | run | the header every other mart is filtered by |
| `matchup` | build × target × provenance | the wide fact; drill-down |
| `weapon_axis` | weapon profile × roll × target | what a profile is worth, and the spread runes add |
| `rune_contribution` | single rune × baseline slice | what one rune is worth |
| `rune_set_synergy` | rune set × matchup | whether a set beats the sum of its parts |
| `skill_contribution` | skill × level × baseline slice | what a skill adds, measured or modelled |
| `run_diff` | fingerprint × target | run A vs run B |
| `data_quality` | expectation | the audit log |

Two design points carry over from DESIGN.md rather than being re-decided here.
`rune_contribution` attributes **only single-rune builds**, because a build carrying
several cannot be decomposed afterwards — that is why runes got their own sweep tier,
and inventing a decomposition in the mart would undo the reasoning. And `run_diff`
reports its three overlap classes (`both`, `only_a`, `only_b`) separately instead of
inner-joining, because a build present in one run and not the other contributes no
delta and a large non-overlap means the remaining deltas are a biased sample — which
is exactly the caveat an inner join deletes.

## 4. Derived skills, and Backstab specifically

`Backstab` is an assassin `PASSIVE_B` that adds a flat amount to a melee hit, but only
when attacker and target face within 60° of each other. The orchestrator's duels are
head-on, so it contributed exactly zero to every row of the EQUIPMENT sweep. Measuring
it properly needs a positioning axis the engine does not have.

It is modelled in silver instead, under four constraints that are the whole reason this
is acceptable rather than a return to SQL-derived mechanics:

1. **Provenance is a column, not a comment.** Every derived row carries
   `provenance='derived'`, the skill, the level, the bonus and the uptime. A gold
   expectation asserts the biconditional — a row is derived iff it names a skill — so
   nothing downstream can average a modelled figure into a measured one by accident.
2. **Parameters come from the game's config**, read out of the snapshotted
   `grafana_config`: `skills.assassin.backstab.baseDamage` (1.5),
   `increasePerLevel` (1.0), `maxlevel` (3). A balance change moves these rows. Note
   the live YAML also carries a stale `damageIncreasePerLevel` that *nothing in
   `Backstab.java` reads*; the transform uses the name the code uses, because picking
   the other would produce a plausible number that is wrong.
3. **The closed form is only used where it is exact.** A flat per-hit addition changes
   damage per hit and nothing else — not attack speed, not the count of swings inside
   the burst window — so
   `hits' = ceil(hp / dmg')`, `ttk' = (hits' - 1) × interval`, and burst scales
   linearly. Rows where `ceil(hp / dmg) ≠ hits_to_kill` are **excluded, not
   approximated**: something other than a constant per-hit amount decided that fight,
   and adding a flat term to the average is not a model of it. The exclusion rate is a
   logged expectation (100% coverage on the equipment tiers, which carry no skills).
4. **Uptime is a stated assumption.** `1.0` means every hit landed from behind. It is
   the upper bound the request asked for, it is on every row, and no panel shows a
   derived figure without it.

`FlatPerHitSkill` is written as a shape rather than as a Backstab special case, so the
next passive of this form is a config entry. `tests/test_derived_backstab.py` pins the
arithmetic against hand-checkable run-1 numbers (7.0 damage, 29 HP, five hits, 32
ticks) — necessary because a derived row has no measured counterpart anywhere to check
it against, so a drift in the model would go unnoticed.

Observed on run 4: +1.5/+2.5/+3.5 damage per hit at levels 1–3, mean TTK
2.103 s → 1.699/1.533/1.362 s, and level 3 takes a 29 HP kill from five hits to three.

## 5. What the expectations caught immediately

Listed because it is the argument for the layer, not a changelog.

1. **`champions:wind_blade` has an inverted damage envelope.** The sweep measured it at
   `min 7.0, base 6.0, max 8.0` — a min above the base, and *none* of the three
   matching the `5.0 / 6.0 / 7.0` that `champions/src/main/resources/configs/items/weapon.yml`
   declares. The dev server's plugin data folder had drifted from the repo. 14,292
   builds in run 4 are affected. This is a `warn`, not a failure: the run's other
   275,000 builds are fine and refusing to process them would help nobody.
2. **The config dimension was silently multiplied.** `grafana_config` is snapshotted
   per pipeline invocation, so reading the bronze dataset whole returned one row per
   (key × snapshot) — 6,952 rows over 1,738 keys — and every balance-value lookup
   became ambiguous. The uniqueness expectation failed on the fourth run against the
   same lake. Silver now pins one snapshot explicitly.
3. **Rune-set synergy over-counted by the target count.** The member sum was grouped by
   `build_id`, but a build is measured against every target, so a 2-rune set reported
   30 members attributed. The synergy figure was wrong by a factor of 15 and looked
   entirely plausible — a large negative number, which is a thing rune sets genuinely
   do. `runes_attributed <= rune_count` is now an expectation, and the mart's grain is
   the matchup.

4. **The weapon roll axis measured nothing.** `weapon_roll` selects a corner of the
   item's damage envelope, and run 1 swept all three. `champions:thornfang` has an
   envelope of 5 / 6 / 7 and `weapon_damage_applied` reports 5, 6 and 7 correctly —
   but `dmg_per_hit` is **6.000 on all three**, in `sim_result` itself and not only in
   the mart. The roll is recorded on the build row and every duel was fought with the
   base weapon. 1,620 of 1,620 weapon/target groups are affected.

   The cost is the point: the axis tripled the sweep, so roughly a third of a 35-hour
   run measured nothing new, and every `min` and `max` row is a duplicate of its `base`
   one. It is also invisible in the output, because triplicated rows average to exactly
   what the base rows alone would. `V20260805_1`'s stated premise — *"a MIN row and a
   MAX row of one weapon are indistinguishable while reporting very different TTK"* —
   is currently half true: they are indistinguishable, and they report the same TTK.

The third and fourth are the ones to keep in mind. Neither would have been found by
looking at the output.

## 6. Visualisation

One new generated dashboard, **Sim — Gold Balance Explorer** (uid
`betterpvp-sim-gold`), added to `grafana/generate_sim_dashboards.py` rather than to a
second generator — same principle as the rest: hand-maintained Grafana JSON is how the
last set became unsafe to change.

Panels: run header; weapon axis with its rune spread; overkill distribution; per-rune
contribution (with `hits_saved`, because swings are discrete and DPS deltas are not);
rune-set synergy filtered to sets whose every member was measured alone; skill
contribution with measured and modelled side by side; the wide fact for drill-down; and
the pipeline's audit log, on the dashboard so that a failed expectation is visible next
to the numbers it should discredit.

`provenance` is a single-select variable on purpose. A mean over both provenances is
not a figure about anything.

## 7. Not done, and why

- **Beam ingest for live combat data.** §2. Silver is the join point when it arrives.
- **`sim_trace` is carried but has never held a row.** Bronze ingests it and records
  the zero; the per-hit divergence work it was widened for is a different question from
  balance analysis.
- **`run_diff` has no dashboard.** The mart and the DAG exist and the existing
  `sim_patch_diff` dashboard covers the same ground against `sim_*`. Pointing it at the
  gold mart is a small follow-up and would make it a fingerprint-matched diff that also
  covers derived rows.
- **Defender-side anything.** `target_skills` is empty by construction upstream
  (DESIGN.md open question 9). The pipeline carries the columns and will populate them
  the day a sweep does.
- **Incremental processing.** A run is reprocessed whole. Runs are immutable once
  settled and a full rebuild of the largest one is minutes, so partitioned incremental
  loading would be complexity with no payoff yet.

## 8. Cost, measured on run 1

Run 1 is the completed EQUIPMENT sweep: 289,548 builds, 4,343,220 measured matchups,
35 hours of duels. On one laptop in `local[*]`:

| Layer | Time | Output |
|---|---|---|
| bronze | ~90 s | 4.34M results, 289.5K builds, 147.6K diagnostics, 1,738 config leaves |
| silver | ~2 min | 6.51M results (4.34M measured + 2.17M derived Backstab), plus bridges |
| gold — compute | ~7 min | 6.51M `matchup`, 6.42M `rune_set_synergy`, 89.9K `rune_contribution`, 7,290 `weapon_axis`, 2.17M `skill_contribution` |
| gold — publish | the slow part | JDBC insert of the two per-matchup marts dominates; `warehouse.skip_publish` exists for this |

So the transforms are minutes and the serving write is the bottleneck. That is the
right way round: the expensive part is a one-time push into a table the dashboard then
reads in milliseconds, which is the whole trade this pipeline is making against the
old approach of computing the joins per panel load.
