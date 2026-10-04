#!/usr/bin/env bash
# Phase 09 on an emulator, through the whole app over SSH to a herdr of its own: the Spaces screen (sessions, saved layout, Delete and Stop
# with their dialogs), starting an agent in a tab and in a worktree, a duplicate name's recovery card, closing what a saga created, rename
# and focus from a herd row, and Activity.
#   tools/run-spaces-e2e.sh [adb-serial]
# The test (app/src/androidTest/.../e2e/SpacesFlowTest.kt) drives the UI. This script prepares the host and, at each checkpoint the test
# announces in logcat, checks what only the host can see and releases the test with a file. Everything is in an isolated HOME
# (/tmp/pdk-s9, short because unix socket paths are): its own herdr sessions (herdr reads XDG_CONFIG_HOME before HOME, and a pane of the
# developer's herdr exports HERDR_SOCKET_PATH; both are overridden), `claude` stand-ins (tools/fake-agent.py) first on the PATH, and the
# throwaway loopback sshd. The developer's `default` and `paddock-test` sessions are never addressed.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; . "$HERE/harness.sh"
SERIAL="${1:-emulator-5570}"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb -s $SERIAL"
PKG=io.github.tuthan.paddock; RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
OUT="$OUT_BASE/spaces-e2e-$(date +%Y%m%d-%H%M%S)-${SERIAL#emulator-}"; mkdir -p "$OUT"
LOG="$OUT/script-log.txt"
say() { printf '%s %s\n' "$(date +%H:%M:%S.%3N)" "$*" | tee -a "$LOG"; }

HH=/tmp/pdk-s9; RUNNING=paddock-test-e2e9; STOPPED=paddock-test-e2e9b
export TEST_SSHD_RUN="$OUT_BASE/e2e-sshd-s9" TEST_SSHD_PORT=2233 TEST_SSHD_HOME="$HH"
rm -rf "$TEST_SSHD_RUN" "$HH"; mkdir -p "$HH/bin" "$HH/.local/bin" "$HH/work" "$HH/repo" "$HH/notes"
ln -s "$TOOLS/fake-agent.py" "$HH/bin/claude"; ln -s "$TOOLS/fake-agent.py" "$HH/.local/bin/claude"
HERDR_BIN="${PADDOCK_HERDR:-/usr/bin/herdr}"
UNSET=(); for v in $(env | grep -o '^HERDR_[A-Za-z_]*'); do UNSET+=(-u "$v"); done
HENV=(env "${UNSET[@]}" HOME="$HH" XDG_CONFIG_HOME="$HH/.config" XDG_DATA_HOME="$HH/.local/share" XDG_STATE_HOME="$HH/.local/state" PATH="$HH/bin:$PATH")
H() { "${HENV[@]}" "$HERDR_BIN" --session "$RUNNING" "$@"; }
HS() { local s="$1"; shift; "${HENV[@]}" "$HERDR_BIN" --session "$s" "$@"; }
sessions() { "${HENV[@]}" "$HERDR_BIN" session list --json | jq -c '[.sessions[] | {name, running}]'; }
(cd "$HH/repo" && git init -q -b main && git -c user.email=t@t -c user.name=t commit -q --allow-empty -m init)
start_server() { # session, cwd
  (cd "$HH/work" && "${HENV[@]}" HERDR_SOCKET_PATH="$HH/.config/herdr/sessions/$1/herdr.sock" setsid "$HERDR_BIN" --session "$1" server >"$OUT/server-$1.log" 2>&1 </dev/null &)
  for _ in $(seq 40); do HS "$1" status >/dev/null 2>&1 && break; sleep 0.5; done
  HS "$1" status >/dev/null 2>&1 || { echo "the disposable herdr $1 did not come up"; exit 4; }
}
start_server "$STOPPED"
HS "$STOPPED" workspace create --cwd "$HH/notes" --label notes --no-focus >/dev/null
sleep 1; HS "$STOPPED" status >/dev/null
"${HENV[@]}" "$HERDR_BIN" session stop "$STOPPED" --json >/dev/null
start_server "$RUNNING"
H workspace create --cwd "$HH/repo" --label repo --no-focus >/dev/null
for w in $(H workspace list | jq -r '.result.workspaces[]|select(.label!="repo")|.workspace_id'); do H workspace close "$w" >/dev/null; done
REPO_WS=$(H workspace list | jq -r '.result.workspaces[0].workspace_id')
say "isolated herdr: $(H --version 2>&1 | head -1); sessions $(sessions)"

cleanup() {
  [ -n "${PROXY_PID:-}" ] && kill "$PROXY_PID" 2>/dev/null
  for s in "$RUNNING" "$STOPPED"; do "${HENV[@]}" "$HERDR_BIN" session stop "$s" --json >/dev/null 2>&1; "${HENV[@]}" "$HERDR_BIN" session delete "$s" --json >/dev/null 2>&1; done
  "$TOOLS/test-sshd.sh" stop >/dev/null 2>&1
  [[ "$HH" == /tmp/pdk-s9 ]] && rm -rf -- "$HH"
}
trap cleanup EXIT

# --- what is on the phone (a debug build, so run-as reads the app's private files) ---
# A file the app has not written yet reads as empty, not as an error message (run-as prints one on stdout).
store() { local t; t="$($ADB exec-out run-as $PKG cat "files/$1" 2>/dev/null)"; if printf %s "$t" | jq -e . >/dev/null 2>&1; then printf %s "$t"; else echo "$2"; fi; }
journal() { store operations.json '{"records":[]}'; }
sagas() { store sagas.json '{"records":[]}'; }
rows() { journal | jq -c --arg k "$1" '[.records[] | select(.kind == $k)]'; }
snap() { journal >"$OUT/journal-$1.json"; sagas >"$OUT/sagas-$1.json"; }
agents() { H agent list | jq -c '[.result.agents[] | select(.name != null) | {name, pane: .pane_id, tab: .tab_id, ws: .workspace_id, cwd}]'; }
agent_field() { agents | jq -r --arg n "$1" --arg f "$2" '.[] | select(.name == $n) | .[$f]'; }
last_saga() { sagas | jq -c '.records | sort_by(.startedAt) | last'; }

"$TOOLS/test-sshd.sh" start >/dev/null && FP="$("$TOOLS/test-sshd.sh" fingerprint | awk '{print $2}')"
export JAVA_HOME=$HOME/.local/share/mise/installs/java/temurin-17.0.20+8; export PATH=$JAVA_HOME/bin:$PATH ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
(cd "$ROOT" && ./gradlew --no-daemon --console=plain ${GRADLE_EXTRA:-} :app:assembleDebug :app:assembleDebugAndroidTest >"$OUT/build.log" 2>&1) || { echo "build failed: $OUT/build.log"; exit 1; }
$ADB install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
$ADB install -r "$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >/dev/null
$ADB shell pm clear $PKG >/dev/null
# Android 17 (API 37) asks for the local-network permission on first launch; granting it keeps the dialog off the screen (tools/run-permission-tests.sh covers the dialog itself).
$ADB shell pm grant $PKG android.permission.ACCESS_LOCAL_NETWORK >/dev/null 2>&1 || true
$ADB shell am instrument -w -e class "$PKG.e2e.SpacesFlowTest#t0_exportAppKey" "$RUNNER" >"$OUT/t0.txt" 2>&1
$ADB pull "/sdcard/Android/data/$PKG/files/app-phone.pub" "$OUT/transport.pub" >/dev/null 2>&1 \
  && "$TOOLS/test-sshd.sh" authorize "$OUT/transport.pub" >/dev/null || { echo "key export failed"; cat "$OUT/t0.txt"; exit 1; }
$ADB shell "rm -rf /sdcard/Android/data/$PKG/files/screens /sdcard/Android/data/$PKG/files/go-*"; $ADB logcat -c

$ADB shell am instrument -w -e hostFp "$FP" -e user "$USER" -e port 2233 -e session "$RUNNING" -e stopped "$STOPPED" \
  -e class "$PKG.e2e.SpacesFlowTest#t1_spacesAgainstARealHerdr" "$RUNNER" >"$OUT/instrument.txt" 2>&1 &
INSTR=$!
wait_at() {
  for _ in $(seq 900); do
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
  say "instrumentation: $(grep -E '^OK|FAILURES|Tests run' "$OUT/instrument.txt" | head -2 | tr '\n' ' ') $(grep -E '^Error in|AssertionError|Process crashed' "$OUT/instrument.txt" | sort -u | head -3 | tr '\n' ' ')"
  rm -rf "$OUT/screens"; $ADB pull "/sdcard/Android/data/$PKG/files/screens" "$OUT/" >/dev/null 2>&1
  $ADB logcat -d -s E2E:I | sed 's/^.* I E2E *: //' >"$OUT/e2e-log.txt"
  grep -q "^OK" "$OUT/instrument.txt" || fail=1
  [ "$fail" = 0 ] && say "ALL CHECKS PASSED" || say "SOME CHECKS FAILED"
  echo "results in $OUT"
  exit "${1:-$fail}"
}
reach() { wait_at "$1" || { say "FAIL the test never reached '$1'"; fail=1; kill $INSTR 2>/dev/null; $ADB shell am force-stop $PKG; finish 1; }; }
check() { if eval "$2"; then say "PASS $1"; else say "FAIL $1"; fail=1; fi; }
# The journal and the saga file are written a moment before the screen says so; give a fact the host or the phone learns just after up to 5 s.
check_soon() { local n=0; until eval "$2"; do n=$((n+1)); if [ "$n" -ge 25 ]; then say "FAIL $1"; fail=1; return; fi; sleep 0.2; done; say "PASS $1"; }

reach listed
check "AC-09.1 the host has the running session, the stopped one and default" '[ "$(sessions | jq -r "map(.name + \"=\" + (.running|tostring)) | sort | join(\",\")")" = "default=false,$RUNNING=true,$STOPPED=false" ]'
check "AC-09.1 the stopped session left a saved file" '[ -s "$HH/.config/herdr/sessions/$STOPPED/session.json" ]'
go listed

reach delete-asked
check "AC-09.1 the dialog is open and the stopped session still exists" 'sessions | jq -e "map(.name) | index(\"$STOPPED\")" >/dev/null'
check "AC-09.1 no delete row yet" '[ "$(rows SessionDelete | jq length)" = 0 ]'
go delete-asked
reach delete-cancelled
check "AC-09.1 Cancel changed nothing: session still there, no journal row" 'sessions | jq -e "map(.name) | index(\"$STOPPED\")" >/dev/null && [ "$(rows SessionDelete | jq length)" = 0 ]'
go delete-cancelled
reach deleted
check "AC-09.1 the stopped session is gone from the host" '! sessions | jq -e "map(.name) | index(\"$STOPPED\")" >/dev/null'
check "AC-09.2 default was not touched" 'sessions | jq -e "map(.name) | index(\"default\")" >/dev/null && [ "$(rows SessionDelete | jq length)" = 1 ]'
check "AC-09.1 one SessionDelete row, acknowledged, on that session" '[ "$(rows SessionDelete | jq -r ".[0] | .outcome + \"/\" + .terminalId")" = "Acknowledged/session:$STOPPED" ]'
snap deleted; go deleted

reach tab-started
check_soon "AC-09.3 herdr has the agent e2e-worker in a pane of a new tab" '[ -n "$(agent_field e2e-worker pane)" ]'
check "AC-09.3 the saga's ids are the ones herdr has" '[ "$(last_saga | jq -r "(.state) + \"/\" + .createdPaneId + \"/\" + .createdTabId")" = "Succeeded/$(agent_field e2e-worker pane)/$(agent_field e2e-worker tab)" ]'
check "AC-09.3 the new tab is in the repo workspace" '[ "$(agent_field e2e-worker ws)" = "$REPO_WS" ]'
check "AC-09.3 the journal has one TabCreate and one AgentStart, both acknowledged" '[ "$(rows TabCreate | jq -r "length, .[0].outcome" | tr "\n" " ")" = "1 Acknowledged " ] && [ "$(rows AgentStart | jq -r "length, .[0].outcome" | tr "\n" " ")" = "1 Acknowledged " ]'
snap tab-started; go tab-started

reach worktree-started
WTPATH="$(last_saga | jq -r .worktreePath)"
check_soon "AC-09.3 herdr has e2e-wt in a workspace of its own" '[ -n "$(agent_field e2e-wt ws)" ] && [ "$(agent_field e2e-wt ws)" != "$REPO_WS" ]'
check "AC-09.3 the worktree exists and the agent's directory is that worktree" '[ -d "$WTPATH" ] && [ "$(agent_field e2e-wt cwd)" = "$WTPATH" ]'
check "AC-09.3 git lists the worktree on the branch" '(cd "$HH/repo" && git worktree list --porcelain | grep -q "branch refs/heads/e2e-branch")'
check "AC-09.3 one WorktreeCreate row, acknowledged" '[ "$(rows WorktreeCreate | jq -r "length, .[0].outcome" | tr "\n" " ")" = "1 Acknowledged " ]'
snap worktree-started; go worktree-started

reach name-taken
CARD="$(last_saga)"
check "AC-09.4 the saga is Failed at the start step with name_taken and recovery pending" '[ "$(echo "$CARD" | jq -r "(.state) + \"/\" + .failure + \"/\" + .step")" = "Failed/name_taken/Start" ]'
CARD_PANE="$(echo "$CARD" | jq -r .createdPaneId)"; CARD_TAB="$(echo "$CARD" | jq -r .createdTabId)"
check "AC-09.4 the pane the card names exists on the host, in the tab the card names, with no agent in it" '[ "$(H pane list | jq -r --arg p "$CARD_PANE" ".result.panes[] | select(.pane_id == \$p) | .tab_id + \"/\" + (.agent == null | tostring)")" = "$CARD_TAB/true" ]'
check "AC-09.4 only one agent is called e2e-worker" '[ "$(agents | jq "[.[] | select(.name == \"e2e-worker\")] | length")" = 1 ]'
snap name-taken; go name-taken

reach renamed-start
check_soon "AC-09.4 the retry started e2e-worker-2 in the very pane the card named" '[ "$(agent_field e2e-worker-2 pane)" = "$CARD_PANE" ]'
check "AC-09.4 the retry made no tab of its own (the repo workspace has its first tab, e2e-worker's and the card's)" '[ "$(H tab list | jq "[.result.tabs[] | select(.workspace_id == \"$REPO_WS\")] | length")" = 3 ]'
go renamed-start

reach close-asked
CARD2="$(last_saga)"; CARD2_TAB="$(echo "$CARD2" | jq -r .createdTabId)"
check "AC-09.4 the second duplicate left a tab the card names" '[ "$(H tab list | jq -r --arg t "$CARD2_TAB" "[.result.tabs[] | select(.tab_id == \$t)] | length")" = 1 ]'
go close-asked
reach closed
check_soon "AC-09.4 closing through the card closed that tab and only that tab" '[ "$(H tab list | jq -r --arg t "$CARD2_TAB" "[.result.tabs[] | select(.tab_id == \$t)] | length")" = 0 ] && [ -n "$(agent_field e2e-worker pane)" ] && [ -n "$(agent_field e2e-worker-2 pane)" ]'
check "AC-09.4 one CloseTab row, acknowledged" '[ "$(rows CloseTab | jq -r "length, .[0].outcome" | tr "\n" " ")" = "1 Acknowledged " ]'
snap closed; go closed

reach renamed
check_soon "the long-press rename reached herdr" '[ -n "$(agent_field e2e-renamed pane)" ] && [ -z "$(agent_field e2e-worker-2 pane)" ]'
check "one Rename row, acknowledged" '[ "$(rows Rename | jq -r "length, .[0].outcome" | tr "\n" " ")" = "1 Acknowledged " ]'
go renamed
reach focused-workspace
check "one FocusWorkspace row, acknowledged" '[ "$(rows FocusWorkspace | jq -r "length, .[0].outcome" | tr "\n" " ")" = "1 Acknowledged " ]'
go focused-workspace

reach activity
snap activity; go activity

reach stop-asked
check "AC-09.1 the dialog is open and the session is still running" '[ "$(sessions | jq -r --arg s "$RUNNING" ".[] | select(.name == \$s) | .running")" = true ]'
go stop-asked
reach stop-cancelled
check "AC-09.1 Cancel changed nothing: still running, no SessionStop row" '[ "$(sessions | jq -r --arg s "$RUNNING" ".[] | select(.name == \$s) | .running")" = true ] && [ "$(rows SessionStop | jq length)" = 0 ]'
go stop-cancelled
reach stopped
check_soon "AC-09.1 the session is stopped on the host" '[ "$(sessions | jq -r --arg s "$RUNNING" ".[] | select(.name == \$s) | .running")" = false ]'
check "AC-09.1 one SessionStop row, acknowledged, on that session" '[ "$(rows SessionStop | jq -r ".[0] | .outcome + \"/\" + .terminalId" )" = "Acknowledged/session:$RUNNING" ]'
check "AC-09.8 the journal never holds a prompt, and no first prompt was asked for" '[ "$(journal | jq "[.records[] | select(.kind == \"Prompt\")] | length")" = 0 ]'
snap stopped; go stopped
reach done
go done
finish
