#!/usr/bin/env bash
# Regenerate protocol/SOURCE.json from the files on disk. A deliberate pin update: run it only
# after a reviewed recapture, and commit the schema, fixtures and SOURCE.json together.
#   pin-source.sh <captured-date> <host> [fixtures/herdr-<version>]
# Nothing is assumed about the version: herdr's version and protocol are read from the corpus itself (the client section
# of <corpus>/status.txt). The corpus must be fixtures/herdr-<that version>/ (the only one under fixtures/ unless named),
# and the schema protocol/herdr-schema-<that protocol>.json with the same protocol inside. Any other file under protocol/
# or fixtures/ (a stale corpus or schema from the previous version) is refused until it is removed with git rm, so the
# manifest can never record a new version against old files.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
die() { echo "pin-source: $*" >&2; exit 1; }
CAPTURED="${1:?usage: pin-source.sh <YYYY-MM-DD> <host> [fixtures/herdr-<version>]}"; HOST="${2:?host}"
[[ "$CAPTURED" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || die "captured date '$CAPTURED' is not YYYY-MM-DD"
if [ -n "${3:-}" ]; then
  FIX="${3%/}"
else
  mapfile -t corpora < <(find fixtures -mindepth 1 -maxdepth 1 | LC_ALL=C sort)
  [ "${#corpora[@]}" -eq 1 ] || die "expected exactly one corpus under fixtures/, found ${#corpora[@]} (${corpora[*]}); name it, and git rm the stale one"
  FIX="${corpora[0]}"
fi
[ -f "$FIX/status.txt" ] || die "$FIX/status.txt not found"
client() { awk -v k="$1:" '/^client:/{c=1; next} /^[^[:space:]]/{c=0} c && $1==k {print $2; exit}' "$FIX/status.txt"; }
HERDR="$(client version)"; PROTO="$(client protocol)"
[[ "$HERDR" =~ ^[0-9]+(\.[0-9]+)+([-+][0-9A-Za-z.]+)?$ ]] || die "no client version in $FIX/status.txt"
[[ "$PROTO" =~ ^[0-9]+$ ]] || die "no client protocol in $FIX/status.txt"
[ "$FIX" = "fixtures/herdr-$HERDR" ] || die "$FIX was captured from herdr $HERDR; it must be fixtures/herdr-$HERDR"
SCHEMA="protocol/herdr-schema-$PROTO.json"
[ -f "$SCHEMA" ] || die "$SCHEMA not found (the corpus reports protocol $PROTO)"
[ "$(jq -r '.protocol' "$SCHEMA")" = "$PROTO" ] || die "$SCHEMA does not declare protocol $PROTO"
stray="$(find protocol fixtures ! -type d ! -path protocol/SOURCE.json ! -path "$SCHEMA" ! -path "$FIX/*" | LC_ALL=C sort)"
[ -z "$stray" ] || die "files outside this pin (herdr $HERDR, protocol $PROTO); git rm them or name the right corpus: $(echo $stray)"
trap 'rm -f protocol/SOURCE.json.tmp' EXIT
if command -v herdr >/dev/null 2>&1; then
  installed="$(herdr --version | awk '{print $2}')"
  [ "$installed" = "$HERDR" ] || echo "pin-source: note: installed herdr is $installed; the corpus and the manifest say $HERDR" >&2
fi
{
  sha256sum "$SCHEMA"
  find "$FIX" ! -type d | LC_ALL=C sort | xargs -r -d '\n' sha256sum
} | jq -R -s --arg herdr "$HERDR" --argjson proto "$PROTO" --arg cap "$CAPTURED" --arg host "$HOST" '
  {herdr:$herdr, protocol:$proto, captured:$cap, host:$host,
   files:(split("\n")|map(select(length>0)|capture("^(?<h>[0-9a-f]{64})  (?<p>.+)$"))|map({key:.p, value:("sha256:"+.h)})|from_entries)}' \
  > protocol/SOURCE.json.tmp
mv protocol/SOURCE.json.tmp protocol/SOURCE.json
echo "wrote protocol/SOURCE.json ($(jq '.files|length' protocol/SOURCE.json) files, herdr $HERDR, protocol $PROTO)"
