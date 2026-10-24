#!/bin/sh
# Build launcher/build/friday-home.apk and run the policy tests.
set -e
. "$(dirname "$0")/env.sh"
cd "$ROOT/launcher"
sh build.sh > build.log 2>&1 || { tail -30 build.log; exit 1; }
rm -rf build/test-classes && mkdir -p build/test-classes
"$JAVA_HOME/bin/javac" -nowarn -d build/test-classes src/home/friday/tv/ArtworkPolicy.java \
    src/home/friday/tv/LayoutBackups.java tests/PolicyTest.java
"$JAVA_HOME/bin/java" -cp build/test-classes home.friday.tv.PolicyTest
shasum -a 256 build/friday-home.apk
