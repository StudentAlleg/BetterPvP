1. Make sure the data is what I want it to collect (what the permutations are, skills, weapons runes etc.)
2. Optimize that data collection (reduce number for an absolute full run)
3. Build a way to get this data off of real players/combat
4. ~~Build a full medalion, audtiable pipeline to get actual data~~ **done 2026-08-07** — `pipelines/balance-sim`, see PIPELINE.md
5. ~~Display it in a useful way.~~ **done 2026-08-07** — `sim_gold_balance_explorer` dashboard, generated from `grafana/generate_sim_dashboards.py`
6. ~~Then have it only trigger on changes (to compare with baseline) and new items. (reduction in sim time)~~ **done 2026-08-07** — `/simulate <SCOPE> --changed`, plus `--weapons/--skills/--runes/--roles/--targets` to sweep a slice deliberately. See DELTA-SWEEPS.md

7. Decision tooling, so the answers come from panels rather than from prompting:
   - a. ~~An armour tier axis, so tier 0 / 1 / 2 can be compared at all.~~ **done 2026-08-08**
     — a role had exactly one armour set and a second one would have been silently
     dropped or silently substituted. See ARMOUR-TIERS.md. `--armor=<set id>` sweeps one.
   - b. **The damage envelope.** Range of dmg/hit and TTK across every target role x
     armour tier x roll, per build — plus the per-hit distribution from `sim_trace`.
     Blocked in part by the inert roll axis below: the roll half of the envelope is a
     constant today.
   - c. ~~**Generalised anti-synergy.**~~ **done 2026-08-08** — `sim_gold_effect_interaction`
     and its panel. Runes and skills are unioned into one `effect_contribution` shape and
     each is correlated against the attack speed *and* the damage of the weapons carrying
     it. Both coefficients, because fast weapons hit softer by construction and an effect
     that really tracks damage otherwise shows a spurious negative speed correlation.
     A verdict needs 3+ carrier weapons or it reads "too few weapons". See INTERACTIONS.md.
   - d. **The tier-parity solver.** "Which %damage makes tier 2 kill in the same time as
     tier 1" is an inversion, not a view: it needs the candidate multiplier to become a
     swept axis so the crossing can be read off a TTK-vs-multiplier curve.

Create a control skill


NOTES: Backstab should be done in the transformation phase (it only triggers on a back, so the sim by default does not pick it up)
— **done**: `pipelines/balance-sim/jobs/silver/derived.py`, as a `FlatPerHitSkill`
whose bonus comes from `grafana_config` rather than a constant. Every row it emits
carries `provenance='derived'` plus the assumed uptime, so a modelled figure can never
be averaged into a measured one. See PIPELINE.md §4.

Notes on 3 and 6, now that 4 exists:

- **3 (live combat data)** — silver is the join point. Land live per-build rows as
  `silver_live_result` at the same grain with the same `fingerprint`, and every gold
  mart works on them unchanged. Apache Beam is the right tool for that ingest
  specifically (unbounded, windowed, late events) and was deliberately left out of the
  batch pipeline; PIPELINE.md §2 has the reasoning.
- **6 (only sweep what changed)** — **done.** The missing half was the sim deciding
  *which* permutations a config change invalidated: `config_hash` is per-run, so it
  could say "something moved", not "these 400 builds moved". `SimConfigDigest` now also
  answers per item, per skill and per role, and a build's `config_scope_hash` is
  composed from just the components it carries. `/simulate EQUIPMENT --changed` stages
  its catalog, joins it against the newest finished run at that scope, carries every
  matchup whose own scope is unchanged forward, and measures the rest. See
  DELTA-SWEEPS.md for the design and for what is deliberately left out of a scope hash.

Also surfaced by the pipeline's expectations rather than by anyone looking, and worth
fixing in the game rather than the pipeline:

- ~~**The weapon roll axis is inert — and it is tripling the sweep for nothing.**~~
  **fixed 2026-08-08.** `champions:thornfang` rolls 5 / 6 / 7 and `sim_result.dmg_per_hit`
  read **6.000 at all three**. The cause was not in `SimEquipment`, which instantiated the
  weapon at the roll correctly: `SimStatRoll.apply` wrote the rolled values into the
  container's **base** stats, and `StatContainerSerializer` persists only *modifier* stats
  onto an `ItemStack` and reads base stats back off the registered `BaseItem`. So the roll
  was discarded by `createItemStack` and replaced with the configured value by
  `fromItemStack` — the round trip every combatant's gear makes. The roll now lands in the
  modifier half, which overrides base by type. **This also hit armour**: run 4's
  `role_set_max` targets have byte-identical HP to `role_set` ones, so the axis was
  doubling the armoured target space too. The `roll_axis_effective` expectation already
  checks the condition and will confirm the fix on the next sweep.
- **`champions:wind_blade` has an inverted damage envelope on the dev server.** The
  sweep measured `min 7.0 / base 6.0 / max 8.0` — a min above the base, and none of the
  three matching the `5.0 / 6.0 / 7.0` in `champions/src/main/resources/configs/items/weapon.yml`.
  The dev server's plugin data folder has drifted from the repo, so 14,292 builds in
  every EQUIPMENT run were swept against numbers nobody wrote.
