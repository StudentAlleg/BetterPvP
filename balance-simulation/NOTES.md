# Balance Simulation — Vision & Notes

## Purpose

Build an authoritative, reproducible dataset from simulated combat that can be
analyzed independent of live server data. The simulation isn't meant to be a
perfect model of real play — it's meant to be *consistent*, so relative
comparisons between items/skills/runes are meaningful even if absolute numbers
aren't realistic.

## Combat simulation goals

- **Coverage**: simulate as many permutations of weapons, runes, and skills as
  is practical, so any two build combinations can be compared head-to-head.
- **Baseline diffing**: maintain a pre-run baseline dataset. When an item or
  skill is added/changed, re-run the simulation against just that change and
  diff the results against baseline to see the delta in win rate, TTK, damage
  share, etc. This is the core workflow — balance changes should come with a
  "before vs after" comparison, not just gut feel.
- **Known limitation**: simulated outcomes won't match live play exactly
  (no real player decision-making, positioning, latency, etc.), but relative
  comparisons between builds/versions should still surface useful signal.

## Stretch goals (roughly in order of ambition)

1. **Sim vs. live comparison** — take the simulated dataset and compare it
   against real collected combat data (from live servers) to sanity-check how
   far the model diverges from reality, and where.
2. **Profession/economy simulation** — a separate simulation track for
   professions, dungeons, etc. Track potential loot drops, money earned, and
   experience gained per run/build/strategy.
3. **Profession sim vs. live comparison** — same idea as #1 but for the
   economy sim: compare simulated loot/money/XP against live-collected
   economy data.

## Open questions / things to figure out later

- What format the baseline dataset should be stored in for easy diffing
  (flat files vs. DB table vs. something queryable).
- How to define "a permutation" cleanly as more skills/runes/weapons are
  added — combinatorial explosion is a real risk, may need sampling instead
  of full enumeration eventually.
- What live data would actually be available to compare against, and how to
  normalize it against sim conditions.
