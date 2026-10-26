# Wallpaper

Wallpaper Engine wallpapers on an Android TV. The Mac downloads a Workshop item and converts it
into a small bundle; the TV plays that bundle and never learns anything about Wallpaper Engine.

```
wallpaper/
  converter/   Python, runs on the Mac: convert, push, and the LAN service the TV talks to
  tv/src/      Java, home.friday.wallpaper: the library, the two backdrops, the picker, the browser
  tv/tests/    plain JVM tests, no device and no Android
  docs/format.md   the bundle format, which is the contract between the two halves
```

Everything here is meant to lift out into its own repository. The only thing it knows about Friday
Home is the `WallpaperHost` interface.

## The boundary with Friday Home

`WallpaperHost` (in `tv/src/home/friday/wallpaper/`) is the whole seam. Friday Home implements it
and gets back two overlay views to add to its root:

```java
picker = new WallpaperPicker(this, wallpapers, bg, img);
for (View v : picker.views()) root.addView(v, match());
```

The host answers five questions and does four jobs: which wallpaper is selected, select this one,
rescan the library, show a toast, tick the focus sound; and, for looks only, the focus animation,
the tile frame, a `TextView` in the app's font, and the accent colour. Back, Menu and the key
handling stay with the host, which forwards them to `picker.close()`, `picker.onKey()` and
`picker.isOpen()`.

Friday Home also uses `WallpaperLibrary` and `Backdrop` directly, to show the chosen wallpaper
behind the home screen and in the screensaver. Those are the only other public classes.

The module fetches Workshop preview images itself, through `Previews`, which allows https only, on
port 443, with no user-info, from a fixed list of Steam image hosts. The launcher's `ArtworkPolicy`
does the same for app artwork but keys its allowlist on the publishing package, which a Workshop
item does not have, so the two lists stay separate on purpose.

## Using it from the Mac

```sh
scripts/wallpaper.sh add 1275921440        # download a Workshop item and convert it
scripts/wallpaper.sh convert ~/some/folder # convert a folder you already have
scripts/wallpaper.sh list                  # what is converted here
scripts/wallpaper.sh push --set 1275921440 # copy to the TV and show it
scripts/wallpaper.sh remove 1275921440     # delete it here and on the TV
scripts/wallpaper.sh report                # how much of each bundle survived conversion
scripts/wallpaper.sh preview 1275921440 --out /tmp/f.png   # one frame, reference renderer
```

Bundles live in `.local/wallpapers/<id>/` on the Mac and in
`/sdcard/Android/data/home.friday.tv/files/wallpapers/<id>/` on the TV. Downloads need `steamcmd`
on `PATH` and a Steam account name in `.local/steam-user`, logged in once by hand
(`steamcmd +login <name>`) so the credentials are cached. Nothing here ever asks for a password.

## The service

So that the TV can browse Workshop on its own, the Mac runs a small HTTP service on the LAN:

```sh
scripts/wallpaper.sh serve                  # run it in this terminal
scripts/wallpaper.sh service install        # run it whenever the Mac is logged in
scripts/wallpaper.sh service status
scripts/wallpaper.sh service uninstall
```

| Route | What it does |
|---|---|
| `GET /health` | liveness, the only route that needs no token |
| `GET /workshop?sort=&type=&q=&page=` | one page of Workshop items: trend, toprated, mostrecent |
| `GET /library` | the signed-in account's own subscribed and favourited items |
| `GET /steam` | signed in, or a QR challenge to show |
| `POST /steam/signin`, `POST /steam/signout` | start a QR sign-in, or forget the account |
| `POST /install {id}` | queue a download, conversion and push |
| `GET /jobs` | every job's state: queued, downloading, converting, pushing, ready, failed |
| `POST /remove {id}` | delete the bundle here and on the TV |

Jobs run one at a time. A job fails with `steam-login-needed` if steamcmd's cached login has gone
stale, `tv-storage-full` if pushing would leave the TV under 1 GB free, and `steamcmd-missing` if
there is no steamcmd. Browsing needs no Steam account at all: the browse pages are HTML and the
item details come from the public `GetPublishedFileDetails` endpoint.

`service install` writes a launchd agent at `~/Library/LaunchAgents/home.friday.wallpaper.plist`,
logs to `.local/logs/`, generates `.local/service-token` at 0600 if there is none, and then checks
`/health` before saying it worked.

> macOS privacy settings block launchd agents from reading `~/Documents`, `~/Desktop` and
> `~/Downloads`. With the repository in one of those, the agent starts and immediately fails; the
> installer detects this and prints the fix (give `/bin/sh` Full Disk Access, or move the
> repository). Until then, run `scripts/wallpaper.sh serve` in a terminal instead.

### How the TV finds it

On start, and after every push, the service writes `{url, token}` to
`/sdcard/Android/data/home.friday.tv/files/server.json` over adb, to a temporary name and then
`mv`, so the TV never reads half a file. That is the only discovery mechanism; mDNS would add a
second one without removing the dependency on adb, which pushing bundles needs anyway.

Every request but `/health` carries the token in `X-Friday-Token`, compared with
`hmac.compare_digest`. Ids must be digits and are never passed through a shell. When `server.json`
is missing, or the Mac does not answer, the TV says so and keeps playing the wallpapers it already
has.

## Signing in to Steam

The "My Steam library" tab shows the account's own Workshop subscriptions, which Steam does not
make public. There is no Web API key: the TV shows a QR code, and the Steam app on a phone approves
it.

1. `POST IAuthenticationService/BeginAuthSessionViaQR/v1/` as the Steam mobile app
   (`device_details.platform_type=3`, MobileApp) returns a `challenge_url` such as `https://s.team/q/1/8630…`.
2. The TV draws that URL as a QR with `Qr.java`, a byte-mode encoder for versions 1 to 6 at ECC
   level M. No libraries, and the matrices are tested against python-qrcode's in `tv/tests`.
3. While the panel is up the TV polls `/steam` every two seconds, and the Mac polls
   `PollAuthSessionStatus/v1/`. Steam hands back a refresh token once the phone approves.
4. The refresh token is written to `.local/steam-auth.json` at 0600. It is never sent to the TV,
   never logged, and never leaves the Mac. The first access token comes with the approval;
   later ones are minted with `GenerateAccessTokenForApp/v1/` a minute before the last expires.
   Steam answers AccessDenied to that call for SteamClient and WebBrowser tokens, which is why the
   sign-in is a MobileApp one. Like Steam's own clients, these calls are POSTs carrying a protobuf in
   `input_protobuf_encoded` (fields from SteamDatabase's `steammessages_auth.steamclient.proto`). If Steam refuses a renewal, the Mac forgets the sign-in and the TV
   shows the QR again.
5. The subscriptions come from `steamcommunity.com/profiles/<steamid>/myworkshopfiles/?appid=431960&browsefilter=mysubscriptions`,
   the page the Steam website shows, with `steamLoginSecure=<steamid>||<access token>` as the cookie.
   `IPublishedFileService/GetUserFiles` answers 401 to access tokens, so it is not used.

Menu on the library tab signs out, which deletes `.local/steam-auth.json`. Removing a wallpaper
from the TV never touches the Steam subscription, and the confirmation says so.

## Tests

`scripts/build.sh` compiles both source roots into the one APK and runs the JVM tests:

```
52 artwork, Watch Next, progress and backup checks passed
62 scene matrix, orientation, downscale, drift and timing checks passed
852 QR module, mask, size and overflow checks passed
```

The converter's tests are pytest:

```sh
.local/venv/bin/python -m pytest wallpaper/converter/tests -q
```
