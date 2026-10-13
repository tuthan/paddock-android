# Privacy checklist: the Play Console declarations and the policy

Phase 16, slice 4. Drafted 2026-10-12 from the repository; nothing has been entered in the Play Console and no page is published. Decisions P3 (Data safety stance) and P12 (GitHub Pages) are proposed and unconfirmed: this draft assumes the recommendation of each, and the lines they decide say so.

Three tables. **Console answers** is one line per form question, with the exact choice, where it was verified, and its status. **Policy to form** is AC-16.4's "line for line" comparison: each section of [privacy.md](privacy.md) against the form line it must match. **What the policy's claims rest on** lists the file each factual sentence of the policy was checked against, so the policy itself carries no citations. A last list names what was left out because it could not be verified.

Status values: **draft** (drafted from the repository and verified there), **needs slice N** (cannot be answered until that slice exists), **the user's** (a decision or a Console action that is not the code's), and, once entered, **entered YYYY-MM-DD** with the screenshot's name in the evidence folder (AC-16.10). The Console's own wording is re-read at entry; the questions below are quoted from memory of the form, not from the live form.

## Console answers

### Data safety

| # | Question | Answer | Verified in | Status |
| --- | --- | --- | --- | --- |
| D1 | Does your app collect or share any of the required user data types? | **No** (nothing collected, nothing shared) | The only network code in Paddock's own sources is `app/.../ssh/SshlibConnector.kt` (SSH to the user's machine) and `core/.../ports/Sockets.kt` (LAN sockets for Find, the probe, Wake and pairing); no HTTP client in `app/src/main` or `core/src/main` (grep for `HttpURLConnection`, `java.net.URL`, `OkHttp`, `openConnection`, 2026-10-12). `docs/release.md` (the release check bans analytics, crash reporting, advertising and attribution SDKs; every URL is on an allow-list; the smoke's socket sample for `foss`). `docs/alerts.md` (the relay on the user's machine posts the alert; the app never connects to ntfy.sh) and `docs/files.md` (files go over the SSH session only). The vault's fact-check lists the exemptions (note 13). | draft for `foss`; **needs slice 7** for `play` (any address of the app's own, or the billing library's collection, changes this answer); the stance itself is P3, the user's |
| D2 | Is all of the user data collected by your app encrypted in transit? | **Yes**, if the form asks (it may skip the question when D1 is No) | SSH: `docs/ssh-library-decision.md` (algorithms). The two exceptions are not data the developer collects, and the policy states both: the pairing exchange carries a public key and a session handle in plaintext on the LAN (`docs/pairing.md`, step 4), and a Wake packet carries a hardware address unencrypted (`docs/wake.md`, What Wake sends) | the user's (a judgement about the two exceptions) |
| D3 | Do you provide a way for users to request that their data is deleted? | **Not applicable**: no account, the developer holds no data; what Paddock keeps is on the phone and goes with Remove machine or uninstall | `docs/machines.md` (Remove a machine: Forgotten, Kept); `app/src/main/AndroidManifest.xml` (`allowBackup="false"`); D1 | the user's (the form may not ask it) |
| D4 | Independent security review (optional) | **No**, not claimed | no independent review is recorded in the repository | the user's |

### App content and store settings

| # | Question | Answer | Verified in | Status |
| --- | --- | --- | --- | --- |
| C1 | Privacy policy URL | `https://tuthan.github.io/paddock-android/privacy` once GitHub Pages serves `main`'s `docs/` folder (P12). Until then the fallback `https://github.com/tuthan/paddock-android/blob/main/docs/privacy.md`, which is `PRIVACY_POLICY_URL` in `app/.../ui/screens/Settings.kt` | `git remote -v` (origin `git@github.com:tuthan/paddock-android.git`); `docs/_config.yml` and the front matter of `docs/privacy.md` (`permalink: /privacy`). The Pages address is GitHub's form for a project site and is confirmed in Settings > Pages; neither address was fetched (nothing was contacted) | the user's (enables Pages); the page is **not published before slice 7**, and the address changes in `PRIVACY_POLICY_URL` and in the host list of `tools/check-release-apk.py` when Pages exists |
| C2 | Ads: does your app contain ads? | **No** | `docs/release.md` (no advertising SDK in the dex, checked by `tools/check-release-apk.py`) | draft |
| C3 | Advertising ID: does your app use an advertising ID? | **No** | `app/src/main/AndroidManifest.xml` (six permissions, no `AD_ID`); `docs/billing.md` (the `play` APK adds only `com.android.vending.BILLING`; the permission set is exact in `tools/check-release-apk.py`) | draft; re-check on the APK Play delivers (AC-16.1) |
| C4 | App access | **All or some functionality is restricted**, with the instructions: `Tap Try a demo on the first screen; the real app needs your own machine with herdr 0.9.1 reachable over SSH, which cannot be provided` | the text is the plan's (Phase 16, slice 6); Google's rule is quoted in the vault's fact-check | draft: the demo exists (slice 3, `docs/demo.md`); enter it in the Console only once a build with it is on a track the reviewer can open |
| C5 | Account deletion: does your app allow users to create an account? | **No**; account deletion is not applicable | no account, no server and no sign-in code (D1's sources); `docs/privacy.md`, What Paddock is | draft |
| C6 | Content rating (IARC questionnaire) | Category **Utility, Productivity, Communication, or Other** (the exact name is read in the Console). Expected: **No** to every content question; digital purchases **Yes** in the `play` build (Pro, tips) | no user-to-user content or social feature, no location permission (`app/src/main/AndroidManifest.xml`); the purchases are `docs/billing.md` | the user's (the publisher answers and signs the questionnaire) |
| C7 | Target audience and content | **18 and over** | the vault's plan (note 13, section 3, item 6); the policy's Children section | the user's |
| C8 | Financial features | **None** (Pro and tips are digital goods bought through Play Billing, not a financial feature) | `docs/billing.md` | draft |
| C9 | Government apps | **No** | the app is a developer tool | draft |
| C10 | Health: does your app have any health features? | **No** | the app is a developer tool | draft |
| C11 | News apps | **No** | the app is a developer tool | draft |
| C12 | Trader status (EU Digital Services Act) | **Undecided (M6).** Either declare as a trader, which publishes the address, phone and email from the payments profile, or exclude the EU. The slice carries whichever the user picks | the vault's plan (P5, M6) | the user's |
| C13 | Store listing contact: support email (public) | `[SUPPORT EMAIL]`, the same as the policy's Contact | `docs/privacy.md`, Contact | the user's |
| C14 | Developer public details (name, address) | as the Console's payments profile shows them (P4), the same as the policy's Contact | `docs/privacy.md`, Contact | the user's |

## Policy to form

AC-16.4's comparison: after the Console is filled, go down this table with the policy open. A row fails if either side says something the other does not.

| Policy section | Form line | What must be the same on both sides |
| --- | --- | --- |
| What Paddock is | D1, C5 | no Paddock server, no account, nothing sent to the developer by Paddock's own code, against "No data collected or shared" and "no account" |
| Your machine and the SSH connection | D1, D2 | SSH goes to the user's own machine only, encrypted, against "No" and "encrypted in transit" |
| Finding, pairing and waking a machine on your network | D1, D2 | local network only, nothing to the developer; the two plaintext exchanges named in the policy are the caveat on D2 |
| The phone key and imported keys | D1, D3 | keys stay on the phone and go only to the user's machine as a public key or a sign-in; nothing at the developer to delete |
| Alerts | D1 | the relay on the user's machine posts to a server the user chose; the policy names ntfy.sh and what a server sees. That the form still says "No" is P3's judgement, the user's |
| The camera | D1 | no frame or photo is collected; a photo goes to the user's machine only, cleaned of metadata |
| Files | D1, D3 | a file goes to the user's machine only; the cache holds a copy for at most a day |
| The clipboard | D1 | nothing read except the two cases the policy names; nothing leaves the phone |
| What stays on the phone | D3 | everything on the phone, backup and device transfer off, nothing at the developer to delete |
| Permissions | C3 | the six permissions the policy lists are the manifest's six; none is the advertising ID |
| No analytics, no crash reporting | D1, C2, C3 | none, in Paddock's own code, and no ads |
| The Google Play build and its billing library | D1, C8; **slice 7** | outcome A (no address of the app's own): the paragraph says so and D1 stays "No". Outcome B (an address appears): the paragraph names Google and the purpose, D1 declares the library's collection in Google's wording, and `aboutTelemetryClaim` keeps its `play` sentence. Never ship A's text with B's measurement |
| Children | C7 | not directed at anyone under 18, against "18 and over" |
| Changes to this policy | C1 | the address is stable; the date at the top changes |
| Contact; effective date | C13, C14, C12 | the same email and public details as the Console and the listing; the date is the day the page is published |

## What the policy's claims rest on

Checked 2026-10-12. Paths are in this repository.

| Policy section | Claim | Verified in |
| --- | --- | --- |
| What Paddock is | herdr is a terminal workspace manager for coding agents; Paddock is its phone companion | `fastlane/metadata/android/en-US/full_description.txt` |
| | no server, no account; Paddock's own code sends nothing to the developer | the sources named at D1 |
| | open source, Apache License 2.0, at github.com/tuthan/paddock-android | `LICENSE`; `git remote -v` |
| SSH connection | the host key is shown and pinned only if the user trusts it; a changed key waits for the user | `docs/ssh-library-decision.md` (First trust); `RECONNECT_POLICY` in `ui/screens/Settings.kt` |
| | what travels on it: herdr commands, output, prompts and keys, files, the alert setup script | `docs/ssh-library-decision.md` (Exec deadline: "short herdr calls and relay streams"); `docs/output.md`; `docs/operations.md`; `docs/files.md`; `docs/alerts.md` (Set up in one step) |
| Local network | Find reads a banner (a TCP connect, one line); mDNS browse; no sign-in | `docs/onboarding.md` (the finder, mDNS) |
| | the lookup for a moved machine: name resolver, mDNS, host keys only with the per-machine switch, off by default, never signs in, a managed network may log it | `docs/machines.md` (Finding a machine that moved) |
| | pairing sends the phone's public key only, in plaintext, with a fingerprint comparison before the desktop approves | `docs/pairing.md` (The flow, step 4; The wire) |
| | Wake: the packet holds the hardware address; to the broadcast address, or over a VPN to a saved relay | `docs/wake.md` (What Wake sends) |
| Keys | EC P-256 in Android's Keystore, StrongBox first; sign-only; the private key is not exportable | `app/.../ssh/PhoneKey.kt` (`generateEc`: `PURPOSE_SIGN`, `secp256r1`, StrongBox first); `docs/ssh-library-decision.md` (Phone key) |
| | Add machine says where the key is held and says so when it is software only | `app/.../ui/screens/AddMachine.kt` (`backingText`) |
| | only the public key leaves the phone: copy, share, QR, pairing | `docs/enrollment.md`; `docs/pairing.md` |
| | uninstalling removes the key; revoking sooner is deleting its line from `authorized_keys` | `docs/files.md` (An APK is never opened: "forgets this phone's key"); `docs/ssh-library-decision.md` (Phone key). Android's behaviour on uninstall was not tested here |
| | an imported key and its passphrase are stored in private storage, wrapped with a non-exportable AES-256-GCM Keystore key; the passphrase is stored because connecting never prompts | `app/.../ssh/KeystoreSecretStore.kt`; `app/.../ssh/ImportedKeyStore.kt`; `docs/ssh-library-decision.md` (Imported key at rest) |
| | an imported key is used only to sign in over SSH | `AppGraph.kt` (`KeyKind.Imported` in the connect path) is the one place `importedKeys.load` is called (grep, 2026-10-12), and it feeds the SSH connector |
| Alerts | optional, per machine, set up by a script over SSH that installs a relay; the relay reads no pane text; the relay sends the alert | `docs/alerts.md` (Set up in one step; Privacy: who sees what, the host row) |
| | UnifiedPush path: a distributor app gives Paddock the address, Paddock raises a generic notification | `docs/alerts.md` (Connector mode; On receipt) |
| | ntfy path: a topic on the public or the user's own server; the topic is 24 random bytes made on the phone | `docs/alerts.md` (Set up in one step; Which server) |
| | what a server sees, both paths, plus timing and the two IP addresses | `docs/alerts.md` (Which server, the operator row; Privacy: who sees what) |
| | on the public server anyone who learns a topic can read it | `docs/alerts.md` (Which server, "Who can read the topic") |
| | Paddock's own code never connects to ntfy.sh; the relay does | `docs/release.md` (the URL allow-list bullet) |
| | ntfy.sh is run by a third party | the vault's plan (note 13, section 3, item 2: "through a third party"); not checked against ntfy's own site |
| | the lock screen shows only generic words by default; the machine's name is in the title | `docs/alerts.md` (Privacy: who sees what; Which server, the hardening list); `core/.../settings/AppSettings.kt` (`hidePromptOnLockScreen = true`) |
| Camera | asked in context, never at launch; the scanner decodes in memory, keeps nothing, is open only while its preview shows | `docs/onboarding.md` (Scan the code on the desktop); `app/.../files/AttachSources.kt` (the grant is asked in context) |
| | Take a photo asks the camera app for one picture into the private cache; the capture is deleted once its copy exists | `app/.../files/AttachSources.kt` (`TakePicture`); `app/.../files/TransferCache.kt` (`camera/`) |
| | JPEG and PNG are rewritten to what draws them plus orientation (Exif, XMP, IPTC dropped); HEIC and AVIF are re-encoded on API 28 and later; what cannot be cleaned is refused | `core/.../files/ImageMetadata.kt` (the strips are allowlists); `app/.../files/IncomingFiles.kt` (`finish`, `reencodeHeif`); `docs/files.md` (What leaves the phone) |
| Files | one file per prompt; 25 MB to the machine, 50 MB to the phone; nothing is sent until the user chooses | `docs/files.md` (Caps; Three ways in) |
| | one private copy at intake is what is sent; deleted when the prompt is accepted; the sweep removes what is left | `docs/files.md` (What leaves the phone); `app/.../routes/ComposeRoute.kt` (the snapshot goes with the acknowledged prompt); `app/.../files/TransferCache.kt` (`sweep`, one day, ten minutes' grace); `AppGraph.sweepFiles` |
| | a PDF or a text file is sent as it is | `app/.../files/IncomingFiles.kt` (`finish`: `raw.copyTo` for PDF and text) |
| | files go to the agent's folder or the drop folder, over SSH, nowhere else | `docs/files.md` (Where files land on the machine) |
| | a fetched file stays in the cache for a day; Remove deletes a machine's fetched files | `app/.../files/TransferCache.kt`; `docs/machines.md` (Remove a machine) |
| Clipboard | which copies are marked sensitive and which not; the two alert copies are cleared when Paddock next comes to the front; the mark is a request | `docs/output.md` (The sensitive mark); `docs/files.md` (Copy path is never marked) |
| | the clipboard text is read for Paste a pairing link, and after a pasted key's import to clear it | `app/.../routes/AddMachineParts.kt` (`readPairingLink`; `clearClipboardIfKey`, called at the import) |
| What stays | backup and device transfer are off | `app/src/main/AndroidManifest.xml`; `res/xml/backup_rules.xml`; `res/xml/data_extraction_rules.xml` |
| | the machines saved and what Wake reads; the host keys; settings and snippets | `docs/machines.md`; `docs/wake.md` (What is read, and when); `docs/app-structure.md` |
| | the record of sends: a hash, not the text, unless Keep prompt text; for a file, its path, size and hash | `docs/operations.md` (The journal; Files); `core/.../settings/AppSettings.kt` (`keepPromptText = false`) |
| | the log of agent state changes, no prompt or output, 30 days or 5,000 rows | `core/.../ledger/LedgerModels.kt` (`Observation`, `Retention`) |
| | each machine's alert setup kept in private storage, forgotten with the machine | `docs/alerts.md` (Privacy table, This phone) |
| | the widgets' cache: counts, the machine's name, up to two blocked agents' titles | `docs/widgets.md` (Where the data comes from: the cache file) |
| | agent output is shown and not saved | the About note in `ui/screens/Settings.kt`; `docs/widgets.md` (Where the data comes from: no prompt text, output or path in the cache) |
| | what Remove forgets and keeps | `docs/machines.md` (Remove a machine) |
| Permissions | the six, and why each | `app/src/main/AndroidManifest.xml` (the comments); `docs/release.md` (What the release APK is checked for) |
| No analytics | none in Paddock's own code; the release build is checked | `docs/release.md` (What the release APK is checked for); `tools/check-release-apk.py` |
| Billing library | placeholder only; states what it will say | `docs/billing.md` (Privacy: what is known and what is not; the measurement is open) |
| Children | not directed at anyone under 18 | C7, the publisher's declaration |
| Changes | earlier versions in git history | git |

## Left out of the policy because it could not be verified

- **What the billing library does on the network, and what Google collects through it.** It needs the Play measurement (slice 7). The policy's paragraph is a placeholder.
- **That Paddock never sees payment details.** Nothing in the repository measures it; the vault's P3 relies on Google's exemption for payment services.
- **Anything about the demo machine in the policy beyond one sentence.** The demo (slice 3) is built and `DemoSessionTest` shows it has no network or file code; the policy says only that it runs inside the app, opens no connection, never saves its machine, and deletes the few records it makes (a prompt's fingerprint, the log of its agents' state changes) on Remove or at the next start. The harness's `check-demo.sh` (AC-16.5) ran the demo on an emulator, debug build, with the socket sampler, and saw no endpoint; the same run on the signed release build and on a phone is not yet done.
- **ntfy.sh's own handling of what it receives.** The policy says only that it is run by a third party and what Paddock's relay sends it.
- **That the repository is public**, which the GitHub blob address and Pages need. No request was made.
- **The live wording of the Console's questions and the IARC category names.** They are read at entry.
- **Android's Keystore guarantees beyond what the app asks for** (for example, that StrongBox exists on a given phone). The policy says "where the phone has it" and that Paddock reports where the key is held.
