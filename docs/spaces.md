# Spaces: sessions, starting agents, rename and focus (Phase 09)

What the Spaces tab does against herdr 0.9.1, what was measured to design it, and what is held. The operation journal it builds on is in `operations.md`; button kinds are in `buttons.md`.

## Sessions

The list is `herdr session list --json` over SSH, read when the tab opens, when the connection changes and after every operation on it. It is that moment's read, not a live view, and says so ("List read at 14:02"). The catalogue is host-wide: it works before a session is chosen and after the watched one stops.

| Control | Offered when | What it sends | Journal row |
| --- | --- | --- | --- |
| Stop… | the session is running | `herdr session stop NAME --json` | `SessionStop`, subject `session:NAME` |
| Delete… | the session is stopped and is not `default` | `herdr session delete NAME --json` | `SessionDelete`, subject `session:NAME` |
| Restart | never (disabled, with its reason on screen) | nothing | none |
| Re-read | the session's last operation ended `Unknown` | the list read again | none |

Both dialogs name the exact session and the machine, show them again as facts, and open with Cancel focused. `default` is refused before any call, in the UI and again in `SessionRules.deleteRefusal`: it is the session a plain `herdr` command on that machine uses. No process is ever signalled; a wedged server is reported with the CLI's own error.

Restart stays off: a stopped session has no server, and starting one headlessly needs a supervised bootstrap, which is a separate design (see "Held" below).

### Saved layout

A stopped session shows what herdr saved: `Saved layout: repo, notes · file dated 14:02`, or `saved contents unavailable`. One bounded command reads `<session_dir>/session.json` (first line the mtime, then at most 256 KiB + 1 byte); only a numeric `"version": 3` is read; names are the basenames of each workspace's `identity_cwd` with control characters removed, at most 64 characters and 50 workspaces. A missing file, another version, garbage or an oversized file is "unavailable". The result is for showing and is never an input to a mutation. (`default`, which has no saved file until it has run, reads "unavailable".)

## Starting an agent

`StartAgentSaga` runs one request, one step at a time, each step a journal row of its own, and persists what herdr returned (`SagaStore`, `files/sagas.json`) before the next step begins.

1. **Availability.** `command -v` of the kind's executable over SSH (the user's usual bin directories added to a non-interactive PATH, then the login shell's PATH for bash, zsh, fish, sh, dash and ksh). Missing: nothing is created, the card offers **Start anyway** (the user's call; it skips only this check).
2. **Folder** (only when the user chose one). The host is asked what the typed path is (`RelaySagaHost.resolveFolder`, below). Not a folder there: nothing is created and the card says `/that/path is not a folder on the host` (`folder_missing`). Otherwise the real path it answered is what the tab is created in and what the verify step expects.
3. **Place.** With a worktree: relay `worktree.create` (branch in the request's JSON, never in argv; `trust_repository` only after a dialog naming the repository). Without: relay `tab.create`, with `cwd` when a folder was chosen. Workspace, tab, pane, terminal id, cwd and the worktree path are saved. A tab that herdr opened in any folder but the one asked for stops the saga here (`wrong_cwd`), with the tab's ids on the card.
4. **Verify.** `pane.get` and `pane.process_info`, retried for up to 10 s while the shell starts: the pane exists, is the same terminal, still in the tab and workspace that were created, has no agent, has the shell alone in the foreground, has a revision of at least 1, and its cwd is the worktree path (or the workspace's). A mismatch stops the saga before `agent start`.
5. **Start.** `herdr agent start NAME --kind K --pane P --timeout 30000` over SSH. Not the relay: see the facts below.
6. **First prompt** (optional). Only when the Phase 06 readiness predicate holds for the new pane, through the Phase 06 prompt path. The text is never saved; the saga keeps its hash.

A saga is never replayed. The only retry is the user's explicit new name after `agent_name_taken`, which runs one new `agent start` in the pane that is still there.

### The folder (2026-10-07, the user's request)

The form's **Folder** section (shown when "Start in a new worktree" is off) is **The workspace's folder** (the default, exactly what it did before) or **Another folder on the machine**: a path field (`/srv/app`, `~/projects/api`; a leading `~` is the machine's home folder) and, under it, up to six folders panes are already open in on that machine (read from the installed snapshot), one tap to fill the field. A worktree has a folder of its own, so the two never go together (`SagaRules.problem` refuses both, and the form drops the choice when the worktree switch is on). The tab is a **new tab in the workspace chosen above**; it does not make a new workspace.

Why the host is asked first: **herdr 0.9.1 never refuses a `cwd`.** Measured with `tab.create`: a missing folder, a file, a relative path and `~` all succeed and open the pane in herdr's own default folder; a symlink, a trailing slash and `..` are resolved to the real path. So a typo would have started an agent in the wrong place with no error. `resolveFolder` runs `/bin/sh -c` with the path on **stdin** (free text is never an argument) and prints `pwd -P` of it after expanding `~`; anything not absolute is refused. The saga compares herdr's own answer for the new pane's cwd with that real path, and stops before `agent start` when they differ. `ResolveFolderTest` runs the script under a real `sh` against real folders (symlink, space, a name with `'` and `\`, `~`, a file, a missing one).

Not run: a Mac (`pwd -P` there reports `/private/tmp` for `/tmp`, which is also what herdr's own cwd is expected to be; the comparison is the same two strings, but no Mac has been asked).

### A start that stopped before it created anything

An executable or a folder that is not there creates nothing, so there is nothing to close and the old rule (a card only when something was created) showed **no card at all**: the progress panel went away and the tap looked like it did nothing. The card for the attempt the user just made now shows (`SagaRecord.showsCard`): why it stopped, "Nothing was created", **Start anyway** for a missing executable, **Dismiss**. Older failures of this kind stay in Activity and do not come back as cards.

### The recovery card

A saga that stops after creating something becomes a card on the Spaces tab (and a row in Activity) that lists every id it created as its own fact: workspace, tab, pane, worktree path. Actions: **Open the pane**, **Choose another name** (name taken), **Trust this repository…** (herdr asked), **Start anyway** (executable missing), **Close the new workspace/tab/pane…** (a confirmed operation of its own; the dialog opens on Cancel; the worktree folder and its branch stay on the host and the card says so) and **Leave it**. Nothing on a card runs without a tap. After a restart of the app, a saga left Running is shown as Failed (`app_ended`) with the ids it had saved.

Failure codes (`SagaFailure`): `executable_missing`, `folder_missing`, `host_unreachable`, `place_refused`, `place_not_sent`, `place_unknown`, `place_blocked`, `ids_not_saved`, `pane_moved`, `pane_not_ready`, `pane_busy`, `wrong_cwd`, `name_taken`, `start_refused`, `start_not_sent`, `start_unknown`, `start_misdelivered`, `app_ended`, `saga_unwritable`.

## Rename and focus

Long-press on a herd row: **Rename agent…** (`agent rename`; a name is 1 to 40 letters, digits, dots, dashes, underscores; empty clears it), **Show its workspace on the desktop** (`workspace focus`), **Show its tab on the desktop** (`tab focus`). Each is a journal row. Focus resolves the id from the current snapshot and is `Stale` if it is gone. None changes what the agent is doing.

## Facts measured on herdr 0.9.1 (fixtures in `fixtures/herdr-0.9.1/`, captured by `tools/capture-phase09-fixtures.sh`)

- **Relay `agent.start` does not wait.** It answers at once with `launch_pending` and an unknown status whatever `timeout_ms` says; the agent is not yet detected, so `agent.get` and rename refuse it for a moment. The CLI's `agent start` waits until the agent is detected and ready, or fails with `timeout`, `agent_name_taken` or `agent_pane_busy`, so the saga uses the CLI.
- **A pane keeps its id across `pane move`**; the old tab closes. A moved pane is therefore detected by its tab or workspace changing (`pane_moved`); only the pane is then offered for closing.
- **`worktree create` returns everything**: workspace, tab, root pane, terminal, cwd and `worktree.path`; the pane's cwd is that path.
- **`session delete` of a name that does not exist reports `deleted: true`**, and of a running session fails with herdr's own refusal; a stopped session's `session.json` is version 3 with `workspaces[].identity_cwd` (the saved file also lists a worktree workspace).
- **herdr reads `XDG_CONFIG_HOME` before `HOME`**, and a pane exports `HERDR_SOCKET_PATH`: a test that wants its own sessions must override both, or it lists and writes the developer's.
- **An agent that is never detected** (the `kiro` stand-in, a program herdr does not recognise as an agent) ends in `timeout` after the requested wait; `cline` stand-ins behaved differently from run to run and are not used.

## Held

The multi-host merge and the hub-mode spike (slices 6 and 7, AC-09.5, AC-09.6) are held by the vault note of 2026-10-02 until the herdr conversation says whether a client API or an official multi-machine client is coming. Nothing here assumes several hosts, but the host-keyed journal and saga rows already carry the host id.

## Tests

- `core`: `SessionOperationsTest`, `SavedLayoutTest`, `StartAgentSagaTest` (injected failure at every step, no repeated mutation, a folder resolved first and the tab made there, a missing folder creates nothing, a tab herdr opened elsewhere stops the saga, the card for an attempt that created nothing), `ResolveFolderTest` (the script under a real shell), `RenameAndFocusTest`, `Phase09FixturesTest` (every fixture is parsed by the production code), `HerdrCliTest`.
- `core` live (`PADDOCK_TEST_SOCKET`): `Phase09LiveTest`, each test on a herdr server of its own in an isolated HOME: stop, delete, saved layout, `default` refused, a tab saga, a worktree saga (cwd equals the worktree path; closing leaves the checkout), a chosen folder through `~` and a symlink (the pane's cwd is the real folder), a missing folder or a file creates no tab, a duplicate name and its retry, a missing executable, a start that times out, a moved pane, a refused worktree branch, rename and focus.
- `app` Compose: `SpacesTest` (dialogs open on Cancel and name session and machine, `default` has no Delete, Restart is disabled with its reason, saved layout and its absence, a recovery card lists its ids and closing asks first, start-form validation, the folder choice: a full path is required, a suggestion fills it with one tap, a worktree drops it, audits dark and light; the long-press dialogs).
- Device end to end: `paddock-harness/run-spaces-e2e.sh` (Spaces list, delete and stop with their dialogs, a tab agent, a worktree agent, a duplicate name's card and its retry, closing through the card, rename and focus from a long-press, Activity) against a herdr and sshd in an isolated HOME; the host's state is checked at every step.
