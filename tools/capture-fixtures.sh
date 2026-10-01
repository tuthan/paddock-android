#!/usr/bin/env bash
# Capture the Paddock fixture corpus from a disposable herdr session.
#   capture-fixtures.sh <session> [outdir]
# Without an outdir: writes fixtures/herdr-<installed version>/ and protocol/herdr-schema-<protocol>.json in this repository.
# With an outdir: it must not exist or be an empty directory, and the schema goes next to it
# (<outdir>/../herdr-schema-<protocol>.json). SCHEMA_OUT=<file> names the schema file explicitly in either case.
# Every refusal runs before anything is written. The corpus is captured into a temporary directory beside the outdir and
# swapped into place only when the whole capture succeeded; a failed run leaves the previous corpus and schema untouched.
# Then run tools/pin-source.sh to re-hash. A recapture is a deliberate pin update; review the git diff before committing it.
# Refuses anything but paddock-test or paddock-test-<suffix> (suffix [a-z0-9]+); never reads the default session.
set -euo pipefail

SESSION="${1:-}"
[[ "$SESSION" =~ ^paddock-test(-[a-z0-9]+)?$ ]] || {
  echo "capture-fixtures: refusing session '${SESSION}' (only paddock-test or paddock-test-<[a-z0-9]+> may be captured)" >&2
  exit 2
}
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
OUT_ARG="${2:-}"
WS=/tmp/paddock-test-ws
H=(herdr --session "$SESSION")
refuse() { local rc="$1"; shift; echo "capture-fixtures: $*; refusing" >&2; exit "$rc"; }

# --- refusals: nothing is written or deleted before every one of these has passed ---
if [ -n "$OUT_ARG" ] && [ -e "$OUT_ARG" ]; then
  [ -d "$OUT_ARG" ] && [ -z "$(ls -A -- "$OUT_ARG")" ] || refuse 5 "outdir '$OUT_ARG' exists and is not an empty directory (choose a new one)"
fi
if [ -n "${SCHEMA_OUT:-}" ] && [ -e "$SCHEMA_OUT" ] && [ ! -f "$SCHEMA_OUT" ]; then
  refuse 5 "SCHEMA_OUT '$SCHEMA_OUT' exists and is not a regular file"
fi

SOCK="$(herdr session list --json | jq -r --arg s "$SESSION" '.sessions[]|select(.name==$s and .running)|.socket_path')"
[ -n "$SOCK" ] && [ -S "$SOCK" ] || { echo "capture-fixtures: session '$SESSION' is not running; run setup-session.sh" >&2; exit 3; }
# The socket must really be this session's (a symlink to another session's socket is refused).
REAL_SOCK="$(realpath -e -- "$SOCK")"
[[ "$REAL_SOCK" =~ /sessions/${SESSION}/herdr\.sock$ ]] || refuse 3 "socket $SOCK resolves to $REAL_SOCK, not session $SESSION"

# Every pane in the session must live in the synthetic workspace. A failed `pane list` aborts here (set -e), never passes.
PANES="$("${H[@]}" pane list)"
FOREIGN="$(jq -r --arg ws "$WS" '[.result.panes[]|select((.cwd // "") as $c|($c==$ws or ($c|startswith($ws+"/")))|not)|.pane_id]|join(" ")' <<<"$PANES")"
[ -z "$FOREIGN" ] || refuse 4 "session holds a pane outside $WS ($FOREIGN)"
PANE="$(jq -r '.result.panes[0].pane_id // empty' <<<"$PANES")"
[ -n "$PANE" ] || refuse 4 "session has no pane"

VERSION="$(herdr --version | awk '{print $2}')"
[[ "$VERSION" =~ ^[0-9]+(\.[0-9]+)+([-+][0-9A-Za-z.]+)?$ ]] || refuse 6 "cannot read the installed herdr version ('$VERSION')"
OUT="${OUT_ARG:-$REPO/fixtures/herdr-$VERSION}"
OUT="${OUT%/}"
PARENT="$(dirname -- "$OUT")"

# --- capture into a temporary directory on the same filesystem as the outdir ---
mkdir -p -- "$PARENT"
TMP="$(mktemp -d "$PARENT/.capture-XXXXXX")"
chmod 755 "$TMP"
OLD=""
cleanup() {
  local rc=$? j
  j="$(jobs -pr)"; [ -z "$j" ] || kill $j 2>/dev/null || true
  if [ -n "$OLD" ] && [ -d "$OLD/corpus" ] && [ ! -e "$OUT" ]; then mv -- "$OLD/corpus" "$OUT"; fi
  [ -z "$OLD" ] || rm -rf -- "$OLD"
  [ -z "$TMP" ] || rm -rf -- "$TMP"
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

herdr api schema --json > "$TMP/.schema.json"
PROTO="$(jq -er '.protocol|select(type=="number")' "$TMP/.schema.json")" || refuse 6 "'herdr api schema --json' has no numeric protocol"
if [ -n "${SCHEMA_OUT:-}" ]; then :
elif [ -z "$OUT_ARG" ]; then SCHEMA_OUT="$REPO/protocol/herdr-schema-$PROTO.json"
else SCHEMA_OUT="$PARENT/herdr-schema-$PROTO.json"
fi
[ ! -e "$SCHEMA_OUT" ] || [ -f "$SCHEMA_OUT" ] || refuse 5 "schema target '$SCHEMA_OUT' is not a regular file"

C="$TMP"
cap() { python3 "$HERE/sockcap.py" "$SOCK" "$@"; }
say() { printf '  %s\n' "$*" >&2; }
drive() { "${H[@]}" pane run "$PANE" "$@" >/dev/null; }

say "herdr $VERSION, protocol $PROTO; pane under test: $PANE  socket: $SOCK"

# --- static reads -----------------------------------------------------------
"${H[@]}" status                         > "$C/status.txt"
herdr session list --json | jq -c --arg s "$SESSION" '{sessions:[.sessions[]|select(.name==$s)]}' > "$C/session-list.json"
"${H[@]}" api snapshot                   > "$C/snapshot.json"
"${H[@]}" workspace list                 > "$C/workspace-list.json"
"${H[@]}" tab list                       > "$C/tab-list.json"
"${H[@]}" pane get "$PANE"               > "$C/pane-get.json"
cap request '{"id":"fx-ping","method":"ping","params":{}}'              > "$C/socket-ping.jsonl"
cap request '{"id":"fx-snap","method":"session.snapshot","params":{}}'  > "$C/socket-snapshot.jsonl" || true

# --- subscription acceptance -----------------------------------------------
LIFE='{"id":"fx-life","method":"events.subscribe","params":{"subscriptions":[{"type":"workspace.created"},{"type":"workspace.closed"},{"type":"tab.created"},{"type":"tab.closed"},{"type":"pane.created"},{"type":"pane.closed"},{"type":"pane.updated"},{"type":"pane.focused"},{"type":"pane.exited"},{"type":"pane.agent_detected"}]}}'
STAT="{\"id\":\"fx-stat\",\"method\":\"events.subscribe\",\"params\":{\"subscriptions\":[{\"type\":\"pane.agent_status_changed\",\"pane_id\":\"$PANE\"}]}}"
MISS='{"id":"fx-miss","method":"events.subscribe","params":{"subscriptions":[{"type":"pane.agent_status_changed"}]}}'
cap subscribe "$LIFE" 1 | head -1 > "$C/subscribe-lifecycle-ack.jsonl"
cap subscribe "$STAT" 1 | head -1 > "$C/subscribe-status-ack.jsonl"
cap subscribe "$MISS" 1 | head -1 > "$C/subscribe-status-missing-pane.jsonl"

# --- synthetic agent states, with both subscriptions open ------------------
cap subscribe "$STAT" 14 > "$C/events-status.jsonl" &  P1=$!
cap subscribe "$LIFE" 14 > "$C/events-lifecycle.jsonl" & P2=$!
sleep 1
# --seq is tracked per source and survives release-agent, so each run takes a fresh source name.
SRC="paddock-fixture-$(date +%s)"
drive "clear; echo paddock-fixture-marker"; sleep 0.5
n=0
for state in blocked working idle unknown; do
  n=$((n+1))
  msg="{\"kind\":\"fixture\",\"n\":$n}"
  set +e
  "${H[@]}" pane report-agent "$PANE" --source "$SRC" --agent fake --state "$state" --message "$msg" --seq "$n" > "$C/report-agent-$state.txt" 2>&1
  echo "exit=$?" >> "$C/report-agent-$state.txt"
  set -e
  sleep 0.7
  "${H[@]}" agent list      > "$C/agent-list-$state.json" || true
  "${H[@]}" agent get "$PANE" > "$C/agent-get-$state.json" || true
done
"${H[@]}" agent read "$PANE" --source detection > "$C/agent-read-detection.txt" || true
"${H[@]}" agent read "$PANE" --source recent    > "$C/agent-read-recent.txt"    || true

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
  timeout 6 "${H[@]}" terminal session observe "$PANE" --cols 80 --rows 24 > "$C/frames-$name.jsonl" 2>/dev/null &
  local ob=$!
  sleep 1.5; drive "$WS/fx-$name.sh"; sleep 3.5
  kill "$ob" 2>/dev/null || true; wait "$ob" 2>/dev/null || true
}
for n in colours wide cursor scroll altscreen; do frames "$n"; done

# --- plain reads, errors ---------------------------------------------------
"${H[@]}" pane read "$PANE" --source recent > "$C/pane-read-recent.txt" || true
"${H[@]}" agent get w9:p9 > "$C/error-agent-get.json" 2>&1 || true
cap request '{"id":"fx-bad","method":"agent.get","params":{"target":"w9:p9"}}' > "$C/error-socket-agent-get.json" || true
cap raw 'not json' > "$C/error-socket-badjson.json" || true
set +e
"${H[@]}" pane split --bogus-flag > "$C/cli-usage-error.stdout" 2> "$C/cli-usage-error.txt"
echo "exit=$?" >> "$C/cli-usage-error.txt"
set -e

# --- message capability (Phase 08 input) -----------------------------------
# The reported message is {"kind":"fixture",...}. It may surface raw or as an escaped JSON string ({\"kind\":\"fixture\"...}),
# so the probe accepts an optional backslash before each quote and whitespace around the colon.
{
  for f in agent-get-blocked.json agent-list-blocked.json events-status.jsonl events-lifecycle.jsonl snapshot.json; do
    if grep -q -E '\\?"kind\\?"[[:space:]]*:[[:space:]]*\\?"fixture\\?"' "$C/$f" 2>/dev/null; then echo "$f: PRESENT"; else echo "$f: absent"; fi
  done
} > "$C/message-capability.txt"

# --- redaction: home directory, user, host (non-frame files only; frames are base64) ---
HOSTN="$(hostname)"
for f in "$C"/*; do
  case "$f" in *frames-*.jsonl) continue;; esac
  sed -i -e "s#$HOME#/home/user#g" -e "s#$HOSTN#host#g" -e "s#$USER@#user@#g" "$f"
done

# --- swap into place: schema first, then the corpus directory ---------------
mkdir -p -- "$(dirname -- "$SCHEMA_OUT")"
mv -f -- "$TMP/.schema.json" "$SCHEMA_OUT"
if [ -e "$OUT" ]; then
  OLD="$(mktemp -d "$PARENT/.previous-XXXXXX")"
  mv -- "$OUT" "$OLD/corpus"
fi
mv -- "$TMP" "$OUT"
TMP=""
say "done: $(find "$OUT" -type f | wc -l) files in $OUT; schema in $SCHEMA_OUT"
say "next: review the diff, then tools/pin-source.sh <YYYY-MM-DD> <host>"
