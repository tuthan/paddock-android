#!/usr/bin/env bash
# AC-07.4 on an emulator: with a PIN set and sensitive notification content hidden on the lock screen, a Paddock alert shows only
# its generic words there. Posts two alerts through the real notifier (tests in LockScreenFixtureTest), one with the redaction on
# and one with it off as the control, locks the device, and reads the lock screen with `uiautomator dump` and a screenshot.
#   tools/check-lockscreen.sh [adb-serial]
# It sets a PIN (1234) and the system's "hide sensitive notification content" setting on the emulator and puts both back at the
# end, whatever happens. Use a disposable emulator.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"; . "$HERE/harness.sh"
SERIAL="${1:-emulator-5570}"
ADB="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb -s $SERIAL"
PKG=io.github.tuthan.paddock; RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
OUT="$OUT_BASE/lockscreen-$(date +%Y%m%d-%H%M%S)-${SERIAL#emulator-}"; mkdir -p "$OUT"
LOG="$OUT/log.txt"; say() { printf '%s %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$LOG"; }
PIN=1234; fail=0
check() { if eval "$2"; then say "PASS $1"; else say "FAIL $1"; fail=1; fi; }

WAS_PRIVATE=$($ADB shell settings get secure lock_screen_allow_private_notifications | tr -d '\r')
unlock() { $ADB shell input keyevent KEYCODE_WAKEUP; sleep 1; $ADB shell input swipe 540 2000 540 600 200; sleep 1; $ADB shell input text $PIN; $ADB shell input keyevent KEYCODE_ENTER; sleep 2; }
cleanup() {
  unlock >/dev/null 2>&1
  $ADB shell locksettings clear --old $PIN >/dev/null 2>&1
  if [ "$WAS_PRIVATE" = null ] || [ -z "$WAS_PRIVATE" ]; then $ADB shell settings delete secure lock_screen_allow_private_notifications >/dev/null 2>&1
  else $ADB shell settings put secure lock_screen_allow_private_notifications "$WAS_PRIVATE" >/dev/null 2>&1; fi
  $ADB shell cmd notification cancel >/dev/null 2>&1
}
trap cleanup EXIT

$ADB shell locksettings set-pin $PIN >/dev/null 2>&1 || { say "could not set a PIN (a lock is already set?)"; exit 1; }
$ADB shell settings put secure lock_screen_allow_private_notifications 0
$ADB shell settings put secure lock_screen_show_notifications 1
say "PIN set; sensitive notification content hidden on the lock screen (was: ${WAS_PRIVATE:-unset})"

lock_screen() { # variant -> dumps the lock screen text to $OUT/$1.xml and a screenshot
  $ADB shell am instrument -w -e class "$PKG.notify.LockScreenFixtureTest#$2" "$RUNNER" >"$OUT/$1-fixture.txt" 2>&1
  grep -q "^OK" "$OUT/$1-fixture.txt" || { say "FAIL the fixture did not run: $(tail -3 "$OUT/$1-fixture.txt" | tr '\n' ' ')"; fail=1; return 1; }
  $ADB shell input keyevent KEYCODE_SLEEP; sleep 2
  $ADB shell input keyevent KEYCODE_WAKEUP; sleep 3
  $ADB shell uiautomator dump /sdcard/lock-$1.xml >/dev/null 2>&1; $ADB pull /sdcard/lock-$1.xml "$OUT/$1.xml" >/dev/null 2>&1
  $ADB exec-out screencap -p >"$OUT/$1.png"
  $ADB shell rm -f /sdcard/lock-$1.xml
  unlock
}

lock_screen redacted postsOneRedactedAlertAndLeavesIt
check "AC-07.4 the lock screen shows the generic title" 'grep -q "Paddock: attention on laptop" "$OUT/redacted.xml"'
check "AC-07.4 and the generic text" 'grep -q "An agent needs you." "$OUT/redacted.xml"'
check "AC-07.4 and nothing of the agent's title" '! grep -qi "users table\|production" "$OUT/redacted.xml"'
lock_screen control postsOneUnredactedAlertAndLeavesIt
check "control: with the redaction off the same screen reader sees the agent's title, so the check above can tell" 'grep -q "Drop the users table" "$OUT/control.xml"'
[ "$fail" = 0 ] && say "ALL CHECKS PASSED" || say "SOME CHECKS FAILED"
echo "results in $OUT"
exit $fail
