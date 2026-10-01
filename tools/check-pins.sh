#!/usr/bin/env bash
# Fail when the installed herdr or any pinned file differs from protocol/SOURCE.json.
# A difference is a deliberate pin update (recapture, re-hash, review), never silently accepted.
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
fail=0
pinned="$(jq -r .herdr protocol/SOURCE.json)"
installed="$(herdr --version | awk '{print $2}')"
if [ "$installed" != "$pinned" ]; then
  echo "FAIL installed herdr $installed, pinned $pinned: recapture (tools/capture-fixtures.sh, tools/pin-source.sh) or keep $pinned deliberately and record it" >&2; fail=1
fi
if ! diff <(herdr api schema --json) protocol/herdr-schema-22.json >/dev/null; then
  echo "FAIL 'herdr api schema --json' differs from protocol/herdr-schema-22.json" >&2; fail=1
fi
while IFS=$'\t' read -r path want; do
  have="sha256:$(sha256sum "$path" 2>/dev/null | cut -d' ' -f1)"
  [ "$have" = "$want" ] || { echo "FAIL hash mismatch: $path" >&2; fail=1; }
done < <(jq -r '.files|to_entries[]|[.key,.value]|@tsv' protocol/SOURCE.json)
extra="$(comm -13 <(jq -r '.files|keys[]' protocol/SOURCE.json | LC_ALL=C sort) <(find protocol/herdr-schema-22.json fixtures -type f | LC_ALL=C sort))"
[ -z "$extra" ] || { echo "FAIL files not in SOURCE.json: $extra" >&2; fail=1; }
[ "$fail" -eq 0 ] && echo "pins ok: herdr $installed, $(jq '.files|length' protocol/SOURCE.json) files"
exit $fail
