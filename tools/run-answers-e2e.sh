#!/usr/bin/env bash
# Phase 08 on an emulator, through the whole app: Settings > Guarded answers installs the two pinned scripts, then a blocked agent's
# permission requests are answered Yes and No from the phone, a newer request is offered but never swapped in, a cut input and an
# expired request leave both buttons off, and an answer whose reply is lost is shown as unknown and settled by reading the files.
#   tools/run-answers-e2e.sh [adb-serial]
# The test (app/src/androidTest/.../e2e/AnswersFlowTest.kt) drives the UI. This script prepares the host and, at each checkpoint the
# test announces in logcat, checks what only the host can see and releases the test with a file:
#   - tools/fake-agent.py runs as `claude` in a pane of the disposable session `paddock-test` and logs every Esc, Ctrl+C and
#     submission outside the pane, so "no key was sent" is a count; `herdr pane report-agent` moves it between idle and blocked;
#   - the real hook (the copy the app installed, so the file under test is the file that shipped) is run the way Claude Code runs it:
#     a PermissionRequest on stdin and herdr's environment for that pane, with its configuration in a scratch XDG_CONFIG_HOME;
#   - the request files under $XDG_RUNTIME_DIR/paddock/paddock-test/<pane>/ say what became of each request, and what the hook printed
#     is read from its stdout;
#   - the journal on the phone is read with run-as (debug build): outcomes and request ids;
#   - the link is cut by tools/blackhole-proxy.py: 300 ms of latency from the host so the reply to the answer is still on its way
#     when tools/cut-after-file.py freezes the link the instant the decision file exists. The held reply is discarded, so the phone
#     sees what a dead link after the write looks like: a write that landed and no word of it.
# Only `paddock-test` is touched in herdr, on a pane this script creates and closes; the sshd is loopback-only with test keys and an
# isolated HOME; the real ~/.claude and ~/.config/paddock are never read or written.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; . "$HERE/harness.sh"
SERIAL="${1:-emulator-5570}"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb -s $SERIAL"
PKG=io.github.tuthan.paddock; RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
OUT="$OUT_BASE/answers-e2e-$(date +%Y%m%d-%H%M%S)-${SERIAL#emulator-}"; mkdir -p "$OUT"
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
SRC="answers-e2e-$(date +%s)"; SEQN=0
BASE=$(H pane list | jq -r '.result.panes[0].pane_id')
P=$(H pane split "$BASE" --direction right --no-focus | jq -r '.result.pane.pane_id')
H pane run "$P" "FAKE_AGENT_LOG=$AGENT_LOG $OUT/bin/claude" >/dev/null
for _ in $(seq 1 40); do H pane read "$P" --source recent --lines 20 | grep -q "fake agent ready" && break; sleep 0.25; done
rep() { SEQN=$((SEQN + 10)); H pane report-agent "$P" --source "$SRC" --agent claude --state "$1" --seq "$SEQN" >/dev/null; }
rep idle
say "pane $P in paddock-test running the fake agent; $(H --version 2>&1 | head -1)"
RT="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/paddock/paddock-test/$P"

cleanup() {
  [ -n "${PROXY_PID:-}" ] && kill "$PROXY_PID" 2>/dev/null
  [ -n "${CUT_PID:-}" ] && kill "$CUT_PID" 2>/dev/null
  for f in "$OUT"/hook-*.pid; do [ -e "$f" ] && kill "$(cat "$f")" 2>/dev/null; done
  H agent send-keys "$P" ctrl+c >/dev/null 2>&1
  H pane release-agent "$P" --source "$SRC" --agent claude --seq 9000 >/dev/null 2>&1
  H pane close "$P" >/dev/null 2>&1
  "$TOOLS/test-sshd.sh" stop >/dev/null 2>&1
  rm -rf "$TEST_SSHD_HOME" "$RT"
}
trap cleanup EXIT

# --- what the host can see ---
count() { [ -s "$AGENT_LOG" ] && jq -s --arg e "$1" '[.[] | select(.event == $e)] | length' "$AGENT_LOG" || echo 0; }
keys_sent() { echo "esc=$(count esc) ctrl-c=$(count ctrl-c) submissions=$(count submit)"; }
journal() { $ADB exec-out run-as $PKG cat files/operations.json 2>/dev/null; }
rows() { journal | jq -c --arg k "$1" '[.records[] | select(.kind == $k)]'; }
snap() { journal >"$OUT/journal-$1.json"; }
INSTALLED="$TEST_SSHD_HOME/.local/share/paddock"
HOOK="$INSTALLED/paddock-claude-permission-hook.py"
pin() { jq -r --arg f "host/$1" '.files[$f]' "$ROOT/host/SOURCE.json" | sed 's/^sha256://'; }

# the two hook configurations: a long window for the flows and a short one for the expiry
for w in long:120 short:25; do
  d="$OUT/cfg-${w%%:*}/paddock"; mkdir -p "$d"; printf 'window_seconds = %s\n' "${w##*:}" >"$d/hook.toml"; chmod 600 "$d/hook.toml"
done
mkinput() { # name command description [padding]
  python3 - "$OUT/in-$1.json" "$2" "$3" "${4:-0}" <<'PY'
import json, sys
path, command, description, pad = sys.argv[1:5]
tool_input = {"command": command + (" " + "x" * int(pad) if int(pad) else ""), "description": description}
json.dump({"session_id": "e2e-session-1", "transcript_path": "/tmp/none", "cwd": "/tmp", "permission_mode": "default",
           "hook_event_name": "PermissionRequest", "tool_name": "Bash", "tool_input": tool_input}, open(path, "w"))
PY
}
start_hook() { # name cfg
  env "${UNSET[@]}" HERDR_ENV=1 HERDR_PANE_ID="$P" HERDR_SOCKET_PATH="$SOCK" XDG_CONFIG_HOME="$OUT/cfg-$2" \
    python3 "$HOOK" <"$OUT/in-$1.json" >"$OUT/hook-$1.out" 2>"$OUT/hook-$1.err" &
  echo $! >"$OUT/hook-$1.pid"
}
hook_gone() { ! kill -0 "$(cat "$OUT/hook-$1.pid")" 2>/dev/null; }
wait_hook_gone() { for _ in $(seq 1 100); do hook_gone "$1" && return 0; sleep 0.1; done; return 1; }
behavior() { jq -r '.hookSpecificOutput.decision.behavior // empty' "$OUT/hook-$1.out" 2>/dev/null; }
req_by() { local f; for f in $(ls -t "$RT"/*.json 2>/dev/null); do case "$f" in *.decision.json) continue;; esac; grep -q -- "$1" "$f" && { basename "$f" | cut -d. -f1; return; }; done; }
wait_req() { local id; for _ in $(seq 1 60); do id=$(req_by "$1"); [ -n "$id" ] && { echo "$id"; return 0; }; sleep 0.2; done; return 1; }
req_state() { local s; for s in pending claimed consumed expired; do [ -e "$RT/$1.$s.json" ] && { echo $s; return; }; done; echo none; }
decision_of() { jq -r '.behavior' "$RT/$1.decision.json" 2>/dev/null; }

"$TOOLS/test-sshd.sh" start >/dev/null && FP="$("$TOOLS/test-sshd.sh" fingerprint | awk '{print $2}')"
python3 "$HERE/blackhole-proxy.py" $PROXY_PORT 2233 $PROXY_CTL >"$OUT/proxy.log" 2>&1 & PROXY_PID=$!
sleep 1
proxy() { python3 - "$1" <<'PY'
import socket, sys
s = socket.create_connection(("127.0.0.1", 2235), timeout=3); s.sendall((sys.argv[1] + "\n").encode()); print(s.makefile().readline().strip())
PY
}

export JAVA_HOME=$HOME/.local/share/mise/installs/java/temurin-17.0.20+8; export PATH=$JAVA_HOME/bin:$PATH ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
(cd "$ROOT" && ./gradlew --no-daemon --console=plain ${GRADLE_EXTRA:-} :app:assembleDebug :app:assembleDebugAndroidTest >"$OUT/build.log" 2>&1) || { echo "build failed: $OUT/build.log"; exit 1; }
$ADB install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
$ADB install -r "$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
$ADB shell pm clear $PKG >/dev/null
$ADB shell am instrument -w -e class "$PKG.e2e.AnswersFlowTest#t0_exportAppKey" "$RUNNER" >"$OUT/t0.txt" 2>&1
$ADB pull "/sdcard/Android/data/$PKG/files/app-phone.pub" "$OUT/transport.pub" >/dev/null 2>&1 \
  && "$TOOLS/test-sshd.sh" authorize "$OUT/transport.pub" >/dev/null || { echo "key export failed"; cat "$OUT/t0.txt"; exit 1; }
$ADB shell "rm -rf /sdcard/Android/data/$PKG/files/screens /sdcard/Android/data/$PKG/files/go-*"; $ADB logcat -c

$ADB shell am instrument -w -e hostFp "$FP" -e user "$USER" -e port $PROXY_PORT -e session paddock-test \
  -e class "$PKG.e2e.AnswersFlowTest#t1_answersThroughTheApp" "$RUNNER" >"$OUT/instrument.txt" 2>&1 &
INSTR=$!
wait_at() {
  for _ in $(seq 1 900); do
    $ADB logcat -d -s E2E:I | grep -q "AT $1\$" && return 0
    kill -0 $INSTR 2>/dev/null || return 1
    sleep 0.5
  done
  return 1
}
go() { : >"$OUT/go-$1"; $ADB push "$OUT/go-$1" "/sdcard/Android/data/$PKG/files/go-$1" >/dev/null 2>&1; }
fail=0
instrument_result() { grep -E '^OK|FAILURES|Tests run' "$OUT/instrument.txt" | head -2 | tr '\n' ' '; grep -E '^Error in|AssertionError|Process crashed' "$OUT/instrument.txt" | sort -u | head -3 | tr '\n' ' '; }
finish() {
  wait $INSTR 2>/dev/null
  say "instrumentation: $(instrument_result)"
  rm -rf "$OUT/screens"; $ADB pull "/sdcard/Android/data/$PKG/files/screens" "$OUT/" >/dev/null 2>&1
  $ADB logcat -d -s E2E:I | sed 's/^.* I E2E *: //' >"$OUT/e2e-log.txt"
  grep -E "SETUP|SHEET|UNKNOWN|SETTLED" "$OUT/e2e-log.txt" | tee -a "$LOG"
  say "host totals: $(keys_sent)"
  [ "$fail" = 0 ] && say "ALL CHECKS PASSED" || say "SOME CHECKS FAILED"
  echo "results in $OUT"
  exit "${1:-$fail}"
}
reach() { wait_at "$1" || { say "FAIL the test never reached '$1'"; fail=1; kill $INSTR 2>/dev/null; $ADB shell am force-stop $PKG; finish 1; }; }
check() { if eval "$2"; then say "PASS $1"; else say "FAIL $1"; fail=1; fi; }
check_soon() { local n=0; until eval "$2"; do n=$((n+1)); if [ "$n" -ge 25 ]; then say "FAIL $1"; fail=1; return; fi; sleep 0.2; done; say "PASS $1"; }

# ---------------- setup: nothing is written before the user agrees ----------------
reach setup-shown
check "AC-08.5 nothing of Paddock's is on the host before the install is agreed" '[ ! -e "$INSTALLED/paddock-decide.py" ] && [ ! -e "$HOOK" ]'
go setup-shown
reach install-asked
check "the confirmation is on screen and still nothing is written" '[ ! -e "$INSTALLED/paddock-decide.py" ] && [ ! -e "$HOOK" ]'
go install-asked
reach installed
check_soon "AC-08.5 the installed writer is the pinned file, byte for byte" '[ "$(sha256sum "$INSTALLED/paddock-decide.py" | cut -d" " -f1)" = "$(pin paddock-decide.py)" ]'
check_soon "AC-08.5 the installed hook is the pinned file, byte for byte" '[ "$(sha256sum "$HOOK" | cut -d" " -f1)" = "$(pin paddock-claude-permission-hook.py)" ]'
check "both scripts are owner-only (mode 600)" '[ "$(stat -c %a "$INSTALLED/paddock-decide.py" "$HOOK" | sort -u | tr "\n" " ")" = "600 " ]'
check "the app did not register the hook, write its configuration or touch Claude Code's settings" '[ ! -e "$TEST_SSHD_HOME/.claude" ] && [ ! -e "$TEST_SSHD_HOME/.config/paddock" ]'
go installed

# ---------------- Yes: bound to the request that was shown ----------------
reach ready-for-yes
rep blocked
mkinput one "echo e2e-one" "e2e description one"; start_hook one long
ID1=$(wait_req e2e-one); say "request one is $ID1"
check "a pending request exists for the pane" '[ "$(req_state "$ID1")" = pending ]'
go ready-for-yes
reach yes-tapped
check "AC-08.1 the hook ended and printed an allow for Claude Code" 'wait_hook_gone one && [ "$(behavior one)" = allow ]'
check "AC-08.1 the printed answer has exactly the hook-output shape and nothing else (no updatedInput, no rule)" '[ "$(jq -c . "$OUT/hook-one.out")" = "{\"hookSpecificOutput\":{\"hookEventName\":\"PermissionRequest\",\"decision\":{\"behavior\":\"allow\"}}}" ]'
check "the request ended consumed and its decision file names the same id" '[ "$(req_state "$ID1")" = consumed ] && [ "$(jq -r .request_id "$RT/$ID1.decision.json")" = "$ID1" ] && [ "$(decision_of "$ID1")" = allow ]'
check "AC-08.1 the journal has one Yes row, acknowledged, carrying that request id" '[ "$(rows Allow | jq -r "length, .[0].outcome, .[0].requestId" | tr "\n" " ")" = "1 Acknowledged $ID1 " ]'
check "AC-08.3 no key was sent to the agent: no Esc, no Ctrl+C, no submission" '[ "$(keys_sent)" = "esc=0 ctrl-c=0 submissions=0" ]'
snap yes
rep idle
go yes-tapped

# ---------------- No ----------------
reach ready-for-no
rep blocked
mkinput two "echo e2e-two" "e2e description two"; start_hook two long
ID2=$(wait_req e2e-two); say "request two is $ID2"
go ready-for-no
reach no-tapped
check "AC-08.1 the hook ended and printed a deny" 'wait_hook_gone two && [ "$(behavior two)" = deny ]'
check "the request ended consumed with a deny decision bound to its id" '[ "$(req_state "$ID2")" = consumed ] && [ "$(jq -r .request_id "$RT/$ID2.decision.json")" = "$ID2" ] && [ "$(decision_of "$ID2")" = deny ]'
check "the journal has one No row (acknowledged, that id) and still one Yes row" '[ "$(rows Deny | jq -r "length, .[0].outcome, .[0].requestId" | tr "\n" " ")" = "1 Acknowledged $ID2 " ] && [ "$(rows Allow | jq length)" = 1 ]'
check "no key was sent" '[ "$(keys_sent)" = "esc=0 ctrl-c=0 submissions=0" ]'
snap no
rep idle
go no-tapped

# ---------------- a newer request of the same session expires the older one ----------------
reach ready-for-replace
rep blocked
mkinput old "echo e2e-three-old" "the old one"; start_hook old long
IDO=$(wait_req e2e-three-old); say "old request is $IDO"
go ready-for-replace
reach old-shown
sleep 1
mkinput new "echo e2e-three-new" "the new one"; start_hook new long
IDN=$(wait_req e2e-three-new); say "new request is $IDN"
check_soon "AC-08.9 the newer request expired the older one at once, without the older hook waiting out its window" '[ "$(req_state "$IDO")" = expired ] && [ "$(req_state "$IDN")" = pending ]'
check "the older hook left by itself and printed nothing: the desktop dialog it belonged to is gone" 'wait_hook_gone old && [ -z "$(behavior old)" ]'
go old-shown
reach replace-answered
check "AC-08.1 the Yes bound to the new request: it was consumed with an allow" 'wait_hook_gone new && [ "$(behavior new)" = allow ] && [ "$(req_state "$IDN")" = consumed ]'
check "AC-08.9 the old request was never answered by the phone: expired, no decision file, nothing printed" '[ "$(req_state "$IDO")" = expired ] && [ ! -e "$RT/$IDO.decision.json" ] && [ -z "$(behavior old)" ]'
check "the journal's last Yes row carries the new id, not the old one" '[ "$(rows Allow | jq -r "length, .[1].outcome, .[1].requestId" | tr "\n" " ")" = "2 Acknowledged $IDN " ]'
snap replace
rep idle
go replace-answered

# ---------------- an input cut by the hook ----------------
reach ready-for-cut
rep blocked
mkinput cut "echo e2e-cut" "the cut one" 300000; start_hook cut long
IDC=$(wait_req e2e-cut); say "cut request is $IDC ($(stat -c %s "$RT/$IDC.pending.json") bytes)"
go ready-for-cut
reach cut-shown
check "AC-08.6 the hook cut the input and said so in the request" '[ "$(jq -r .truncated "$RT/$IDC.pending.json")" = true ] && [ "$(jq -r ".tool_input | type" "$RT/$IDC.pending.json")" = string ]'
check "AC-08.6 nothing was answered for the cut request: pending, no decision file, journal unchanged (2 Yes, 1 No)" '[ "$(req_state "$IDC")" = pending ] && [ ! -e "$RT/$IDC.decision.json" ] && [ "$(rows Allow | jq length)" = 2 ] && [ "$(rows Deny | jq length)" = 1 ]'
kill "$(cat "$OUT/hook-cut.pid")" 2>/dev/null; wait_hook_gone cut
rep idle
go cut-shown

# ---------------- an expired request ----------------
reach ready-for-expiry
rep blocked
mkinput expiry "echo e2e-expiry" "the expiring one"; start_hook expiry short
IDE=$(wait_req e2e-expiry); say "expiring request is $IDE (25 s window)"
go ready-for-expiry
reach expired-shown
check "the hook ended without printing anything: the desktop dialog is what is left" 'wait_hook_gone expiry && [ ! -s "$OUT/hook-expiry.out" ]'
check "the request ended expired, no decision, journal unchanged" '[ "$(req_state "$IDE")" = expired ] && [ ! -e "$RT/$IDE.decision.json" ] && [ "$(rows Allow | jq length)" = 2 ] && [ "$(rows Deny | jq length)" = 1 ]'
rep idle
go expired-shown

# ---------------- an answer whose reply is lost ----------------
reach ready-for-cut-link
rep blocked
mkinput seven "echo e2e-seven" "the unknown one"; start_hook seven long
ID7=$(wait_req e2e-seven); say "request seven is $ID7"
go ready-for-cut-link
reach arm-cut
say "arming the cut: delay $(proxy "delay 300"); the cutter waits for $ID7.decision.json"
python3 "$HERE/cut-after-file.py" "$RT/$ID7.decision.json" $PROXY_CTL 120 >"$OUT/cutter.out" 2>&1 & CUT_PID=$!
go arm-cut
reach unknown
wait $CUT_PID 2>/dev/null; CUT_PID=""
say "cut: $(cat "$OUT/cutter.out")"
check "the decision did land on the host before the link was cut, bound to the right id" '[ "$(decision_of "$ID7")" = allow ] && [ "$(jq -r .request_id "$RT/$ID7.decision.json")" = "$ID7" ]'
check "the freeze was sent within 50 ms of the decision file appearing" 'grep -Eo "gap_ms=-?[0-9]+" "$OUT/cutter.out" | cut -d= -f2 | awk "{exit !(\$1 <= 50)}"'
check "the hook took the answer and printed it (the phone could not know)" 'wait_hook_gone seven && [ "$(behavior seven)" = allow ] && [ "$(req_state "$ID7")" = consumed ]'
check "the journal row is Unknown and still open" '[ "$(rows Allow | jq -r ".[2] | .outcome, (.resolvedAt // \"open\"), .requestId" | tr "\n" " ")" = "Unknown open $ID7 " ]'
snap unknown
say "link back: delay $(proxy "delay 0"), $(proxy thaw); waiting 20 s to see whether anything is sent by itself"
sleep 20
check "nothing was sent by itself when the link came back (still one decision file for $ID7, still three Yes rows)" '[ "$(ls "$RT"/$ID7.decision.json | wc -l)" = 1 ] && [ "$(rows Allow | jq length)" = 3 ]'
go unknown
reach settled
check "the read of the host's files freed the row (outcome unchanged, resolved time set) and sent nothing" '[ "$(rows Allow | jq -r ".[2] | .outcome + \"/\" + ((.resolvedAt != null) | tostring)")" = "Unknown/true" ] && [ "$(rows Allow | jq length)" = 3 ] && [ "$(ls "$RT"/$ID7.decision.json | wc -l)" = 1 ]'
check "AC-08.3 in all, no key reached the agent" '[ "$(keys_sent)" = "esc=0 ctrl-c=0 submissions=0" ]'
snap settled
go settled
reach done
go done
finish
