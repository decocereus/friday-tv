#!/bin/sh
# Install the built launcher on Friday. A settings checkpoint is saved first.
#   scripts/deploy.sh preview                 install and open it, leaving Home as it is
#   scripts/deploy.sh activate <checkpoint>   make the Home button open it
#   scripts/deploy.sh restore <checkpoint>    go back to the Home setup saved in <checkpoint>
set -e
. "$(dirname "$0")/env.sh"
"$ADB" -P "$ADB_PORT" connect "$TV_HOST:5555" >/dev/null
action="$1"; shift
if [ "$action" = preview ]; then set -- --apk "$ROOT/launcher/build/friday-home.apk"; else set -- --checkpoint "$1"; fi
python3 "$ROOT/scripts/deploy.py" "$action" --adb "$ADB" --serial "$TV_HOST:5555" --port "$ADB_PORT" \
    --backups "$LOCAL/backups" "$@"
