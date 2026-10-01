#!/usr/bin/env bash
# Fail when the installed herdr or any pinned file differs from protocol/SOURCE.json.
# A difference is a deliberate pin update (recapture, re-hash, review), never silently accepted.
# Every file under protocol/ (except SOURCE.json) and under fixtures/ must be listed with a matching sha256; the same rule
# as PinVerifier in :core's FixturePinTest.
#   check-pins.sh               also compares the installed herdr's version and `api schema` with the pin
#   check-pins.sh --pins-only   hashes and coverage only; needs no herdr (CI and tools/check.sh)
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
case "${1:-}" in
  "") PINS_ONLY=0 ;;
  --pins-only) PINS_ONLY=1 ;;
  *) echo "usage: check-pins.sh [--pins-only]" >&2; exit 2 ;;
esac
fail=0
jq -e '(.files|type=="object") and (.herdr|type=="string") and (.protocol|type=="number")' protocol/SOURCE.json >/dev/null \
  || { echo "FAIL protocol/SOURCE.json is missing or lacks herdr, protocol or files" >&2; exit 1; }
pinned="$(jq -r .herdr protocol/SOURCE.json)"
schema="protocol/herdr-schema-$(jq -r .protocol protocol/SOURCE.json).json"
jq -e --arg s "$schema" '.files|has($s)' protocol/SOURCE.json >/dev/null || { echo "FAIL SOURCE.json does not pin $schema" >&2; fail=1; }
if [ "$PINS_ONLY" -eq 0 ]; then
  installed="$(herdr --version | awk '{print $2}')"
  if [ "$installed" != "$pinned" ]; then
    echo "FAIL installed herdr $installed, pinned $pinned: recapture (tools/capture-fixtures.sh, tools/pin-source.sh) or keep $pinned deliberately and record it" >&2; fail=1
  fi
  if ! diff <(herdr api schema --json) "$schema" >/dev/null; then
    echo "FAIL 'herdr api schema --json' differs from $schema" >&2; fail=1
  fi
fi
while IFS=$'\t' read -r path want; do
  have="sha256:$(sha256sum "$path" 2>/dev/null | cut -d' ' -f1)"
  [ "$have" = "$want" ] || { echo "FAIL hash mismatch or missing: $path" >&2; fail=1; }
done < <(jq -r '.files|to_entries[]|[.key,.value]|@tsv' protocol/SOURCE.json)
extra="$(LC_ALL=C comm -13 <(jq -r '.files|keys[]' protocol/SOURCE.json | LC_ALL=C sort) \
  <(find protocol fixtures ! -type d ! -path protocol/SOURCE.json | LC_ALL=C sort))"
[ -z "$extra" ] || { echo "FAIL files not in SOURCE.json: $extra" >&2; fail=1; }
if [ "$fail" -eq 0 ]; then
  if [ "$PINS_ONLY" -eq 1 ]; then echo "pins ok: herdr $pinned pin, $(jq '.files|length' protocol/SOURCE.json) files"
  else echo "pins ok: herdr $installed, $(jq '.files|length' protocol/SOURCE.json) files"; fi
fi
exit $fail
