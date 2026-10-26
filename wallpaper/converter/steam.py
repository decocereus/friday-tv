"""Signing in to Steam from the TV, by showing a QR code the Steam app scans.

No Web API key and no password: Steam's IAuthenticationService hands out a refresh token once the
phone approves the challenge. The refresh token stays on the Mac in .local/steam-auth.json at
0600 and is never sent to the TV or written to the log; the TV only ever sees the challenge URL.
"""

from __future__ import annotations

import base64
import json
import struct
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

BEGIN_URL = "https://api.steampowered.com/IAuthenticationService/BeginAuthSessionViaQR/v1/"
POLL_URL = "https://api.steampowered.com/IAuthenticationService/PollAuthSessionStatus/v1/"
RENEW_URL = "https://api.steampowered.com/IAuthenticationService/GenerateAccessTokenForApp/v1/"
# Signed in as the Steam mobile app (EAuthTokenPlatformType 3). GenerateAccessTokenForApp answers
# AccessDenied for WebBrowser and SteamClient refresh tokens; only MobileApp ones can be renewed.
# The device details and headers are node-steam-session's MobileApp platform data.
PLATFORM_MOBILE_APP = 3
OS_ANDROID_UNKNOWN = -500
GAMING_DEVICE_PHONE = 528
MOBILE_HEADERS = {
    "User-Agent": "okhttp/4.9.2",
    "Cookie": "mobileClient=android; mobileClientVersion=777777 3.10.3",
}
ERESULT_OK, ERESULT_ACCESS_DENIED = 1, 15
DEVICE_NAME = "Friday TV"
AUTH_FILE = ".local/steam-auth.json"
# Give up on an unscanned challenge rather than polling Steam forever.
CHALLENGE_TTL = 300.0


class SteamError(Exception):
    pass


class SignedOut(SteamError):
    """Steam no longer honours the stored sign-in; the TV should offer the QR again."""


def _varint(n: int) -> bytes:
    out = bytearray()
    while True:
        byte, n = n & 0x7F, n >> 7
        out.append(byte | (0x80 if n else 0))
        if not n:
            return bytes(out)


def _field(number: int, value) -> bytes:
    """One protobuf field: ints as varints, str and bytes length-delimited."""
    if isinstance(value, int):
        return _varint(number << 3) + _varint(value & 0xFFFFFFFFFFFFFFFF)
    data = value.encode() if isinstance(value, str) else value
    return _varint(number << 3 | 2) + _varint(len(data)) + data


def _fixed64(number: int, value: int) -> bytes:
    return _varint(number << 3 | 1) + value.to_bytes(8, "little")


def _decode(data: bytes) -> dict[int, object]:
    """A flat protobuf message: field number to int, float (fixed32) or bytes. Last value wins."""
    fields: dict[int, object] = {}
    i = 0

    def varint() -> int:
        nonlocal i
        shift = result = 0
        while True:
            byte = data[i]
            i += 1
            result |= (byte & 0x7F) << shift
            shift += 7
            if not byte & 0x80:
                return result

    while i < len(data):
        key = varint()
        number, wire = key >> 3, key & 7
        if wire == 0:
            fields[number] = varint()
        elif wire == 1:
            fields[number] = int.from_bytes(data[i:i + 8], "little")
            i += 8
        elif wire == 2:
            size = varint()
            fields[number] = data[i:i + size]
            i += size
        elif wire == 5:
            fields[number] = struct.unpack("<f", data[i:i + 4])[0]
            i += 4
        else:
            raise SteamError(f"unexpected protobuf wire type {wire}")
    return fields


def _text(fields: dict, number: int) -> str:
    value = fields.get(number)
    return value.decode() if isinstance(value, bytes) else ""


def _call(url: str, message: bytes) -> dict[int, object]:
    """A service method as Steam's own clients call it: POST, protobuf in input_protobuf_encoded."""
    data = urllib.parse.urlencode({"input_protobuf_encoded": base64.b64encode(message).decode()})
    request = urllib.request.Request(url, data=data.encode(), headers=MOBILE_HEADERS)
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            eresult = int(response.headers.get("X-eresult") or ERESULT_OK)
            body = response.read()
    except (urllib.error.URLError, OSError) as exc:
        raise SteamError(f"steam unreachable: {exc}") from exc
    if eresult == ERESULT_ACCESS_DENIED:
        raise SignedOut(f"steam denied {url.rsplit('/', 3)[-3]}")
    if eresult != ERESULT_OK:
        raise SteamError(f"steam answered EResult {eresult}")
    try:
        return _decode(body)
    except IndexError as exc:
        raise SteamError("truncated response from steam") from exc


def _get(url: str, params: dict) -> dict:
    full = url + "?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(full, headers={"User-Agent": "FridayWallpaper/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            body = response.read()
    except urllib.error.HTTPError as exc:
        raise SteamError(f"steam refused the token ({exc.code})") from exc
    except (urllib.error.URLError, OSError) as exc:
        raise SteamError(f"steam unreachable: {exc}") from exc
    try:
        return json.loads(body).get("response") or {}
    except json.JSONDecodeError as exc:
        raise SteamError(f"malformed library response: {exc}") from exc


def jwt_claims(token: str) -> dict:
    """The middle of a Steam token, which carries the steamid in `sub` and the expiry in `exp`."""
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        return json.loads(base64.urlsafe_b64decode(payload))
    except (IndexError, ValueError, json.JSONDecodeError):
        return {}


class Auth:
    """The Mac's half of the sign-in: one pending challenge at a time, plus the stored token."""

    def __init__(self, root: Path):
        self.path = root / AUTH_FILE
        self._lock = threading.Lock()
        self._pending: dict | None = None
        self._access = ""
        self._access_until = 0.0

    # -- stored identity ------------------------------------------------------

    def stored(self) -> dict:
        try:
            return json.loads(self.path.read_text())
        except (OSError, json.JSONDecodeError):
            return {}

    def signed_in(self) -> bool:
        return bool(self.stored().get("refresh_token"))

    def account(self) -> str:
        return str(self.stored().get("account_name") or "")

    def steamid(self) -> str:
        return str(self.stored().get("steamid") or "")

    def sign_out(self) -> None:
        with self._lock:
            self._pending = None
            self._access = ""
            self._access_until = 0.0
        try:
            self.path.unlink()
        except OSError:
            pass

    def _store(self, refresh_token: str, account_name: str) -> None:
        claims = jwt_claims(refresh_token)
        blob = {
            "refresh_token": refresh_token,
            "account_name": account_name,
            "steamid": str(claims.get("sub") or ""),
        }
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(blob) + "\n")
        self.path.chmod(0o600)

    # -- the QR challenge -----------------------------------------------------

    def begin(self) -> dict:
        """Starts a challenge and returns the URL to draw. Replaces any challenge still pending."""
        device = (_field(1, DEVICE_NAME) + _field(2, PLATFORM_MOBILE_APP)
                  + _field(3, OS_ANDROID_UNKNOWN) + _field(4, GAMING_DEVICE_PHONE))
        response = _call(BEGIN_URL, _field(3, device))
        url = _text(response, 2)
        if not url:
            raise SteamError("steam did not return a challenge")
        with self._lock:
            self._pending = {
                "client_id": int(response.get(1) or 0),
                "request_id": bytes(response.get(3) or b""),
                "challenge_url": url,
                "interval": float(response.get(4) or 5),
                "started": time.monotonic(),
            }
        return {"challenge": url, "interval": self._pending["interval"]}

    def poll(self) -> dict:
        """One poll of the pending challenge. The TV calls this; it never sees a token."""
        with self._lock:
            pending = dict(self._pending) if self._pending else None
        if not pending:
            return {"state": "signed-out"}
        if time.monotonic() - pending["started"] > CHALLENGE_TTL:
            with self._lock:
                self._pending = None
            return {"state": "expired"}
        response = _call(POLL_URL, _field(1, pending["client_id"]) + _field(2, pending["request_id"]))
        if response.get(1):
            with self._lock:
                if self._pending:
                    self._pending["client_id"] = int(response[1])
        scanned = bool(response.get(5))
        refresh = _text(response, 3)
        if refresh:
            self._store(refresh, _text(response, 6))
            with self._lock:
                self._pending = None
            self._remember(_text(response, 4))
            return {"state": "signed-in", "account": self.account()}
        fresh = _text(response, 2)
        if fresh:
            with self._lock:
                if self._pending:
                    self._pending["challenge_url"] = fresh
            return {"state": "pending", "challenge": fresh, "scanned": scanned}
        return {"state": "pending", "challenge": pending["challenge_url"], "scanned": scanned}

    def challenge(self) -> str:
        with self._lock:
            return self._pending["challenge_url"] if self._pending else ""

    # -- using the token ------------------------------------------------------

    def access_token(self) -> str:
        """A web API access token, renewed from the refresh token a minute before it expires."""
        with self._lock:
            if self._access and time.monotonic() < self._access_until:
                return self._access
        stored = self.stored()
        kept = str(stored.get("access_token") or "")
        if kept and float(jwt_claims(kept).get("exp") or 0) > time.time() + 60:
            self._remember(kept)
            return kept
        refresh = str(stored.get("refresh_token") or "")
        if not refresh:
            raise SteamError("not signed in")
        try:
            response = _call(RENEW_URL, _field(1, refresh) + _fixed64(2, int(stored.get("steamid") or 0))
                             + _field(3, 1))
        except SignedOut:
            self.sign_out()
            raise
        token = _text(response, 1)
        if not token:
            raise SteamError("steam would not renew the access token")
        renewed = _text(response, 2)
        if renewed:
            self._store(renewed, self.account())
        self._remember(token)
        return token

    def _remember(self, token: str) -> None:
        if not token:
            return
        expires = float(jwt_claims(token).get("exp") or 0)
        life = max(60.0, expires - time.time() - 60.0) if expires else 600.0
        with self._lock:
            self._access = token
            self._access_until = time.monotonic() + life
        stored = self.stored()
        if stored:
            stored["access_token"] = token
            self.path.write_text(json.dumps(stored) + "\n")
            self.path.chmod(0o600)
