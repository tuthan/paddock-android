# Release cut

How the Paddock release artefacts are built, proven reproducible, signed and checked, and what has and has not been done: the `foss` APK (GitHub, F-Droid, IzzyOnDroid) and, for Google Play, the `play` APK and app bundle. Phase 10, AC-10.4 to AC-10.6, extended per flavor in Phase 13. Nothing here publishes anything: the last steps (a GitHub release, a Play upload) are a person's decision and are not scripted. The Google Play route is Phase 16 (vault `phases/16-play-publication.md`); its pipeline half is on this page, from "Version table" on.

## The steps

```sh
# 1. The baseline gate (wrapper, SDK revisions, pins, script self-tests, unit tests, lint, debug APK).
tools/check.sh --offline

# 2. Two clean, daemonless builds (always with -PpaddockUnlocked=false, so a published foss APK is the free version) of the unsigned release APK, compared byte for byte; the toolchain tuple written next to them.
#    --flavor foss (default) is the GitHub, F-Droid and IzzyOnDroid artefact; --flavor play is the billing build and also builds and compares the app bundle.
#    --publish refuses a dirty tree or a HEAD that is not the tag v<versionName>: the APK embeds the commit and F-Droid builds from the tag.
#    Each run empties build/release/, so move one flavor's output out before building the other.
tools/release-build.sh --offline          # build/release/{paddock-<v>[-play]-unsigned.apk, REPRODUCIBLE.txt, TOOLCHAIN.txt, build-{1,2}.log}

# 3. Sign with keys that live outside the repository (Key policy below). An APK takes the RELEASE key; an app bundle takes the separate UPLOAD key. Each password is read
#    from its own mode-600 file, never an argument or variable; the script takes the unsigned file and nothing else.
PADDOCK_KEYSTORE=/path/outside/repo/release.p12 PADDOCK_KEY_ALIAS=paddock PADDOCK_KS_PASS_FILE=/path/to/release-pass \
  tools/release-sign.sh build/release/paddock-<v>-unsigned.apk     # paddock-<v>.apk, SIGNATURE.txt, SHA256SUMS (play: paddock-<v>-play-unsigned.apk gives paddock-<v>-play.apk)
PADDOCK_UPLOAD_KEYSTORE=/path/outside/repo/upload.p12 PADDOCK_UPLOAD_KEY_ALIAS=upload PADDOCK_KS_PASS_FILE=/path/to/upload-pass \
  tools/release-sign.sh build/release/paddock-<v>-play.aab         # play only: paddock-<v>-play-signed.aab, SIGNATURE-bundle.txt; SHA256SUMS keeps the APK's lines and gains the bundle's
#    release-build.sh --sign signs the APK only. It names the unsigned bundle paddock-<v>-play.aab, so the signed one is ...-play-signed.aab; a file named ...-unsigned.aab would
#    give paddock-<v>-play.aab, as the APK does (release-build.sh does not name it that way yet).

# 4. Static privacy and manifest inspection of the APK that will be published.
tools/check-release-apk.py build/release/paddock-<v>.apk build/release/PRIVACY-MANIFEST.txt                 # foss, the strict default
tools/check-release-apk.py build/release/paddock-<v>-play.apk build/release/PRIVACY-MANIFEST-play.txt --flavor play   # the named additions only (docs/billing.md)

# 5. Release smoke and the runtime half of the privacy checklist, on each device (an emulator or a phone with adb). Run against the foss APK; the play APK's sockets are measured separately (docs/billing.md).
tools/run-release-smoke.py <adb-serial> build/release/paddock-<v>.apk <out-dir>
# 5b. play only: the Play build's sockets, on a phone with Google Play and the build Play installed. It installs and drives nothing; a person does the purchase sequence while it samples.
tools/run-release-smoke.py <adb-serial> --sockets-only --minutes 6 <out-dir>    # <out-dir>/play-sockets.txt: every remote address beyond loopback, with a reverse lookup
```

`release-sign.sh` refuses a keystore inside the repository (a symlink into it too), a password file that is not mode 600, any argument beyond the one file (a key or a password is never an argument), a file that is neither `.apk` nor `.aab`, and, for an APK, an unaligned input. It signs an APK with APK Signature Scheme v2 and v3 only (minSdk 26 makes v1 unnecessary; v4 is for incremental installs and would leave an extra, unpublished file), runs `apksigner verify --print-certs --min-sdk-version 26`, and proves signing changed nothing but `META-INF` by comparing every entry with the unsigned APK. It signs a bundle with `jarsigner` (PKCS12, `SHA256withRSA`, SHA-256, password by `-storepass:file`, no timestamp because nothing here contacts a server), runs `jarsigner -verify -verbose -certs`, accepts only the line `jar verified.` (jarsigner exits 0 for an unsigned jar), appends the signer certificate's fingerprints (what Play's upload-key page asks for), and proves, from the zip's own entries, that every entry outside the top-level `META-INF/` equals the unsigned bundle's in name and bytes: `base/root/META-INF/...` is content and is compared. `tools/test-release-scripts.sh` runs all of this with throwaway keys, including the cases that must fail (a changed entry, an unsigned result, each refusal) and a check that no password reaches a tool's argument list.

## What "reproducible" means here

`tools/release-build.sh` runs two clean builds per artefact (`clean` plus `:app:assembleFossRelease`, or `clean` plus `:app:assemblePlayRelease` and `:app:bundlePlayRelease`; `--no-daemon`, `-PpaddockUnlocked=false`, nothing carried over) of the same commit on the same machine and toolchain, and claims:

- `foss`: an **identical unsigned APK**.
- `play`: an **identical unsigned APK** and an **identical app bundle** (`.aab`). The bundle is the artefact uploaded to Google Play; the APK Play delivers to a phone is built by Play and is not covered.

The signed APK is not compared across builds: a signature carries randomness and a signing time, so two signed copies of the same contents can differ; instead the signed APK's contents are proven equal to the unsigned one's. A different machine, a different JDK build or a different SDK revision is **not** claimed to give the same bytes. The comparison is the first thing to re-run on a release machine.

Rehearsal results, each from a dirty working tree, so rehearsals and not the 1.0 record. Before the flavors, 2026-10-03, commit `c98c3f8` (`clean :app:assembleRelease`, one APK):

```
build 1: ee5f1c0dd6e3a72c5cfb01ac087bcb7621a21c9fc8bd471230577b14baff0348
build 2: ee5f1c0dd6e3a72c5cfb01ac087bcb7621a21c9fc8bd471230577b14baff0348
verdict: IDENTICAL
```

Per flavor, 2026-10-04, commit `cb3cd89` plus the uncommitted working tree (`release-build.sh --flavor foss` and `--flavor play`):

```
foss  build 1: cd6c15c217872ad80cfd78f0ef8c79e38238b5ddd1c73230dc3b2bd346106701
foss  build 2: cd6c15c217872ad80cfd78f0ef8c79e38238b5ddd1c73230dc3b2bd346106701
foss  verdict: IDENTICAL
play  build 1: 34f6c201d749062d14f75a1c6260a4051ec9b1a31001473d8030a3b2b13936a3
play  build 2: 34f6c201d749062d14f75a1c6260a4051ec9b1a31001473d8030a3b2b13936a3
play  bundle 1: 8aba34ea49d29f38877f0865dbcefcd968eadbd1c977116f39c34d101cad6977
play  bundle 2: 8aba34ea49d29f38877f0865dbcefcd968eadbd1c977116f39c34d101cad6977
play  verdict: IDENTICAL (APK and bundle)
```

R8 (`isMinifyEnabled`) and resource shrinking are on for the release type. They shorten resource paths (`res/xml/backup_rules.xml` is `res/Qq.xml`), which is why `check-release-apk.py` finds files through the resource table. `dependenciesInfo` is off in both the APK and the bundle (`build-decision-record.md`): AGP would otherwise embed a block, encrypted to a Google key, that cannot be reviewed or reproduced.

## Toolchain tuple (what a release page publishes)

| | |
| --- | --- |
| JDK | Temurin 17.0.20+8 |
| Gradle | 9.6.1 (wrapper, hash-pinned distribution) |
| Android Gradle Plugin | 9.3.3 |
| Kotlin | 2.4.20 |
| Build tools | 36.0.0 |
| Platform | `android-37.0`, revision 2 |
| minSdk / targetSdk / compileSdk | 26 / 37 / 37 |
| Dependencies | locked (`*.lockfile`, strict) and checksum-verified (`gradle/verification-metadata.xml`); signatures not verified, see `build-decision-record.md` |

`tools/release-build.sh` writes the same tuple to `build/release/TOOLCHAIN.txt`.

## Target SDK and Billing Library policy, re-checked 2026-10-12

First read 2026-10-03, re-read 2026-10-12. Google Play requires new apps and updates to target API 36 from 2026-08-31, with an extension to 2026-11-01 for developers who ask; an existing app needs API 35 to stay visible to new users. Paddock targets **37**, so it meets the requirement with a level to spare. Play Billing Library 8 or later is required from 2026-08-31; the catalog has **9.1.0** (`docs/billing.md`). Android 17 behaviour at target 37 (edge-to-edge enforced, `ACCESS_LOCAL_NETWORK` for LAN sockets) is handled and listed in `build-decision-record.md`. For a GitHub-only release no store policy applies; for the Play route (Phase 16) both do. Re-check the policy pages on the day a bundle is uploaded: the date above is when they were last read, not a standing fact.

## What the release APK is checked for

`check-release-apk.py` (static, run on the APK itself):

- the permission set is exactly `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, `ACCESS_LOCAL_NETWORK`, `CAMERA` and `USE_BIOMETRIC`: nothing else (no log, storage, contacts or location access). `USE_BIOMETRIC` is a normal permission granted at install with no prompt; the app lock's system prompt (`docs/app-lock.md`) will not open without it, and Paddock never reads a fingerprint or a face itself. `CAMERA` belongs to the in-app scanner (Phase 14) and to **Take a photo** on the composer (Phase 15, the same permission, asked for in context when that choice is made): it is never asked for at launch, and `android.hardware.camera` and `android.hardware.camera.autofocus` (which `CAMERA` implies) are declared optional so a phone without a camera, or with a fixed-focus one, still installs the app; nothing else uses the camera, pasting a pairing link does what the scanner does without it, and choosing a photo from the picker does what Take a photo does without it. The `play` flavor adds `com.android.vending.BILLING` and nothing else;
- not debuggable; `allowBackup=false`; `usesCleartextTraffic=false`; a network security config (system CAs only, cleartext off) and both backup rule files (everything excluded) are in the APK;
- the exported components are the launcher activity, the UnifiedPush receiver and the three widget providers. One library receiver is also exported, `androidx.profileinstaller.ProfileInstallReceiver`; it requires the signature-level `DUMP` permission, so only the shell can reach it, and the check accepts it only while that guard is present;
- `<queries>` are exactly the UnifiedPush distributor lookup intent and the ntfy app's package (`io.heckel.ntfy`, so "Open in the ntfy app" can address it explicitly); the `play` flavor adds the two billing intents;
- no analytics, crash reporting, advertising, attribution, billing, Play-services or datatransport SDK, no Work or Glance library, and no Compose tooling or test code in the dex. In the `play` flavor the billing, Play-services and datatransport packages are allowed only as the named list in `check-release-apk.py`, together with the two extra `<queries>` intents and six non-exported library components; anything else, and any firebase package, fails (`docs/billing.md`);
- every host script in `assets/` has the SHA-256 that `host/SOURCE.json` pins, and its `.sha256` sibling says the same (the app refuses any other hash on the machine);
- every URL string in the dex and assets is on an allow-list: documentation links inside libraries, XML namespaces, font licence links, the project's own example ntfy host and `ntfy.sh` (the public server the alert setup offers as a choice and shows as an address; the app never connects to it, the relay on the user's machine does). None is a host the app calls.

`run-release-smoke.py` (on a running device, against the signed `foss` APK; it uses an isolated HOME, a throwaway sshd on loopback and a herdr session it creates and deletes; `claude` is a stand-in, never a real agent):

- installs, launches, adds a machine, makes the phone key, authorizes it, connects, compares the fingerprint the phone shows with the sshd's, trusts, installs the relay (the hash on screen is the pinned one), shows the herd, Output, and the Terminal tab observing; opens an alert link (fresh read; and a link to an agent that is gone);
- the app is not debuggable and `run-as` is refused;
- every socket the app's uid opened (sampled from `/proc/net` every 0.4 s) went to the sshd and nowhere else: no analytics, no crash upload, no third-party call;
- `FLAG_SECURE` is on while Output is shown and while Home shows a captured prompt, and is off once no prompt is shown;
- the whole run's logcat holds none of the seeded agent title, none of the seeded agent output, no private-key text and no password;
- what the phone granted is within the manifest's four.

The socket check is not expected to pass unchanged on the `play` APK: billing reaches the Play Store app over IPC, but the library's logging backend can open sockets from the app's uid. The `play` measurement is open and is written up in `docs/billing.md`; until it is done, "nothing leaves but SSH" is claimed for `foss` only. It is taken with `run-release-smoke.py --sockets-only` on the Play-installed build (step 5b, Play section below). That sampler sees a socket only while it is open, so a connection that opens and closes between two samples is missing: an empty list is what was seen, not a proof of absence.

## Key policy

Two keys, each generated by the person who publishes, on a machine they control, as RSA with 2048 bits or more (Play's minimum for both; read 2026-10-12). Neither is ever in this repository, in an environment variable or on a command line. Create each with a validity Play's page accepts (read it the day the key is made).

- **The release key** signs every APK published outside Play (the `foss` APK on GitHub) and is Play's **app signing key**: it is exported to Google once, encrypted with PEPK, when the app is set up in the Console, so Google holds a copy and re-signs every APK it delivers with it. That is why a GitHub install and a Play install of the same tag are the same app to Android and update over each other. **Losing it is not recoverable for the GitHub route**: no update can be installed over an existing install, and every phone needs an uninstall first, which removes the phone's SSH key and host pins.
- **The upload key** is a separate key, also RSA 2048 or more, and signs every app bundle before it is uploaded; Play checks the bundle against the upload certificate registered for the app, then signs the APKs it delivers with the app signing key. **Losing it is recoverable**: Play support resets the upload key and nothing installed changes.
- The release key never signs a bundle and the upload key never signs an APK: `release-sign.sh` takes the key from the file's type, and `PADDOCK_UPLOAD_KEYSTORE` is the only way to sign a bundle.
- Both keystores (PKCS12) live outside the repository and outside any directory its tools write to, each with an **offline copy** and its **own password held separately from it**. A script reads a password from a mode-600 file, never from an argument or a variable; `release-sign.sh` refuses a keystore under the repository.
- The choice of app signing key is made before the first upload: Play enrols a new app in a Google-generated key by default, "use a different key" (then the PEPK export) can be changed until a release is in open testing or production, and the plan makes it on the Console's Play app signing page, before the internal upload (vault `phases/16`, slices 5 and 8).
- This page records the certificates' SHA-256 fingerprints once the keys exist (AC-16.12), never a key or a password:

| Key | Certificate SHA-256 |
| --- | --- |
| Release (Play's app signing key) | not generated yet |
| Upload | not generated yet |

The only keys that exist today are **throwaway** ones. `tools/test-release-scripts.sh` makes two in a temporary directory outside the repository (`CN=Paddock REHEARSAL KEY (not a release key)` and `CN=Paddock REHEARSAL UPLOAD KEY (not an upload key)`), signs a throwaway APK and bundle with them, and deletes everything on exit; it is the only place a rehearsal key is used. The signed APK of the 2026-10-03 rehearsal used a throwaway key whose certificate says so, and it must never be published.

## Version table

Applied at release time, not yet in `app/build.gradle.kts` (which says `0.0.1`, code 1): the bump, its commit and the tag are release actions (Phase 16, slices 8 and 9).

| Cut | `versionName` | `versionCode` | Tag | Goes to |
| --- | --- | --- | --- | --- |
| Internal | `0.1.0-rc1` | 2 | `v0.1.0-rc1` | the `play` bundle, signed with the upload key, to Play's internal testing track |
| Closed testing and production | `0.1.0` | 3 | `v0.1.0` | the `play` bundle to closed testing and production; the `foss` APK, signed with the release key, to GitHub |

- `release-build.sh --publish` accepts `v0.1.0-rc1`: the tag equals `v<versionName>`.
- One more code per re-upload: Play takes a `versionCode` once, so a replaced or rejected upload spends its number.
- The `foss` GitHub APK carries the production code, the same number as the Play build of the same tag, so either route updates over the other.
- F-Droid, if Phase 13 slice 7 is taken, lists only non-rc tags.

## Before any push: hostnames

Two old hostnames were scrubbed from this repository's history (the two names are listed in the vault's Phase 16 note; they are never written here, in a listing, a screenshot or a commit message). Before **any** push, search every local ref, and every worktree, for both; only the two backup refs may match.

```sh
OLD1=...; OLD2=...        # the two names from the vault's Phase 16 note; do not save them in this repository
git for-each-ref --format='%(refname)' | grep -v -e '^refs/backup/pre-rewrite$' -e '^refs/backup/pre-scrub-2026-10-12$' \
  | while read -r ref; do git grep -l -i -e "$OLD1" -e "$OLD2" "$ref" --; done                       # no output: every ref is clean (the exit status means nothing here)
git worktree list --porcelain | sed -n 's/^worktree //p' | while read -r wt; do git -C "$wt" grep -l -i --untracked -e "$OLD1" -e "$OLD2"; done   # and the working trees
```

- `refs/backup/pre-rewrite` and `refs/backup/pre-scrub-2026-10-12` stay **local, never pushed**: no `git push --mirror`, no refspec naming `refs/backup/`. The second holds the old names by design.
- A screenshot or a piece of listing text is taken from the demo host only, never from a real machine (Phase 16 rule). `PADDOCK_OLD_NAMES=$OLD1,$OLD2 tools/check-listing.py` checks the listing text, the image file names and the images' metadata.

## Google Play (Phase 16)

The Play route is one listing: the `play` flavor is the free install and Pro is an in-app purchase inside it; the `foss` APK on GitHub is the same application id and the same release key, so a phone can move between the two. The vault note `herdr-android/phases/16-play-publication.md` holds the slices and the acceptance criteria; this page holds what the pipeline does.

- **The two cuts.** `0.1.0-rc1` / code 2 goes to internal testing first, so the signing, the app signing key and the purchase path are proven before the production cut; `0.1.0` / code 3 follows once the listing, the policy page and the socket measurement exist. See the version table. Every upload spends a code for ever.
- **Signing.** A bundle is signed with the upload key (step 3). Before the first upload the app signing key is chosen in the Console (Key policy), and the upload certificate's fingerprint, printed at the end of `SIGNATURE-bundle.txt`, is registered there. All Console actions are the user's; no key material is ever passed to a tool or written to this repository.
- **Policy dates.** Target API and Billing Library: see "Target SDK and Billing Library policy, re-checked 2026-10-12" above; both are met (37, 9.1.0).
- **The delivered APK check (AC-16.1).** Play builds the APK a phone gets from the uploaded bundle and signs it with the app signing key. On a phone with the Play-installed build: `adb shell pm path io.github.tuthan.paddock`, `adb pull` of the `base.apk` line, then `apksigner verify --print-certs base.apk`, which must show the **release** certificate (AC-13.2), and `tools/check-release-apk.py base.apk report.txt --flavor play`, which must pass every check. The script's header says why no flag is needed; the first real run (Phase 16, slice 8) confirms it.
- **The sockets (AC-16.3).** Step 5b, on the same build. The list goes into `docs/privacy-checklist.md`, the privacy policy and the Data safety answers, or is empty and all three say so.
- **The listing text.** There are two text folders on purpose. `fastlane/metadata/android/en-US/` is F-Droid's source: F-Droid reads it from the tagged commit, so editing it for Play would change the F-Droid listing. `play-listing/en-US/` is the Google Play text, derived from it: it drops the sentence about the free build and the names of the other channels (decision P2), and adds a demo sentence to the "You need" paragraph; the twelve-month window and compatibility text (M7) are the user's and are not in it yet. `tools/check-listing.py` checks the lengths of both, that the Play description is its own, the image sizes and, with `PADDOCK_OLD_NAMES`, the old names. The images are in `play-listing/en-US/images/` (the icon stays under `fastlane/`, where both stores read it). `featureGraphic.png` is drawn by `tools/make-feature-graphic.py` from the launcher icon, the bundled IBM Plex Sans and the theme's colours (Pillow, for that script only). `phoneScreenshots/` holds four screens of the demo machine (`docs/demo.md`), taken from the `play` debug build on an API 37 emulator with its display set to 1080x2160 (`wm size`, because Play takes a long side of at most twice the short one, and the emulator's own 1080x2400 is over), the status bar fixed by the system's demo mode (10:00, full battery), Protect sensitive screens off in the app (it blocks the capture; this is the demo, so nothing sensitive is on screen), and the images saved as 24-bit RGB with no metadata. They are made again when a screen changes, in the same way; `tools/check-listing.py --strict` is the check. The Play text names the demo, which now exists, so it may be entered in the Console once the user has read it.

## Not done

- **The two real keys** (release and upload, Key policy) and a signed `foss` APK and `play` bundle. Needs the publisher. Their fingerprints go into the Key policy table when they exist.
- **The version bump.** The build says `0.0.1` / code 1; the cuts in the version table (`0.1.0-rc1` / 2, then `0.1.0` / 3), their commits and tags are release actions.
- **A GitHub release** (the `foss` APK, `SHA256SUMS`, toolchain tuple, this page's policy line, links to every phase's evidence report). Not published; nothing here sends anything anywhere. The licence is in place: `LICENSE` (Apache-2.0, decision M1, 2026-10-04) is at the repository root and named in the README and About.
- **Play items** (the Console app, Play App Signing with the PEPK export, the bundle upload, the Data safety form, the privacy policy URL, the support email, products, the feature graphic and screenshots from the demo host): Phase 16 (vault `phases/16-play-publication.md`). Nothing has been entered in the Console. Signing a bundle is done (`release-sign.sh`, tested with throwaway keys).
- **A smoke on a physical phone.** The three runs (2026-10-03, before the flavors) are an API 26 emulator, an API 36 emulator and an API 37 emulator. The release criterion names a physical phone; that run remains, for the `foss` APK, and the `play` build's socket measurement needs a phone with Google Play (`run-release-smoke.py --sockets-only`, `docs/billing.md`).
- **Reproducibility on a second machine.** Only one machine and toolchain were used, for both flavors.
