# Running a baseline sweep

*Written 2026-08-08, after run 1 turned out to have measured three things that were not
what they said they were.*

The procedure for producing a sweep whose numbers can be trusted as the reference every
later run is diffed against. Follow it in order — steps 1-3 are the ones run 1 skipped, and
step 1 comes first for a reason spelled out there.

---

## 0. What went wrong last time

Run 1 is 35 hours of wall clock and 4.34M matchups, and three separate defects mean parts
of it measure nothing:

| | Effect on run 1 |
|---|---|
| Weapon roll never applied | every `min`/`max` row duplicates its `base` row — **2/3 of the weapon axis** |
| Armour roll never applied | `role_set_max` targets have byte-identical HP to `role_set` |
| `wind_blade` config drift | 14,292 builds swept against `min 7.0 / base 6.0 / max 8.0`, a min above its own base |

None of these were visible in any aggregate. Triplicated rows average to exactly what the
base rows alone would, so every dashboard looked healthy. All three are fixed; this runbook
is how you avoid finding a fourth one in a month.

## 1. Build and deploy the plugin jars

The roll fix lives in `SimStatRoll`, so a sweep run against an old jar reproduces the
original defect exactly and silently.

```bash
./gradlew :core:shadowJar :champions:shadowJar :balance-simulation:shadowJar
```

Then, from the server directory, `copydevsimulation.bat` — which clears the jars belonging
to whichever profile was there before and robocopies `build/devsimulation/` in. The server
hosts one profile at a time and gets switched deliberately, so which jars are present is a
choice, not drift; a jar older than the repo usually just means that module has not changed.

Two things worth knowing if you ever copy by hand instead:

- **`<module>/build/libs/` is the wrong directory.** It holds the *thin* jar — core's is
  5 MB against the shaded 28 MB. Deploying it looks fine and fails at class load. The
  artifact the server loads is `build/devsimulation/`, the output bucket the root
  `build.gradle.kts` declares for exactly this set of three plugins.
- **`:module:build` does not re-shade its dependencies.** It compiles their thin jars for the
  classpath, so a build that just succeeded can still leave a days-old fat jar in the bucket.
  Ask for `shadowJar` by name, as above.

Deploy **before** the configs, not after: `walkAndSaveFiles` writes configs *from the jar*,
so regenerating them against an older jar does not merely leave the old values — it rewrites
them, and they then look freshly generated.

## 2. Regenerate the server's configs

`BPvPPlugin.walkAndSaveFiles` copies a packaged config **only if the file does not already
exist**, so a data folder freezes at whatever it first wrote and every later balance change
is invisible to it. That is how `wind_blade` drifted.

```bash
# Stop the server FIRST. saveConfig() writes the in-memory config back on shutdown, so a
# running server recreates every file you delete, stale values and all.
python scripts/config_sync.py                 # what differs, by value not by bytes
python scripts/config_sync.py --regenerate    # delete them; the plugin rewrites from the jar
# start the server
python scripts/config_sync.py                 # confirm: only server-only keys remain
```

`--regenerate` rather than `--apply` because it is the only mode that clears **stale keys**
as well as stale files. Skills save their Java defaults to disk on load, so a default that
changed in code since the file was written is overridden by the saved copy forever;
deleting the file is the only thing that clears it. Everything removed is backed up to
`build/config-sync-backup/<timestamp>/`.

Scope is `items/**` and `skills/**` — what `SimConfigDigest` walks. `config.yml` is
excluded deliberately: this dev server's has 61 values and 52 keys that differ from the
repo's defaults (command ranks, activity thresholds), all intentional, none of them
anything a duel measures.

## 3. Check the capture settings

Both are now on by default, and the ceilings are sized for a full `EQUIPMENT` sweep rather
than the `SKILLS` sweeps they were set for:

| Setting | Value | Full-sweep cost |
|---|---|---|
| `hitTrace` | `true` | ~52.8M rows, **~28 GB** (565 B/row measured) |
| `hitTraceMaxRows` | 80,000,000 | ceiling ~45 GB, room for `MUTUAL` |
| `duelDiagnostics` | `true` | ~4.34M rows, **~6.7 GB** (1,620 B/row measured) |
| `duelDiagnosticsMaxRows` | 6,000,000 | ~9.7 GB |

**The sweep is bound by wall clock, not storage.** Run 1 spent 35 hours to produce 19 GB.
Capture that records what the duels are already doing is therefore close to free in the
currency that is actually scarce, which is the argument for having it on.

Check two volumes before starting, because they are not the same one:

```bash
docker run --rm -v <postgres-volume>:/d alpine df -h /d   # the database: ~920 GB free
df -h /g                                                  # the lake: ~1.04 TB free
```

The lake lives at `G:/betterpvp-lake`, not beside the code. C: has 31.6 GB free and bronze
alone is ~28 GB on a full sweep, so the default `./data` would have filled the system drive.
The run-1 lake was moved there and verified (1,581 files, 586,029,733 bytes, identical on
both sides); a copy is still at `pipelines/balance-sim/data` and can be deleted once you are
happy.

G: is exFAT with a **1 MB allocation unit**, so budget on file count as well as bytes: run
1's lake is 586 MB of content but 2.0 GB on disk, because 561 of its files are under 4 KB
and each one costs a full megabyte. Large datasets are unaffected.

Raising `iterations` is the expensive knob: it multiplies wall clock linearly, and run 1
was taken at `iterations: 1`. Per-hit variation is better bought with `hitTrace`, which
costs disk instead of time. Repeat *iterations* buy something different — they are the only
way to see the sweep's known non-reproducibility (2.3% of `MUTUAL` matchups, 5.2% of
`ONE_WAY` ones diverge between repeats) — so raise them deliberately, for that, not for
resolution on damage.

## 4. Sweep

```
/simulate EQUIPMENT --changed
```

`--changed` rather than a bare sweep, and it is not a shortcut here — it is what makes
this affordable. A delta run is still a *whole* run (unchanged rows are carried forward,
`measured_run_id` records which run's duels produced each), so the output is a complete
baseline either way. What it saves is re-measuring what genuinely did not move.

After steps 1-2 the delta re-measures:

- **every `min` and `max` roll**, weapon and armour — the roll is now part of the matchup
  scope hash whenever it is not `BASE`, precisely because the fix changed what those rows
  mean. `BASE` is byte-identical before and after (`SimStatRoll.apply` returns the
  container untouched), so those rows carry forward. That distinction is worth roughly a
  third of the sweep against bumping `ENGINE_VERSION`, which would re-measure all of it.
- **every build touching a config value the regeneration changed** — automatic, via the
  per-item and per-skill digests.

**Check what is actually carryable before assuming `--changed` saves anything.** Baseline
candidacy is realm + scope + scenario and deliberately *not* `config_hash` — see
`findBaselineRun` — so a config regeneration does not disqualify a baseline. Correctness is
guarded per matchup by `sim_result.config_scope_hash`, which is the right granularity: a row
is carried only when the config *it* depended on is unchanged.

The trap is that renaming an axis invalidates rows without changing what they measured.
`targetScopeHash` hashes `armorSet=<id>`, so when the armour tier axis replaced the
single-set-per-role id `role_set` with real set ids, every armoured row in run 1 stopped
matching — 3,474,576 of 4,343,220, **80% of the baseline** — even though `role_set` and
`reinforced` are the same physical armour. Only the 868,644 `none` rows remain reachable,
because tier 0 contributes no `armorSet=` part at all.

That cost is real but one-time, and it is the price of the axis rather than a defect. Do not
try to recover it by rewriting `target_armor` or `config_scope_hash` on the baseline: the
column rename alone does not help (the stored hash still disagrees), and editing the hash
fabricates the provenance the whole delta mechanism rests on.

Expect a delta immediately after an axis change to be close to a full sweep. Use `--changed`
anyway — it costs nothing and records `measured_run_id` — but budget the full wall clock.

To re-check one thing rather than everything, narrow it:

```
/simulate EQUIPMENT --weapons=wind_blade          # the drifted weapon alone
/simulate EQUIPMENT --armor=reinforced --changed  # one armour tier
```

## 5. Process and verify

```bash
export HADOOP_HOME=/c/Users/$USER/hadoop && export PATH="$HADOOP_HOME/bin:$PATH"  # Windows
python -m jobs.cli all --run-id <n>
```

Then read the **Pipeline data quality** panel before anything else on the dashboard. These
four are the ones that would have caught run 1's defects, and on a good baseline they all
pass:

| Expectation | What a failure means |
|---|---|
| `roll_axis_effective` | the roll is inert again — the whole reason this runbook exists |
| `weapon_roll_envelope` | a `min` above its own `base`: config drift, so step 1 did not take |
| `armour_tier_known` | rows with no tier; expected only on runs predating the tier axis |
| `carried_forward_share` | 100% carried means the sweep ran no duels at all |

## 6. What is still not automated

- **Nothing refuses a sweep whose config has drifted.** Step 2 is a script someone has to
  remember to run, and the failure mode is silent. The durable version is a preflight in the
  sim comparing the loaded config against the packaged defaults and refusing to start — the
  sweep already digests every leaf, so it is a comparison it nearly does already. Until that
  exists, this document is the control, and a document is not a control.
- **The jar the sweep ran against is not recorded.** Not drift — the server hosts one plugin
  profile at a time and gets switched on purpose — but a run's provenance currently stops at
  `engine_version`, which is hand-maintained. Stamping the build with a commit hash and
  recording it on `sim_run` would make "which code produced these numbers" answerable from
  the data instead of from memory. That matters most exactly when a run turns out to be
  wrong, which is when memory is least reliable.
- **`ENGINE_VERSION` is unchanged** at `phase3-rolls-1` despite the roll fix, deliberately,
  because the scope hash now expresses the invalidation precisely and a version bump would
  be a blunter instrument costing a full re-run. The cost of that choice: two runs either
  side of the fix carry the same `engine_version`, so the column alone does not tell you
  they measured `min`/`max` differently. This paragraph is the only place that is written
  down.
