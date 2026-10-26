"""Every corpus scene must produce a plan.json that matches wallpaper/docs/format.md."""

import json
import shutil

import pytest

from converter import shaders

from .conftest import corpus_scenes

GEOMETRY_KINDS = {"fullscreen", "quad"}
BLEND_MODES = {"disabled", "normal", "translucent", "additive", "multiply"}
SKIP_REASONS = {"particles", "text", "script", "sound", "3d-model", "video-texture"}

pytestmark = pytest.mark.skipif(not corpus_scenes(), reason="the Workshop corpus is not on this machine")


@pytest.fixture(scope="module")
def plans(bundles):
    return {identifier: (path, json.loads((path / "plan.json").read_text())) for identifier, path in bundles.items()}


def test_every_corpus_scene_converted(bundles):
    assert len(bundles) == len(corpus_scenes())


def test_manifest_shape(plans):
    for identifier, (path, _) in plans.items():
        manifest = json.loads((path / "wallpaper.json").read_text())
        assert manifest["version"] == 1
        assert manifest["id"] == identifier
        assert manifest["kind"] == "scene"
        assert manifest["scene"] == {"plan": "plan.json", "fps": 30}
        assert "video" not in manifest
        assert (path / manifest["preview"]).is_file()


def test_plan_shape(plans):
    for identifier, (path, plan) in plans.items():
        assert plan["version"] == 1, identifier
        assert len(plan["canvas"]) == 2 and all(v > 0 for v in plan["canvas"])
        assert len(plan["clear"]) == 4
        assert set(plan["parallax"]) == {"enabled", "amount", "delay", "mouseInfluence"}

        for tid, info in plan["textures"].items():
            assert (path / info["file"]).is_file(), f"{identifier}:{tid}"
            assert info["width"] > 0 and info["height"] > 0
            assert 0 < info["imageWidth"] <= info["width"]
            assert 0 < info["imageHeight"] <= info["height"]
            assert info["wrap"] in {"clamp", "repeat"}
            assert info["filter"] in {"linear", "nearest"}

        for pid, files in plan["programs"].items():
            assert (path / files["vertex"]).is_file(), f"{identifier}:{pid}"
            assert (path / files["fragment"]).is_file(), f"{identifier}:{pid}"

        for rid, box in plan["targets"].items():
            assert box["width"] > 0 and box["height"] > 0, f"{identifier}:{rid}"


def test_draws_only_name_things_the_plan_declares(plans):
    for identifier, (_, plan) in plans.items():
        for index, draw in enumerate(plan["draws"]):
            where = f"{identifier}:draw{index}"
            assert draw["program"] in plan["programs"], where
            assert draw["target"] == "main" or draw["target"] in plan["targets"], where
            assert draw["blend"] in BLEND_MODES, where
            assert draw["geometry"]["kind"] in GEOMETRY_KINDS, where
            if draw["geometry"]["kind"] == "quad":
                for key in ("origin", "size", "scale", "parallaxDepth"):
                    assert len(draw["geometry"][key]) == 2, where
                assert isinstance(draw["geometry"]["angle"], (int, float)), where
            assert len(draw["geometry"].get("texCoordScale", [1, 1])) == 2, where
            for reference in draw["textures"]:
                if reference is None or reference == "fullframe":
                    continue
                if reference.startswith("target:"):
                    assert reference[len("target:") :] in plan["targets"], where
                else:
                    assert reference in plan["textures"], where
            for name, value in draw["uniforms"].items():
                assert len(value) in (1, 2, 3, 4, 9, 16), f"{where}:{name}"


def test_targets_are_written_before_they_are_read(plans):
    for identifier, (path, plan) in plans.items():
        if json.loads((path / "report.json").read_text())["shaderFailures"]:
            continue  # a dropped draw can leave a gap in an effect's chain; report.json says so
        written = set()
        for index, draw in enumerate(plan["draws"]):
            for reference in draw["textures"]:
                if isinstance(reference, str) and reference.startswith("target:"):
                    assert reference[len("target:") :] in written, f"{identifier}:draw{index}:{reference}"
            if draw["target"] != "main":
                written.add(draw["target"])


@pytest.mark.skipif(shutil.which("glslangValidator") is None, reason="glslangValidator is not installed")
def test_every_shader_in_every_bundle_validates(plans):
    for identifier, (path, plan) in plans.items():
        for pid, files in plan["programs"].items():
            for key, stage in (("vertex", shaders.VERTEX), ("fragment", shaders.FRAGMENT)):
                source = (path / files[key]).read_text()
                assert source.startswith("#version 300 es")
                assert shaders.validate(source, stage) is None, f"{identifier}:{pid}.{stage}"


def test_report_explains_every_skip(bundles):
    for identifier, path in bundles.items():
        report = json.loads((path / "report.json").read_text())
        assert report["id"] == identifier
        for entry in report["objects"]:
            assert entry["state"] in {"rendered", "skipped"}
            if entry["state"] == "skipped":
                assert entry["reason"]
