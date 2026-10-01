#!/usr/bin/env bash
# Run the S1-S6 suite for one candidate against the throwaway sshd.
#   spike/run-spike.sh <sshj|sshlib> [adb-serial]
set -uo pipefail
M="${1:?sshj|sshlib}"; SERIAL="${2:-emulator-5570}"; VARIANT="${VARIANT:-debug}"   # VARIANT=r8test runs the minified build
CAP="$(tr a-z A-Z <<<"${VARIANT:0:1}")${VARIANT:1}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; ROOT="$HERE/.."; RUN="$HERE/run"; mkdir -p "$RUN"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb -s $SERIAL"
PKG="io.github.tuthan.paddock.spike.$M"; RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
OUT="$RUN/$M-$VARIANT-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$OUT"

"$HERE/sshd.sh" status >/dev/null 2>&1 || "$HERE/sshd.sh" start
pgrep -f blackhole_proxy.py >/dev/null || { setsid nohup python3 "$HERE/blackhole_proxy.py" >"$RUN/proxy.log" 2>&1 & sleep 1; }
[ -f "$RUN/imported_ed25519" ] || ssh-keygen -q -t ed25519 -N spikepass -C paddock-spike-imported -f "$RUN/imported_ed25519"
"$HERE/sshd.sh" authorize "$RUN/imported_ed25519.pub" >/dev/null
$ADB push -q "$RUN/imported_ed25519" /data/local/tmp/spike_imported; $ADB shell chmod 644 /data/local/tmp/spike_imported

export JAVA_HOME=$HOME/.local/share/mise/installs/java/temurin-17.0.20+8; export PATH=$JAVA_HOME/bin:$PATH ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
(cd "$ROOT" && ./gradlew --no-daemon --console=plain -PspikeTest=$VARIANT ":spike-$M:assemble$CAP" ":spike-$M:assemble${CAP}AndroidTest" >"$OUT/build.log" 2>&1) || { echo "build failed, see $OUT/build.log"; exit 1; }
$ADB uninstall "$PKG" >/dev/null 2>&1; $ADB uninstall "$PKG.test" >/dev/null 2>&1
$ADB install "$ROOT/spike/$M/build/outputs/apk/$VARIANT/spike-$M-$VARIANT.apk" >/dev/null
$ADB install "$ROOT/spike/$M/build/outputs/apk/androidTest/$VARIANT/spike-$M-$VARIANT-androidTest.apk" >/dev/null

# Step 0: have the app mint its Keystore key and export the public half, then authorize it.
$ADB shell am instrument -w -e class "io.github.tuthan.paddock.spike.$( [ $M = sshj ] && echo SshjClient || echo SshlibClient)Suite#s0_exportKeys" "$RUNNER" >"$OUT/s0.txt" 2>&1
$ADB pull -q "/sdcard/Android/data/$PKG/files/keystore.pub" "$OUT/keystore.pub" && "$HERE/sshd.sh" authorize "$OUT/keystore.pub" >/dev/null || { echo "key export failed"; cat "$OUT/s0.txt"; exit 1; }

FP="$("$HERE/sshd.sh" fingerprint | awk '{print $2}')"
LOGLINES=$(wc -l < "$RUN/sshd.log")
$ADB logcat -c
$ADB shell am instrument -w -e hostFp "$FP" -e user "$USER" "$RUNNER" >"$OUT/instrument.txt" 2>&1
$ADB logcat -d -s SPIKE:I >"$OUT/spike-log.txt"
tail -n +"$((LOGLINES+1))" "$RUN/sshd.log" >"$OUT/sshd-during.log"
cat "$OUT/spike-log.txt"; echo "--- instrument summary"; grep -E "^OK|FAILURES|Tests run|INSTRUMENTATION_STATUS: test=|INSTRUMENTATION_STATUS_CODE: -|stack=" "$OUT/instrument.txt" | head -40
echo "results in $OUT"
