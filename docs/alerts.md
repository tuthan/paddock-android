# Alerts (Phase 07)

How Paddock tells a locked phone that an agent needs you, what each path can and cannot promise, and what was measured. Alerts are **measured best effort, never always-on**: they depend on a relay on your machine, a push path you choose, the phone's battery settings and the network, and any of them can make an alert late or missing.

## Three paths, one rule

| Path | What delivers | What Paddock ships | Needs |
| --- | --- | --- | --- |
| **Stopgap: Alerts via ntfy app** | The ntfy app shows a message the host relay posted to your ntfy topic. Tapping it opens Paddock. | `host/paddock-alert-relay.py`, its unit and example configuration | ntfy app on the phone, an ntfy server (your own is recommended), the relay running on the machine |
| **Connector: UnifiedPush** | A UnifiedPush distributor on the phone (the ntfy app can be one) wakes Paddock, which raises a generic notification. | The same relay with `delivery = "unifiedpush"`, and the connector in the app | A distributor installed and opened once, the address sent to the machine |
| **Watching** | Paddock itself, while it is open but not in front. | Nothing extra: the open connection raises the notification locally | The app running; no background SSH is attempted in this phase |

**A push is a hint. Nothing is sent to an agent because one arrived.** A hint names a place and what the sender saw; the phone never trusts that over a fresh read, and no notification action does anything but open the app (Open, Review).

## The relay

`host/paddock-alert-relay.py` is Python 3.11 or newer (it needs `tomllib`), standard library only. It watches one herdr session and, when an agent newly becomes **blocked** or **done**, posts one generic message. It uses only `session.snapshot` and `events.subscribe`: it never reads pane text, and nothing about an agent (name, title, prompt, output) is in what it posts.

Setup, all on the machine, all your steps (the app never enables anything):

1. In the app: **Settings > Locked-phone alerts**. The screen shows the script's SHA-256, installs only that pinned file (after a confirmation, with owner-only permissions and a hash check before it is ever run), and shows the commands below with this machine's id and socket filled in.
2. The commands write `~/.config/systemd/user/paddock-alert-relay.service` (the shipped unit: `Restart=always`, `RestartSec=5`, `NoNewPrivileges=yes`) and, only if you have none, `~/.config/paddock/alert-relay.toml` (mode 600). Edit the ntfy `url` and `topic` in it. Use a long random topic: the topic name is the only secret in this path.
3. `python3 ~/.local/share/paddock/paddock-alert-relay.py --check` says whether the configuration parses and herdr answers (exit 0 fine, 2 configuration, 3 herdr not reachable).
4. `systemctl --user daemon-reload && systemctl --user enable --now paddock-alert-relay.service`, and `loginctl enable-linger "$USER"` so the unit runs without a login session and comes back after a reboot.

Configuration keys (`host/alert-relay.example.toml`): `socket`, `profile` (the machine id the phone shows), `label`, `delivery` (`ntfy` or `unifiedpush`), `alert_on`, `debounce_seconds` (15), `expiry_seconds` (600), `heartbeat_seconds` (30), `[ntfy] url, topic, token`, `[unifiedpush] endpoint_file`. A bearer token is sent only over https (or loopback http) and a token in a group- or world-readable file is refused. A redirect is never followed.

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

`POST_NOTIFICATIONS` (Android 13 and later) is asked for in context, when **Alerts from this app** is turned on. A refusal has a recovery row in Settings that goes to the right system page (the permission dialog while the system will still show it, app notification settings after that, channel settings when only a channel is off). The app clears its notifications when it comes to the front: the herd in front of you is the alert.

## Connector mode (UnifiedPush)

The app implements the UnifiedPush Android specification (AND_3.1.0) directly, with no library: `org.unifiedpush.android.connector:connector` has no Socket result (404, 2026-10-02), so it has no row to stand on; [dependency-reviews.md](dependency-reviews.md) records that. The spec is five broadcast actions and a token.

- **Registering.** The user picks an installed distributor (found through the manifest's `queries` entry for `org.unifiedpush.android.distributor.REGISTER`). Paddock sends REGISTER with a fresh random UUID token per machine, a short description, and its identity: the `FLAG_SHARE_IDENTITY` broadcast option on Android 14 and later, an immutable PendingIntent to `org.unifiedpush.dummy_app` before. It registers again with the same token on every start, as the spec asks.
- **The token is the only authentication.** Android cannot say who sent a broadcast, so a broadcast whose token this phone does not hold is dropped, as is anything that breaks the spec's rules for its action (token over 100 bytes, an endpoint that is not https or loopback http or carries credentials, a message of 0 or over 4096 bytes). A failure before an address changes the token for the next try; an unregistration or an unregistered notice drops the registration.
- **Sending the address to the machine** is a separate, explicit step with its own confirmation naming the file and the risk: the address is a capability, so whoever holds it can send this phone an alert hint. It is written to `~/.config/paddock/push-endpoint.json` as `{"endpoint": "..."}` through SSH stdin (never in a command line), mode 600, replaced atomically. The screen shows the address's host, never the address. Unregistering removes the file.
- **The payload** is `{"v":1,"h":"<profile>","n":"<nonce>"}` and nothing else. It is not encrypted because it carries nothing worth encrypting; the consequence is that the push server must accept a plain POST (ntfy does; a Web Push server that insists on RFC 8291 content would refuse it, and the relay's stdlib-only design cannot encrypt). Paddock uses the *token*, not the payload's `h`, to pick the machine, and the nonce only for dedupe. Bytes that are not this at all still wake the alert and show nothing of themselves.
- **On receipt** Paddock raises one generic notification per machine ("An agent needs your attention.", Needs-you channel; a push cannot say Done), unless the herd is in front, and acknowledges the message. Tapping it opens that machine's herd, reads it fresh, and says "Opened from an alert: 2 agents need you on X." or "No longer observed: no agent on X needs you now." It opens no agent by itself.
- **Missing distributor.** Connector mode says it is unavailable; the stopgap still works if the ntfy app is installed.

## Privacy: who sees what

| Party | Sees |
| --- | --- |
| The ntfy server (stopgap) | the topic, a fixed title and text, the machine's label, and a link naming a machine id, session, terminal id, pane id, `blocked` or `done`, a time and a sequence. No agent name, prompt or output |
| A UnifiedPush server | the address's path, `{"v":1,"h":...,"n":...}` and nothing else |
| The lock screen | generic words only (redaction on); the agent's title only after unlock |
| The host | the pinned script, the unit, the configuration and (connector mode) the address file; the relay reads no pane text |

## Failure cases

- **Host offline or relay stopped:** the queue expires (`expiry_seconds`) and the phone shows nothing stale as new; the next tap resolves against a fresh read or says it could not.
- **Blocker gone by the time the phone opens:** "No longer observed", never an answer surface.
- **Duplicates:** deduped per terminal and state in the relay, and per profile and sequence (or nonce) on the phone.
- **Python older than 3.11:** the relay exits with a message at start; `--check` says so.
- **Notifications blocked, or a channel turned off:** Settings says which, with the way to the system page.
- **ntfy app killed or battery-restricted:** nothing is delivered until it runs again. This is why the copy says best effort; see the measurements.

## Tests

| Tier | What | How |
| --- | --- | --- |
| Python | relay config, message shape, tracker, outbox, sequence, notifier (loopback HTTP), a fake herdr unix server, process tests | `python3 tools/test-alert-relay.py` |
| Python, live | the relay against the disposable herdr session | `PADDOCK_TEST_SOCKET=~/.config/herdr/sessions/paddock-test/herdr.sock python3 tools/test-alert-relay.py --live` |
| Unit file | verify, run, SIGKILL, resume within 60 s under a transient user unit | `PADDOCK_TEST_SOCKET=... paddock-harness/check-alert-unit.py` |
| Core | link parser, resolver, arrival rule, inbox, notification content, access rules, relay host, UnifiedPush rules | `./gradlew :core:test` |
| Device | channels, redaction, actions, permission state (`NotifyTest`, `NotifyPermissionTest`), the relay screen, settings, the connector against a spec-following test distributor (`PushConnectorTest`) | `paddock-harness/run-ui-tests.sh`, `paddock-harness/run-permission-tests.sh` |
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

See the evidence report for the run. In short: nothing here ran on a physical phone, against a real ntfy server or the ntfy app, or across a host reboot; the connector was tested against a distributor that follows the spec, not a real one (the system will not start a process for a test package, so the test distributor runs in the app's own process and the manifest `queries` discovery is unexercised).
