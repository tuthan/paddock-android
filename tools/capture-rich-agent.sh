#!/usr/bin/env bash
# Capture the fields real agent integrations set and the first corpus leaves out: `state_labels` (a map,
# from report-metadata) and `display_agent`. Real claude and codex panes also carry `agent_session` (an
# object, `{agent, kind, source, value}`, source `herdr:claude`), which herdr 0.9.1 fills from its own
# detection only: a report with --agent-session-id leaves it null, so FixtureDecodeTest inserts that object
# into these fixtures in the test instead of pinning a fabricated file.
#   capture-rich-agent.sh <session> [outdir]
# Writes agent-get-rich.json, snapshot-rich.json and events-status-rich.jsonl, then run tools/pin-source.sh.
# Same rules as capture-fixtures.sh: paddock-test[-suffix] only, every pane under the synthetic workspace.
set -euo pipefail

SESSION="${1:-}"
[[ "$SESSION" =~ ^paddock-test(-[a-z0-9]+)?$ ]] || {
  echo "capture-rich-agent: refusing session '${SESSION}' (only paddock-test[-suffix] may be captured)" >&2
  exit 2
}
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
OUT="${2:-$REPO/fixtures/herdr-0.9.1}"
WS=/tmp/paddock-test-ws
H=(herdr --session "$SESSION")

SOCK="$(herdr session list --json | jq -r --arg s "$SESSION" '.sessions[]|select(.name==$s and .running)|.socket_path')"
[ -S "$SOCK" ] || { echo "capture-rich-agent: session '$SESSION' is not running; run setup-session.sh" >&2; exit 3; }
if "${H[@]}" pane list | jq -e --arg ws "$WS" '[.result.panes[]|select(.cwd|startswith($ws)|not)]|length>0' >/dev/null; then
  echo "capture-rich-agent: session holds a pane outside $WS; refusing" >&2; exit 4
fi
PANE="$("${H[@]}" pane list | jq -r '.result.panes[0].pane_id')"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
cap() { python3 "$HERE/sockcap.py" "$SOCK" "$@"; }

STAT="{\"id\":\"fx-stat\",\"method\":\"events.subscribe\",\"params\":{\"subscriptions\":[{\"type\":\"pane.agent_status_changed\",\"pane_id\":\"$PANE\"}]}}"
cap subscribe "$STAT" 6 > "$TMP/events-status-rich.jsonl" & SUB=$!
sleep 1
# --seq is tracked per source and survives release-agent, so each run takes a fresh source name.
SRC="paddock-fixture-rich-$(date +%s)"
"${H[@]}" pane report-agent "$PANE" --source "$SRC" --agent fake --state idle --seq 1 >/dev/null
"${H[@]}" pane report-metadata "$PANE" --source "$SRC" --agent fake --display-agent "Fixture agent" --state-label "idle=waiting" --seq 2 >/dev/null
sleep 0.7
"${H[@]}" pane report-agent "$PANE" --source "$SRC" --agent fake --state blocked --seq 3 >/dev/null
sleep 0.7
"${H[@]}" agent get "$PANE" > "$TMP/agent-get-rich.json"
"${H[@]}" api snapshot      > "$TMP/snapshot-rich.json"
wait "$SUB" || true
"${H[@]}" pane release-agent "$PANE" --source "$SRC" --agent fake --seq 99 >/dev/null 2>&1 || true

jq -e '.result.agent.state_labels|type=="object"' "$TMP/agent-get-rich.json" >/dev/null \
  || { echo "capture-rich-agent: state_labels is not an object; herdr changed, review the pin" >&2; exit 5; }
HOSTN="$(hostname)"
for f in "$TMP"/*; do sed -i -e "s#$HOME#/home/user#g" -e "s#$HOSTN#host#g" -e "s#$USER@#user@#g" "$f"; done
mkdir -p "$OUT"; cp "$TMP"/* "$OUT/"
echo "captured $(ls "$TMP" | wc -l) files into $OUT (pane $PANE)" >&2
