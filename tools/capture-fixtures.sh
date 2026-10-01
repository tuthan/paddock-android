#!/usr/bin/env bash
# Capture the Paddock fixture corpus from a disposable herdr session.
#   capture-fixtures.sh <session> [outdir]
# Writes fixtures/herdr-0.9.1/ and protocol/herdr-schema-22.json, then run tools/pin-source.sh to re-hash.
# A recapture is a deliberate pin update; review the git diff before committing it.
# Refuses anything but paddock-test or paddock-test-<suffix>; never reads the default session.
set -euo pipefail

SESSION="${1:-}"
[[ "$SESSION" =~ ^paddock-test(-[A-Za-z0-9._-]+)?$ ]] || {
  echo "capture-fixtures: refusing session '${SESSION}' (only paddock-test[-suffix] may be captured)" >&2
  exit 2
}
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
OUT="${2:-$REPO/fixtures/herdr-0.9.1}"
SCHEMA_OUT="${SCHEMA_OUT:-$REPO/protocol/herdr-schema-22.json}"
WS=/tmp/paddock-test-ws
H=(herdr --session "$SESSION")
mkdir -p "$OUT"; rm -f "$OUT"/*

SOCK="$(herdr session list --json | jq -r --arg s "$SESSION" '.sessions[]|select(.name==$s and .running)|.socket_path')"
[ -S "$SOCK" ] || { echo "capture-fixtures: session '$SESSION' is not running; run setup-session.sh" >&2; exit 3; }

# Every pane in the session must live under the synthetic workspace, else abort before saving anything.
if "${H[@]}" pane list | jq -e --arg ws "$WS" '[.result.panes[]|select(.cwd|startswith($ws)|not)]|length>0' >/dev/null; then
  echo "capture-fixtures: session holds a pane outside $WS; refusing" >&2; exit 4
fi
PANE="$("${H[@]}" pane list | jq -r '.result.panes[0].pane_id')"
WSID="${PANE%%:*}"
cap() { python3 "$HERE/sockcap.py" "$SOCK" "$@"; }
say() { printf '  %s\n' "$*" >&2; }
drive() { "${H[@]}" pane run "$PANE" "$@" >/dev/null; }

say "pane under test: $PANE  socket: $SOCK"

# --- static reads -----------------------------------------------------------
"${H[@]}" status                         > "$OUT/status.txt"
herdr api schema --json                  > "$SCHEMA_OUT"
herdr session list --json | jq -c --arg s "$SESSION" '{sessions:[.sessions[]|select(.name==$s)]}' > "$OUT/session-list.json"
"${H[@]}" api snapshot                   > "$OUT/snapshot.json"
"${H[@]}" workspace list                 > "$OUT/workspace-list.json"
"${H[@]}" tab list                       > "$OUT/tab-list.json"
"${H[@]}" pane get "$PANE"               > "$OUT/pane-get.json"
cap request '{"id":"fx-ping","method":"ping","params":{}}'              > "$OUT/socket-ping.jsonl"
cap request '{"id":"fx-snap","method":"session.snapshot","params":{}}'  > "$OUT/socket-snapshot.jsonl" || true

# --- subscription acceptance -----------------------------------------------
LIFE='{"id":"fx-life","method":"events.subscribe","params":{"subscriptions":[{"type":"workspace.created"},{"type":"workspace.closed"},{"type":"tab.created"},{"type":"tab.closed"},{"type":"pane.created"},{"type":"pane.closed"},{"type":"pane.updated"},{"type":"pane.focused"},{"type":"pane.exited"},{"type":"pane.agent_detected"}]}}'
STAT="{\"id\":\"fx-stat\",\"method\":\"events.subscribe\",\"params\":{\"subscriptions\":[{\"type\":\"pane.agent_status_changed\",\"pane_id\":\"$PANE\"}]}}"
MISS='{"id":"fx-miss","method":"events.subscribe","params":{"subscriptions":[{"type":"pane.agent_status_changed"}]}}'
cap subscribe "$LIFE" 1 | head -1 > "$OUT/subscribe-lifecycle-ack.jsonl"
cap subscribe "$STAT" 1 | head -1 > "$OUT/subscribe-status-ack.jsonl"
cap subscribe "$MISS" 1 | head -1 > "$OUT/subscribe-status-missing-pane.jsonl"

# --- synthetic agent states, with both subscriptions open ------------------
cap subscribe "$STAT" 14 > "$OUT/events-status.jsonl" &  P1=$!
cap subscribe "$LIFE" 14 > "$OUT/events-lifecycle.jsonl" & P2=$!
sleep 1
# --seq is tracked per source and survives release-agent, so each run takes a fresh source name.
SRC="paddock-fixture-$(date +%s)"
drive "clear; echo paddock-fixture-marker"; sleep 0.5
n=0
for state in blocked working idle unknown; do
  n=$((n+1))
  msg="{\"kind\":\"fixture\",\"n\":$n}"
  set +e
  "${H[@]}" pane report-agent "$PANE" --source "$SRC" --agent fake --state "$state" --message "$msg" --seq "$n" > "$OUT/report-agent-$state.txt" 2>&1
  echo "exit=$?" >> "$OUT/report-agent-$state.txt"
  set -e
  sleep 0.7
  "${H[@]}" agent list      > "$OUT/agent-list-$state.json" || true
  "${H[@]}" agent get "$PANE" > "$OUT/agent-get-$state.json" || true
done
"${H[@]}" agent read "$PANE" --source detection > "$OUT/agent-read-detection.txt" || true
"${H[@]}" agent read "$PANE" --source recent    > "$OUT/agent-read-recent.txt"    || true

# --- topology events -------------------------------------------------------
NEW="$("${H[@]}" pane split "$PANE" --direction right --no-focus | jq -r '.result.pane.pane_id // empty')"
sleep 0.7
[ -n "$NEW" ] && "${H[@]}" pane close "$NEW" >/dev/null
wait $P1 $P2 || true
"${H[@]}" pane release-agent "$PANE" --source "$SRC" --agent fake --seq 99 >/dev/null 2>&1 || true

# --- terminal frames -------------------------------------------------------
# Generators live in the synthetic workspace so the echoed command line stays short.
cat > "$WS/fx-colours.sh" <<'FX'
for i in 30 31 32 33 34 35 36 37; do printf '\033[%sm16c-%s\033[0m ' "$i" "$i"; done; echo
for i in 16 46 82 118 154 196 226 255; do printf '\033[38;5;%sm256-%s\033[0m ' "$i" "$i"; done; echo
printf '\033[1mbold\033[0m \033[2mdim\033[0m\n'
FX
cat > "$WS/fx-wide.sh" <<'FX'
printf '日本語の文字 wide 漢字\n全角ＡＢＣ ok\n'
FX
cat > "$WS/fx-cursor.sh" <<'FX'
printf 'abcdefghij\033[5D\033[K|after-erase\n\033[2;1Hrow2\033[1;3Hmoved\n'
FX
cat > "$WS/fx-scroll.sh" <<'FX'
for i in $(seq 1 40); do echo "scroll-line-$i"; done
FX
cat > "$WS/fx-altscreen.sh" <<'FX'
echo before-alt; tput smcup; echo inside-alt; sleep 1; tput rmcup; echo after-alt
FX
chmod +x "$WS"/fx-*.sh
frames() { # name; runs $WS/fx-<name>.sh in the pane while one observer records it
  local name="$1"
  drive "clear"; sleep 1
  timeout 6 "${H[@]}" terminal session observe "$PANE" --cols 80 --rows 24 > "$OUT/frames-$name.jsonl" 2>/dev/null &
  local ob=$!
  sleep 1.5; drive "$WS/fx-$name.sh"; sleep 3.5
  kill "$ob" 2>/dev/null || true; wait "$ob" 2>/dev/null || true
}
for n in colours wide cursor scroll altscreen; do frames "$n"; done

# --- plain reads, errors ---------------------------------------------------
"${H[@]}" pane read "$PANE" --source recent > "$OUT/pane-read-recent.txt" || true
"${H[@]}" agent get w9:p9 > "$OUT/error-agent-get.json" 2>&1 || true
cap request '{"id":"fx-bad","method":"agent.get","params":{"target":"w9:p9"}}' > "$OUT/error-socket-agent-get.json" || true
cap raw 'not json' > "$OUT/error-socket-badjson.json" || true
set +e
"${H[@]}" pane split --bogus-flag > "$OUT/cli-usage-error.stdout" 2> "$OUT/cli-usage-error.txt"
echo "exit=$?" >> "$OUT/cli-usage-error.txt"
set -e

# --- message capability (Phase 08 input) -----------------------------------
{
  for f in agent-get-blocked.json agent-list-blocked.json events-status.jsonl events-lifecycle.jsonl snapshot.json; do
    if grep -q 'fixture' "$OUT/$f" 2>/dev/null && grep -q '"kind"' "$OUT/$f"; then echo "$f: PRESENT"; else echo "$f: absent"; fi
  done
} > "$OUT/message-capability.txt"

# --- redaction: home directory, user, host (non-frame files only; frames are base64) ---
HOSTN="$(hostname)"
for f in "$OUT"/*; do
  case "$f" in *frames-*.jsonl) continue;; esac
  sed -i -e "s#$HOME#/home/user#g" -e "s#$HOSTN#host#g" -e "s#$USER@#user@#g" "$f"
done
say "done: $(ls "$OUT" | wc -l) files in $OUT"
