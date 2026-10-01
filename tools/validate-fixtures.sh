#!/usr/bin/env bash
# Phase 00 validation: corpus completeness, JSON validity, no leaks or credentials, event names, refusal of `default`.
#   validate-fixtures.sh [corpus-dir]   validate a corpus (default: the pinned one)
#   validate-fixtures.sh --self-test    prove the leak and credential scans fail closed, on synthetic frames and files
# Every scan reads a regular file (frames are decoded into one first), so no pipeline status can hide a match; a frame
# that cannot be decoded and a grep that errors are failures, never passes.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
fail=0
ok()  { printf 'PASS  %s\n' "$*"; }
bad() { printf 'FAIL  %s\n' "$*"; fail=1; }

HOSTN="$(hostname)"
LEAK="$HOME|$HOSTN|\\b$USER\\b|Projects/|\\.config/herdr/herdr\\.sock"
# Credentials, matched case-insensitively: private key blocks, password words, bearer and basic auth, and the token
# shapes of GitHub, GitLab, AWS, Slack, Google, OpenAI and Anthropic, JWTs, and generic key or token assignments.
CRED='-----BEGIN ([A-Z0-9]+ )*PRIVATE KEY-----|PuTTY-User-Key-File|passw(or)?d|passphrase'
CRED+='|authorization["]?[[:space:]]*:[[:space:]]*"?(bearer|basic)[[:space:]]|bearer[[:space:]]+[a-z0-9._~+/=-]{16,}'
CRED+='|gh[pousr]_[a-z0-9]{30,}|github_pat_[a-z0-9_]{20,}|glpat-[a-z0-9_-]{20,}|akia[0-9a-z]{16}|xox[abprs]-[a-z0-9-]{10,}'
CRED+='|aiza[0-9a-z_-]{35}|sk-(ant-)?[a-z0-9_-]{20,}|eyj[a-z0-9_-]{10,}\.eyj[a-z0-9_-]{10,}\.'
CRED+='|(api[_-]?key|secret|token|access[_-]?key)["]?[[:space:]]*[:=]'

# matches <file> <ERE> [grep flags]: 0 = a match, 1 = none, 2 = the scan itself failed (missing file, grep error).
matches() {
  local file="$1" pat="$2"; shift 2
  [ -f "$file" ] || return 2
  grep -a -q -E "$@" -e "$pat" -- "$file"
  case $? in 0) return 0 ;; 1) return 1 ;; *) return 2 ;; esac
}
# decode_frames <frames.jsonl> <out>: every record's base64 `bytes`, concatenated; non-zero if any record has none or
# does not decode.
decode_frames() {
  local f="$1" out="$2" b
  : > "$out" || return 2
  jq -r 'if (.bytes|type)=="string" then .bytes else error("record without bytes") end' "$f" > "$out.b64" 2>/dev/null || return 2
  while IFS= read -r b; do
    printf '%s' "$b" | base64 -d >> "$out" 2>/dev/null || return 2
  done < "$out.b64"
}
# scan <label> <file>: reports a leak, a credential, or a failed scan; returns non-zero on any of them.
scan() {
  local label="$1" file="$2" r=0
  matches "$file" "$LEAK"; case $? in 0) bad "leak (home/user/host/project path) in $label"; r=1 ;; 2) bad "leak scan failed on $label"; r=1 ;; esac
  matches "$file" "$CRED" -i; case $? in 0) bad "credential pattern in $label"; r=1 ;; 2) bad "credential scan failed on $label"; r=1 ;; esac
  return $r
}
# scan_frames <frames.jsonl> <workdir>
scan_frames() {
  local f="$1" dec="$2/$(basename "$1").decoded"
  decode_frames "$f" "$dec" || { bad "undecodable frame record in $(basename "$f")"; return 1; }
  scan "decoded $(basename "$f")" "$dec"
}

self_test() {
  T="$(mktemp -d)"; trap 'rm -rf -- "$T"' EXIT
  local i
  frame() { printf '{"type":"terminal.frame","seq":%s,"full":false,"encoding":"ansi","bytes":"%s"}\n' "$1" "$(printf '%b' "$2" | base64 -w0)"; }
  # A leak in the first frame, ending a line, followed by thousands of frames: the shape that made the old
  # `decode | grep -q && bad` pipeline exit 141 under pipefail and skip the failure.
  { frame 1 "\e[1;1Hcwd $HOME/secret-project\r\n"; for i in $(seq 2 3000); do frame "$i" '\e[2;1Hfiller\r\n'; done; } > "$T/leak.jsonl"
  { frame 1 '\e[1;1H-----BEGIN OPENSSH PRIVATE KEY-----\r\n'; for i in $(seq 2 500); do frame "$i" 'filler\r\n'; done; } > "$T/key.jsonl"
  { frame 1 '\e[1;1H\e[38;5;46m256-46\e[0m wide 漢字\r\n'; frame 2 'scroll-line-1\r\n'; } > "$T/clean.jsonl"
  printf '{"bytes":"%s"}\n{"bytes":"!!! not base64 !!!"}\n' "$(printf 'ok' | base64 -w0)" > "$T/undecodable.jsonl"
  printf '{"bytes":"%s"}\n{"seq":2}\n' "$(printf 'ok' | base64 -w0)" > "$T/nobytes.jsonl"
  printf '{"pane":"w1:p1","line":"export PASSWORD=hunter2"}\n' > "$T/password.json"
  printf '{"line":"token ghp_%s"}\n' "$(printf 'a%.0s' $(seq 36))" > "$T/ghtoken.json"
  printf '{"text":"-----BEGIN RSA PRIVATE KEY-----\\nMIIE\\n"}\n' > "$T/escapedkey.json"
  printf '{"cwd":"/tmp/paddock-test-ws","agent":"fake","agent_status":"blocked"}\n' > "$T/clean.json"

  local outer=$fail st
  st() { ( fail=0; "$@" >/dev/null; echo $? ); }
  [ "$(st scan_frames "$T/leak.jsonl" "$T")" != 0 ] && ok "self-test: home path in the first of 3000 decoded frames fails" || bad "self-test: decoded leak passed"
  [ "$(st scan_frames "$T/key.jsonl" "$T")" != 0 ] && ok "self-test: private key header in a decoded frame fails" || bad "self-test: decoded key passed"
  [ "$(st scan_frames "$T/clean.jsonl" "$T")" = 0 ] && ok "self-test: clean colour and wide frames pass" || bad "self-test: clean frames failed"
  [ "$(st scan_frames "$T/undecodable.jsonl" "$T")" != 0 ] && ok "self-test: an undecodable frame fails" || bad "self-test: undecodable frame passed"
  [ "$(st scan_frames "$T/nobytes.jsonl" "$T")" != 0 ] && ok "self-test: a frame record without bytes fails" || bad "self-test: record without bytes passed"
  [ "$(st scan_frames "$T/missing.jsonl" "$T")" != 0 ] && ok "self-test: a missing frames file fails" || bad "self-test: missing file passed"
  for f in password ghtoken escapedkey; do
    [ "$(st scan "$f.json" "$T/$f.json")" != 0 ] && ok "self-test: credential in $f.json fails" || bad "self-test: credential in $f.json passed"
  done
  [ "$(st scan clean.json "$T/clean.json")" = 0 ] && ok "self-test: a clean fixture line passes" || bad "self-test: clean fixture failed"

  # End to end: the pinned corpus passes; the same corpus with the leaking frames appended to one frames file fails.
  local C="$T/corpus"; cp -r "$HERE/../fixtures/herdr-0.9.1" "$C"
  "$HERE/validate-fixtures.sh" "$C" >"$T/clean.out" 2>&1 && ok "self-test: the pinned corpus validates" || bad "self-test: the pinned corpus fails: $(grep FAIL "$T/clean.out" | head -3)"
  cat "$T/leak.jsonl" >> "$C/frames-scroll.jsonl"
  "$HERE/validate-fixtures.sh" "$C" >"$T/leak.out" 2>&1; local rc=$?
  [ "$rc" -ne 0 ] && grep -q 'leak .* in decoded frames-scroll.jsonl' "$T/leak.out" \
    && ok "self-test: the corpus with a leaking frame fails validation (exit $rc)" || bad "self-test: the corpus with a leaking frame passed (exit $rc)"
  [ "$outer" -eq 0 ] || fail=1
  return $fail
}

if [ "${1:-}" = "--self-test" ]; then self_test; exit $fail; fi

F="${1:-$HERE/../fixtures/herdr-0.9.1}"
SCHEMA="$HERE/../protocol/herdr-schema-$(jq -r .protocol "$HERE/../protocol/SOURCE.json").json"
WORK="$(mktemp -d)"; trap 'rm -rf -- "$WORK"' EXIT

# 1. corpus completeness (globs mirror the table in the phase note)
need=(status.txt session-list.json snapshot.json workspace-list.json tab-list.json pane-get.json
  'agent-list-*.json' 'agent-get-*.json' agent-read-detection.txt agent-read-recent.txt
  socket-ping.jsonl socket-snapshot.jsonl
  subscribe-lifecycle-ack.jsonl subscribe-status-ack.jsonl subscribe-status-missing-pane.jsonl
  events-lifecycle.jsonl events-status.jsonl
  frames-colours.jsonl frames-wide.jsonl frames-cursor.jsonl frames-scroll.jsonl frames-altscreen.jsonl
  'error-*.json' cli-usage-error.txt
  'report-agent-*.txt' pane-read-recent.txt message-capability.txt)
for g in "${need[@]}"; do
  n=$(compgen -G "$F/$g" | wc -l)
  [ "$n" -gt 0 ] && ok "present: $g ($n)" || bad "missing: $g"
  for f in $(compgen -G "$F/$g"); do [ -s "$f" ] || bad "empty: $(basename "$f")"; done
done
for s in blocked working idle unknown; do
  [ -s "$F/report-agent-$s.txt" ] && grep -q '^exit=' "$F/report-agent-$s.txt" || bad "report-agent-$s.txt missing or without its exit status"
done

[ -s "$SCHEMA" ] && ok "present: ${SCHEMA#"$HERE/../"}" || bad "missing: ${SCHEMA#"$HERE/../"}"

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

# 5. leaks and credentials: every text fixture, and every frames file decoded
before=$fail; fail=0
for f in "$F"/*; do
  case "$f" in */frames-*.jsonl) scan_frames "$f" "$WORK" ;; *) scan "$(basename "$f")" "$f" ;; esac
done
[ "$fail" -eq 0 ] && ok "no home/user/host strings or credentials in any fixture (frames decoded)"
[ "$before" -eq 0 ] || fail=1
paths=$(grep -h -o -E '"(cwd|foreground_cwd)":"[^"]*"' "$F"/*.json* | sort -u | grep -v -E '^"[a-z_]+":"/tmp/paddock-test-ws(/[^"]*)?"$')
[ -z "$paths" ] && ok "every cwd is under /tmp/paddock-test-ws" || bad "foreign cwd: $paths"

# 6. refusal of default
"$HERE/capture-fixtures.sh" default >/dev/null 2>&1; rc=$?
[ "$rc" -ne 0 ] && ok "capture-fixtures.sh default exits $rc" || bad "capture-fixtures.sh default exited 0"

exit $fail
