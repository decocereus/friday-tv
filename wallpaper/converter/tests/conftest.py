import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "wallpaper"))

CORPUS = ROOT / ".local" / "steam" / "steamapps" / "workshop" / "content" / "431960"
ASSETS = ROOT / ".local" / "wallpaper-engine" / "assets"


def corpus_scenes() -> list[Path]:
    if not CORPUS.is_dir():
        return []
    return sorted(folder for folder in CORPUS.iterdir() if (folder / "scene.pkg").is_file())


@pytest.fixture(scope="session")
def bundles(tmp_path_factory) -> dict[str, Path]:
    from converter import convert

    if not ASSETS.is_dir():
        pytest.skip("Wallpaper Engine assets are not on this machine")
    library = tmp_path_factory.mktemp("wallpapers")
    out = {}
    for folder in corpus_scenes():
        done = convert.convert(folder, ASSETS, library)
        out[done["id"]] = done["path"]
    return out
