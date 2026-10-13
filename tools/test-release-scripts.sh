#!/usr/bin/env bash
# Self-tests for the release scripts that need no Gradle, adb, device or network (about 20 seconds):
#   tools/release-sign.sh   signs a throwaway APK with a throwaway release key and a throwaway app bundle with a throwaway upload key, then
#                           checks that both verify, that the content proof passes and fails when it should, that the refusals fire, and that
#                           no password is ever an argument.
#   tools/run-release-smoke.py --self-test   the /proc/net parser, the --sockets-only report and the arguments, on canned text; and --sockets-only itself
#                           end to end against a fake adb (it must install nothing, drive nothing and read only /proc/net).
#   tools/check-listing.py                    its own self-test, and a run over the real listing text (missing images are reported, not failed).
# The throwaway keys live in a mktemp directory outside the repository and are deleted on exit; this is the only place a rehearsal key is made. The
# APK is made by aapt2 and zipalign from the SDK that tools/check.sh already requires (a manifest and nothing else), so no Gradle build is needed.
# Exit 0 only when every case passes.
#   tools/test-release-scripts.sh
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
T="$(mktemp -d)"
trap 'rm -rf -- "$T"' EXIT
case "$T" in "$REPO"/*) echo "TMPDIR is inside the repository ($T); the keys must not be" >&2; exit 2;; esac
# The environment of a person who signs releases must not reach this test: it is only ever allowed to use its own throwaway keys.
unset PADDOCK_KEYSTORE PADDOCK_KEY_ALIAS PADDOCK_KS_PASS_FILE PADDOCK_UPLOAD_KEYSTORE PADDOCK_UPLOAD_KEY_ALIAS
fail=0
ok()  { printf 'PASS  %s\n' "$*"; }
bad() { printf 'FAIL  %s\n' "$*"; fail=1; }
expect() { # description, expected exit status, actual exit status
  [ "$2" = "$3" ] && ok "$1 (exit $3)" || bad "$1: expected exit $2, got $3"
}
refused() { # description, actual exit status (any non-zero is a refusal)
  [ "$2" != 0 ] && ok "$1 (exit $2)" || bad "$1: the script exited 0"
}
has() { # description, file, fixed text that must be in it
  grep -qF -- "$3" "$2" && ok "$1" || { bad "$1: '$3' not in $2"; sed 's/^/      | /' "$2" | tail -8; }
}
lacks() { # description, file, fixed text that must not be in it
  grep -qF -- "$3" "$2" && bad "$1: '$3' is in $2" || ok "$1"
}

# --- the SDK, the JDK and the throwaway fixtures -----------------------------------------------------------------------------
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f "$REPO/local.properties" ]; then SDK="$(sed -n 's/^sdk\.dir=//p' "$REPO/local.properties" | tail -1)"; fi
[ -n "$SDK" ] || SDK="$HOME/Android/Sdk"
RBT="$SDK/build-tools/36.0.0"
AJAR="$(ls "$SDK"/platforms/android-37.0/android.jar "$SDK"/platforms/android-*/android.jar 2>/dev/null | head -1)"
RJDK="${JAVA_HOME:-$HOME/.local/share/mise/installs/java/temurin-17.0.20+8}"
[ -x "$RJDK/bin/jarsigner" ] || RJDK="$(dirname "$(dirname "$(readlink -f "$(command -v jarsigner)")")")"
[ -x "$RJDK/bin/jarsigner" ] && [ -x "$RJDK/bin/keytool" ] || { echo "no JDK with jarsigner and keytool (JAVA_HOME=${JAVA_HOME:-unset})" >&2; exit 2; }
HAVE_SDK=1; [ -x "$RBT/apksigner" ] && [ -x "$RBT/zipalign" ] && [ -x "$RBT/aapt2" ] && [ -f "$AJAR" ] || HAVE_SDK=0
PATH="$RJDK/bin:$PATH"

umask 077
mkdir -p "$T/keys" "$T/fix" "$T/cases" "$T/jdk/bin" "$T/sdk/build-tools/36.0.0" "$T/shim"
mkkey() { # name, alias, subject: a throwaway RSA 2048 key in a PKCS12 keystore, its password (random) in a mode-600 file
  head -c 24 /dev/urandom | base64 | tr -d '\n=+/' >"$T/keys/$1.pass"
  keytool -genkeypair -alias "$2" -keyalg RSA -keysize 2048 -validity 3 -storetype PKCS12 -keystore "$T/keys/$1.p12" -storepass:file "$T/keys/$1.pass" -dname "CN=$3" >/dev/null 2>&1
}
mkkey release rehearsal "Paddock REHEARSAL KEY (not a release key)"
mkkey upload rehearsal-upload "Paddock REHEARSAL UPLOAD KEY (not an upload key)"
[ -f "$T/keys/release.p12" ] && [ -f "$T/keys/upload.p12" ] && ok "two throwaway keystores made (RSA 2048, PKCS12, outside the repository)" || { bad "keytool made no keystore"; exit 1; }
fp() { # keystore name, alias: the certificate's SHA-256, lower case, no colons
  keytool -list -keystore "$T/keys/$1.p12" -storepass:file "$T/keys/$1.pass" -alias "$2" 2>/dev/null | sed -n 's/.*(SHA-256): *//p' | tr -d ':' | tr 'A-F' 'a-f'
}
REL_FP="$(fp release rehearsal)"; UP_FP="$(fp upload rehearsal-upload)"
[ -n "$REL_FP" ] && [ -n "$UP_FP" ] && [ "$REL_FP" != "$UP_FP" ] && ok "the two keys have different certificates" || bad "certificate fingerprints: release '$REL_FP', upload '$UP_FP'"

# A tiny app bundle: a zip with the entries a real one has, one of them under base/root/META-INF (content, not signature), none a real app.
python3 - "$T/fix/paddock-0.0.0-play-unsigned.aab" <<'PY'
import random, sys, zipfile
random.seed(1)
with zipfile.ZipFile(sys.argv[1], "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nCreated-By: test\r\n\r\n")
    z.writestr("BundleConfig.pb", b"\x0a\x00")
    z.writestr("base/manifest/AndroidManifest.xml", b"manifest")
    z.writestr("base/dex/classes.dex", random.randbytes(20000))
    z.writestr("base/root/META-INF/services/example.Service", "example.Impl\n")
    z.writestr("base/res/raw/blob.bin", random.randbytes(300000))
    z.writestr("BUNDLE-METADATA/com.android.tools.build.libraries/dependencies.pb", b"deps")
PY
cat >"$T/shim/tamper.py" <<'PY'
import sys, zipfile
src, dst, name = sys.argv[1:4]
with zipfile.ZipFile(src) as a, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as b:
    names = a.namelist()
    for i in a.infolist():
        data = a.read(i)
        if i.filename == name: data += b"!"
        b.writestr(i.filename, data)
    if name not in names: b.writestr(name, b"added")
PY
python3 - "$T/fix/unaligned-unsigned.apk" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w", zipfile.ZIP_STORED) as z:
    z.writestr("a", b"x")   # a stored entry whose data starts at offset 31
PY

# The APK: aapt2 links a manifest into a real, tiny APK and zipalign aligns it the way the release build's output is aligned. Without the SDK the
# APK cases are skipped (and said to be), the bundle cases still run.
if [ "$HAVE_SDK" = 1 ]; then
  cat >"$T/fix/AndroidManifest.xml" <<'XML'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="test.throwaway.rehearsal">
  <uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
  <application android:label="rehearsal" android:hasCode="false"/>
</manifest>
XML
  if "$RBT/aapt2" link -o "$T/fix/raw.apk" --manifest "$T/fix/AndroidManifest.xml" -I "$AJAR" --version-code 1 --version-name 0.0.0-rehearsal >/dev/null 2>&1 \
     && "$RBT/zipalign" -f -P 16 4 "$T/fix/raw.apk" "$T/fix/paddock-0.0.0-play-unsigned.apk" && "$RBT/zipalign" -c -P 16 4 "$T/fix/paddock-0.0.0-play-unsigned.apk"; then
    ok "a throwaway APK made with aapt2 and zipalign (a manifest and a resource table, aligned)"
  else bad "aapt2 or zipalign could not make the throwaway APK"; HAVE_SDK=0; fi
  "$RBT/zipalign" -c -P 16 4 "$T/fix/unaligned-unsigned.apk" >/dev/null 2>&1 && bad "the unaligned fixture is aligned" || ok "the unaligned fixture is unaligned"
else
  echo "SKIP  APK cases: no build-tools 36.0.0 (apksigner, zipalign, aapt2) or android.jar under $SDK"
fi

# --- shims: the script is run with these first on PATH / as its SDK, so every tool call is logged and a case can tamper with what is signed ---
cat >"$T/jdk/bin/java" <<'SH'
#!/usr/bin/env bash
exec "$REAL_JDK/bin/java" "$@"
SH
cat >"$T/jdk/bin/keytool" <<'SH'
#!/usr/bin/env bash
printf 'keytool %s\n' "$*" >>"$SHIM_LOG"
exec "$REAL_JDK/bin/keytool" "$@"
SH
cat >"$T/jdk/bin/jarsigner" <<'SH'
#!/usr/bin/env bash
# Logs its arguments; SHIM_MODE=tamper signs a changed copy of the input (so the signature is valid and the content differs), unsigned writes the
# output without signing it. Verification always runs the real tool.
printf 'jarsigner %s\n' "$*" >>"$SHIM_LOG"
case " $* " in *" -verify "*) exec "$REAL_JDK/bin/jarsigner" "$@";; esac
args=("$@"); n=$#; in="${args[n-2]}"
case "${SHIM_MODE:-pass}" in
  pass) exec "$REAL_JDK/bin/jarsigner" "$@";;
  tamper) python3 "$SHIM_TAMPER" "$in" "$SHIM_TMP/tampered.aab" "$SHIM_ENTRY" || exit 1; args[n-2]="$SHIM_TMP/tampered.aab"; exec "$REAL_JDK/bin/jarsigner" "${args[@]}";;
  unsigned) out=""; for ((i = 0; i < n; i++)); do [ "${args[i]}" = -signedjar ] && out="${args[i+1]}"; done; cp "$in" "$out"; echo "jar signed.";;
esac
SH
cat >"$T/sdk/build-tools/36.0.0/zipalign" <<'SH'
#!/usr/bin/env bash
exec "$REAL_BT/zipalign" "$@"
SH
cat >"$T/sdk/build-tools/36.0.0/apksigner" <<'SH'
#!/usr/bin/env bash
printf 'apksigner %s\n' "$*" >>"$SHIM_LOG"
args=("$@"); n=$#
if [ "$1" = sign ] && [ "${SHIM_MODE:-pass}" = tamper ]; then
  python3 "$SHIM_TAMPER" "${args[n-1]}" "$SHIM_TMP/tampered.apk" "$SHIM_ENTRY" || exit 1; args[n-1]="$SHIM_TMP/tampered.apk"
fi
exec "$REAL_BT/apksigner" "${args[@]}"
SH
chmod +x "$T"/jdk/bin/* "$T"/sdk/build-tools/36.0.0/*
SIGN="$HERE/release-sign.sh"
SENV=(ANDROID_HOME="$T/sdk" JAVA_HOME="$T/jdk" REAL_JDK="$RJDK" REAL_BT="$RBT" SHIM_LOG="$T/shim.log" SHIM_TAMPER="$T/shim/tamper.py" SHIM_TMP="$T/shim")
REL=(PADDOCK_KEYSTORE="$T/keys/release.p12" PADDOCK_KEY_ALIAS=rehearsal PADDOCK_KS_PASS_FILE="$T/keys/release.pass")
UP=(PADDOCK_UPLOAD_KEYSTORE="$T/keys/upload.p12" PADDOCK_UPLOAD_KEY_ALIAS=rehearsal-upload PADDOCK_KS_PASS_FILE="$T/keys/upload.pass")
OUTF="$T/last.out"
sign() { env "${SENV[@]}" "$@" >"$OUTF" 2>&1; }   # env assignments, then the script and its arguments; output in $OUTF, status returned
mkcase() { # name, fixture files...: a fresh directory holding copies of the fixtures
  local d="$T/cases/$1"; shift; mkdir -p "$d"; for f in "$@"; do cp "$T/fix/$f" "$d/"; done; printf '%s' "$d"
}
STAMP="$T/stamp"; touch "$STAMP"; sleep 1

# --- the bundle, signed with the upload key ---------------------------------------------------------------------------------------
echo "# bundle"
D="$(mkcase b1 paddock-0.0.0-play-unsigned.aab)"; BEFORE="$(sha256sum "$D/paddock-0.0.0-play-unsigned.aab" | cut -d' ' -f1)"
: >"$T/shim.log"; sign "${UP[@]}" "$SIGN" "$D/paddock-0.0.0-play-unsigned.aab"; expect "a bundle is signed with the upload key" 0 $?
[ -f "$D/paddock-0.0.0-play.aab" ] && ok "the signed bundle is paddock-<version>-play.aab beside the unsigned one" || bad "no $D/paddock-0.0.0-play.aab"
[ "$(sha256sum "$D/paddock-0.0.0-play-unsigned.aab" | cut -d' ' -f1)" = "$BEFORE" ] && ok "the unsigned bundle is untouched" || bad "the unsigned bundle changed"
jarsigner -verify "$D/paddock-0.0.0-play.aab" >"$T/verify.out" 2>&1; grep -q '^jar verified\.' "$T/verify.out" && ok "jarsigner -verify (run by the test) says jar verified" || bad "jarsigner -verify: $(head -3 "$T/verify.out")"
has "SIGNATURE-bundle.txt holds the verbose verification" "$D/SIGNATURE-bundle.txt" "jar verified."
has "SIGNATURE-bundle.txt carries the upload certificate's SHA-256" "$D/SIGNATURE-bundle.txt" "$(fp upload rehearsal-upload | sed 's/../&:/g; s/:$//' | tr a-f A-F)"
lacks "the bundle was not signed with the release key" "$D/SIGNATURE-bundle.txt" "$(printf '%s' "$REL_FP" | sed 's/../&:/g; s/:$//' | tr a-f A-F)"
has "the content proof ran and passed" "$D/SIGNATURE-bundle.txt" "content check: all 6 entries outside META-INF equal the unsigned bundle's"
unzip -Z1 "$D/paddock-0.0.0-play.aab" | grep -q '^META-INF/.*\.\(RSA\|SF\)$' && ok "the signed bundle has its signature files in META-INF" || bad "no META-INF/*.SF or *.RSA in the signed bundle"
python3 - "$D/paddock-0.0.0-play-unsigned.aab" "$D/paddock-0.0.0-play.aab" <<'PY' && ok "every entry outside META-INF is byte-equal (checked again by the test)" || bad "an entry differs between the unsigned and the signed bundle"
import sys, zipfile
a, b = (zipfile.ZipFile(p) for p in sys.argv[1:3])
f = lambda z: {i.filename: z.read(i) for i in z.infolist() if not i.filename.endswith("/") and not i.filename.startswith("META-INF/")}
sys.exit(0 if f(a) == f(b) and f(a) else 1)
PY
(cd "$D" && sha256sum -c SHA256SUMS >/dev/null 2>&1) && [ "$(wc -l <"$D/SHA256SUMS")" = 2 ] && ok "SHA256SUMS lists the signed and the unsigned bundle and both match" || bad "SHA256SUMS: $(cat "$D/SHA256SUMS" 2>&1)"
has "the upload password file went to jarsigner as -storepass:file" "$T/shim.log" "-storepass:file $T/keys/upload.pass"
lacks "no password is in any argument jarsigner or keytool saw" "$T/shim.log" "$(cat "$T/keys/upload.pass")"
has "the bundle was signed as PKCS12, SHA256withRSA, SHA-256" "$T/shim.log" "-storetype PKCS12"
has "...with SHA256withRSA and SHA-256" "$T/shim.log" "-sigalg SHA256withRSA -digestalg SHA-256"

D="$(mkcase b2 paddock-0.0.0-play-unsigned.aab)"; cp "$D/paddock-0.0.0-play-unsigned.aab" "$D/paddock-0.0.0-play.aab"; rm "$D/paddock-0.0.0-play-unsigned.aab"
sign "${UP[@]}" "$SIGN" "$D/paddock-0.0.0-play.aab"; expect "a bundle not named -unsigned (as release-build.sh names it) is signed" 0 $?
[ -f "$D/paddock-0.0.0-play-signed.aab" ] && ok "its output is paddock-0.0.0-play-signed.aab and the input is not overwritten" || bad "no -signed.aab in $D"

D="$(mkcase b3 paddock-0.0.0-play-unsigned.aab)"
sign "${UP[@]}" SHIM_MODE=tamper SHIM_ENTRY=base/dex/classes.dex "$SIGN" "$D/paddock-0.0.0-play-unsigned.aab"; refused "a signed bundle whose dex differs from the unsigned one fails the content proof" $?
has "...and says which check failed" "$OUTF" "differs: base/dex/classes.dex"
sign "${UP[@]}" SHIM_MODE=tamper SHIM_ENTRY=base/root/META-INF/services/example.Service "$SIGN" "$D/paddock-0.0.0-play-unsigned.aab"; refused "a changed entry under base/root/META-INF is content and fails the proof too" $?
has "...by name" "$OUTF" "differs: base/root/META-INF/services/example.Service"
sign "${UP[@]}" SHIM_MODE=tamper SHIM_ENTRY=base/added.txt "$SIGN" "$D/paddock-0.0.0-play-unsigned.aab"; refused "an entry that only the signed bundle has fails the proof" $?
has "...by name" "$OUTF" "only in the signed bundle: base/added.txt"
D="$(mkcase b4 paddock-0.0.0-play-unsigned.aab)"
sign "${UP[@]}" SHIM_MODE=unsigned "$SIGN" "$D/paddock-0.0.0-play-unsigned.aab"; refused "a bundle that comes out unsigned is not accepted" $?
has "...and says it did not verify" "$OUTF" "did not verify cleanly"

D="$(mkcase b5 paddock-0.0.0-play-unsigned.aab)"
sign "${REL[@]}" "$SIGN" "$D/paddock-0.0.0-play-unsigned.aab"; refused "a bundle is refused when only the release key is configured" $?
has "...and the message names the upload key" "$OUTF" "PADDOCK_UPLOAD_KEYSTORE"
sign PADDOCK_UPLOAD_KEYSTORE="$T/keys/upload.p12" PADDOCK_UPLOAD_KEY_ALIAS=rehearsal-upload PADDOCK_KS_PASS_FILE="$T/keys/release.pass" "$SIGN" "$D/paddock-0.0.0-play-unsigned.aab"; refused "the release key's password does not open the upload keystore" $?
[ ! -f "$D/paddock-0.0.0-play.aab" ] && ok "...and no signed bundle was left" || bad "a signed bundle exists after the failed signing"

# --- the refusals (same for both) --------------------------------------------------------------------------------------------------
echo "# refusals"
mkdir -p "$T/fakerepo/tools"; cp "$SIGN" "$T/fakerepo/tools/release-sign.sh"; cp "$T/keys/upload.p12" "$T/keys/release.p12" "$T/fakerepo/"; ln -s "$T/fakerepo/upload.p12" "$T/keys/upload-link.p12"
D="$(mkcase r1 paddock-0.0.0-play-unsigned.aab)"
sign PADDOCK_UPLOAD_KEYSTORE="$T/fakerepo/upload.p12" PADDOCK_UPLOAD_KEY_ALIAS=rehearsal-upload PADDOCK_KS_PASS_FILE="$T/keys/upload.pass" "$T/fakerepo/tools/release-sign.sh" "$D/paddock-0.0.0-play-unsigned.aab"; refused "an upload keystore inside the repository is refused" $?
has "...and says so" "$OUTF" "the keystore is inside the repository"
sign PADDOCK_UPLOAD_KEYSTORE="$T/keys/upload-link.p12" PADDOCK_UPLOAD_KEY_ALIAS=rehearsal-upload PADDOCK_KS_PASS_FILE="$T/keys/upload.pass" "$T/fakerepo/tools/release-sign.sh" "$D/paddock-0.0.0-play-unsigned.aab"; refused "a symlink from outside to a keystore inside the repository is refused" $?
cp "$T/keys/upload.pass" "$T/keys/upload-644.pass"; chmod 644 "$T/keys/upload-644.pass"
sign PADDOCK_UPLOAD_KEYSTORE="$T/keys/upload.p12" PADDOCK_UPLOAD_KEY_ALIAS=rehearsal-upload PADDOCK_KS_PASS_FILE="$T/keys/upload-644.pass" "$SIGN" "$D/paddock-0.0.0-play-unsigned.aab"; refused "a bundle's password file that is mode 644 is refused" $?
has "...and says so" "$OUTF" "is mode 644, not 600"
sign "${UP[@]}" "$SIGN" "$D/paddock-0.0.0-play-unsigned.aab" --storepass hunter2; expect "a second argument (a password) is refused" 2 $?
sign "${UP[@]}" "$SIGN" "$D/paddock-0.0.0-play-unsigned.aab" extra; expect "any second argument is refused" 2 $?
sign "${UP[@]}" "$SIGN"; expect "no argument is a usage error" 2 $?
sign "${UP[@]}" "$SIGN" "$D/paddock-0.0.0-play.zip"; refused "a file that is neither .apk nor .aab is refused" $?
[ ! -e "$D/paddock-0.0.0-play.aab" ] && [ ! -e "$D/SHA256SUMS" ] && ok "no refusal left an output behind" || bad "a refusal left output in $D"

# --- the APK, signed with the release key -----------------------------------------------------------------------------------------
echo "# apk"
if [ "$HAVE_SDK" = 1 ]; then
  D="$(mkcase a1 paddock-0.0.0-play-unsigned.apk)"; BEFORE="$(sha256sum "$D/paddock-0.0.0-play-unsigned.apk" | cut -d' ' -f1)"
  : >"$T/shim.log"; sign "${REL[@]}" "$SIGN" "$D/paddock-0.0.0-play-unsigned.apk"; expect "an APK is signed with the release key" 0 $?
  [ -f "$D/paddock-0.0.0-play.apk" ] && ok "the signed APK is paddock-<version>-play.apk" || bad "no $D/paddock-0.0.0-play.apk"
  [ "$(sha256sum "$D/paddock-0.0.0-play-unsigned.apk" | cut -d' ' -f1)" = "$BEFORE" ] && ok "the unsigned APK is untouched" || bad "the unsigned APK changed"
  "$RBT/apksigner" verify --print-certs --min-sdk-version 26 "$D/paddock-0.0.0-play.apk" >"$T/verify.out" 2>&1
  has "apksigner verify (run by the test) passes with the release certificate" "$T/verify.out" "Signer #1 certificate SHA-256 digest: $REL_FP"
  lacks "the APK was not signed with the upload key" "$T/verify.out" "$UP_FP"
  "$RBT/apksigner" verify -v --min-sdk-version 26 "$D/paddock-0.0.0-play.apk" 2>&1 | grep -q 'v2 scheme (APK Signature Scheme v2): true' && ok "v2 is on" || bad "v2 is not on"
  "$RBT/apksigner" verify -v --min-sdk-version 26 "$D/paddock-0.0.0-play.apk" 2>&1 | grep -q 'v1 scheme (JAR signing): false' && ok "v1 is off" || bad "v1 is not off"
  has "SIGNATURE.txt holds the verification and the content proof" "$D/SIGNATURE.txt" "content check: every entry outside META-INF equals the unsigned APK's"
  (cd "$D" && sha256sum -c SHA256SUMS >/dev/null 2>&1) && [ "$(wc -l <"$D/SHA256SUMS")" = 2 ] && ok "SHA256SUMS lists the signed and the unsigned APK and both match" || bad "SHA256SUMS: $(cat "$D/SHA256SUMS" 2>&1)"
  has "the release password file went to apksigner as file:" "$T/shim.log" "--ks-pass file:$T/keys/release.pass"
  lacks "no password is in any argument apksigner saw" "$T/shim.log" "$(cat "$T/keys/release.pass")"
  # an APK and a bundle of one cut share a directory: the second signing keeps the first one's lines in SHA256SUMS
  cp "$T/fix/paddock-0.0.0-play-unsigned.aab" "$D/"; sign "${UP[@]}" "$SIGN" "$D/paddock-0.0.0-play-unsigned.aab"
  [ "$(wc -l <"$D/SHA256SUMS")" = 4 ] && (cd "$D" && sha256sum -c SHA256SUMS >/dev/null 2>&1) && ok "signing the bundle after the APK keeps the APK's lines in SHA256SUMS (four, all matching)" || bad "SHA256SUMS: $(cat "$D/SHA256SUMS" 2>&1)"
  [ -f "$D/SIGNATURE.txt" ] && [ -f "$D/SIGNATURE-bundle.txt" ] && ok "SIGNATURE.txt (APK) and SIGNATURE-bundle.txt are separate files" || bad "SIGNATURE files in $D: $(ls "$D")"

  D="$(mkcase a2 paddock-0.0.0-play-unsigned.apk)"
  sign "${REL[@]}" SHIM_MODE=tamper SHIM_ENTRY=resources.arsc "$SIGN" "$D/paddock-0.0.0-play-unsigned.apk"; refused "a signed APK whose entry differs from the unsigned one fails the content proof" $?
  has "...and says so" "$OUTF" "content check FAILED"
  D="$(mkcase a3 unaligned-unsigned.apk)"
  sign "${REL[@]}" "$SIGN" "$D/unaligned-unsigned.apk"; refused "an unaligned APK is refused" $?
  has "...and says why" "$OUTF" "is not aligned"
  D="$(mkcase a4 paddock-0.0.0-play-unsigned.apk)"
  sign "${UP[@]}" "$SIGN" "$D/paddock-0.0.0-play-unsigned.apk"; refused "an APK is refused when only the upload key is configured (the APK takes the release key)" $?
  has "...and the message names the release key's variable" "$OUTF" "PADDOCK_KEYSTORE"
  cp "$T/keys/release.pass" "$T/keys/release-640.pass"; chmod 640 "$T/keys/release-640.pass"
  sign PADDOCK_KEYSTORE="$T/keys/release.p12" PADDOCK_KEY_ALIAS=rehearsal PADDOCK_KS_PASS_FILE="$T/keys/release-640.pass" "$SIGN" "$D/paddock-0.0.0-play-unsigned.apk"; refused "an APK's password file that is mode 640 is refused" $?
  has "...and says so" "$OUTF" "is mode 640, not 600"
  sign PADDOCK_KEYSTORE="$T/fakerepo/release.p12" PADDOCK_KEY_ALIAS=rehearsal PADDOCK_KS_PASS_FILE="$T/keys/release.pass" "$T/fakerepo/tools/release-sign.sh" "$D/paddock-0.0.0-play-unsigned.apk"; refused "a release keystore inside the repository is refused" $?
  has "...and says so" "$OUTF" "the keystore is inside the repository"
  sign "${REL[@]}" "$SIGN" "$D/paddock-0.0.0-play-unsigned.apk" --ks-pass pass:hunter2; expect "an APK given a password argument is refused" 2 $?
else
  echo "SKIP  the signed-APK cases (apksigner verification, v2 only, the APK content proof, the unaligned and tamper cases): they need aapt2, zipalign and apksigner from build-tools 36.0.0"
fi

# --- nothing was written in the repository ----------------------------------------------------------------------------------------
echo "# repository"
STRAY="$(find "$REPO" \( -path "$REPO/.git" -o -path "$REPO/build" -o -path "$REPO/.gradle" -o -name build -type d \) -prune -o -newer "$STAMP" \
  \( -name '*.p12' -o -name '*.jks' -o -name '*.pass' -o -name 'SIGNATURE*.txt' -o -name SHA256SUMS -o -name 'SHA256SUMS.new' -o -name '*.aab' -o -name '*-unsigned.apk' -o -name '.cmp[12]' \) -print 2>/dev/null)"
[ -z "$STRAY" ] && ok "no key, password, signature, checksum, bundle or APK file was written inside the repository" || { bad "files written inside the repository: $STRAY"; }
git -C "$REPO" ls-files --error-unmatch -- '*.p12' '*.jks' '*.aab' >/dev/null 2>&1 && bad "a keystore or bundle is tracked by git" || ok "no keystore or bundle is tracked by git"

# --- run-release-smoke.py --sockets-only, end to end against a fake adb (HOME points at a directory whose Android/Sdk/platform-tools/adb is the fake) ---
echo "# sockets-only"
FH="$T/home"; mkdir -p "$FH/Android/Sdk/platform-tools" "$T/proc" "$T/proc-empty"
cat >"$FH/Android/Sdk/platform-tools/adb" <<'SH'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$FAKE_ADB_LOG"
[ "$1" = -s ] && shift 2
case "$*" in
  "shell pm list packages -i -U io.github.tuthan.paddock") [ -n "${FAKE_NOPKG:-}" ] || echo "package:io.github.tuthan.paddock installer=${FAKE_INSTALLER:-com.android.vending} uid:10179" ;;
  "shell pidof io.github.tuthan.paddock") echo 4242 ;;
  "shell cat /proc/net/tcp"|"shell cat /proc/net/tcp6"|"shell cat /proc/net/udp"|"shell cat /proc/net/udp6") cat "$FAKE_PROC/$(basename "${@: -1}")" ;;
  *) echo "fake adb: unexpected call: $*" >&2; exit 1 ;;
esac
SH
chmod +x "$FH/Android/Sdk/platform-tools/adb"
HDR='  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode'
for f in tcp tcp6 udp udp6; do printf '%s\n' "$HDR" >"$T/proc/$f"; printf '%s\n' "$HDR" >"$T/proc-empty/$f"; done
# the app's own loopback sockets (not listed), and another uid's connection to 8.8.8.8 (not the app's, so not listed)
printf '   0: 0100007F:C350 0100007F:13AD 01 00000000:00000000 00:00000000 00000000 10179        0 1001 1 0000000000000000 20 4 30 10 -1\n   1: 0F02000A:C351 08080808:01BB 01 00000000:00000000 00:00000000 00000000 10180        0 1002 1 0000000000000000 20 4 30 10 -1\n' >>"$T/proc/tcp"
printf '   0: 00000000000000000000000001000000:C352 00000000000000000000000001000000:1F90 01 00000000:00000000 00:00000000 00000000 10179        0 1003 1 0000000000000000 20 4 30 10 -1\n' >>"$T/proc/tcp6"
smoke() { # out-dir, then env assignments: --sockets-only for 0.03 minutes against the fake adb
  local out="$1"; shift; : >"$T/adb.log"
  env HOME="$FH" FAKE_ADB_LOG="$T/adb.log" FAKE_PROC="$T/proc" "$@" python3 -I "$HERE/run-release-smoke.py" FAKESERIAL --sockets-only --minutes 0.03 "$out" >"$OUTF" 2>&1
}
smoke "$T/sock1"; expect "--sockets-only on a Play-installed build with only loopback sockets" 0 $?
has "play-sockets.txt says no remote address beyond loopback" "$T/sock1/play-sockets.txt" "remote addresses beyond loopback: 0"
has "...and names the Play installer and the uid" "$T/sock1/play-sockets.txt" "(uid 10179) on FAKESERIAL; installer com.android.vending"
lacks "...another uid's connection is not in it" "$T/sock1/play-sockets.txt" "8.8.8.8"
lacks "...and it is not marked as no measurement" "$T/sock1/play-sockets.txt" "NOT A MEASUREMENT"
grep -qE '^(install|uninstall|push|pull|reverse|forward) |shell (am|input|pm (grant|clear|uninstall)|run-as|settings)|exec-out|logcat' "$T/adb.log" && bad "the mode drove or changed the phone: $(grep -E '^(install|uninstall)|shell (am|input|pm (grant|clear|uninstall)|run-as|settings)|exec-out|logcat' "$T/adb.log" | head -3)" || ok "it only listed the package, read /proc/net and asked for the pid: nothing installed, driven or logged"
smoke "$T/sock2" FAKE_INSTALLER=null; expect "a sideloaded build is exit 1" 1 $?
has "...and its file starts NOT A MEASUREMENT" "$T/sock2/play-sockets.txt" "NOT A MEASUREMENT: the installer is null"
smoke "$T/sock3" FAKE_PROC="$T/proc-empty"; expect "unreadable /proc/net is exit 1" 1 $?
has "...and says the list says nothing" "$T/sock3/play-sockets.txt" "no /proc/net row of any uid could be read"
smoke "$T/sock4" FAKE_NOPKG=1; expect "no installed package is exit 2" 2 $?
[ ! -e "$T/sock4/play-sockets.txt" ] && ok "...and writes no file" || bad "a file was written for a missing package"

# --- the other release scripts' own tests -----------------------------------------------------------------------------------------
echo "# smoke sampler and listing check"
python3 -I "$HERE/run-release-smoke.py" --self-test >"$OUTF" 2>&1; rc=$?; expect "run-release-smoke.py --self-test (the /proc/net parser and the --sockets-only report)" 0 $rc; [ $rc = 0 ] || sed 's/^/      | /' "$OUTF" | tail -12
python3 -I "$HERE/check-listing.py" --self-test >"$OUTF" 2>&1; rc=$?; expect "check-listing.py --self-test (lengths, PNG headers, sizes, hostnames)" 0 $rc; [ $rc = 0 ] || sed 's/^/      | /' "$OUTF" | tail -12
python3 -I "$HERE/check-listing.py" >"$OUTF" 2>&1; rc=$?; expect "check-listing.py on the real listing text (images still to be made are reported, not failed)" 0 $rc; [ $rc = 0 ] || sed 's/^/      | /' "$OUTF" | tail -12

[ "$fail" = 0 ] && echo "all release script tests passed" || echo "release script tests FAILED"
exit "$fail"
