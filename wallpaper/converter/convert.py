from __future__ import annotations

import hashlib
import json
import shutil
from pathlib import Path

from . import pkg, scene, video
from .locator import Locator, MissingAsset
from .pkg import FormatError

SCENE_FPS = 30


def bundle_id(folder: Path, project: dict) -> str:
    workshop = project.get("workshopid")
    if workshop:
        return str(workshop)
    if folder.name.isdigit():
        return folder.name
    digest = hashlib.sha1(str(folder.resolve()).encode()).hexdigest()[:8]
    return f"local-{digest}"


def read_project(folder: Path) -> dict:
    path = folder / "project.json"
    if not path.is_file():
        raise FormatError(f"{path}: no project.json")
    try:
        return json.loads(path.read_text(encoding="utf-8-sig"))
    except json.JSONDecodeError as exc:
        raise FormatError(f"{path}: malformed JSON ({exc})") from exc


def convert(folder: Path, assets: Path, library: Path) -> dict:
    project = read_project(folder)
    identifier = bundle_id(folder, project)
    kind = str(project.get("type", "")).lower()
    out = library / identifier
    for stale in ("textures", "shaders"):
        shutil.rmtree(out / stale, ignore_errors=True)
    out.mkdir(parents=True, exist_ok=True)

    manifest = {
        "version": 1,
        "id": identifier,
        "title": str(project.get("title") or identifier),
        "kind": "video" if kind == "video" else "scene",
        "preview": None,
        "source": f"workshop:{identifier}" if identifier.isdigit() else f"folder:{folder.name}",
    }

    preview_name = project.get("preview")
    if isinstance(preview_name, str) and (folder / preview_name).is_file():
        manifest["preview"] = video.preview(folder / preview_name, out)

    report: dict = {"id": identifier, "kind": manifest["kind"], "title": manifest["title"]}

    if kind == "video":
        source = folder / str(project.get("file") or "")
        if not source.is_file():
            raise FormatError(f"{folder}: project.json names a missing video {project.get('file')!r}")
        manifest["video"] = video.convert(source, out)
        report["video"] = manifest["video"]
        (out / "plan.json").unlink(missing_ok=True)
    else:
        plan, scene_report = convert_scene(folder, assets, out, project)
        (out / "plan.json").write_text(json.dumps(plan, indent=1, sort_keys=False) + "\n")
        manifest["scene"] = {"plan": "plan.json", "fps": SCENE_FPS}
        report.update(scene_report)

    manifest["coverage"] = coverage(report)
    (out / "wallpaper.json").write_text(json.dumps(manifest, indent=1) + "\n")
    (out / "report.json").write_text(json.dumps(report, indent=1) + "\n")
    return {"id": identifier, "path": out, "manifest": manifest, "report": report}


def coverage(report: dict) -> dict:
    """What report.json says, small enough for the TV to read with the manifest it already parses."""
    counts = report.get("counts") or {}
    reasons: list[str] = []
    for obj in report.get("objects", []):
        if obj["state"] != "skipped":
            continue
        reason = str(obj.get("reason", "?")).split(":")[0].strip()
        if reason and reason not in reasons:
            reasons.append(reason)
    return {
        "rendered": int(counts.get("rendered", 1 if report.get("kind") == "video" else 0)),
        "skipped": int(counts.get("skipped", 0)),
        "reasons": sorted(reasons)[:6],
    }


def backfill(bundle: Path) -> bool:
    """Tops up an older bundle's manifest with its coverage summary, so the TV can badge it."""
    manifest, report = bundle / "wallpaper.json", bundle / "report.json"
    if not manifest.is_file() or not report.is_file():
        return False
    try:
        data = json.loads(manifest.read_text())
        if "coverage" in data:
            return False
        data["coverage"] = coverage(json.loads(report.read_text()))
    except (json.JSONDecodeError, KeyError, TypeError):
        return False
    manifest.write_text(json.dumps(data, indent=1) + "\n")
    return True


def convert_scene(folder: Path, assets: Path, out: Path, project: dict) -> tuple[dict, dict]:
    package = None
    candidate = folder / "scene.pkg"
    if candidate.is_file():
        package = pkg.load(candidate)
    locator = Locator(assets, package, folder)
    name = str(project.get("file") or "scene.json")
    try:
        scene_json = locator.read_json(name)
    except MissingAsset as exc:
        raise FormatError(f"{folder}: no {name} in the package or folder") from exc

    properties = ((project.get("general") or {}).get("properties")) or {}
    builder = scene.Builder(locator, out, properties)
    plan = builder.build(scene_json)
    report = builder.report()
    report["counts"] = {
        "rendered": sum(1 for e in builder.entries if e.state == "rendered"),
        "skipped": sum(1 for e in builder.entries if e.state == "skipped"),
        "draws": len(plan["draws"]),
        "programs": len(plan["programs"]),
        "textures": len(plan["textures"]),
        "targets": len(plan["targets"]),
    }
    return plan, report
