# Paddock

A phone companion for [herdr](https://herdr.dev): answer the herd, do not operate it. Native Kotlin, SSH only, no server of its own. Plan and phase notes live in the docs vault under `herdr-android/`.

Status: Phase 04 implemented (monitor UI): connection ownership, host-key trust, relay install with consent, a live attention home, Output, Activity, Add machine, Settings. `:core` is JVM only (enforced); `:app` is the Android shell. Device-only acceptance (AC-04.8 recents thumbnail, AC-04.9 cold start, TalkBack) is open; see the vault's Phase 04 evidence report.

## Requirements

- JDK 17 (`mise install` reads `mise.toml`) and the Android SDK with platform 37 and build-tools 36. Set `sdk.dir` in `local.properties` or `ANDROID_HOME`.
- For the tools: `herdr` 0.9.1, `jq`, `python3`.

## Build and test

```sh
./gradlew :core:test :app:assembleDebug      # JVM tests and the debug APK
./tools/check-pins.sh                        # installed herdr and the pinned schema and fixtures still agree
```

`FixturePinTest` fails if any file under `protocol/` or `fixtures/` differs from `protocol/SOURCE.json`. A fixture change is a deliberate pin update: recapture, run `tools/pin-source.sh <date> <host>`, review the diff, commit schema, fixtures and manifest together.

## Test tiers

| Tier | How |
| --- | --- |
| JVM unit | `./gradlew :core:test` (fixtures only; no herdr needed) |
| JVM integration | Phase 03 onward: start the disposable session (below), set `PADDOCK_TEST_SOCKET` to its socket |
| Emulator | `tools/run-ui-tests.sh <serial>` (Compose tests plus screenshots); `tools/run-live-e2e.sh <serial>` (the whole app against a throwaway sshd and `paddock-test`); `tools/check-permission-flow.py` (Android 17, adb-driven); `tools/check-add-machine-ime.py` (keyboard and rotation) |
| Physical | Phase 02 onward |

## The disposable session

Integration tests and fixture capture use one herdr session and nothing else:

```sh
tools/setup-session.sh                       # starts `herdr --session paddock-test server`, one workspace at /tmp/paddock-test-ws
tools/capture-fixtures.sh paddock-test       # recapture the corpus (a pin update); refuses before writing, swaps in only on success
tools/validate-fixtures.sh                   # corpus completeness, no leaks, `default` refused
```

**No test ever touches the default session or a real agent.** Only sessions whose name matches `^paddock-test(-[a-z0-9]+)?$` (`paddock-test`, or `paddock-test-` plus lowercase letters and digits) may be mutated; the scripts and the integration harness refuse any other name or socket path.

## Dependencies

Every dependency is reviewed with Socket before it enters `gradle/libs.versions.toml`, and its row lands in `docs/dependency-reviews.md` in the same commit. See `docs/build-decision-record.md` for the toolchain.
