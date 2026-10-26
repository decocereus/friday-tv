#!/bin/sh
# Build launcher/build/friday-home.apk and run the JVM tests of both source roots.
set -e
. "$(dirname "$0")/env.sh"
cd "$ROOT/launcher"
sh build.sh > build.log 2>&1 || { tail -30 build.log; exit 1; }
rm -rf build/test-classes && mkdir -p build/test-classes
W="$ROOT/wallpaper/tv"
"$JAVA_HOME/bin/javac" -nowarn -d build/test-classes \
    src/home/friday/tv/ArtworkPolicy.java src/home/friday/tv/LayoutBackups.java tests/PolicyTest.java \
    "$W/src/home/friday/wallpaper/SceneMath.java" "$W/src/home/friday/wallpaper/Qr.java" \
    "$W"/tests/*.java
"$JAVA_HOME/bin/java" -cp build/test-classes home.friday.tv.PolicyTest
"$JAVA_HOME/bin/java" -cp build/test-classes home.friday.wallpaper.SceneMathTest
"$JAVA_HOME/bin/java" -cp build/test-classes home.friday.wallpaper.QrTest
shasum -a 256 build/friday-home.apk
