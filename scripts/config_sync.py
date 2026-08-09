"""Check, repair, or fully regenerate a server's plugin configs against the ones the repo ships.

Why this has to exist
---------------------
`BPvPPlugin.walkAndSaveFiles` copies a packaged config into the plugin's data folder
**only when the target does not already exist**:

    if (!Files.exists(targetPath)) { Files.copy(file, targetPath); }

That is correct for a live server, where an operator's edits must survive a plugin update.
It also means a data folder freezes at whatever it first wrote: every balance change made
in the repo afterwards is invisible to that server, silently and with nothing logged. The
same applies one level down, to individual keys -- a skill that saves its Java defaults on
load writes them once, and a later change to that default never reaches the file.

For a balance sweep that is not a nuisance, it is a correctness failure. Run 1 measured
14,292 `champions:wind_blade` builds against `min 7.0 / base 6.0 / max 8.0` -- a min above
its own base -- while the repo said `5.0 / 6.0 / 7.0`. Nothing in the sweep, the database
or the dashboards could have said so; a pipeline expectation written for another purpose
caught it weeks later.

Values, not bytes
-----------------
Comparison is on parsed YAML leaves. The plugins reserialize configs when they save, so a
byte comparison calls 34 files "drifted" when 3 of them differ in any value that matters --
and acting on that would rewrite working files for no reason.

Three states per leaf:

* **differs**     -- both sides have the key, the values disagree. The real drift.
* **server-only** -- the server has a key the repo does not. Usually a Java default the
  plugin saved itself; sometimes a setting removed from the repo since. Copying the repo's
  file over the top **deletes these**, which is why `--apply` refuses such files.
* **repo-only**   -- the repo has a key the server never received. Harmless if the code
  defaults to the same value, wrong if it does not, and unknowable from here.

Modes
-----
    python scripts/config_sync.py                  # report. changes nothing
    python scripts/config_sync.py --apply          # copy repo -> server where that is lossless
    python scripts/config_sync.py --regenerate     # delete server copies; plugin rewrites them
    python scripts/config_sync.py --all            # widen past the balance surface
    python scripts/config_sync.py --server D:/mc/plugins

`--regenerate` is the only mode that guarantees no stale key survives, because it is the
only one that does not preserve anything: the plugin rewrites the file from the jar on next
start and every skill re-saves its current Java defaults. That is what "fully regenerated"
has to mean, and it is what a trustworthy baseline sweep needs. Everything it removes is
backed up first.

**Stop the server first.** `BPvPPlugin.saveConfig` writes the in-memory config back to
disk, so a running server will recreate every file this deletes -- with exactly the stale
values it already had in memory -- the moment it shuts down. Deleting the files under a
live server does not fail, does not warn, and quietly achieves nothing.

Scope
-----
By default only the **balance surface**: `items/**` and `skills/**`, which is what
`SimConfigDigest` walks and therefore what a sweep's numbers depend on. `config.yml` is
excluded on purpose -- the dev server's has 61 values and 52 keys that differ from the
repo's defaults (command ranks, activity thresholds), all of them deliberate, and none of
them anything a duel measures. `--all` includes it and should be used with care.
"""
from __future__ import annotations

import argparse
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

try:
    import yaml
except ImportError:
    print("error: needs PyYAML  (pip install pyyaml)", file=sys.stderr)
    raise SystemExit(2)

REPO = Path(__file__).resolve().parent.parent
DEFAULT_SERVER = Path(r"C:\Users\Owen\Personal\Minecraft\Server\plugins")

# Gradle module -> the plugin folder its resources land in. Related by convention rather
# than by a rule -- `getDataFolder()` resolves the Bukkit plugin name, not the module
# directory -- so it is a table and not a transformation.
MODULES = {
    "core": "Core",
    "champions": "Champions",
    "clans": "Clans",
    "progression": "Progression",
    "shops": "Shops",
    "game": "Game",
    "hub": "Hub",
    "areas": "Areas",
    "lunar": "Lunar",
    "balance-simulation": "BalanceSimulation",
}

# What a duel's numbers actually come from. See the module docstring.
BALANCE_SURFACE = ("items", "skills")


def flatten(tree, prefix=""):
    """Config as a flat {dotted.key: leaf} map, the same shape `SimConfigDigest` hashes."""
    out = {}
    for key, value in (tree or {}).items():
        path = f"{prefix}.{key}" if prefix else str(key)
        if isinstance(value, dict):
            out.update(flatten(value, path))
        else:
            out[path] = value
    return out


def load(path: Path) -> dict:
    try:
        return flatten(yaml.safe_load(path.read_text(encoding="utf-8")))
    except Exception as exc:  # a malformed config is a finding, not a crash
        print(f"  ! could not parse {path}: {exc}", file=sys.stderr)
        return {}


class Finding:
    def __init__(self, plugin, rel, repo_path, server_path):
        self.plugin = plugin
        self.rel = rel
        self.repo_path = repo_path
        self.server_path = server_path
        self.missing = not server_path.exists()
        if self.missing:
            self.differs, self.server_only, self.repo_only = {}, set(), set()
            return
        server, repo = load(server_path), load(repo_path)
        self.differs = {k: (server[k], repo[k])
                        for k in set(server) & set(repo) if server[k] != repo[k]}
        self.server_only = set(server) - set(repo)
        self.repo_only = set(repo) - set(server)

    @property
    def clean(self) -> bool:
        return not (self.missing or self.differs or self.server_only or self.repo_only)

    @property
    def lossless(self) -> bool:
        """Whether copying the repo's file over the server's would discard nothing."""
        return not self.server_only

    def label(self) -> str:
        if self.missing:
            return "MISSING "
        if self.differs:
            return "DRIFTED "
        if self.server_only:
            return "EXTRA   "
        return "AHEAD   "


def scan(server_root: Path, only: set[str] | None, everything: bool) -> list[Finding]:
    findings = []
    for module, plugin in sorted(MODULES.items()):
        if only and module not in only:
            continue
        packaged = REPO / module / "src" / "main" / "resources" / "configs"
        if not packaged.is_dir():
            continue
        for repo_file in sorted(packaged.rglob("*.yml")):
            rel = repo_file.relative_to(packaged)
            if not everything and rel.parts[0] not in BALANCE_SURFACE:
                continue
            findings.append(Finding(plugin, rel, repo_file, server_root / plugin / rel))
    return findings


def report(finding: Finding, limit: int) -> None:
    print(f"{finding.label()} {finding.plugin}/{finding.rel.as_posix()}")
    for key, (was, now) in sorted(finding.differs.items())[:limit]:
        print(f"           {key}")
        print(f"             server {was!r}")
        print(f"             repo   {now!r}")
    if len(finding.differs) > limit:
        print(f"           ... and {len(finding.differs) - limit} more differing")
    if finding.server_only:
        preview = ", ".join(sorted(finding.server_only)[:3])
        print(f"           {len(finding.server_only)} server-only keys ({preview}"
              f"{', ...' if len(finding.server_only) > 3 else ''})")
    if finding.repo_only:
        preview = ", ".join(sorted(finding.repo_only)[:3])
        print(f"           {len(finding.repo_only)} repo-only keys ({preview}"
              f"{', ...' if len(finding.repo_only) > 3 else ''})")


def backup_dir() -> Path:
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return REPO / "build" / "config-sync-backup" / stamp


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--server", type=Path, default=DEFAULT_SERVER)
    parser.add_argument("--apply", action="store_true",
                        help="copy the repo's file over the server's, where lossless")
    parser.add_argument("--regenerate", action="store_true",
                        help="delete the server's copies so the plugin rewrites them from the jar")
    parser.add_argument("--force", action="store_true",
                        help="with --apply, overwrite even when server-only keys would be lost")
    parser.add_argument("--all", action="store_true",
                        help="every packaged config, not just items/ and skills/")
    parser.add_argument("--only", nargs="*", metavar="MODULE")
    parser.add_argument("--limit", type=int, default=8, help="differing keys shown per file")
    args = parser.parse_args()

    if args.apply and args.regenerate:
        print("error: --apply and --regenerate mean different things; pick one", file=sys.stderr)
        return 2
    if not args.server.is_dir():
        print(f"error: no such plugins directory: {args.server}", file=sys.stderr)
        return 2

    findings = scan(args.server, set(args.only) if args.only else None, args.all)
    if not findings:
        print("error: no packaged configs matched", file=sys.stderr)
        return 2

    interesting = [f for f in findings if not f.clean]
    print(f"server : {args.server}")
    print(f"scope  : {'every packaged config' if args.all else 'items/ and skills/ (the balance surface)'}")
    print(f"{len(findings) - len(interesting)} identical, {len(interesting)} to look at"
          f"  (of {len(findings)} files)\n")

    for finding in interesting:
        report(finding, args.limit)
        print()

    drifted = [f for f in interesting if f.differs or f.missing]

    if args.regenerate:
        backup = backup_dir()
        removed = 0
        for finding in findings:
            if finding.missing:
                continue
            target = backup / finding.plugin / finding.rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(finding.server_path, target)
            finding.server_path.unlink()
            removed += 1
        print(f"removed {removed} config files; copies kept in {backup}")
        print("\nIf the server was RUNNING while this ran, none of it took: saveConfig()")
        print("writes the in-memory config back on shutdown, stale values and all. Stop it,")
        print("re-run this, then start it.")
        print("\nStart the server. Each plugin rewrites these from its jar and every skill")
        print("re-saves its current Java defaults, so no stale key survives. Re-run this")
        print("script afterwards -- it should report only server-only keys, which are the")
        print("defaults the plugins just wrote.")
        return 0

    if args.apply:
        backup = backup_dir()
        written, refused = 0, []
        for finding in drifted:
            if not finding.lossless and not args.force:
                refused.append(finding)
                continue
            if not finding.missing:
                target = backup / finding.plugin / finding.rel
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(finding.server_path, target)
            finding.server_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(finding.repo_path, finding.server_path)
            written += 1
        print(f"wrote {written} files; previous versions kept in {backup}")
        for finding in refused:
            print(f"refused {finding.plugin}/{finding.rel.as_posix()}:"
                  f" {len(finding.server_only)} server-only keys would be deleted."
                  f" Use --regenerate, or --force if you know they are dead.")
        print("\nRestart the server before sweeping. The plugins read these at load and the")
        print("sim hashes what it read, so a sweep started now measures the old values.")
        return 0

    if drifted:
        print("Nothing was changed.")
        print("  --apply       to copy the repo's versions over (refuses lossy writes)")
        print("  --regenerate  to delete them and let the plugins rewrite from the jar")
    return 1 if drifted else 0


if __name__ == "__main__":
    raise SystemExit(main())
