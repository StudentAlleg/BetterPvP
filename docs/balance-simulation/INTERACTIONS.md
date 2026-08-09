# Effect interactions

*Added 2026-08-08. NEXTSTEPS item 7c — "do certain things not mix well together, e.g. attack
speed increases with on-hit things".*

`sim_gold_effect_interaction`, plus the **Effect interactions** panel on the gold dashboard.

---

## 1. Why the axis is the weapon

The obvious reading of the question is "vary attack speed, hold everything else, watch the
on-hit effect". The sweep cannot do that, and it is worth being clear why before trusting any
number here.

Within one weapon, attack speed barely varies. `SimStatRoll` moves **every stat to the same
corner at once** — a `MIN` row is a slower *and* weaker weapon — so the roll axis cannot
separate the two. That is a deliberate choice (three corners instead of a product of
envelopes, `SimStatRoll`'s own javadoc says so), and it means the roll is useless for this
question.

Across weapons, attack speed varies a lot: that is most of what distinguishes a weapon profile.
So the axis is the **carrier weapon**, and the question becomes: *does an effect's marginal
value track how fast the weapon carrying it swings?*

- An **on-hit** effect fires once per swing. Its DPS contribution should rise with swings per
  second — a positive `speed_corr` is that effect compounding.
- A **flat percentage** buff should be indifferent to swing rate and track weapon damage.
- A **negative** speed correlation is an effect contending with its weapon for the same term in
  the damage pipeline. That is the trap worth finding: a combination the item screen encourages
  and the numbers punish.

## 2. Both coefficients, always

**`speed_corr` alone is misleading and the mart will not let you read it alone.**

Fast weapons are not a random sample of weapons — they hit softer, because that is how weapons
get balanced. So speed and damage are themselves correlated across the catalog, and an effect
that genuinely scales with weapon *damage* will show a spurious *negative* speed correlation
and look like a contention.

`damage_corr` sits beside it and `tracks` names whichever dominates. Ties go to damage: an
attack-speed interaction is the stronger claim and should have to win outright.

This is not a hypothetical — it is the single most likely way to misread this table, which is
why the panel description says it in capitals and why there is a test (`test_an_effect_tracking
_damage_is_named_as_such`) whose entire point is a fixture that would fool the naive reading.

## 3. Refusing to answer

A correlation over one carrier weapon is a number that looks exactly like a finding.

`weapons` counts distinct carrier profiles, `correlation_reliable` is `weapons >= 3`, and
`interaction` reads **"too few weapons"** rather than a verdict when it is not. Two carriers is
still too few: two points always correlate perfectly and never mean it.

A one-weapon sweep — every `MELEE` run — therefore produces a full table of honest refusals
rather than 405 confident numbers. That is the correct output, and it is what run 4 produces.

`speed_corr` is **null** rather than `0.0` where the axis did not vary. Zero would claim the
two were measured and found unrelated, which is a different and much stronger statement than
"there was nothing to measure".

### The correlation is computed from its parts

Not `F.corr`. Under Spark's ANSI mode `corr` raises `DIVIDE_BY_ZERO` on a group whose carrier
never varied — which is not an edge case but the normal state of a single-weapon sweep, and it
took the whole gold layer down the first time it ran. `covar_samp` and `stddev_samp` are
defined on a constant column (both zero), so the division is guarded explicitly and the
undefined case becomes a null instead of an exception.

## 4. Runes and skills in one table

`effect_contribution` unions the two contribution marts into one shape. A rune and a skill are
different things to equip and the same thing to measure: each has a marginal delta against an
otherwise identical build without it, and each is a candidate for compounding with the weapon
underneath it. Keeping them apart meant asking this question twice and comparing by eye.

`effect_kind` keeps them distinguishable, because they are **not** interchangeable as design
levers — a rune is a drop, a skill is twelve points — and an average across both would be an
average over two currencies.

## 5. Not done

- **This is a correlation, not a model.** Weapons differ in more than speed and damage, and
  nothing here controls for the rest. `weapons` and the two coefficients are on the row so a
  reader can judge how much to believe; the threshold at ±0.3 is a reporting convention that
  lets the panel sort, not a significance test.
- **No effect x effect pairing beyond runes.** `rune_set_synergy` covers rune x rune. Skill x
  rune and skill x skill are not enumerated by any sweep, so they cannot be measured yet.
- **Attack speed is still confounded with damage within the roll axis**, so a per-stat roll
  axis would answer this far more directly than a correlation across weapons ever can. That is
  a catalog change, and an expensive one — `SimStatRoll`'s javadoc has the arithmetic.
