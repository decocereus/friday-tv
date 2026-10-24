#!/bin/sh
# Undo scripts/debloat.sh: every listed package goes back to its recorded state, and the Google TV
# launcher, screensaver and notification listener go back to their factory values.
set -e
. "$(dirname "$0")/env.sh"
(cd "$TOOLS/.." && TV_HOST="$TV_HOST" bash tools/restore.sh "$ROOT/debloat/friday-g10.txt" \
    com.google.android.apps.tv.launcherx)
tv "cmd appops set com.google.android.apps.tv.launcherx RUN_ANY_IN_BACKGROUND default; \
cmd appops set com.google.android.apps.tv.launcherx RUN_IN_BACKGROUND default; \
settings put secure enabled_notification_listeners \
com.google.android.apps.tv.launcherx/com.google.android.apps.tv.launcherx.coreservices.notificationlistener.TvNotificationListenerService; \
settings put secure screensaver_components com.google.android.apps.tv.dreamx/.service.Backdrop; \
appops write-settings"
