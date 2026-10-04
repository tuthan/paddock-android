# The device harness

The scripts that run the app on a device against a real herdr, measure the host and cut the link on purpose live in their own repository, `paddock-harness`, beside this one. They were `tools/` here until 2026-10-04 (the first harness commit, `2f15eb4`, is those scripts as `tools/` held them at `354c6be`); evidence reports before that date cite them under `tools/`.

## What stays here, and why

`tools/` holds the 25 scripts that the offline gate, the pins, the release cut or a JVM test executes:

| Group | Files | Why it stays |
| --- | --- | --- |
| CI gate | `check.sh`, `check-pins.sh`, `check-fonts.sh`, `pin-host.py`, `pin-host.sh`, `pin-source.sh`, `validate-fixtures.sh`, `test-scripts.sh` | `check.sh` is exactly what `.github/workflows/build.yml` runs; the pins are the protocol contract |
| Fixture capture | `capture-fixtures.sh`, `capture-agent-kinds.sh`, `capture-operation-fixtures.sh`, `capture-phase09-fixtures.sh`, `capture-rich-agent.sh`, `setup-session.sh`, `sockcap.py` | A pin update commits schema, fixtures and manifest together; these scripts are that update |
| Release cut | `release-build.sh`, `release-sign.sh`, `check-release-apk.py`, `run-release-smoke.py` | `docs/release.md` runs its steps from this one checkout; the release record must be reproducible from here alone |
| Host-script tests | `test-alert-relay.py`, `test-control-script.py`, `test-permission-hook.py` | They test `host/*.py`, which ships inside the APK; tests land in the same commit as the code they cover |
| Shared stand-ins | `fake-agent.py`, `test-sshd.sh`, `desktop-client.py` | `fake-agent.py` drives three capture scripts, the release smoke, and `Phase06LiveTest` and `Phase09LiveTest` in `:core`; `test-sshd.sh` serves the smoke and the harness's transport tests; `Phase05LiveTest` executes `desktop-client.py` through `ProcessBuilder` |

The Kotlin tests do not move either: the instrumented tests must be a source set of this Gradle build, and `:core`'s tests read `fixtures/`, `protocol/` and `host/` from this checkout through `paddock.repoRoot`.

## Running one flow

```sh
git clone <paddock-harness> ../paddock-harness      # beside this checkout; PADDOCK_APP names another path
cd ../paddock-harness
./run-spaces-e2e.sh emulator-5570                   # builds and installs from ../paddock-android, writes under out/
```

The harness reaches `tools/test-sshd.sh`, `tools/fake-agent.py`, `tools/desktop-client.py` and `tools/setup-session.sh` here through that checkout, and refuses a path whose `settings.gradle.kts` does not name `paddock-android`. Its `README.md` lists the prerequisites and every script; `docs/traps.md` there holds the lessons that cost a run.

## Rules

- A harness-only change never changes an app verdict. It is rerun evidence: a line in the harness's `docs/runs.md` and a mention in the next app report.
- A slice that changes a device flow lands as a harness commit whose message names the app commit it was run against; the app commit's message names the harness commit. The Kotlin tests keep landing with the code they cover.
- The evidence template's `Harness commit` row is filled for every run that used a device flow, a device check or a measurement.
- A shared stand-in that changes here is an app commit. A harness run that notices it records the app commit.

## Visibility

The harness repository is local only for now. Whether it is published, and under which licence, follows the app's licence decision (M1). If it is private while this repository is public, this page says so and the evidence reports still cite the harness commit, so the record is complete where a reader cannot open it.
