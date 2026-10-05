# Build decision record

**Recorded:** 2026-10-01; revised the same day after the scaffolding review (facts below re-derived from the build at that revision). **Owner of dependency dispositions:** Hung Vo. Reviews are in [dependency-reviews.md](dependency-reviews.md).

## Toolchain

| Component | Pinned | Basis |
| --- | --- | --- |
| JDK | Eclipse Temurin 17.0.20+8 (`mise.toml`; CI uses `actions/setup-java` with the same version) | AGP 9.3 requires JDK 17. The machine's system JDK (26) is not used. |
| Gradle daemon JVM | `gradle/gradle-daemon-jvm.properties`: `toolchainVersion=17`, `toolchainVendor=ADOPTIUM`, no download URLs | D8 and R8 run in the daemon, so the daemon's JVM is part of the toolchain, not just the `jvmToolchain(17)` compilers. Criteria name a major and a vendor only; the exact build is pinned by `mise.toml` and `setup-java`. With `JAVA_HOME` on the mise Temurin (or CI's), the launcher JVM matches; any other JVM fails at startup with "Cannot find a Java installation … matching {languageVersion=17, vendor=Eclipse Temurin}" (checked: a wrong vendor, version 21, and the system JDK 26 as `JAVA_HOME` all fail; JDK 26 already failed before, at `:core:compileKotlin`, for want of a JDK 17 toolchain). Nothing is provisioned: `org.gradle.java.installations.auto-download=false` and no `toolchainUrl` entries. |
| Gradle wrapper | 9.6.1 `-bin`, distribution SHA-256 `9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14` in `gradle-wrapper.properties`; wrapper jar SHA-256 `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`, re-checked by `tools/check.sh` (CI and local) | Within AGP 9.3's minimum (9.5) and KGP 2.4.20's range. Same wrapper as `steamos-companion-android`. |
| Android Gradle Plugin | 9.3.3 | The line KGP 2.4.20 documents as supported; AGP 9.4.x is outside that range. |
| Kotlin | 2.4.20 (`kotlin.jvm` for `:core`, AGP built-in Kotlin for `:app`, plus the `compose` and `serialization` compiler plugins at the same version) | One Kotlin version across both modules. |
| Compose BOM | 2026.09.00, which resolves Compose UI 1.12.1 and Material 3 1.4.0 (`app/gradle.lockfile`) | Same as the sibling project. |
| kotlinx-serialization-json / coroutines-core | 1.11.0 / 1.11.0 | Fresh Socket review 2026-10-01. |
| SSH | `org.connectbot:sshlib` 2.2.48 with tink 1.21.0; kyber, keccak and kotlincrypto excluded in `app/build.gradle.kts` | [ssh-library-decision.md](ssh-library-decision.md) |
| Android SDK packages | `platforms;android-37.0` revision 2 and `build-tools;36.0.0` (revision 36.0.0), from `sdkmanager` | AGP 9.3's default build tools. AGP never downloads a missing package (`android.builder.sdkDownload=false` in `gradle.properties`), because such a download bypasses dependency verification. CI installs both with the runner's preinstalled `sdkmanager`, and `tools/check.sh` fails if either `source.properties` revision differs from this row; `sdkmanager` cannot install an older revision, so a new upstream revision is a deliberate bump of this row and of `tools/check.sh`. |

## SDK levels

| Setting | Value |
| --- | --- |
| minSdk | 26 |
| compileSdk | 37 (`platforms;android-37.0`) |
| **targetSdk** | **37** |
| `ACCESS_LOCAL_NETWORK` | **Declared.** Android 17 gates LAN sockets behind it and says to declare it only at target 37 or later; the recorded target is 37, so the manifest has it, and a build that lowers the target must drop it. Phase 02 requests it at runtime and handles denial. |

Target 37 also means edge-to-edge is enforced: the first screen draws under the system bars on API 36 and 37 unless it applies insets. `MainActivity` uses `safeDrawingPadding()`; every later screen must do the same. This closes the API 37 build-tuple item left open by Gate G0.

`INTERNET` is declared (Phase 02, the SSH transport), and so is `ACCESS_NETWORK_STATE`, a normal permission `ConnectionOwner` uses to notice a default-network change and replace a dead socket.

## Release-cut settings

`dependenciesInfo { includeInApk = false; includeInBundle = false }`: AGP otherwise writes a dependency block, encrypted to a Google Play key, into the APK signing block and the bundle. Its bytes can be neither reviewed nor reproduced, and it discloses the dependency list; with it off, `:app:sdkReleaseDependencyData` is not in the release graph.

## Distribution flavors (Phase 13, 2026-10-04)

One flavor dimension, `distribution`, with two flavors and one application id, `io.github.tuthan.paddock`, so every route is the same app signed with the one release key and installs update over each other.

| Flavor | Carries | Used by |
| --- | --- | --- |
| `foss` | no billing library; `Distribution.UNLOCKED` is `false` in every published build, so the free version: Pro capabilities locked, nothing to buy (vault decision M4, taken 2026-10-04). A source build with `-PpaddockUnlocked=true` turns it on; the release cut passes `-PpaddockUnlocked=false` | GitHub releases, F-Droid, IzzyOnDroid, the device harness and `tools/check.sh` |
| `play` | Play Billing 9.1.0 and the libraries it brings, through `playImplementation` only; `Distribution.UNLOCKED = false`; Pro and tips are bought through Google Play | the Google Play listing |

`:app` generates `BuildConfig` (`buildFeatures.buildConfig`) only to carry that one constant. The flavor renames every variant: the tasks are `assemble<Flavor>Debug`, `assemble<Flavor>Release`, `bundlePlayRelease`, `test<Flavor>DebugUnitTest`, `lint<Flavor>Debug` and `assemble<Flavor>DebugAndroidTest`; the outputs are `app/build/outputs/apk/<flavor>/<type>/app-<flavor>-<type>[-unsigned].apk` and `.../androidTest/<flavor>/debug/app-<flavor>-debug-androidTest.apk`. Every caller was changed in the same commit: `tools/check.sh` (both flavors, since CI runs it), `tools/release-build.sh` (`--flavor`), the README, this record, and the nine device flows in `paddock-harness`, which build and install the `foss` variants. The strict lockfiles gained the per-flavor configurations and, for `play`, 18 coordinates (`docs/dependency-reviews.md`); the `foss` configurations resolve the same coordinates as before.

## Repositories, locks, verification

Google Maven is restricted to groups `com.android.*`, `com.google.*` and `androidx.*`, then Maven Central; no Gradle Plugin Portal; `FAIL_ON_PROJECT_REPOS`.

**Locks.** Every configuration of both modules and the root build-script classpath is locked in `LockMode.STRICT` (`core/gradle.lockfile`, `app/gradle.lockfile`, `buildscript-gradle.lockfile`): a configuration resolved without lock state fails rather than resolving unlocked (checked by removing one entry). The settings build-script classpath is empty and is not locked: Gradle 9.6.1 writes no lock state for it, so STRICT there fails every build. `settings-gradle.lockfile` holds only Gradle's own empty entry for the version catalog's incoming configuration.

**Checksums.** `gradle/verification-metadata.xml` holds SHA-256 for 456 components; the build fails on a mismatch (checked by deliberate tamper on 2026-10-01, before and after the pruning). The entries are trust on first use ("Generated by Gradle" from the local cache): they detect a changed artifact, not a bad first download. 61 components appear in no lockfile, and all are needed: parent POMs and BOMs read while parsing POMs, and `com.android.tools.build:aapt2`, which AGP resolves through a detached configuration. The file was pruned from 508 components on 2026-10-01 (the excluded ML-KEM tree and 46 metadata-only entries no build path reads; the commit lists each) and completed for a cold cache: the earlier file had been generated on a cache warmed by the sibling project and failed a build from an empty Gradle home.

**Signatures: not enabled (`verify-signatures` stays `false`).** Turning it on needs every artifact's `.asc` file and the signers' public keys, fetched from the repositories and key servers, then a reviewed `gradle/verification-keyring.keys`. None of that is available offline: the local cache holds no `.asc` file and no keyring. Google Maven has not, as far as this record knows (not re-checked, since that needs the network), published PGP signatures for AGP and most AndroidX artifacts, which are most of this graph; those would need per-artifact checksum-only exceptions. Enabling it for the Maven Central part only would be a half measure that looks stronger than it is, so it is not done. Revisit as one reviewed change, online: `./gradlew --write-verification-metadata pgp,sha256 --export-keys` over the task set below from an empty Gradle home, review the key list, commit the keyring, and record which artifacts stay checksum-only and why.

Regenerate locks and checksums only as a reviewed change. Run the full task set twice, from an empty Gradle home (the cold path CI takes, which reads redirect POMs and their parents) and then on the normal one, so the file covers both:

```sh
TASKS="help buildEnvironment check :core:check :app:testFossDebugUnitTest :app:testPlayDebugUnitTest :app:lintFossDebug :app:lintPlayDebug :app:assembleFossDebug :app:assemblePlayDebug :app:assembleFossRelease :app:assemblePlayRelease :app:bundlePlayRelease :app:assembleFossDebugAndroidTest :app:assemblePlayDebugAndroidTest"
./gradlew -g "$(mktemp -d)" --rerun-tasks $TASKS --write-verification-metadata sha256
./gradlew -g "$(mktemp -d)" --rerun-tasks -PminifiedTest=true :app:assembleFossDebug :app:assembleFossDebugAndroidTest :app:assemblePlayDebug :app:assemblePlayDebugAndroidTest --write-verification-metadata sha256
./gradlew --rerun-tasks $TASKS --write-locks --write-verification-metadata sha256
./gradlew --rerun-tasks -PminifiedTest=true :app:assembleFossDebug :app:assembleFossDebugAndroidTest :app:assemblePlayDebug :app:assemblePlayDebugAndroidTest --write-locks --write-verification-metadata sha256
```

Gradle only adds entries; to prune, delete the file first and run all four. The 2026-10-01 regeneration ran offline, with the empty-home runs pointed (by an init script) at a local Maven mirror of the cache, and every task set then passed on both homes. `--write-locks` over the same task sets changed no lockfile.

## Build-time input

The build reads nothing outside the repository. The only herdr input is `protocol/` and `fixtures/herdr-0.9.1/`, pinned by `protocol/SOURCE.json`; every file under `protocol/` (except the manifest) and `fixtures/` must be pinned (`FixturePinTest`, `tools/check-pins.sh`). `tools/check-pins.sh` without arguments also compares the installed herdr's version and schema to the pin and is run by hand, never by the build; `--pins-only` is part of `tools/check.sh`.

## Test tiers

| Tier | Where | Run |
| --- | --- | --- |
| Gate | any machine with JDK 17 and the SDK packages above; CI | `tools/check.sh` (CI) or `tools/check.sh --offline`: wrapper, SDK revisions, pins, script self-tests, `:core:check` and, for each flavor, `:app:test<Flavor>DebugUnitTest :app:lint<Flavor>Debug :app:assemble<Flavor>Debug :app:compile<Flavor>DebugAndroidTestKotlin` (the instrumentation sources compile; running them needs an emulator), with `PADDOCK_TEST_SOCKET` removed |
| JVM | any machine with JDK 17, CI | `./gradlew :core:test` |
| Emulator smoke | AVDs `sc-api26` (API 26 google_apis) and `sc-api36`, started `-read-only` | `./gradlew :app:assembleFossDebug :app:assembleFossDebugAndroidTest`, then `adb install` both APKs and `adb shell am instrument -w io.github.tuthan.paddock.test/androidx.test.runner.AndroidJUnitRunner` |
| Physical | one current phone | from Phase 02 |
