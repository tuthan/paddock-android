#!/usr/bin/env bash
# Phase 00 validation: corpus completeness, JSON validity, no leaks, event names, refusal of `default`.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
F="${1:-$HERE/../fixtures/herdr-0.9.1}"
SCHEMA="$HERE/../protocol/herdr-schema-22.json"
fail=0
ok()  { printf 'PASS  %s\n' "$*"; }
bad() { printf 'FAIL  %s\n' "$*"; fail=1; }

# 1. corpus completeness (globs mirror the table in the phase note)
need=(status.txt session-list.json snapshot.json workspace-list.json tab-list.json pane-get.json
  'agent-list-*.json' 'agent-get-*.json' agent-read-detection.txt agent-read-recent.txt
  socket-ping.jsonl socket-snapshot.jsonl
  subscribe-lifecycle-ack.jsonl subscribe-status-ack.jsonl subscribe-status-missing-pane.jsonl
  events-lifecycle.jsonl events-status.jsonl
  frames-colours.jsonl frames-wide.jsonl frames-cursor.jsonl frames-scroll.jsonl frames-altscreen.jsonl
  'error-*.json' cli-usage-error.txt message-capability.txt)
for g in "${need[@]}"; do
  n=$(compgen -G "$F/$g" | wc -l)
  [ "$n" -gt 0 ] && ok "present: $g ($n)" || bad "missing: $g"
  for f in $(compgen -G "$F/$g"); do [ -s "$f" ] || bad "empty: $(basename "$f")"; done
done

[ -s "$SCHEMA" ] && ok "present: protocol/herdr-schema-22.json" || bad "missing: protocol/herdr-schema-22.json"

# 2. valid JSON / JSONL
for f in "$F"/*.json "$F"/*.jsonl; do jq -e . "$f" >/dev/null 2>&1 || bad "invalid JSON: $(basename "$f")"; done
ok "JSON parse pass complete"

# 3. frames: one full + at least one incremental each (AC-00.6)
for f in "$F"/frames-*.jsonl; do
  full=$(jq -c 'select(.full==true)' "$f" | wc -l); inc=$(jq -c 'select(.full==false)' "$f" | wc -l)
  { [ "$full" -ge 1 ] && [ "$inc" -ge 1 ]; } && ok "$(basename "$f"): full=$full incremental=$inc" || bad "$(basename "$f"): full=$full incremental=$inc"
done

# 4. event envelope names (both kinds)
jq -e -s 'map(select(.event)|.event)|(index("pane_created")!=null) and (index("pane_closed")!=null)' "$F/events-lifecycle.jsonl" >/dev/null && ok "lifecycle envelopes: pane_created, pane_closed" || bad "lifecycle envelope names"
jq -e -s 'map(select(.event)|.event)|index("pane.agent_status_changed")!=null' "$F/events-status.jsonl" >/dev/null && ok "status envelope: pane.agent_status_changed" || bad "status envelope name"

# 5. leaks: home, user, host, real project names; frames are decoded first
HOSTN="$(hostname)"
pat="$HOME|$HOSTN|\\b$USER\\b|Projects/|\\.config/herdr/herdr\\.sock"
leak=$(grep -E -l "$pat" "$F"/* 2>/dev/null | grep -v frames- || true)
[ -z "$leak" ] && ok "no home/user/host strings in text fixtures" || bad "leak in: $leak"
for f in "$F"/frames-*.jsonl; do
  jq -r '.bytes' "$f" | while read -r b; do echo "$b" | base64 -d 2>/dev/null; done | grep -a -E -q "$pat" && bad "leak in decoded $(basename "$f")" || true
done
ok "decoded frames scanned"
paths=$(grep -h -o -E '"(cwd|foreground_cwd)":"[^"]*"' "$F"/*.json* | sort -u | grep -v '/tmp/paddock-test-ws' || true)
[ -z "$paths" ] && ok "every cwd is under /tmp/paddock-test-ws" || bad "foreign cwd: $paths"

# 6. refusal of default
"$HERE/capture-fixtures.sh" default >/dev/null 2>&1; rc=$?
[ "$rc" -ne 0 ] && ok "capture-fixtures.sh default exits $rc" || bad "capture-fixtures.sh default exited 0"

exit $fail
