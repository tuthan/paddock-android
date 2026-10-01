#!/usr/bin/env bash
# Run the SSH transport instrumentation tests against the throwaway sshd.
#   tools/run-transport-tests.sh [adb-serial] [extra instrumentation class#method]
# Needs the emulator or phone to reach the host at 10.0.2.2 (emulator) or set HOST_ADDR for a phone.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; ROOT="$HERE/.."
SERIAL="${1:-emulator-5570}"; FILTER="${2:-io.github.tuthan.paddock.ssh.SshSessionTest}"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb -s $SERIAL"
RUN="$ROOT/build/test-sshd"; OUT="$ROOT/build/transport-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$RUN" "$OUT"
PKG=io.github.tuthan.paddock; RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"

"$HERE/test-sshd.sh" status >/dev/null 2>&1 || "$HERE/test-sshd.sh" start
pgrep -f blackhole-proxy.py >/dev/null || { setsid nohup python3 "$HERE/blackhole-proxy.py" >"$RUN/proxy.log" 2>&1 & sleep 1; }
[ -f "$RUN/imported_ed25519" ] || ssh-keygen -q -t ed25519 -N spikepass -C paddock-test-imported -f "$RUN/imported_ed25519"
"$HERE/test-sshd.sh" authorize "$RUN/imported_ed25519.pub" >/dev/null
$ADB push "$RUN/imported_ed25519" /data/local/tmp/spike_imported >/dev/null; $ADB shell chmod 644 /data/local/tmp/spike_imported

export JAVA_HOME=$HOME/.local/share/mise/installs/java/temurin-17.0.20+8; export PATH=$JAVA_HOME/bin:$PATH ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
(cd "$ROOT" && ./gradlew --no-daemon --console=plain :app:assembleDebug :app:assembleDebugAndroidTest >"$OUT/build.log" 2>&1) || { echo "build failed: $OUT/build.log"; exit 1; }
$ADB install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
$ADB install -r "$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null

$ADB shell am instrument -w -e class "io.github.tuthan.paddock.ssh.SshSessionTest#t0_exportPhoneKey" "$RUNNER" >"$OUT/t0.txt" 2>&1
$ADB pull "/sdcard/Android/data/$PKG/files/transport.pub" "$OUT/transport.pub" >/dev/null 2>&1 \
  && "$HERE/test-sshd.sh" authorize "$OUT/transport.pub" >/dev/null || { echo "key export failed"; cat "$OUT/t0.txt"; exit 1; }

FP="$("$HERE/test-sshd.sh" fingerprint | awk '{print $2}')"
LINES=$(wc -l < "$RUN/sshd.log"); $ADB logcat -c
$ADB shell am instrument -w -e hostFp "$FP" -e user "$USER" -e class "$FILTER" "$RUNNER" >"$OUT/instrument.txt" 2>&1
$ADB logcat -d -s TRANSPORT:I >"$OUT/transport-log.txt"
tail -n +"$((LINES+1))" "$RUN/sshd.log" >"$OUT/sshd-during.log"
sed 's/^[0-9-]* [0-9:.]* *[0-9]* *[0-9]* I //' "$OUT/transport-log.txt" | grep -v "T0 exported"
echo "--- summary"; grep -E "^OK|FAILURES|Tests run" "$OUT/instrument.txt"
grep -E "^Error in|^java.lang.AssertionError|Exception:" "$OUT/instrument.txt" | sort -u | head -20
python3 - "$OUT/sshd-during.log" <<'PY'
import re,sys,collections
conns=collections.OrderedDict()
for l in open(sys.argv[1]).read().splitlines():
    m=re.match(r'Connection from \S+ port (\d+)',l)
    if m: conns[m.group(1)]=[l]; continue
    m=re.search(r'port (\d+)',l)
    if m and m.group(1) in conns: conns[m.group(1)].append(l)
no=[p for p,ls in conns.items() if not any(re.search(r'Accepted|Postponed|Failed|Invalid user|authenticating',x) for x in ls)]
print(f"sshd: {len(conns)} connections, {len(no)} with no authentication line at all (host-key rejections expected)")
PY
echo "results in $OUT"
