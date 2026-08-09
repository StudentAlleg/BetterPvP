# Duel diagnostics

`sim_duel_diagnostic` exists to answer one question that `sim_result` structurally cannot.

## Resolved: run 165

Run 165 (45,254 duels, `SKILLS`/`MUTUAL`) answered it. **The target's max health was not a property of
the target spec.** A defender built from `ASSASSIN/none`, whose true max health is 29, was recorded at
29, 36, 43 and 47 — its own base plus the health bonus of whichever armour set the pooled entity wore
in the *previous* duel on that arena. The match is exact, in every combination, across the whole run:

| previous occupant | its armour bonus | stale bonus carried in | duels |
|---|---|---|---|
| `ASSASSIN/role_set` | +7 | **+7** | 1790 |
| `KNIGHT/role_set` | +18 | **+18** | 1770 |
| `MAGE/role_set` | +14 | **+14** | 1634 |
| … all 15 combinations | | 1:1, no exceptions | |

Targets with `armor='role_set'` are unaffected — they re-equip a full set, which fires the events that
recompute the modifier. Only `armor='none'` inherits, because `SimCombatant.equipArmor` returned early
on an empty set and so fired no armour event at all.

The mechanism: `HealthListener.updateHealth` installs the `betterpvp:health` `MAX_HEALTH` modifier and
recomputes it *only* when an armour event fires. `despawn` unequipped skills and purged per-UUID state
but never stripped the armour, so the modifier survived into the next duel on that entity.

Why it decided the sweep: a 29 HP target dies in five hits, a 47 HP one does not die at all inside the
window. Win rate against `ASSASSIN/none` was 92.9% at the true 29 health and 5.3% at 47. Skill-less
baselines are enumerated *first* and therefore inherited least, so they won matchups every later skill
build lost — and `sim_result` booked the difference as the skill's contribution. That is the whole of
the `dDPS` anomaly: Cleave, Blood Compass, Tranquility and the rest were credited with the baseline's
inherited-health advantage.

Fixed in `SimCombatant.unequipArmor`, called from `despawn`: strip each armour slot and fire the real
`ArmorUnequipEvent`, which is what drives the recompute.

### Second half of the same bug: runs 166 and 167

That fix was necessary and not sufficient. The invariant check below still failed on both follow-up
runs, and the two of them together name the remaining cause precisely:

| run | scenario | duels ending with the defender dead | contaminated |
|---|---|---|---|
| 165 | `MUTUAL`, before the fix | 26% | 49.3% |
| 166 | `MUTUAL`, after | 26% | **13.5%** |
| 167 | `ONE_WAY`, after | 100% | **49.4%** |

`ONE_WAY` regressed to the unfixed rate because every one of its duels ends `DEFENDER_KILLED`. The
proportionality is exact — 49.3% × 26% ≈ 13.5% — so the unequip works on a survivor and never on a
corpse.

Why: `updateHealth` opens with `previousPercentage = getHealth() / attribute.getValue()` and returns
early when that is 0, deliberately, so it does not resize a dead entity. A combatant that died wearing
a set therefore keeps the modifier through despawn — the event fires and does nothing — and the pool's
`reviveIfDead` then heals it to the *inflated* maximum. By the time the pool revives it, its armour is
already gone and it can no longer raise the event that would correct the attribute.

Confirmed on three independent cuts of run 166: a resident that had never died was clean in all 686 of
its duels; contamination climbs with its own revive count (4.7% at one revive, plateauing near 23%);
and it appears only on `armor='none'` targets (27.4% once died) while `role_set` targets sit at 5 rows
in 22,272, because re-equipping a set fires the event while the entity is alive.

Fixed by calling `handle.reviveIfDead()` at the top of `unequipArmor`, so the strip happens on a living
entity. Both duel slots need it: in `MUTUAL`, contamination follows a previous `ATTACKER_KILLED` (23.1%)
as well as a previous `DEFENDER_KILLED` (38.6%), because either corpse can be handed back out as the
next duel's defender.

**This is a sim-only defect. Production has no equivalent, by design.** A live player always holds a
role, so their base health is always that role's and the armour set that goes with it is always worn:
a role change strips the incompatible pieces and the new role's set is re-applied, and the equip that
follows recomputes the modifier. There is no state in which a live player is armourless and carrying an
armour bonus. The sim reaches one only because `armor='none'` is a sweep construct with no counterpart
in the game — a combatant deliberately given no set at all, and therefore never given the event that
would zero the attribute.

That is worth stating plainly, because it inverts the usual reading: a sim divergence from production
is not automatically a bug found in production. Here it is the sim's own extra degree of freedom.

### Closed by runs 168 and 169, and what it exposed underneath

The invariant passes on both: 23 of 24 target specs have exactly one max health. The armour leak is
done. One residue remains, 4 duels in 45,140 — a `WARLOCK/role_set` target that fought at 32, its bare
base with the set's bonus *missing*, on a fresh entity. The same build produced a correct 46 in
`ONE_WAY`, so it is a rare timing artifact on a cold arena, not the residency bug inverted.

With the health confound gone, the real remaining problem is visible, and it is not a skill effect:

**The sim is not deterministic.** Same build, same target, four repeat iterations, different damage:

| run | scenario | matchups | varying damage | varying hits |
|---|---|---|---|---|
| 168 | `MUTUAL` | 11,285 | 258 (2.3%) | 135 |
| 169 | `ONE_WAY` | 11,261 | 589 (5.2%) | 481 |

This is what is left of the phantom relevance. Break Fall cannot affect a duel, yet against its own
baseline it diverges in hit count in 35 of 864 duels. The signature is consistent across every skill
that shows it: `attacker_first_hit_tick` is *always* identical, divergence appears later in the fight,
`ONE_WAY` (whose duels run longer, the defender never killing anyone) is roughly twice as bad, and the
jump is quantised — a flat 5.940 burst-DPS step, which is one hit crossing the edge of the one-second
window in `burstDps`. Timing jitter accumulating over a fight, not randomness in damage itself.

Ruled out already: the sim's own `System.currentTimeMillis` use is confined to progress and ETA
reporting and never touches the damage path; `DamageDelayManager` already quantises delays to ticks
deliberately; `DamageData` holds a wall-clock stamp but has no consumers anywhere in core.

## Hit trace — finding where two iterations part

`sim_trace` existed for roughly this purpose and had never held a row in any run. It is now widened
(`V20260803_1__Add_hit_trace.sql`) and written, one row per landed hit, behind
`champions.simulation.hitTrace` (off by default, capped by `hitTraceMaxRows`, not part of
`config_hash`).

`hitTraceMaxRows` defaults to 2,000,000 — an order of magnitude above the largest sweep, not close
to it. A full `SKILLS` sweep costs 161,287 rows on `ONE_WAY`; `MUTUAL` records the defender's hits
as well and runs longer duels, and run 170 hit the old 200,000 ceiling partway through. A capped run
drops the tail of the catalog with nothing in the data marking which builds went unmeasured, so its
divergence rate is a floor rather than a measurement. Check it before trusting a number:

```sql
select run_id, count(*) from sim_trace group by run_id order by run_id;
```

Ticks are relative to the duel's **first landed hit** — the same anchor TTK uses — so a trace row and
the `sim_result` row it explains share an axis. `seq` orders hits that land on the same tick, without
which a diff reports insertion order as a divergence. Both raw and final damage are stored: if raw
agrees and final does not, the swing was identical and the mitigation pipeline diverged; if raw
already differs, the swing did.

The trace is assembled from the finished recording rather than emitted as hits land. That is not an
implementation detail — the thing being hunted is a timing difference, so instrumentation sitting in
the damage path could manufacture its own subject.

### The diff

Find the first tick at which two iterations of one matchup disagree:

```sql
SELECT tick, seq, actor,
       max(amount) FILTER (WHERE iteration = 0) AS iter0,
       max(amount) FILTER (WHERE iteration = 1) AS iter1
FROM sim_trace
WHERE run_id = :run AND build_id = :build
  AND target_role = :role AND target_armor = :armor
  AND iteration IN (0, 1)
GROUP BY 1, 2, 3
HAVING count(*) <> 2
    OR max(amount) FILTER (WHERE iteration = 0) IS DISTINCT FROM max(amount) FILTER (WHERE iteration = 1)
ORDER BY tick, seq
LIMIT 20;
```

The first row is the divergence. A missing side (`count(*) <> 2`) means one iteration landed a hit the
other did not — a timing difference. Matching ticks with different amounts mean the pipeline itself
diverged, which is a different bug and a shorter search.

Pick a target with `SimResultRepository`-side evidence first: the matchups worth tracing are the ones
where repeats already disagree, which the query in the section above names.

## Also changed, from what these runs established

- **`dps_sustained` denominator.** The killed branch measured from the first landed hit; the timeout
  branch measured from `endTick - startTick`, the whole duel including setup and the idle stretch
  before contact — precisely the offset the engagement anchor exists to cancel. The two branches
  measured different things, and any matchup mixing kills with timeouts averaged them together. The
  fallback now subtracts the engagement start too.
- **Audit bucket order.** `significant` was tested before the firing gate, so a driven skill that
  never fired could be reported `RELEVANT` on movement it could not have caused. That is how Takedown
  reached `RELEVANT` on 0 successes in 11,831 attempts and Wreath on 0 in 1,800. The gate now runs
  first. This matters more while the sweep is irreproducible, since there is always noise for it to
  catch, and is correct regardless.

A single 5.940 burst step clears the audit's 0.250 dps threshold twenty-four times over, so no
`SKILLS` verdict on a small-effect skill should be trusted until repeats agree.

### What the instrumentation ruled out

- **H1 (setup state)** — dead. Every attribute is identical at every resident age.
- **H2 (resident wear)** — dead *as a cause*, despite the most seductive curve in the run: win rate
  falls 42.8% → 5% with `attacker_duels_fought`. It is confounded. Each matchup runs 4 consecutive
  duels with an age spread of 2.25, so age is a proxy for sweep position. The paired within-matchup
  test settles it — youngest to oldest gives 13.01 / 12.99 / 13.21 / 13.22%, flat.
- **H6 (funnel)** — dead. Land rate is 0.145 at every age; the attacker's pipeline is unchanged.

The aggregate age curve was real and meant nothing. Only the paired comparison could tell.

## Resolved by the trace: runs 170 and 204

The trace found it on the first run. Runs 170 (`MUTUAL`, 200,011 trace rows — capped) and 204
(`ONE_WAY`, 161,287 rows) disagree between repeat iterations of the same build in 2.6% and 3.8% of
duel specs respectively. Classifying the *first* disagreeing event per spec is what broke it open:

| run | divergent specs | first event is a missing hit | raw damage differs | same raw, different final | mean first tick |
|---|---|---|---|---|---|
| 170 | 218 | 215 | 3 | 0 | 7.4 |
| 204 | 431 | 271 | 0 | **160** | 6.0 |

That last column killed the timing theory. Divergence does not accumulate — it is present at tick 0,
on the opening hit, in 166 of run 204's specs. And the `raws=1, finals>1` shape says the swing was
identical and the *mitigation* differed. Dumping one such pair:

| iteration | arena | ticks | raw | final |
|---|---|---|---|---|
| 0 | 42 | 0, 8, 16, 24, 32, 40 | 6.000 | **7.980** |
| 1 | 102 | 0, 8, …, 48 | 6.000 | **6.000** |

Not jitter. A flat ×1.33 on every hit of one iteration and nothing on the other. Across run 204,
`final/raw` is 1.0000 on 153,817 hits and exactly 1.3300 on 3,159 — and the only 0.33 in the combat
code is `TormentedSoil.baseDamageIncrease`.

The builds collecting the bonus were Break Fall, Blood Compass and Energy Pool. None of them can
amplify damage; none of them is even a Warlock skill.

### Why a Warlock zone was buffing a Brute's autoattacks

`TormentedSoil` keeps its zones in a `tormentList` on the skill singleton. Three properties combine:

1. `onDamage` applies the multiplier based only on **where the damagee is standing**. It never asks
   whether the damager carries the skill — correct, because it is an area denial zone rather than a
   personal buff.
2. Zones expire on `UtilTime.elapsed(castTime, duration * 1000)` against a `System.currentTimeMillis`
   stamp, swept by an `@UpdateEvent(delay = 500)`. **Wall clock, not ticks** — 7 real seconds.
3. The only other expiry condition is `torment.getCaster() == null`, and the caster is a strong
   reference that never becomes null.

The sim reuses arenas. `ARENA_SPACING` is 64 blocks and the zone's range is ~5, so this cannot leak
sideways between concurrent duels — it leaks *forward in time*, into the next duel on the same
platform. Whether the zone is still alive when that duel starts depends on how quickly the host
finished the previous one. **That is the nondeterminism.** Nothing in the sim is random; the sweep
was racing a wall clock.

The predecessor test, run 204, over builds that are not Tormented Soil:

| | duels | prev duel in arena was Tormented Soil | within previous 3 |
|---|---|---|---|
| ×1.33 | 374 | 51.1% | **100.0%** |
| clean | 21,721 | 0.6% | 2.1% |

Zero exceptions in 374. An 85× enrichment at lag 1, and the zone reaches up to three duels forward.

### The fix

`Skill.invalidatePlayer` already exists for exactly this, ~28 skills already override it, and
`SimCombatant.despawn` already calls it for every equipped skill on the way out. Tormented Soil
simply never opted in. It does now:

```java
@Override
public void invalidatePlayer(Player player, Gamer gamer) {
    tormentList.removeIf(torment -> player.equals(torment.getCaster()));
}
```

This is also right in production, which is why it belongs in the skill rather than in sim-side
cleanup: a player who logs out or swaps build should not leave a damage-amplifying zone standing.

### The rest of the class

Tormented Soil is one instance of a shape, not a one-off. 73 skill singletons hold per-caster
collections; 39 of those expire on wall clock; **29 of those still have no `invalidatePlayer`**:

```
assassin: Blink, SmokeBomb, Evade
brute:    Takedown, BlockTossObject
knight:   BullsCharge, Ride, Riposte
mage:     Rupture, PestilenceProjectile, Inferno, Swarm
ranger:   Agility, WolfsFury, MarkOfTheWolf, StormSphere, TriShot, BarbedArrows,
          HuntersThrill, Kinetics, Sharpshooter, VitalitySpores, Disengage
warlock:  BloodBarrier, Clone, BloodSphereProjectile, SoulBonds, SoulHarvest, Grasp
```

Not all of them can contaminate a later duel — most are anchored to the caster rather than to a
location, and a caster who has been despawned cannot be hit. Tormented Soil is dangerous
specifically because its state is anchored to **ground the next duel will stand on**. The list is
ordered by nothing; the ones worth checking first are the other location-anchored zones.

Re-run the trace and repeat the divergence query. Anything still disagreeing is a second source.

## What survived the fix: runs 205 and 207

Runs 205 (`MUTUAL`, 270,161 rows — clear of the raised cap) and 207 (`ONE_WAY`, 161,583).

The Tormented Soil leak is gone, completely. Zero non-Tormented-Soil builds land at ×1.33, across
314 duels that immediately followed one on the same arena — against 191 of 374 before. The 1,290
remaining ×1.33 rows are the skill buffing its own caster, which is the point of it. `ONE_WAY`
divergence fell 3.81% → 2.14%.

What remains is a different bug with a different signature:

| run | divergent specs | first event is a missing hit | raw differs | same raw, different final |
|---|---|---|---|---|
| 205 | 197 | 194 | 3 | **0** |
| 207 | 242 | 239 | 3 | **0** |

The mitigation-only column is now empty — that column *was* Tormented Soil. And the divergence is no
longer spread across builds that cannot act. Break Fall, Blood Compass and Energy Pool are clean.
What is left is concentrated in skills that genuinely do something:

| skill | divergent / specs | | skill | divergent / specs |
|---|---|---|---|---|
| Rupture | 103 / 180 (57%) | | Inferno | 10 / 108 |
| Grasp | 58 / 180 (32%) | | Wind Dagger | 9 / 108 |
| Skullsplitter | 21 / 180 | | Blood Sphere | 4 / 180 |
| Magnetic Axe | 19 / 180 | | Soul Bonds | 3 / 36 |
| Leech | 13 / 180 | | Slash, Blizzard | 1 each |

### It is timer phase, not contamination

The predecessor test that convicted Tormented Soil comes back weak here — 54% of divergent specs
diverge despite an identical arena predecessor. Note also that repeat iterations run in **different
arenas**, so any per-arena analysis has to be done per iteration and then compared across them.

A divergent Rupture pair, build 224178 vs ASSASSIN/none:

| iteration | arena | ticks (raw) |
|---|---|---|
| 0 | 265 | 0 (7.0), 8 (7.0), **12 (7.5)**, 16 (7.0), 24 (7.0) |
| 1 | 248 | 0 (7.0), 8 (7.0), **10 (7.5)**, 16 (7.0), 24 (7.0) |

The autoattacks are identical and perfectly aligned on the 8-tick swing cycle. Only the skill hit
moves, by 2 ticks. Same total damage, same hit count — but a 2-tick shift moves the 1-second window
`burstDps` is measured over, which is where the flat 5.940 steps came from.

Rupture advances on `runTaskTimer(champions, 0, 2)`. Its jitter should therefore be quantised to 2,
and it is: **all 258 of Rupture's tick spreads are even** (2, 4, 6, 8). Grasp is also
`runTaskTimer(champions, 0, 2)`, and its spreads are also all even.

The cause is that periodic machinery is scheduled globally rather than per duel:

- `BukkitRunnable.runTaskTimer(plugin, 0, N)` counts from when it was scheduled.
- `UpdateEventExecutor` (`core/.../framework/updater/`) is tick-based, not wall-clock — it converts
  `delay()` to `delay / 50` ticks — but it keys its schedule on the delay value **server-wide**:
  `lastRunTimers.get(event.delay())`, offset from `currentTick`. One phase, shared by every handler
  with that delay, anchored to server boot.

So a duel beginning on an odd global tick can see a period-2 skill step one tick later than a duel
beginning on an even one. Duel start ticks drift because the sweep's pacing is wall-clock, which is
how a fully tick-based engine still produces irreproducible results. Nothing here is random.

Spreads of exactly 8 recur across several skills; 8 is the autoattack period, so those are a shifted
skill hit displacing a swing — a consequence, not a second cause.

### The projectiles were a second cause: wall-clock physics

Skullsplitter and Magnetic Axe showed **mixed odd/even** spreads, which phase cannot produce — phase
shifts are quantised to the skill's period. They were logged as open. They are not open; they were a
different bug, in the shared projectile base rather than in either skill.

`core/.../model/projectile/Projectile.java` measured two things against the wall clock, and all 17
subclasses inherited both:

```java
this.elapsedMillis = time - lastTick;                       // time = System.currentTimeMillis()
UtilVelocity.applyGravity(..., elapsedMillis);              // integrates position from that delta
public boolean isExpired() { return UtilTime.elapsed(creationTime, aliveTime); }
```

The integration is the serious one. **Where a projectile is after N ticks depended on how long those
N ticks actually took**, so the same shot fired twice landed in different places and impacted on
different ticks — drift that is continuous, not quantised, which is exactly the observed signature.
Every caller drives `tick()` from a default `@UpdateEvent` (50 ms = 1 tick), so the delta was meant
to be a constant 50 ms; the wall clock was measuring jitter, not signal.

Both now count ticks (`elapsedTicks`, `creationTick`, `impactTick`, `aliveTicks`), with the
millisecond-facing constructor kept so none of the 17 construction sites changed. The eight
`isExpired()` overrides have genuinely different semantics — three measure from creation, three from
impact, `BoomerangProjectile` from its own recall, `PestilenceProjectile` from a sliding
`lastTargetTick` so it lives while it keeps finding targets — and each was preserved individually.

Two notes for whoever reads the next run. `ticksElapsed` tests `>=` where `elapsed` tested `>`, so
windows are a hair shorter at the boundary; this is sub-tick and intended. And `ReturningLinkProjectile`
already hardcoded `/20.` per call for its motion, i.e. it always assumed 20 calls per second — it now
agrees with the base class instead of contradicting it.

### Confirming it

`anchor_tick` (`V20260803_2__Add_trace_anchor_tick.sql`) stores the absolute server tick each duel's
relative axis was built from — the phase that normalisation otherwise hides. This is currently the
one inferred step in the chain, so it is worth measuring rather than assuming:

```sql
-- Divergence should partition on phase. If it does, this splits clean from divergent.
select b.skills->0->>'skill' as skill, t.anchor_tick % 2 as phase,
       count(distinct (t.build_id, t.target_role, t.target_armor, t.iteration)) as duels
from sim_trace t join sim_build b on b.id = t.build_id
where t.run_id = <run> and b.skills->0->>'skill' in ('Rupture', 'Grasp')
group by 1, 2 order by 1, 2;
```

If phase is the cause, the fix is sim-side and needs no skill changes: start every duel on a tick
whose index is a fixed residue modulo the least common multiple of the periods in play, so that all
iterations of a matchup see the same phase.

## The finding it was built for

Run 164, scope `SKILLS`, scenario `MUTUAL`. Per-hit damage is fully deterministic —
`extras->>'dmg_per_hit_stddev'` is `0.0` on every row of the run — so nothing in that sweep is
statistical noise. Every difference is systematic.

The WARLOCK skill-less baseline against ASSASSIN/none (29 HP):

| build | dps_sustained | ttk_s | kills | attacker_deaths |
|---|---|---|---|---|
| `<baseline>` | 18.750 | 1.600 | **10** | 0 |
| Bloodthirst (passive) | 16.744 | — | **0** | 10 |
| Siphon (passive) | 16.744 | — | **0** | 10 |
| Soul Harvest (passive) | 16.744 | — | **0** | 10 |
| Blood Compass (toggle) | 16.744 | — | **0** | 10 |

Bloodthirst, Siphon and Soul Harvest are passives. `GreedyRotationPolicy.act` presses nothing for
them — they fall through to `default -> {}`. Blood Compass is a compass. Same weapon, same target,
identical `dmg_per_hit` of 6.000. Adding a skill that does nothing turns 10/10 wins into 10/10
deaths.

Run-wide, baselines win 18.1% of matchups and single-skill builds win 14.5%. Only 1,639 of 11,316
rows produced a kill at all.

This also fully explains the audit's magic constants: `18.750 − 16.744 = 2.006` and
`18.750 − 16.471 = 2.279`, the two `dDPS` values that appear on ~60 of the 96 "relevant" verdicts.
They are not skill contributions. They are the baseline winning its one winnable matchup while the
skill build lost it, with `dps_sustained` silently switching denominators between the two —
`DuelOrchestrator.record` divides by the TTK window on a kill and by the whole loop otherwise.

## Turning it on

```yaml
champions:
  simulation:
    duelDiagnostics: true
    duelDiagnosticsMaxRows: 50000
```

Off by default: one row per duel rather than per matchup. Deliberately **not** part of
`config_hash`, so a diagnostic run stays diffable against the run it is explaining.

Run the same scope that produced the finding, and ideally run it twice — once `MUTUAL` to reproduce
run 164, once `ONE_WAY` to remove the attacker-death confound.

## The hypotheses, and the query that kills each

Each of these was a guess. None of them is now.

### H1 — carrying a skill changes the combatant's starting state

If a build with one inert passive is not identical at setup to a bare build, the fight was decided
before the first swing.

```sql
SELECT coalesce(b.skills->0->>'skill', '<baseline>') AS skill,
       d.attacker_max_health, d.attacker_start_health,
       d.detail->'attacker'->'setup'->>'armor'          AS armor,
       d.detail->'attacker'->'setup'->>'attack_damage'  AS atk_dmg,
       d.detail->'attacker'->'setup'->>'attack_speed'   AS atk_speed,
       d.detail->'attacker'->'setup'->>'movement_speed' AS move_speed,
       d.detail->'attacker'->'setup'->>'held_item'      AS held,
       d.detail->'attacker'->'setup'->'effects'         AS effects,
       count(*), count(*) FILTER (WHERE d.outcome = 'DEFENDER_KILLED') AS wins
FROM sim_duel_diagnostic d JOIN sim_build b ON b.id = d.build_id
WHERE d.run_id = :run AND b.role = 'WARLOCK'
  AND d.target_role = 'ASSASSIN' AND d.target_armor = 'none'
GROUP BY 1,2,3,4,5,6,7,8,9 ORDER BY 1;
```

Any column that differs between `<baseline>` and a passive is the answer.

### H2 — pooled entities degrade, and baselines are enumerated first

`BalanceCatalog.enumerateSingleSkill` emits the skill-less baselines before any skill build, and
`SimCombatantPool` reuses `ServerPlayer`s and revives them after each death. So a baseline
systematically fights younger residents than the builds subtracted from it. This is the leading
hypothesis.

```sql
SELECT attacker_duels_fought / 10 * 10 AS duels_bucket,
       attacker_ever_died, attacker_revives > 0 AS revived,
       count(*) AS duels,
       round(100.0 * count(*) FILTER (WHERE outcome = 'DEFENDER_KILLED') / count(*), 1) AS win_pct
FROM sim_duel_diagnostic
WHERE run_id = :run
GROUP BY 1,2,3 ORDER BY 1,2,3;
```

If `win_pct` falls as `duels_bucket` rises, or collapses the moment `attacker_ever_died` is true,
the sweep is measuring resident wear rather than builds. Cross-check by holding the build constant:

```sql
SELECT b.skills::text = '[]' AS is_baseline,
       avg(d.attacker_duels_fought) AS mean_resident_age,
       round(100.0 * count(*) FILTER (WHERE d.outcome = 'DEFENDER_KILLED') / count(*), 1) AS win_pct
FROM sim_duel_diagnostic d JOIN sim_build b ON b.id = d.build_id
WHERE d.run_id = :run GROUP BY 1;
```

### H3 — teardown leaves residue on the resident

`SimCombatant.despawn` unequips every registered skill and purges per-UUID state. `residue_before_setup`
is the entity's own combat state as it arrived, read before anything was applied.

```sql
SELECT d.detail->'attacker'->>'residue_before_setup' AS residue, count(*)
FROM sim_duel_diagnostic d
WHERE d.run_id = :run AND d.attacker_duels_fought > 1
GROUP BY 1 ORDER BY 2 DESC LIMIT 20;
```

A resident is supposed to arrive indistinguishable from a fresh entity. Compare rows where
`attacker_duels_fought = 1` against the rest; any field that differs is state that survived teardown.

### H4 — the fight is a race decided by who lands first

`DuelOrchestrator.advance` runs the defender's rotation and swing **before** the attacker's on every
tick under `MUTUAL`, and a 29 HP target dies in five hits. `sim_result` anchors TTK to the first
landed hit precisely so setup cost cancels out — which also throws away the tick that hit landed on.

```sql
SELECT coalesce(b.skills->0->>'skill', '<baseline>') AS skill,
       d.attacker_first_hit_tick, d.defender_first_hit_tick,
       d.attacker_first_hit_tick - d.defender_first_hit_tick AS attacker_deficit,
       d.attacker_hits, d.defender_hits, d.attacker_end_health, d.defender_end_health, d.outcome
FROM sim_duel_diagnostic d JOIN sim_build b ON b.id = d.build_id
WHERE d.run_id = :run AND b.role = 'WARLOCK'
  AND d.target_role = 'ASSASSIN' AND d.target_armor = 'none'
ORDER BY 1, d.iteration LIMIT 60;
```

A loser ending on ≤ 6 health lost by one hit. If `attacker_deficit` is positive for skill builds and
zero for the baseline, the skill cost the attacker its opening tempo and nothing else.

### H5 — the role equip path differs depending on the previous duel

`SimCombatant.equipRole` passes through a priming role when the resident already reads as the target
role, firing two `RoleChangeEvent`s instead of one. Which branch is taken depends on the *previous*
duel on that resident. Every skill's `trackPlayer` hangs off that event.

```sql
SELECT d.detail->'attacker'->'setup'->>'role_primed' AS primed,
       d.detail->'attacker'->'setup'->>'role_before_equip' AS prev_role,
       count(*), round(100.0 * count(*) FILTER (WHERE d.outcome = 'DEFENDER_KILLED') / count(*), 1) AS win_pct
FROM sim_duel_diagnostic d WHERE d.run_id = :run GROUP BY 1,2 ORDER BY 3 DESC;
```

### H6 — swings are issued but refused upstream of every event

The recorder only sees hits that landed. `funnel` is the per-combatant pipeline count from
`SimPlayer`: swings made, vanilla events, can-hurt checks, `DamageEvent`s, damage allowed.

```sql
SELECT coalesce(b.skills->0->>'skill', '<baseline>') AS skill,
       d.detail->'attacker'->>'funnel' AS attacker_funnel,
       d.detail->>'max_separation'     AS max_separation,
       count(*)
FROM sim_duel_diagnostic d JOIN sim_build b ON b.id = d.build_id
WHERE d.run_id = :run AND b.role = 'WARLOCK' AND d.target_role = 'ASSASSIN'
GROUP BY 1,2,3 ORDER BY 1 LIMIT 40;
```

Where the funnel collapses is where the swing died. `max_separation` covers the one mechanism that
leaves no other trace: both sides swing every tick regardless of range, so a pair knocked apart
produces swings refused before any event fires. `-1` means the duel resolved before a sample.

### H7 — the deciding difference is in the defender, not the attacker

Every block above exists for the defender too — swap `'attacker'` for `'defender'`. The defender is
built from the target spec alone (`DuelOrchestrator.defenderBuild`), so it must be identical across
every attacker build against the same target. If it is not, the confound is on the other side.

## Reading order

Kept for the next contamination, since the queries above are the general instrument rather than a
one-off. The lesson from run 165 is the ordering rule: **a curve against resident age proves nothing
until it survives a within-matchup paired comparison.** Age, build identity and target are enumerated
together, so any aggregate cut against one of them is a cut against all three. Check first that the
target spec's own invariants hold — `defender_max_health` must have exactly one value per
`target_role`/`target_armor` pair — before reading anything as a property of a build.

## Invariant check — run this first on any new run

```sql
SELECT target_role, target_armor, count(DISTINCT defender_max_health) distinct_hp,
       min(defender_max_health), max(defender_max_health)
FROM sim_duel_diagnostic WHERE run_id = :run GROUP BY 1,2 ORDER BY 1,2;
```

`distinct_hp` must be 1 for every row. Anything else means the target spec is not being honoured and
no figure in the run describes what its build column says it does.

## What this does not fix

The instrumentation explains the sweep; it does not repair it. Independently of what these queries
show, `dps_sustained` mixes two incompatible denominators (`DuelOrchestrator.record`), and
`SkillRelevanceAudit.bucket` tests significance before the never-fired gate, so a skill with zero
successes can still be classified `RELEVANT` — which is how Takedown (`0/11831` attempts) and Wreath
(`0/1800`) reached the relevant list in run 164.
