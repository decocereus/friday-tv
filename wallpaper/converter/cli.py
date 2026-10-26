from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

from . import convert as converter
from . import device
from . import preview as previewer
from . import serve as server
from . import service as agent
from .device import TV_DIR, adb
from .pkg import FormatError

WORKSHOP_APP = "431960"
WORKSHOP_URL = re.compile(r"[?&]id=(\d+)")


def root() -> Path:
    return device.root()


def paths() -> tuple[Path, Path, Path]:
    base = root() / ".local"
    return base / "wallpaper-engine" / "assets", base / "wallpapers", base / "steam"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="wallpaper.sh", description="Wallpaper Engine to Friday Home bundles")
    sub = parser.add_subparsers(dest="command", required=True)

    add = sub.add_parser("add", help="download a Workshop item (or take a folder) and convert it")
    add.add_argument("items", nargs="+")

    convert_cmd = sub.add_parser("convert", help="convert a folder that is already on disk")
    convert_cmd.add_argument("folders", nargs="+", type=Path)

    push = sub.add_parser("push", help="copy bundles to the TV")
    push.add_argument("ids", nargs="*")
    push.add_argument("--set", action="store_true", help="tell Friday Home to show the first one")

    sub.add_parser("list", help="list converted bundles")

    remove = sub.add_parser("remove", help="delete a bundle here and on the TV")
    remove.add_argument("ids", nargs="+")

    sub.add_parser("report", help="print the coverage table across every bundle")

    serve_cmd = sub.add_parser("serve", help="run the LAN service the TV browses Workshop through")
    serve_cmd.add_argument("--port", type=int, default=server.DEFAULT_PORT)

    service_cmd = sub.add_parser("service", help="manage the launchd agent that runs serve")
    service_cmd.add_argument("action", choices=("install", "uninstall", "status"))
    service_cmd.add_argument("--port", type=int, default=server.DEFAULT_PORT)

    render = sub.add_parser("preview", help="render one frame of a bundle with the reference renderer")
    render.add_argument("id")
    render.add_argument("--time", type=float, default=0.0)
    render.add_argument("--out", type=Path, required=True)

    args = parser.parse_args(argv)
    handler = {
        "add": cmd_add,
        "convert": cmd_convert,
        "push": cmd_push,
        "list": cmd_list,
        "remove": cmd_remove,
        "report": cmd_report,
        "preview": cmd_preview,
        "serve": cmd_serve,
        "service": cmd_service,
    }[args.command]
    return handler(args)


# -- commands ----------------------------------------------------------------


def cmd_add(args) -> int:
    assets, library, steam = paths()
    folders: list[Path] = []
    ids: list[str] = []
    for item in args.items:
        match = WORKSHOP_URL.search(item)
        if match:
            ids.append(match.group(1))
        elif item.isdigit():
            ids.append(item)
        else:
            folders.append(Path(item))
    if ids:
        download(ids, steam)
        folders += [steam / "steamapps" / "workshop" / "content" / WORKSHOP_APP / i for i in ids]
    failed = 0
    for folder in folders:
        failed += convert_one(folder, assets, library)
    return 1 if failed else 0


def cmd_convert(args) -> int:
    assets, library, _ = paths()
    failed = 0
    for folder in args.folders:
        failed += convert_one(folder, assets, library)
    return 1 if failed else 0


def cmd_list(_args) -> int:
    _, library, _ = paths()
    for manifest in sorted(library.glob("*/wallpaper.json")):
        try:
            data = json.loads(manifest.read_text())
        except json.JSONDecodeError:
            continue
        extra = data.get("video", {}).get("file") if data["kind"] == "video" else "plan.json"
        print(f"{data['id']:<14} {data['kind']:<6} {extra:<12} {data.get('title', '')}")
    return 0


def cmd_remove(args) -> int:
    import shutil

    _, library, _ = paths()
    for identifier in args.ids:
        shutil.rmtree(library / identifier, ignore_errors=True)
        device.remove_bundle(identifier)
        print(f"removed {identifier}")
    return 0


def cmd_push(args) -> int:
    _, library, _ = paths()
    identifiers = args.ids or [p.parent.name for p in sorted(library.glob("*/wallpaper.json"))]
    if not identifiers:
        print("nothing to push", file=sys.stderr)
        return 1
    adb("shell", "mkdir", "-p", TV_DIR)
    for identifier in identifiers:
        bundle = library / identifier
        if not (bundle / "wallpaper.json").is_file():
            print(f"{identifier}: no bundle here", file=sys.stderr)
            return 1
        converter.backfill(bundle)
        device.push_bundle(bundle, identifier)
        print(f"pushed {identifier}")
    if args.set:
        device.select(identifiers[0])
        print(f"set {identifiers[0]}")
    return 0


def cmd_report(_args) -> int:
    _, library, _ = paths()
    rows = []
    for path in sorted(library.glob("*/report.json")):
        try:
            report = json.loads(path.read_text())
        except json.JSONDecodeError:
            continue
        if report.get("kind") == "video":
            video = report.get("video", {})
            rows.append(
                (report["id"], "video", "-", "-", "-", "-", f"{video.get('codec')} {video.get('mode')}")
            )
            continue
        counts = report.get("counts", {})
        reasons: dict[str, int] = {}
        for obj in report.get("objects", []):
            if obj["state"] == "skipped":
                key = obj.get("reason", "?").split(":")[0]
                reasons[key] = reasons.get(key, 0) + 1
        rows.append(
            (
                report["id"],
                "scene",
                str(counts.get("rendered", 0)),
                str(counts.get("skipped", 0)),
                str(counts.get("draws", 0)),
                str(len(report.get("shaderFailures", []))),
                ", ".join(f"{k} x{v}" for k, v in sorted(reasons.items())) or "-",
            )
        )
    header = ("id", "kind", "rendered", "skipped", "draws", "shaderfail", "skipped because")
    widths = [max(len(header[i]), *(len(r[i]) for r in rows)) if rows else len(header[i]) for i in range(7)]
    print("  ".join(h.ljust(widths[i]) for i, h in enumerate(header)))
    print("  ".join("-" * widths[i] for i in range(7)))
    for row in rows:
        print("  ".join(row[i].ljust(widths[i]) for i in range(7)))
    return 0


def cmd_preview(args) -> int:
    _, library, _ = paths()
    bundle = library / args.id
    if not (bundle / "plan.json").is_file():
        print(f"{args.id}: no plan.json, is it a video wallpaper?", file=sys.stderr)
        return 1
    out = previewer.render(bundle, args.time, args.out)
    print(out)
    return 0


def cmd_serve(args) -> int:
    assets, library, steam = paths()
    return server.run(assets, library, steam, root(), args.port)


def cmd_service(args) -> int:
    if args.action == "install":
        return agent.install(root(), args.port)
    if args.action == "uninstall":
        return agent.uninstall(root())
    return agent.status(root(), args.port)


# -- helpers -----------------------------------------------------------------


def convert_one(folder: Path, assets: Path, library: Path) -> int:
    try:
        done = converter.convert(folder, assets, library)
    except (FormatError, OSError, ValueError) as exc:
        print(f"{folder}: {exc}", file=sys.stderr)
        return 1
    report = done["report"]
    counts = report.get("counts")
    tail = (
        f"{counts['rendered']} rendered, {counts['skipped']} skipped, {counts['draws']} draws"
        if counts
        else f"{report.get('video', {}).get('codec', '?')}"
    )
    print(f"{done['id']}: {done['manifest']['kind']}, {tail} -> {done['path']}")
    return 0


def steam_user() -> str:
    user = os.environ.get("STEAM_USER")
    if user:
        return user
    path = root() / ".local" / "steam-user"
    if path.is_file():
        return path.read_text().strip()
    raise SystemExit("set STEAM_USER or write the account name to .local/steam-user")


def download(ids: list[str], steam: Path) -> None:
    steam.mkdir(parents=True, exist_ok=True)
    command = ["steamcmd", "+force_install_dir", str(steam), "+login", steam_user()]
    for identifier in ids:
        command += ["+workshop_download_item", WORKSHOP_APP, identifier]
    command.append("+quit")
    # Interactive on purpose: this is where a Steam Guard prompt is answered. The service has a
    # non-interactive twin in jobs.py that fails instead of ever asking.
    subprocess.run(command, check=True)


if __name__ == "__main__":
    raise SystemExit(main())
