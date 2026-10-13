# The demo machine

**Try a demo** on Welcome (Phase 16, slice 3) gives a phone with no machine something to look at: one machine, "Demo machine", that exists only in memory. It is for a person deciding whether Paddock fits, and for a store reviewer, who has no herdr machine to point the app at. It is also where the listing's screenshots come from (`docs/release.md`).

**It opens no socket, writes no profile, asks for no permission.** There is no connector, so no local-network prompt and no host-key prompt; nothing leaves the phone. The release smoke's socket sampler runs over the demo and expects an empty list (`paddock-harness`, `check-demo.sh`), and `DemoSessionTest` reads the demo's source and fails on a network, file or Android import.

## What it is made of

| Piece | Where | What it does |
| --- | --- | --- |
| `Demo`, `DemoCopy` | `:core`, `demo/Demo.kt` | the demo profile (`demo-invalid`, host `demo.invalid`, a name no real host has), what the machine "is" (herdr path, home, session `demo`, herdr 0.9.1, protocol 22), every "Not in the demo" sentence |
| `DemoHerd` | `demo/DemoHerd.kt` | four agents in two workspaces, their states on a script, their screens, and the JSON herdr would answer (the same shapes as `fixtures/herdr-0.9.1/`) |
| `DemoSession` | `demo/DemoSession.kt` | an `SshSession` that answers each command the app sends from the herd: the herdr locator and probes, `herdr session list`, `agent read`, `api snapshot`, the terminal size probe, the relay script's JSON requests and its event stream, `terminal session observe`. A command it does not know is refused (exit 127) and remembered |
| `DemoMode`, `DemoProfileStore` | `demo/DemoMode.kt` | whether the demo runs and its herd; the profile store the app uses, which lists the demo beside the saved machines and never writes it to the file |
| the switch | `AppGraph.connect` | for the demo profile, the session factory returns a `DemoSession` after 300 ms of "Connecting", before the connector is touched |

The app code is the real code. `DemoSessionTest.theRealBringUpReachesAHerdOfFour…` runs the real `HostSessionController` and `MonitoredHost` over a `DemoSession`: the relay check is answered with the bundled script's hash (so the install screen is never shown), the herd is read, the lifecycle subscription is acknowledged, the OS and hostname are read, and `session.unanswered` must be empty. A step the app takes that the demo does not know fails there, not on a reviewer's phone.

## The herd, and its clock

Times are from the moment the demo starts.

| Agent (name, kind) | Row title | Start | Later |
| --- | --- | --- | --- |
| `refactor`, claude | Refactor order validation | working | done at 2 minutes |
| `migrate`, claude | Invoice status migration | done | |
| `docs`, codex | Write the install guide | idle | blocked at 20 seconds: it asks to run `npm run docs:check` (the question is its detection text, so Home's card shows it and Review prompt opens its Terminal tab) |
| `tests`, opencode | Keep the test suite green | idle | |

A prompt sent to an agent that takes one (idle or done) is accepted and journaled like any other; the agent works for 8 seconds and then finishes, and its screen shows the prompt and, after the work, "This is a demo, so nothing ran". The text lives in memory only. A blocked agent refuses a prompt with the readiness check's own sentence ("The agent is blocked and needs an answer. Use the terminal."). The Terminal tab observes the agent's screen in a fixed 80 × 36 pane; the demo never gives control.

Alerts follow the herd as they do for any machine: the open app raises its own notification when `docs` blocks while Paddock is open but not in front. No notification permission is asked for by the demo.

## What it says it cannot do

Each says so in place, in the sentence style of `docs/buttons.md`, and does nothing else. There is no dead button.

| Where | What shows |
| --- | --- |
| Machine page, Wake-on-LAN | "Waking a machine is not in the demo. It needs your own machine." in place of the section |
| Machine page, Files from this phone go to | the drop folder's sentence, in place of the section |
| Machine page, Locked-phone alerts › Set up or check… | a notice: alert setup is not in the demo |
| Machine page, Guarded answers | a notice: guarded answers are not in the demo |
| Machine page, Change address…, Search the network | not drawn: the demo machine has no address to change |
| Composer, Attach | the attach control is off with the files sentence |
| Terminal, Request control | the terminal's banner: taking control is not in the demo |
| Spaces, Start an agent… | the page says starting an agent is not in the demo (behind the Pro gate where the build has one) |
| Spaces, Stop… and Delete… | behind the Pro gate; herdr's answer is a refusal named `not_in_demo` |
| Files shared to Paddock | one notice: sending files is not in the demo; the sender's file is not read |
| Output, Get and the Files list | not offered: the demo machine has no files (`transfers` is null for it) |

The relay, alert, answers and control scripts are never given to the demo's controller, so nothing is installed, offered or asked.

## Entering and leaving it

- **Enter:** Welcome › **Try a demo** (Ghost). `AppGraph.startDemo()` adds the demo profile through `MachineBook.add`, which makes it the watched and chosen machine. Home says "This is a demo machine. Nothing here runs on a computer." above its herd, and Settings' About line says "demo machine" where a real machine shows its herdr version.
- **Leave:** Remove… on the machine's page (Machines › Demo machine), which goes through the same removal as any machine. Welcome returns. Adding a real machine also ends the demo.
- **What is left:** nothing. The demo's profile is never written; `MachineRemoval` forgets its ledger rows, its widget copy and (for the demo only) its rows in the operation journal; a process that ends with the demo running leaves the settings naming a machine that no longer exists, which the next start treats as no machine, and `AppGraph` forgets what the demo left in the ledger, the widget cache and the journal before it reads the machines.

## Tests

| Test | What it proves |
| --- | --- |
| `DemoSessionTest` (`:core`) | the real bring-up over the demo, with no unanswered command; the script on a clock; a prompt, its refusal when blocked; `agent read` and the blocked detection text; the observe frame; the terminal size probe; the refusals by name; no network or file import in the demo's source; the profile never stored, and a real machine ends the demo |
| `ButtonRolesTest` | "Try a demo" is a Ghost button |
| `WelcomeAndConnectTest` (instrumented) | the entry is not drawn unless wired; its handler is called; it is spoken with what it is |
| `DemoFlowTest` (instrumented, `e2e`) | Welcome to Home to the blocked agent to Output to a prompt to Remove, and nothing left; the machine page, the terminal and Spaces say what the demo does not do. It writes `screens/demo-*.png` into the app's external files |

## The reviewer's path

The Play Console's App access text is "Tap *Try a demo* on the first screen; the real app needs your own machine with herdr 0.9.1 reachable over SSH, which cannot be provided" (`docs/privacy-checklist.md`, C4). The demo reaches Home, Output, the composer, Spaces and the Pro gate; it does not reach alerts through a relay, files, a second machine or the terminal's control.

A purchase from the demo's gate is a real purchase: the gate is the real gate (`docs/billing.md`).
