#!/usr/bin/env bash
# Builds the test-only "Touch Test Game" APK (tools/touch-test-app). Same requirements as
# scripts/build-apk.sh. Output: build/touch-test/TouchTestGame.apk
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
OUT="$ROOT/build/touch-test"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BT="$SDK/build-tools/${BUILD_TOOLS:-$(ls "$SDK/build-tools" | sort -V | tail -1)}"
ANDROID_JAR="$SDK/platforms/android-36/android.jar"
if [[ -n "${KOTLIN_HOME:-}" ]]; then KOTLINC="$KOTLIN_HOME/bin/kotlinc"; else KOTLINC="$(command -v kotlinc)"; fi
STDLIB="$(cd "$(dirname "$(readlink -f "$KOTLINC")")/../lib" && pwd)/kotlin-stdlib.jar"

rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" --manifest "$HERE/AndroidManifest.xml" \
    --min-sdk-version 30 --target-sdk-version 36
"$KOTLINC" "$HERE"/src/com/fullautoclicker/touchtest/*.kt -jvm-target 17 -no-reflect -no-stdlib \
    -cp "$ANDROID_JAR:$STDLIB" -d "$OUT/classes"
(cd "$OUT/classes" && jar cf "$OUT/classes.jar" .)
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release --min-api 30 --lib "$ANDROID_JAR" \
    --output "$OUT/dex" "$OUT/classes.jar" "$STDLIB"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes*.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
KEYSTORE="$ROOT/build/throwaway.jks"
[[ -f "$KEYSTORE" ]] || keytool -genkeypair -keystore "$KEYSTORE" -storepass android -keypass android \
    -alias key -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Full Auto Clicker" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --ks-key-alias key --key-pass pass:android \
    --out "$OUT/TouchTestGame.apk" "$OUT/aligned.apk"
echo "== APK: $OUT/TouchTestGame.apk"
