# friday-tv

Setup for "Friday", a TCL C6KS Google TV (board G10, Android 12): a private Apple TV-style home
screen, and a reversible cleanup of TCL and Google ads, tracking and background services. The
firmware is unchanged, so Play Store, streaming DRM, AirPlay, Chromecast, voice and every TCL
picture, sound and game setting keep working.

Nothing here needs root or an unlocked bootloader. Packages are disabled, never uninstalled, and
every change has an undo.

## What's in it

| Path | What it is |
|---|---|
| `launcher/` | **Friday Home**, a fork of [Haven](https://github.com/joydeepsjce/haven-tv). Continue watching from every installed app (Watch Next), favourites dock, inputs, full app grid, photo screensaver. No analytics, weather or trending feeds. |
| `debloat/friday-g10.txt` | The packages the cleanup disables, with the reason for each and what is kept on purpose. |
| `scripts/` | Build, deploy, cleanup, undo and firmware-update scripts (below). |
| `third_party/cleanyourtv` | [CleanYourTV](https://github.com/Gathin23/cleanyourtv), pinned. Its `disable.sh`, `restore.sh` and `tv.py` do the work on the TV. |

## Friday Home

- **Continue watching** reads the TV's local Watch Next feed, so there's no account access. It
  shows a NEXT episode, or a CONTINUE item that is started and under 95% watched, from an app you
  can launch, touched in the last 60 days. Other apps' preview channels are recommendations and
  are skipped.
- **Artwork** is only downloaded from the publishing app's own image hosts
  (`launcher/src/home/friday/tv/ArtworkPolicy.java`). A card from any other app shows its title.
- **Home button**: an accessibility helper (`HomeKeyService`) opens Friday Home. Google TV's launcher
  stays installed because TCL's firmware insists on it (see Known limits).

## Cleanup

`scripts/debloat.sh` disables 25 packages: TCL and Samba TV tracking (`com.tcl.ttvs`,
`tv.samba.ssm.ui`, `com.tcl.logkit`), TCL promos and FAST channels, the TCL "cleaner", Alexa, the
retail demo, Google feedback and partner installers, Play Games, the Google TV store app and Google's
ambient screensaver. It also stops Google TV's launcher from running in the background.

Kept on purpose: everything behind picture and sound modes, Dolby, game mode, inputs and Live TV;
AirPlay, Chromecast, Miracast, the phone remote, Google Assistant, Play Store and Play services, and
every streaming app.

## Use

```sh
scripts/setup.sh              # once per machine: submodule, venv, toolchain check
scripts/build.sh              # build launcher/build/friday-home.apk and run the tests
scripts/deploy.sh preview     # install it (saves a settings checkpoint first)
scripts/debloat.sh            # apply the cleanup (repeatable)
scripts/restore-debloat.sh    # undo the cleanup
scripts/check-tcl-update.sh   # turn TCL's updater on, check for firmware, turn it off again
```

`scripts/env.sh` holds the paths. Set `TV_HOST` if the TV's address changes. The toolchain (JDK 17,
Android SDK), the launcher signing key, backups and screenshots live in `.local/`, which is never
committed. Updates to the installed launcher must be signed with the same key.

## Known limits

- **Google TV's launcher can't be disabled on this firmware.** TCL's `system_server` hard-codes it
  as the boot Home package. With it disabled, Google's setup app (`setupwraith`) takes Home and
  loops on a black screen. setupwraith can't be disabled either: something in early boot turns it
  back on, and Android 12 doesn't let the shell disable a single component. So the launcher stays,
  restricted.
- **TCL firmware updates** come through `com.tcl.ttvs`, which also carries Samba TV tracking. It
  stays disabled; use `scripts/check-tcl-update.sh` to update. Play Store and Google Play system
  updates are unaffected.
- **Google Play services** still talk to Google. Removing them would break the Play Store and many
  apps' DRM.

## Credits

[Haven](https://github.com/joydeepsjce/haven-tv) (launcher base, MIT),
[CleanYourTV](https://github.com/Gathin23/cleanyourtv) (TV tools and TCL package research, MIT),
[unbloat-android-tv](https://github.com/xMazaki/unbloat-android-tv) (ads and telemetry list),
[NuvioTV](https://github.com/NuvioMedia/NuvioTV) (Watch Next integration).
Wallpaper credits are in `launcher/NOTICE.md`.
