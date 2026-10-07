# How the app is put together

The app module composes; `:core` decides. This page is the map after the 2026-10-07 split of `PaddockRoot.kt` and `AppGraph.kt`, which was a move and
a narrowing with no change in behavior.

## The graph (`AppGraph.kt`)

`AppGraph` builds the stores, ports and services once per process and starts what runs for the life of the process. It holds no rules of its own that a
service could hold. Its parts, each a class in `:core` that takes lambdas or ports and is tested on the JVM with fakes:

| Member | Owns | Test |
|---|---|---|
| `prefs` (`SettingsController`) | the saved settings and the user's snippets; which writes wait for the file and which are saved in the graph's scope | `SettingsControllerTest` |
| `proService` (`ProService`) | Pro's view, the gate request and its notice, purchase and restore | `ProServiceTest` |
| `machineBook` (`MachineBook`) | the saved machines, the watched and chosen one, add, switch, remove, rename, the readings a bring-up writes | `MachineBookTest` |
| `hostWatcher` (`HostWatcher`) | the watched machine's controller and the jobs that follow it | `HostWatcherTest`, `HostProfileSameConnectionTest` |
| `alertService` (`AlertService`) | what an alert tap resolves to, and the open app's own notifications | `AlertServiceTest` |
| `wakes` (`WakeService`) | per-machine Wake facts and the send guard | `WakeServiceTest` |
| `widgets` (`WidgetService`) | the widget cache: tracking the watched machine, the refresh job | `WidgetServiceTest` |
| `hostScripts` (`PinnedHostScripts`) | the bundled scripts, each checked against its pin when the graph is built | `PinnedHostScriptsTest` |

What stays in `AppGraph`: composition, `start()` (five named collectors and a load step; the order is in its KDoc), and the phone-key and import paths
that need Android's Keystore (`createPhoneKey`, `importKey`, `importedKey`, `sendPairingKey`, `replaceKey`), `retry`, `installRelay` and `savedSagas`.

## The router (`PaddockRoot.kt`, `nav/`, `routes/`)

- `PaddockRoot.kt` is the route switch: it reads `NavState`, draws the notice bars and the dialogs, and calls one route per `Route`.
- `nav/NavState.kt` and `nav/AddMachineFlow.kt` hold where the user is and every way it moves (`effective`, `backTo`, `openAgent`, `openPairing`,
  `removalShown`, `connected`, …). They survive rotation and process death through `NavState.Saver`; a saved state that is not ours, or that names a
  screen this version lacks, restores to the defaults. `NavStateTest` covers each transition and the saver.
- `RootEffects.kt` holds the four effects that are not a screen: an alert tap, a pairing link, a removal's outcome and the follow of a connect attempt.
- `routes/` has one file per screen (`HomeRoute.kt`, `SettingsRoute.kt`, `MachinesRoute.kt` with the machine's page and `switchMachine`, …). A route takes
  the services it uses as named parameters, not the graph, so what a screen depends on is its signature. The one holder is `AddMachineDeps`, because the Add
  machine screens (form, Find, Scan, Import key) forward most of it to each other; `AddMachineDeps.of(graph)` is the only place those files know the graph.
- `AppLockGate.kt` covers the app while the lock says so; it takes the lock, the settings, the triggers and the graph's scope.

## What the host tests do not cover

The route files are Compose and are checked by compiling both flavors and by the instrumented tests (`ui/`, the graph tests, the smoke test) on an
emulator. The move that created `routes/` was checked by comparing the two files' lines; the narrowing was checked by reading every changed line that did
not mention the graph.
