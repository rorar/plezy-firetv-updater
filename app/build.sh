#!/usr/bin/env bash
# Hand-rolled APK build for the updater (no Gradle, no AGP, no SDK manager).
#
#   BT=/path/to/build-tools/android-15 \
#   AJ=/path/to/platforms/android-35/android.jar \
#   ./build.sh
#
# Needs: aapt2, d8, zipalign, apksigner from build-tools; javac/keytool from JDK 17 or 21.
#
# The signing key lives in app/debug.keystore and is deliberately NOT in git. Keep it:
# an update can only be installed over the current app if it is signed with the same key.
# Override with KEYSTORE / KEY_ALIAS / KEY_PASS if you use a real release key.
set -euo pipefail

: "${BT:?set BT to the unpacked build-tools dir (contains aapt2, d8, zipalign, apksigner)}"
: "${AJ:?set AJ to the platform android.jar}"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
JAVAC="$JAVA_HOME/bin/javac"
JAR="$JAVA_HOME/bin/jar"
KEYTOOL="$JAVA_HOME/bin/keytool"

PKG="dev.rorar.plezyupdater"
MIN_SDK=25
TARGET_SDK=25

cd "$(dirname "$0")"

KEYSTORE="${KEYSTORE:-debug.keystore}"
KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"
KEY_PASS="${KEY_PASS:-android}"

rm -rf build
mkdir -p build/gen build/classes

echo "== resources"
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$AJ" \
  --manifest AndroidManifest.xml \
  -R build/res.zip \
  --java build/gen \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --no-version-vectors

echo "== java"
find src build/gen -name '*.java' | sort > build/sources.txt
"$JAVAC" -source 8 -target 8 -encoding UTF-8 -Xlint:-options \
  -bootclasspath "$AJ" -d build/classes @build/sources.txt

echo "== dex"
"$JAR" cf build/classes.jar -C build/classes .
"$BT/d8" --lib "$AJ" --min-api "$MIN_SDK" --output build build/classes.jar
( cd build && zip -q -u unsigned.apk classes.dex )

echo "== align + sign"
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

if [ ! -f "$KEYSTORE" ]; then
  echo "   generating $KEYSTORE"
  "$KEYTOOL" -genkeypair -keystore "$KEYSTORE" -storepass "$KEY_PASS" \
    -keypass "$KEY_PASS" -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi

"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass "pass:$KEY_PASS" \
  --key-pass "pass:$KEY_PASS" --out build/plezy-updater.apk build/aligned.apk

echo "== verify"
"$BT/apksigner" verify --print-certs build/plezy-updater.apk | head -3
"$BT/aapt2" dump badging build/plezy-updater.apk | head -4
ls -lh build/plezy-updater.apk
