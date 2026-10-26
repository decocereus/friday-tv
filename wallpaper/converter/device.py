"""Everything that talks to the TV over adb: pushing bundles, free space, the service pointer."""

from __future__ import annotations

import json
import os
import subprocess
from pathlib import Path

APP = "home.friday.tv"
FILES_DIR = f"/sdcard/Android/data/{APP}/files"
TV_DIR = f"{FILES_DIR}/wallpapers"
SERVER_JSON = f"{FILES_DIR}/server.json"
# A bundle must not take the TV below this; Android starts misbehaving on a full /sdcard.
FREE_FLOOR = 1024 * 1024 * 1024


class DeviceError(Exception):
    pass


def root() -> Path:
    return Path(__file__).resolve().parents[2]


def adb(*args: str, check: bool = True, capture: bool = False, timeout: float = 120.0):
    home = os.environ.get("ANDROID_HOME", str(root() / ".local" / "toolchain" / "android-sdk"))
    host = os.environ.get("TV_HOST", "192.168.1.123")
    command = [str(Path(home) / "platform-tools" / "adb"), "-P", "5041", "-s", f"{host}:5555", *args]
    return subprocess.run(command, check=check, timeout=timeout,
                          capture_output=capture, text=capture)


def free_bytes() -> int:
    """What `df /sdcard` calls Available, in bytes. Raises when the TV does not answer."""
    try:
        done = adb("shell", "df", "/sdcard", capture=True, timeout=20)
    except (subprocess.SubprocessError, OSError) as exc:
        raise DeviceError(f"tv not reachable: {exc}") from exc
    for line in done.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 4 and parts[3].isdigit():
            return int(parts[3]) * 1024
    raise DeviceError("could not read free space from df /sdcard")


def tree_bytes(bundle: Path) -> int:
    return sum(p.stat().st_size for p in bundle.rglob("*") if p.is_file())


def push_tree(bundle: Path, remote: str) -> None:
    """One file at a time: adb cannot create directories under /sdcard/Android/data on this TV."""
    files = sorted(path for path in bundle.rglob("*") if path.is_file())
    folders = sorted({str(path.parent.relative_to(bundle)) for path in files})
    adb("shell", "mkdir", "-p", remote)
    for folder in folders:
        if folder != ".":
            adb("shell", "mkdir", "-p", f"{remote}/{folder}")
    for path in files:
        adb("push", "-q", str(path), f"{remote}/{path.relative_to(bundle).as_posix()}")


def push_bundle(bundle: Path, identifier: str) -> None:
    """Into a staging folder, then one rename, so the TV never scans a half-written bundle."""
    staging = f"{TV_DIR}/.incoming-{identifier}"
    adb("shell", "mkdir", "-p", TV_DIR)
    adb("shell", "rm", "-rf", staging)
    push_tree(bundle, staging)
    adb("shell", "rm", "-rf", f"{TV_DIR}/{identifier}")
    adb("shell", "mv", staging, f"{TV_DIR}/{identifier}")


def remove_bundle(identifier: str) -> None:
    adb("shell", "rm", "-rf", f"{TV_DIR}/{identifier}", check=False)


def select(identifier: str) -> None:
    """Tell Friday Home which wallpaper to show.

    A broadcast, not `am start`: when the screensaver is up, Android brings Home's task forward
    behind the dream without resuming it, so a start intent and its extras are never delivered.
    """
    adb("shell", "am", "broadcast", "-n", f"{APP}/.SetWallpaper",
        "--es", "friday.wallpaper", identifier, check=False)


def publish_server(url: str, token: str) -> None:
    """Writes server.json where the TV looks for it: to a temp name first, then one rename."""
    blob = json.dumps({"url": url, "token": token}) + "\n"
    temp = root() / ".local" / "server.json"
    temp.parent.mkdir(parents=True, exist_ok=True)
    temp.write_text(blob)
    adb("shell", "mkdir", "-p", FILES_DIR)
    adb("push", "-q", str(temp), f"{SERVER_JSON}.tmp")
    adb("shell", "mv", f"{SERVER_JSON}.tmp", SERVER_JSON)
