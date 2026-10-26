"""The launchd user agent that keeps `wallpaper.sh serve` running while the Mac is logged in."""

from __future__ import annotations

import os
import plistlib
import secrets
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

from . import steam

LABEL = "home.friday.wallpaper"
TOKEN_FILE = ".local/service-token"
LOG_DIR = ".local/logs"
# macOS keeps Desktop, Documents and Downloads from processes launchd starts, and a background
# agent has nobody to show the consent prompt to, so it is simply denied.
GUARDED = ("Desktop", "Documents", "Downloads")
BLOCKED = "Operation not permitted"

TCC_HELP = """
macOS will not let a background agent read {where}.

Either move the repo somewhere outside {guarded},
or give the agent's interpreter Full Disk Access:
  System Settings -> Privacy & Security -> Full Disk Access -> +
  press Cmd-Shift-G, type /bin/sh, Add, then run `service install` again.

Until then `scripts/wallpaper.sh serve` in a terminal works exactly the same.
"""


def guarded_root(root: Path) -> str:
    try:
        relative = root.resolve().relative_to(Path.home())
    except ValueError:
        return ""
    first = relative.parts[0] if relative.parts else ""
    return str(root) if first in GUARDED else ""


def plist_path() -> Path:
    return Path.home() / "Library" / "LaunchAgents" / f"{LABEL}.plist"


def domain() -> str:
    return f"gui/{os.getuid()}"


def launchctl(*args: str, check: bool = False) -> subprocess.CompletedProcess:
    return subprocess.run(["launchctl", *args], capture_output=True, text=True, check=check)


def ensure_token(root: Path) -> str:
    path = root / TOKEN_FILE
    if path.is_file():
        existing = path.read_text().strip()
        if existing:
            return existing
    path.parent.mkdir(parents=True, exist_ok=True)
    token = secrets.token_urlsafe(24)
    path.write_text(token + "\n")
    path.chmod(0o600)
    return token


def install(root: Path, port: int) -> int:
    token = ensure_token(root)
    logs = root / LOG_DIR
    logs.mkdir(parents=True, exist_ok=True)
    agent = {
        "Label": LABEL,
        "ProgramArguments": ["/bin/sh", str(root / "scripts" / "wallpaper.sh"), "serve",
                             "--port", str(port)],
        "WorkingDirectory": str(root),
        "EnvironmentVariables": {"TV_HOST": os.environ.get("TV_HOST", "192.168.1.123")},
        "RunAtLoad": True,
        "KeepAlive": True,
        "ProcessType": "Background",
        "StandardOutPath": str(logs / "wallpaper-service.log"),
        "StandardErrorPath": str(logs / "wallpaper-service.err.log"),
    }
    path = plist_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as handle:
        plistlib.dump(agent, handle)
    errors = logs / "wallpaper-service.err.log"
    errors.write_text("")
    launchctl("bootout", f"{domain()}/{LABEL}")
    done = launchctl("bootstrap", domain(), str(path))
    if done.returncode != 0:
        print(done.stderr.strip() or done.stdout.strip())
        return 1
    print(f"installed {LABEL} on port {port}")
    print(f"token in {TOKEN_FILE}, logs in {LOG_DIR}/")
    if wait_for_health(port):
        print(f"serving on port {port}")
        return 0
    if BLOCKED in _read(errors):
        print(TCC_HELP.format(where=guarded_root(root) or str(root), guarded=", ".join(GUARDED)))
        return 1
    print(f"the agent did not come up; see {LOG_DIR}/wallpaper-service.err.log")
    return 1


def _read(path: Path) -> str:
    try:
        return path.read_text(errors="replace")
    except OSError:
        return ""


def wait_for_health(port: int, seconds: float = 8.0) -> bool:
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=2):
                return True
        except (urllib.error.URLError, OSError):
            time.sleep(0.5)
    return False


def uninstall(root: Path) -> int:
    launchctl("bootout", f"{domain()}/{LABEL}")
    path = plist_path()
    if path.is_file():
        path.unlink()
    print(f"removed {LABEL}")
    return 0


def status(root: Path, port: int) -> int:
    path = plist_path()
    print(f"agent     {path} {'present' if path.is_file() else 'absent'}")
    listed = launchctl("list", LABEL)
    if listed.returncode == 0:
        state = dict(line.strip().strip(";").split(" = ", 1)
                     for line in listed.stdout.splitlines() if " = " in line)
        print(f"launchd   pid {state.get('PID', '-')}, last exit {state.get('LastExitStatus', '-')}")
    else:
        print("launchd   not loaded")
    token = (root / TOKEN_FILE)
    print(f"token     {'set' if token.is_file() and token.read_text().strip() else 'missing'}")
    print(f"http      {probe(port)}")
    account = steam.Auth(root).account()
    print(f"steam     {'signed in as ' + account if account else 'signed out'}")
    if BLOCKED in _read(root / LOG_DIR / "wallpaper-service.err.log"):
        print(TCC_HELP.format(where=guarded_root(root) or str(root), guarded=", ".join(GUARDED)))
    return 0


def probe(port: int) -> str:
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/health", timeout=3) as response:
            return f"answering on {port} ({response.status})"
    except (urllib.error.URLError, OSError) as exc:
        return f"not answering on {port} ({exc})"
