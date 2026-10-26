"""The LAN service the TV browses Workshop through.

It binds to the LAN, so every request carries the token that `service install` generated and that
the TV reads out of server.json. Ids are digits; nothing user-typed ever reaches a shell.
"""

from __future__ import annotations

import hmac
import json
import os
import socket
import sys
import threading
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from . import device, steam, workshop
from .jobs import Jobs

DEFAULT_PORT = 8787
TOKEN_HEADER = "X-Friday-Token"
MAX_BODY = 4096
SIGNED_OUT = "steam-signed-out"


def local_address(peer: str) -> str:
    """The Mac's address on the network the TV is on, not whatever localhost resolves to."""
    probe = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        probe.connect((peer, 9))
        return probe.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        probe.close()


def read_secret(path: Path) -> str:
    try:
        return path.read_text().strip()
    except OSError:
        return ""


class Service:
    """The state one `wallpaper.sh serve` owns: the job queue, the token and the Steam key."""

    def __init__(self, assets: Path, library: Path, steam_dir: Path, root: Path, port: int):
        self.library_dir = library
        self.root = root
        self.port = port
        self.token = read_secret(root / ".local" / "service-token")
        self.auth = steam.Auth(root)
        self.jobs = Jobs(assets, library, steam_dir, on_change=self._changed)

    def _changed(self, identifier: str, state: str, reason: str) -> None:
        log(f"job {identifier}: {state}{(' ' + reason) if reason else ''}")
        if state == "ready":
            self.announce()

    def url(self) -> str:
        host = local_address(_tv_host())
        return f"http://{host}:{self.port}"

    def steam_state(self) -> dict:
        return _steam_state(self)

    def steam_begin(self) -> dict:
        begun = self.auth.begin()
        log("steam: new QR challenge")
        return {"state": "pending", **begun}

    def announce(self) -> None:
        """Puts {url, token} where the TV looks. Best effort: the TV may simply be off."""
        try:
            device.publish_server(self.url(), self.token)
            log(f"announced {self.url()}")
        except Exception as exc:
            log(f"could not announce to the TV: {exc}")

    # -- what the endpoints return -------------------------------------------

    def decorate(self, items: list[dict]) -> list[dict]:
        states = self.jobs.snapshot()
        here = {p.parent.name for p in self.library_dir.glob("*/wallpaper.json")}
        for item in items:
            job = states.get(item["id"])
            item["state"] = job["state"] if job else ("ready" if item["id"] in here else "")
            item["reason"] = job["reason"] if job else ""
        return items


def _steam_state(service) -> dict:
    """What the TV draws on the My Steam library tab: a QR to scan, or the account that is on."""
    if service.auth.signed_in():
        return {"state": "signed-in", "account": service.auth.account()}
    if not service.auth.challenge():
        return {"state": "signed-out"}
    try:
        return service.auth.poll()
    except steam.SteamError as exc:
        return {"state": "error", "error": str(exc)}


def _tv_host() -> str:
    return os.environ.get("TV_HOST", "192.168.1.123")


def log(message: str) -> None:
    print(message, flush=True)


class Handler(BaseHTTPRequestHandler):
    service: Service = None  # set on the server class before serving
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        log(f"{self.address_string()} {fmt % args}")

    # -- plumbing -------------------------------------------------------------

    def reply(self, code: int, payload) -> None:
        body = json.dumps(payload).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def authorised(self, query: dict) -> bool:
        want = self.service.token
        if not want:
            return False
        got = self.headers.get(TOKEN_HEADER) or (query.get("token") or [""])[0]
        return hmac.compare_digest(got, want)

    def body(self) -> dict:
        try:
            length = int(self.headers.get("Content-Length") or 0)
        except ValueError:
            return {}
        if length <= 0 or length > MAX_BODY:
            return {}
        try:
            return json.loads(self.rfile.read(length) or b"{}")
        except (json.JSONDecodeError, UnicodeDecodeError):
            return {}

    @staticmethod
    def wanted_id(payload: dict) -> str:
        identifier = str(payload.get("id") or "")
        return identifier if identifier.isdigit() and len(identifier) <= 20 else ""

    # -- routes ---------------------------------------------------------------

    def do_GET(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        if parsed.path == "/health":
            self.reply(200, {"ok": True})
            return
        if not self.authorised(query):
            self.reply(403, {"error": "forbidden"})
            return
        try:
            if parsed.path == "/workshop":
                items = workshop.page(
                    sort=(query.get("sort") or ["trend"])[0],
                    kind=(query.get("type") or ["all"])[0],
                    query=(query.get("q") or [""])[0][:100],
                    page_no=int((query.get("page") or ["1"])[0] or 1),
                )
                self.reply(200, {"items": self.service.decorate(items)})
            elif parsed.path == "/library":
                auth = self.service.auth
                try:
                    token = auth.access_token() if auth.signed_in() else ""
                except steam.SignedOut:
                    token = ""
                if not token or not auth.steamid():
                    self.reply(200, {"items": [], "error": SIGNED_OUT})
                    return
                items = workshop.library(token, auth.steamid())
                self.reply(200, {"items": self.service.decorate(items)})
            elif parsed.path == "/steam":
                self.reply(200, self.service.steam_state())
            elif parsed.path == "/jobs":
                self.reply(200, {"jobs": self.service.jobs.snapshot()})
            else:
                self.reply(404, {"error": "no such endpoint"})
        except (workshop.WorkshopError, steam.SteamError) as exc:
            self.reply(502, {"error": str(exc)})
        except (ValueError, OSError) as exc:
            self.reply(500, {"error": str(exc)})
        except Exception:
            traceback.print_exc()
            self.reply(500, {"error": "internal"})

    def do_POST(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        if not self.authorised(query):
            self.body()
            self.reply(403, {"error": "forbidden"})
            return
        payload = self.body()
        try:
            if parsed.path == "/steam/signin":
                self.reply(200, self.service.steam_begin())
                return
            if parsed.path == "/steam/signout":
                self.service.auth.sign_out()
                self.reply(200, {"state": "signed-out"})
                return
        except steam.SteamError as exc:
            self.reply(502, {"state": "error", "error": str(exc)})
            return
        identifier = self.wanted_id(payload)
        if not identifier:
            self.reply(400, {"error": "bad id"})
            return
        try:
            if parsed.path == "/install":
                self.reply(200, {"id": identifier, **self.service.jobs.submit(identifier)})
            elif parsed.path == "/remove":
                self.service.jobs.remove(identifier)
                self.reply(200, {"id": identifier, "removed": True})
            else:
                self.reply(404, {"error": "no such endpoint"})
        except Exception:
            traceback.print_exc()
            self.reply(500, {"error": "internal"})


def run(assets: Path, library: Path, steam_dir: Path, root: Path, port: int = DEFAULT_PORT) -> int:
    service = Service(assets, library, steam_dir, root, port)
    if not service.token:
        print("no token: run scripts/wallpaper.sh service install first", file=sys.stderr)
        return 1
    Handler.service = service
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    server.daemon_threads = True
    log(f"serving {service.url()}")
    threading.Thread(target=service.announce, daemon=True).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0
