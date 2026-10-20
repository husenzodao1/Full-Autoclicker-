#!/usr/bin/env bash
# Runs the JVM unit tests for the Android-free core (app/src/main/.../core) without Gradle.
# With Gradle available, `./gradlew testDebugUnitTest` runs the same tests.
#
# Requirements: JDK 17+, kotlinc, and JUnit 4 + Hamcrest jars (JUNIT_JARS, colon separated).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build/unit-tests"
: "${JUNIT_JARS:?Set JUNIT_JARS to junit-4.13.2.jar:hamcrest-core-1.3.jar}"
if [[ -n "${KOTLIN_HOME:-}" ]]; then KOTLINC="$KOTLIN_HOME/bin/kotlinc"; else KOTLINC="$(command -v kotlinc)"; fi
STDLIB="$(cd "$(dirname "$(readlink -f "$KOTLINC")")/../lib" && pwd)/kotlin-stdlib.jar"

rm -rf "$OUT" && mkdir -p "$OUT"
"$KOTLINC" -Werror -jvm-target 17 -no-reflect -cp "$JUNIT_JARS" -d "$OUT" \
    "$ROOT"/app/src/main/java/com/fullautoclicker/core/*.kt \
    "$ROOT"/app/src/test/java/com/fullautoclicker/core/*.kt

TESTS=$(cd "$OUT" && find . -name '*Test.class' | sed 's#^\./##; s#\.class$##; s#/#.#g' | sort)
java -cp "$OUT:$STDLIB:$JUNIT_JARS" org.junit.runner.JUnitCore $TESTS
