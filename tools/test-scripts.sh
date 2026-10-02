#!/usr/bin/env bash
# Self-tests for the fixture and pin scripts. Never calls the real herdr: every script runs inside a throwaway copy of the
# repository with a fake `herdr`, a fake `sockcap.py` and a no-op `sleep` first on PATH, and the synthetic workspace
# redirected into the temporary directory. Exit 0 only when every case passes.
#   tools/test-scripts.sh
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
T="$(mktemp -d)"
trap 'rm -rf -- "$T"' EXIT
fail=0
ok()  { printf 'PASS  %s\n' "$*"; }
bad() { printf 'FAIL  %s\n' "$*"; fail=1; }
expect() { # description, expected exit status, actual exit status
  [ "$2" = "$3" ] && ok "$1 (exit $3)" || bad "$1: expected exit $2, got $3"
}

# --- fake herdr -----------------------------------------------------------------------------------------------------
mkdir -p "$T/bin" "$T/ws" "$T/sessions/paddock-test" "$T/sessions/default"
cat > "$T/bin/sleep" <<'EOF'
#!/bin/sh
exit 0
EOF
cat > "$T/bin/herdr" <<'EOF'
#!/usr/bin/env bash
# Fake herdr for tools/test-scripts.sh. Behaviour comes from FAKE_* variables; every call is logged to FAKE_LOG.
printf '%s\n' "$*" >> "$FAKE_LOG"
[ "${1:-}" = "--session" ] && shift 2
cmd="${1:-} ${2:-}"
[ -n "${FAKE_FAIL_AT:-}" ] && [ "$cmd" = "$FAKE_FAIL_AT" ] && { echo "fake failure at $cmd" >&2; exit 1; }
b64() { printf '%s' "$1" | base64 -w0; }
case "$cmd" in
  "--version "*) echo "herdr ${FAKE_VERSION:-0.9.1}" ;;
  "session list") printf '{"sessions":[{"name":"paddock-test","running":%s,"socket_path":"%s"}]}\n' "${FAKE_RUNNING:-true}" "$FAKE_SOCK" ;;
  "status ") printf 'client:\n  version: %s\n  protocol: %s\n' "${FAKE_VERSION:-0.9.1}" "${FAKE_PROTO:-22}" ;;
  "api schema")
    if [ -n "${FAKE_SCHEMA_FILE:-}" ]; then cat "$FAKE_SCHEMA_FILE"; else printf '{"protocol":%s,"title":"Herdr API"}\n' "${FAKE_PROTO:-22}"; fi ;;
  "api snapshot") echo '{"id":"cli:api:snapshot","result":{"type":"session_snapshot","snapshot":{"panes":[],"agents":[]}}}' ;;
  "workspace list") echo '{"id":"cli:workspace:list","result":{"workspaces":[]}}' ;;
  "tab list") echo '{"id":"cli:tab:list","result":{"tabs":[]}}' ;;
  "pane list")
    if [ -n "${FAKE_FOREIGN:-}" ]; then
      printf '{"result":{"panes":[{"pane_id":"w2:p1","cwd":"%s"},{"pane_id":"w1:p1","cwd":"%s"}]}}\n' "$FAKE_WS" "$FAKE_FOREIGN"
    else
      printf '{"result":{"panes":[{"pane_id":"w2:p1","cwd":"%s"}]}}\n' "$FAKE_WS"
    fi ;;
  "pane get") printf '{"id":"cli:pane:get","result":{"pane":{"pane_id":"w2:p1","cwd":"%s"}}}\n' "$FAKE_WS" ;;
  "pane run"|"pane report-agent"|"pane close"|"pane release-agent") ;;
  "pane split")
    case " $* " in *" --bogus-flag "*) echo "unknown option: --bogus-flag" >&2; exit 2 ;; esac
    echo '{"result":{"pane":{"pane_id":"w2:p2"}}}' ;;
  "pane read") echo "fake pane text" ;;
  "agent list") echo '{"id":"cli:agent:list","result":{"agents":[]}}' ;;
  "agent get")
    [ "${3:-}" = "w9:p9" ] && { echo '{"id":"cli:agent:get","error":{"code":"agent_not_found","message":"no agent"}}'; exit 1; }
    if [ -n "${FAKE_ESCAPED:-}" ]; then
      echo '{"id":"cli:agent:get","result":{"agent":{"agent_status":"blocked","message":"{\"kind\":\"fixture\",\"n\":1}"}}}'
    else
      echo '{"id":"cli:agent:get","result":{"agent":{"agent_status":"blocked"}}}'
    fi ;;
  "agent read") echo "fake agent text" ;;
  "terminal session")
    printf '{"type":"terminal.frame","seq":1,"full":true,"width":80,"height":24,"encoding":"ansi","bytes":"%s"}\n' "$(b64 $'\e[2Jfull')"
    printf '{"type":"terminal.frame","seq":2,"full":false,"width":80,"height":24,"encoding":"ansi","bytes":"%s"}\n' "$(b64 'incremental')" ;;
  *) echo "fake herdr: unhandled '$*'" >&2; exit 64 ;;
esac
EOF
chmod +x "$T/bin/sleep" "$T/bin/herdr"
mksock() { python3 -c 'import socket,sys; socket.socket(socket.AF_UNIX).bind(sys.argv[1])' "$1"; }
mksock "$T/sessions/paddock-test/herdr.sock"
mksock "$T/sessions/default/herdr.sock"

# A throwaway repository: the scripts under test, a sentinel corpus and schema, and a fake socket helper.
fresh_repo() {
  local r="$T/repo"
  rm -rf -- "$r"; mkdir -p "$r/tools" "$r/fixtures/herdr-0.9.1" "$r/protocol"
  cp "$REPO/tools/"*.sh "$r/tools/"
  # The synthetic workspace is fixed at /tmp/paddock-test-ws in the script; the copy uses the temporary one instead.
  sed -i "s#^WS=/tmp/paddock-test-ws\$#WS=$T/ws#" "$r/tools/capture-fixtures.sh"
  grep -q "^WS=$T/ws\$" "$r/tools/capture-fixtures.sh" || { bad "could not redirect WS in the capture-fixtures copy"; exit 1; }
  cat > "$r/tools/sockcap.py" <<'EOF'
import sys
mode = sys.argv[2]
print('{"id":"fx","result":{"type":"pong"}}' if mode != "subscribe" else '{"id":"fx","result":{"type":"subscription_started"}}')
EOF
  echo sentinel-corpus > "$r/fixtures/herdr-0.9.1/status.txt"
  echo sentinel-schema > "$r/protocol/herdr-schema-22.json"
  echo "$r"
}
export PATH="$T/bin:$PATH" FAKE_LOG="$T/herdr.log" FAKE_WS="$T/ws" FAKE_SOCK="$T/sessions/paddock-test/herdr.sock"
capture() { : > "$FAKE_LOG"; "$R/tools/capture-fixtures.sh" "$@" >"$T/capture.out" 2>&1; }
sentinels_intact() {
  [ "$(cat "$R/fixtures/herdr-0.9.1/status.txt")" = sentinel-corpus ] && [ "$(cat "$R/protocol/herdr-schema-22.json")" = sentinel-schema ] \
    && [ "$(ls -A "$R/fixtures/herdr-0.9.1")" = status.txt ] && [ -z "$(find "$R/fixtures" "$R/protocol" -name '.capture-*' -o -name '.previous-*')" ]
}

# --- capture-fixtures.sh ----------------------------------------------------------------------------------------------
R="$(fresh_repo)"
for name in default "" paddock-test-Foo paddock-test-a.b paddock-test- paddock-tester; do
  capture "$name"; rc=$?
  expect "capture-fixtures refuses session '$name'" 2 "$rc"
  [ -s "$FAKE_LOG" ] && bad "capture-fixtures ran herdr for refused session '$name'"
done
sentinels_intact && ok "refused names leave the corpus and schema untouched" || bad "refused names touched the corpus"

FAKE_RUNNING=false capture paddock-test; rc=$?
expect "capture-fixtures refuses a session that is not running" 3 "$rc"
sentinels_intact && ok "session down: corpus and schema untouched" || bad "session down: corpus or schema changed"

ln -sf "$T/sessions/default/herdr.sock" "$T/sessions/link.sock"
mkdir -p "$T/fake/sessions/paddock-test"; ln -sf "$T/sessions/default/herdr.sock" "$T/fake/sessions/paddock-test/herdr.sock"
FAKE_SOCK="$T/fake/sessions/paddock-test/herdr.sock" capture paddock-test; rc=$?
expect "capture-fixtures refuses a paddock-test socket path that resolves to another session" 3 "$rc"
sentinels_intact && ok "symlinked socket: corpus and schema untouched" || bad "symlinked socket: corpus or schema changed"

FAKE_FOREIGN=/home/someone/project capture paddock-test; rc=$?
expect "capture-fixtures refuses a session with a pane outside the synthetic workspace" 4 "$rc"
FAKE_FOREIGN="$T/ws-sibling" capture paddock-test; rc=$?
expect "capture-fixtures refuses a pane in a sibling of the workspace (prefix only)" 4 "$rc"
sentinels_intact && ok "foreign pane: corpus and schema untouched" || bad "foreign pane: corpus or schema changed"

mkdir -p "$T/busy"; echo keep > "$T/busy/precious.txt"
capture paddock-test "$T/busy"; rc=$?
expect "capture-fixtures refuses a non-empty custom outdir" 5 "$rc"
[ "$(cat "$T/busy/precious.txt" 2>/dev/null)" = keep ] && [ "$(ls -A "$T/busy")" = precious.txt ] \
  && ok "non-empty outdir: its files are untouched" || bad "non-empty outdir: files deleted or changed"
[ -s "$FAKE_LOG" ] && bad "capture-fixtures ran herdr before refusing the outdir"

FAKE_FAIL_AT="tab list" capture paddock-test; rc=$?
[ "$rc" -ne 0 ] && ok "a capture that fails midway exits non-zero ($rc)" || bad "a failing capture exited 0"
sentinels_intact && ok "failed capture: previous corpus and schema untouched, no temporary directory left" || bad "failed capture changed the corpus or left a temporary directory"

capture paddock-test "$T/out/herdr-0.9.1"; rc=$?
expect "capture-fixtures into a new custom outdir" 0 "$rc"
n=$(find "$T/out/herdr-0.9.1" -type f | wc -l)
[ "$n" -ge 39 ] && ok "custom outdir holds the corpus ($n files)" || bad "custom outdir holds $n files"
[ -f "$T/out/herdr-schema-22.json" ] && ok "schema written next to the custom outdir" || bad "schema not next to the custom outdir"
[ ! -e "$T/out/herdr-0.9.1/.schema.json" ] || bad "temporary schema left inside the corpus"
sentinels_intact && ok "custom outdir: the repository's corpus and protocol/herdr-schema-22.json are untouched" || bad "custom outdir overwrote the repository pin"
grep -q 'PRESENT' "$T/out/herdr-0.9.1/message-capability.txt" && bad "capability reported PRESENT without a message" || ok "capability absent when no message surfaces"

SCHEMA_OUT="$T/elsewhere/schema.json" capture paddock-test "$T/out2/c"; rc=$?
expect "capture-fixtures with SCHEMA_OUT" 0 "$rc"
[ -f "$T/elsewhere/schema.json" ] && [ ! -e "$T/out2/herdr-schema-22.json" ] && ok "SCHEMA_OUT names the schema file" || bad "SCHEMA_OUT ignored"

FAKE_ESCAPED=1 capture paddock-test "$T/out3/c"; rc=$?
expect "capture-fixtures with an escaped message in agent get" 0 "$rc"
grep -qx 'agent-get-blocked.json: PRESENT' "$T/out3/c/message-capability.txt" && ok "capability probe finds the escaped form (\\\"kind\\\")" \
  || bad "escaped message reported absent: $(tr '\n' ' ' < "$T/out3/c/message-capability.txt")"

capture paddock-test; rc=$?
expect "capture-fixtures into the default outdir" 0 "$rc"
[ ! -e "$R/fixtures/herdr-0.9.1/status.txt" ] || ! grep -q sentinel "$R/fixtures/herdr-0.9.1/status.txt" \
  && [ -f "$R/fixtures/herdr-0.9.1/frames-wide.jsonl" ] && ok "default outdir replaced by the new capture" || bad "default outdir not replaced"
grep -q '"protocol":22' "$R/protocol/herdr-schema-22.json" && ok "default schema replaced at protocol/herdr-schema-22.json" || bad "default schema not replaced"
[ -z "$(find "$R/fixtures" "$R/protocol" -name '.capture-*' -o -name '.previous-*')" ] && ok "no temporary directory left after the swap" || bad "temporary directory left after the swap"

R="$(fresh_repo)"
FAKE_VERSION=0.9.2 FAKE_PROTO=23 capture paddock-test; rc=$?
expect "capture-fixtures after a herdr upgrade" 0 "$rc"
[ -f "$R/fixtures/herdr-0.9.2/status.txt" ] && [ -f "$R/protocol/herdr-schema-23.json" ] && sentinels_intact \
  && ok "an upgraded herdr writes fixtures/herdr-0.9.2 and herdr-schema-23.json, leaving the 0.9.1 pin alone" || bad "upgrade capture wrote over the old pin"

# --- check-pins.sh and pin-source.sh on a copy of the real pins -------------------------------------------------------
pins_repo() {
  local r="$T/pins"
  rm -rf -- "$r"; mkdir -p "$r/tools"
  cp "$REPO/tools/"*.sh "$r/tools/"
  cp -r "$REPO/protocol" "$REPO/fixtures" "$r/"
  echo "$r"
}
P="$(pins_repo)"
pins() { "$P/tools/check-pins.sh" "$@" >"$T/pins.out" 2>&1; }
pins --pins-only; expect "check-pins --pins-only on the committed pins" 0 $?
FAKE_SCHEMA_FILE="$P/protocol/herdr-schema-22.json" pins; expect "check-pins with a herdr that matches the pin" 0 $?
FAKE_SCHEMA_FILE="$P/protocol/herdr-schema-22.json" FAKE_VERSION=0.9.2 pins; expect "check-pins with a newer installed herdr" 1 $?
pins; expect "check-pins when the installed schema differs" 1 $?
grep -q "differs from protocol/herdr-schema-22.json" "$T/pins.out" && ok "schema difference names the pinned file" || bad "schema difference message: $(cat "$T/pins.out")"
echo '{"protocol":23}' > "$P/protocol/herdr-schema-23.json"
pins --pins-only; expect "check-pins --pins-only with an unlisted protocol/herdr-schema-23.json" 1 $?
grep -q "protocol/herdr-schema-23.json" "$T/pins.out" && ok "the unlisted schema is named" || bad "unlisted schema not named: $(cat "$T/pins.out")"
rm "$P/protocol/herdr-schema-23.json"; echo x > "$P/protocol/notes.txt"
pins --pins-only; expect "check-pins --pins-only with any stray file under protocol/" 1 $?
rm "$P/protocol/notes.txt"; printf 'x' >> "$P/fixtures/herdr-0.9.1/status.txt"
pins --pins-only; expect "check-pins --pins-only with a tampered fixture" 1 $?

P="$(pins_repo)"
# The date and host are inputs, not something pin-source derives; take them from the committed file so a re-pin on a later
# day does not fail this test, while the versions, file list and hashes are still checked byte for byte.
"$P/tools/pin-source.sh" "$(jq -r .captured "$REPO/protocol/SOURCE.json")" "$(jq -r .host "$REPO/protocol/SOURCE.json")" >"$T/pin.out" 2>&1; expect "pin-source on the committed corpus" 0 $?
cmp -s "$P/protocol/SOURCE.json" "$REPO/protocol/SOURCE.json" && ok "pin-source reproduces the committed SOURCE.json byte for byte" || bad "pin-source output differs from the committed SOURCE.json"
# A herdr bump half done: a new corpus and schema beside the old ones.
mkdir -p "$P/fixtures/herdr-0.9.2"
printf 'client:\n  version: 0.9.2\n  channel: stable\n  protocol: 23\n\nserver:\n  version: 0.9.2\n' > "$P/fixtures/herdr-0.9.2/status.txt"
echo '{}' > "$P/fixtures/herdr-0.9.2/snapshot.json"
echo '{"protocol":23}' > "$P/protocol/herdr-schema-23.json"
cp "$P/protocol/SOURCE.json" "$T/source.before"
"$P/tools/pin-source.sh" 2026-10-02 devbox >"$T/pin.out" 2>&1; expect "pin-source refuses two corpora without a name" 1 $?
"$P/tools/pin-source.sh" 2026-10-02 devbox fixtures/herdr-0.9.2 >"$T/pin.out" 2>&1; expect "pin-source refuses while the 0.9.1 corpus and schema 22 remain" 1 $?
grep -q "fixtures/herdr-0.9.1/status.txt" "$T/pin.out" && grep -q "protocol/herdr-schema-22.json" "$T/pin.out" \
  && ok "the stale files are named" || bad "stale files not named: $(cat "$T/pin.out")"
cmp -s "$P/protocol/SOURCE.json" "$T/source.before" && ok "a refused pin leaves SOURCE.json unchanged" || bad "a refused pin rewrote SOURCE.json"
cp "$P/fixtures/herdr-0.9.2/status.txt" "$T/status-0.9.2"
cp "$P/fixtures/herdr-0.9.1/status.txt" "$P/fixtures/herdr-0.9.2/status.txt"
rm -r "$P/fixtures/herdr-0.9.1" "$P/protocol/herdr-schema-22.json"
"$P/tools/pin-source.sh" 2026-10-02 devbox >"$T/pin.out" 2>&1; expect "pin-source refuses a 0.9.1 corpus filed as fixtures/herdr-0.9.2" 1 $?
cp "$T/status-0.9.2" "$P/fixtures/herdr-0.9.2/status.txt"
"$P/tools/pin-source.sh" 2026-10-02 devbox >"$T/pin.out" 2>&1; expect "pin-source once only the 0.9.2 corpus and schema 23 remain" 0 $?
[ "$(jq -c '[.herdr,.protocol,(.files|keys)]' "$P/protocol/SOURCE.json")" = '["0.9.2",23,["fixtures/herdr-0.9.2/snapshot.json","fixtures/herdr-0.9.2/status.txt","protocol/herdr-schema-23.json"]]' ] \
  && ok "SOURCE.json records herdr 0.9.2, protocol 23 and only the new files" || bad "SOURCE.json after the bump: $(jq -c . "$P/protocol/SOURCE.json")"
pins --pins-only; expect "check-pins --pins-only after the bump" 0 $?

exit $fail
