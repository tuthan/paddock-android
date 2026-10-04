#!/usr/bin/env bash
# The accessibility audit (SemanticsAudit) at the size AC-10.3 names: 200 % font (or another scale) on a 360 x 640 dp screen, with the checks for text that is cut
# off and controls that are off screen switched on (-e a11yLarge <scale>). The emulator's display is resized and its font scale set for the run and
# both are restored afterwards, whatever happens.
#   tools/run-a11y-large.sh [adb-serial] [out-dir] [font-scale, default 2.0]
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; . "$HERE/harness.sh"
SERIAL="${1:-emulator-5570}"; OUT="${2:-$OUT_BASE/a11y-large-$(date +%Y%m%d-%H%M%S)}"; SCALE="${3:-2.0}"; mkdir -p "$OUT"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb -s $SERIAL"
P=io.github.tuthan.paddock.ui
CLASSES=(ActivityLogTest AddMachineTest AgentOutputTest AlertRelayScreenTest ComposerTest DecisionSheetTest GuardedAnswersTest HerdHomeTest ImportKeyTest ManualInputPanelTest RelayInstallTest SettingsTest SnippetEditorTest SpacesTest TerminalTabTest)
# Only the audit tests: the other tests assume the default display.
METHODS=(); for c in "${CLASSES[@]}"; do for m in $(grep -o 'fun audit[A-Za-z0-9]*' "$ROOT/app/src/androidTest/kotlin/io/github/tuthan/paddock/ui/$c.kt" | sed 's/fun //'); do METHODS+=("$P.$c#$m"); done; done
LIST="$(IFS=,; echo "${METHODS[*]}")"
size_before="$($ADB shell wm size | tail -1)"; density_before="$($ADB shell wm density | tail -1)"; font_before="$($ADB shell settings get system font_scale)"
restore() { $ADB shell wm size reset >/dev/null; $ADB shell wm density reset >/dev/null; if [ "$font_before" = null ]; then $ADB shell settings delete system font_scale >/dev/null; else $ADB shell settings put system font_scale "$font_before" >/dev/null; fi; }
trap restore EXIT
# 360 x 640 dp: 720 x 1280 px at 320 dpi (density 2.0). The device's own display size is untouched on exit.
$ADB shell wm size 720x1280 >/dev/null; $ADB shell wm density 320 >/dev/null; $ADB shell settings put system font_scale "$SCALE" >/dev/null
echo "display: $($ADB shell wm size | tr '\n' ' ') / $($ADB shell wm density | tr '\n' ' ') / font_scale $($ADB shell settings get system font_scale); was: $size_before, $density_before, ${font_before}" | tee "$OUT/display.txt"
INSTR_ARGS="-e a11yLarge $SCALE -e class $LIST" "$HERE/run-ui-tests.sh" "$SERIAL" "$P" "$OUT"
