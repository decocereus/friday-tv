#!/bin/sh
# One-time setup on a new machine: submodule, Python venv for the TV tools, toolchain check.
set -e
. "$(dirname "$0")/env.sh"
git -C "$ROOT" submodule update --init
[ -x "$PY" ] || { python3 -m venv "$LOCAL/venv" && "$LOCAL/venv/bin/pip" install -q -r "$TOOLS/requirements.txt"; }
[ -e "$TOOLS/venv" ] || ln -s ../../../.local/venv "$TOOLS/venv"
[ -x "$JAVA_HOME/bin/javac" ] || echo "Missing JDK 17: set JAVA_HOME or unpack one into .local/toolchain/" >&2
[ -f "$ANDROID_HOME/platforms/android-34/android.jar" ] || echo "Missing Android SDK (build-tools, platform-tools, platforms;android-34): set ANDROID_HOME" >&2
[ -f "$KEYSTORE" ] || echo "Missing signing key $KEYSTORE: Friday only accepts launcher updates signed with the key it was installed with" >&2
[ -f "$TOOLS/adbkey/adbkey" ] || echo "No ADB key yet: the first tv.py call creates one and the TV asks to allow it" >&2
echo "Setup checked."
