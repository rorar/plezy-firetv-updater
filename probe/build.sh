#!/usr/bin/env bash
# Hand-rolled APK build for the probe (no Gradle, no AGP, no SDK manager).
#
#   BT=/path/to/build-tools/android-15 \
#   AJ=/path/to/platforms/android-35/android.jar \
#   ./build.sh
#
# Needs: aapt2, d8, zipalign, apksigner from build-tools; javac/keytool from JDK 17 or 21.
set -euo pipefail

: "${BT:?set BT to the unpacked build-tools dir (contains aapt2, d8, zipalign, apksigner)}"
: "${AJ:?set AJ to the platform android.jar}"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
JAVAC="$JAVA_HOME/bin/javac"
KEYTOOL="$JAVA_HOME/bin/keytool"

cd "$(dirname "$0")"
rm -rf classes build unsigned.apk aligned.apk toasttest.apk
mkdir -p build

"$BT/aapt2" link -o build/unsigned.apk -I "$AJ" \
  --manifest AndroidManifest.xml \
  --min-sdk-version 21 --target-sdk-version 25

"$JAVAC" -source 8 -target 8 -bootclasspath "$AJ" \
  -d build/classes src/dev/local/toasttest/*.java

"$BT/d8" --lib "$AJ" --min-api 21 --output build \
  build/classes/dev/local/toasttest/*.class

( cd build && zip -q -u unsigned.apk classes.dex )

"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk

if [ ! -f build/debug.keystore ]; then
  "$KEYTOOL" -genkeypair -keystore build/debug.keystore -storepass android \
    -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 \
    -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi

"$BT/apksigner" sign --ks build/debug.keystore --ks-pass pass:android \
  --key-pass pass:android --out build/toasttest.apk build/aligned.apk

"$BT/apksigner" verify --print-certs build/toasttest.apk | head -3
ls -lh build/toasttest.apk
