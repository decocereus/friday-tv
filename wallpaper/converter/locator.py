from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

from .pkg import FormatError, Package


class MissingAsset(Exception):
    pass


@lru_cache(maxsize=1)
def _effect_index(assets: Path) -> tuple[Path, ...]:
    root = assets / "effects"
    if not root.is_dir():
        return ()
    return tuple(sorted(p for p in root.iterdir() if p.is_dir()))


class Locator:
    """Finds a scene asset in the wallpaper package, then in Wallpaper Engine's own assets."""

    def __init__(self, assets: Path, package: Package | None = None, folder: Path | None = None):
        self.assets = assets
        self.package = package
        self.folder = folder

    def find(self, name: str, effect_dir: str | None = None) -> str:
        """Returns an opaque handle string for a resolved asset, or raises MissingAsset."""
        for candidate in self._candidates(name, effect_dir):
            if self.package is not None and candidate in self.package:
                return f"pkg:{candidate}"
            if self.folder is not None and (self.folder / candidate).is_file():
                return f"file:{self.folder / candidate}"
            local = self.assets / candidate
            if local.is_file():
                return f"file:{local}"
            if effect_dir:
                scoped = self.assets / "effects" / effect_dir / candidate
                if scoped.is_file():
                    return f"file:{scoped}"
            for effect in _effect_index(self.assets):
                scoped = effect / candidate
                if scoped.is_file():
                    return f"file:{scoped}"
        raise MissingAsset(name)

    def read(self, name: str, effect_dir: str | None = None) -> bytes:
        return self.read_handle(self.find(name, effect_dir))

    def read_handle(self, handle: str) -> bytes:
        kind, _, rest = handle.partition(":")
        if kind == "pkg":
            assert self.package is not None
            return self.package.read(rest)
        return Path(rest).read_bytes()

    def read_json(self, name: str, effect_dir: str | None = None) -> dict:
        handle = self.find(name, effect_dir)
        raw = self.read_handle(handle)
        try:
            return json.loads(raw.decode("utf-8-sig"))
        except json.JSONDecodeError as exc:
            raise FormatError(f"{handle}: malformed JSON ({exc})") from exc

    def read_text(self, name: str, effect_dir: str | None = None) -> str:
        return self.read(name, effect_dir).decode("utf-8-sig")

    def _candidates(self, name: str, effect_dir: str | None) -> list[str]:
        name = name.replace("\\", "/").lstrip("/")
        out = [name]
        if name.endswith(".tex"):
            out.append(name[: -len(".tex")] + ".png")
        return out
