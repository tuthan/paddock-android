#!/usr/bin/env bash
# Phase 06 on an emulator, through the whole app: Manual input and Esc, desktop focus with its first-time question, prompts, the
# operation journal on the phone, and a link that dies right after a prompt was delivered (AC-06.3), in two stages with an app
# restart between them.
#   tools/run-operations-e2e.sh [adb-serial]
# The test (app/src/androidTest/.../e2e/OperationsFlowTest.kt) drives the UI. This script prepares the host and, at each
# checkpoint the test announces in logcat, checks what only the host can see and releases the test with a file:
#   - tools/fake-agent.py runs as `claude` in a pane of the disposable session `paddock-test` and logs every Esc, Ctrl+C and
#     submission to a file outside the pane, so "exactly once" is a count and not a reading of a screen (herdr accepts
#     `agent prompt` only for a pane whose foreground process is a known agent);
#   - `herdr pane get` says whether the desktop's cursor is on the pane (focus);
#   - the journal on the phone is read with run-as (debug build): hashes, kept text, outcomes;
#   - the link is cut by tools/blackhole-proxy.py: 300 ms of latency from the host so the answer to a prompt is still on its
#     way when tools/cut-after-submit.py freezes the link the instant the submission is logged. The held answer is
#     discarded, so the phone sees exactly what a dead link after the write looks like: a request that arrived, no answer.
# Only `paddock-test` is touched in herdr, on a pane this script creates and closes; the sshd is loopback-only with test keys
# and an isolated HOME.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; . "$HERE/harness.sh"
SERIAL="${1:-emulator-5572}"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb -s $SERIAL"
PKG=io.github.tuthan.paddock; RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
OUT="$OUT_BASE/ops-e2e-$(date +%Y%m%d-%H%M%S)-${SERIAL#emulator-}"; mkdir -p "$OUT"
LOG="$OUT/script-log.txt"
say() { printf '%s %s\n' "$(date +%H:%M:%S.%3N)" "$*" | tee -a "$LOG"; }
export TEST_SSHD_RUN="$OUT_BASE/e2e-sshd" TEST_SSHD_PORT=2233 TEST_SSHD_HOME="$HOME/.cache/pdk-e2e-home"
PROXY_PORT=2234; PROXY_CTL=2235
rm -rf "$TEST_SSHD_RUN" "$TEST_SSHD_HOME"; mkdir -p "$TEST_SSHD_HOME/.config"
ln -s "$HOME/.config/herdr" "$TEST_SSHD_HOME/.config/herdr"
HERDR_BIN="${PADDOCK_HERDR:-/usr/bin/herdr}"
UNSET=(); for v in $(env | grep -o '^HERDR_[A-Za-z_]*'); do UNSET+=(-u "$v"); done
H() { env "${UNSET[@]}" "$HERDR_BIN" --session paddock-test "$@"; }
SOCK="$HOME/.config/herdr/sessions/paddock-test/herdr.sock"
[ -S "$SOCK" ] || { echo "paddock-test herdr session is not running ($SOCK)"; exit 1; }

# --- the fake agent, as `claude`, in a pane of its own ---
AGENT_LOG="$OUT/agent.log"; : >"$AGENT_LOG"
mkdir -p "$OUT/bin"; ln -s "$TOOLS/fake-agent.py" "$OUT/bin/claude"
SRC="ops-e2e-$(date +%s)"
BASE=$(H pane list | jq -r '.result.panes[0].pane_id')
P=$(H pane split "$BASE" --direction right --no-focus | jq -r '.result.pane.pane_id')
H pane run "$P" "FAKE_AGENT_LOG=$AGENT_LOG $OUT/bin/claude" >/dev/null
for _ in $(seq 1 40); do H pane read "$P" --source recent --lines 20 | grep -q "fake agent ready" && break; sleep 0.25; done
H pane report-agent "$P" --source "$SRC" --agent claude --state idle --message '{"kind":"ops-e2e"}' --seq 1 >/dev/null
say "pane $P in paddock-test running the fake agent; $(H --version 2>&1 | head -1)"

cleanup() {
  [ -n "${PROXY_PID:-}" ] && kill "$PROXY_PID" 2>/dev/null
  [ -n "${CUT_PID:-}" ] && kill "$CUT_PID" 2>/dev/null
  H agent send-keys "$P" ctrl+c >/dev/null 2>&1
  H pane release-agent "$P" --source "$SRC" --agent claude --seq 90 >/dev/null 2>&1
  H pane close "$P" >/dev/null 2>&1
  "$TOOLS/test-sshd.sh" stop >/dev/null 2>&1
  rm -rf "$TEST_SSHD_HOME"
}
trap cleanup EXIT

# --- what the host can see ---
count() { [ -s "$AGENT_LOG" ] && jq -s --arg e "$1" '[.[] | select(.event == $e)] | length' "$AGENT_LOG" || echo 0; }
submitted() { [ -s "$AGENT_LOG" ] && jq -r 'select(.event == "submit") | .text' "$AGENT_LOG" || true; }
focused() { H pane get "$P" | jq -r '.result.pane.focused'; }
desktop_back_to_base() { H pane focus --pane "$P" --direction left >/dev/null; }
pane_has() { H pane read "$P" --source recent --lines 80 | grep -q -- "$1"; }
sha() { printf '%s' "$1" | sha256sum | cut -d' ' -f1; }
# --- what is on the phone (a debug build, so run-as reads the app's private files) ---
journal() { $ADB exec-out run-as $PKG cat files/operations.json 2>/dev/null; }
settings() { $ADB exec-out run-as $PKG cat files/settings.json 2>/dev/null; }
rows() { journal | jq -c --arg k "$1" '[.records[] | select(.kind == $k)]'; }   # the rows of one kind, oldest first
snap() { journal >"$OUT/journal-$1.json"; }

"$TOOLS/test-sshd.sh" start >/dev/null && FP="$("$TOOLS/test-sshd.sh" fingerprint | awk '{print $2}')"
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
$ADB shell am instrument -w -e class "$PKG.e2e.OperationsFlowTest#t0_exportAppKey" "$RUNNER" >"$OUT/t0.txt" 2>&1
$ADB pull "/sdcard/Android/data/$PKG/files/app-phone.pub" "$OUT/transport.pub" >/dev/null 2>&1 \
  && "$TOOLS/test-sshd.sh" authorize "$OUT/transport.pub" >/dev/null || { echo "key export failed"; cat "$OUT/t0.txt"; exit 1; }
$ADB shell "rm -rf /sdcard/Android/data/$PKG/files/screens /sdcard/Android/data/$PKG/files/go-* /sdcard/Android/data/$PKG/files/unknown-line.txt"; $ADB logcat -c

instrument() { # test method, output file
  $ADB shell am instrument -w -e hostFp "$FP" -e user "$USER" -e port $PROXY_PORT -e session paddock-test \
    -e class "$PKG.e2e.OperationsFlowTest#$1" "$RUNNER" >"$OUT/$2" 2>&1 &
  INSTR=$!
}
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
instrument_result() { grep -E '^OK|FAILURES|Tests run' "$OUT/$1" | head -2 | tr '\n' ' '; grep -E '^Error in|AssertionError|Process crashed' "$OUT/$1" | sort -u | head -3 | tr '\n' ' '; }
finish() {
  wait $INSTR 2>/dev/null
  say "instrumentation: $(instrument_result "${INSTR_OUT:-instrument1.txt}")"
  rm -rf "$OUT/screens"; $ADB pull "/sdcard/Android/data/$PKG/files/screens" "$OUT/" >/dev/null 2>&1
  $ADB logcat -d -s E2E:I | sed 's/^.* I E2E *: //' >>"$OUT/e2e-log.txt"
  grep -E "UNKNOWN|REREAD|PROGRESS" "$OUT/e2e-log.txt" | tee -a "$LOG"
  say "host totals: esc=$(count esc) ctrl-c=$(count ctrl-c) submissions=$(count submit)"
  [ "$fail" = 0 ] && say "ALL CHECKS PASSED" || say "SOME CHECKS FAILED"
  echo "results in $OUT"
  exit "${1:-$fail}"
}
reach() { wait_at "$1" || { say "FAIL the test never reached '$1'"; fail=1; kill $INSTR 2>/dev/null; $ADB shell am force-stop $PKG; finish 1; }; }
check() { if eval "$2"; then say "PASS $1"; else say "FAIL $1"; fail=1; fi; }
# For a fact the host learns a moment after the phone does: herdr acknowledges a key once it has written it to the pane, and the
# agent logs it when it reads it, so a check made at once can see the count one short (seen on API 26: the Esc was logged 14 ms
# after the check). Retries for up to 3 s. Counts only grow, so a duplicate still fails: it never goes back to the right number.
check_soon() { local n=0; until eval "$2"; do n=$((n+1)); if [ "$n" -ge 15 ]; then say "FAIL $1"; fail=1; return; fi; sleep 0.2; done; say "PASS $1"; }

FIRST="first prompt from the phone"; SECOND="second prompt, cut after the write"; THIRD="third prompt, deliberate"
INSTR_OUT=instrument1.txt
instrument t1_keysFocusAndPromptsThenTheLinkDiesAfterTheWriteLeavingAnUnknownOutcome instrument1.txt

# ---------------- stage 1 ----------------
reach esc-sent
check_soon "AC-06.5 one Esc reached the agent and nothing else did" '[ "$(count esc)" = 1 ] && [ "$(count ctrl-c)" = 0 ] && [ "$(count submit)" = 0 ]'
check_soon "the pane shows the Esc" 'pane_has "\[esc\]"'
check "AC-06.5 the journal has one Esc row, acknowledged, with a send time" '[ "$(rows Esc | jq -r "length, .[0].outcome, (.[0].sentAt != null)" | tr "\n" " ")" = "1 Acknowledged true " ]'
snap esc
go esc-sent

reach focus-asked
check "AC-06.6 the question is on screen and the desktop has not been moved" '[ "$(focused)" = false ] && [ "$(rows Focus | jq length)" = 0 ]'
check "AC-06.6 nothing is remembered before the user agrees" '[ "$(settings | jq -r ".desktopFocusConfirmed // false")" != true ]'
go focus-asked

reach focused
check "AC-06.6 after agreeing the desktop's cursor is on the agent's pane" '[ "$(focused)" = true ]'
check "AC-06.6 one Focus row, acknowledged" '[ "$(rows Focus | jq -r "length, .[0].outcome" | tr "\n" " ")" = "1 Acknowledged " ]'
check "AC-06.6 agreeing is remembered in the settings file" '[ "$(settings | jq -r .desktopFocusConfirmed)" = true ]'
desktop_back_to_base
check "the desktop is back on the other pane, so the next focus is a real one" '[ "$(focused)" = false ]'
go focused

reach focused-again
check "AC-06.6 the second tap went straight through: the pane is focused again and a second Focus row exists" '[ "$(focused)" = true ] && [ "$(rows Focus | jq length)" = 2 ]'
go focused-again

reach prompt-sent
check "AC-06.1 the first prompt arrived once, whole" '[ "$(count submit)" = 1 ] && [ "$(submitted)" = "$FIRST" ]'
check "the pane shows it once" '[ "$(H pane read "$P" --source recent --lines 80 | grep -c "GOT 1: ")" = 1 ]'
check "AC-06.7 with the setting off the journal holds the hash and no text" '[ "$(rows Prompt | jq -r "length, .[0].payloadSha256, (.[0].promptText // \"none\")" | tr "\n" " ")" = "1 $(sha "$FIRST") none " ]'
check "AC-06.7 the prompt text is nowhere in the journal file" '[ "$(journal | grep -c "$FIRST")" = 0 ]'
snap prompt1
go prompt-sent

reach arm-cut
say "arming the cut: delay $(proxy "delay 300"); the cutter waits for submission 2"
python3 "$HERE/cut-after-submit.py" "$AGENT_LOG" 2 $PROXY_CTL 120 >"$OUT/cutter.out" 2>&1 & CUT_PID=$!
check "AC-06.7 Keep prompt text is on in the settings file" '[ "$(settings | jq -r .keepPromptText)" = true ]'
go arm-cut

reach unknown
wait $CUT_PID 2>/dev/null; CUT_PID=""
say "cut: $(cat "$OUT/cutter.out")"
check "AC-06.3 the second prompt did reach the agent, once, before the link was cut" '[ "$(count submit)" = 2 ] && [ "$(submitted | tail -1)" = "$SECOND" ]'
check "the freeze was sent within 50 ms of the logged submission" 'grep -Eo "gap_ms=[0-9]+" "$OUT/cutter.out" | cut -d= -f2 | awk "{exit !(\$1 <= 50)}"'
check "AC-06.3 the journal row is Unknown, not resolved, with the text kept (setting on) and the hash" \
  '[ "$(rows Prompt | jq -r ".[1] | .outcome, (.resolvedAt // \"open\"), .promptText, .payloadSha256" | tr "\n" "|")" = "Unknown|open|$SECOND|$(sha "$SECOND")|" ]'
check "AC-06.7 the first prompt's row still holds no text" '[ "$(rows Prompt | jq -r ".[0].promptText // \"none\"")" = none ]'
snap unknown
say "link back: delay $(proxy "delay 0"), $(proxy thaw); waiting 40 s to see whether anything is sent by itself"
sleep 40
check "AC-06.3 nothing was sent by itself when the link came back (still two submissions, still two prompt rows)" '[ "$(count submit)" = 2 ] && [ "$(rows Prompt | jq length)" = 2 ]'
go unknown

reach restart
check "AC-06.3 the unknown row is still open in the journal file the new process will read" '[ "$(rows Prompt | jq -r ".[1].outcome + \"/\" + (.[1].resolvedAt // \"open\" | tostring)")" = "Unknown/open" ]'
go restart
wait $INSTR 2>/dev/null
say "stage 1: $(instrument_result instrument1.txt)"
$ADB pull "/sdcard/Android/data/$PKG/files/screens" "$OUT/" >/dev/null 2>&1; $ADB logcat -d -s E2E:I | sed 's/^.* I E2E *: //' >"$OUT/e2e-log.txt"
$ADB shell am force-stop $PKG; sleep 1
check "after the kill the unknown row is still open on disk" '[ "$(rows Prompt | jq -r ".[1].outcome + \"/\" + (.[1].resolvedAt // \"open\" | tostring)")" = "Unknown/open" ]'

# ---------------- stage 2: a new process ----------------
$ADB logcat -c
INSTR_OUT=instrument2.txt
instrument t2_afterARestartTheRowIsStillThereAReReadFreesItAndFocusNoLongerAsks instrument2.txt

reach reread
check "AC-06.3 the re-read resolved the row (outcome unchanged, resolved time set) and nothing was sent" \
  '[ "$(rows Prompt | jq -r ".[1].outcome + \"/\" + ((.[1].resolvedAt != null) | tostring)")" = "Unknown/true" ] && [ "$(count submit)" = 2 ] && [ "$(rows Prompt | jq length)" = 2 ]'
snap reread
go reread

reach third-sent
check "AC-06.3 a deliberate third prompt arrived once, and only now" '[ "$(count submit)" = 3 ] && [ "$(submitted | tail -1)" = "$THIRD" ]'
check "the journal reads Acknowledged, Unknown (re-read), Acknowledged" '[ "$(rows Prompt | jq -r "map(.outcome) | join(\",\")")" = "Acknowledged,Unknown,Acknowledged" ]'
go third-sent

reach focus-away
desktop_back_to_base
check "the desktop is on the other pane" '[ "$(focused)" = false ]'
go focus-away

reach focus-back
check "AC-06.6 after a restart focus goes straight through (no question) and the pane is focused" '[ "$(focused)" = true ] && [ "$(rows Focus | jq length)" = 3 ]'
go focus-back

reach composer-esc-on
check "the composer's first Esc turned Manual input on and sent nothing (still one Esc, no other key)" '[ "$(count esc)" = 1 ] && [ "$(count ctrl-c)" = 0 ] && [ "$(count submit)" = 3 ] && [ "$(rows Esc | jq length)" = 1 ]'
go composer-esc-on

reach composer-esc-sent
# The tap and the checkpoint are a moment apart: give the send up to 10 s to be journalled as acknowledged before reading the row.
for _ in $(seq 20); do [ "$(rows Esc | jq -r ".[1].outcome // \"\"")" = Acknowledged ] && break; sleep 0.5; done
check "the composer's second Esc reached the agent once, as a recorded key" '[ "$(count esc)" = 2 ] && [ "$(count ctrl-c)" = 0 ] && [ "$(rows Esc | jq -r "length, .[1].outcome" | tr "\n" " ")" = "2 Acknowledged " ]'
snap composer-esc
go composer-esc-sent

reach ctrl-c
check "AC-06.5 one Ctrl+C reached the agent, and the agent ended on it" '[ "$(count ctrl-c)" = 1 ] && pane_has "\[ctrl-c\]"'
check "the journal's Ctrl+C row is Acknowledged" '[ "$(rows CtrlC | jq -r "length, .[0].outcome" | tr "\n" " ")" = "1 Acknowledged " ]'
check "in all: 2 Esc, 1 Ctrl+C, 3 submissions, no duplicates" '[ "$(count esc)" = 2 ] && [ "$(count ctrl-c)" = 1 ] && [ "$(count submit)" = 3 ]'
snap final
go ctrl-c
reach done
go done
finish
