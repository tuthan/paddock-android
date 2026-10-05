# Release cut

How the Paddock release artefacts are built, proven reproducible, signed and checked, and what has and has not been done: the `foss` APK (GitHub, F-Droid, IzzyOnDroid) and, for Google Play, the `play` APK and app bundle. Phase 10, AC-10.4 to AC-10.6, extended per flavor in Phase 13. Nothing here publishes anything: the last step (a GitHub release) is a person's decision and is not scripted.

## The steps

```sh
# 1. The baseline gate (wrapper, SDK revisions, pins, script self-tests, unit tests, lint, debug APK).
tools/check.sh --offline

# 2. Two clean, daemonless builds (always with -PpaddockUnlocked=false, so a published foss APK is the free version) of the unsigned release APK, compared byte for byte; the toolchain tuple written next to them.
#    --flavor foss (default) is the GitHub, F-Droid and IzzyOnDroid artefact; --flavor play is the billing build and also builds and compares the app bundle.
#    --publish refuses a dirty tree or a HEAD that is not the tag v<versionName>: the APK embeds the commit and F-Droid builds from the tag.
#    Each run empties build/release/, so move one flavor's output out before building the other.
tools/release-build.sh --offline          # build/release/{paddock-<v>[-play]-unsigned.apk, REPRODUCIBLE.txt, TOOLCHAIN.txt, build-{1,2}.log}

# 3. Sign with a key that lives outside the repository. The password is read from a mode-600 file, never an argument or variable.
PADDOCK_KEYSTORE=/path/outside/repo/release.p12 PADDOCK_KEY_ALIAS=paddock PADDOCK_KS_PASS_FILE=/path/to/pass \
  tools/release-sign.sh build/release/paddock-<v>-unsigned.apk     # paddock-<v>.apk, SIGNATURE.txt, SHA256SUMS (play: paddock-<v>-play-unsigned.apk gives paddock-<v>-play.apk; APKs only, the .aab is not signed here)

# 4. Static privacy and manifest inspection of the APK that will be published.
tools/check-release-apk.py build/release/paddock-<v>.apk build/release/PRIVACY-MANIFEST.txt                 # foss, the strict default
tools/check-release-apk.py build/release/paddock-<v>-play.apk build/release/PRIVACY-MANIFEST-play.txt --flavor play   # the named additions only (docs/billing.md)

# 5. Release smoke and the runtime half of the privacy checklist, on each device (an emulator or a phone with adb). Run against the foss APK; the play APK's sockets are measured separately (docs/billing.md).
tools/run-release-smoke.py <adb-serial> build/release/paddock-<v>.apk <out-dir>
```

`release-sign.sh` refuses a keystore inside the repository, a password file that is not mode 600, and an unaligned input; signs with APK Signature Scheme v2 and v3 only (minSdk 26 makes v1 unnecessary; v4 is for incremental installs and would leave an extra, unpublished file); runs `apksigner verify --print-certs --min-sdk-version 26`; and proves signing changed nothing but `META-INF` by comparing every entry with the unsigned APK.

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

## Target SDK policy, consulted 2026-10-03

Google Play requires new apps and updates to target an API level within one year of the latest release: API 36 from 2026-08-31 (with an extension to 2026-11-01 for developers who ask). Paddock targets **37**, so it meets the requirement with a level to spare. Android 17 behaviour at target 37 (edge-to-edge enforced, `ACCESS_LOCAL_NETWORK` for LAN sockets) is handled and listed in `build-decision-record.md`. This only matters if decision M2 chooses Play; for a GitHub-only release no store policy applies. Re-check the policy page on the day a release is cut: the date above is when it was last read, not a standing fact.

## What the release APK is checked for

`check-release-apk.py` (static, run on the APK itself):

- the permission set is exactly `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, `ACCESS_LOCAL_NETWORK`: nothing else (no log, storage, contacts or location access). The `play` flavor adds `com.android.vending.BILLING` and nothing else;
- not debuggable; `allowBackup=false`; `usesCleartextTraffic=false`; a network security config (system CAs only, cleartext off) and both backup rule files (everything excluded) are in the APK;
- the exported components are the launcher activity, the UnifiedPush receiver and the three widget providers. One library receiver is also exported, `androidx.profileinstaller.ProfileInstallReceiver`; it requires the signature-level `DUMP` permission, so only the shell can reach it, and the check accepts it only while that guard is present;
- no analytics, crash reporting, advertising, attribution, billing, Play-services or datatransport SDK, no Work or Glance library, and no Compose tooling or test code in the dex. In the `play` flavor the billing, Play-services and datatransport packages are allowed only as the named list in `check-release-apk.py`, together with the two extra `<queries>` intents and six non-exported library components; anything else, and any firebase package, fails (`docs/billing.md`);
- every host script in `assets/` has the SHA-256 that `host/SOURCE.json` pins, and its `.sha256` sibling says the same (the app refuses any other hash on the machine);
- every URL string in the dex and assets is on an allow-list: documentation links inside libraries, XML namespaces, font licence links and the project's own example ntfy host. None is a host the app calls.

`run-release-smoke.py` (on a running device, against the signed `foss` APK; it uses an isolated HOME, a throwaway sshd on loopback and a herdr session it creates and deletes; `claude` is a stand-in, never a real agent):

- installs, launches, adds a machine, makes the phone key, authorizes it, connects, compares the fingerprint the phone shows with the sshd's, trusts, installs the relay (the hash on screen is the pinned one), shows the herd, Output, and the Terminal tab observing; opens an alert link (fresh read; and a link to an agent that is gone);
- the app is not debuggable and `run-as` is refused;
- every socket the app's uid opened (sampled from `/proc/net` every 0.4 s) went to the sshd and nowhere else: no analytics, no crash upload, no third-party call;
- `FLAG_SECURE` is on while Output is shown and while Home shows a captured prompt, and is off once no prompt is shown;
- the whole run's logcat holds none of the seeded agent title, none of the seeded agent output, no private-key text and no password;
- what the phone granted is within the manifest's four.

The socket check is not expected to pass unchanged on the `play` APK: billing reaches the Play Store app over IPC, but the library's logging backend can open sockets from the app's uid. The `play` measurement is open and is written up in `docs/billing.md`; until it is done, "nothing leaves but SSH" is claimed for `foss` only.

## Key policy

The release key is generated by the person who publishes, on a machine they control, outside this repository and outside any directory the repository's tools write to; it is never in an environment variable or on a command line. Keep an offline copy and its password separately: **a lost key means no update can ever be installed over an existing install.** The key is RSA with 2048 bits or more, because Play requires that of an uploaded app signing key and the same key can then serve every route. If Play App Signing is used (decision M2), upload the existing key as the app signing key or enrol a new upload key, as the Play documentation of the day says. Play enrols a new app in a Google-generated key by default, and the switch to an uploaded key is possible only before the first rollout to open testing or production, so make it on the closed track (vault `phases/13`, read 2026-10-04).

The signed APK in the rehearsal was made with a **throwaway key whose certificate says so** (`CN=Paddock REHEARSAL KEY (not a release key)`). It exists to prove the signing scripts, and its APK must never be published.

## Not done

- **A real signing key** and a signed 1.0 `foss` APK (and, if Play is chosen, a `play` APK and bundle on the same key). Needs the publisher.
- **The version bump.** The build says `0.0.1`; setting `versionName`/`versionCode` for 1.0, the commit and the tag are release actions.
- **A GitHub release** (the `foss` APK, `SHA256SUMS`, toolchain tuple, this page's policy line, links to every phase's evidence report). Not published; nothing here sends anything anywhere. The licence is in place: `LICENSE` (Apache-2.0, decision M1, 2026-10-04) is at the repository root and named in the README and About.
- **Play items** (bundle upload and the signing of the bundle for upload, which `release-sign.sh` does not do; Play App Signing, Data safety form, privacy policy URL, support email): only if M2 chooses Play; `phases/13` holds the detail.
- **A smoke on a physical phone.** The three runs (2026-10-03, before the flavors) are an API 26 emulator, an API 36 emulator and an API 37 emulator. The release criterion names a physical phone; that run remains, for the `foss` APK, and the `play` APK's socket measurement needs a phone with Google Play (`docs/billing.md`).
- **Reproducibility on a second machine.** Only one machine and toolchain were used, for both flavors.
