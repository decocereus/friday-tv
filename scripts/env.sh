# Shared paths for the scripts in this folder. Source it: . scripts/env.sh
# Machine-local things (toolchain, signing key, venv) live in .local/, which is never committed.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")/.." && pwd)"
LOCAL="$ROOT/.local"
export TV_HOST="${TV_HOST:-192.168.1.123}"
export JAVA_HOME="${JAVA_HOME:-$LOCAL/toolchain/jdk-17.0.21+9/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$LOCAL/toolchain/android-sdk}"
# The deployed launcher is signed with this key; updates must use the same one.
export KEYSTORE="${KEYSTORE:-$LOCAL/signing/debug.keystore}" KS_PASS="${KS_PASS:-android}"
ADB="$ANDROID_HOME/platform-tools/adb"
ADB_PORT=5041
PY="$LOCAL/venv/bin/python"
TOOLS="$ROOT/third_party/cleanyourtv/tools"
tv() { "$PY" -I "$TOOLS/tv.py" "$@"; }
