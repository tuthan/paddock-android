#!/usr/bin/env bash
# Phase 07 stopgap mode on an emulator, through the whole app (AC-07.1, AC-07.3, AC-07.7): the real host relay posts to a loopback
# ntfy stub, the script taps the message's link the way the ntfy app does (a VIEW intent), and the app resolves it against a fresh
# read. Two stages: one process, then a new process started by the tap itself.
#   tools/run-alerts-e2e.sh [adb-serial]
# The test (app/src/androidTest/.../e2e/AlertsFlowTest.kt) drives the UI. This script prepares the host and, at each checkpoint the
# test announces in logcat, does what only the host can do and checks what only the host can see:
#   - tools/fake-agent.py runs as `claude` in a pane of the disposable session `paddock-test` and logs every Esc, Ctrl+C and
#     submission to a file outside the pane, so "an alert never sends input" is a count of zero, not a reading of a screen;
#   - host/paddock-alert-relay.py (the pinned script, unmodified) watches that session and posts to tools/ntfy-stub.py, which records
#     every body, so what an ntfy server (and anyone who can read its log) would have been given can be read back;
#   - `herdr pane report-agent` moves the agent through idle, working and blocked; a second pane is blocked and then closed;
#   - the link is cut by tools/blackhole-proxy.py, so an alert can arrive while the link is down and a reconnect starts a new epoch
#     (read from the ledger on the phone with run-as; a debug build).
# Only `paddock-test` is touched in herdr, on panes this script creates and closes; the sshd is loopback-only with test keys and an
# isolated HOME. Nothing is posted anywhere but the loopback stub.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; ROOT="$HERE/.."
SERIAL="${1:-emulator-5570}"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb -s $SERIAL"
PKG=io.github.tuthan.paddock; RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
OUT="$ROOT/build/alerts-e2e-$(date +%Y%m%d-%H%M%S)-${SERIAL#emulator-}"; mkdir -p "$OUT"
LOG="$OUT/script-log.txt"
say() { printf '%s %s\n' "$(date +%H:%M:%S.%3N)" "$*" | tee -a "$LOG"; }
export TEST_SSHD_RUN="$ROOT/build/e2e-sshd" TEST_SSHD_PORT=2233 TEST_SSHD_HOME="$HOME/.cache/pdk-e2e-home"
PROXY_PORT=2234; PROXY_CTL=2235; STUB_PORT=2290
# FROM_STAGE=3 (or 4) starts at that stage on the state a full run left on the phone: no pm clear, and the sshd keeps its host key, so
# the phone's pinned key still matches. Stages 1 and 2 are skipped.
FROM="${FROM_STAGE:-1}"
[ "$FROM" = 1 ] && rm -rf "$TEST_SSHD_RUN"
rm -rf "$TEST_SSHD_HOME"; mkdir -p "$TEST_SSHD_HOME/.config"
ln -s "$HOME/.config/herdr" "$TEST_SSHD_HOME/.config/herdr"
HERDR_BIN="${PADDOCK_HERDR:-/usr/bin/herdr}"
UNSET=(); for v in $(env | grep -o '^HERDR_[A-Za-z_]*'); do UNSET+=(-u "$v"); done
H() { env "${UNSET[@]}" "$HERDR_BIN" --session paddock-test "$@"; }
SESSION=paddock-test
SOCK="$HOME/.config/herdr/sessions/$SESSION/herdr.sock"
[ -S "$SOCK" ] || { echo "paddock-test herdr session is not running ($SOCK)"; exit 1; }

# --- the fake agent, as `claude`, in a pane of its own ---
AGENT_LOG="$OUT/agent.log"; : >"$AGENT_LOG"
mkdir -p "$OUT/bin"; ln -s "$ROOT/tools/fake-agent.py" "$OUT/bin/claude"
SRC="alerts-e2e-$(date +%s)"; SEQN=1; PB=""
BASE=$(H pane list | jq -r '.result.panes[0].pane_id')
P=$(H pane split "$BASE" --direction right --no-focus | jq -r '.result.pane.pane_id')
H pane run "$P" "FAKE_AGENT_LOG=$AGENT_LOG $OUT/bin/claude" >/dev/null
for _ in $(seq 1 40); do H pane read "$P" --source recent --lines 20 | grep -q "fake agent ready" && break; sleep 0.25; done
rep() { SEQN=$((SEQN + 10)); H pane report-agent "$1" --source "$SRC" --agent claude --state "$2" --seq "$SEQN" >/dev/null; }
rep "$P" idle
TERM_A=$(H pane get "$P" | jq -r '.result.pane.terminal_id')
say "pane $P ($TERM_A) in paddock-test running the fake agent; $(H --version 2>&1 | head -1)"

cleanup() {
  for pid in ${PROXY_PID:-} ${RELAY_PID:-} ${STUB_PID:-}; do kill "$pid" 2>/dev/null; done
  if [ -n "$PB" ]; then SEQN=$((SEQN + 10)); H pane release-agent "$PB" --source "$SRC" --agent claude --seq "$SEQN" >/dev/null 2>&1; H pane close "$PB" >/dev/null 2>&1; fi
  H agent send-keys "$P" ctrl+c >/dev/null 2>&1
  SEQN=$((SEQN + 10)); H pane release-agent "$P" --source "$SRC" --agent claude --seq "$SEQN" >/dev/null 2>&1
  H pane close "$P" >/dev/null 2>&1
  "$HERE/test-sshd.sh" stop >/dev/null 2>&1
  rm -rf "$TEST_SSHD_HOME"
}
trap cleanup EXIT

# --- what the host can see ---
count() { [ -s "$AGENT_LOG" ] && jq -s --arg e "$1" '[.[] | select(.event == $e)] | length' "$AGENT_LOG" || echo 0; }
NTFY_LOG="$OUT/ntfy.log"; : >"$NTFY_LOG"
# The stub's records for one terminal, oldest first, one message body (JSON) per line. Other agents in paddock-test may alert too.
posts() { [ -s "$NTFY_LOG" ] && jq -c --arg t "t=$1&" 'select(((.body | fromjson | .click) // "") | contains($t)) | .body | fromjson' "$NTFY_LOG" || true; }
# The stub's records for one UnifiedPush endpoint path: whole records, oldest first.
up_posts() { [ -s "$NTFY_LOG" ] && jq -c --arg p "$1" 'select(.path == $p)' "$NTFY_LOG" || true; }
npost() { posts "$1" | grep -c . || true; }
wait_posts() { for _ in $(seq 1 100); do [ "$(npost "$1")" -ge "$2" ] && return 0; sleep 0.2; done; return 1; }
click_of() { posts "$1" | sed -n "${2}p" | jq -r .click; }
# --- what is on the phone (a debug build, so run-as reads the app's private files) ---
phone() { $ADB exec-out run-as $PKG cat "files/$1" 2>/dev/null; }
epoch() { phone ledger.json | jq -r --arg h "$PROFILE" --arg s "$SESSION" '[.epochs[] | select(.host == $h and .session == $s) | .installed] | first // 0'; }
# The ntfy app's tap: a VIEW intent for the link, from outside the app.
tap() { $ADB shell "am start -a android.intent.action.VIEW -d '$1' -n $PKG/.MainActivity" >"$OUT/tap-$2.txt" 2>&1; }

"$HERE/test-sshd.sh" start >/dev/null && FP="$("$HERE/test-sshd.sh" fingerprint | awk '{print $2}')"
python3 "$HERE/blackhole-proxy.py" $PROXY_PORT 2233 $PROXY_CTL >"$OUT/proxy.log" 2>&1 & PROXY_PID=$!
python3 "$HERE/ntfy-stub.py" $STUB_PORT "$NTFY_LOG" >"$OUT/stub.log" 2>&1 & STUB_PID=$!
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
[ "$FROM" = 1 ] && $ADB shell pm clear $PKG >/dev/null
$ADB shell am instrument -w -e class "$PKG.e2e.AlertsFlowTest#t0_exportAppKey" "$RUNNER" >"$OUT/t0.txt" 2>&1
$ADB pull "/sdcard/Android/data/$PKG/files/app-phone.pub" "$OUT/transport.pub" >/dev/null 2>&1 \
  && "$HERE/test-sshd.sh" authorize "$OUT/transport.pub" >/dev/null || { echo "key export failed"; cat "$OUT/t0.txt"; exit 1; }
$ADB shell "rm -rf /sdcard/Android/data/$PKG/files/screens /sdcard/Android/data/$PKG/files/go-* /sdcard/Android/data/$PKG/files/link.txt"; $ADB logcat -c

instrument() { # test method, output file, extra instrumentation arguments
  $ADB shell am instrument -w -e hostFp "$FP" -e user "$USER" -e port $PROXY_PORT -e session $SESSION ${3:-} \
    -e class "$PKG.e2e.AlertsFlowTest#$1" "$RUNNER" >"$OUT/$2" 2>&1 &
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
  if [ "${INSTR_OUT:-}" = instrument3.txt ]; then check "stage 3 instrumentation reported no failure" '! grep -q "FAILURES" "$OUT/instrument3.txt" && grep -q "^OK" "$OUT/instrument3.txt"'; fi
  $ADB pull "/sdcard/Android/data/$PKG/files/screens" "$OUT/" >/dev/null 2>&1
  $ADB logcat -d -s E2E:I | sed 's/^.* I E2E *: //' >>"$OUT/e2e-log.txt"
  say "relay: $(grep -c 'lost:' "$OUT/relay.log" 2>/dev/null) lost-stream lines; last: $(tail -1 "$OUT/relay.log" 2>/dev/null)"
  say "host totals: esc=$(count esc) ctrl-c=$(count ctrl-c) submissions=$(count submit)"
  [ "$fail" = 0 ] && say "ALL CHECKS PASSED" || say "SOME CHECKS FAILED"
  echo "results in $OUT"
  exit "${1:-$fail}"
}
reach() { wait_at "$1" || { say "FAIL the test never reached '$1'"; fail=1; kill $INSTR 2>/dev/null; $ADB shell am force-stop $PKG; finish 1; }; }
check() { if eval "$2"; then say "PASS $1"; else say "FAIL $1"; fail=1; fi; }
# For a fact the phone writes a moment after the screen shows it (the ledger's epoch). Retries for up to 5 s.
check_soon() { local n=0; until eval "$2"; do n=$((n+1)); if [ "$n" -ge 25 ]; then say "FAIL $1"; fail=1; return; fi; sleep 0.2; done; say "PASS $1"; }
now() { date +%s; }

if [ "$FROM" -le 1 ]; then
INSTR_OUT=instrument1.txt
instrument t1_aTapOnAnAlertIsResolvedAgainstAFreshReadWhateverHappenedSinceItWasSent instrument1.txt

# ---------------- stage 1 ----------------
reach case-a
PROFILE=$(phone host-profiles.json | jq -r '.profiles[0].id')
check "the phone named its machine by a profile id the relay can be given" '[ -n "$PROFILE" ] && [ "$PROFILE" != null ]'
cat >"$OUT/relay.toml" <<TOML
socket = "$SOCK"
profile = "$PROFILE"
label = "e2e box"
delivery = "ntfy"
debounce_seconds = 1
heartbeat_seconds = 5
[ntfy]
url = "http://127.0.0.1:$STUB_PORT"
topic = "paddock-e2e-$(head -c 9 /dev/urandom | od -An -tx1 | tr -d ' \n')"
TOML
python3 "$ROOT/host/paddock-alert-relay.py" --config "$OUT/relay.toml" >"$OUT/relay.log" 2>&1 & RELAY_PID=$!
for _ in $(seq 1 50); do grep -q "^.*started (version" "$OUT/relay.log" && break; sleep 0.2; done
sleep 3   # the relay's first read is a silent baseline: the agent is idle in it
check "the relay started against paddock-test" 'grep -q "session $SESSION" "$OUT/relay.log"'
check "AC-07.1 the baseline posted nothing" '[ "$(npost "$TERM_A")" = 0 ]'
rep "$P" blocked
check "AC-07.1 the agent becoming blocked posts one message" 'wait_posts "$TERM_A" 1 && sleep 1 && [ "$(npost "$TERM_A")" = 1 ]'
LINK_A=$(click_of "$TERM_A" 1)
SHAPE=$(posts "$TERM_A" | sed -n 1p | jq -r '.title + "|" + .message + "|" + (.tags | join(","))')
check "AC-07.1 the message is generic: a fixed title and text, no agent, prompt or pane text, and no token" \
  '[ "$SHAPE" = "Paddock: attention on e2e box|An agent needs you.|paddock" ] && ! grep -qi "fake agent\|claude\|GOT " "$NTFY_LOG" && [ "$(jq -r .auth "$NTFY_LOG" | sort -u)" = false ]'
ACTIONS=$(posts "$TERM_A" | sed -n 1p | jq -r --arg l "$LINK_A" '.actions | map(.label + "|" + .action + "|" + ((.url == $l) | tostring)) | join(",")')
check "AC-07.7 the one action on the message is a view of the same link as a tap, labelled Review" '[ "$ACTIONS" = "Review|view|true" ]'
check "the link names this phone's profile, the session, this terminal, blocked, and no phone-local state" \
  'case "$LINK_A" in "paddock://open?h=$PROFILE&s=$SESSION&t=$TERM_A&p="*"&st=blocked&at="*"&n="*) ! echo "$LINK_A" | grep -qi "epoch";; *) false;; esac'
say "tapping: $LINK_A"
tap "$LINK_A" a
go case-a

reach case-b
rep "$P" working; sleep 1.5; rep "$P" blocked
check "AC-07.1 working posted nothing; blocked again posts a second message" 'wait_posts "$TERM_A" 2 && sleep 1 && [ "$(npost "$TERM_A")" = 2 ]'
LINK_B=$(click_of "$TERM_A" 2)
rep "$P" working; sleep 1
check "the agent has moved on since that alert was sent" '[ "$(H pane get "$P" | jq -r .result.pane.agent_status)" = working ]'
say "tapping the second alert after the agent moved on"
tap "$LINK_B" b
go case-b

reach case-c
PB=$(H pane split "$P" --direction down --no-focus | jq -r '.result.pane.pane_id')
TERM_C=$(H pane get "$PB" | jq -r '.result.pane.terminal_id')
rep "$PB" idle; sleep 2; rep "$PB" blocked
check "AC-07.1 a second terminal's blocked alert is posted for it alone" 'wait_posts "$TERM_C" 1 && [ "$(npost "$TERM_C")" = 1 ]'
LINK_C=$(click_of "$TERM_C" 1)
SEQN=$((SEQN + 10)); H pane release-agent "$PB" --source "$SRC" --agent claude --seq "$SEQN" >/dev/null; H pane close "$PB" >/dev/null; PB=""
check "that terminal is gone from the session" '! H pane list | jq -e --arg t "$TERM_C" ".result.panes[] | select(.terminal_id == \$t)" >/dev/null'
say "tapping the alert of a terminal that was closed"
tap "$LINK_C" c
go case-c

reach freeze
EPOCH0=$(epoch)
rep "$P" blocked
check "AC-07.1 a third blocked is a third message" 'wait_posts "$TERM_A" 3 && sleep 1 && [ "$(npost "$TERM_A")" = 3 ]'
LINK_D=$(click_of "$TERM_A" 3)
say "epoch before the cut: $EPOCH0; link cut: $(proxy freeze)"
go freeze

reach link-down
say "tapping while the link is down"
tap "$LINK_D" d
sleep 1
go link-down

reach thawed
say "link back: $(proxy thaw)"
go thawed

reach case-e
check_soon "AC-07.3 the reconnect advanced the phone's epoch for this machine" '[ "$(epoch)" -gt "$EPOCH0" ]'
say "epoch after the reconnect: $(epoch)"
tap "paddock://open?h=elsewhere&s=$SESSION&t=term_nowhere&p=w1%3Ap1&st=blocked&at=$(now)&n=1" e
go case-e

reach case-f
tap "paddock://open?h=$PROFILE&s=..%2F..%2Fetc&t=term_x&p=w1%3Ap1&st=blocked&at=$(now)&n=2" f
go case-f

reach done
check "AC-07.7 no tap sent anything to the agent: no Esc, no Ctrl+C, no submission" '[ "$(count esc)" = 0 ] && [ "$(count ctrl-c)" = 0 ] && [ "$(count submit)" = 0 ]'
check "AC-07.1 the relay posted exactly one message per blocked and none for idle or working (agent: 3, closed pane: 1)" '[ "$(npost "$TERM_A")" = 3 ] && [ "$(npost "$TERM_C")" = 1 ]'
check "the relay is still running" 'kill -0 $RELAY_PID'
go done
wait $INSTR 2>/dev/null
say "stage 1: $(instrument_result instrument1.txt)"
check "stage 1 instrumentation reported no failure" '! grep -q "FAILURES" "$OUT/instrument1.txt" && grep -q "^OK" "$OUT/instrument1.txt"'
$ADB pull "/sdcard/Android/data/$PKG/files/screens" "$OUT/" >/dev/null 2>&1; $ADB logcat -d -s E2E:I | sed 's/^.* I E2E *: //' >"$OUT/e2e-log.txt"
cp "$NTFY_LOG" "$OUT/ntfy-stage1.log"
fi

# ---------------- stage 2: a new process, started by the tap ----------------
if [ "$FROM" -le 2 ]; then
$ADB shell am force-stop $PKG; sleep 1
rep "$P" working; sleep 1.5; rep "$P" blocked
check "AC-07.1 a fourth message for the agent that is blocked again" 'wait_posts "$TERM_A" 4 && sleep 1 && [ "$(npost "$TERM_A")" = 4 ]'
LINK_G=$(click_of "$TERM_A" 4)
printf '%s' "$LINK_G" >"$OUT/link.txt"; $ADB push "$OUT/link.txt" "/sdcard/Android/data/$PKG/files/link.txt" >/dev/null 2>&1
$ADB logcat -c
INSTR_OUT=instrument2.txt
instrument t2_aTapOnAnAlertStartsTheAppFromNothingAndItIsResolvedOnceTheHerdIsRead instrument2.txt

reach cold-start
check "AC-07.3 the cold start from the link resolved it against a fresh read and nothing was sent" '[ "$(count esc)" = 0 ] && [ "$(count ctrl-c)" = 0 ] && [ "$(count submit)" = 0 ]'
go cold-start
reach recreated
check "a recreated activity did not run the link again (still one agent screen's worth of nothing sent)" '[ "$(count esc)" = 0 ] && [ "$(count submit)" = 0 ]'
go recreated
wait $INSTR 2>/dev/null
say "stage 2: $(instrument_result instrument2.txt)"
check "stage 2 instrumentation reported no failure" '! grep -q "FAILURES" "$OUT/instrument2.txt" && grep -q "^OK" "$OUT/instrument2.txt"'
$ADB pull "/sdcard/Android/data/$PKG/files/screens" "$OUT/" >/dev/null 2>&1; $ADB logcat -d -s E2E:I | sed 's/^.* I E2E *: //' >>"$OUT/e2e-log.txt"

fi

# ---------------- stage 3: connector mode (UnifiedPush) ----------------
if [ "$FROM" -ge 3 ]; then
  PROFILE=$(phone host-profiles.json | jq -r '.profiles[0].id')
  check "the phone has a machine from the earlier run" '[ -n "$PROFILE" ] && [ "$PROFILE" != null ]'
  cat >"$OUT/relay.toml" <<TOML
socket = "$SOCK"
profile = "$PROFILE"
label = "e2e box"
delivery = "ntfy"
debounce_seconds = 1
heartbeat_seconds = 5
[ntfy]
url = "http://127.0.0.1:$STUB_PORT"
topic = "paddock-e2e-restart"
TOML
  RELAY_PID=""
fi
# The relay is told to deliver to a UnifiedPush endpoint the phone sends it. The "distributor" is the test's own, which gives the
# phone the stub's address; the script plays the push server by handing the relay's post to the phone as the distributor would.
PUSH_FILE="$TEST_SSHD_HOME/.config/paddock/push-endpoint.json"
PUSH_PATH="/up-e2e-$(head -c 9 /dev/urandom | od -An -tx1 | tr -d ' \n')"; PUSH_ENDPOINT="http://127.0.0.1:$STUB_PORT$PUSH_PATH"
[ ! -e "$PUSH_FILE" ] && say "no address file on the host yet"
$ADB logcat -c
INSTR_OUT=instrument3.txt
instrument t3_aPushFromTheRelayRaisesOneGenericNotificationThatOpensTheMachinesHerd instrument3.txt "-e pushEndpoint $PUSH_ENDPOINT"

reach push-sent
check "AC-07 the address file is on the host, owner-only, and holds exactly the address the distributor gave" \
  '[ -f "$PUSH_FILE" ] && [ "$(stat -c %a "$PUSH_FILE")" = 600 ] && [ "$(jq -r .endpoint "$PUSH_FILE")" = "$PUSH_ENDPOINT" ] && [ "$(jq -r "keys | join(\",\")" "$PUSH_FILE")" = endpoint ]'
check "nothing is left beside it (the temporary file was moved into place)" '[ "$(ls "$(dirname "$PUSH_FILE")" | grep -c "push-endpoint")" = 1 ]'
[ -n "${RELAY_PID:-}" ] && { kill $RELAY_PID 2>/dev/null; wait $RELAY_PID 2>/dev/null; }
sed -e 's/^delivery = .*/delivery = "unifiedpush"/' -e '/^\[ntfy\]/,$d' "$OUT/relay.toml" >"$OUT/relay-up.toml"
printf '[unifiedpush]\nendpoint_file = "%s"\n' "$PUSH_FILE" >>"$OUT/relay-up.toml"
python3 "$ROOT/host/paddock-alert-relay.py" --config "$OUT/relay-up.toml" >"$OUT/relay-up.log" 2>&1 & RELAY_PID=$!
for _ in $(seq 1 50); do grep -q "started (version" "$OUT/relay-up.log" && break; sleep 0.2; done
sleep 3
check "the relay started in UnifiedPush mode" 'grep -q "delivery unifiedpush" "$OUT/relay-up.log"'
go push-sent

reach push-posted
rep "$P" working; sleep 1.5
T0=$(date +%s.%N); rep "$P" blocked
for _ in $(seq 1 100); do [ "$(up_posts "$PUSH_PATH" | grep -c .)" -ge 1 ] && break; sleep 0.1; done
check "AC-07.1 one post reached the address, and none for the other states" '[ "$(up_posts "$PUSH_PATH" | grep -c .)" = 1 ]'
PUSH_BODY=$(up_posts "$PUSH_PATH" | head -1 | jq -r .body)
check "the push holds a version, the machine id and a nonce, and nothing else: no agent, state, terminal, title or text" \
  '[ "$(echo "$PUSH_BODY" | jq -r "keys | join(\",\")")" = "h,n,v" ] && [ "$(echo "$PUSH_BODY" | jq -r .h)" = "$PROFILE" ] && [ "$(echo "$PUSH_BODY" | jq -r .v)" = 1 ] && echo "$PUSH_BODY" | jq -e ".n | test(\"^[0-9a-f]{16}\$\")" >/dev/null && ! echo "$PUSH_BODY" | grep -qi "term_\|blocked\|claude\|fake agent"'
check "the post carried no credentials" '[ "$(up_posts "$PUSH_PATH" | head -1 | jq -r .auth)" = false ]'
LAT=$(python3 - "$T0" "$(up_posts "$PUSH_PATH" | head -1 | jq -r .t)" <<'PY'
import sys
print(round((float(sys.argv[2]) - float(sys.argv[1])) * 1000))
PY
)
say "relay latency, herdr transition to the push reaching its address: ${LAT} ms (one sample; the measurement run has the table)"
printf '%s' "$PUSH_BODY" >"$OUT/push-body.json"; $ADB push "$OUT/push-body.json" "/sdcard/Android/data/$PKG/files/push-body.json" >/dev/null 2>&1
go push-posted

reach push-notified
$ADB logcat -d -s E2E:I | grep PUSHNOTIFICATION | sed 's/^.* I E2E *: //' | tee -a "$LOG"
check "AC-07.4 the push notification's words are the generic ones and name no agent" '$ADB logcat -d -s E2E:I | grep PUSHNOTIFICATION | grep -q "Paddock: attention on 10.0.2.2 | An agent needs your attention."'
check "AC-07.7 nothing was sent to the agent by the push" '[ "$(count esc)" = 0 ] && [ "$(count ctrl-c)" = 0 ] && [ "$(count submit)" = 0 ]'
go push-notified

reach push-tapped
check "AC-07.7 nor by the tap on the notification" '[ "$(count esc)" = 0 ] && [ "$(count ctrl-c)" = 0 ] && [ "$(count submit)" = 0 ]'
go push-tapped

reach push-removed
check "unregistering removed the address file from the host" '[ ! -e "$PUSH_FILE" ]'
go push-removed
wait $INSTR 2>/dev/null
say "stage 3: $(instrument_result instrument3.txt)"
check "stage 3 instrumentation reported no failure" '! grep -q "FAILURES" "$OUT/instrument3.txt" && grep -q "^OK" "$OUT/instrument3.txt"'
$ADB pull "/sdcard/Android/data/$PKG/files/screens" "$OUT/" >/dev/null 2>&1; $ADB logcat -d -s E2E:I | sed 's/^.* I E2E *: //' >>"$OUT/e2e-log.txt"

# ---------------- stage 4: how long a tap takes to become a verdict (warm, live, loopback) ----------------
$ADB logcat -c
rep "$P" working; sleep 1.5; rep "$P" blocked; sleep 1
INSTR_OUT=instrument4.txt
instrument t4_aTapOnALiveBlockerBecomesAVerdictInTheTimeRecorded instrument4.txt "-e terminal $TERM_A -e profile $PROFILE"
wait $INSTR 2>/dev/null
$ADB logcat -d -s E2E:I | sed 's/^.* I E2E *: //' >>"$OUT/e2e-log.txt"
TAPS=$(grep -h "^TAPLATENCY [0-9]" "$OUT/e2e-log.txt" | awk '{print $2}' | sort -n | tr '\n' ' ')
say "tap to verdict, ms, 10 taps on a warm live connection over loopback: $TAPS"
python3 - $TAPS <<'PY' | tee -a "$LOG"
import statistics, sys
v = sorted(int(x) for x in sys.argv[1:])
if v: print("tap to verdict: n=%d median=%d ms p95=%d ms max=%d ms" % (len(v), statistics.median(v), v[min(len(v) - 1, round(0.95 * (len(v) - 1)))], v[-1]))
PY
check "stage 4 instrumentation reported no failure" '! grep -q "FAILURES" "$OUT/instrument4.txt" && grep -q "^OK" "$OUT/instrument4.txt"'
check "ten taps were timed" '[ "$(grep -hc "^TAPLATENCY [0-9]" "$OUT/e2e-log.txt")" = 10 ]'
check "no tap sent anything to the agent, in all four stages" '[ "$(count esc)" = 0 ] && [ "$(count ctrl-c)" = 0 ] && [ "$(count submit)" = 0 ]'
finish
