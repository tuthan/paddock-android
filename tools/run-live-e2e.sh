#!/usr/bin/env bash
# The whole app on one emulator against a throwaway sshd and the disposable herdr session `paddock-test`.
#   tools/run-live-e2e.sh [adb-serial]
# The sshd is loopback-only, test-keys-only, and its sessions get an isolated HOME, so the relay install lands under
# build/e2e-home and never in the real home. Only `paddock-test` is touched in herdr; the default session is never used.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; . "$HERE/harness.sh"
SERIAL="${1:-emulator-5572}"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb -s $SERIAL"
PKG=io.github.tuthan.paddock; RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
OUT="$OUT_BASE/e2e-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$OUT"
export TEST_SSHD_RUN="$OUT_BASE/e2e-sshd" TEST_SSHD_PORT=2233 TEST_SSHD_HOME="$HOME/.cache/pdk-e2e-home"
rm -rf "$TEST_SSHD_RUN" "$TEST_SSHD_HOME"; mkdir -p "$TEST_SSHD_HOME/.config"
# herdr finds its sessions under $HOME/.config/herdr, so the isolated home links to the real config dir (read only for this test). The
# home is short on purpose, as in the terminal and operations flows: under build/ the terminal stream failed with "local socket name
# length exceeds capacity of sun_path", because herdr's client socket name is longer than the API socket's;
# everything the relay install writes goes under .local/share inside the isolated home.
ln -s "$HOME/.config/herdr" "$TEST_SSHD_HOME/.config/herdr"
HERDR="${PADDOCK_HERDR:-/usr/bin/herdr} --session paddock-test"
SOCK="$HOME/.config/herdr/sessions/paddock-test/herdr.sock"
[ -S "$SOCK" ] || { echo "paddock-test herdr session is not running ($SOCK)"; exit 1; }

# Android 17 (API 37) asks before an app reaches a LAN address (the emulator reaches the host at 10.0.2.2), and the dialog hides the
# app from the test; grant it up front. Older versions do not know the permission, and the error is ignored.
grant_lan() { $ADB shell pm grant $PKG android.permission.ACCESS_LOCAL_NETWORK >/dev/null 2>&1 || true; }

SRC="e2e-$(date +%s)"
BASE=$($HERDR pane list | python3 -c "import sys,json;print(json.load(sys.stdin)['result']['panes'][0]['pane_id'])")
SPLIT=$($HERDR pane split "$BASE" --direction right --no-focus | python3 -c "import sys,json;print(json.load(sys.stdin)['result']['pane']['pane_id'])")
cleanup() {
  $HERDR pane release-agent "$BASE" --source "$SRC" --agent fake --seq 90 >/dev/null 2>&1
  $HERDR pane release-agent "$SPLIT" --source "$SRC" --agent fake --seq 90 >/dev/null 2>&1
  $HERDR pane close "$SPLIT" >/dev/null 2>&1
  "$TOOLS/test-sshd.sh" stop >/dev/null 2>&1
  rm -rf "$TEST_SSHD_HOME"
}
trap cleanup EXIT
$HERDR pane report-agent "$BASE" --source "$SRC" --agent fake --state blocked --seq 1 >/dev/null
$HERDR pane report-agent "$SPLIT" --source "$SRC" --agent fake --state working --seq 1 >/dev/null

"$TOOLS/test-sshd.sh" start >/dev/null && FP="$("$TOOLS/test-sshd.sh" fingerprint | awk '{print $2}')"
export JAVA_HOME=$HOME/.local/share/mise/installs/java/temurin-17.0.20+8; export PATH=$JAVA_HOME/bin:$PATH ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
(cd "$ROOT" && ./gradlew --no-daemon --console=plain :app:assembleDebug :app:assembleDebugAndroidTest >"$OUT/build.log" 2>&1) || { echo "build failed: $OUT/build.log"; exit 1; }
$ADB install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
$ADB install -r "$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
$ADB shell pm clear $PKG >/dev/null; grant_lan
$ADB shell am instrument -w -e class "$PKG.e2e.LiveFlowTest#t0_exportAppKey" "$RUNNER" >"$OUT/t0.txt" 2>&1
$ADB pull "/sdcard/Android/data/$PKG/files/app-phone.pub" "$OUT/transport.pub" >/dev/null 2>&1 \
  && "$TOOLS/test-sshd.sh" authorize "$OUT/transport.pub" >/dev/null || { echo "key export failed"; cat "$OUT/t0.txt"; exit 1; }
$ADB shell rm -rf "/sdcard/Android/data/$PKG/files/screens"; $ADB logcat -c
$ADB shell am instrument -w -e hostFp "$FP" -e user "$USER" -e port 2233 -e home "$TEST_SSHD_HOME" -e session paddock-test -e holdMs 6000 -e class "$PKG.e2e.LiveFlowTest#addAMachineTrustItInstallTheRelayWatchAgentsReadOutputAndSeeActivity" "$RUNNER" >"$OUT/instrument.txt" 2>&1 &
INSTR=$!
# While the test holds on Output and then on Home, capture from the shell: FLAG_SECURE windows come back black.
for marker in OUTPUT HOME; do
  for _ in $(seq 1 240); do $ADB logcat -d -s E2E:I | grep -q "HOLD $marker" && break; sleep 0.5; done
  sleep 1; $ADB exec-out screencap -p >"$OUT/shell-capture-$marker.png"
done
wait $INSTR
for marker in OUTPUT HOME; do
  f="$OUT/shell-capture-$marker.png"
  if [ ! -s "$f" ]; then echo "shell capture on $marker: refused (0 bytes)"
  else echo "shell capture on $marker: pixel standard deviation $(magick "$f" -colorspace Gray -format '%[fx:standard_deviation]' info: 2>/dev/null) (0 = one flat colour)"; fi
done
$ADB pull "/sdcard/Android/data/$PKG/files/screens" "$OUT/" >/dev/null 2>&1
$ADB logcat -d -s E2E:I >"$OUT/e2e-log.txt"

# Second run on cleared app data: sign in with an imported key. The key is generated here, authorized on the throwaway sshd
# only, pushed for the test to read (the test deletes it), and removed from build/ afterwards.
IMPORT_KEY="$OUT/import-key"; ssh-keygen -q -t ed25519 -N '' -C paddock-e2e-import -f "$IMPORT_KEY"
"$TOOLS/test-sshd.sh" authorize "$IMPORT_KEY.pub" >/dev/null
$ADB shell pm clear $PKG >/dev/null; grant_lan
$ADB push "$IMPORT_KEY" "/sdcard/Android/data/$PKG/files/e2e-import-key" >/dev/null 2>&1
$ADB shell am instrument -w -e hostFp "$FP" -e user "$USER" -e port 2233 -e home "$TEST_SSHD_HOME" -e session paddock-test -e class "$PKG.e2e.LiveFlowTest#importAKeyThenConnectWithIt" "$RUNNER" >"$OUT/import.txt" 2>&1
rm -f "$IMPORT_KEY" "$IMPORT_KEY.pub"; $ADB shell rm -f "/sdcard/Android/data/$PKG/files/e2e-import-key"
$ADB pull "/sdcard/Android/data/$PKG/files/screens" "$OUT/screens-import/" >/dev/null 2>&1
echo "imported-key flow: $(grep -E '^OK|FAILURES' "$OUT/import.txt" | head -1) $(grep -E '^Error in|AssertionError' "$OUT/import.txt" | sort -u | head -3 | tr '\n' ' ')"
echo "relay on the isolated host home: $(ls -la "$TEST_SSHD_HOME"/.local/share/paddock/ 2>&1 | tail -n +2 | tr '\n' ' ')"
grep -E "^OK|FAILURES|Tests run" "$OUT/instrument.txt"; grep -E "^Error in|AssertionError|Exception" "$OUT/instrument.txt" | sort -u | head -10
cat "$OUT/e2e-log.txt" | sed 's/^.* I E2E *: //'
echo "results in $OUT"
