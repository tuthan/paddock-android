# Billing and entitlements

Phase 13 (store, billing, entitlements), as built on 2026-10-04. The plan and its decisions are in the vault (`herdr-android/phases/13-store-billing-and-entitlements.md`, `06-monetization-and-launch.md`). Nothing here sells anything yet: nothing has run against Google Play (no Play Console app, no licence tester, no phone with Play in this session), and the twelve-month window and compatibility policy (M7) are not written, so no `play` build should be uploaded before they are. Decisions taken 2026-10-04: **M4**, the `foss` build is the free version and anyone who wants every capability builds from source; **M1**, Apache-2.0; **M8**, bundle C plus several hosts (see "What Pro locks").

## What the two flavors are

| | `foss` | `play` |
| --- | --- | --- |
| Routes | GitHub releases, F-Droid, IzzyOnDroid, the device harness, CI | Google Play |
| Billing library | none; `NoBilling` | Play Billing 9.1.0 and what it brings (`docs/dependency-reviews.md`) |
| `Distribution.UNLOCKED` | `false` in every published build: Pro capabilities are locked and nothing can be bought; Settings says "Free version" and where Pro comes from. A source build with `-PpaddockUnlocked=true` sets it `true` (Settings then says "Everything is unlocked"); `tools/release-build.sh` always passes `-PpaddockUnlocked=false`. The foss **debug** variant (device harness, flows, instrumentation; never published) defaults to `true` so flows written before Pro existed still reach every screen; `-PpaddockUnlocked=false` builds it locked to rehearse the gate | `false`: Pro is a purchase |
| Permissions | the five (`INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, `ACCESS_LOCAL_NETWORK`, `CAMERA`) | those five plus `com.android.vending.BILLING` |

Same application id, same code, same release key. The flag is what turns the gate on: with it `false`, the five capabilities in "What Pro locks" are locked. `tools/check-release-apk.py --flavor foss|play` (foss is the default) holds each artefact to its list; see "What the `play` APK adds" below.

## How it fits together

| Piece | Where | What it does |
| --- | --- | --- |
| `Billing` port, `StorePurchase`, `StoreResult`, `NoBilling` | `core/.../billing/Billing.kt` | The store as `core` sees it: purchases, product prices, purchase, acknowledge, consume, and `changes` (a purchase change that no call of ours was waiting for, so the owner verifies). `core` never sees the library. |
| `Entitlements` | `core/.../billing/Entitlements.kt` | The state machine. The store account's purchase list is the only source of truth. `Entitlements.hasPro(state, unlockedBuild, sellsPro)` is the one grant rule: the graph, the screens, the gate and the widget process all apply it. `saved()` never waits for a store call (store calls queue on one lock, the file has its own and holds it for one read or one compare-and-save). |
| `FileEntitlementStore` | `core/.../billing/FileEntitlementStore.kt` | The last verified answer and its time in `entitlement.json` (plain JSON through `DurableFile`, in app-private storage that backup excludes). No purchase token is ever written. An unreadable file is moved to `.corrupt` and the state is unknown until the next verification. |
| `ProGate`, `GateContext` | `core/.../billing/ProGate.kt` | Decides whether a chosen capability runs, shows the gate, or is deferred, from what the user is in the middle of (`ProGate.context`), and says why a deferral did nothing (`ProGate.deferNotice`). `ProGate.GATED` is the id set of `ProCapabilities.ALL` (M8). |
| `GateNotice` | `core/.../billing/GateNotice.kt` | The sentence a deferred tap shows and its clock; the graph's one use of a gate decision besides opening the sheet. |
| `EntitlementPresenter`, `ProCopy` | `core/.../billing/` | The sentences Settings shows, with the age of the last answer. |
| `ProSession`, `ProView`, `StoreActions`, `ProMessages` | `core/.../billing/ProSession.kt`, `ProActions.kt` | What the app does about Pro between the screens and `Entitlements`: the view the screens read, one store action at a time, every throw caught as a sentence, the verification triggers, lazy prices. See "One store action at a time". |
| `PlayBilling`, `Distribution` | `app/src/play/.../billing/` (and `app/src/foss/.../Distribution.kt`) | The library behind the port; every library call on the main thread. No call throws (a library exception, a client that cannot connect, no answer in time are `Failed`; no Play Store is `Unavailable`); every call except the purchase sheet is bounded at 30 s, the sheet at 15 minutes; updates nobody waits for are announced on `Billing.changes`. `Distribution.billing` builds the client; if building it throws, it returns a store that answers `Unavailable` to everything and still reports `sellsPro`, so the app starts and a saved purchase stays honoured. `Distribution.SELLS_PRO` (`true` in play, `false` in foss) is the same fact as a constant, for the widget process, which must not construct a client; `DistributionTest` (both flavors) and `ProGraphTest` hold the two together. |
| Settings Pro card, `ProGateSheet`, `ProGateHost` | `app/src/main/.../ui/screens/` and `PaddockRoot.kt` | Status, Restore, tips (play only, only when the store lists prices), and the gate sheet. The card shows `ProView.message`, the sheet shows `ProView.gateMessage`, and neither shows the other's. |

### Entitlement states

| Status | Meaning | Grants Pro |
| --- | --- | --- |
| `UNKNOWN` | never verified, or the saved file was unreadable | no |
| `FREE` | the store was asked; the account does not own `pro` | no |
| `PENDING` | an order the store has not finished charging | no |
| `PRO` | owned; `acknowledged` says whether the store has the receipt | yes in a build that sells Pro, acknowledged or not; never in the foss build, which cannot verify it, even if `entitlement.json` says `PRO` (a play install of the same application id can leave one behind) |
| `REVOKED` | was owned, no longer listed; reason `REFUNDED` or `UNCONFIRMED` (never acknowledged, so Play refunded it after three days) | no; data made with Pro stays readable |

A verification that cannot reach the store (`Unavailable`, `Failed`, which also covers a library exception and no answer within 30 s) changes nothing: the last state stands and Settings shows its age ("Confirmed with Google Play 3 days ago", marked stale after a week). Verification runs at app start, after a purchase, on Restore, when the store reports a change nobody was waiting for (`Billing.changes`: a pending purchase it finished while the app was open), and on a return to the foreground once this process's last attempt is 15 minutes old (`Entitlements.REVERIFY_AFTER_MILLIS`, `Entitlements.reverifyDue`), so a pending purchase that completed in the background is seen and acknowledged inside Play's three days without a store round trip on every return. Only in a build that sells Pro. An unacknowledged `pro` is acknowledged during verification, so a failed acknowledgement is retried on every start. A tip is consumed only after the thank-you is on screen, which is what lets the same tip be bought again; one whose consume failed is listed by the next verification and consumed then. Settings, the gate and the widget refresh read the saved state and never wait for a store call.

### One store action at a time

`ProSession` owns `ProView`, and `StoreActions` is its one lock (review F9). Buy, Tip and Restore are refused up front while any store action runs: nothing starts and nothing is cleared, which is what the disabled buttons promise; the app's own verifications (start, a store change) wait their turn instead, so a change that arrives during a purchase is verified afterwards, not dropped. A return to the foreground is not queued behind a running action: that action is as good as the verification. `busy` is true exactly while one action runs.

- A throw from a store call is caught per action (`storeGuarded`) and ends as "Google Play could not be used: <exception class>" with `busy` cleared, on the surface the action belongs to. The port does not throw, but the graph's scope has no exception handler, so it does not depend on that.
- Two sentences, two surfaces (F10). `ProView.message` is what the last Settings action (Restore, a tip, a verification) said and is shown on the Pro card only. `ProView.gateMessage` is what a purchase started from the gate sheet said and is shown in the sheet only; it is cleared whenever the sheet opens or closes. After a purchase the card keeps only the success. A Restore that restored Pro says so and then adds the thank-you for an unconsumed earlier tip; the tip sentence never replaces it.
- Prices are asked for the first time Settings' Pro card or the gate sheet is shown, kept for the process, and asked for again at the next showing when the answer listed none (F14). A start, including the cold process the widgets' 15-minute job starts, asks the store only what the account owns.

### What Pro locks (M8, taken by the user 2026-10-04: bundle C plus several hosts)

The one list is `ProCapabilities.ALL` in `core/.../billing/ProGate.kt`; `ProGate.GATED` and the copy are derived from it.

| Id | What is locked | Where the gate is asked | Free path |
| --- | --- | --- | --- |
| `answers.guarded` | Setting up guarded answers (Settings; the decision sheet's "Set up" is withheld, not labelled, and the sheet says why) and the Yes/No entry above an agent's output | Settings row (`onGuardedAnswers`). Without Pro the entry is not shown, the decision sheet offers no Set up and says in a notice that guarded answers are Pro and set up from Settings (decision sheet: `decisionSetUp`, `decisionNotice`), and the Output tab does not poll the host's request files, so a pending request is never answered by a purchase screen. The `PENDING_ANSWER` call from the sheet's Set up only guards a Pro-lost-mid-tap race | Type the answer in the Terminal tab or Manual input; every alert still arrives |
| `operations.start` | Spaces "Start an agent…" (form, saga, worktree) | `SpacesActions.onChoose` | Start an agent on the host, in herdr; the app still shows it |
| `operations.manage` | Spaces "Stop…" and "Delete…" for a session | `SpacesActions.onChoose` | herdr on the host; viewing and re-reading sessions stays free |
| `widgets` | The three home-screen widgets: locked ones say "Widgets are Pro" and draw no count, no machine, no rows | no tap; `PaddockWidgets.locked` reads the saved entitlement file at every redraw and applies `Entitlements.hasPro(saved, UNLOCKED, SELLS_PRO)`, so a leftover `PRO` file does not unlock widgets in a foss build; the app redraws when Pro appears or goes, and a redraw with no widget placed returns before reading any file; the 15-minute background read is skipped while locked, by the same `PaddockWidgets.locked` (a job that starts a cold process runs before the app has loaded its view) | The app itself |
| `hosts.merged` | Reserved: one attention queue across several machines. **Nothing is built**, so nothing is gated and the copy never names it | none yet | Adding a machine stays free, and a machine picker is a Free fix (vault rule) |

A locked control carries its label plus " · Pro" (`proLabel`), is still tappable, and the tap asks the gate: from an idle app it opens the sheet titled for that capability; from a pending answer, Manual input or an operation in flight it opens no sheet and runs nothing, and says why in one sentence ("Pro is offered once the operation in progress finishes."; see "What the gate does"). Free forever, by the vault's rules: agent status and herd, readable output, the Terminal tab and Manual input, snippets, alerts and their relay, adding a machine, and everything about security, accessibility and recovery. Counts of sessions, workspaces or agents never reach the gate.

A buyer who is later refunded loses the entries above and nothing else: everything they made stays readable (journal, snippets, ledger). The shortcuts and notification rules rows of bundle A are not built, so nothing is gated for them; when they are, they join `ProCapabilities.ALL` with the controls that ask the gate.

### What the gate does

`ProGate.decide(capability, hasPro, context)`: a capability that is not in `GATED`, or a holder of Pro, proceeds; a gated one chosen from `IDLE` shows the gate; chosen from `PENDING_ANSWER`, `MANUAL_INPUT` or `OPERATION_IN_FLIGHT` it is deferred: no sheet, the control keeps its Pro label and does not run. The host drops an open sheet the moment the user becomes busy. Counts of sessions, workspaces or agents never reach the decision.

**A deferral is never silent (review F6).** `ProGate.deferNotice(context)` is the one sentence for each busy context, and `AppGraph.requestCapability` hands every decision to `GateNotice` (`core/.../billing/GateNotice.kt`), whose text is `AppGraph.gateNotice`; `PaddockRoot` shows it in the notice bar (`GateNoticeBar`, a polite live region, with Dismiss) and `GateNotice` clears it after `GateNotice.SHOW_MILLIS` (8 s). A second deferral while it shows restarts the wait; a tap that opens the sheet clears it; a capability that proceeds changes nothing.

| Context | Sentence |
| --- | --- |
| `PENDING_ANSWER` | Pro is offered once the request on screen is answered. |
| `MANUAL_INPUT` | Pro is offered once Manual input is closed. |
| `OPERATION_IN_FLIGHT` | Pro is offered once the operation in progress finishes. |

**What counts as busy.** `ProGate.context(pendingAnswerOnScreen, manualInputOpen, records, nowMillis)`, asked in that order, so the sentence names what the user can see first. An operation is in flight when some journal row, on any terminal, session or saga, is `Requested` or `Sent` and was requested at most `ProGate.OPERATION_STUCK_AFTER_MILLIS` (2 minutes) ago. Every call a row stands for ends by itself inside a bound (a worktree create 60 s on the relay, starting an agent herdr's 30 s plus 20 s of slack, each after at most one short read) and a failure turns the row `Unknown` at once, so a row still `Requested` or `Sent` after two minutes is one whose call never came back (a leaked coroutine, a write that never returned). Only the next start settles it (`OperationJournal` recovery: `Sent` becomes `Unknown`), and until then it would make every locked control a dead tap with no way to buy Pro. So the gate stops counting it. The journal is **not** changed by this: the composer and the Spaces row still treat the row as running (see "What is not done"). A running operation is always younger than the bound, so the rule of AC-13.5 (no sheet over a pending answer, Manual input or an operation in flight) holds; a phone clock that jumps forward can make a running row look stuck for a moment, and one that moves back makes the gate wait.

## What the `play` APK adds over `foss`

Written from the built `play` release APK on 2026-10-04 and checked by `tools/check-release-apk.py --flavor play`; extending the list is a person reading the diff.

- Permission: `com.android.vending.BILLING`.
- `<queries>` intents: `com.android.vending.billing.InAppBillingService.BIND` and `com.google.android.apps.play.billingtestcompanion.BillingOverrideService.BIND`.
- Components, all `exported=false`: `ProxyBillingActivity`, `ProxyBillingActivityV2`, `GoogleApiActivity`, `TransportBackendDiscovery`, `JobInfoSchedulerService`, `AlarmManagerSchedulerBroadcastReceiver`.
- Dex packages under `com/android/billingclient`, `com/google/android/gms` and `com/google/android/datatransport`, only those named in the script.

## Privacy: what is known and what is not

- `foss`: unchanged. Nothing but SSH and the user's own alert provider; `tools/run-release-smoke.py` checks the sockets.
- `play`: the library talks to the Play Store app on the phone over IPC. Its datatransport stack includes a CCT destination that can send logging from this app's own process, so "nothing leaves except the store's calls" is **not yet established**. The measurement below decides the privacy exception, the privacy copy and the Data safety form; none of them is written.

### Device measurement (open: needs a phone with Google Play)

1. Install the `play` release APK from `tools/release-build.sh --flavor play --sign` on a phone signed in to Google Play.
2. With `tools/run-release-smoke.py` sampling the app uid's sockets every 0.4 s, do: cold start and idle two minutes; Settings, Restore purchase; a licence-tester tip purchase and its thank-you; a second Restore.
3. Record every remote address the uid opens beyond the SSH host. An empty list means billing adds no traffic of the app's own; any address goes into the privacy checklist, the Data safety form and the copy by name.

## What is not done

| Item | Why open |
| --- | --- |
| Play Console listing, closed track, App Signing with the existing key, Data safety form | needs the seller account (M5); outward-facing |
| Licence-tester purchase, acknowledgement shown in Play Console, restore on a second device (AC-13.3, AC-13.10 device half) | needs a Play listing and a phone |
| Device socket measurement (AC-13.7) | needs a phone with Play |
| Selling Pro: the twelve-month window and compatibility policy (M7), EU availability (M6) | the user's decisions; M8 is taken, so the gate sheet already offers "Buy Pro" in a `play` build, which must not reach a store before M7's text exists |
| A row that stays `Sent` past the gate's bound (a call that never came back) still blocks that agent's composer ("Another send to this agent is still running.") and keeps its Spaces row busy until the next start makes it `Unknown` | The gate no longer waits for such a row (F6), but the journal was not changed. The follow-up is for the journal to mark such a row `Unknown` itself once the connection that carried it is gone, as recovery does at start. It was not done here: while its call is live `Operation.run` is the only writer of the row's outcome, and an outcome written from outside would turn that call's late `Acknowledged` into an `IllegalOutcomeTransition`; it needs the call stopped first, and the journal rules in `docs/operations.md` ("On restart") would change with it |
| Several hosts (`hosts.merged`) and bundle A (shortcuts, notification rules) | not built; the id is reserved and the copy does not name it |
| GitHub repository, F-Droid and IzzyOnDroid submission | outward-facing; `LICENSE` (Apache-2.0, M1) is in the repository |
| Phone screenshots for the listing | a person's pick; none were made up |
