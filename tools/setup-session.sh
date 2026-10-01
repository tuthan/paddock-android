#!/usr/bin/env bash
# Bring up the disposable herdr session headlessly and leave exactly one
# workspace at /tmp/paddock-test-ws. Only paddock-test[-suffix] is ever touched.
set -euo pipefail
SESSION="${1:-paddock-test}"
[[ "$SESSION" =~ ^paddock-test(-[A-Za-z0-9._-]+)?$ ]] || { echo "refusing session '$SESSION'" >&2; exit 2; }
WS=/tmp/paddock-test-ws
H=(herdr --session "$SESSION")
mkdir -p "$WS"
if ! herdr session list --json | jq -e --arg s "$SESSION" '.sessions[]|select(.name==$s and .running)' >/dev/null; then
  (cd "$WS" && setsid nohup herdr --session "$SESSION" server >"/tmp/$SESSION-server.log" 2>&1 &)
  for _ in $(seq 20); do "${H[@]}" status >/dev/null 2>&1 && break; sleep 0.5; done
fi
# A headless server seeds a workspace at $HOME; replace it so no fixture sees it.
if "${H[@]}" pane list | jq -e --arg ws "$WS" '[.result.panes[]|select(.cwd|startswith($ws)|not)]|length>0' >/dev/null; then
  "${H[@]}" workspace create --cwd "$WS" --label "$SESSION" --no-focus >/dev/null
  for id in $("${H[@]}" pane list | jq -r --arg ws "$WS" '.result.panes[]|select(.cwd|startswith($ws)|not)|.workspace_id' | sort -u); do
    "${H[@]}" workspace close "$id" >/dev/null
  done
fi
"${H[@]}" pane list | jq -r '.result.panes[0].pane_id'
