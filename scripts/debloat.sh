#!/bin/sh
# Apply Friday's cleanup. Safe to run again: the first recorded package states are kept.
#   1. screensaver -> Friday Home's photo screensaver, Home pinned to Friday Home
#   2. debloat/friday-g10.txt disabled through CleanYourTV's disable.sh (records original states)
#   3. Google TV's launcher, which must stay enabled, may not run in the background
set -e
. "$(dirname "$0")/env.sh"
tv "settings put secure screensaver_components home.friday.tv/.HavenDream"
tv "cmd package set-home-activity --user 0 home.friday.tv/.HomeActivity"
(cd "$TOOLS/.." && TV_HOST="$TV_HOST" bash tools/disable.sh "$ROOT/debloat/friday-g10.txt")
tv "cmd appops set com.google.android.apps.tv.launcherx RUN_ANY_IN_BACKGROUND ignore; \
cmd appops set com.google.android.apps.tv.launcherx RUN_IN_BACKGROUND ignore; \
settings put secure enabled_notification_listeners ''; appops write-settings"
