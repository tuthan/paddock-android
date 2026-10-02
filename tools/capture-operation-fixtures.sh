#!/usr/bin/env bash
# Capture the Phase 06 operation fixtures (agent.prompt, agent.send_keys, agent.focus and their refusals) from a
# disposable herdr session, into the pinned corpus next to the files capture-fixtures.sh wrote.
#   capture-operation-fixtures.sh <session>
# herdr accepts `agent prompt` only for a pane whose foreground process is a known agent, so this runs
# tools/fake-agent.py under the name `claude` in a pane it creates, and closes that pane again. Nothing is written to
# the corpus until every capture has succeeded. Then run tools/pin-source.sh to re-hash and review the diff.
# Refuses anything but paddock-test or paddock-test-<suffix>; never reads the default session; touches only its own pane.
set -euo pipefail

SESSION="${1:-}"
[[ "$SESSION" =~ ^paddock-test(-[a-z0-9]+)?$ ]] || {
  echo "capture-operation-fixtures: refusing session '${SESSION}' (only paddock-test or paddock-test-<[a-z0-9]+>)" >&2
  exit 2
}
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
WS=/tmp/paddock-test-ws
H=(herdr --session "$SESSION")
VERSION="$(herdr --version | awk '{print $2}')"
OUT="$REPO/fixtures/herdr-$VERSION"
[ -f "$OUT/status.txt" ] || { echo "capture-operation-fixtures: no pinned corpus at $OUT" >&2; exit 3; }

SOCK="$(herdr session list --json | jq -r --arg s "$SESSION" '.sessions[]|select(.name==$s and .running)|.socket_path')"
[ -n "$SOCK" ] && [ -S "$SOCK" ] || { echo "capture-operation-fixtures: session '$SESSION' is not running; run setup-session.sh" >&2; exit 3; }
REAL_SOCK="$(realpath -e -- "$SOCK")"
[[ "$REAL_SOCK" =~ /sessions/${SESSION}/herdr\.sock$ ]] || { echo "capture-operation-fixtures: socket resolves to $REAL_SOCK, not session $SESSION" >&2; exit 3; }
PANES="$("${H[@]}" pane list)"
FOREIGN="$(jq -r --arg ws "$WS" '[.result.panes[]|select((.cwd // "") as $c|($c==$ws or ($c|startswith($ws+"/")))|not)|.pane_id]|join(" ")' <<<"$PANES")"
[ -z "$FOREIGN" ] || { echo "capture-operation-fixtures: session holds a pane outside $WS ($FOREIGN); refusing" >&2; exit 4; }
BASE="$(jq -r '.result.panes[0].pane_id // empty' <<<"$PANES")"
[ -n "$BASE" ] || { echo "capture-operation-fixtures: session has no pane" >&2; exit 4; }

TMP="$(mktemp -d)"
PANE=""
SRC="paddock-operations-$(date +%s)"
seq=0
cleanup() {
  local rc=$?
  if [ -n "$PANE" ]; then
    "${H[@]}" agent send-keys "$PANE" ctrl+c >/dev/null 2>&1 || true
    "${H[@]}" pane release-agent "$PANE" --source "$SRC" --agent claude --seq 9999 >/dev/null 2>&1 || true
    "${H[@]}" pane close "$PANE" >/dev/null 2>&1 || true
  fi
  rm -rf -- "$TMP"
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

say() { printf '  %s\n' "$*" >&2; }
cap() { python3 "$HERE/sockcap.py" "$SOCK" request "$1"; }
report() { seq=$((seq+1)); "${H[@]}" pane report-agent "$PANE" --source "$SRC" --agent "${2:-claude}" --state "$1" --message '{"kind":"operations"}' --seq "$seq" >/dev/null; sleep 0.4; }

mkdir -p "$WS/fx-bin"
ln -sf "$HERE/fake-agent.py" "$WS/fx-bin/claude"
PANE="$("${H[@]}" pane split "$BASE" --direction right --no-focus | jq -r '.result.pane.pane_id // empty')"
[ -n "$PANE" ] || { echo "capture-operation-fixtures: could not create a pane" >&2; exit 5; }
"${H[@]}" pane run "$PANE" "$WS/fx-bin/claude" >/dev/null
for _ in $(seq 40); do "${H[@]}" pane read "$PANE" --source recent 2>/dev/null | grep -q 'fake agent ready' && break; sleep 0.25; done
"${H[@]}" pane read "$PANE" --source recent | grep -q 'fake agent ready' || { echo "capture-operation-fixtures: the fake agent did not start" >&2; exit 5; }
say "herdr $VERSION; pane under test: $PANE (fake agent as claude)"

report idle
cap "{\"id\":\"op-prompt\",\"method\":\"agent.prompt\",\"params\":{\"target\":\"$PANE\",\"text\":\"paddock operation fixture\"}}" > "$TMP/operation-agent-prompt.json"
cap "{\"id\":\"op-keys\",\"method\":\"agent.send_keys\",\"params\":{\"target\":\"$PANE\",\"keys\":[\"esc\"]}}" > "$TMP/operation-send-keys-ok.json"
cap "{\"id\":\"op-badkey\",\"method\":\"agent.send_keys\",\"params\":{\"target\":\"$PANE\",\"keys\":[\"notakey\"]}}" > "$TMP/operation-send-keys-invalid.json"
cap "{\"id\":\"op-focus\",\"method\":\"agent.focus\",\"params\":{\"target\":\"$PANE\"}}" > "$TMP/operation-agent-focus.json"
cap "{\"id\":\"op-notfound\",\"method\":\"agent.prompt\",\"params\":{\"target\":\"w9:p9\",\"text\":\"x\"}}" > "$TMP/operation-agent-prompt-not-found.json"

report blocked
cap "{\"id\":\"op-blocked\",\"method\":\"agent.prompt\",\"params\":{\"target\":\"$PANE\",\"text\":\"must not land\"}}" > "$TMP/operation-agent-prompt-blocked.json"

# A label herdr does not know as an agent. (Over a plain shell, a known label also gets agent_not_ready, with the message
# "no longer the pane foreground process"; that sequence only reproduces from a fresh pane and is described, not pinned.)
report idle fake
cap "{\"id\":\"op-notactive\",\"method\":\"agent.prompt\",\"params\":{\"target\":\"$PANE\",\"text\":\"x\"}}" > "$TMP/operation-agent-prompt-not-active.json"

# Redaction, as in capture-fixtures.sh: home directory, user, host.
HOSTN="$(hostname)"
for f in "$TMP"/operation-*.json; do
  sed -i -e "s#$HOME#/home/user#g" -e "s#$HOSTN#host#g" -e "s#$USER@#user@#g" "$f"
  jq -e . "$f" >/dev/null || { echo "capture-operation-fixtures: $(basename "$f") is not JSON" >&2; exit 6; }
done
for f in "$TMP"/operation-*.json; do cp -- "$f" "$OUT/$(basename "$f")"; done
say "wrote $(ls "$TMP"/operation-*.json | wc -l) files into $OUT"
say "next: tools/pin-source.sh <YYYY-MM-DD> <host>, review the diff"
