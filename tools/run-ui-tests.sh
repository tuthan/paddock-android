#!/usr/bin/env bash
# Run Compose UI tests on a device and pull the screenshots they write.
#   tools/run-ui-tests.sh [adb-serial] [class] [out-dir]
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; . "$HERE/harness.sh"
SERIAL="${1:-emulator-5570}"; FILTER="${2:-io.github.tuthan.paddock.ui}"; OUT="${3:-$OUT_BASE/ui-$(date +%Y%m%d-%H%M%S)}"; mkdir -p "$OUT"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb -s $SERIAL"
PKG=io.github.tuthan.paddock; RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
export JAVA_HOME=$HOME/.local/share/mise/installs/java/temurin-17.0.20+8; export PATH=$JAVA_HOME/bin:$PATH ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
(cd "$ROOT" && ./gradlew --no-daemon --console=plain ${GRADLE_EXTRA:-} :app:assembleDebug :app:assembleDebugAndroidTest >"$OUT/build.log" 2>&1) || { echo "build failed: $OUT/build.log"; exit 1; }
$ADB install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
$ADB install -r "$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
$ADB shell rm -rf "/sdcard/Android/data/$PKG/files/screens"
$ADB shell am instrument -w -e package "$FILTER" ${INSTR_ARGS:-} "$RUNNER" >"$OUT/instrument.txt" 2>&1
$ADB pull "/sdcard/Android/data/$PKG/files/screens" "$OUT/" >/dev/null 2>&1
grep -E "^OK|FAILURES|Tests run" "$OUT/instrument.txt"; grep -E "^Error in|AssertionError|Exception" "$OUT/instrument.txt" | sort -u | head -20
echo "results in $OUT"
