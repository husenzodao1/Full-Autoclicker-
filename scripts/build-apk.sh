#!/usr/bin/env bash
# Builds a signed release APK straight from the Android SDK build tools, without the Android
# Gradle Plugin: aapt2 -> javac (R) -> kotlinc -> R8 -> zipalign -> apksigner.
#
# Use this when Google Maven (and therefore AGP) is unreachable. With normal internet access,
# `./gradlew assembleRelease` builds the same sources.
#
# Requirements: JDK 17+, ANDROID_HOME with platforms/android-<compileSdk> and
# build-tools/<BUILD_TOOLS>, and kotlinc (KOTLIN_HOME or on PATH).
#
# Signing: set KEYSTORE, KEYSTORE_PASS, KEY_ALIAS, KEY_PASS to sign with your key. Otherwise a
# throwaway key is generated once at build/throwaway.jks and reused (fine for testing).
#
# Output: build/offline/FullAutoClicker-<versionName>.apk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"
OUT="$ROOT/build/offline"

gradle_value() { sed -nE "s/^\s*$1\s*=\s*\"?([^\"]+)\"?\s*$/\1/p" "$APP/build.gradle.kts" | head -1; }
APP_ID="$(gradle_value applicationId)"
MIN_SDK="$(gradle_value minSdk)"
TARGET_SDK="$(gradle_value targetSdk)"
COMPILE_SDK="$(gradle_value compileSdk)"
VERSION_CODE="$(gradle_value versionCode)"
VERSION_NAME="$(gradle_value versionName)"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -d "$SDK" ]] || { echo "Set ANDROID_HOME to your Android SDK" >&2; exit 1; }
BUILD_TOOLS="${BUILD_TOOLS:-$(ls "$SDK/build-tools" | sort -V | tail -1)}"
BT="$SDK/build-tools/$BUILD_TOOLS"
ANDROID_JAR="$SDK/platforms/android-$COMPILE_SDK/android.jar"
[[ -f "$ANDROID_JAR" ]] || { echo "Missing $ANDROID_JAR" >&2; exit 1; }

if [[ -n "${KOTLIN_HOME:-}" ]]; then KOTLINC="$KOTLIN_HOME/bin/kotlinc"; else KOTLINC="$(command -v kotlinc)"; fi
KOTLIN_LIB="$(cd "$(dirname "$(readlink -f "$KOTLINC")")/../lib" && pwd)"
STDLIB="$KOTLIN_LIB/kotlin-stdlib.jar"

echo "== $APP_ID $VERSION_NAME ($VERSION_CODE)  minSdk $MIN_SDK  targetSdk $TARGET_SDK  compileSdk $COMPILE_SDK"
echo "== build-tools $BUILD_TOOLS  kotlinc $("$KOTLINC" -version 2>&1 | grep -oE 'kotlinc-jvm [0-9.]+' | cut -d' ' -f2)"

rm -rf "$OUT"
mkdir -p "$OUT"/{res,gen,classes,dex}

# 1. Resources. AGP supplies the package name from `namespace`; aapt2 needs it in the manifest.
sed "s#<manifest #<manifest package=\"$APP_ID\" #" "$APP/src/main/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
"$BT/aapt2" compile --dir "$APP/src/main/res" -o "$OUT/res/"
"$BT/aapt2" link -o "$OUT/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$OUT/AndroidManifest.xml" \
    --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
    --java "$OUT/gen" --proguard "$OUT/aapt-rules.pro" \
    --auto-add-overlay "$OUT"/res/*.flat

# 2. Code.
find "$OUT/gen" -name '*.java' > "$OUT/r-sources.txt"
javac --release 17 -nowarn -d "$OUT/classes" @"$OUT/r-sources.txt"
find "$APP/src/main/java" -name '*.kt' > "$OUT/kt-sources.txt"
"$KOTLINC" @"$OUT/kt-sources.txt" \
    -jvm-target 17 -no-reflect -no-stdlib -cp "$ANDROID_JAR:$OUT/classes:$STDLIB" \
    -Werror \
    -d "$OUT/classes"

# 3. Dex with R8 (shrinks the Kotlin standard library, like AGP's release minify).
(cd "$OUT/classes" && jar cf "$OUT/app-classes.jar" .)
cat > "$OUT/r8-rules.pro" <<'EOF'
-dontwarn kotlin.**
-dontwarn org.jetbrains.annotations.**
EOF
java -cp "$BT/lib/d8.jar" com.android.tools.r8.R8 --release \
    --min-api "$MIN_SDK" \
    --lib "$ANDROID_JAR" \
    --pg-conf "$APP/proguard-rules.pro" \
    --pg-conf "$OUT/aapt-rules.pro" \
    --pg-conf "$OUT/r8-rules.pro" \
    --pg-map-output "$OUT/mapping.txt" \
    --output "$OUT/dex" \
    "$OUT/app-classes.jar" "$STDLIB"

# 4. Package, align, sign.
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -X "$OUT/unsigned.apk" classes*.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [[ -z "${KEYSTORE:-}" ]]; then
    # Kept outside $OUT so repeated builds share one key and can update each other.
    KEYSTORE="$ROOT/build/throwaway.jks"; KEYSTORE_PASS=android; KEY_ALIAS=key; KEY_PASS=android
    if [[ ! -f "$KEYSTORE" ]]; then
        keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KEYSTORE_PASS" -keypass "$KEY_PASS" \
            -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
            -dname "CN=Full Auto Clicker" >/dev/null 2>&1
    fi
    echo "== No KEYSTORE given: signed with the local throwaway key $KEYSTORE"
fi
APK="$OUT/FullAutoClicker-$VERSION_NAME.apk"
"$BT/apksigner" sign --v2-signing-enabled true --v3-signing-enabled true --ks "$KEYSTORE" --ks-pass "pass:$KEYSTORE_PASS" \
    --ks-key-alias "$KEY_ALIAS" --key-pass "pass:$KEY_PASS" \
    --out "$APK" "$OUT/aligned.apk"
"$BT/apksigner" verify --verbose "$APK" | grep -E '^Verified|v[0-9]'
"$BT/zipalign" -c -p 4 "$APK" && echo "== zipalign OK"
echo "== APK: $APK ($(stat -c %s "$APK") bytes)"
