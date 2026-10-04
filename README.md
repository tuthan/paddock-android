# Paddock

A phone companion for [herdr](https://herdr.dev): answer the herd, do not operate it. Native Kotlin, SSH only, no server of its own. Plan and phase notes live in the docs vault under `herdr-android/`.

Status: Phases 00 to 12 are implemented (Phase 09's multi-host slices are held; Phase 08 and the optional Phases 11 and 12 are built). The Phase 10 release cut has been rehearsed with a throwaway key and nothing is published (`docs/release.md`). `:core` is JVM only (enforced); `:app` is the Android shell. Acceptance that needs a physical phone or other people is open: TalkBack, the real-radio link cuts, widgets on a real launcher, the usability test and a second person following `docs/host-setup.md`; the vault's evidence reports list each item. The notes for what each part does are in `docs/` (`widgets.md`, `accessibility.md`, `contrast.md`, `release.md`, `host-setup.md`, `operations.md`, `spaces.md`, `alerts.md`, `terminal-control.md`).

## Requirements

- Temurin JDK 17 (`mise install` reads `mise.toml`; point `JAVA_HOME` at it, since `gradle/gradle-daemon-jvm.properties` requires an Adoptium 17 daemon) and the Android SDK packages `platforms;android-37.0` (revision 2) and `build-tools;36.0.0`, installed with `sdkmanager`: the build never downloads them (`android.builder.sdkDownload=false`). Set `sdk.dir` in `local.properties` or `ANDROID_HOME`.
- For the tools: `herdr` 0.9.1, `jq`, `python3`.

## Build and test

```sh
tools/check.sh --offline                     # the CI gate: wrapper, SDK revisions, pins, script self-tests, check, unit tests, lint, debug APK
./gradlew :core:test :app:assembleDebug      # JVM tests and the debug APK
./tools/check-pins.sh                        # installed herdr and the pinned schema and fixtures still agree
./tools/check-pins.sh --pins-only            # the same hash and coverage rule without herdr (part of tools/check.sh)
./tools/test-scripts.sh                      # self-tests of the fixture and pin scripts against a fake herdr
tools/release-build.sh --offline             # two clean release builds compared byte for byte (docs/release.md); then release-sign.sh, check-release-apk.py, run-release-smoke.py
```

`FixturePinTest` and `tools/check-pins.sh` fail if a file pinned in `protocol/SOURCE.json` is missing or differs, or if any file under `protocol/` (except `SOURCE.json` itself) or `fixtures/` is not pinned there. A fixture change is a deliberate pin update: recapture, `git rm` the previous version's corpus and schema if herdr moved, run `tools/pin-source.sh <date> <host>` (it reads the herdr version and protocol from the corpus and refuses stale files), review the diff, commit schema, fixtures and manifest together.

## Test tiers

| Tier | How |
| --- | --- |
| JVM unit | `./gradlew :core:test` (fixtures only; no herdr needed) |
| JVM integration | Phase 03 onward: start the disposable session (below), set `PADDOCK_TEST_SOCKET` to its socket |
| Emulator | From a `paddock-harness` checkout beside this one (`docs/harness.md`): `./run-ui-tests.sh <serial>` (Compose tests plus screenshots); `./run-live-e2e.sh <serial>` (the whole app against a throwaway sshd and `paddock-test`); `./run-terminal-e2e.sh <serial>` (the Terminal tab: observe, conflict, takeover, typing, the Android keyboard's keys, scroll timing, resize, release, rotation, background and Back while controlling, link loss; `LINK_CUT=airplane` for real airplane mode on Android 12+); `./run-operations-e2e.sh <serial>` (Phase 06 through the whole app, in two stages with an app restart: Manual input and Esc, the focus question, prompts, the journal read from the phone, a link cut right after a prompt was delivered, Re-read, Ctrl+C; uses `tools/fake-agent.py`, `blackhole-proxy.py` and `cut-after-submit.py`); `./check-permission-flow.py` (Android 17, adb-driven); `./check-add-machine-ime.py` (keyboard and rotation) |
| Physical | Phase 02 onward |

## The disposable session

Integration tests and fixture capture use one herdr session and nothing else:

```sh
tools/setup-session.sh                       # starts `herdr --session paddock-test server`, one workspace at /tmp/paddock-test-ws
tools/capture-fixtures.sh paddock-test       # recapture the corpus (a pin update); refuses before writing, swaps in only on success
tools/validate-fixtures.sh                   # corpus completeness, no leaks, `default` refused
```

**No test ever touches the default session or a real agent.** Only sessions whose name matches `^paddock-test(-[a-z0-9]+)?$` (`paddock-test`, or `paddock-test-` plus lowercase letters and digits) may be mutated; the scripts and the integration harness refuse any other name, and any socket path whose real path (symlinks resolved; on the host for the over-SSH test) is not that same session's `sessions/<name>/herdr.sock`.

## Dependencies

Every dependency is reviewed with Socket before it enters `gradle/libs.versions.toml`, and its row lands in `docs/dependency-reviews.md` in the same commit. See `docs/build-decision-record.md` for the toolchain.
