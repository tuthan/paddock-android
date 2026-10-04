#!/usr/bin/env bash
# The release cut's build: the unsigned release APK built twice from clean, compared byte for byte, then (optionally) signed.
#   tools/release-build.sh [--offline] [--sign]
# Output in build/release/: paddock-<versionName>-unsigned.apk, REPRODUCIBLE.txt (both builds' SHA-256 and the verdict), TOOLCHAIN.txt (the
# tuple the release page publishes), and with --sign the signed APK and SHA256SUMS (tools/release-sign.sh).
# Reproducible here means: two clean builds of the same commit, same machine and toolchain, give an identical *unsigned* APK. The signature is
# left out of the comparison on purpose: an ECDSA signature is randomised and an RSA one differs with the signing time of a v3 lineage, so two
# signed copies can differ while the contents are the same; the signed APK's contents are then proven equal to the unsigned one's
# (release-sign.sh compares the entries). A different machine or JDK build is not claimed.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; ROOT="$HERE/.."; cd "$ROOT"
GRADLE_EXTRA=(); SIGN=0
for a in "$@"; do case "$a" in --offline) GRADLE_EXTRA+=(--offline);; --sign) SIGN=1;; *) echo "unknown argument $a" >&2; exit 2;; esac; done
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/mise/installs/java/temurin-17.0.20+8}"; export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
OUT="$ROOT/build/release"; rm -rf "$OUT"; mkdir -p "$OUT"
VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts | head -1)"
APK_SRC="app/build/outputs/apk/release/app-release-unsigned.apk"

# A clean, daemonless build each time, with a fresh build directory, so nothing from the first build can reach the second.
build() {
  local n="$1"
  ./gradlew --no-daemon --console=plain "${GRADLE_EXTRA[@]}" clean :app:assembleRelease >"$OUT/build-$n.log" 2>&1 || { echo "build $n failed: $OUT/build-$n.log" >&2; exit 1; }
  [ -f "$APK_SRC" ] || { echo "build $n made no $APK_SRC (is a signingConfig set on the release type?)" >&2; exit 1; }
  cp "$APK_SRC" "$OUT/build-$n.apk"
}
build 1; build 2
H1="$(sha256sum "$OUT/build-1.apk" | cut -d' ' -f1)"; H2="$(sha256sum "$OUT/build-2.apk" | cut -d' ' -f1)"
{
  echo "commit: $(git rev-parse HEAD)$(git diff --quiet HEAD -- . ':!build' 2>/dev/null && echo '' || echo ' (working tree has uncommitted changes)')"
  echo "build 1: $H1"; echo "build 2: $H2"
  if [ "$H1" = "$H2" ]; then echo "verdict: IDENTICAL"; else echo "verdict: DIFFERENT"; fi
} >"$OUT/REPRODUCIBLE.txt"
cat "$OUT/REPRODUCIBLE.txt"
if [ "$H1" != "$H2" ]; then
  echo "differing entries:" | tee -a "$OUT/REPRODUCIBLE.txt"
  mkdir -p "$OUT/x1" "$OUT/x2"; (cd "$OUT/x1" && unzip -q ../build-1.apk); (cd "$OUT/x2" && unzip -q ../build-2.apk)
  diff -rq "$OUT/x1" "$OUT/x2" | tee -a "$OUT/REPRODUCIBLE.txt" || true
  exit 1
fi
cp "$OUT/build-1.apk" "$OUT/paddock-$VERSION-unsigned.apk"

BT="$ANDROID_HOME/build-tools/36.0.0"
{
  echo "versionName: $VERSION"
  echo "versionCode: $(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)"
  echo "minSdk: $(sed -n 's/.*minSdk = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)  targetSdk: $(sed -n 's/.*targetSdk = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)  compileSdk: $(sed -n 's/.*compileSdk = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)"
  echo "JDK: $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
  echo "Gradle: $(sed -n 's/.*gradle-\(.*\)-bin.zip/\1/p' gradle/wrapper/gradle-wrapper.properties)"
  grep -E '^(agp|kotlin) *=' gradle/libs.versions.toml | tr -d ' ' | sed 's/^/catalog: /'
  echo "build-tools: $(sed -n 's/^Pkg\.Revision=//p' "$BT/source.properties")"
  echo "platform: android-37.0 revision $(sed -n 's/^Pkg\.Revision=//p' "$ANDROID_HOME/platforms/android-37.0/source.properties")"
} >"$OUT/TOOLCHAIN.txt"
cat "$OUT/TOOLCHAIN.txt"
rm -rf "$OUT/x1" "$OUT/x2"
if [ "$SIGN" = 1 ]; then "$HERE/release-sign.sh" "$OUT/paddock-$VERSION-unsigned.apk"; fi
