# Resume here

State as of 2026-08-13, commits `218336b3` (repo) and `27f89c2` (grafana). Everything below
is a fresh-shell instruction; nothing depends on a session still being open.

## Before anything

Docker Desktop is not set to autostart, and the warehouse lives in a container that stops with
it. If Postgres on 5002 is unreachable, that is why:

```bash
docker start postgres          # container name is literally "postgres"
until docker exec postgres pg_isready -U user; do sleep 2; done
```

Every pipeline command needs this in the shell or parquet writes fail on Windows with a
`winutils`/`HADOOP_HOME` error:

```bash
export HADOOP_HOME=/c/Users/Owen/hadoop
export PATH="$HADOOP_HOME/bin:$PATH"
cd pipelines/balance-sim
```

## 0. What the last chain actually finished

The whole chain ran to completion — nothing was left mid-write, and no process was still alive
at the end:

```
DONE all --run-id 1    exit=0   11:24:49
DONE all --run-id 7    exit=1   11:30:40   <- OOM, see section 1
DONE all --run-id 13   exit=0   11:43:41
DONE diff 1-7          exit=0   12:03:29
DONE diff 12-13        exit=0   12:04:35
ALL FINISHED
```

**The diff contents were never verified** — Docker Desktop stopped before the check ran, so the
counts below are expectations, not observations. Confirm them first:

```sql
SELECT diff_key, overlap, count(*) n,
       count(*) FILTER (WHERE role IS NULL)          AS role_null,
       count(*) FILTER (WHERE skill_set_key IS NULL) AS sk_null
FROM sim_gold_run_diff GROUP BY 1, 2 ORDER BY 1, 2;
```

* **`1-7` still needs re-running after run 7 is reprocessed.** It succeeded against run 7's
  *stale* gold parquet — the one with no `skill_set_key` — which Spark tolerated by reading the
  column as NULL rather than failing. So the descriptor fix did apply (the 4,312,263 `only_b`
  rows should now carry a real role, weapon and rune set) but every `skill_set_key` on run 7's
  side is NULL. Re-run it once section 1 is done.
* **`12-13` is new and should be sound** — both sides were reprocessed with the column. Expect
  near-total overlap, since runs 12 and 13 share a build space; a large `only_a`/`only_b` there
  would mean something is wrong.

If a future diff *is* interrupted, the damage is bounded: `_replace_slice` does `DELETE FROM
sim_gold_run_diff WHERE diff_key = 'A-B'` then appends, so it costs that one key and nothing
else, and re-running the same diff repairs it. Nothing needs fixing by hand.

## 1. Re-run run 7 — the one thing that is unfinished

Run 7 failed with a driver OOM ingesting `sim_duel_diagnostic` (3,619,230 rows, 4,071 MB of
JSON). **The cause is fixed** — `jdbc.read` now sizes partitions by row width, per table, so
that read drops from ~124 MB per task to ~15 MB. The run itself was never re-attempted.

```bash
python -m jobs.cli all --run-id 7        # ~50 min; run 1 took 55
```

Watch for `ERROR` and `OutOfMemory` in `logs/`. If it OOMs again, lower
`source.rows_per_partition_by_table.sim_duel_diagnostic` below 10000 in `conf/pipeline.yml`;
raising `read_partitions_by_table` alone will not help once the row budget is the binding term.

Then the two diffs, which need run 7's gold parquet to exist with `skill_set_key`:

```bash
python -m jobs.cli diff --run-a 1 --run-b 7      # ~5 min
python -m jobs.cli diff --run-a 12 --run-b 13    # ~5 min
```

`sim_gold_run_diff` currently holds the **pre-fix** 1-7 diff: 10,827,093 rows, of which all
4,312,263 `only_b` rows have null descriptors. The re-run replaces it.

### Check it landed

```sql
SELECT run_id, health, published, checks_total, errors FROM sim_gold_run_health ORDER BY run_id;
```

Want: runs 1, 7, 12, 13 all `published = true` and `errors = 0`. Runs 1, 12, 13 already are.
Panel 10 of the Gold Balance Explorer shows the same thing.

## 2. Then: measure `direction`, and only then project

The goal is skill × weapon × rune permutations, projecting measured skill modifiers across the
weapon catalog — the SKILLS sweeps only ran on **5 `core:` tier-0 weapons with 0–1 runes**,
while run 7 covers **17 `champions:` weapons with 0–4 runes**.

Do **not** infer a modifier's direction from `reductive`. Fortify reduces incoming *and*
outgoing damage by design, so `reductive` says nothing about which axis an operand belongs on,
and a projection that assumes "reductive = defence only" overstates a Fortify build's DPS by
10–30%.

Direction is directly measurable, and run 13 is what makes it measurable:

* `sim_trace.actor` is `attackerId.equals(hit.damager()) ? ATTACKER : DEFENDER` — who **dealt**
  the hit.
* `sim_trace.build_id` is always the measured (attacking-side) build.
* Both runs 12 and 13 have **zero target skills across all 250,920 results**, so every skill
  modifier on either actor's hit belongs to the measured build. Attribution is unambiguous.

So per (skill, modifier_source): appears only under `attacker` → `outgoing`; only under
`defender` → `incoming`; both → `symmetric`. Run 12 is ONE_WAY and has no `defender` rows at
all, so it can only ever say `outgoing (unverified)` — direction must come from run 13.

Add it to `sim_gold_skill_modifier` (`sql/gold_ddl.sql`) by dropping the `actor = 'attacker'`
filter in the `applied` CTE, carrying `actor`, and adding
`COUNT(*) FILTER (WHERE actor = 'attacker'/'defender')` to `agg`. Keep the existing grain.

Then gate the projection on `direction` **and** `scaling_class`, excluding:

* `ramping` — no single operand exists at a fixed level (Combo Attack, Overwhelm's 185 values)
* `silent` — never reached the damage pipeline
* the **nine ally-targeting skills** below

Composition is closed form and order-independent —
`final = (base × Σ amplifying + Σ flats) × Π reductive` — so a projected row is exact
arithmetic over measured inputs, provided the inputs mean what they say.

## 3. Blocked on a plugin rebuild + new sweep

`UtilPlayer.getNearbyPlayers` / `UtilEntity.getNearbyEntities` now stamp `EntityProperty.ENEMY`
instead of the caller's requested property, so a missing relation listener fails closed. Runs 12
and 13 were measured **before** that fix, when ally-targeted buffs landed on the opponent.

These nine are **unmeasured, not zero**, until the plugin is rebuilt and a SKILLS sweep re-run:
ArcticArmour, BloodBarrier, Bloodshed, Cleanse, DefensiveAura, HolyLight, MarkOfTheWolf,
SoulBonds, TormentedSoil.

Falsifiable prediction for the re-run: Blood Barrier goes from a false `percent 0.7` to `silent`
under ONE_WAY (the caster shields only itself and is never damaged), and to a genuine incoming
reduction under MUTUAL. Tormented Soil's "1.33 that does not scale across five levels" should
also change.

The lang-file fix (`balancesim_en.properties`, 34 `<N>` → `{N}` placeholders) also needs that
rebuild.

## Run inventory

| Run | Scope | Scenario | State |
|---|---|---|---|
| 1 | EQUIPMENT | one_way | reprocessed, published. **Bugged run** — exists only to diff against 7 |
| 7 | EQUIPMENT | one_way | **needs re-run** (OOM, cause fixed). Weapon/rune breadth lives here |
| 12 | SKILLS | one_way | reprocessed, published — 61 checks, 0 errors |
| 13 | SKILLS | mutual | reprocessed, published — 61 checks, 0 errors. Same 20,910 builds as 12 |

Careful reading `sim_gold_run_health` for run 7: it says `published = true`, and that is
correct but misleading — those are the 2026-08-10 marts. The re-run OOM'd before publishing, so
run 7's rows and its gold parquet still have no `skill_set_key`. The view reports *whether* a
run published, not *when*, and there is currently nothing that would tell you a published run is
stale.

12 and 13 share an identical build space and differ only in scenario, so 12-vs-13 is a
controlled comparison — unlike 1-vs-7, which overlapped only 10% (1,078,290 `both` against
5,436,540 `only_a` and 4,312,263 `only_b`).

## Two open questions, not started

* **Attacker role may not affect outgoing damage.** Mean ΔDPS was identical to the cent (1.70)
  across KNIGHT, WARLOCK, MAGE, RANGER and BRUTE over identical row counts (120,645 each), with
  only ASSASSIN differing (2.04 over 475,065). Confirm before drawing any per-role conclusion.
* **`sim_duel_diagnostic` has no consumer** and is the largest thing in bronze — 4 GB of JSON
  for run 7 alone, and the direct cause of the OOM above. Either gate its ingest behind a flag
  or give it a reader.

Full detail, including the derivations behind all of this, is in `grafana/DASHBOARD_NOTES.md`.
