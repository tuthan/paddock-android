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
| `demo` (`DemoMode`) and `profiles` (`DemoProfileStore`) | whether the demo machine runs and its herd; the profile store lists it beside the saved machines and never writes it. `connect` returns a `DemoSession` for its profile, before the connector (Phase 16, `docs/demo.md`) | `DemoSessionTest` |

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
- **What a screen had open outlives a reconnect** (2026-10-12). The app lets the connection go a few seconds after it leaves the front (`ConnectionOwner`'s
  grace), and a screen that needs it says "not connected" until it is back. Nothing the user typed or opened may go with that: a route draws its connected part
  inside a `SaveableStateHolder` (`ComposeRoute`, `OutputRoute`), keeps what outlives the connection above the connection check (the pickers and the camera,
  the Select text copy, the Get sheet), and runs work whose result must land (a picked file's intake) in the graph's scope; a form whose choices come from the
  machine stays up on the last ones with its action off (`SpacesScreen`, the drop folder's sheet). `FilesFlowTest#t6` sends the app to the back until the
  connection closes and checks each.
- `AppLockGate.kt` covers the app while the lock says so; it takes the lock, the settings, the triggers and the graph's scope.

## Copying from Output (Phase 15, Part A)

- `ui/Copy.kt` is the one place the app writes the clipboard: `CopyKind` (label and sensitivity for every copy), `clipFor`, `copyText`, `clearClip`, `SensitiveClipboard` (the Compose wrapper that marks the system toolbar's Copy), `AlertClipGuard` (C7, built in `AppGraph` as `clipGuard` and told when the app comes to the front) and `copyNotice`. `CopyKindTest` scans the sources for a clipboard write anywhere else.
- `ui/screens/SelectText.kt` is the frozen-copy view and `SelectHolder` its snapshot; `AppGraph.selections` (`Retained<SelectHolder>`) keeps the snapshot across a rotation, as `terminals` keeps a terminal session. `OutputRoute` keeps `selecting` in saved state and the holder out of it, so a restart brings back the Output tab. The holder is keyed by the machine and the terminal, not by one connection, so the view outlives a reconnect. `docs/output.md` has the behaviour.
- `ui/components/OutputComponents.kt` holds `OutputRow` (the long-press menu and the TalkBack actions) and `linesToText` (the copy's text with its colours).

## Files (Phase 15, Part B)

`docs/files.md` has the behavior. How it is put together:

- **`:core` decides.** `FileTransfers` runs the constant `/bin/sh -c` scripts of `TransferScripts` over the host's session and journals each transfer (`Attachment`, `Fetch`); `TransferLimits`, `FileNames`, `PathTokens` and `DropFolderCheck` are pure; `ShareInbox` holds the one waiting share and `ShareDraft` decides how shared words join a draft. `MonitoredHost.transfers` is the host's one `FileTransfers`.
- **`:app` takes and hands over.** `IncomingFiles` is the one intake of a picked, captured or shared file (SEC-1, SEC-2); `ShareIntake` reads an `ACTION_SEND` for `MainActivity` and nothing else; `ShareSender` sends a share to a folder or the drop folder with no agent, in the graph's scope; `GetController` (kept across a rotation and a reconnect in `AppGraph.gets`, a `Retained` holder keyed by machine and agent) looks at, reads and caches a file from the machine; `Handoff` and `TransferFileProvider` give another app a read-only URI of one cached file; `ApkCheck` reads what an APK says about itself; `TransferCache` is the one owner of `cache/camera`, `cache/incoming` and `cache/transfers`.
- **Graph members.** `incoming`, `transferCache`, `transferHold`, `shares`, `shareSender`, `shareNotice`, `gets` and `receiveShare(intent)`, which `MainActivity` calls from `onCreate` and `onNewIntent`. A forgotten machine drops its fetched files through the `forgotten` list.
- **Routes.** `Route.Share` opens from `ShareEvents` (`RootEffects.kt`) once the app is unlocked and a machine is saved, and returns to Home; `ShareRoute` is the chooser's state, `GetRoute` is the Get sheet and the outbox list over `OutputRoute`, and `MachinePage` has the drop folder section (`MachineRoute`).
- **A test seam.** `AppGraph.activityResultsOverride` lets `FilesFlowTest` answer the picker with a MediaStore image: a pick is another app's content, so the real SEC-1 check admits it.

## What the host tests do not cover

The route files are Compose and are checked by compiling both flavors and by the instrumented tests (`ui/`, the graph tests, the smoke test) on an
emulator. The move that created `routes/` was checked by comparing the two files' lines; the narrowing was checked by reading every changed line that did
not mention the graph.
