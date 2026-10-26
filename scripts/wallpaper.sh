#!/bin/sh
# Wallpaper Engine wallpapers to Friday Home bundles. See wallpaper/docs/format.md.
set -e
. "$(dirname "$0")/env.sh"
PYTHONPATH="$ROOT/wallpaper" exec "$PY" -m converter.cli "$@"
