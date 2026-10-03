#!/usr/bin/env bash
# Phase 11 slices 1 to 3 on one emulator against a throwaway sshd: the phone's key is authorized by running the copied command (and only
# that) on the host, then a pairing link is opened as a VIEW intent, the trust dialog names the link's fingerprint, and a link with
# another fingerprint is refused before any question.
#   tools/run-pairing-e2e.sh [adb-serial]
# The sshd is loopback-only and its authorized_keys is $HOME/.cache/pdk-pair-home/.ssh/authorized_keys, the file the copied command
# writes; the command runs with that directory as HOME, never the real one. herdr is not involved.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; ROOT="$HERE/.."
SERIAL="${1:-emulator-5570}"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb -s $SERIAL"
PKG=io.github.tuthan.paddock; RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
OUT="$ROOT/build/pairing-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$OUT"
FAKE_HOME="$HOME/.cache/pdk-pair-home"
rm -rf "$FAKE_HOME"; mkdir -p "$FAKE_HOME/.ssh"; chmod 755 "$FAKE_HOME/.ssh"   # a loose directory, as a hand-made one often is
export TEST_SSHD_RUN="$FAKE_HOME/.ssh" TEST_SSHD_PORT=2235 TEST_SSHD_HOME="$FAKE_HOME"
cleanup() { "$HERE/test-sshd.sh" stop >/dev/null 2>&1; rm -rf "$FAKE_HOME"; }
trap cleanup EXIT
grant_lan() { $ADB shell pm grant $PKG android.permission.ACCESS_LOCAL_NETWORK >/dev/null 2>&1 || true; }

"$HERE/test-sshd.sh" start >/dev/null && FP="$("$HERE/test-sshd.sh" fingerprint | awk '{print $2}')"
[ -n "$FP" ] || { echo "no host fingerprint"; exit 1; }
export JAVA_HOME=$HOME/.local/share/mise/installs/java/temurin-17.0.20+8; export PATH=$JAVA_HOME/bin:$PATH ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
(cd "$ROOT" && ./gradlew --no-daemon --console=plain :app:assembleDebug :app:assembleDebugAndroidTest >"$OUT/build.log" 2>&1) || { echo "build failed: $OUT/build.log"; exit 1; }
$ADB install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
$ADB install -r "$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
ARGS=(-e hostFp "$FP" -e user "$USER" -e port 2235)
run() { $ADB shell am instrument -w "${ARGS[@]}" -e class "$PKG.e2e.PairingFlowTest#$1" "$RUNNER" >"$OUT/$1.txt" 2>&1; echo "$1: $(grep -E '^OK|FAILURES' "$OUT/$1.txt" | head -1) $(grep -E '^Error in|AssertionError' "$OUT/$1.txt" | sort -u | head -3 | tr '\n' ' ')"; }

# 1. a fresh app: create the key, show and copy the command
$ADB shell pm clear $PKG >/dev/null; grant_lan; $ADB logcat -c
run t1_createTheKeyAndCopyTheCommand
$ADB pull "/sdcard/Android/data/$PKG/files/copied-command.txt" "$OUT/copied-command.txt" >/dev/null 2>&1 || { echo "no command was written"; exit 1; }
# 2. the host: before the command there is no authorization at all; the command is run exactly as copied, twice
test ! -s "$FAKE_HOME/.ssh/authorized_keys" || { echo "authorized_keys was not empty before the command"; exit 1; }
for i in 1 2; do HOME="$FAKE_HOME" sh -c "$(cat "$OUT/copied-command.txt")" || { echo "the copied command failed on run $i"; exit 1; }; done
echo "authorized_keys after two runs: $(wc -l < "$FAKE_HOME/.ssh/authorized_keys") line(s); modes $(stat -c '%a' "$FAKE_HOME/.ssh") and $(stat -c '%a' "$FAKE_HOME/.ssh/authorized_keys"); the key's fingerprint: $(ssh-keygen -lf "$FAKE_HOME/.ssh/authorized_keys" | awk '{print $2}')"
# 3. the same app process data: the link, the trust dialog, the sign-in
run t2_aPairingLinkFillsInTheMachineAndTheCopiedCommandIsAllTheHostNeeded
# 4. a link with another fingerprint, on fresh data
$ADB shell pm clear $PKG >/dev/null; grant_lan
run t3_aLinkWhoseFingerprintIsNotTheHostsIsRefusedBeforeAnyQuestion
run t4_aWebPagesBrowsableIntentCannotOpenThePairingLinkButATapCan
$ADB logcat -d -s E2E:I | sed 's/^.* I E2E *: //' >"$OUT/e2e-log.txt"; cat "$OUT/e2e-log.txt"
echo "results in $OUT"
