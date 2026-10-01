#!/usr/bin/env bash
# Regenerate protocol/SOURCE.json from the files on disk. A deliberate pin update: run it only
# after a reviewed recapture, and commit the schema, fixtures and SOURCE.json together.
#   pin-source.sh <captured-date> <host>
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
CAPTURED="${1:?usage: pin-source.sh <YYYY-MM-DD> <host>}"; HOST="${2:?host}"
SCHEMA=protocol/herdr-schema-22.json
HERDR="$(herdr --version | awk '{print $2}')"
PROTO="$(jq -r '.protocol' "$SCHEMA")"
{
  sha256sum "$SCHEMA"
  find fixtures/herdr-0.9.1 -type f | LC_ALL=C sort | xargs sha256sum
} | jq -R -s --arg herdr "$HERDR" --argjson proto "$PROTO" --arg cap "$CAPTURED" --arg host "$HOST" '
  {herdr:$herdr, protocol:$proto, captured:$cap, host:$host,
   files:(split("\n")|map(select(length>0)|capture("^(?<h>[0-9a-f]{64})  (?<p>.+)$"))|map({key:.p, value:("sha256:"+.h)})|from_entries)}' \
  > protocol/SOURCE.json
echo "wrote protocol/SOURCE.json ($(jq '.files|length' protocol/SOURCE.json) files, herdr $HERDR, protocol $PROTO)"
