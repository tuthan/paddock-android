#!/usr/bin/env bash
# Signs the unsigned release APK with a key that lives outside the repository, verifies it, and writes SHA256SUMS.
#   PADDOCK_KEYSTORE=/path/to/release.p12 PADDOCK_KEY_ALIAS=paddock PADDOCK_KS_PASS_FILE=/path/to/pass tools/release-sign.sh build/release/paddock-1.0.0-unsigned.apk
# The key is never in the repository, never an argument (a process list shows arguments) and never in the environment: the password is read
# from a file (mode 600) named by PADDOCK_KS_PASS_FILE. The keystore path must not be under the repository. Output next to the input:
# paddock-<version>.apk (aligned, v2 + v3 signed; no v1, no v4 .idsig), SHA256SUMS, SIGNATURE.txt (the `apksigner verify --print-certs` result).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; ROOT="$(cd "$HERE/.." && pwd)"
IN="${1:?usage: release-sign.sh <unsigned.apk>}"
: "${PADDOCK_KEYSTORE:?set PADDOCK_KEYSTORE to the keystore outside the repository}" "${PADDOCK_KEY_ALIAS:?set PADDOCK_KEY_ALIAS}" "${PADDOCK_KS_PASS_FILE:?set PADDOCK_KS_PASS_FILE to a file holding the keystore password}"
KS="$(readlink -f "$PADDOCK_KEYSTORE")"; case "$KS" in "$ROOT"/*) echo "refusing: the keystore is inside the repository ($KS)" >&2; exit 1;; esac
[ -f "$KS" ] || { echo "no keystore at $KS" >&2; exit 1; }
[ -f "$PADDOCK_KS_PASS_FILE" ] || { echo "no password file at $PADDOCK_KS_PASS_FILE" >&2; exit 1; }
perm="$(stat -c %a "$PADDOCK_KS_PASS_FILE")"; case "$perm" in 600|400) ;; *) echo "refusing: $PADDOCK_KS_PASS_FILE is mode $perm, not 600" >&2; exit 1;; esac
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"; BT="$ANDROID_HOME/build-tools/36.0.0"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/mise/installs/java/temurin-17.0.20+8}"; export PATH="$JAVA_HOME/bin:$PATH"
DIR="$(dirname "$IN")"; BASE="$(basename "$IN" -unsigned.apk)"; OUT="$DIR/$BASE.apk"
"$BT/zipalign" -c -P 16 4 "$IN" || { echo "the unsigned APK is not aligned (4 bytes, 16 KiB page for native libraries)" >&2; exit 1; }
"$BT/apksigner" sign --ks "$KS" --ks-key-alias "$PADDOCK_KEY_ALIAS" --ks-pass "file:$PADDOCK_KS_PASS_FILE" --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --out "$OUT" "$IN"
"$BT/apksigner" verify --verbose --print-certs --min-sdk-version 26 "$OUT" | tee "$DIR/SIGNATURE.txt"
# Signing must not change a byte of content: every entry outside META-INF is identical to the unsigned APK's.
mkdir -p "$DIR/.cmp1" "$DIR/.cmp2"; (cd "$DIR/.cmp1" && unzip -q "../$(basename "$IN")"); (cd "$DIR/.cmp2" && unzip -q "../$(basename "$OUT")")
if diff -rq -x META-INF "$DIR/.cmp1" "$DIR/.cmp2" >/dev/null; then echo "content check: every entry outside META-INF equals the unsigned APK's" | tee -a "$DIR/SIGNATURE.txt"; else echo "content check FAILED" >&2; diff -rq -x META-INF "$DIR/.cmp1" "$DIR/.cmp2" >&2; exit 1; fi
rm -rf "$DIR/.cmp1" "$DIR/.cmp2"
(cd "$DIR" && sha256sum "$(basename "$OUT")" "$(basename "$IN")" >SHA256SUMS && cat SHA256SUMS)
