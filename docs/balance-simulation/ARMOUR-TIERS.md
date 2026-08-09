# The armour tier axis

*Added 2026-08-08. Groundwork for the tier-parity question: "does a tier-2 combo kill in the
same time as tier 0 and tier 1".*

Before this, a role had exactly one armour set and the sweep could not have measured a second
one if you had registered it.

---

## 1. What was actually wrong

`SimEquipment.armorSet(role)` walked the registry for `ArmorItem`s carrying that role's
`RoleArmorComponent` and picked, **per slot, the lowest-sorting registry key**. One set per
role, chosen alphabetically.

That was fine while `champions/configs/items/armor.yml` held exactly one family
(`reinforced_<role>_<slot>`). It stops being fine the moment a second tier is registered:
depending on how the new keys alphabetise, either the new tier replaces the old one in every
matchup, or it is ignored entirely. **Both outcomes are silent.** The sweep reports a complete
armour axis, `target_armor` still says `role_set`, every dashboard still renders, and the
numbers describe armour nobody chose.

So `target_armor` only ever held `none` or `role_set` (plus `_min` / `_max` roll variants), and
the tier ladder had nowhere to live.

## 2. A set is what its pieces' keys have in common

A set id is the piece's key with the role and the slot's material word removed:
`champions:reinforced_knight_helmet` → `reinforced`.

Derived from the keys rather than declared on the item, because tiering is a naming convention
the game already follows, and a declared `tier:` field would be a second source of truth that
can disagree with the name a designer reads. A key that does not carry both tokens keeps its
whole name and becomes its own single-piece set — visible in the sweep as a one-piece oddity
rather than folded into a set it does not belong to, quietly changing that set's durability.

The per-slot alphabetical tie-break survives, **scoped to within a set**. Two models of one
tier's helmet are still one helmet; two tiers are no longer one set.

### Tier is an ordering, not a label

Sets are numbered by their summed base `HEALTH`, flimsiest as tier 1, with `none` as tier 0.
Ordered rather than declared so registering a tier adds it to the ladder with no second place
to update — and so a set that is *not* more durable than the one below it cannot be
mislabelled, because the ordering **is** the measurement.

The consequence, which matters for the selector: registering a flimsier set renumbers every
tier above it. That is why `--armor` takes set ids (`--armor=reinforced`) and not tier numbers.
A selector typed against the old numbering would silently start selecting a different set.

## 3. `target_armor` keeps its meaning; the tier gets its own column

`target_armor` still folds the set and its stat roll into one discriminator. It is the identity
of a measurement — what `sim_result` stores, what `run_diff` joins on, what a delta sweep
matches its baseline through, and what every existing dashboard groups by. Changing it would
have orphaned every stored row.

`target_armor_set` and `target_armor_tier` sit beside it. A tier comparison groups *across*
values of the discriminator, and the set→tier mapping is an ordering over the pieces' live
`HEALTH` stats — nothing reading the string alone can recompute it. A dashboard deriving it
with a `CASE` would be a second source of truth that stops agreeing the moment a set is added
between them.

Per-set scope hashes too: `targetScopeHash` now digests the pieces of *that* set, so a tier-2
piece whose health moves does not invalidate tier 1. Hashing the role's every piece into every
tier would undo most of what makes a delta sweep cheaper than a full one.

## 4. What historical rows can and cannot say

| | Recoverable? | |
|---|---|---|
| `none` → tier 0 | yes | meant tier 0 then, means tier 0 now |
| `role_set` → set | yes | stripping the roll suffix is a string operation |
| `role_set` → tier | **no** | left null |

`role_set` named whichever set the role had at the time, and nothing recorded which. The
pipeline cannot know it was the same set that ranks tier 1 today, so the tier is **left null
rather than guessed** — a guess would place a historical row on a rung it was never measured
at, which is the error a TTK-parity comparison is least able to survive.

The `armour_tier_known` expectation reports the share, as a warning rather than an error: those
rows are not wrong, only unplaceable, and remain fine for every comparison that does not cross
tiers. What it prevents is a parity panel reading a run where most of the ladder is missing and
presenting the remainder as the answer. On run 4 it correctly says all 21,456 armoured rows
have no tier, with only tier 0 present.

## 5. Not done

- **Nothing has been swept at more than one tier**, because no second tier is registered yet.
  Everything here is the axis; the ladder still has two rungs (bare and `reinforced`).
- **The tier-parity solver.** Reading "which %damage makes tier 2 match tier 1" off the data
  needs the candidate multiplier to be a swept axis, which it is not. See NEXTSTEPS.
- **Armour is still effective HP, not mitigation** — `ArmorItem` contributes a `HEALTH` stat and
  nothing registers a `ModifierType.ARMOR` damage modifier. Unchanged by this work, but it is
  the assumption a tier ladder rests on most heavily: a tier is a bigger health pool and
  nothing else.
