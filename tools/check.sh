#!/usr/bin/env bash
# The CI gate, runnable locally: .github/workflows/build.yml installs the JDK and the SDK packages, then runs exactly this.
#   tools/check.sh [extra gradle args, e.g. --offline]
# Stages: wrapper jar hash, SDK packages at the recorded revisions, protocol and fixture pins, bundled font hashes, self-tests of the fixture
# and pin scripts, the host control helper self-test, the alert relay tests, the guarded-answers hook and writer tests, then :core:check
# :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin. Needs no herdr, adb or
# emulator. PADDOCK_TEST_SOCKET is removed from the environment so the integration tests skip here as they do in CI.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
stage() { printf '\n== %s\n' "$*"; }

# Recorded in docs/build-decision-record.md; a change here is a deliberate toolchain bump.
WRAPPER_JAR_SHA256=497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7
SDK_PACKAGES=("platforms/android-37.0 2" "build-tools/36.0.0 36.0.0")

stage "Gradle wrapper jar"
echo "$WRAPPER_JAR_SHA256  gradle/wrapper/gradle-wrapper.jar" | sha256sum -c -

stage "Android SDK packages"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f local.properties ]; then SDK="$(sed -n 's/^sdk\.dir=//p' local.properties | tail -1)"; fi
[ -n "$SDK" ] && [ -d "$SDK" ] || { echo "no Android SDK: set ANDROID_HOME or sdk.dir in local.properties" >&2; exit 1; }
for p in "${SDK_PACKAGES[@]}"; do
  dir="${p% *}"; want="${p#* }"
  have="$(sed -n 's/^Pkg\.Revision=//p' "$SDK/$dir/source.properties" 2>/dev/null | head -1)"
  [ "$have" = "$want" ] || { echo "SDK package $dir: revision '${have:-absent}', recorded $want (install it, or bump the record deliberately)" >&2; exit 1; }
  echo "$dir revision $have"
done

stage "Protocol and fixture pins"
tools/check-pins.sh --pins-only

stage "Bundled fonts and licence text"
tools/check-fonts.sh

stage "Fixture and pin script self-tests"
tools/test-scripts.sh
tools/validate-fixtures.sh --self-test

stage "Host control helper self-test (fake herdr, about 40 seconds)"
python3 tools/test-control-script.py

stage "Alert relay tests (fake herdr and a loopback notifier, about 20 seconds)"
python3 tools/test-alert-relay.py

stage "Guarded answers: pinned host scripts and the hook and writer tests (fake herdr, about 90 seconds)"
python3 tools/pin-host.py --check
python3 tools/test-permission-hook.py

stage "Gradle: :core:check :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin"
env -u PADDOCK_TEST_SOCKET ./gradlew --no-daemon --stacktrace "$@" :core:check :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin
