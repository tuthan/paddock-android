# Alerts (Phase 07)

How Paddock tells a locked phone that an agent needs you, what each path can and cannot promise, and what was measured. Alerts are **measured best effort, never always-on**: they depend on a relay on your machine, a push path you choose, the phone's battery settings and the network, and any of them can make an alert late or missing.

## Three paths, one rule

| Path | What delivers | What Paddock ships | Needs |
| --- | --- | --- | --- |
| **Paddock shows the alert** (connector, UnifiedPush) | A UnifiedPush distributor on the phone (the ntfy app can be one) wakes Paddock, which raises a generic notification itself: its own channels, its own lock-screen privacy. Android only. Raised for any saved machine, except while that machine's own herd is on screen ("Several machines" below). | The relay with `delivery = "unifiedpush"`, and the connector in the app | A distributor installed and opened once |
| **The ntfy app shows the alert** (ntfy topic) | The ntfy app shows the message the relay posted to your ntfy topic. On Android a tap opens Paddock; on an iPhone it is a heads-up only. | `host/paddock-alert-relay.py`, its unit; the app makes the topic and writes the configuration | The ntfy app on the phone; the public server or your own |
| **Watching** | Paddock itself, while it is open but not in front. | Nothing extra: the open connection raises the notification locally | The app running; no background SSH is attempted in this phase |

**A push is a hint. Nothing is sent to an agent because one arrived.** A hint names a place and what the sender saw; the phone never trusts that over a fresh read, and no notification action does anything but open the app (Open, Review). Both pushed paths work with the public ntfy server (`ntfy.sh`) and with your own.

## Set up in one step

**Settings > Locked-phone alerts** (or a machine's page > Locked-phone alerts > Set up or check) asks two things and does the rest:

1. **How should alerts reach you?** *Paddock shows the alert* or *The ntfy app shows the alert*. The first is chosen when a UnifiedPush app (the ntfy app, usually) is installed; otherwise the second. Each card says what it needs.
2. **Which server?** (ntfy-app way only.) *Public server (ntfy.sh)* needs nothing typed. *My own server* asks for its address (`https://`, host with optional port and path) and, if it needs one, an access token. In the Paddock way the server is whatever the ntfy app is set to (its Settings), and the screen says which host the address came from once it has one; Paddock does not ask.

**Turn on alerts…** opens one confirmation that lists exactly what will be written on the machine, then does it over the SSH connection already open, step by step (each step is shown as it runs, the first failure stops the rest with a sentence):

| Step | Mode | What it does |
| --- | --- | --- |
| Register with the ntfy app | Paddock shows | Registers a fresh token with the distributor and waits up to 20 s for the address it gives; reuses a registration that already has one |
| Install the relay script | both | Writes `paddock-alert-relay.py` only when the pinned file is not already there; hash checked before and after |
| Send the address to the machine | Paddock shows | Writes `~/.config/paddock/push-endpoint.json` (mode 600) through SSH stdin; the confirmation names the file and says whoever has the address can send this phone an alert hint |
| Write the configuration and start the relay | both | One script (below) |

The script is the one a person would otherwise paste: it writes the unit and `~/.config/paddock/alert-relay.toml` (owner-only, an earlier different one kept as `alert-relay.toml.bak`), runs the relay's own `--check` on it, and **only then** enables and restarts `paddock-alert-relay.service` and runs `loginctl enable-linger`. A configuration the relay refuses starts nothing and the screen shows the relay's reason. The topic of the ntfy-app way is 24 random bytes (32 URL-safe characters), made on the phone, kept per machine across runs (a new one would orphan the ntfy app's subscription), never typed.

After it ran:

- **Subscribe the ntfy app** (ntfy-app way): *Open in the ntfy app* sends `ntfy://<server>/<topic>` to the ntfy app only (explicit package, because the link holds the topic and no other app that handles `ntfy://` may get it); *Copy the subscribe link* for another phone; the server and topic are shown to type on an iPhone.
- **Send a test alert**: the machine posts one test message the way the relay would (to the topic, or to the address file's endpoint). A success proves the machine reaches the server and the address is right; whether the phone shows it is what you then see. What it says (sent, or why not) is drawn directly under the buttons, and scrolled into view when it arrives, with a polite live region for TalkBack; it first sat under the status card at the top of the screen, out of sight of a button at the bottom, so a tap looked like it did nothing (found 2026-10-07; `AlertRelayScreenTest.theResultOfATestIsDrawnUnderTheButtonsThatAskedForIt` fails on the old layout). Once the lower part is gone (after Turn off) the result stays at the top.
- **Turn off alerts…**: stops and disables the unit and removes the address file (the capability); the configuration stays, so turning alerts on again keeps your topic. Paddock unregisters from the distributor.
- **Status**, at the top: *Alerts are on* (running, set up from this phone), *A relay is running* (set up outside Paddock; turning alerts on here replaces its configuration and keeps the old one), *Alerts need attention* (set up from here but the script is gone, its check fails, the unit is not active, or the address file is missing), *Alerts are off*.
- **Details and manual setup**: the relay's hash, its state on the machine, a button to install the script alone, and the exact text of the setup script for the choices on the form, copyable: what is shown is what runs.

The app writes to the machine only after that one confirmation, and it never enables anything outside it. This replaces the earlier flow (install the script, paste commands on the machine, edit the topic and server in a file, subscribe, register, send the address).

Linux needs systemd on the machine (a user unit). A Mac gets the same setup as a launchd LaunchAgent (next section). A machine with neither (Alpine, say) is told so before anything is written.

### On a Mac (2026-10-07)

The app decides the platform from what the machine said it is when it connected (`uname -s`, the machine's icon: `ServicePlatform.of`), and asks the machine again over the open connection before it reads the relay's state, sends a test or turns alerts off (`AlertRelayHost.platform`), so the script that is shown under *Details and manual setup* is the one that runs. What differs from Linux:

| | Linux | Mac |
| --- | --- | --- |
| Service | `~/.config/systemd/user/paddock-alert-relay.service` (the pinned unit), enabled and restarted with `systemctl --user` | `~/Library/LaunchAgents/io.github.tuthan.paddock-alert-relay.plist`, written by the script (RunAtLoad, KeepAlive, 5 s throttle, log `~/Library/Logs/paddock-alert-relay.log`), loaded with `launchctl bootstrap gui/<uid>`, then `enable` and `kickstart -k` |
| Python | `python3` (3.11 or newer) | found by name in `~/.local/bin`, `/opt/homebrew/bin`, `/usr/local/bin`, `/opt/local/bin` and `~/.pyenv/shims`, 3.14 down to 3.11, and its version checked. **The system `/usr/bin/python3` is never used**: it is 3.9, and running it on a Mac without the developer tools opens an install dialog. The plist names the absolute path, because launchd expands neither `~` nor `$HOME` |
| File hash | `sha256sum` | `shasum -a 256` (a Mac has no `sha256sum`; this also fixed Install on a Mac, which used to fail with "did not match after installing") |
| After a logout | `loginctl enable-linger` | A LaunchAgent lives in the login session: it runs while the user is logged in at the Mac's desktop and starts again at the next login. An unattended Mac needs automatic login (System Settings > Users & Groups). The screen says so after the setup |
| Turn off | `systemctl --user disable --now` | `launchctl bootout` then `launchctl disable` (disable is what keeps it from coming back at the next login); the plist and the configuration stay, so turning alerts on again keeps the topic |
| Status | `systemctl --user is-active` | `launchctl print gui/<uid>/<label>`: "state = running" is running; a plist that is not loaded is "installed, not running"; no plist is "not installed" |

Refusals, all before anything is started: not a Mac (`wrong-os`), no Python 3.11 or newer ("`brew install python`"), an unexpected character in a folder name that could break the plist (`& < > "`), the relay's own `--check` failing. If **nobody is logged in to the Mac's desktop**, `launchctl bootstrap` has no `gui/<uid>` domain to load into: the script stops there, the configuration is already written, and the screen says to log in once (or turn on automatic login) and try again.

A Python from python.org (not Homebrew) has no certificate bundle until its `Install Certificates.command` has been run; the relay's HTTPS post to the ntfy server would then fail, which *Send a test alert* shows as an error. The guarded-answers scripts (`docs/guarded-answers.md`) still use `sha256sum` and are not yet Mac-ready.

## Which server

| | Public `ntfy.sh` | Your own ntfy server |
| --- | --- | --- |
| Setup | none | a server; its address (and token) |
| Who can read the topic | anyone who learns it (topics are the only secret on a default server) | with `auth-default-access: deny-all`, only logged-in users; a leaked topic is then harmless |
| What the server operator sees | timing of your alerts, the IPs of the machine and the phone, the title (the machine's name) and the fixed text; in the Paddock way only `{"v":1,"h":<machine id>,"n":<nonce>}` | the same, but on infrastructure you control |
| iPhone | instant | needs `upstream-base-url: "https://ntfy.sh"` in `server.yml`: the server forwards only a poll request (message id and a SHA-256 of the topic URL, never the alert) so Apple's push can wake the app (ntfy docs) |

For a machine that touches regulated work, prefer your own server: the alert content is generic, but the timing of agent activity stays on infrastructure you control. Hardening, from the ntfy documentation:

- On a private server UnifiedPush topics all start with `up`; allow the applications to write and nothing else: `ntfy access '*' 'up*' write-only` with `auth-default-access: "deny-all"`. A leaked Paddock address then lets someone send an alert hint but not read your timing; the phone reads with its own login.
- In the ntfy-app way give the relay a **token** (the field on the form) and have the phone log in; a leaked topic is then harmless too.
- The machine's name is in the clear in the ntfy-app way (the notification title) and on the lock screen. Use a neutral name (no client, project or hostname): Machines > the machine's page > Name.
- To get a new address in the Paddock way: Turn off alerts, then turn them on (a new registration, a new address). A new topic in the ntfy-app way is not offered yet: the topic is kept on purpose; remove the machine and add it again, or edit the topic in `alert-relay.toml` and subscribe to it.

## What it looks like

Descriptions of the notifications, from the relay's message and the apps' documented behaviour. They are not screenshots; no iPhone was available.

**Android, Paddock shows it.** A notification in Paddock's *Needs you* channel, "Paddock: attention on Laptop", "An agent needs your attention." With the lock-screen setting on (the default) the lock screen shows only those generic words. A tap opens that machine's herd and says what is true now.

**Android, the ntfy app shows it.** The message the relay posted:

```
Paddock: attention on Laptop          (title)
An agent needs you.                   (blocked; "An agent finished." for done)
[ Review ]                            (a view action; the tap on the message does the same)
```

Priority high for a blocked agent and default for a finished one. A tap opens Paddock.

**iPhone, the ntfy app shows it.** Paddock itself is Android only. The ntfy iOS app can still receive the same message, so an iPhone gets a heads-up:

```
┌ ntfy ───────────────────────────── now ┐
│ Paddock: attention on Laptop           │
│ An agent needs you.                    │
└────────────────────────────────────────┘
```

(Illustrative: the frame is Apple's and the app's, and varies by iOS version.) Set up from the phone that has Paddock, or by hand: in the ntfy app add a subscription, enter the topic (and, for your own server, its address). **Several machines:** subscribe the ntfy app to each machine's own topic; every machine then alerts on the iPhone whatever else is running, and the title names the machine ("Paddock: attention on Laptop", "… on Server"). The ntfy app never hides a message because something is on screen, which is the rule that needed fixing on Android (below). **What an iPhone gets is the same as Android's ntfy path:** a heads-up for `blocked` and `done` only, so a Codex permission request raises nothing there either (below). Paddock's guarded Yes/No (`guarded-answers.md`) is Android only: nothing on an iPhone answers an agent. The `ntfy://` link is documented for Android only, so Paddock does not claim it opens the iOS app. Tapping the message or *Review* tries to open a `paddock://` link, which no iPhone app handles, so it does nothing useful: open your SSH or terminal client yourself. Instant delivery needs the server to be Apple-push connected (`ntfy.sh` is; your own needs `upstream-base-url`, above).

## Several machines, and what an alert is for (2026-10-07)

**Every saved machine alerts, whichever one is watched.** Each machine runs its own relay and has its own address (UnifiedPush) or topic (ntfy), registered on the phone for that machine. A notification names its machine, and a tap switches the watch to it (free, and it does not change the machine the user chose; `docs/machines.md`), reads that machine fresh and says what is true now. Setting a machine up runs over the live connection, so each machine is watched once to turn its alerts on; the machine's page says "Watch this machine to set up or check its alerts" until then.

**While Paddock is open, only the watched machine is "on screen".** The herd shows one machine. The first version of the connector skipped every push whenever any Paddock screen was in front, so with Paddock open on machine A an agent on machine B that needed you raised nothing and showed nothing (a user reported it). Now:

- a push is skipped only for the machine whose herd is in front (`AlertScreen.showsIt`); a push for any other machine raises its notification, over the app if need be;
- the app lock counts: a covered app shows no herd, so nothing is skipped while the lock is up;
- coming to the front, or switching the watched machine while in front, clears the notifications of **the machine now on screen** and no other's (`AlertNotifier.cancelFor`; each notification carries its link, which names the machine). It used to clear all of them, which also dismissed another machine's alert that nothing on screen replaced. A Done stack's summary goes with its last child.

The in-app herd, the attention counts and the Yes/No sheet are still the watched machine's only. What Pro adds (`hosts.merged`) is a count on each other machine's chip on Home, read by a light look while the app is in front (`docs/machines.md`, "What a chip says about its machine"): "Beta server · 2 need you · 20 s ago". It is not a merged queue and it is not an alert: in the background only the push alerts above reach the user.

**What raises an alert.** The relay alerts on a transition of herdr's agent status to `blocked` or `done`, nothing else. So:

| Agent | Alert |
| --- | --- |
| Claude Code, opencode: a permission prompt or a question (`AskUserQuestion`, opencode's `question` tool) | yes, herdr shows `blocked` for each |
| Codex: a permission request | **no**. herdr keeps the agent `working` while the hook waits for the phone and shows `blocked` only when Codex's own prompt appears, after the hook's window (`guarded-answers.md`). Open the agent's Output tab in Paddock within the window; an earlier version of the hook reported `blocked` itself and was removed (herdr's own Codex integration ignored it, and a killed hook left the pane stuck) |
| Any agent finishing | yes (`done`) |

## The relay

`host/paddock-alert-relay.py` is Python 3.11 or newer (it needs `tomllib`), standard library only. It watches one herdr session and, when an agent newly becomes **blocked** or **done**, posts one generic message. It uses only `session.snapshot` and `events.subscribe`: it never reads pane text, and nothing about an agent (name, title, prompt, output) is in what it posts.

Setup is the app's one step above; what it writes is below, and the text it runs is shown under *Details and manual setup*, so the same thing can be done by hand. The relay needs Python 3.11 or newer on the machine. `python3 ~/.local/share/paddock/paddock-alert-relay.py --check` says whether the configuration parses and herdr answers (exit 0 fine, 2 configuration, 3 herdr not reachable).

Configuration keys (the app writes these; `host/alert-relay.example.toml` documents them): `socket`, `profile` (the machine id the phone shows), `label`, `delivery` (`ntfy` or `unifiedpush`), `alert_on`, `debounce_seconds` (15), `expiry_seconds` (600), `heartbeat_seconds` (30), `[ntfy] url, topic, token`, `[unifiedpush] endpoint_file`. A bearer token is sent only over https (or loopback http) and a token in a group- or world-readable file is refused. A redirect is never followed.

How it decides, in `Tracker` (pure, tested without herdr): the first read is a silent baseline; an alert is a *transition* to blocked or done (a status change, or a changed `state_change_seq`); the same terminal and state alerts at most once per debounce window; an event and the read that confirms it are one alert. An `Outbox` retries with exponential backoff (capped at 300 s), drops items older than `expiry_seconds` and holds at most 100. The process logs counts and reason codes, never payloads, ids or the token. The sequence number in a link rises across restarts (persisted, seeded from the wall clock).

### herdr 0.9.1 facts the design rests on

- `events_lost` is **never sent** (found 2026-10-02, `paddock-harness/probe-event-loss`): a subscriber that lags silently loses events. Events are therefore only *invalidations*; a closed stream and a heartbeat also lead to a snapshot read, and a change that happened while a stream was down is alerted on once after the reconnect.
- `done` is detection-only: only `blocked`, `idle`, `working` and `unknown` can be reported, so a test cannot drive `done` through `pane report-agent`.
- A subscription gives no initial status sample; a repeated report of the same state emits nothing; `state_change_seq` is a global counter bumped per real transition; status subscriptions need a `pane_id`; session names allow dots.

## The link

```
paddock://open?h=<profile>&s=<session>&t=<terminal_id>&p=<pane_id>&st=<blocked|done>&at=<unix seconds>&n=<sequence>
paddock://machine?h=<profile>&n=<nonce>        (only Paddock's own push notification builds this)
```

`DeepLink.parse` is the security boundary, because the intent filter admits any app's VIEW intent (no BROWSABLE: a web page cannot open it). It accepts printable ASCII only, at most 512 characters and 16 parameters, decodes percent escapes strictly, refuses fragments, userinfo, ports, paths and repeated known keys, ignores unknown keys, and checks every field against the alphabet of the thing it names (`h` as a host profile id, `s` as a herdr session name, `at` 1..4102444800, `n` 0..2^53-1). It never echoes a value from a hostile link. A link never carries anything phone-local (no epoch): the relay cannot know it.

### What a tap does

The app switches to the machine the link names, waits for **a read made after the tap arrived, on a live connection** (up to 20 s), and resolves the terminal id against that read. The pane id in the link is never used (panes are renumbered); the terminal id is the identity.

| The fresh read shows | The app shows |
| --- | --- |
| the terminal, still in the linked state | the agent (Terminal tab, observing, for a blocker) and "Opened from an alert. This agent is still blocked." Answering stays a deliberate Request control |
| the terminal in another state | the agent's Output and "State changed since the alert: this agent is working now." |
| no such agent, another herdr session, or a machine this phone does not have | the herd and "No longer observed: ..." (never an answer surface) |
| nothing within 20 s | the herd and "Could not read the herd on X to check that alert. Open the agent from the herd once the connection is back." The alert is **not** kept |
| a link that does not parse | "That alert link is not valid, so it was ignored." |

Opening a Done from its alert acknowledges it, as tapping its row does. The same message delivered twice within 5 s (a tap that fires both the click and the action) is one arrival. A recreated activity does not replay the link.

## Notifications on the phone

Four channels, fixed ids: **Needs you** (high), **Done** (default, silent, grouped per machine), **Machines** (low) and **Watching** (min); the last two are reserved and nothing posts to them yet. With the lock-screen redaction on (the default) every notification is private with a public version of generic words only ("Paddock: attention on <machine>", "An agent needs you."); the agent's title appears only after unlock. The only actions are **Open** and **Review**, and both are the same immutable, explicit VIEW intent.

`POST_NOTIFICATIONS` (Android 13 and later) is asked for in context, when **Alerts from this app** is turned on. A refusal has a recovery row in Settings that goes to the right system page (the permission dialog while the system will still show it, app notification settings after that, channel settings when only a channel is off). The app clears the notifications of the machine whose herd comes to the front (and of a machine it is switched to while in front): the herd in front of you is the alert for that machine, and for no other ("Several machines" above).

## Connector mode (UnifiedPush)

The app implements the UnifiedPush Android specification (AND_3.1.0) directly, with no library: `org.unifiedpush.android.connector:connector` has no Socket result (404, 2026-10-02), so it has no row to stand on; [dependency-reviews.md](dependency-reviews.md) records that. The spec is five broadcast actions and a token.

- **Registering.** Part of the one-step setup: the installed distributor is found through the manifest's `queries` entry for `org.unifiedpush.android.distributor.REGISTER` (the ntfy app is picked first when several are installed; the user picks another only on the form). Paddock sends REGISTER with a fresh random UUID token per machine, a short description, and its identity: the `FLAG_SHARE_IDENTITY` broadcast option on Android 14 and later, an immutable PendingIntent to `org.unifiedpush.dummy_app` before. It registers again with the same token on every start, as the spec asks.
- **The token is the only authentication.** Android cannot say who sent a broadcast, so a broadcast whose token this phone does not hold is dropped, as is anything that breaks the spec's rules for its action (token over 100 bytes, an endpoint that is not https or loopback http or carries credentials, a message of 0 or over 4096 bytes). A failure before an address changes the token for the next try; an unregistration or an unregistered notice drops the registration.
- **Sending the address to the machine** is a step of the one-step setup, and the one confirmation names the file and the risk: the address is a capability, so whoever holds it can send this phone an alert hint. It is written to `~/.config/paddock/push-endpoint.json` as `{"endpoint": "..."}` through SSH stdin (never in a command line), mode 600, replaced atomically. The screen shows the address's host, never the address. Unregistering removes the file.
- **The payload** is `{"v":1,"h":"<profile>","n":"<nonce>"}` and nothing else. It is not encrypted because it carries nothing worth encrypting; the consequence is that the push server must accept a plain POST (ntfy does; a Web Push server that insists on RFC 8291 content would refuse it, and the relay's stdlib-only design cannot encrypt). Paddock uses the *token*, not the payload's `h`, to pick the machine, and the nonce only for dedupe. Bytes that are not this at all still wake the alert and show nothing of themselves.
- **On receipt** Paddock raises one generic notification per machine ("An agent needs your attention.", Needs-you channel; a push cannot say Done), unless **that machine's** herd is in front (`AlertScreen.showsIt`: the app is resumed, the app lock does not cover it, and the machine is the watched one), and acknowledges the message. Tapping it opens that machine's herd, reads it fresh, and says "Opened from an alert: 2 agents need you on X." or "No longer observed: no agent on X needs you now." It opens no agent by itself.
- **Missing distributor.** The form says Paddock needs the ntfy app (or another UnifiedPush app) and cannot start in this way; *The ntfy app shows the alert* works with only the ntfy app installed.

## Privacy: who sees what

| Party | Sees |
| --- | --- |
| The ntfy server, ntfy-app way (public or yours) | the topic, a fixed title ("Paddock: attention on <the machine's name>") and text, and a link naming a machine id, session, terminal id, pane id, `blocked` or `done`, a time and a sequence. No agent name, prompt or output. And, as any server does: timing and the IPs of the machine and the phone |
| The ntfy server, Paddock way | the address's path, `{"v":1,"h":...,"n":...}` and nothing else; the same timing and IPs |
| Apple's push service, iPhone with your own server | a poll request forwarded by your server through `ntfy.sh`: the message id and a SHA-256 of the topic URL; never the alert (ntfy docs) |
| The lock screen | generic words only (redaction on); the agent's title only after unlock |
| The host | the pinned script, the unit, the configuration (owner-only; it holds the topic and any token) and (Paddock way) the address file; the relay reads no pane text |
| This phone | for each machine, what was set up (the way, the server, the topic and any token) in app-private storage, forgotten when the machine is removed |

## Failure cases

- **Host offline or relay stopped:** the queue expires (`expiry_seconds`) and the phone shows nothing stale as new; the next tap resolves against a fresh read or says it could not.
- **Blocker gone by the time the phone opens:** "No longer observed", never an answer surface.
- **Duplicates:** deduped per terminal and state in the relay, and per profile and sequence (or nonce) on the phone.
- **Python older than 3.11:** the relay exits with a message at start; `--check` says so.
- **Notifications blocked, or a channel turned off:** Settings says which, with the way to the system page.
- **A machine without systemd that is not a Mac** (Alpine): the setup says so before anything is written; nothing is changed. Run the relay your own way with the text under *Details and manual setup*. If the machine is a Mac that Paddock took for Linux, open its page and choose the macOS icon.
- **The machine will not keep the relay after a logout** (`loginctl enable-linger` refused): the relay runs and the screen says to run `sudo loginctl enable-linger $USER` there.
- **The relay refuses the configuration:** nothing is enabled or restarted; the screen shows the relay's reason (`--check`), never a secret.
- **The setup stops half way** (the connection drops): every step is safe to run again, so turning alerts on again finishes it.
- **A test alert goes out but nothing shows:** the ntfy app is not subscribed to the topic (the Subscribe step), or on an iPhone with your own server `upstream-base-url` is not set.
- **ntfy app killed or battery-restricted:** nothing is delivered until it runs again. This is why the copy says best effort; see the measurements.

## Tests

| Tier | What | How |
| --- | --- | --- |
| Python | relay config, message shape, tracker, outbox, sequence, notifier (loopback HTTP), a fake herdr unix server, process tests | `python3 tools/test-alert-relay.py` |
| Python, live | the relay against the disposable herdr session | `PADDOCK_TEST_SOCKET=~/.config/herdr/sessions/paddock-test/herdr.sock python3 tools/test-alert-relay.py --live` |
| Unit file | verify, run, SIGKILL, resume within 60 s under a transient user unit | `PADDOCK_TEST_SOCKET=... paddock-harness/check-alert-unit.py` |
| Core | link parser, resolver, arrival rule, inbox, notification content, access rules, relay host, UnifiedPush rules; the one-step setup: server and topic rules, every written configuration run through the relay's own `parse_config`, the setup and turn-off scripts run under a real `sh` with stand-ins for systemd (order of steps, backup, nothing started before the check, owner-only files), the test alert posted to a loopback server, the step runner (`AlertSetupTest`, `AlertSetupRunnerTest`); which machine a push or a notification is for and when the screen is the alert (`AlertScreenTest`) | `./gradlew :core:test` |
| Device | channels, redaction, actions, permission state (`NotifyTest`, `NotifyPermissionTest`), the alert setup screen (`AlertRelayScreenTest`: form, confirmation, progress, status, subscribe, details, 200 % font, accessibility audit), settings, the connector against a spec-following test distributor (`PushConnectorTest`) | `paddock-harness/run-ui-tests.sh`, `paddock-harness/run-permission-tests.sh` |
| End to end | the real relay, a loopback ntfy stub (`paddock-harness/ntfy-stub.py`), the tap as a VIEW intent, every outcome above, a reconnect that advances the epoch, a cold start, then connector mode from the relay to the notification | `paddock-harness/run-alerts-e2e.sh [serial]` |
| Lock screen | a notification raised with the phone locked, read from the lock screen itself: generic text only by default, the agent's title only once the privacy setting is turned off | `paddock-harness/check-lockscreen.sh [serial]` |
| Permission | the real system dialog, a first and a second denial, the way to the app's system page, a grant there seen without a restart, one channel turned off on its system page (API 33 and later); notifications turned off for the whole app (API 32 and earlier) | `paddock-harness/check-notification-permission.py [serial] [out-dir]` |
| Measurement | the relay's own latency in both delivery modes and two outages, against a loopback server | `PADDOCK_TEST_SOCKET=... paddock-harness/measure-alert-latency.py` |

Only `paddock-test` (or `paddock-test-<suffix>`) is ever touched in herdr, on panes the test creates. Nothing is posted anywhere but loopback. After any edit under `host/`, run `tools/pin-host.sh`: the app refuses to start when a bundled script does not match its pin in `host/SOURCE.json`.

## Measurements

`paddock-harness/measure-alert-latency.py` runs the real relay against a loopback stand-in for the server and drives blocked transitions in `paddock-test` with `pane report-agent` at random gaps (3 to 15 s). Time is taken from the moment the transition is reported to herdr to the moment the stub receives the post: the relay's whole contribution, and nothing after it.

Run 2026-10-02, herdr 0.9.1, 20 transitions per mode, debounce 1 s:

| Mode | Delivered | Median | p95 | Max | Misses |
| --- | --- | --- | --- | --- | --- |
| ntfy publish | 20 of 20 | 32 ms | 102 ms | 103 ms | 0 |
| UnifiedPush post | 20 of 20 | 54 ms | 93 ms | 102 ms | 0 |

Exactly one post arrived per transition in both modes (no post unmatched to a transition). Two outages: with the server down for 20 s, the one message arrived 15.9 s after it returned (35.9 s after the transition, late by the backoff), once; with the server down past a 10 s expiry, nothing stale arrived after it returned.

These numbers are **not** delivery to a phone. The network to a real ntfy server, the server, the ntfy app, the phone's battery state and Doze are not in them. The note's protocol for those (a locked phone, twenty transitions over two hours on Wi-Fi and again on cellular over the VPN, forced Doze for a quarter of them, a Wi-Fi to cellular switch, a phone reboot, the ntfy app force-stopped once) needs a physical phone and has not been run; Settings says so.

## What was not verified, and why

The machine-aware rule (2026-10-07) is covered on an API 36 emulator: a push for a machine that is not on screen, with Paddock in front, raises its notification (`PushConnectorTest`; it fails with the old app-wide skip), and `cancelFor` clears one machine's notifications and an orphaned Done summary (`NotifyTest`). Not run: two real machines with a real distributor, the lock screen up with a push arriving, and the ntfy app on an iPhone with two topics.

`run-alerts-e2e.sh` stages 1 and 2 pass (21 checks) after the Review change: a blocked Claude Code agent opened from an alert now lands on its **Output** tab (the harness agent is a `claude` kind). **Stage 3 (connector mode through the relay screen) is stale and fails:** it was written for the standalone "Send the address to <machine>…" step and the "Alert relay" title, which the one-step setup replaced earlier on 2026-10-07; it no longer reaches "the address ready to send". It needs rewriting around **Turn on alerts…** with the *Paddock shows the alert* way. The connector itself is still covered by `PushConnectorTest` and the relay's Python tests.

See the evidence report for the run. In short: nothing here ran on a physical phone, against a real ntfy server or the ntfy app, or across a host reboot; the connector was tested against a distributor that follows the spec, not a real one (the system will not start a process for a test package, so the test distributor runs in the app's own process and the manifest `queries` discovery is unexercised).

The one-step setup (2026-10-07) was run against a scripted machine and a real `sh` (core tests) and its screen on an API 36 emulator; it has not been run against a real machine over SSH from a phone, a real ntfy server, the ntfy app on Android or iOS, or a real Mac. The Mac way is proved the same way as the Linux one, against a real `sh` with stand-ins for `uname`, `launchctl` and Python (the generated LaunchAgent is parsed with Python's `plistlib`), and over a scripted SSH session; **`launchctl` itself, the `gui/<uid>` domain over SSH, and the Python search on a real Mac have not been run**. The notification descriptions under "What it looks like" are from the relay's message and ntfy's documentation, not from a device.
