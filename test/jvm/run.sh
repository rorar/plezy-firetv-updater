#!/usr/bin/env bash
# Compile and run the pure-Java tests for DeviceKind — no device, no Android SDK.
#
#   test/jvm/run.sh
#
# DeviceKind.java is written without any android.* import precisely so this works: javac compiles
# it against the plain JDK, next to the test, and the result runs on the JVM. That is how the
# Fire TV / Android TV / touch branches get exercised even though only one stick is available.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(dirname "$(dirname "$HERE")")"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"

SRC="$REPO/app/src/dev/rorar/plezyupdater/DeviceKind.java"
OUT="$HERE/build"

rm -rf "$OUT"
mkdir -p "$OUT"

"$JAVAC" -encoding UTF-8 -d "$OUT" "$SRC" "$HERE/DeviceKindTest.java"
"$JAVA" -cp "$OUT" dev.rorar.plezyupdater.DeviceKindTest
