# Build decision record

**Recorded:** 2026-10-01. **Owner of dependency dispositions:** Hung Vo. Reviews are in [dependency-reviews.md](dependency-reviews.md).

## Toolchain

| Component | Pinned | Basis |
| --- | --- | --- |
| JDK | Eclipse Temurin 17.0.20+8 (`mise.toml`; CI uses `actions/setup-java` with the same version) | AGP 9.3 requires JDK 17. The machine's system JDK (26) is not used. |
| Gradle wrapper | 9.6.1 `-bin`, distribution SHA-256 `9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14` in `gradle-wrapper.properties`; wrapper jar SHA-256 `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`, re-checked in CI | Within AGP 9.3's minimum (9.5) and KGP 2.4.20's range. Same wrapper as `steamos-companion-android`. |
| Android Gradle Plugin | 9.3.3 | The line KGP 2.4.20 documents as supported; AGP 9.4.x is outside that range. |
| Kotlin | 2.4.20 (`kotlin.jvm` for `:core`, AGP built-in Kotlin for `:app`, plus the `compose` and `serialization` compiler plugins at the same version) | One Kotlin version across both modules. |
| Compose BOM | 2026.09.00 | Same as the sibling project. |
| kotlinx-serialization-json / coroutines-core | 1.11.0 / 1.11.0 | Fresh Socket review 2026-10-01. |
| Build Tools | 36.0.0 | AGP 9.3 default, installed under `~/Android/Sdk`. |

## SDK levels

| Setting | Value |
| --- | --- |
| minSdk | 26 |
| compileSdk | 37 (`platforms;android-37.0`) |
| **targetSdk** | **37** |
| `ACCESS_LOCAL_NETWORK` | **Declared.** Android 17 gates LAN sockets behind it and says to declare it only at target 37 or later; the recorded target is 37, so the manifest has it, and a build that lowers the target must drop it. Phase 02 requests it at runtime and handles denial. |

Target 37 also means edge-to-edge is enforced: the first screen draws under the system bars on API 36 and 37 unless it applies insets. `MainActivity` uses `safeDrawingPadding()`; every later screen must do the same. This closes the API 37 build-tuple item left open by Gate G0.

`INTERNET` is not declared yet: Phase 01 has no network code. Phase 02 adds it with the SSH transport.

## Repositories, locks, verification

Google Maven is restricted to groups `com.android.*`, `com.google.*` and `androidx.*`, then Maven Central; no Gradle Plugin Portal; `FAIL_ON_PROJECT_REPOS`. Every configuration of both modules and the root build-script classpath is locked (`core/gradle.lockfile`, `app/gradle.lockfile`, `buildscript-gradle.lockfile`, `settings-gradle.lockfile`). `gradle/verification-metadata.xml` holds SHA-256 for 498 components; the build fails on a mismatch (checked by deliberate tamper on 2026-10-01).

Regenerate only as a reviewed change, with the task set that resolves every configuration:

```sh
./gradlew buildEnvironment check assembleDebug assembleRelease assembleDebugAndroidTest \
  --write-locks --write-verification-metadata sha256
```

## Build-time input

The build reads nothing outside the repository. The only herdr input is `protocol/` and `fixtures/herdr-0.9.1/`, pinned by `protocol/SOURCE.json`. `tools/check-pins.sh` compares the installed herdr's schema to the pin and is run by hand, never by the build.

## Test tiers

| Tier | Where | Run |
| --- | --- | --- |
| JVM | any machine with JDK 17, CI | `./gradlew :core:test` |
| Emulator smoke | AVDs `sc-api26` (API 26 google_apis) and `sc-api36`, started `-read-only` | `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest`, then `adb install` both APKs and `adb shell am instrument -w io.github.tuthan.paddock.test/androidx.test.runner.AndroidJUnitRunner` |
| Physical | one current phone | from Phase 02 |
