#!/usr/bin/env bash
# AC-02.9 to AC-02.11 on an Android 17 emulator or phone. Revoking a runtime permission kills the app, so the
# grant state is set with pm between instrumentation runs.
#   tools/run-permission-tests.sh [adb-serial]
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; . "$HERE/harness.sh"
SERIAL="${1:-emulator-5574}"; ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb -s $SERIAL"
PKG=io.github.tuthan.paddock; PERM=android.permission.ACCESS_LOCAL_NETWORK
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"; CLS="$PKG.ssh.LocalNetworkGateTest"
OUT="$OUT_BASE/permission-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$OUT"

# Build, install, export and authorize the phone key (also runs the first method once).
"$HERE/run-transport-tests.sh" "$SERIAL" "$CLS#recordWhatTheOsDoes" >"$OUT/setup.txt" 2>&1 || { echo "setup failed: $OUT/setup.txt"; exit 1; }
FP="$("$TOOLS/test-sshd.sh" fingerprint | awk '{print $2}')"
SSHD_LOG="${TEST_SSHD_RUN:-$OUT_BASE/test-sshd}/sshd.log"
step() { # <label> <method>
  local label="$1" method="$2"
  $ADB logcat -c; LINES=$(wc -l < "$SSHD_LOG")
  $ADB shell am instrument -w -e hostFp "$FP" -e user "$USER" ${INSTR_ARGS:-} -e class "$CLS#$method" "$RUNNER" >"$OUT/$label.txt" 2>&1
  $ADB logcat -d -s TRANSPORT:I | sed 's/^[0-9-]* [0-9:.]* *[0-9]* *[0-9]* I //' | grep -v "T0 exported" >>"$OUT/$label.txt"
  local conns; conns=$(tail -n +"$((LINES+1))" "$SSHD_LOG" | grep -c '^Connection from')
  printf '%-34s %-6s sshd connections: %s\n' "$label" "$(grep -qE '^OK \(' "$OUT/$label.txt" && echo PASS || echo FAIL)" "$conns"
  grep -E "^TRANSPORT|T (denied|granted|os)" "$OUT/$label.txt" | sed 's/^/    /'
}
$ADB shell pm revoke $PKG $PERM 2>/dev/null
step 1-denied-refused              deniedRefusesInTheGateAndNoSocketOpens
step 2-denied-os-behaviour         recordWhatTheOsDoes
$ADB shell pm grant $PKG $PERM
step 3-granted-connects            grantedConnectsInAFreshProcessWithoutAnyAppRestartLogic
step 4-granted-os-behaviour        recordWhatTheOsDoes
$ADB shell pm revoke $PKG $PERM
step 5-revoked-refused-next-attempt deniedRefusesInTheGateAndNoSocketOpens
echo "results in $OUT"
