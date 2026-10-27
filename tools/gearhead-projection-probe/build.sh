#!/usr/bin/env bash
# Build the standalone gearhead projected-activity probe.
#
# Requires: JDK 17+, Android SDK platform 33+ and build-tools with aapt2/d8/apksigner.
# The probe targets SDK 37 because that is what the test device runs; lower targets work too.
set -euo pipefail

cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
[ -d "$SDK" ] || { echo "Android SDK not found; set ANDROID_HOME" >&2; exit 1; }

BUILD_TOOLS="$(find "$SDK/build-tools" -maxdepth 1 -mindepth 1 -type d | sort -V | tail -1)"
PLATFORM_DIR="$(find "$SDK/platforms" -maxdepth 1 -mindepth 1 -type d -name 'android-*' | sort -V | tail -1)"
[ -n "$BUILD_TOOLS" ] && [ -n "$PLATFORM_DIR" ] || { echo "Missing build-tools or platform" >&2; exit 1; }
ANDROID_JAR="$PLATFORM_DIR/android.jar"

AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"
KEYSTORE="${DEBUG_KEYSTORE:-$HOME/.android/debug.keystore}"

for tool in "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER"; do
    [ -x "$tool" ] || { echo "Missing tool: $tool" >&2; exit 1; }
done

if [ ! -f "$KEYSTORE" ]; then
    echo "Debug keystore not found: $KEYSTORE" >&2
    echo "Create one with: keytool -genkey -v -keystore \"$KEYSTORE\" -storepass android \\" >&2
    echo "  -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname CN=Android" >&2
    exit 1
fi

rm -rf build
mkdir -p build/classes build/dex

find src -name '*.java' -print0 | xargs -0 javac \
    --release 17 -Xlint:deprecation -classpath "$ANDROID_JAR" -d build/classes

find build/classes -name '*.class' -print0 | xargs -0 "$D8" \
    --lib "$ANDROID_JAR" --output build/dex

"$AAPT2" link \
    -o build/base.apk \
    -I "$ANDROID_JAR" \
    --manifest AndroidManifest.xml

(cd build/dex && zip -q ../base.apk classes.dex)

"$ZIPALIGN" -f 4 build/base.apk build/probe-unaligned.apk
"$APKSIGNER" sign \
    --ks "$KEYSTORE" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --out build/probe.apk \
    build/probe-unaligned.apk

echo "Built: $(pwd)/build/probe.apk"
echo "Install: adb install -r build/probe.apk"
