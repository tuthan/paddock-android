#!/usr/bin/env bash
# Signs the unsigned release APK, or the unsigned app bundle (.aab), with a key that lives outside the repository, verifies it, and writes SHA256SUMS.
#   PADDOCK_KEYSTORE=/path/to/release.p12 PADDOCK_KEY_ALIAS=paddock PADDOCK_KS_PASS_FILE=/path/to/pass tools/release-sign.sh build/release/paddock-1.0.0-unsigned.apk
#   PADDOCK_UPLOAD_KEYSTORE=/path/to/upload.p12 PADDOCK_UPLOAD_KEY_ALIAS=upload PADDOCK_KS_PASS_FILE=/path/to/upload-pass tools/release-sign.sh build/release/paddock-1.0.0-play.aab
# An APK is signed with the RELEASE key and a bundle with the separate UPLOAD key (docs/release.md, Key policy); the script never signs a bundle with
# the release key. The keys are never in the repository, never an argument (a process list shows arguments) and never in the environment: the password
# is read from a file (mode 600) named by PADDOCK_KS_PASS_FILE, so point it at the file of the key you are signing with. The keystore path must not be
# under the repository. Output next to the input:
#   APK: paddock-<version>.apk (aligned, v2 + v3 signed; no v1, no v4 .idsig), SIGNATURE.txt (the `apksigner verify --print-certs` result).
#   Bundle: paddock-<version>.aab (jarsigner, SHA256withRSA, no timestamp: nothing here contacts a server) from paddock-<version>-unsigned.aab, or
#     <name>-signed.aab from any other .aab name (release-build.sh names the unsigned play bundle paddock-<version>-play.aab); SIGNATURE-bundle.txt
#     (`jarsigner -verify -verbose -certs`, then the signer certificate and its fingerprints, which are what Play's upload-key page asks for).
#   Both: SHA256SUMS (the signed file and its input; the lines of other files in the directory are kept, so an APK and a bundle can share one).
# Signing must not change content: every entry outside META-INF equals the unsigned file's. For a bundle only the top-level META-INF is exempt: an
# entry such as base/root/META-INF/services/x is content and is compared.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; ROOT="$(cd "$HERE/.." && pwd)"
[ "$#" -eq 1 ] || { echo "usage: release-sign.sh <unsigned.apk | unsigned.aab> (nothing else: a key or a password is never an argument)" >&2; exit 2; }
IN="$1"
case "$IN" in *.apk) KIND=apk;; *.aab) KIND=aab;; *) echo "refusing: $IN is neither an .apk nor an .aab" >&2; exit 1;; esac
if [ "$KIND" = aab ]; then
  : "${PADDOCK_UPLOAD_KEYSTORE:?a bundle is signed with the upload key: set PADDOCK_UPLOAD_KEYSTORE to its keystore outside the repository}" "${PADDOCK_UPLOAD_KEY_ALIAS:?set PADDOCK_UPLOAD_KEY_ALIAS}" "${PADDOCK_KS_PASS_FILE:?set PADDOCK_KS_PASS_FILE to a file holding the upload keystore password}"
  KS_IN="$PADDOCK_UPLOAD_KEYSTORE"; ALIAS="$PADDOCK_UPLOAD_KEY_ALIAS"
else
  : "${PADDOCK_KEYSTORE:?set PADDOCK_KEYSTORE to the keystore outside the repository}" "${PADDOCK_KEY_ALIAS:?set PADDOCK_KEY_ALIAS}" "${PADDOCK_KS_PASS_FILE:?set PADDOCK_KS_PASS_FILE to a file holding the keystore password}"
  KS_IN="$PADDOCK_KEYSTORE"; ALIAS="$PADDOCK_KEY_ALIAS"
fi
KS="$(readlink -f "$KS_IN")"; case "$KS" in "$ROOT"/*) echo "refusing: the keystore is inside the repository ($KS)" >&2; exit 1;; esac
[ -f "$KS" ] || { echo "no keystore at $KS" >&2; exit 1; }
[ -f "$PADDOCK_KS_PASS_FILE" ] || { echo "no password file at $PADDOCK_KS_PASS_FILE" >&2; exit 1; }
perm="$(stat -c %a "$PADDOCK_KS_PASS_FILE")"; case "$perm" in 600|400) ;; *) echo "refusing: $PADDOCK_KS_PASS_FILE is mode $perm, not 600" >&2; exit 1;; esac
[ -f "$IN" ] || { echo "no input at $IN" >&2; exit 1; }
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"; BT="$ANDROID_HOME/build-tools/36.0.0"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/mise/installs/java/temurin-17.0.20+8}"; export PATH="$JAVA_HOME/bin:$PATH"
DIR="$(dirname "$IN")"

if [ "$KIND" = apk ]; then
  BASE="$(basename "$IN" -unsigned.apk)"; OUT="$DIR/$BASE.apk"
  "$BT/zipalign" -c -P 16 4 "$IN" || { echo "the unsigned APK is not aligned (4 bytes, 16 KiB page for native libraries)" >&2; exit 1; }
  "$BT/apksigner" sign --ks "$KS" --ks-key-alias "$ALIAS" --ks-pass "file:$PADDOCK_KS_PASS_FILE" --v1-signing-enabled false --v2-signing-enabled true --v3-signing-enabled true --v4-signing-enabled false --out "$OUT" "$IN"
  "$BT/apksigner" verify --verbose --print-certs --min-sdk-version 26 "$OUT" | tee "$DIR/SIGNATURE.txt"
  # Signing must not change a byte of content: every entry outside META-INF is identical to the unsigned APK's.
  mkdir -p "$DIR/.cmp1" "$DIR/.cmp2"; (cd "$DIR/.cmp1" && unzip -q "../$(basename "$IN")"); (cd "$DIR/.cmp2" && unzip -q "../$(basename "$OUT")")
  if diff -rq -x META-INF "$DIR/.cmp1" "$DIR/.cmp2" >/dev/null; then echo "content check: every entry outside META-INF equals the unsigned APK's" | tee -a "$DIR/SIGNATURE.txt"; else echo "content check FAILED" >&2; diff -rq -x META-INF "$DIR/.cmp1" "$DIR/.cmp2" >&2; exit 1; fi
  rm -rf "$DIR/.cmp1" "$DIR/.cmp2"
else
  command -v jarsigner >/dev/null && command -v keytool >/dev/null || { echo "no jarsigner or keytool on PATH (JAVA_HOME is $JAVA_HOME)" >&2; exit 1; }
  BASE="$(basename "$IN" .aab)"; case "$BASE" in *-unsigned) OUT="$DIR/${BASE%-unsigned}.aab";; *) OUT="$DIR/$BASE-signed.aab";; esac
  SIG="$DIR/SIGNATURE-bundle.txt"
  jarsigner -keystore "$KS" -storetype PKCS12 -storepass:file "$PADDOCK_KS_PASS_FILE" -sigalg SHA256withRSA -digestalg SHA-256 -signedjar "$OUT" "$IN" "$ALIAS"
  # jarsigner exits 0 for an unsigned jar and for a jar with signer errors, so the verdict line is what is read.
  jarsigner -verify -verbose -certs "$OUT" >"$SIG" 2>&1 || { cat "$SIG" >&2; echo "jarsigner -verify failed" >&2; exit 1; }
  grep -q '^jar verified\.' "$SIG" || { sed -n '/^jar /p;/^Warning/,$p' "$SIG" >&2; echo "the signed bundle did not verify cleanly (the line above is jarsigner's)" >&2; exit 1; }
  { echo; echo "signer certificate (keytool -printcert -jarfile):"; keytool -printcert -jarfile "$OUT" | sed -n '/^Owner:/p;/^Issuer:/p;/^Valid from:/p;/^[[:space:]]*SHA\(1\|256\):/p'; } >>"$SIG"
  sed -n '/^jar verified/,$p' "$SIG"
  # Signing must not change content: every entry outside the top-level META-INF is identical to the unsigned bundle's (names and bytes; directory entries are not content).
  python3 - "$IN" "$OUT" <<'PY' | tee -a "$SIG" || { echo "content check FAILED" >&2; exit 1; }
import hashlib, sys, zipfile
def entries(path):
    out = {}
    with zipfile.ZipFile(path) as z:
        for i in z.infolist():
            if i.filename.endswith("/") or i.filename.startswith("META-INF/"): continue
            if i.filename in out: sys.exit(f"{path}: duplicate entry {i.filename}")
            out[i.filename] = hashlib.sha256(z.read(i)).hexdigest()
    return out
a, b = entries(sys.argv[1]), entries(sys.argv[2])
bad = [f"only in the unsigned bundle: {n}" for n in sorted(a.keys() - b.keys())] + [f"only in the signed bundle: {n}" for n in sorted(b.keys() - a.keys())] + [f"differs: {n}" for n in sorted(a.keys() & b.keys()) if a[n] != b[n]]
if bad: sys.exit("\n".join(bad))
print(f"content check: all {len(a)} entries outside META-INF equal the unsigned bundle's")
PY
fi
# The signed file and its input go into SHA256SUMS; lines for other files (the bundle's beside the APK's) stay.
A="$(basename "$OUT")"; B="$(basename "$IN")"
(cd "$DIR" && { [ ! -f SHA256SUMS ] || awk -v a="$A" -v b="$B" 'substr($0, 67) != a && substr($0, 67) != b' SHA256SUMS; sha256sum "$A" "$B"; } >SHA256SUMS.new && mv SHA256SUMS.new SHA256SUMS && cat SHA256SUMS)
