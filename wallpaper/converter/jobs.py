"""One install at a time: steamcmd, then the converter, then adb push.

Nothing here ever prompts. steamcmd runs with its stdin closed and a cached login; if Steam wants
a password or a Steam Guard code the job fails with "steam-login-needed" and the user sorts it out
on the Mac. Passwords are not read, stored or passed on.
"""

from __future__ import annotations

import os
import queue
import shutil
import subprocess
import threading
import time
from pathlib import Path

from . import convert as converter, device
from .pkg import FormatError

WORKSHOP_APP = "431960"
QUEUED, DOWNLOADING, CONVERTING, PUSHING, READY, FAILED = (
    "queued", "downloading", "converting", "pushing", "ready", "failed")
DOWNLOAD_TIMEOUT = 900.0
# steamcmd says one of these when the cached login is no longer good enough.
LOGIN_TROUBLE = ("FAILED", "Steam Guard", "Two-factor", "password", "Invalid Password",
                 "Login Failure", "Cached credentials not found")


class Jobs:
    """A queue of installs with a state per Workshop id, polled by the TV over /jobs."""

    def __init__(self, assets: Path, library: Path, steam: Path, on_change=None):
        self.assets = assets
        self.library = library
        self.steam = steam
        self.on_change = on_change
        self._lock = threading.Lock()
        self._state: dict[str, dict] = {}
        self._queue: "queue.Queue[str]" = queue.Queue()
        self._worker = threading.Thread(target=self._run, name="wallpaper-jobs", daemon=True)
        self._worker.start()

    # -- the service's side ---------------------------------------------------

    def submit(self, identifier: str) -> dict:
        with self._lock:
            current = self._state.get(identifier)
            if current and current["state"] not in (READY, FAILED):
                return dict(current)
            self._state[identifier] = {"state": QUEUED, "reason": "", "at": time.time()}
            out = dict(self._state[identifier])
        self._queue.put(identifier)
        return out

    def snapshot(self) -> dict[str, dict]:
        with self._lock:
            return {k: dict(v) for k, v in self._state.items()}

    def forget(self, identifier: str) -> None:
        with self._lock:
            self._state.pop(identifier, None)

    # -- the worker -----------------------------------------------------------

    def _set(self, identifier: str, state: str, reason: str = "") -> None:
        with self._lock:
            self._state[identifier] = {"state": state, "reason": reason, "at": time.time()}
        if self.on_change:
            try:
                self.on_change(identifier, state, reason)
            except Exception:  # a listener must never take the worker down
                pass

    def _run(self) -> None:
        while True:
            identifier = self._queue.get()
            try:
                self._install(identifier)
            except Exception as exc:  # the worker outlives any one bad item
                self._set(identifier, FAILED, f"error: {exc}")
            finally:
                self._queue.task_done()

    def _install(self, identifier: str) -> None:
        if not identifier.isdigit():
            self._set(identifier, FAILED, "bad-id")
            return

        self._set(identifier, DOWNLOADING)
        folder = self.steam / "steamapps" / "workshop" / "content" / WORKSHOP_APP / identifier
        problem = download(identifier, self.steam)
        if problem:
            self._set(identifier, FAILED, problem)
            return
        if not (folder / "project.json").is_file():
            self._set(identifier, FAILED, "download-incomplete")
            return

        self._set(identifier, CONVERTING)
        try:
            done = converter.convert(folder, self.assets, self.library)
        except (FormatError, OSError, ValueError) as exc:
            self._set(identifier, FAILED, f"convert-failed: {exc}")
            return
        bundle = Path(done["path"])

        self._set(identifier, PUSHING)
        try:
            size = device.tree_bytes(bundle)
            if device.free_bytes() - size < device.FREE_FLOOR:
                self._set(identifier, FAILED, "tv-storage-full")
                return
            device.push_bundle(bundle, done["id"])
        except (device.DeviceError, subprocess.SubprocessError, OSError) as exc:
            self._set(identifier, FAILED, f"push-failed: {exc}")
            return
        self._set(identifier, READY)

    # -- removal --------------------------------------------------------------

    def remove(self, identifier: str) -> None:
        """Deletes the bundle on the Mac and on the TV, and steamcmd's download of it.

        The Steam subscription is left alone. steamcmd fetches the item again if it is reinstalled.
        """
        if not identifier.isdigit() and not identifier.startswith("local-"):
            raise ValueError("bad id")
        shutil.rmtree(self.library / identifier, ignore_errors=True)
        if identifier.isdigit():
            shutil.rmtree(self.steam / "steamapps" / "workshop" / "content" / WORKSHOP_APP / identifier,
                          ignore_errors=True)
        device.remove_bundle(identifier)
        self.forget(identifier)


def steam_user() -> str:
    user = os.environ.get("STEAM_USER")
    if user:
        return user
    path = device.root() / ".local" / "steam-user"
    if path.is_file():
        return path.read_text().strip()
    raise FileNotFoundError("no .local/steam-user")


def download(identifier: str, steam: Path) -> str:
    """Runs steamcmd for one item. Returns "" on success, otherwise a short failure reason."""
    try:
        user = steam_user()
    except FileNotFoundError:
        return "steam-login-needed"
    steam.mkdir(parents=True, exist_ok=True)
    command = ["steamcmd", "+force_install_dir", str(steam), "+login", user,
               "+workshop_download_item", WORKSHOP_APP, identifier, "+quit"]
    try:
        done = subprocess.run(command, stdin=subprocess.DEVNULL, capture_output=True,
                              text=True, timeout=DOWNLOAD_TIMEOUT)
    except FileNotFoundError:
        return "steamcmd-missing"
    except subprocess.TimeoutExpired:
        return "download-timeout"
    output = (done.stdout or "") + (done.stderr or "")
    if "Success. Downloaded item" in output:
        return ""
    if any(marker in output for marker in LOGIN_TROUBLE):
        return "steam-login-needed"
    return "download-failed"
