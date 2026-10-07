# Lock Paddock (2026-10-07, the user's request)

Paddock can ask for the phone's own screen lock (fingerprint, face, PIN, pattern or password) before it shows anything. It is **Free**, **off by default**, and it adds **no dependency and no code of its own**: Paddock keeps no PIN, no hash and no key for it, so there is no secret of Paddock's to lose, reset or forget.

Why it matters here: Paddock holds an SSH key that can start and answer agents on the user's own machine. Someone holding the phone after it was unlocked (a borrowed phone, a phone left open on a desk) could otherwise approve a prompt on the desktop.

## What the user sees

- **Settings → Lock Paddock** (`Settings.kt`, copy in `AppLockCopy`): a switch, and when it is on, **Ask again after**: Immediately, 1 minute (default), 5 minutes or 15 minutes. Turning it **on or off** asks the phone for the user's credential first, so a stranger with the open phone cannot switch the lock off. Turning it on leaves the app open; turning it off opens it.
- **No screen lock on the phone:** the switch is disabled (unless the lock is already on, so it can still be turned off) and a banner says "This phone has no screen lock. Set a PIN, pattern, password or fingerprint in Android's settings first; Paddock uses the one the phone has." with **Open security settings**. Paddock never offers its own PIN as a substitute.
- **Covered:** one screen, "Paddock is locked", with one **Unlock** button (Primary). The phone's prompt opens by itself when Paddock comes to the front covered, and **Unlock** opens it again after the user backed out. A wrong try is the phone's to count (it enforces its own limit and delay); Paddock only shows "That did not work. Try again." when the phone refused to ask.
- **Back** on the lock screen leaves the app (`moveTaskToBack`), never the screens underneath.

## How it works

| Piece | Where | What it does |
| --- | --- | --- |
| `AppLock` | `core/applock/AppLock.kt` | The pure decision: locked or not, and when it locks again. Tested in `AppLockTest` without Android |
| `DeviceAuth` | `app/applock/DeviceAuth.kt` | Asks the phone. Android 11+ (API 30): platform `android.hardware.biometrics.BiometricPrompt` with a weak-or-better biometric **or** the device credential, one prompt, PIN as the fallback. Android 8–10: the system's confirm-credential screen (`KeyguardManager.createConfirmDeviceCredentialIntent`), which takes the PIN, pattern or password |
| `AppLockGate` | `app/AppLockGate.kt` | Wraps the whole UI: asks once each time the app comes to the front covered, shows the lock screen, drops the lock when the phone's screen lock is gone |
| `AppLockScreen` | `ui/screens/AppLockScreen.kt` | A full-screen **dialog window**, `SecureFlagPolicy.SecureOn` |

**A permission this needs.** The platform `BiometricPrompt` refuses to open for an app that does not declare `android.permission.USE_BIOMETRIC` ("Must have USE_BIOMETRIC permission"), so the manifest declares it (the sixth permission; `tools/check-release-apk.py` expects it, `docs/release.md` lists it). It is a *normal* permission, granted at install with no prompt, and it is only the declaration: Paddock never reads a fingerprint or a face, the phone's prompt does and answers yes or no. The first build of this feature did not declare it, and nothing but a test that opens the real prompt could show it: the lock could not have been turned on from Android 11 up. `DeviceAuthTest` is that test. Android 8 to 10's credential screen needs no permission.

Rules `AppLock` keeps (each has a test):

- **Cold start is locked** when the lock is on, and the first frames before the settings are read are covered too, so the herd never flashes before the lock does.
- **Away long enough:** leaving the app starts a monotonic stopwatch (`SystemClock.elapsedRealtime`, so changing the date changes nothing); coming back covers the screens when the time away reached the chosen timeout, and never before `MIN_AWAY_MILLIS` (3 s): a rotation, a system dialog or the camera's permission prompt is not "leaving", even at **Immediately**.
- **Not while asking:** the phone's credential screen is another activity, so Paddock goes to the back while the user types the PIN. Between asking and the answer nothing counts as leaving; otherwise a right PIN would lock the app again on the way back.
- **A dialog window, not a layer:** it sits above any other dialog that was open when the app was covered (a confirmation, the Pro sheet), takes focus and the keyboard (`clearFocus`, keyboard hidden), and is blank in recent apps and screenshots.
- **Nothing runs underneath:** the SSH connection is released while the lock is up (`AppGraph`: the connection follows `foreground && !locked`) and comes back on unlock, so a covered app does not keep reading, answering or asking. The screens stay composed, so the route, the draft and an open terminal are where the user left them.
- **No way to lock itself out:** when the lock is on and the phone's screen lock was removed, nothing can be asked; the app opens, the lock is turned off, and one toast says so (`AppLockCopy.DROPPED`).
- **Saved before it is believed:** `setAppLock` writes the setting and reports a failed write, so Settings never shows a lock a restart would not have.

While the lock is on, `SecureWindow` sets `FLAG_SECURE` for the **whole app**: no screenshot and no recent-apps preview of any screen. That is stronger than the existing "Protect sensitive screens" setting, which covers only the screens that show commands and output, and it ends with the lock.

## What it does not do

- **It does not encrypt anything.** Paddock's files are app-private and protected by Android's file-based encryption, exactly as before. The lock stops a person with the open phone, not someone with the phone's storage or root.
- **A damaged settings file means the lock is off.** `FileAppSettingsStore.load` returns the defaults for an unreadable file (it always has, for every setting). Anyone who can rewrite Paddock's private files has already got past what an app lock can defend.
- **Widgets and notifications are not covered.** A home-screen widget (Pro) and a notification show what they show; "Hide prompt text on the lock screen" under Alerts decides what a notification says on the lock screen. Settings says so in one line (`AppLockCopy.SCOPE`).
- **Android 8–10 shows the credential screen, not a fingerprint prompt.** A biometric-only prompt cannot fall back to the PIN before Android 11 without a second button, so the older phones use the system's own credential screen, which most phones answer with the fingerprint reader too.
- **It does not lock the machine.** The desktop's herdr, the SSH key on the phone and the connection are untouched; this is only about who may use Paddock on this phone.

## Tests

- `core`: `AppLockTest` (14: locked at start, open when off, the timeout, 3 s floor, rotation, authenticating, cancelled, on does not lock, off opens), `AppSettingsTest` (the two fields, a hand-edited timeout falls to 1 minute, never to "never").
- Instrumentation: `AppLockScreenTest` (the screen's words and one button, Unlock off while the phone's prompt shows, a failure said in words with Unlock still on, Back leaves the app rather than reaching the screens, and a SemanticsAudit dark and light: the title is a heading and the message a live region), `SettingsTest` (Lock Paddock off and on with the announced state and a 48 dp target, a tap asks for the change, no screen lock disables the switch and Open security settings works, an already-on lock can still be turned off, the timeout choice, audits for lock on and no screen lock).
- `DeviceAuthTest` (instrumentation, needs a screen lock on the device and skips without one): the real system prompt opens without a refusal and backing out of it answers `Cancelled`. Run on the API 26 and API 36 emulators with `adb shell locksettings set-pin 1234` (and `locksettings clear --old 1234` afterwards); it never sets or clears a lock itself.
- **Driven by hand on the API 36 emulator (2026-10-07, debug build, a PIN set with `locksettings`, the app's data seeded with two machines):** Settings > Lock Paddock on, the system prompt opened and the PIN `1234` turned it on (settings file: `appLock: true`; the whole window then blanks in a screenshot, which is the app-wide `FLAG_SECURE`); with **Immediately**, leaving for 5 s and coming back showed the system prompt by itself; Back out of it left "Paddock is locked" with **Unlock**; **Unlock** and the PIN returned to Settings exactly where it was left; a rotation to landscape and back did not lock; clearing the PIN with the lock on and opening the app opened it and wrote `appLock: false`.
- **Driven by hand on the API 26 emulator (same day, PIN `1234`):** the lock turned on through the system's credential screen ("Turn on the lock"); a cold start with the lock on opened that screen by itself; **Cancel** (the screen's first Back only closes its keyboard) left "Paddock is locked" with **Unlock**; **Unlock** and the PIN opened Home. Found and fixed there: Paddock stops behind that full-screen activity, which reset the gate's "already asked" flag, so cancelling asked again at once and for ever and **Unlock** was never reachable. The flag is now kept while the phone's prompt is the reason the app went to the back (`AppLockGate`). Android 11 and later never showed it: its prompt is a dialog, not an activity.
- **Not run on a phone:** a real fingerprint or face, a real Android 8–10 phone, a rotation with the credential screen up, and TalkBack through the prompt.

## Decisions to confirm

1. Free, not Pro (a lock is the user's protection, not a feature to sell).
2. The phone's lock only; no in-app PIN (a second secret to store and to recover).
3. Whole-app `FLAG_SECURE` while on.
4. 1 minute as the default timeout.
