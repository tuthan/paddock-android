#!/usr/bin/env bash
# Phase 12 slice 1: what herdr 0.9.1 calls each agent kind it can start.
#   capture-agent-kinds.sh <session> [outdir]
# Writes agent-start-kinds.txt (the `agent start --kind` possible values, one per line, in herdr's order) and
# agent-kind-strings.tsv (kind, then the `agent` string `agent.get` returned for a stand-in started with
# `agent start <kind> --kind <kind>`; a third column says `start-timeout` when herdr detected the stand-in but never
# called it ready, and a kind herdr did not detect is `not-captured` with herdr's reason) into the fixture directory,
# then run tools/pin-source.sh. Only the stand-in tools/fake-agent.py runs, under a throw-away PATH of symlinks named after the
# kinds, in panes this script splits off and closes. Same rules as capture-fixtures.sh: paddock-test[-suffix] only.
# The stand-in's own name is the only thing herdr can see, so a kind whose canonical executable is spelled differently
# from the kind is recorded as not captured, never guessed.
set -euo pipefail

SESSION="${1:-}"
[[ "$SESSION" =~ ^paddock-test(-[a-z0-9]+)?$ ]] || {
  echo "capture-agent-kinds: refusing session '${SESSION}' (only paddock-test[-suffix] may be driven)" >&2
  exit 2
}
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
OUT="${2:-$REPO/fixtures/herdr-0.9.1}"
WS=/tmp/paddock-test-ws
H=(herdr --session "$SESSION")

herdr session list --json | jq -e --arg s "$SESSION" '.sessions[]|select(.name==$s and .running)' >/dev/null \
  || { echo "capture-agent-kinds: session '$SESSION' is not running; run setup-session.sh" >&2; exit 3; }
if "${H[@]}" pane list | jq -e --arg ws "$WS" '[.result.panes[]|select(.cwd|startswith($ws)|not)]|length>0' >/dev/null; then
  echo "capture-agent-kinds: session holds a pane outside $WS; refusing" >&2; exit 4
fi
# An agent left running in the session (agent names are unique per session) would turn its kind into agent_name_taken.
if "${H[@]}" pane list | jq -e '[.result.panes[]|select(.agent != null)]|length>0' >/dev/null; then
  echo "capture-agent-kinds: the session already holds a pane with an agent; use a fresh session" >&2; exit 4
fi
BASE="$("${H[@]}" pane list | jq -r '.result.panes[0].pane_id')"

KINDS="$(herdr agent start --help | sed -n 's/.*\[possible values: \(.*\)\]/\1/p' | tr -d ' ' | tr ',' '\n')"
[ "$(printf '%s\n' "$KINDS" | wc -l)" -ge 20 ] || { echo "capture-agent-kinds: could not read the kind list from --help" >&2; exit 5; }

BIN="$WS/kind-bin"
rm -rf "$BIN"; mkdir -p "$BIN"
TMP="$(mktemp -d)"; PANE=""
cleanup() { [ -z "$PANE" ] || "${H[@]}" pane close "$PANE" >/dev/null 2>&1 || true; rm -rf -- "$TMP" "$BIN"; }
trap cleanup EXIT

printf '%s\n' "$KINDS" > "$TMP/agent-start-kinds.txt"
: > "$TMP/agent-kind-strings.tsv"
for kind in $KINDS; do
  ln -sf "$HERE/fake-agent.py" "$BIN/$kind"
  PANE="$("${H[@]}" pane split "$BASE" --direction right --no-focus | jq -r '.result.pane.pane_id // empty')"
  [ -n "$PANE" ] || { echo "capture-agent-kinds: could not create a pane for $kind" >&2; exit 6; }
  "${H[@]}" pane run "$PANE" "export PATH=$BIN:\$PATH" >/dev/null
  sleep 0.6
  if out="$(timeout 40 "${H[@]}" agent start "$kind" --kind "$kind" --pane "$PANE" --timeout 10000 2>&1)"; then
    seen="$("${H[@]}" agent get "$PANE" | jq -r '.result.agent.agent // "null"')"
    printf '%s\t%s\n' "$kind" "$seen" >> "$TMP/agent-kind-strings.tsv"
    echo "  $kind -> $seen" >&2
  else
    why="$(printf '%s' "$out" | jq -r '.error.code // empty' 2>/dev/null || true)"
    seen="$("${H[@]}" agent get "$PANE" 2>/dev/null | jq -r '.result.agent.agent // empty' 2>/dev/null || true)"
    if [ "$why" = timeout ] && [ -n "$seen" ]; then
      printf '%s\t%s\tstart-timeout\n' "$kind" "$seen" >> "$TMP/agent-kind-strings.tsv"
      echo "  $kind -> $seen (detected, never ready)" >&2
    else
      printf '%s\tnot-captured\t%s\n' "$kind" "${why:-start-failed}" >> "$TMP/agent-kind-strings.tsv"
      echo "  $kind -> not captured (${why:-start-failed})" >&2
    fi
  fi
  "${H[@]}" agent send-keys "$PANE" ctrl+c >/dev/null 2>&1 || true
  "${H[@]}" pane close "$PANE" >/dev/null 2>&1 || true
  PANE=""
done

mkdir -p "$OUT"; cp "$TMP/agent-start-kinds.txt" "$TMP/agent-kind-strings.tsv" "$OUT/"
echo "captured $(wc -l < "$TMP/agent-start-kinds.txt") kinds into $OUT" >&2
