import pytest

from converter import pkg


def test_round_trip(tmp_path):
    entries = {
        "scene.json": b'{"objects": []}',
        "materials/background.json": b"{}",
        "shaders/flow.frag": bytes(range(256)),
    }
    path = tmp_path / "scene.pkg"
    pkg.write(path, entries)

    loaded = pkg.load(path)
    assert loaded.names() == sorted(entries)
    for name, payload in entries.items():
        assert loaded.read(name) == payload


def test_lookup_is_case_insensitive_and_slash_agnostic(tmp_path):
    path = tmp_path / "scene.pkg"
    pkg.write(path, {"Materials/Mask.tex": b"x"})
    loaded = pkg.load(path)

    assert "materials/mask.tex" in loaded
    assert loaded.read("Materials\\Mask.tex") == b"x"


def test_missing_entry_names_the_file(tmp_path):
    path = tmp_path / "scene.pkg"
    pkg.write(path, {"a": b"1"})
    with pytest.raises(pkg.FormatError, match="scene.pkg"):
        pkg.load(path).read("b")


def test_bad_magic_is_rejected(tmp_path):
    path = tmp_path / "broken.pkg"
    path.write_bytes(b"\x04\x00\x00\x00JUNK\x00\x00\x00\x00")
    with pytest.raises(pkg.FormatError, match="not a PKG"):
        pkg.load(path)


def test_truncated_entry_is_rejected(tmp_path):
    path = tmp_path / "short.pkg"
    pkg.write(path, {"a": b"12345"})
    path.write_bytes(path.read_bytes()[:-3])
    with pytest.raises(pkg.FormatError, match="out of range"):
        pkg.load(path)
