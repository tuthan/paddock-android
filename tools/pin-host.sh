#!/usr/bin/env bash
# Regenerate the hashes in host/SOURCE.json from the files under host/. A deliberate pin update: changing a script bumps its
# version constant first (VERSION in paddock-alert-relay.py, the version keys in SOURCE.json for the others), then runs this,
# and the script, its pin and the tests that cover it land in one commit. The app refuses to copy a script whose hash it does
# not know, so a stale pin fails the build (HostScriptPinTest), not a user.
#   tools/pin-host.sh
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
[ -f host/SOURCE.json ] || { echo "pin-host: host/SOURCE.json not found" >&2; exit 1; }
trap 'rm -f host/SOURCE.json.tmp' EXIT
hashes="$(find host -maxdepth 1 -type f ! -name SOURCE.json | LC_ALL=C sort | xargs -r -d '\n' sha256sum)"
version="$(sed -n 's/^VERSION = \([0-9]*\)$/\1/p' host/paddock-alert-relay.py)"
[ -n "$version" ] || { echo "pin-host: no VERSION in host/paddock-alert-relay.py" >&2; exit 1; }
printf '%s\n' "$hashes" | jq -R -s --argjson alert "$version" --slurpfile old host/SOURCE.json '
  $old[0] + {alert_relay: "paddock-alert-relay.py", alert_relay_version: $alert,
    files: (split("\n")|map(select(length>0)|capture("^(?<h>[0-9a-f]{64})  (?<p>.+)$"))|map({key:.p, value:("sha256:"+.h)})|from_entries)}' \
  > host/SOURCE.json.tmp
mv host/SOURCE.json.tmp host/SOURCE.json
echo "wrote host/SOURCE.json ($(jq '.files|length' host/SOURCE.json) files)"
