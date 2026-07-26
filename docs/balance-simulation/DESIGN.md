# Balance Simulation Pipeline — Design

**Status:** In review — real-pipeline engine, dev-server-only, full scope incl. actives
**Goal:** Replace the SQL-modeled DPS/TTK dashboards with a simulation engine
that uses the game's actual code and values, exports results to Postgres, and
lets Grafana be a thin visualization layer.

**Packaging (2026-07-21):** ships as its **own plugin** (`balance-simulation`),
not inside `core` or `champions`. It depends on `core`, `champions`, and
whatever else it needs (Guice module registration, jOOQ/Flyway, Paper API,
NMS/paperweight), same as any other module in this repo — but it is loaded and
versioned separately, and can be excluded entirely from a build/deploy that
doesn't want simulation code on the classpath at all (stronger isolation than
a config flag alone). `champions.simulation.enabled` still hard-gates the
runtime behavior for defense in depth, but the primary isolation mechanism is
now "the plugin jar isn't present."

**Core decision (2026-07-18):** the simulator must not require *any* sim-specific
code in items/skills/effects (single source of truth). Therefore it **drives the
real combat pipeline**: headless fake players are spawned on a dev server, given
real roles/builds/weapons through the real managers, and fight real duels in real
time while a recorder measures the resulting `DamageEvent`s. Duels run massively
in parallel (state is keyed per player UUID), and stochastic elements (damage
min/max rolls, crits) are handled with Monte-Carlo iterations per matchup.
Simulation is hard-gated behind a config flag so it can only run on dev/staging.

---

## 1. Problem

Today there are **three copies of combat truth**:

| Copy | Where | Drift risk |
|------|-------|-----------|
| Game code | `DamageEvent`, skill classes, stat handlers | authoritative |
| Values | YAML → `grafana_config` mirror | low (auto-synced) |
| Mechanics | ~200KB of SQL CTEs in 5 dashboard JSONs | **high — hand-derived** |

`grafana/DASHBOARD_NOTES.md` documents the consequences: ComboAttack's ramp had to
be re-derived as a closed-form quadratic; Vengeance is approximated with an
equal-attack-rate assumption; DoTs assume single application; opponent attack speed
isn't modeled at all. Every mechanic change requires manually re-deriving SQL, and
accuracy bugs are silent.

## 2. Prior art (what the industry does)

- **SimulationCraft (WoW):** event-driven simulator in *virtual time*; exists
  specifically because closed-form calculators fail on stateful mechanics (ramps,
  procs, resources). Runs scenarios over many iterations, produces DPS
  distributions and stat weights.
- **Game telemetry practice:** model-based data (simulation) and empirical data
  (real fight logs / KDR) are separate pipelines feeding the same dashboards;
  comparing them detects model drift.
- **Balance workflow:** results are keyed by patch/config version so designers can
  diff "before vs after" for a proposed change.

## 3. Architecture overview

```
┌────────────────────── Paper server (balance-simulation plugin) ─────────────┐
│  EXTRACT                     TRANSFORM                    LOAD              │
│  ItemRegistry  ─┐                                                           │
│  Skill singletons├─► BalanceCatalog ─► SimulationEngine ─► SimResultRepo ──┼─► Postgres
│  Role enum      │   (enumerate       (virtual-time duel    (jOOQ batch      │   sim_run
│  Rune handlers ─┘    permutations)    engine, reuses       insert, async)   │   sim_build
│                                       DamageEvent math)                     │   sim_result
│  Triggers: /simulate command · config reload hook · @UpdateEvent schedule   │
└─────────────────────────────────────────────────────────────────────────────┘
                                                                Grafana (thin SELECTs)
```

### 3.1 Extract — `BalanceCatalog`

Enumerates the permutation space from **live registries** (never from YAML):

- Weapons: `ItemRegistry.getItems()` filtered to `WeaponItem`, reading the
  `StatContainerComponent` (`MELEE_DAMAGE`, `MELEE_ATTACK_SPEED`) post-`reload()`.
- Roles: `Role` enum (health, armor materials).
- Skills: the Guice `Skill` singletons, slot/class constraints from `SkillType`.
- Runes/gems/effects: their handler singletons.

A **build** = role × one skill per slot × **the level of every skill in that
build** × weapon × rune set. Validity rules (class/slot exclusivity, weapon-type
guards like ComboAttack's) come from the same code the game uses, not re-encoded.

#### Skill levels are fully permuted, not swept one-at-a-time

Every skill in a build varies independently over its legal range, so that every
build a real player can actually run has a row in `sim_result` and live data
(KDR / playtime snapshots, later per-build combat logs) can be **joined directly
against the simulated build fingerprint** — no interpolation, no "nearest level"
fudging. This is what makes §3.5's theory-vs-practice overlay a per-build
comparison instead of a per-skill aggregate.

Two rules bound the space, and both come from real code — they must be applied
by the enumerator, not approximated:

1. **Per-skill cap** — allocated level ∈ `1..Skill.getMaxLevel()`, where
   `maxLevel` is config-driven (`Skill.java:259`, `"maxlevel"`, default 5).
   `BuildRepository.java:126-127` clamps to it on load, so above-cap allocations
   are not representable.
2. **Build point budget** — `RoleBuild.points = 12` (`RoleBuild.java:36`).
   A level vector is only valid if its **total allocated levels ≤ 12**. This is
   the dominant prune: the naive 5-slot × 5-level space is 5⁵ = 3125 vectors,
   but the ≤12-point constraint cuts it to a few hundred, and it also means
   "everything at max" is *not* a reachable build. The enumerator must generate
   **budget-feasible vectors only** — enumerating then filtering is the same
   result but wastes the orchestrator's time.

#### Effective level ≠ allocated level

`SkillListener.getLevel` (`SkillListener.java:699-718`) shows the runtime level a
skill actually executes at is:

```
effective = allocated
          + 1                    if type ∈ {SWORD, AXE, BOW} and holding a booster weapon
          + boostEffectAmplifier if a SkillBoostEffect is active (ChampionsEffectTypes)
```

and it is **not re-clamped to `maxLevel`** — a booster genuinely pushes a skill
to `maxLevel + 1`. Two consequences:

- **Booster is a weapon property, not a separate axis.** The weapon axis already
  includes `booster_sword` / `booster_axe` (`SkillWeapons.java:42-43`,
  `isBooster`), so the +1 falls out of the real code path for free *provided the
  sim equips real weapons and reads level through the real accessor* — which the
  real-pipeline design (§3.2) already does. The engine must **never** compute a
  level itself; passing an allocated level where an effective one is required is
  exactly the class of drift this project exists to remove.
- **`sim_build` must record both.** Store the allocated level vector (that is
  what a player configures, and what joins to live build data) *and* the
  effective level per skill (that is what the numbers came from). Otherwise a
  booster run and a non-booster run at the same allocation look identical in the
  fingerprint but produce different DPS.

The level axis still multiplies the sweep (open question 6), so tiering is
likely: an all-vectors nightly run vs. a budget-feasible-but-representative fast
run for the reload-triggered path.

### 3.2 Transform — real-pipeline duel engine

**No sim-specific code in items/skills/effects.** The engine spawns headless
fake players (NMS `ServerPlayer` with a no-op connection — the repo already
builds against paperweight/Mojang mappings) and runs **real duels through the
real pipeline**: vanilla `attack()` → `DamageEventProcessor` → every registered
listener (skill passives, rune/gem handlers, effect listeners) → 
`DamageEvent.getModifiedDamage()`. ComboAttack's ramp, Vengeance's counter and
expiry, energy regen, cooldowns — all execute as the *actual* mechanic objects.

- `SimCombatant` — a fake player materialized through the real managers:
  ephemeral `Client`/`Gamer` (never persisted), `RoleManager` role, `BuildManager`
  build (one skill per slot at a chosen level), `ItemFactory` weapon, runes.
- `DuelOrchestrator` — runs many duels concurrently in a dedicated sim area;
  per-tick it performs due melee swings (timing from the real
  `DEFAULT_DELAY / (1 + attackSpeed)`; `DamageDelayManager` remains the
  enforcement backstop) and drives active skills via a **rotation policy**
  (greedy: cast when off cooldown and energy allows), synthesizing the same
  input events a real player produces.
- `SimRecorder` — MONITOR-priority listener on the custom damage events; records
  per-hit timestamp, raw/final damage, and the modifier breakdown from
  `getAppliedModifiers()`; detects kill → TTK. Each matchup runs N iterations
  (Monte Carlo over damage rolls/crits); mean + percentiles are stored.
- Real time, parallel: a 30 s fight costs 30 s, but hundreds of duels run
  simultaneously (all combat state is per-UUID), so a full sweep is minutes.

**Isolation (infrastructure-only changes, never in skills/items):**
- Hard gate: `champions.simulation.enabled` config flag — command refuses to run
  when false (default). Dev/staging only.
- Sim entities are flagged (metadata) and excluded from combat stats/kill
  persistence and leaderboards via guards in the stats listeners.
- Fake clients are ephemeral: created in-memory, never written to `clients`,
  removed on duel teardown.

### 3.3 Load — `SimResultRepository`

Mirrors `GrafanaSnapshotRepository`: Flyway migration + jOOQ + async batched
transaction, realm-scoped.

```sql
sim_run    (id, realm, started_at, finished_at, trigger, engine_version,
            config_hash,       -- sha256 over every config value that contributed
            scenario JSONB, status)
sim_build  (id, run_id, role, weapon, runes JSONB,
            skills JSONB,      -- per slot: skill, allocated_level, effective_level
            points_spent,      -- ≤ 12, see §3.1
            booster BOOL,      -- derived from the weapon, stored for queryability
            fingerprint)       -- stable hash → cross-run diffing
sim_result (run_id, build_id, target_role, target_armor, target_hp,
            dmg_per_hit, dps_sustained, dps_burst, ttk_s, hits_to_kill,
            energy_limited BOOL, extras JSONB)
sim_trace  (run_id, build_id, t_ms, event, amount)   -- optional drill-down,
                                                     -- only for flagged builds
```

`config_hash` + `fingerprint` make runs comparable: a "patch preview" is just
two runs diffed in SQL.

### 3.4 Triggers

- `/simulate [scope]` — admin command (auto-registered via the reflections
  loader), runs async with progress + cancellation.
- **Config-reload hook** — same pattern as `GrafanaConfigContributor`: on
  `/reload`, re-run the sim automatically → dashboards are never stale. (Skipped
  if `config_hash` is unchanged.)

### 3.5 Validate

Because the engine *is* the real pipeline, model-vs-game drift is impossible by
construction. Remaining validation is **theory vs practice**: overlay sim TTK/DPS
against empirical data (existing `grafana_role_playtime_snapshot` /
`grafana_skill_kdr_snapshot` KDR tables) to see where real-player outcomes
diverge from theoretical strength — that gap is itself a balance signal
(skill-floor/skill-ceiling effects), not an error to fix.

### 3.6 Visualize

Dashboards become thin (`SELECT … FROM sim_result WHERE run_id = $latest`):

1. **DPS / TTK explorer** — replaces `dps_all_valid_builds`, `ttk_all_valid_builds`,
   `dps_ttk_density` (density/percentile panels read precomputed rows).
2. **Patch diff** — pick run A vs run B, show per-build deltas (the balancing
   workhorse).
3. **Model drift** — predicted vs measured (from §3.5).
4. **Empirical overlay** — sim TTK vs actual KDR/playtime snapshots (existing
   `grafana_*` tables) to see where theory and player behavior disagree.

Existing hand-built SQL dashboards are retired after parity is verified.

## 4. Rollout phases

| Phase | Scope | Exit criteria |
|-------|-------|--------------|
| 1 | Fake-player infra (spawn/teardown, ephemeral clients, stats exclusion), sim void world, sim gate flag, schema + repository, `/simulate` skeleton | Two fake players duel with plain weapons in the sim void world on a dev server; rows land in `sim_result` |
| 2 | `BalanceCatalog` build enumeration (roles × slots × budget-feasible level vectors × weapons incl. boosters × runes × armor), duel orchestration at scale, Monte-Carlo iterations, passives measured correctly | Parity with `ttk_all_valid_builds` where the old SQL was right; documented deltas where it was wrong |
| 3 | Active-skill rotation policy (synthesized inputs, energy/cooldown-aware), thin dashboards (explorer + patch-diff + empirical overlay) | Actives contribute to DPS/TTK; dashboards read only `sim_*` tables |
| 4 | Retire old SQL dashboards; optional reload-triggered auto-runs on dev | Old dashboards deleted |

## 5. Implementation notes & open questions (to settle before/while building)

### What already exists and helps

- **NPC infra**: `core/.../scene/npc/HumanNPC.java` + `HumanNMS.java` — an NMS
  `Player` with a `GameProfile`. **Caveat:** it is deliberately *packet-only*
  (never added to the world) for display purposes. Sim combatants need the
  opposite: a real `ServerPlayer` **added to the world** (with a no-op
  connection) so the vanilla attack path, targeting, and event pipeline engage.
  `HumanNMS` proves the NMS access pattern works here, but the sim needs its own
  `SimPlayer` variant. No client connection = no packets needed; the sim world
  can be far away or empty of real players.
- **ETL template**: `GrafanaSnapshotRepository` (async jOOQ transaction into
  `grafana_*` tables) and `GrafanaConfigSyncService` (reload-triggered sync).
- **Command auto-registration** via the reflections `CommandLoader`.

**Third-party fake-player plugins evaluated (2026-07-21) — build our own,
don't depend on these:** searched for an existing, reliably-maintained
"real server-side fake player" library to avoid re-solving the NMS
`ServerPlayer`-spawning primitive. Candidates: **Fake Player Plugin / FPP**
(fpp.wtf, hangar.papermc.io/Pepe-tf/FakePlayerPlugin — actively updated,
Paper 1.21+, real NMS `ServerPlayer` bodies with hitboxes/collision/damage),
**FakePlayer-CE**, **PlayerDoll**. All are closed-source or oriented at
AFK-farm/population-padding use cases (drop-in bots controlled by slash
commands), not a library for **programmatic, tick-precise control** of a
bot's attack timing, skill activation, and build loadout — which is what
`DuelOrchestrator` needs. None expose the control surface this project
requires, and depending on a closed-source combat-adjacent plugin for the
simulator that is supposed to be the *ground truth* for balance decisions is
a bad trade. Verdict: keep the custom `SimPlayer`/NMS approach in §3.2; if
one of these projects later ships an open-source library mode with
programmatic control, revisit.

### Open questions

1. ~~**Armor damage reduction**~~ — **resolved (2026-07-21): armor does not
   reduce damage.** `ArmorItem` contributes a `StatTypes.HEALTH` stat
   (`ArmorItem.java:25,34-41`) — armor is an effective-HP stat, not a mitigation
   multiplier. `ModifierType.ARMOR` exists in the enum but nothing registers a
   modifier of that type; it is only ever referenced as an *exclusion* category
   (`DamageEvent.java:119,209`), i.e. vestigial. **The SQL model's "armour
   reduction multiplier" is therefore simply wrong** and is a concrete example of
   the drift §1 describes.

   Mitigation that *does* exist and must be modeled:
   - **Resistance** — `EffectTypes.RESISTANCE`, a `VanillaEffectType` wrapping
     `PotionEffectType.RESISTANCE` (−20% per level), applied by the vanilla
     damage calculation rather than by a `DamageModifier`.
   - **Skill-based damage reduction** — `DefensiveSkill` implementors and effect
     listeners that reduce `DamageEvent` damage directly.

   Consequence for the catalog: target durability is `role base HP + Σ armor
   HEALTH stats`, so **armor sets are part of the target permutation space** (and
   of the attacker's, since armor items carry runes/gems). The recorder still logs
   the full `getAppliedModifiers()` breakdown per hit so mitigation stays
   observable rather than assumed.
2. **Ephemeral clients** — how far the `ClientManager` join flow can be bypassed:
   fake players must get a `Client`/`Gamer` + role + build without touching the
   `clients` table, and join listeners (client load is async + DB-backed) must
   not fire for them or must be short-circuited by the sim flag.
3. **Active-skill activation paths (plural)** — there is no single "use skill"
   entry point. `champions/.../skills/types/` defines several activation
   archetypes, each consuming a *different* input, and the rotation policy needs
   a synthesizer per archetype:

   | Archetype | Input the listener consumes |
   |-----------|-----------------------------|
   | `InteractSkill` | right-click (`PlayerInteractEvent` / interaction container) |
   | `PrepareSkill` | right-click to arm, then the next melee hit resolves it |
   | `ToggleSkill` | **drop key** — `SkillListener.onDrop` (`SkillListener.java:234-273`) fires `PlayerUseToggleSkillEvent`; note it filters out inventory-originated drops |
   | `ActiveToggleSkill`, `CooldownToggleSkill` | as above, plus cooldown/state gating |
   | `ChannelSkill`, `EnergyChannelSkill` | *held* right-click, ticked while held — needs sustained input, not a one-shot event |
   | `ChargeSkill` | charge accumulation over time, released at threshold |
   | `BowChargeSkill`, `PrepareArrowSkill` | bow draw/release; needs a real projectile through the vanilla bow path |

   This widens the phase-3 scope meaningfully: a greedy "cast when off cooldown"
   policy is well-defined for `InteractSkill`/`ToggleSkill` but ill-defined for
   channels (how long to hold?) and prepares (they only pay off if a melee hit
   lands). Suggested approach: per-archetype policy with a declared hold/charge
   duration, and treat `PrepareSkill` DPS as conditional-on-hit rather than
   free. Needs a trace through one skill per archetype, not just one sword active.
4. **Stats exclusion surface** — enumerate every persistence path a duel death
   triggers (`KILLS`, `CHAMPIONS_KILLS`, `COMBAT_STATS`, achievements, logs) and
   guard each with the sim-entity flag.
5. **jOOQ codegen dependency** — generated table classes require the local
   Postgres (`localhost:5002/betterpvp`) + codegen task. Fallback: string-based
   `DSL.table(...)` in the repository until codegen is run.
6. **Permutation budget** — the damage-relevant space (roles × skills per slot ×
   **budget-feasible level vectors** × weapons × runes × target roles + armor,
   minus invalid combos) should be counted before building the orchestrator; it
   sets the concurrency and iteration targets (goal: full sweep in single-digit
   minutes on the dev box). The 12-point budget and per-skill `maxLevel` (§3.1)
   prune this hard — count *after* applying them, and decide there whether the
   reload-triggered path needs a reduced tier.
7. ~~**Where duels happen**~~ — **resolved (2026-07-21): a dedicated void
   world.** No chunk-generation cost, no terrain interference with movement
   skills or projectiles, and world-scanning listeners can be excluded by world
   name/flag rather than by distance heuristics. Remaining detail is only
   mechanical: create it on demand when the sim gate is on, flat void generator,
   a solid platform per duel arena, and teardown/unload at run end.

## 6. Key code references

- Damage math: `core/.../combat/events/DamageEvent.java` (`getModifiedDamage`)
- Pipeline: `core/.../combat/listeners/DamageEventProcessor.java`, `DamageEventFinalizer.java`
- Attack speed: `core/.../item/component/impl/stat/StatTypes.java`, `combat/cause/DamageCause.java` (`DEFAULT_DELAY=400`), `combat/delay/DamageDelayManager.java`
- Stats injection: `core/.../item/component/impl/stat/handler/MeleeDamageStatHandler.java`
- Enumeration: `core/.../item/ItemRegistry.java`, `Role` enum, champions `Skill` base
- Skill levels: `champions/.../skills/Skill.java` (`maxLevel`, line 259),
  `champions/.../builds/RoleBuild.java` (`points = 12`),
  `champions/.../builds/repository/BuildRepository.java:126-127` (max-level clamp),
  `champions/.../skills/listeners/SkillListener.java:699-718` (effective level: booster + boost effects)
- Booster weapons: `champions/.../skills/data/SkillWeapons.java` (`hasBooster`, `isBooster`, `getTypeFrom`)
- Activation archetypes: `champions/.../skills/types/` (`InteractSkill`, `PrepareSkill`,
  `ToggleSkill`, `ChannelSkill`, `ChargeSkill`, `BowChargeSkill`, `PrepareArrowSkill`, …)
- Armor → health: `core/.../item/model/ArmorItem.java` (`StatTypes.HEALTH`)
- Mitigation: `core/.../effects/types/positive/ResistanceEffect.java`, `DefensiveSkill` implementors
- ETL template: `core/.../stats/GrafanaConfigSyncService.java`, `champions/.../stats/repository/GrafanaSnapshotRepository.java`
- DB: `core/.../database/Database.java`, `AsyncDSLContext`, Flyway dirs `*-migrations/postgres/`
