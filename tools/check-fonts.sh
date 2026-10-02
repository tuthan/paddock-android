#!/usr/bin/env bash
# Fail when a bundled font or the licence text differs from the recorded hash. A difference is a deliberate font update
# (docs/fonts.md: compare with upstream, record the new hash), never silently accepted. Needs no network.
#   tools/check-fonts.sh
set -uo pipefail
SELF="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/$(basename "${BASH_SOURCE[0]}")"
cd "$(dirname "$SELF")/../app/src/main"
fail=0
check() {
  local want="$1" file="$2"
  [ -f "$file" ] || { echo "FAIL $file is missing" >&2; fail=1; return; }
  local have; have="$(sha256sum "$file" | cut -d' ' -f1)"
  if [ "$have" = "$want" ]; then echo "ok   $file"; else echo "FAIL $file: $have, recorded $want" >&2; fail=1; fi
}
check 975dcda37d80f038dcd143c22e33ca2d97a0cc5a929aace1c749153b0fe1afa5 res/font/ibm_plex_sans_regular.ttf
check 331c8639d7598b2cde62a911a71db195e30cb655cd6bdf2e324a7e984955f907 res/font/ibm_plex_sans_medium.ttf
check a20caf8286023a6a7a85e40b1d2a4ae9fc3e3b1f9eda8f4c542dd4986af67bb1 res/font/ibm_plex_sans_semibold.ttf
check a0bf60ef0f83c5ed4d7a75d45838548b1f6873372dfac88f71804491898d138f res/font/jetbrains_mono_regular.ttf
check 31c92d01a8a08528b718a43addf0ad3df0af2ca4b7b3290a452f70f358e14d3d res/font/jetbrains_mono_medium.ttf
check 47f699dfc58a85c4cfefcf54b54783701425b35edcf88d0b9cd46736a822b6e6 assets/licenses/fonts.txt
# Nothing else may sit in res/font or assets/licenses without a recorded hash.
for f in res/font/* assets/licenses/*; do
  grep -q "$f" "$SELF" || { echo "FAIL $f has no recorded hash" >&2; fail=1; }
done
exit "$fail"
