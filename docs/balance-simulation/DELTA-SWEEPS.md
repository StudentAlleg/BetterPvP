# Delta sweeps and targeted sweeps

*NEXTSTEPS item 6. Added 2026-08-07.*

Two ways to not run a sweep you do not need:

| | Flag | Narrows |
|---|---|---|
| **Targeted** | `--weapons` `--skills` `--runes` `--roles` `--targets` | which permutations exist |
| **Delta** | `--changed[=runId]` | which of them still need duels |

They compose. `--weapons=thornfang --changed` enumerates that weapon's permutations and
then measures only the ones whose config actually moved.

```bash
/simulate EQUIPMENT --changed                      # re-measure what the last balance patch touched
/simulate EQUIPMENT --changed=204                  # ... against a named baseline
/simulate LOADOUT --weapons=thornfang,wind_blade   # two weapons, every rune set each accepts
/simulate SKILLS --skills="Blood Shield"           # one skill, every level
/simulate EQUIPMENT --weapons=reinforced* --changed --targets=KNIGHT
```

---

## 1. Why `config_hash` could not do this

`sim_run.config_hash` covers every balance value in the game. That is the right question
for *"are these two runs comparable"* and the wrong one for *"does this build need
re-measuring"*: one changed skill cooldown moves it, and a sweep keyed on it alone
re-runs the entire catalog. Run 1 is 4.3 million matchups and 35 hours of wall clock, so
"re-run everything" is not a delta strategy — it is the thing a delta is trying to avoid.

`--resume` does not help either, and the two are easily confused. A resume finishes a
sweep interrupted at an **unchanged** configuration; it finds no candidate the moment any
balance value moves, which is exactly when a delta becomes useful. They are opposites,
not variants.

## 2. Scoped digests

`SimConfigDigest` used to hash every config leaf and throw the map away. It now keeps the
map — sorted, so a scope is a subrange lookup rather than a scan — and answers three more
questions:

- `itemDigest(key)` — every leaf under that item's name, in any of the seven item trees.
  Which tree an item lives in is not knowable from its registry key (a rune is in
  `items/misc` or `items/material` depending on what it is), so all of them are searched.
- `skillDigest(role, name)` — `skills.<role>.<name>` **and** `skills.global.<name>`.
  Both, because a wrong prefix fails silently and in the worst direction: it selects an
  empty subtree, which digests to a constant, so the skill would look unchanged through
  every balance edit it ever received.
- `roleDigest(role)` — base health, which is not in any YAML but is the largest term in a
  target's durability.

Components are memoised and composed rather than recomputed per build. That is a
feasibility requirement, not an optimisation: there are ~27 weapons, ~20 runes and ~150
skills behind hundreds of thousands of builds, and walking seven thousand leaves per
build would cost more than the duels.

```
build.config_scope_hash  = compose(role, weapon, each rune, each skill)
target.config_scope_hash = compose(role, each armour piece)
result.config_scope_hash = compose(build, target, measurement terms)
```

**Measurement terms** are scenario, iterations, timeout, channel-hold budget and engine
version — the knobs that change what a duel *means* rather than what the game is. A row
measured over one Monte-Carlo iteration must not be carried into a ten-iteration run as
though the two were the same measurement. Concurrency is absent, for the reason it is
absent from `config_hash`: duels are tick-driven and do not interact.

### What is deliberately left out

Two things, both erring the same way:

- **The weapon roll.** It names a corner of a band the weapon's own leaves already cover,
  so a change to `damage.max` moves the hash for all three rolls including the two that
  do not read it.
- **The allocated level.** A skill's level scales values its own subtree holds, so a
  change to any of them invalidates every level of it.

Both **over**-invalidate. That is the only safe direction: carrying forward a row that
should have moved is a wrong number in a dashboard, and re-measuring one that need not
have moved is a handful of duels.

## 3. A delta run is a whole run

The obvious implementation — a run holding only the matchups that changed — breaks every
dashboard and every gold mart at once. "Latest run" would find a run covering a fraction
of the space, with nothing on it saying so, and every aggregate over it would be a biased
sample of the sweep it claims to be.

So the unchanged rows are **copied in**, server-side, and `sim_result.measured_run_id`
keeps that honest. It follows the *measurement* rather than the row: a row carried twice
across three runs still names the run whose duels produced it, via `COALESCE` on the
source row's own value. Silver turns it into `freshness` ∈ `measured` / `carried` /
`unknown`, gold carries it onto the matchup fact, and the **Where this run's numbers came
from** panel is one row per run whose duels are behind this one's numbers.

`--no-carry` gives the lean version: the delta still decides what to measure, it just does
not keep a copy of what it did not. The run is then explicitly partial, and the command
says so in red.

### The plan goes to the database, not to the heap

The natural approach is to read the baseline's matchups into a `Map` and diff in Java,
which is what `--resume` does with `findMeasuredMatchups`. That does not survive this
table's scale: a map of run 1's 4.3 million matchups to their hashes is on the order of a
gigabyte, inside a Paper server's heap, next to a sweep about to spawn hundreds of fake
players.

`sim_delta_plan` (unlogged) inverts it. The freshly enumerated catalog is written there in
batches, Postgres joins it against the baseline, and the only thing read back is the set
of matchups that still need duels — which for a delta is the short list, because that is
the entire point of a delta. This is also why the orchestrator drives a delta from an
**allow**-list while a resume drives from a **skip**-list: the same decision, read from
whichever end is cheaper to hold.

Carrying happens **before** measuring. A sweep can be stopped or die halfway, and a run
whose carried rows had not landed yet would be a fraction of a run with nothing saying so.

### Baseline candidacy

Realm, scope and scenario — deliberately *not* `config_hash`. A baseline is useful
precisely when the balance config has moved since; requiring the hash to match would only
ever find a run measured under identical config, which has nothing to carry that a resume
would not already have found. Correctness comes from the per-matchup scope hash instead.

`COMPLETED` is preferred over `CANCELLED` rather than required: a stopped sweep's rows are
real measurements, there are simply fewer of them.

**No baseline is not an error.** The first delta run on a fresh database has nothing to
diff against, so it degrades to a full sweep and says so.

## 4. Targeted sweeps

`SimScope` answers *which axes vary*; `SimSelection` answers *over which values*. They are
separate because the axes are the expensive structural choice: an `EQUIPMENT` sweep of one
weapon is still an `EQUIPMENT` sweep — it crosses rolls with every rune set that weapon
accepts — and folding "one weapon" into the scope enum would mean a tier per weapon.

Matching is lenient about the *form* of an identifier and strict about its spelling.
`champions:wind_blade`, `wind_blade`, `windblade` and `Wind Blade` are one name;
`thornfangg` is not `thornfang`. A trailing `*` is a prefix match. Weapons also match
through their aliases, so naming a weapon the reduced tiers folded onto another profile
selects the row that actually stands for it — "never swept" and "swept under another key"
are opposite answers.

Three properties worth stating:

- **Applied during enumeration, not after.** The unrestricted `FULL` product exhausts the
  heap while being enumerated, so a filter that only runs afterwards never gets to run.
  Narrowing the rune list *before* the combinations are generated is the sharpest case:
  386 sets per sword become a handful.
- **An unmatched selector is fatal**, and the refusal lists what was available. A mistyped
  `--weapons=thornfangg` would enumerate an empty catalog, and an empty sweep finishes in
  seconds and reports `COMPLETED` with nothing saying why. Same failure mode
  `SimSkillFilter.RELEVANT` guards against with its empty-list check.
- **The selection is part of `config_hash`.** Without that, a `--weapons=thornfang` sitting
  would find the full `EQUIPMENT` run as a resume candidate, reopen it, measure a handful
  of matchups and close it — and the thousands of missing rows would be indistinguishable
  from ones the sweep had simply not reached yet.

## 5. Schema evolution in the lake

Bronze is an archive, not a mirror of today's schema. A partition landed before these
columns existed does not have them, and re-ingesting it would not change that because the
source rows are NULL there too. Two consequences, both now handled:

- `silver/results.py::optional` reads a column if bronze has it and a typed null if not.
- Every lake reader passes `mergeSchema`. Without it Spark picks one partition's footer
  arbitrarily, and a column is present or absent depending on which file it happened to
  read — which fails loudly on a good day and silently drops a column on a bad one.

## 6. Expectations

Two new gold checks:

- **`freshness_labelling`** — `freshness` and `measured_run_id` must agree. The same
  invariant `provenance_labelling` enforces for derived rows, for the other axis on which
  a row can be something other than what it looks like. A row labelled `measured` whose
  `measured_run_id` is another run would be read as fresh evidence for a change it
  predates.
- **`carried_forward_share`** — states what share of the run was carried. Normally
  passes; carrying rows forward is the feature working. It fails on a run that is
  *entirely* carried, which means either the config genuinely did not move (the run is a
  duplicate of its baseline and should not have been started) or the scope digests are not
  moving when they should — which would silently freeze every future delta sweep at
  today's numbers.

## 7. Not done

- **New items do not automatically join a sweep.** A newly registered weapon has no row in
  any baseline, so `--changed` measures it — that part works. What is missing is a way to
  ask for *only* the new ones without naming them; today that is `--weapons=<the new one>`.
- **The delta is per matchup, not per duel.** A matchup is re-measured whole. That is
  right, since a `sim_result` row is a mean over its iterations and mixing sittings would
  be worse, but it means the granularity floor is `iterations` duels.
- **No automatic trigger.** `--changed` still has to be typed. Wiring it to a config
  reload would need a policy for what a half-finished sweep does when the config moves
  underneath it, which is a decision rather than a mechanism.
