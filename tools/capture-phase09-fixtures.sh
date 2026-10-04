#!/usr/bin/env bash
# Phase 09: capture what herdr answers for session stop and delete, the saved session.json, tab create, worktree create, agent start (CLI
# and relay), a duplicate name, a timeout, and pane move, into the pinned corpus, then run tools/pin-source.sh.
#   capture-phase09-fixtures.sh
# It starts a throw-away herdr of its own in an isolated HOME (herdr reads XDG_CONFIG_HOME before HOME, and a pane of the developer's herdr
# exports HERDR_SOCKET_PATH; both are overridden), under the session name paddock-test-p9fx, so nothing touches `default` or `paddock-test`.
# `claude` and `kiro` stand-ins (tools/fake-agent.py) come first on the server's PATH, so no installed agent is ever started. The home
# path, user and host name are replaced with placeholders before anything is written.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; REPO="$(cd "$HERE/.." && pwd)"
NAME=paddock-test-p9fx
VERSION="$(herdr --version | awk '{print $2}')"; OUT="$REPO/fixtures/herdr-$VERSION"
[ -f "$OUT/status.txt" ] || { echo "no pinned corpus at $OUT" >&2; exit 3; }
HH=/tmp/p9fx$$; mkdir -p "$HH/work" "$HH/bin" "$HH/repo"; TMP="$(mktemp -d)"
ln -s "$HERE/fake-agent.py" "$HH/bin/claude"; ln -s "$HERE/fake-agent.py" "$HH/bin/kiro"
SOCK="$HH/.config/herdr/sessions/$NAME/herdr.sock"
export HOME="$HH" XDG_CONFIG_HOME="$HH/.config" XDG_DATA_HOME="$HH/.local/share" XDG_STATE_HOME="$HH/.local/state" HERDR_SOCKET_PATH="$SOCK" PATH="$HH/bin:$PATH"
H=(herdr --session "$NAME")
cleanup() {
  herdr session stop "$NAME" --json >/dev/null 2>&1 || true
  herdr session delete "$NAME" --json >/dev/null 2>&1 || true
  rm -rf -- "$TMP"; [[ "$HH" == /tmp/p9fx* ]] && rm -rf -- "$HH"
}
trap cleanup EXIT
# Working directories land under /tmp/paddock-test-ws, the one place validate-fixtures.sh allows a cwd; config paths become /home/user.
redact() { sed -e "s|$HH/\.herdr|/tmp/paddock-test-ws/.herdr|g" -e "s|$HH/repo|/tmp/paddock-test-ws/repo|g" -e "s|$HH/work|/tmp/paddock-test-ws/work|g" -e "s|$REAL_HOME|/home/user|g" -e "s|$HH|/home/user|g" -e "s|$(hostname)|host|g" -e "s|\\b$REAL_USER\\b|user|g"; }
REAL_HOME="$(getent passwd "$(id -u)" | cut -d: -f6)"; REAL_USER="$(id -un)"
(cd "$HH/repo" && git init -q -b main && git -c user.email=t@t -c user.name=t commit -q --allow-empty -m init)
(cd "$HH/work" && setsid herdr --session "$NAME" server >"$HH/server.log" 2>&1 </dev/null &)
for _ in $(seq 40); do [ -S "$SOCK" ] && "${H[@]}" status >/dev/null 2>&1 && break; sleep 0.5; done
[ -S "$SOCK" ] || { echo "the disposable herdr did not come up" >&2; exit 4; }
"${H[@]}" workspace create --cwd "$HH/repo" --label repo --no-focus >/dev/null
for w in $("${H[@]}" workspace list | jq -r '.result.workspaces[]|select(.label!="repo")|.workspace_id'); do "${H[@]}" workspace close "$w" >/dev/null; done
WS="$("${H[@]}" workspace list | jq -r '.result.workspaces[0].workspace_id')"
newtab() { local p; p="$("${H[@]}" tab create --workspace "$WS" --no-focus | jq -r '.result.root_pane.pane_id')"; sleep 2; echo "$p"; }

"${H[@]}" tab create --workspace "$WS" --no-focus | redact > "$TMP/tab-create.json"
"${H[@]}" worktree create --workspace "$WS" --branch fx-branch --no-focus | redact > "$TMP/worktree-create.json"
P="$(newtab)"; "${H[@]}" pane get "$P" | redact > "$TMP/pane-get-fresh-shell.json"; "${H[@]}" pane process-info --pane "$P" | redact > "$TMP/pane-process-info-shell.json"
"${H[@]}" agent start fxone --kind claude --pane "$P" --timeout 15000 | redact > "$TMP/agent-start-cli.json"
P2="$(newtab)"; "${H[@]}" agent start fxone --kind claude --pane "$P2" --timeout 15000 2>&1 | redact > "$TMP/agent-start-name-taken.json" || true
P3="$(newtab)"; "${H[@]}" agent start fxkiro --kind kiro --pane "$P3" --timeout 4000 2>&1 | redact > "$TMP/agent-start-timeout.json" || true
P4="$(newtab)"; printf '%s\n' "{\"id\":\"1\",\"method\":\"agent.start\",\"params\":{\"name\":\"fxrelay\",\"kind\":\"claude\",\"pane_id\":\"$P4\",\"timeout_ms\":15000}}" \
  | python3 "$REPO/host/paddock-relay.py" "$SOCK" | head -1 | redact > "$TMP/agent-start-relay-launch-pending.json"
"${H[@]}" pane move "$P" --new-tab | redact > "$TMP/pane-move-new-tab.json"
"${H[@]}" agent rename fxone fxtwo | redact > "$TMP/agent-rename.json"
"${H[@]}" workspace focus "$WS" | redact > "$TMP/workspace-focus.json"
"${H[@]}" tab focus "$("${H[@]}" tab list | jq -r '.result.tabs[0].tab_id')" | redact > "$TMP/tab-focus.json"
herdr session delete "$NAME" --json 2>&1 | redact > "$TMP/session-delete-running.json" || true
herdr session stop "$NAME" --json | redact > "$TMP/session-stop.json"
herdr session stop "$NAME" --json 2>&1 | redact > "$TMP/session-stop-not-running.json" || true
redact < "$HH/.config/herdr/sessions/$NAME/session.json" > "$TMP/session-saved-v3.json"
herdr session delete "$NAME" --json | redact > "$TMP/session-delete.json"
for f in "$TMP"/*; do jq -e . "$f" >/dev/null || { echo "not JSON: $f" >&2; exit 5; }; done
cp "$TMP"/*.json "$OUT/"
echo "captured $(ls "$TMP" | wc -l) fixtures into $OUT" >&2
"$HERE/pin-source.sh" "$(date +%F)" "$(hostname)"
