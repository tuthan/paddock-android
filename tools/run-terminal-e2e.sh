#!/usr/bin/env bash
# Phase 05 on an emulator, through the whole app: the Terminal tab over SSH against the disposable herdr session `paddock-test`.
#   tools/run-terminal-e2e.sh [adb-serial]
# The test (app/src/androidTest/.../e2e/TerminalFlowTest.kt) drives the UI; this script prepares the host and, at each
# checkpoint the test announces in logcat, checks what only the host can see (the pane's geometry from `pane get` and the
# PTY, the desktop client, the helper processes, the proxy), then releases the test by pushing a file. The link is cut
# with tools/blackhole-proxy.py (no RST, like airplane mode); the moment a desktop-style `terminal session control`
# without --takeover is accepted again is the moment the desktop would regain input. Only `paddock-test` is touched in
# herdr, on a pane this script creates and closes; the sshd is loopback-only with test keys and an isolated HOME.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; ROOT="$HERE/.."
SERIAL="${1:-emulator-5572}"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb -s $SERIAL"
PKG=io.github.tuthan.paddock; RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
OUT="$ROOT/build/term-e2e-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$OUT"
LOG="$OUT/script-log.txt"
say() { printf '%s %s\n' "$(date +%H:%M:%S.%3N)" "$*" | tee -a "$LOG"; }
# The isolated HOME is kept short on purpose: herdr builds unix socket paths under $HOME/.config/herdr/sessions/<session>/ and a
# terminal socket under build/ is longer than sun_path (108 bytes) allows.
export TEST_SSHD_RUN="$ROOT/build/e2e-sshd" TEST_SSHD_PORT=2233 TEST_SSHD_HOME="$HOME/.cache/pdk-e2e-home"
PROXY_PORT=2234; PROXY_CTL=2235
# LINK_CUT=proxy (default) cuts the link with the blackhole proxy; LINK_CUT=airplane turns airplane mode on in the emulator
# instead (`cmd connectivity airplane-mode`, Android 12 and later) and off again afterwards.
LINK_CUT="${LINK_CUT:-proxy}"
rm -rf "$TEST_SSHD_RUN" "$TEST_SSHD_HOME"; mkdir -p "$TEST_SSHD_HOME/.config"
ln -s "$HOME/.config/herdr" "$TEST_SSHD_HOME/.config/herdr"
HERDR_BIN="${PADDOCK_HERDR:-/usr/bin/herdr}"
# herdr refuses to run nested inside another herdr pane; drop whatever HERDR_* variables this shell inherited.
UNSET=(); for v in $(env | grep -o '^HERDR_[A-Za-z_]*'); do UNSET+=(-u "$v"); done
H() { env "${UNSET[@]}" "$HERDR_BIN" --session paddock-test "$@"; }
SOCK="$HOME/.config/herdr/sessions/paddock-test/herdr.sock"
[ -S "$SOCK" ] || { echo "paddock-test herdr session is not running ($SOCK)"; exit 1; }

SRC="term-e2e-$(date +%s)"
BASE=$(H pane list | jq -r '.result.panes[0].pane_id')
P=$(H pane split "$BASE" --direction right --no-focus | jq -r '.result.pane.pane_id')
TID=$(H pane get "$P" | jq -r '.result.pane.terminal_id')
H pane report-agent "$P" --source "$SRC" --agent fake --state blocked --seq 1 >/dev/null
say "pane $P ($TID) in paddock-test; $(H --version 2>&1 | head -1)"

DESK_PIDS=()
cleanup() {
  for pid in "${DESK_PIDS[@]:-}"; do kill "$pid" 2>/dev/null; done
  [ -n "${PROXY_PID:-}" ] && kill "$PROXY_PID" 2>/dev/null
  H pane release-agent "$P" --source "$SRC" --agent fake --seq 90 >/dev/null 2>&1
  H pane close "$P" >/dev/null 2>&1
  "$HERE/test-sshd.sh" stop >/dev/null 2>&1
  rm -rf "$TEST_SSHD_HOME" "$OUT"/desk-*.fifo
}
trap cleanup EXIT

pty_size() { # "rows cols" of the pane's terminal, from the shell's controlling terminal
  local pid tty
  pid=$(H pane process-info --pane "$P" | jq -r '.result.process_info.shell_pid')
  tty=$(ps -o tty= -p "$pid" | tr -d ' ')
  stty -F "/dev/$tty" size
}
view_rows() { H pane get "$P" | jq -r '.result.pane.scroll.viewport_rows'; }
pane_has() { H pane read "$P" --source recent --lines 80 | grep -q -- "$1"; }
helper_pids() { pgrep -f "python3 .*paddock-control.py" | sort | tr "\n" " "; }
helpers() { pgrep -af "paddock-control.py" | grep -v pgrep | sed 's/^[0-9]* //' || true; }
observers() { pgrep -af "terminal session observe $P" | grep -v pgrep | sed 's/^[0-9]* //' || true; }

# --- desktop clients, each driven through its own fifo (fd 7 for A, fd 8 for B) ---
desk_start() { # name fd
  DESK_NAME=$1; DESK_FD=$2
  mkfifo "$OUT/desk-$1.fifo"
  python3 "$HERE/desktop-client.py" "$TID" 30 100 <"$OUT/desk-$1.fifo" >"$OUT/desk-$1.out" 2>"$OUT/desk-$1.err" &
  DESK_PIDS+=($!)
  eval "exec $2>\"$OUT/desk-$1.fifo\""; sleep 2
}
desk_ask() { eval "echo \"\$1\" >&$DESK_FD"; sleep "${2:-1.3}"; tail -1 "$OUT/desk-$DESK_NAME.out"; }
desk_start a 7
say "desktop client A attached: $(tail -1 "$OUT/desk-a.out"); pty $(pty_size)"
ROWS0=$(view_rows); SIZE0=$(pty_size)
say "before the app: pane get viewport_rows=$ROWS0, stty rows/cols=$SIZE0"

"$HERE/test-sshd.sh" start >/dev/null && FP="$("$HERE/test-sshd.sh" fingerprint | awk '{print $2}')"
python3 "$HERE/blackhole-proxy.py" $PROXY_PORT 2233 $PROXY_CTL >"$OUT/proxy.log" 2>&1 & PROXY_PID=$!
sleep 1
proxy() { python3 - "$1" <<'EOF'
import socket, sys
s = socket.create_connection(("127.0.0.1", 2235), timeout=3); s.sendall((sys.argv[1] + "\n").encode()); print(s.makefile().readline().strip())
EOF
}

export JAVA_HOME=$HOME/.local/share/mise/installs/java/temurin-17.0.20+8; export PATH=$JAVA_HOME/bin:$PATH ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
(cd "$ROOT" && ./gradlew --no-daemon --console=plain :app:assembleDebug :app:assembleDebugAndroidTest >"$OUT/build.log" 2>&1) || { echo "build failed: $OUT/build.log"; exit 1; }
$ADB install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
$ADB install -r "$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
$ADB shell pm clear $PKG >/dev/null
$ADB shell am instrument -w -e class "$PKG.e2e.TerminalFlowTest#t0_exportAppKey" "$RUNNER" >"$OUT/t0.txt" 2>&1
$ADB pull "/sdcard/Android/data/$PKG/files/app-phone.pub" "$OUT/transport.pub" >/dev/null 2>&1 \
  && "$HERE/test-sshd.sh" authorize "$OUT/transport.pub" >/dev/null || { echo "key export failed"; cat "$OUT/t0.txt"; exit 1; }
$ADB shell rm -rf "/sdcard/Android/data/$PKG/files/screens" "/sdcard/Android/data/$PKG/files/go-*"; $ADB logcat -c

$ADB shell am instrument -w -e hostFp "$FP" -e user "$USER" -e port $PROXY_PORT -e home "$TEST_SSHD_HOME" -e session paddock-test \
  -e class "$PKG.e2e.TerminalFlowTest#observeConflictTakeOverTypeScrollResizeReleaseThenLoseTheLink" "$RUNNER" >"$OUT/instrument.txt" 2>&1 &
INSTR=$!

wait_at() {
  for _ in $(seq 1 600); do
    $ADB logcat -d -s E2E:I | grep -q "AT $1\$" && return 0
    kill -0 $INSTR 2>/dev/null || return 1
    sleep 0.5
  done
  return 1
}
go() { : >"$OUT/go-$1"; $ADB push "$OUT/go-$1" "/sdcard/Android/data/$PKG/files/go-$1" >/dev/null 2>&1; }
fail=0
finish() {
  wait $INSTR 2>/dev/null
  say "instrumentation: $(grep -E '^OK|FAILURES|Tests run' "$OUT/instrument.txt" | head -2 | tr '\n' ' ') $(grep -E '^Error in|AssertionError' "$OUT/instrument.txt" | sort -u | head -3 | tr '\n' ' ')"
  $ADB pull "/sdcard/Android/data/$PKG/files/screens" "$OUT/" >/dev/null 2>&1
  $ADB logcat -d -s E2E:I | sed 's/^.* I E2E *: //' >"$OUT/e2e-log.txt"
  grep -E "SCROLL|RESIZE|LINKLOSS|OBSERVING" "$OUT/e2e-log.txt" | tee -a "$LOG"
  [ "$fail" = 0 ] && say "ALL CHECKS PASSED" || say "SOME CHECKS FAILED"
  echo "results in $OUT"
  exit "${1:-$fail}"
}
reach() { wait_at "$1" || { say "FAIL the test never reached '$1'"; fail=1; kill $INSTR 2>/dev/null; $ADB shell am force-stop $PKG; finish 1; }; }
check() { if eval "$2"; then say "PASS $1"; else say "FAIL $1"; fail=1; fi; }

reach observing
ROWS1=$(view_rows); SIZE1=$(pty_size)
say "AC-05.3 through the real app over SSH: pane get viewport_rows $ROWS0 -> $ROWS1; stty rows/cols $SIZE0 -> $SIZE1; observer: $(observers | head -1 | cut -c1-140)"
check "AC-05.3 geometry unchanged while the app observes" '[ "$ROWS0" = "$ROWS1" ] && [ "$SIZE0" = "$SIZE1" ]'
check "the app runs an observer and no control helper" '[ -n "$(observers)" ] && [ -z "$(helpers)" ]'
go observing

reach helper-consent
check "nothing was written to the host before the user agreed" '[ ! -e "$TEST_SSHD_HOME/.local/share/paddock/paddock-control.py" ]'
go helper-consent

reach conflict
PIN=$(jq -r '.files["host/paddock-control.py"]' "$ROOT/host/SOURCE.json" | sed 's/^sha256://')
check "the helper on the host is the pinned file" '[ "$(sha256sum "$TEST_SSHD_HOME/.local/share/paddock/paddock-control.py" | cut -d" " -f1)" = "$PIN" ]'
say "helper file mode: $(stat -c %a "$TEST_SSHD_HOME/.local/share/paddock/paddock-control.py")"
check "no control helper is left running after the refusal" '[ -z "$(helpers)" ]'
DESK_NAME=a; DESK_FD=7; say "desktop A after the refusal: $(desk_ask status)"
check "the desktop client was not disturbed by the refused request" 'desk_ask status | jq -e ".alive == true" >/dev/null'
desk_ask "type echo desktop-e2e" 1.5 >/dev/null
check "AC-05.4 the desktop still types after the refusal" 'pane_has desktop-e2e'
check "the refusal changed no size" '[ "$(pty_size)" = "$SIZE0" ]'
go conflict

reach controlling
say "after Take over: desktop A $(desk_ask status); helper: $(helpers | head -1 | cut -c1-200)"
check "AC-05.4 takeover displaced the desktop client" 'desk_ask status | jq -e ".alive == false" >/dev/null'
check "the helper runs herdr's control with takeover at the pane's own size" 'helpers | grep -q "$P ${SIZE0#* } ${SIZE0% *} takeover"'
check "control attached at the pane own size, nothing resized" '[ "$(pty_size)" = "$SIZE0" ]'
go controlling

reach typed
check "AC-05.4 the phone's keystrokes reached the pane" 'pane_has phone-e2e'
go typed

reach rotate
HELPER_BEFORE=$(helper_pids)
ROT0=$($ADB shell settings get system accelerometer_rotation | tr -d '\r')
$ADB shell settings put system accelerometer_rotation 0; $ADB shell settings put system user_rotation 1; sleep 4
HELPER_LAND=$(helper_pids)
ROT_LAND=$($ADB shell dumpsys window | grep -m1 -o 'mCurrentRotation=[A-Z_0-9]*' | tr -d '\r')  # API 26 prints 0..3, later releases ROTATION_0..270
$ADB shell settings put system user_rotation 0; sleep 4
HELPER_PORT=$(helper_pids)
$ADB shell settings put system accelerometer_rotation "${ROT0:-1}"
say "rotation under control: device in landscape was $ROT_LAND; helper pid before: $HELPER_BEFORE; in landscape the same: $([ "$HELPER_BEFORE" = "$HELPER_LAND" ] && echo yes || echo NO); back in portrait the same: $([ "$HELPER_BEFORE" = "$HELPER_PORT" ] && echo yes || echo NO)"
check "the device really rotated" 'case "$ROT_LAND" in mCurrentRotation=1|mCurrentRotation=3|mCurrentRotation=ROTATION_90|mCurrentRotation=ROTATION_270) true ;; *) false ;; esac'
check "a rotation keeps the same control helper process (control was not released)" '[ -n "$HELPER_BEFORE" ] && [ "$HELPER_BEFORE" = "$HELPER_LAND" ] && [ "$HELPER_BEFORE" = "$HELPER_PORT" ]'
go rotate

reach soft-keyboard-ready
$ADB exec-out screencap -p > "$OUT/soft-keyboard-ime.png" 2>/dev/null   # the real screen with the Android keyboard up
# Keys through the system's input pipeline reach the focused hidden field the Keyboard button put the focus on: a plain line, then Enter.
$ADB shell input text 'echo%ssoft-e2e'; $ADB shell input keyevent 66; sleep 2
check "the Android keyboard's keys (text and Enter) reached the pane through the hidden field" 'pane_has "^soft-e2e$"'
check "the line was typed once, not twice (one command line, one output line, and no garbled echo)" '[ "$(H pane read "$P" --source recent --lines 80 | grep -c "echo soft-e2e")" = 1 ] && [ "$(H pane read "$P" --source recent --lines 80 | grep -c "^soft-e2e$")" = 1 ] && ! pane_has "command not found"'
go soft-keyboard-ready

# The key strip's modifiers and extra keys: the test ran `stty -echo` and `cat -v` in the pane and tapped Shift+Tab, Ctrl+Right, Insert,
# Delete, F1, F12, Shift+PgUp and Alt+Enter; `cat -v` shows ESC as ^[, so each line is the xterm sequences the pane received.
reach strip-keys
pane_line() { H pane read "$P" --source recent --lines 80 | grep -cxF -- "$1"; }
check "the strip's Shift+Tab, Ctrl+Right, Insert and Delete reached the pane as xterm sends them, once" '[ "$(pane_line "^[[Z^[[1;5C^[[2~^[[3~")" = 1 ]'
check "the strip's F1, F12, Shift+PgUp and Alt+Enter (ESC then CR) reached the pane as xterm sends them, once" '[ "$(pane_line "^[OP^[[24~^[[5;2~^[")" = 1 ]'
go strip-keys

for n in 1 2 3 4 5; do
  reach scroll-$n
  H pane run "$P" "clear; seq 1 200" >/dev/null; sleep 3
  go scroll-$n
done

reach resize-warning
check "AC-05.5 the warning is on screen and nothing has been resized yet" '[ "$(pty_size)" = "$SIZE0" ]'
go resize-warning

reach resized
SIZE2=$(pty_size); ROWS2=$(view_rows)
say "AC-05.5 after Resize to fit: stty rows/cols $SIZE0 -> $SIZE2; pane get viewport_rows $ROWS0 -> $ROWS2"
check "AC-05.5 the PTY and herdr's viewport changed" '[ "$SIZE2" != "$SIZE0" ] && [ "$ROWS2" != "$ROWS0" ]'
go resized

reach released
check "release leaves no control helper behind" '[ -z "$(helpers)" ]'
desk_start b 8
say "desktop client B after the phone released: $(tail -1 "$OUT/desk-b.out")"
check "AC-05.4 after release a desktop client attaches without takeover" 'jq -e ".alive == true" <<<"$(tail -1 "$OUT/desk-b.out")" >/dev/null'
desk_ask "type echo desktop-after-release" 1.5 >/dev/null
check "AC-05.4 and types again" 'pane_has desktop-after-release'
desk_ask quit 1 >/dev/null; exec 8>&-; exec 7>&-; sleep 1
go released

accepted() { # a desktop-style control without --takeover; success means it was accepted (then it is released at once)
  python3 - "$P" "$(pty_size | awk '{print $2}')" "$(pty_size | awk '{print $1}')" <<'EOF'
import json, subprocess, sys, os, time
pane, cols, rows = sys.argv[1:4]
env = {k: v for k, v in os.environ.items() if not k.startswith("HERDR_")}
r = subprocess.Popen(["/usr/bin/herdr", "--session", "paddock-test", "terminal", "session", "control", pane, "--cols", cols, "--rows", rows],
                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
try:
    ok = json.loads(r.stdout.readline()).get("type") == "terminal.frame"
    if ok:
        r.stdin.write(b'{"type":"terminal.release"}\n'); r.stdin.flush(); time.sleep(0.2)
finally:
    r.kill()
sys.exit(0 if ok else 1)
EOF
}

# --- leaving while in control: the app in the background and the Back button both release at once ---
reach background-ready
check "the helper is running before the app is backgrounded" '[ -n "$(helpers)" ]'
$ADB shell input keyevent KEYCODE_HOME; sleep 3
check "AC-05.6 backgrounding the app released control (no helper left on the host)" '[ -z "$(helpers)" ]'
check "AC-05.6 and a desktop-style control is accepted straight away" 'accepted'
# the launcher's own intent: it matches the task's base intent, so the existing activity is brought forward, not a second one created
$ADB shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$PKG/.MainActivity" >/dev/null 2>&1; sleep 1
go background-ready

reach back-ready
check "the helper is running before Back is pressed" '[ -n "$(helpers)" ]'
$ADB shell input keyevent KEYCODE_BACK; sleep 3
check "AC-05.6 Back released control (no helper left on the host)" '[ -z "$(helpers)" ]'
check "AC-05.6 and a desktop-style control is accepted straight away after Back" 'accepted'
go back-ready

reach control-before-link-loss
check "the phone controls again without a conflict" '[ -n "$(helpers)" ]'
check "before the link is cut a desktop-style control is refused" '! accepted'
T0=$(date +%s.%N)
if [ "$LINK_CUT" = airplane ]; then say "cutting the link at $T0: airplane mode on: $($ADB shell cmd connectivity airplane-mode enable 2>&1 | tr -d '\r')"
else say "cutting the link at $T0: $(proxy freeze)"; fi
go control-before-link-loss
GAP=""
while :; do
  sleep 1
  NOW=$(date +%s.%N); EL=$(awk -v a="$NOW" -v b="$T0" 'BEGIN{printf "%.1f", a-b}')
  if accepted; then GAP=$EL; break; fi
  awk -v e="$EL" 'BEGIN{exit !(e > 90)}' && break
done
say "AC-05.6 the desktop-style control without takeover was ${GAP:+accepted $GAP s after the link was cut}${GAP:-still refused after 90 s}"
check "AC-05.6 the desktop regained input within 30 s" '[ -n "$GAP" ] && awk -v g="$GAP" "BEGIN{exit !(g <= 30)}"'
check "the helper process is gone from the host" '[ -z "$(helpers)" ]'
if [ "$LINK_CUT" = airplane ]; then say "airplane mode off: $($ADB shell cmd connectivity airplane-mode disable 2>&1 | tr -d '\r')"
else say "thawing the link: $(proxy thaw)"; fi
reach done
go done
finish
