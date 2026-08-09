"""Pipeline configuration loading.

One YAML file, environment overrides for the two secrets, and a resolved-path
accessor for the lake. Deliberately not a settings framework: every consumer wants
the whole document and the layer paths, and nothing else.
"""
from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_CONF = PROJECT_ROOT / "conf" / "pipeline.yml"

# Layer names are used as directory names under lake.root and as the audit log's
# layer column, so they are declared once here rather than spelled in each job.
BRONZE = "bronze"
SILVER = "silver"
GOLD = "gold"


@dataclass(frozen=True)
class Config:
    raw: dict[str, Any]
    path: Path

    # -- source -------------------------------------------------------------

    @property
    def source(self) -> dict[str, Any]:
        src = dict(self.raw["source"])
        src["password"] = os.environ.get("BALANCE_SIM_PG_PASSWORD", src.get("password"))
        return src

    @property
    def realm(self) -> int:
        return int(self.source["realm"])

    # -- warehouse ----------------------------------------------------------

    @property
    def warehouse(self) -> dict[str, Any]:
        wh = dict(self.raw["warehouse"])
        wh["password"] = os.environ.get(
            "BALANCE_SIM_WAREHOUSE_PASSWORD",
            os.environ.get("BALANCE_SIM_PG_PASSWORD", wh.get("password")),
        )
        return wh

    # -- lake ---------------------------------------------------------------

    @property
    def lake_root(self) -> Path:
        root = Path(self.raw["lake"]["root"])
        return root if root.is_absolute() else (PROJECT_ROOT / root).resolve()

    def dataset(self, layer: str, name: str) -> str:
        """Filesystem location of one dataset. Spark wants a URI-ish string, and on
        Windows a bare backslash path is read as an escape sequence by the Hadoop
        path parser, so posix separators are used throughout."""
        return (self.lake_root / layer / name).as_posix()

    # -- sections -----------------------------------------------------------

    @property
    def spark(self) -> dict[str, Any]:
        return self.raw.get("spark", {})

    @property
    def quality(self) -> dict[str, Any]:
        return self.raw.get("quality", {})

    @property
    def derived_skills(self) -> dict[str, Any]:
        return self.raw.get("derived_skills", {})


def load(path: str | os.PathLike | None = None) -> Config:
    resolved = Path(path) if path else DEFAULT_CONF
    with open(resolved, "r", encoding="utf-8") as handle:
        return Config(raw=yaml.safe_load(handle), path=resolved)
