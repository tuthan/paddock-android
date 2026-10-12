# Files between the phone and the machine

How a file gets to an agent's machine and back (Phase 15, Part B). Free, with no capability id: every transfer works with Pro locked, apart from choosing another machine in the share chooser, which is the existing switch. It adds no permission and no dependency, and opens no socket but the SSH one the machine's session already uses (`tools/check-release-apk.py` is unchanged on both flavors; `run-files-e2e.sh` samples the app's sockets during a transfer). `docs/output.md` has Part A, copying from the Output tab.

## Three ways in, one way back

| What | Where it starts | Where it goes |
| --- | --- | --- |
| **Attach** on the composer | a photo (picker), a file (document picker) or the camera | `<the agent's folder>/.paddock/inbox/<time>-<name>`, and the prompt names it on a line of its own |
| **Share to Paddock** from another app | an image, a PDF, text or a URL shared to Paddock | an agent's composer (a draft), a folder's inbox, or the machine's drop folder |
| **Get** | a path the agent printed (a link in the Output tab), or *Files from this agent* | the app's cache, then Open, Share or Save |

Nothing is sent until the person chooses where; nothing on the machine is browsed. The file is always one the person picked, and a path is always one the machine's own script has judged.

## Where files land on the machine

- **The inbox.** `<folder>/.paddock/inbox/`, created by the put script. When the script creates `.paddock` it writes `.paddock/.gitignore` holding `*`, so `git status` stays clean in a checkout's root, a subfolder, a linked worktree and a submodule with no git process run and nothing under `.git/` touched. The files are `0600` in `0700` folders, the name is the time, then the sender's name reduced to letters, digits, `.`, `_` and `-` (`2026-10-11-143205-screenshot.png`).
- **The drop folder.** *Files from this phone go to* on a machine's page, `~/Paddock` by default, per machine. The machine resolves the value (`~` is the login user's home) and judges it: not home itself, not a hidden folder, not a link, inside home. The page asks before it saves ("`~/Paddock` is `/home/jdoe/Paddock` on this machine and will be created when the first file arrives") and lists the newest files under it ("List read at 14:02:11"). Only the watched machine, while it is connected, can be asked. A drop never overwrites: a name that is taken is retried as `name-2`, `name-3`, up to five tries, then the machine's sentence.
- **Instruction files are refused before a byte leaves.** A drop named `CLAUDE.md`, `CLAUDE.local.md`, `AGENTS.md`, `GEMINI.md`, `codex.md`, `.mcp.json`, `opencode.json` or `settings.json` (any case, any trailing dot or space the reduction removes) is never sent: an agent that reads a project's instruction files must not be handed one by a share. The phone refuses; no channel is opened and no journal row is written.
- **Both write scripts** (`TransferScripts.PUT`, `DROP`) set `LC_ALL=C`, `umask 077` and `set -C`, check their header against an allowlist before touching a file, make the part with `mktemp`, compare `wc -c` as a number and publish with `link` (never `ln`, which would put a file inside a directory found at the name). A part older than an hour is swept by the next run. Each refusal has its own exit code and sentence (`TransferRefusal`).

## The journal

Every transfer is an operation row, written ahead of the first byte:

- **`Attachment`**: path, size and SHA-256, `Sent` before the header, then `Acknowledged`, `Rejected` (a refusal) or `Unknown` (a cut). An `Unknown` attachment holds only the prompt that would name it: **Read the inbox** looks for the name on the machine and settles the row; nothing is resent by itself. A file sent from a share with no agent is the host profile's own subject (`drop:<profile>`), has no terminal and holds nothing: a cut one never blocks a later send.
- **`Fetch`**: written when a whole file has arrived, with the real path, the size and the SHA-256 of what was received.
- The prompt row carries the hash of the user's own words (an attachment alone may be sent), because what herdr received also holds the phrase line.

## Caps, deadlines, and what a transfer asks of the phone

One file per prompt. 25 MB to the machine, 50 MB to the phone, the same for everyone: they are safety bounds, not a tier. The phone refuses before it opens a channel and the script refuses before it reads a byte. The deadline is a 20 s floor plus twice the time the size takes at a deliberately slow rate (128 KB/s up, 256 KB/s down); the rates are provisional until spike S2 measures them on a phone. The transfer sheet keeps the screen on and says to keep Paddock open. A picker, the camera or a Save chooser Paddock opened holds the connection for at most two minutes (`TransferHold`); the app lock keeps its rule and locks on return if its timeout passed.

## What leaves the phone

- **One snapshot at intake.** A pick or a share is copied once into the app's cache (`incoming/<id>/data`, within the cap) and the sniff, the hash and the send all use that copy: a sender that changes its file afterwards changes nothing that leaves, and the draft holds only the snapshot's id.
- **Images lose their metadata.** A JPEG or PNG keeps only what draws it, plus orientation, and ends where the primary image ends; a HEIC or AVIF is re-encoded to JPEG on API 28 and later and refused below; what the phone cannot decode is refused, not sent raw.
- **The type is the sniff's, never the sender's claim:** image, PDF or text; anything else is refused and leaves no snapshot.
- **Shared text is cleaned** of what the Output tab drops (tag characters, bidirectional controls, zero-width characters, escape sequences, unpaired surrogates) and keeps line breaks and tabs; the draft says how many characters went. A text over 20,000 characters is refused. The same rule applies to a path offered by *Copy path* and *Use in a prompt*.

## Share to Paddock

`MainActivity` takes `ACTION_SEND` for `image/*`, `application/pdf` and `text/*` and nothing else (no `SEND_MULTIPLE`, no new component). `ShareIntake` reads the intent once and only the parts it understands: `EXTRA_STREAM` typed inside a `try` (a malformed bundle is a refusal), `EXTRA_TEXT` measured as the `CharSequence` it came as before anything scans it, then SEC-1 and SEC-2 for a stream. The intent is replaced with an empty one after it is read.

One item waits at a time in `ShareInbox`; a new share replaces it and its snapshot goes with it. With the app lock up, the item waits and the chooser opens after the unlock; with no machine saved it is dropped with a sentence. The chooser (`ShareChooser`, `ShareRoute`) is the item, the machine, then *Where?*:

- **The machine.** The watched machine and the chosen machine's free return; any other is the Pro switch, shown with the lock glyph and the tag, and a tap runs the same `switchMachine` Home's chips run.
- **Where?** The agents that can take a prompt now (the others disabled with their reason), the folders herdr reports (distinct absolute pane folders), and the drop folder. An agent opens the composer with the words and the attachment already in its draft; a folder or the drop folder sends through `ShareSender` with the transfer sheet and no prompt. A text goes only to an agent.
- **The result** names the path on the machine, with **Copy path** (never marked sensitive: a place is not a secret) and **Use in a prompt** (the path becomes the waiting text, so the chooser then lists agents).

## Get

- **Links.** A path in the agent's output that resolves under the agent's own folder is a link. A tap asks the machine to stat it: the machine resolves the real path, refuses a link, a non-regular file, a file over the cap or a path outside the folder, and answers size, time and real path. The sheet shows name, size, age and folder with **Get** and Cancel.
- **The read.** One read, capped and with a size-scaled deadline, into `cache/transfers/<machine>/<id>/<name>`; the name on the phone is Paddock's own reduction (SEC-6), the machine's name is only shown, and through `SafeText`. A count that is not the stat's size fails and deletes the part. A rotation does not cancel a read (`GetController` is kept in a `Retained` holder).
- **Then.** **Open** only for images, PDF and plain text, through the non-exported `TransferFileProvider` with a read-only grant, **Share** (a read-only `ACTION_SEND` of one `content://` URI) and **Save** (the system's create-document chooser). The cache is swept at start and after a day, and removing a machine deletes its fetched files.
- **An APK is never opened by Paddock** (X5), on either flavor: it is offered Share and Save, and Paddock holds no install permission. The sheet shows the package, version and signer's SHA-256 read from the file, says an unsigned package will be refused by the installer, and says that Paddock's own package signed by another key means uninstalling Paddock first, which forgets this phone's key. The person installs from Files, where the system asks.
- **Files from this agent** lists the regular files of `.paddock/outbox/` (a link is not listed), newest first, "List read at 14:02:11". Paddock only reads it: nothing in it is deleted, and nothing is told to the agent.

## Security controls

| | Control | Where | Tests |
| --- | --- | --- | --- |
| SEC-1 | An incoming URI must be `content://` of another app: never `file://`, never one of Paddock's authorities, an unresolvable authority is another app's and fails as unreadable | `UriPolicy`, `IncomingFiles.admits` | `UriPolicyTest`, `IntakeRefusalTest`, `ShareIntakeTest` |
| SEC-2 | One private snapshot, sniffed, cleaned, hashed | `IncomingFiles` | `IncomingFilesTest` |
| SEC-3 | Hidden characters never enter a draft | `SafeText.cleanPrompt`, `SharedTexts` | `SafeTextTest`, `PromptTextTest`, `ShareIntakeTest` |
| SEC-4 | The scripts are hardened and each check fails its test when removed | `TransferScripts` | `TransferScriptsTest` (real `sh`, real git) |
| SEC-5 | The provider is not exported and names only `camera/` and `transfers/`; grants are read-only | `TransferFileProvider`, `Handoff` | `HandoffTest`, `tools/check-release-apk.py` |
| SEC-6 | What an agent made is untrusted: Open only for allowlisted types, names cleaned for display and for the cache | `OpenableTypes`, `FileNames`, `GetController` | `GetControllerTest`, `CachedNameTest` |
| SEC-7 | No file name, path or content in a log; Remove forgets fetched files | everywhere | `run-files-e2e.sh` searches logcat for the run's marker and for `CLAUDE.md` |
| SEC-8 | Bounds: one pending share, caps, deadlines, list and read limits | `ShareInbox`, `TransferLimits`, `FileTransfers` | `ShareInboxTest`, `FileTransfersTest` |

## Where it lives

`core/.../files/` (`FileTransfers`, `TransferScripts`, `TransferLimits`, `FileNames`, `PathTokens`, `DropFolderCheck`, `ShareInbox`, `ShareDraft`, `Snapshot`), `core/.../ops/OperationJournal.kt` (`Attachment`, `Fetch`, `Subject.drop`), `core/.../lifecycle/TransferHold.kt`; `app/.../files/` (`IncomingFiles`, `ShareIntake`, `ShareSender`, `GetController`, `Handoff`, `ApkCheck`, `TransferCache`, `TransferFileProvider`), `app/.../routes/` (`ShareRoute`, `GetRoute`, `OutputRoute`, `ComposeRoute`, `MachinesRoute`), `app/.../ui/screens/` (`ShareChooser`, `GetSheet`, `Attach`, `DropFolder`). The flows are run by `paddock-harness/run-files-e2e.sh`.
