#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
BUILD_TOOLS="${BUILD_TOOLS:-$SDK/build-tools/36.0.0}"
ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-37.1/android.jar}"
API_JAR="${API_JAR:-$ROOT/lib/api-82.jar}"
KS="${AARE_HOOKS_KEYSTORE:-$HOME/.android/debug.keystore}"

for f in "$BUILD_TOOLS/aapt2" "$BUILD_TOOLS/d8" "$BUILD_TOOLS/zipalign" "$BUILD_TOOLS/apksigner" "$ANDROID_JAR" "$API_JAR" "$KS"; do
  [[ -e "$f" ]] || { echo "missing: $f" >&2; exit 1; }
done

rm -rf "$ROOT/build"
mkdir -p "$ROOT/build/classes" "$ROOT/build/dex"

find "$ROOT/src" -name '*.java' -print0 | xargs -0 javac -source 8 -target 8 \
  -cp "$API_JAR:$ANDROID_JAR" -d "$ROOT/build/classes"

find "$ROOT/build/classes" -name '*.class' -print0 | xargs -0 "$BUILD_TOOLS/d8" --release --min-api 26 \
  --lib "$ANDROID_JAR" --output "$ROOT/build/dex"

"$BUILD_TOOLS/aapt2" link -o "$ROOT/build/unsigned.apk" \
  -I "$ANDROID_JAR" --manifest "$ROOT/src/AndroidManifest.xml" \
  -A "$ROOT/src/assets" --min-sdk-version 26 --target-sdk-version 37 \
  --version-code 1 --version-name 1.0

( cd "$ROOT/build" && zip -q -j unsigned.apk dex/classes.dex )
"$BUILD_TOOLS/zipalign" -f 4 "$ROOT/build/unsigned.apk" "$ROOT/build/aligned.apk"
"$BUILD_TOOLS/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$ROOT/build/aare-hooks.apk" "$ROOT/build/aligned.apk"
"$BUILD_TOOLS/apksigner" verify --print-certs "$ROOT/build/aare-hooks.apk" | head -3
ls -lh "$ROOT/build/aare-hooks.apk"
