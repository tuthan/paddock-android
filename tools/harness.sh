# Sourced by every device flow and device check that builds, installs or runs the app: one place that decides which checkout it works
# against and where its run directories go. Not executable on its own.
#   PADDOCK_APP          the paddock-android checkout the flow builds and runs against (default: the checkout this file sits in);
#                        refused unless its settings.gradle.kts names paddock-android
#   PADDOCK_HARNESS_OUT  where run directories are written (default: $PADDOCK_APP/build)
# Sets ROOT (the app checkout), TOOLS (its tools/ with the stand-ins that stay with the app: test-sshd.sh, fake-agent.py,
# desktop-client.py, setup-session.sh) and OUT_BASE, and exports all three plus the default for TEST_SSHD_RUN, so the stand-in's run
# directory and the flows' agree. harness.py does the same for the Python checks.
_harness_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PADDOCK_APP="${PADDOCK_APP:-$_harness_dir/..}"
if ! grep -qxF 'rootProject.name = "paddock-android"' "$PADDOCK_APP/settings.gradle.kts" 2>/dev/null; then
  {
    echo "not a paddock-android checkout: ${PADDOCK_APP}"
    echo "  set PADDOCK_APP to the checkout, or put it next to this directory as ../paddock-android"
  } >&2
  exit 2
fi
PADDOCK_APP="$(cd "$PADDOCK_APP" && pwd)"
ROOT="$PADDOCK_APP"; TOOLS="$ROOT/tools"
OUT_BASE="${PADDOCK_HARNESS_OUT:-$ROOT/build}"; mkdir -p "$OUT_BASE"
export PADDOCK_APP PADDOCK_HARNESS_OUT="$OUT_BASE" TEST_SSHD_RUN="${TEST_SSHD_RUN:-$OUT_BASE/test-sshd}"
unset _harness_dir
