"""Answer gear-tier balance questions from a completed EQUIPMENT sweep.

Three questions, one tool:

    validate  -- prove the closed-form model reproduces the sweep exactly
    compare   -- tier 0 (standard sword, no armour) vs tier 1 (power axe, reinforced)
    solve     -- the tier 2 (ancient) weapon, armour and skill-damage values
    export    -- the JSON the dot-chart artifact reads

Why a closed-form model at all
------------------------------
Run 7 measured 3.6M matchups and every one of them is reproducible from four numbers.
The sweep established, not assumed, that:

* ``dmg_per_hit`` equals the weapon roll's damage exactly, at every armour tier. Armour
  in this game is **pure health** -- it grants no damage reduction whatsoever, despite
  the pieces being backed by IRON/DIAMOND/LEATHER materials whose vanilla armour points
  would normally reduce damage. That single fact is what makes the tier ladder solvable.
* ``hits_to_kill`` is ``ceil(target_hp / dmg_per_hit)``, always integral.
* ``ttk_ticks`` is ``(hits_to_kill - 1) * gap_ticks`` exactly, where ``gap_ticks`` is an
  integer set by the weapon and its roll -- 6/8/11 ticks for a max/base/min roll of a
  plain sword. Zero variance across a 34-hour run.

So tier questions do not need another sweep. They need the model, fitted to the measured
data and checked against it, and then evaluated at hypothetical values. ``validate`` is
what earns the right to do that: it must report zero mismatches before ``solve`` means
anything.

Why the measurement is trustworthy
----------------------------------
Run 7 predates commit 1d7ae7d4, so it ran without the over-budget rejection and opened
at 700 concurrent duels rather than climbing from the floor -- some of it was measured on
a server well past its 50ms tick budget. That does not contaminate these numbers, because
every quantity above is counted in **ticks**. A server at 105ms per tick executes the same
tick sequence as one at 20ms, just slower in wall-clock; the swing gap came out an exact
integer with zero spread, which is the observable that would have moved first if lag were
leaking in. The lag cost hours, not correctness.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
ARMOR_YML = REPO / "champions" / "src" / "main" / "resources" / "configs" / "items" / "armor.yml"

DSN = os.environ.get(
    "SIM_DSN",
    "host=localhost port=5002 dbname=betterpvp user=user password=BetterPvP123!",
)

# The pairing the balance question is asked about.
TIER0 = ("core:standard_sword", "none")
TIER1 = ("core:power_axe", "reinforced")
# Tier 2's weapons already exist in the catalogue at tier-1-ish damage; the question is
# what they *should* be.
TIER2_WEAPONS = ("core:ancient_sword", "core:ancient_axe")

ARMOR_SLOTS = ("helmet", "chestplate", "leggings", "boots")


# --------------------------------------------------------------------------- data


def query(sql, params=None, timeout_s=600):
    """One read-only query. Goes straight to the port, not through `docker exec psql`:
    the Docker API on this machine wedges under sweep IO while Postgres keeps serving."""
    import psycopg

    with psycopg.connect(DSN, connect_timeout=10) as conn:
        conn.read_only = True
        with conn.cursor() as cur:
            cur.execute(f"set local statement_timeout = {int(timeout_s * 1000)}")
            cur.execute(sql, params)
            cols = [d.name for d in cur.description]
            return [dict(zip(cols, r)) for r in cur.fetchall()]


def f(v):
    return None if v is None else float(v)


def profiles(run: int) -> dict[tuple[str, str], dict]:
    """(weapon, roll) -> damage per hit and swing gap in ticks, fitted to measured rows.

    Fitted on rune-free builds only. Runes move attack speed, and a profile averaged over
    rune sets would describe no weapon that exists.
    """
    rows = query(
        """
        select b.weapon, b.weapon_roll,
               avg(r.dmg_per_hit) as dph,
               min((r.ttk_s*20)/(r.hits_to_kill-1)) as gap_min,
               max((r.ttk_s*20)/(r.hits_to_kill-1)) as gap_max,
               count(*) as n
        from sim_result r join sim_build b on b.id = r.build_id
        where r.run_id = %(run)s and jsonb_array_length(b.runes) = 0
          and r.hits_to_kill > 1 and r.ttk_s > 0 and r.dmg_per_hit > 0
        group by 1,2
        """,
        {"run": run},
    )
    out = {}
    for r in rows:
        gap_min, gap_max = f(r["gap_min"]), f(r["gap_max"])
        # A profile whose gap is not a single value is not a profile. Surface it rather
        # than average it away -- it would mean the roll is not the only speed axis.
        out[(r["weapon"], r["weapon_roll"])] = {
            "dph": round(f(r["dph"]), 6),
            "gap_ticks": gap_min,
            "gap_consistent": abs(gap_max - gap_min) < 1e-9,
            "n": int(r["n"]),
        }
    return out


def targets(run: int) -> list[dict]:
    """Every (role, armour) target the sweep measured, with its health."""
    rows = query(
        """
        select target_role, target_armor, target_armor_set, target_armor_tier,
               avg(target_hp) as hp, count(*) as n
        from sim_result where run_id = %(run)s
        group by 1,2,3,4
        """,
        {"run": run},
    )
    return [
        {
            "role": r["target_role"],
            "armor": r["target_armor"],
            "armor_set": r["target_armor_set"],
            "tier": r["target_armor_tier"],
            "hp": f(r["hp"]),
        }
        for r in rows
    ]


def armor_health() -> dict[str, dict[str, dict[str, dict[str, float]]]]:
    """set -> role -> slot -> {base,min,max} health, parsed from the packaged armour config.

    Parsed rather than hardcoded so the tool keeps telling the truth when the values move,
    which is the entire point of asking it what tier 2 should be.
    """
    text = ARMOR_YML.read_text(encoding="utf-8")
    out: dict[str, dict[str, dict[str, dict[str, float]]]] = {}
    key = None
    for line in text.splitlines():
        if m := re.match(r"^([a-z0-9_]+):\s*$", line):
            key = m.group(1)
            continue
        if key is None:
            continue
        if m := re.match(r"^\s+(base|min|max):\s*([0-9.]+)\s*$", line):
            parts = key.split("_")
            if len(parts) < 3:
                continue
            set_id, role, slot = parts[0], parts[1], "_".join(parts[2:])
            if slot not in ARMOR_SLOTS:
                continue
            out.setdefault(set_id, {}).setdefault(role.upper(), {}).setdefault(slot, {})[
                m.group(1)
            ] = float(m.group(2))
    return out


def base_role_hp(run: int) -> dict:
    """Role health with no armour on.

    Only three roles have an unarmoured row in the sweep, so the rest are backed out of
    their reinforced health minus that set's own bonus -- and the three that *are*
    measured are used to check the arithmetic rather than trusted blindly.
    """
    tgt = targets(run)
    hp = {(t["role"], t["armor"]): t["hp"] for t in tgt}
    sets = armor_health()
    reinforced = sets.get("reinforced", {})

    out, checks = {}, []
    roles = sorted({t["role"] for t in tgt})
    for role in roles:
        bonus = sum(
            reinforced.get(role, {}).get(slot, {}).get("base", 0.0) for slot in ARMOR_SLOTS
        )
        derived = hp.get((role, "reinforced"))
        if derived is None:
            continue
        base = derived - bonus
        out[role] = base
        if (measured := hp.get((role, "none"))) is not None:
            checks.append((role, measured, base, abs(measured - base) < 1e-6))
    return {"base_hp": out, "checks": checks, "sets": sets, "targets": tgt}


# --------------------------------------------------------------------------- model


def predict(hp: float, dph: float, gap_ticks: float) -> dict:
    """The sweep's outcome for one matchup, closed form."""
    h2k = math.ceil(hp / dph - 1e-9)
    ttk_ticks = (h2k - 1) * gap_ticks
    ttk_s = ttk_ticks / 20.0
    dps = (dph * h2k / ttk_s) if ttk_s > 0 else None
    return {"hits_to_kill": h2k, "ttk_s": ttk_s, "dps": dps, "dmg_per_hit": dph}


def cmd_validate(args):
    """Reproduce every rune-free measured row from the model and report the mismatches."""
    prof = profiles(args.run)
    bad_gap = [k for k, v in prof.items() if not v["gap_consistent"]]
    print(f"weapon profiles: {len(prof)}   inconsistent swing gap: {len(bad_gap)}")
    for k in bad_gap:
        print(f"  !! {k} gap is not single-valued")

    rows = query(
        """
        select b.weapon, b.weapon_roll, r.target_role, r.target_armor,
               avg(r.target_hp) as hp, avg(r.hits_to_kill) as h2k,
               avg(r.ttk_s) as ttk_s, avg(r.dps_sustained) as dps, count(*) as n
        from sim_result r join sim_build b on b.id = r.build_id
        where r.run_id = %(run)s and jsonb_array_length(b.runes) = 0
          and r.ttk_s is not null and r.dps_sustained is not null and r.dmg_per_hit > 0
        group by 1,2,3,4
        """,
        {"run": args.run},
    )
    checked = mismatch = skipped = 0
    worst = []
    for r in rows:
        p = prof.get((r["weapon"], r["weapon_roll"]))
        if p is None:
            skipped += 1
            continue
        got = predict(f(r["hp"]), p["dph"], p["gap_ticks"])
        d_h2k = abs(got["hits_to_kill"] - f(r["h2k"]))
        d_ttk = abs(got["ttk_s"] - f(r["ttk_s"]))
        d_dps = abs((got["dps"] or 0) - f(r["dps"]))
        checked += 1
        if d_h2k > 1e-6 or d_ttk > 1e-6 or d_dps > 1e-3:
            mismatch += 1
            worst.append((d_dps, r["weapon"], r["weapon_roll"], r["target_role"],
                          r["target_armor"], f(r["h2k"]), got["hits_to_kill"],
                          f(r["ttk_s"]), got["ttk_s"], f(r["dps"]), got["dps"]))
    print(f"\nchecked {checked} matchup classes ({skipped} skipped, no profile)")
    print(f"mismatches: {mismatch}")
    for w in sorted(worst, reverse=True)[:15]:
        print(f"  {w[1]:<28} {w[2]:<5} {w[3]:<9} {w[4]:<15} "
              f"h2k {w[5]:g}->{w[6]:g}  ttk {w[7]:g}->{w[8]:g}  dps {w[9]:.3f}->{w[10]:.3f}")
    return 0 if mismatch == 0 else 1


# --------------------------------------------------------------------------- compare


def cmd_compare(args):
    """Tier 0 (standard sword, unarmoured) against tier 1 (power axe, reinforced)."""
    prof = profiles(args.run)
    info = base_role_hp(args.run)
    hp = {(t["role"], t["armor"]): t["hp"] for t in info["targets"]}

    print("Base role health, and the check against the unarmoured rows the sweep measured")
    for role, measured, derived, ok in info["checks"]:
        print(f"  {role:<9} measured {measured:>6.1f}   derived {derived:>6.1f}   "
              f"{'OK' if ok else 'MISMATCH'}")
    print()

    w0, a0 = TIER0
    w1, a1 = TIER1
    print(f"tier 0 = {w0} vs {a0}      tier 1 = {w1} vs {a1}")
    for roll in ("min", "base", "max"):
        p0, p1 = prof.get((w0, roll)), prof.get((w1, roll))
        if not p0 or not p1:
            continue
        print(f"\n--- roll = {roll}   (tier0 {p0['dph']:g} dmg / {p0['gap_ticks']:g}t, "
              f"tier1 {p1['dph']:g} dmg / {p1['gap_ticks']:g}t)")
        print(f"  {'role':<9} {'hp0':>6} {'hp1':>6} | {'ttk0':>6} {'ttk1':>6} {'dttk%':>7} "
              f"| {'dps0':>6} {'dps1':>6} {'ddps%':>7} | {'h2k0':>4} {'h2k1':>4}")
        for role in sorted(info["base_hp"]):
            hp0, hp1 = hp.get((role, a0)), hp.get((role, a1))
            if hp0 is None or hp1 is None:
                continue
            r0, r1 = predict(hp0, p0["dph"], p0["gap_ticks"]), predict(hp1, p1["dph"], p1["gap_ticks"])
            dttk = 100 * (r1["ttk_s"] / r0["ttk_s"] - 1) if r0["ttk_s"] else float("nan")
            ddps = 100 * (r1["dps"] / r0["dps"] - 1) if r0["dps"] else float("nan")
            print(f"  {role:<9} {hp0:>6.1f} {hp1:>6.1f} | {r0['ttk_s']:>6.2f} {r1['ttk_s']:>6.2f} "
                  f"{dttk:>+6.1f}% | {r0['dps']:>6.2f} {r1['dps']:>6.2f} {ddps:>+6.1f}% "
                  f"| {r0['hits_to_kill']:>4d} {r1['hits_to_kill']:>4d}")

    # The ratio that decides fight length. Everything in the tier question is this number.
    print("\nHealth-to-damage ratio -- the only thing that sets hits-to-kill.")
    print(f"  {'role':<9} {'tier0':>8} {'tier1':>8} {'drift':>8}")
    for role in sorted(info["base_hp"]):
        hp0, hp1 = hp.get((role, TIER0[1])), hp.get((role, TIER1[1]))
        if hp0 is None or hp1 is None:
            continue
        r0 = hp0 / prof[(TIER0[0], "base")]["dph"]
        r1 = hp1 / prof[(TIER1[0], "base")]["dph"]
        print(f"  {role:<9} {r0:>8.2f} {r1:>8.2f} {100*(r1/r0-1):>+7.1f}%")
    return 0


# --------------------------------------------------------------------------- solve


def cmd_solve(args):
    """Tier-2 values that hold fight length where tier 1 put it.

    The ratio ``(base_hp + armour_bonus) / weapon_damage`` is the whole game: it is
    hits-to-kill, and hits-to-kill times the swing gap is time-to-kill. Hold that ratio
    and DPS and TTK hold with it. So a tier step is one number, ``s``, applied to three
    places at once -- weapon damage, effective health, and skill damage. Move any one of
    them alone and the tier drifts.
    """
    prof = profiles(args.run)
    info = base_role_hp(args.run)
    hp = {(t["role"], t["armor"]): t["hp"] for t in info["targets"]}
    s = args.step
    sets = info["sets"]
    reinforced = sets.get("reinforced", {})

    d1_axe = prof[("core:power_axe", "base")]["dph"]
    d1_sword = prof[("core:power_sword", "base")]["dph"]
    print(f"Tier step s = {s:g}  ({100*(s-1):+.1f}%)\n")
    print("Weapon damage (base roll). Tier 2 must move by the same s as everything else.")
    for name, d1, cur in (
        ("axe", d1_axe, prof[("core:ancient_axe", "base")]["dph"]),
        ("sword", d1_sword, prof[("core:ancient_sword", "base")]["dph"]),
    ):
        print(f"  {name:<6} tier1 {d1:>5.2f}  ->  tier2 should be {d1*s:>5.2f}   "
              f"(ancient is {cur:>5.2f} today, {100*(cur/d1-1):+.1f}%)")

    print("\nTier-2 armour set health, so that (base_hp + bonus)/damage matches tier 1.")
    print(f"  {'role':<9} {'base_hp':>8} {'tier1 +':>8} {'tier2 +':>8} {'ratio1':>7} {'ratio2':>7}")
    plan = {}
    for role in sorted(info["base_hp"]):
        base = info["base_hp"][role]
        hp1 = hp.get((role, TIER1[1]))
        if hp1 is None:
            continue
        a1 = hp1 - base
        # (base + a2) / (d1*s) == (base + a1) / d1   =>   a2 = s*(base + a1) - base
        a2 = s * (base + a1) - base
        plan[role] = {"base_hp": base, "tier1_bonus": a1, "tier2_bonus": a2}
        print(f"  {role:<9} {base:>8.1f} {a1:>8.1f} {a2:>8.1f} "
              f"{hp1/d1_axe:>7.2f} {(base+a2)/(d1_axe*s):>7.2f}")

    print("\nPer-slot health for the tier-2 config, split the way tier 1 splits it.")
    for role in sorted(plan):
        pieces = reinforced.get(role, {})
        tot_base = sum(pieces.get(sl, {}).get("base", 0.0) for sl in ARMOR_SLOTS)
        tot_max = sum(pieces.get(sl, {}).get("max", 0.0) for sl in ARMOR_SLOTS)
        if tot_base <= 0:
            continue
        a2 = plan[role]["tier2_bonus"]
        scale = a2 / tot_base
        print(f"  {role.lower()} (set total {a2:.1f} base, {tot_max*scale:.1f} max):")
        for sl in ARMOR_SLOTS:
            p = pieces.get(sl, {})
            print(f"    tier2_{role.lower()}_{sl}: base {p.get('base',0)*scale:>5.2f}  "
                  f"min {p.get('min',0)*scale:>5.2f}  max {p.get('max',0)*scale:>5.2f}")

    print(f"\nSkill damage: multiply by s, i.e. {100*(s-1):+.1f}%.")
    print("  A skill's share of a kill is its damage over the target's health. Health rises")
    print(f"  by s at this tier, so skill damage must rise by s to keep that share. Scaling")
    print("  skills by more than the weapon step makes skills relatively stronger every tier.")
    cur_step_axe = prof[("core:ancient_axe", "base")]["dph"] / d1_axe
    print(f"\n  Note: ancient's *current* damage is only {100*(cur_step_axe-1):+.1f}% over power.")
    print(f"  A {100*(s-1):+.1f}% skill increase is only consistent if the weapon moves {100*(s-1):+.1f}% too.")

    # The question asks for tier 2 to resemble tier 1 *and* tier 0, and those are not the
    # same target: tier 1 already sits a third of the way off tier 0. Saying so, with the
    # two ways out and their price, is more use than silently picking one.
    d0 = prof[(TIER0[0], "base")]["dph"]
    print("\n" + "-" * 72)
    print("Tier 0 parity -- why 'like tier 1 AND tier 0' needs a decision first.")
    print("Holding the ratio at tier 0's level means armour may only add what the weapon's")
    print("extra damage pays for. Two levers reach it; they are very different changes.")
    print(f"\n  {'role':<9} {'ratio0':>7} {'ratio1':>7} {'drift':>7} | "
          f"{'t1 armour':>9} {'->should be':>11} | {'or t1 dmg':>9} {'->should be':>11}")
    for role in sorted(plan):
        base = plan[role]["base_hp"]
        a1 = plan[role]["tier1_bonus"]
        r0, r1 = base / d0, (base + a1) / d1_axe
        armour_parity = base * (d1_axe / d0 - 1)
        damage_parity = (base + a1) / r0
        print(f"  {role:<9} {r0:>7.2f} {r1:>7.2f} {100*(r1/r0-1):>+6.1f}% | "
              f"{a1:>9.1f} {armour_parity:>11.1f} | {d1_axe:>9.2f} {damage_parity:>11.2f}")
    print("\n  Left lever: cut tier-1 armour to a fraction of what it grants today.")
    print("  Right lever: raise tier-1 weapon damage instead, keeping the armour.")
    print("  Neither is free, and doing nothing means every tier lengthens fights again.")
    return 0


# --------------------------------------------------------------------------- export


def cmd_export(args):
    """Everything the artifact renders, as one JSON file."""
    prof = profiles(args.run)
    info = base_role_hp(args.run)
    grid = query(
        """
        select b.weapon, b.weapon_roll, jsonb_array_length(b.runes) as rune_count,
               b.booster,
               avg(b.weapon_damage_base) as weapon_damage_base,
               r.target_role, r.target_armor, r.target_armor_tier,
               avg(r.target_hp) as target_hp, count(*) as n,
               percentile_cont(0.5) within group (order by r.dps_sustained) as dps_p50,
               percentile_cont(0.1) within group (order by r.dps_sustained) as dps_p10,
               percentile_cont(0.9) within group (order by r.dps_sustained) as dps_p90,
               percentile_cont(0.5) within group (order by r.ttk_s)         as ttk_p50,
               percentile_cont(0.1) within group (order by r.ttk_s)         as ttk_p10,
               percentile_cont(0.9) within group (order by r.ttk_s)         as ttk_p90,
               percentile_cont(0.5) within group (order by r.dmg_per_hit)   as dph_p50,
               percentile_cont(0.5) within group (order by r.hits_to_kill)  as h2k_p50
        from sim_result r join sim_build b on b.id = r.build_id
        where r.run_id = %(run)s and r.dps_sustained is not null and r.ttk_s is not null
        group by 1,2,3,4,6,7,8
        """,
        {"run": args.run},
        timeout_s=1800,
    )
    payload = {
        "run_id": args.run,
        "profiles": [{"weapon": k[0], "roll": k[1], **v} for k, v in sorted(prof.items())],
        "base_hp": info["base_hp"],
        "targets": info["targets"],
        "grid": [{k: (f(v) if not isinstance(v, (str, bool, type(None))) else v)
                  for k, v in row.items()} for row in grid],
    }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload), encoding="utf-8")
    print(f"{len(payload['grid']):,} grid rows -> {out} ({out.stat().st_size/1024:.0f} KB)")
    return 0


# --------------------------------------------------------------------------- cli


def main(argv=None):
    p = argparse.ArgumentParser(prog="tier_balance", description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--run", type=int, default=7, help="sim_run.id to read (default 7)")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("validate", help="prove the model reproduces the sweep")
    sub.add_parser("compare", help="tier 0 vs tier 1 DPS and TTK")
    sv = sub.add_parser("solve", help="tier 2 weapon, armour and skill-damage values")
    sv.add_argument("--step", type=float, default=1.20,
                    help="tier step multiplier; 1.20 is the +20%% the question proposes")
    ex = sub.add_parser("export", help="write the artifact's JSON")
    ex.add_argument("--out", default="build/tier-balance/tier_balance.json")
    args = p.parse_args(argv)
    return {"validate": cmd_validate, "compare": cmd_compare,
            "solve": cmd_solve, "export": cmd_export}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
