"""Reading Steam Workshop without a login: the browse pages are HTML, the details are public.

Browsing needs no key. The user's own subscriptions are private: they come from the same
myworkshopfiles page the Steam website shows, signed in with the QR sign-in's access token as the
steamLoginSecure cookie. GetUserFiles answers 401 to access tokens, so it is not used.
"""

from __future__ import annotations

import json
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

APP = "431960"
BROWSE_URL = "https://steamcommunity.com/workshop/browse/"
DETAILS_URL = "https://api.steampowered.com/ISteamRemoteStorage/GetPublishedFileDetails/v1/"
SUBSCRIPTIONS_URL = "https://steamcommunity.com/profiles/{steamid}/myworkshopfiles/"
# Steam serves the browse pages as a bot-blocked page to anything that does not look like a browser.
UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")

SORTS = {"trend": "trend", "toprated": "toprated", "mostrecent": "mostrecent"}
KINDS = {"scene": "Scene", "video": "Video"}
# A Friday bundle is a scene or a video; the rest are Windows-only or not wallpapers at all.
EXCLUDED_TAGS = ("Web", "Application", "Preset")

ID_IN_PAGE = re.compile(r"filedetails/\?id=(\d+)")
PAGE_TTL = 600.0
DETAIL_TTL = 600.0


class WorkshopError(Exception):
    pass


class Cache:
    """Smallest thing that holds a value for a while; the browse pages change slowly."""

    def __init__(self, ttl: float):
        self.ttl = ttl
        self._lock = threading.Lock()
        self._items: dict[str, tuple[float, object]] = {}

    def get(self, key: str):
        with self._lock:
            hit = self._items.get(key)
        if hit and time.monotonic() - hit[0] < self.ttl:
            return hit[1]
        return None

    def put(self, key: str, value):
        with self._lock:
            self._items[key] = (time.monotonic(), value)
            if len(self._items) > 256:
                oldest = sorted(self._items.items(), key=lambda kv: kv[1][0])[:64]
                for k, _ in oldest:
                    del self._items[k]


_pages = Cache(PAGE_TTL)
_details = Cache(DETAIL_TTL)


def _fetch(url: str, data: bytes | None = None, timeout: float = 20.0, cookie: str = "") -> bytes:
    headers = {"User-Agent": UA, **({"Cookie": cookie} if cookie else {})}
    request = urllib.request.Request(url, data=data, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.read()
    except (urllib.error.URLError, OSError) as exc:
        raise WorkshopError(f"steam unreachable: {exc}") from exc


def kind_of(tags: list[str]) -> str | None:
    """"scene", "video", or None for the types the converter cannot turn into a bundle."""
    if any(t in tags for t in EXCLUDED_TAGS):
        return None
    for key, tag in KINDS.items():
        if tag in tags:
            return key
    return None


def browse(sort: str = "trend", kind: str = "all", query: str = "", page: int = 1) -> list[str]:
    """The ids on one Workshop browse page, in the order Steam ranked them."""
    sort = SORTS.get(sort, "trend")
    page = max(1, min(100, page))
    params = [("appid", APP), ("browsesort", sort), ("section", "readytouseitems"), ("p", str(page))]
    if kind in KINDS:
        params.append(("requiredtags[]", KINDS[kind]))
    if query:
        params.append(("searchtext", query))
    url = BROWSE_URL + "?" + urllib.parse.urlencode(params)
    cached = _pages.get(url)
    if cached is not None:
        return cached
    html = _fetch(url).decode("utf-8", "replace")
    ids = list(dict.fromkeys(ID_IN_PAGE.findall(html)))
    _pages.put(url, ids)
    return ids


def details(ids: list[str]) -> dict[str, dict]:
    """Title, preview, tags and size for each id, from the public details endpoint."""
    out: dict[str, dict] = {}
    missing = []
    for identifier in ids:
        hit = _details.get(identifier)
        if hit is None:
            missing.append(identifier)
        else:
            out[identifier] = hit
    for chunk in (missing[i:i + 50] for i in range(0, len(missing), 50)):
        form = {"itemcount": len(chunk)}
        for i, identifier in enumerate(chunk):
            form[f"publishedfileids[{i}]"] = identifier
        raw = _fetch(DETAILS_URL, urllib.parse.urlencode(form).encode())
        try:
            body = json.loads(raw)["response"].get("publishedfiledetails", [])
        except (json.JSONDecodeError, KeyError, TypeError) as exc:
            raise WorkshopError(f"malformed details response: {exc}") from exc
        for entry in body:
            item = _item(entry)
            if item:
                _details.put(item["id"], item)
                out[item["id"]] = item
    return out


def _item(entry: dict) -> dict | None:
    identifier = str(entry.get("publishedfileid") or "")
    if not identifier.isdigit() or entry.get("result") != 1 or entry.get("banned"):
        return None
    tags = [str(t.get("tag", "")) for t in entry.get("tags") or []]
    kind = kind_of(tags)
    if kind is None:
        return None
    preview = str(entry.get("preview_url") or "")
    return {
        "id": identifier,
        "title": str(entry.get("title") or identifier),
        "preview": preview if preview.startswith("https://") else "",
        "type": kind,
        "sizeBytes": int(entry.get("file_size") or 0),
        "subscriptions": int(entry.get("subscriptions") or 0),
    }


def page(sort: str = "trend", kind: str = "all", query: str = "", page_no: int = 1) -> list[dict]:
    """One page of browse results, already filtered to what the converter can handle."""
    ids = browse(sort, kind, query, page_no)
    found = details(ids)
    items = [found[i] for i in ids if i in found]
    if kind in KINDS:
        items = [i for i in items if i["type"] == kind]
    return items


# -- the user's own library ---------------------------------------------------

# GetUserFiles has been renamed over the years; the first value that returns items wins, and the
# one that worked is logged so the next reader does not have to guess again.
SUBSCRIPTIONS_PER_PAGE = 30
SUBSCRIPTIONS_MAX_PAGES = 20
SUBSCRIPTION_TOTAL = re.compile(r"Showing [\d,]+-[\d,]+ of ([\d,]+) entries")


def subscriptions(access_token: str, steamid: str) -> list[str]:
    """The ids on the user's Wallpaper Engine subscriptions pages, in Steam's order."""
    cookie = "steamLoginSecure=" + urllib.parse.quote(f"{steamid}||{access_token}")
    ids: list[str] = []
    for page_no in range(1, SUBSCRIPTIONS_MAX_PAGES + 1):
        query = urllib.parse.urlencode({"appid": APP, "browsefilter": "mysubscriptions",
                                        "p": page_no, "numperpage": SUBSCRIPTIONS_PER_PAGE})
        html = _fetch(SUBSCRIPTIONS_URL.format(steamid=steamid) + "?" + query, cookie=cookie)
        text = html.decode("utf-8", "replace")
        if f'g_steamID = "{steamid}"' not in text:
            raise WorkshopError("steam did not accept the sign-in")
        fresh = [i for i in dict.fromkeys(ID_IN_PAGE.findall(text)) if i not in ids]
        ids += fresh
        total = SUBSCRIPTION_TOTAL.search(text)
        if not fresh or (total and len(ids) >= int(total.group(1).replace(",", ""))):
            break
    return ids


def library(access_token: str, steamid: str) -> list[dict]:
    """The user's subscribed Wallpaper Engine items, in Steam's own order."""
    cache_key = f"library:{steamid}"
    cached = _pages.get(cache_key)
    if cached is not None:
        return cached
    ids = subscriptions(access_token, steamid)
    found = details(ids)
    items = [found[i] for i in ids if i in found]
    _pages.put(cache_key, items)
    return items
