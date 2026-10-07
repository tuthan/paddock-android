# Guarded native answers (Phase 08)

A native **Yes** or **No** on the phone for one kind of question: a permission prompt of **Claude Code, Codex or opencode** (Codex and opencode since 2026-10-07, see "Codex and opencode" below). Everything else an agent asks stays manual, in the terminal. An answer is bound to exactly one request, by an id the hook mints, and Paddock never sends a key to give it.

## What it is, and what it is not

- **Only permission prompts of those three agents, never a question.** Claude Code's `AskUserQuestion` (a multiple-choice question) and `ExitPlanMode` (plan approval) also pass through a `PermissionRequest` hook, and opencode has a `question` tool (its pane is `blocked` for it too, with no `permission.asked`). A hook's `allow` cannot choose an option, so a Yes would be consumed and change nothing. **Seen on a real machine (2026-10-07):** a test question with the options "Allow" and "Deny" got a Yes on the phone, the hook printed `allow`, and the question stayed on the desktop. The hook (version 4) never publishes those two tools, whatever `allowed_tools` says (`QUESTIONS`), and the phone refuses them as "not a permission" and offers no entry (`AnswerRules.isQuestion`), for a machine that still runs an older hook. Answering a question from the phone would be a different feature (options, not Yes/No). A `PermissionRequest` hook on the machine publishes the request (for opencode, a plugin hands its event to the same hook); the phone answers it through a second script. No other agent, no other prompt, no free text.
- **One request, one answer.** The answer carries the request id from the very file the phone read, and a tap is bound to the id the screen drew: if the sheet has moved to another request by then, nothing is sent. While the request on screen is still live, a newer one is only offered (the user taps to review it); when the one on screen has expired, the sheet moves to the new one, and Yes and No stay off for 1.5 seconds so a finger on its way to the old content cannot land on the new.
- **Never "always allow".** The hook's output has one shape, `{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"|"deny"}}}`: no `updatedInput`, no permission rule.
- **No key is ever sent.** The answering code holds no herdr client (a unit test pins that). The phone writes one decision file over SSH; the hook, running inside Claude Code's own call, hands it back.
- **The desktop is never blocked** (Claude Code, opencode). Both show their own dialog while the hook waits, so whoever answers first wins. **Codex is the exception**: it shows nothing until every hook has returned, so the window is a delay of its prompt, and it has a window of its own (below). A failure of any kind (no config, a timeout, a kill, an exception) prints nothing and exits 0, which leaves the normal dialog.
- **Off until set up, on the machine, by you.** Paddock installs three pinned files after a confirmation (the hook, the writer and the opencode plugin, all in `~/.local/share/paddock/`). It does not register the hook, write the hook's configuration or touch `~/.claude/settings.json`, `~/.codex/hooks.json` or opencode's folders; it shows the texts to paste, one registration per agent.

## Setup

In the app: **Settings > Machines > the machine > Guarded answers** (**Set up guarded answers…**). Guarded answers are per machine, since the two scripts and the hook registration live on one machine; since 2026-10-07 they are no longer a row of Settings (Settings keeps one line that says where they went). The row is offered only on the page of the machine Paddock is watching, because the setup reads and writes that machine through the live connection; another machine's page says to watch it first.

1. The screen shows the SHA-256 of the three files (`paddock-claude-permission-hook.py`, `paddock-decide.py`, `paddock-opencode-permission.js`), what is on the host now (missing, current, or a different file that is never run), and installs only those files, owner-only, after a confirmation that repeats paths and hashes.
2. Paste the **configuration command** on the machine. It writes `~/.config/paddock/hook.toml` (`window_seconds`, 1 to 300, for Claude Code and opencode; `codex_window_seconds`, 0 to 300, for Codex; the optional `allowed_tools` list) only if none exists, mode 600. The hook ignores a configuration that is not yours or is group- or world-writable. No file, no window, or `window_seconds = 0` means the hook does nothing for any agent; `codex_window_seconds = 0` turns off Codex only. Without a `codex_window_seconds` line Codex gets the smaller of 20 and `window_seconds`.
3. Register the hook for each agent you use (the screen has one chip per agent and shows one registration at a time; **Copy the registration** copies the one shown):
   - **Claude Code:** merge the shown JSON under `hooks.PermissionRequest` in `~/.claude/settings.json`. The `timeout` is the window plus five seconds. Start a new session: Claude Code reads its hook settings at session start.
   - **Codex:** merge the shown JSON under `hooks.PermissionRequest` in `~/.codex/hooks.json`. The command is the same hook with `--agent codex`; the `timeout` is Codex's window plus ten seconds. Codex asks you to trust a new hook once (start Codex and choose Trust, or `/hooks` then `t`); a change to the command or the timeout asks again.
   - **opencode:** paste the shown two lines: `mkdir -p ~/.config/opencode/plugins` and `ln -sf '<installed plugin>' ~/.config/opencode/plugins/paddock-opencode-permission.js`. The plugin stays the pinned file Paddock installed (a link, not a copy), so a reinstall updates it. Restart opencode: plugins load at start.

Python 3.11 or newer on the machine (the hook reads TOML with `tomllib`); standard library only.

## How a request travels

```
Claude Code ──stdin──▶ hook ──pending.json──▶ $XDG_RUNTIME_DIR/paddock/<herdr session>/<pane>/
                         │                                   ▲ list (phone, SSH, read only)
                         │ waits ≤ window                    │ decide (phone, SSH): claim, then decision.json
                         ◀──────────── decision ─────────────┘
Claude Code ◀─stdout─ hook   {"behavior": "allow" | "deny"}   (or nothing: the desktop dialog decides)
```

One file per request, its state in its name, every move a `rename(2)` so two processes can never both win:

| State | Meaning |
| --- | --- |
| `pending` | The hook published it (temp file, then rename). Carries the id, the Claude session, the tool and its input, the permission mode, the host-clock deadline and the hook's pid. |
| `claimed` | `decide` renamed `pending` to `claimed`. `ENOENT` means somebody else, or the hook's expiry, got there first. |
| `decision` | `decide`'s answer, beside `claimed`, temp file then rename. |
| `consumed` | The hook read the decision and printed it. The last thing the host can see. |
| `expired` | The window lapsed, the desktop answered first, a newer request of the same Claude session was published, or the hook ended. The desktop handles it. |

Directories are 0700 and files 0600 on a tmpfs, so they vanish at logout; `decide` deletes files older than an hour. `decide` exits 3 (already claimed, expired, consumed or unknown), 4 (expired, or its hook has ended), 5 (input truncated), 6 (malformed or another pane or session), 7 (I/O), 2 (usage); the wrapper the phone runs exits 90 when the file on the host is not the pinned script.

### A newer request expires the older one

Claude Code makes its next tool call only after the dialog for the one before was resolved. When the desktop answers a request and the next prompt follows at once, herdr's status goes `blocked`, `working`, `blocked`: the older hook never sees two reads away from `blocked`, keeps waiting out its window, and its request would come back as the newest pending one once the new one was answered. So a hook that publishes a request first renames every older **pending** request of the same Claude session in the same pane to `expired` (one rename, the same one the older hook's own expiry makes; a request the phone already claimed is not touched), and the older hook, seeing its file gone, leaves at once with no output. A request of another Claude session is left alone. Found by the scenario suite against real Claude Code: the rewritten `a_new_request_replaces_the_old` failed in one of its three runs, and every run has passed since this rule.

### What decides whether Yes and No are on

`AnswerRules.answerability`, from one listing, nothing from herdr: the request was read whole, is not truncated (the hook keeps 256 KiB of input and cuts the rest), its hook has not ended (the writer checks the pid in the request against `/proc`; the shipped hook always writes one, and a request without a usable pid reads as "unknown", which is not treated as ended), no other live Claude session shares the pane, and more time is left than one round trip plus 500 ms by the **host's** clock (the phone's clock is never compared with the host's). Otherwise both buttons are off and one sentence says which condition failed. For 1.5 seconds after a request first appears on the sheet (opening it, reviewing a newer one, or the sheet moving on from an expired one) both are off as well, with the sentence "This request just appeared…". Before the write the phone re-lists and refuses unless the request on screen is still the newest pending one, and refuses a tap that was drawn for a different request than the one now on the sheet.

### Facts the design rests on (checked 2026-10-03, Claude Code and herdr 0.9.1)

- The hook gets no tool-use id, so the id is the hook's own. Exit code 2 is ignored for this event. A hook that times out leaves the normal dialog. The dialog is shown while the hook waits. An answer at the desktop does **not** signal the hook, and a decision printed afterwards is ignored.
- herdr detects `blocked` from the screen by itself and **ignores `pane report-agent` for a pane whose agent it detects**, so for Claude Code and opencode the hook reports nothing; Phase 07's alerts already fire on herdr's own `blocked` (Codex is the exception, below: no early alert, and the hook reports nothing for it either). The hook reads `agent.get` over herdr's socket (one connection per question: herdr closes it after one reply) to notice a desktop answer: it has seen `blocked`, then two reads of `working`, `idle` or `done`.
- `HERDR_ENV`, `HERDR_PANE_ID`, `HERDR_SOCKET_PATH` are set in a pane's processes; `HERDR_SESSION` only in a named session, so the session directory component comes from the socket path (`.../sessions/<name>/herdr.sock`, else `default`).

## Codex and opencode (2026-10-07, the user's request)

Measured by running the real agents (Codex 0.160.0, opencode 1.18.34, herdr 0.9.1) in the disposable `paddock-test` session. Everything below that says "measured" was observed, not read from documentation.

### opencode: a fit, with the hook and the writer unchanged

- opencode's `permission.ask` plugin hook is never called in 1.18.34 (registered, zero calls), and the SDK's `permission.updated` event never fires. The working signals are the `permission.asked` and `permission.replied` events, which a plugin's `event` handler receives **concurrently with the dialog** (the dialog was up 0.05 s after the event, before the handler returned). So the desktop is not blocked, exactly as with Claude Code.
- A plugin answers with `POST /permission/{id}/reply` through its client (`{reply: "once" | "reject", message?}`; HTTP 200 and the dialog closes 0.04 s later). Desktop first: the reply gets HTTP 404 (`PermissionNotFoundError`) and nothing runs; the plugin also sees `permission.replied` from the desktop and stops the hook.
- `host/paddock-opencode-permission.js` (pinned, ~60 lines) turns each `permission.asked` into the Claude-style `PermissionRequest` and runs `python3 ~/.local/share/paddock/paddock-claude-permission-hook.py --agent opencode`. `Yes` replies `once`, `No` replies `reject` with "Denied from the Paddock phone"; never `always`. Nothing in the hook or the writer had to change for opencode.
- The payload differs from the SDK types: the kind is `permission` (`bash`, `edit`, `external_directory`), the command is `metadata.command`, an edit carries `metadata.filepath` and `metadata.diff` (cut at 8,000 characters by the plugin). A command that touches a path outside the project raises **two** requests, `external_directory` then `bash`, each its own dialog.
- Plugin discovery: `~/.config/opencode/plugins/*.js` (global) or `<project>/.opencode/plugins/`. `opencode --pure` disables external plugins.
- The plugin runs inside the opencode process, so `HERDR_PANE_ID` is the pane's own. Exception: `opencode serve` plus `opencode attach` (one shared server): the plugin then runs in the serve process and sees the serve pane's id, so a request would be published under the wrong pane. Not handled.
- Not verified: opencode's V2 plugin API, subagent sessions (each raises its own `sessionID`, which the "two sessions in one pane" rule would read as ambiguous), the plugin under `serve`/`attach`.

### Codex: possible, but not the same thing

- **Codex blocks while the hook runs (measured).** Its approval dialog is not drawn until every hook has returned (0.13 s after the last one), so a hook that waits N seconds delays the desktop prompt by N seconds. "Whoever answers first wins" is false for Codex: the phone wins, or the desktop waits the whole window. That is why Codex has its own, short window (`codex_window_seconds`, default 20) and why the screen says so. Esc during the wait does nothing; Ctrl+C interrupts the turn but does not stop the hook.
- **No early alert, and the hook writes nothing to herdr (measured, then reverted).** herdr shows a Codex pane as `working` for the whole wait and `blocked` only when Codex's own dialog finally appears, after the window. A first version had the hook report `blocked` (`pane.report_agent`) for the pane while it waited and release it at the end, so Phase 07's alert would not be held back. Run against a real Codex, that failed three ways, so it was removed (hook version 3):
  - **Dropped under herdr's own Codex integration.** Its SessionStart reporter stores an `agent_session` (`source: herdr:codex`) for the pane; while one is stored `pane.report_agent` answers `ok` and the status stays `working`. Three source names, with and without `seq`, and `pane.clear_agent_authority` first changed nothing. It worked only on a pane with no stored session (before its first turn). Anyone with herdr's Codex integration would have had the phone path and no `blocked`.
  - **SIGKILL leaves `blocked` stuck.** A hook killed with SIGKILL cannot release; the status stayed `blocked` for at least 203 s and 504 s in two runs, cleared only by `herdr pane release-agent <pane> --source paddock-hook --agent codex` or by the next hook on that pane. While stuck, `herdr agent prompt` fails with `agent_blocked`, which would have hit any prompt Paddock sent to that pane, and `agent explain` does not show it.
  - **A phantom agent** if the report lands on a pane where herdr detects no Codex.
  So for Codex the phone reads the request files whatever herdr says about the agent (`AnswerRules.readsAtFullPace`: a real machine had two Codex requests published and never offered to the phone while it waited on the agent's status), the Output tab's entry is shown whenever a live request exists, and there is **no "needs you" alert for a Codex permission request**: Phase 07 alerts only on herdr's `blocked`, which for Codex arrives when the desktop prompt appears, up to the window later. A display-only alternative was measured (`pane.report_metadata` with `state_labels`, a token and an 8 s `ttl_ms`; it works with a session stored and expires by itself) and not adopted: it carries no `blocked`, so it produces no notification, and it adds a herdr write for a label.
- **Shared daemon: the hook sees another pane's environment (measured).** Codex 0.160 starts a managed app-server daemon from the first terminal and later terminals connect to it; hooks run as children of the daemon and inherit the first terminal's `HERDR_PANE_ID`. Unchanged, every approval would be published under the wrong pane and the phone would never see it. For `--agent codex` the hook detects a parent process called `app-server` (Linux: `/proc/<ppid>/cmdline`) and finds the pane as the one `agent.list` entry that is Codex and in the same folder as the hook's `cwd`, **whatever its status**; when that is not exactly one, it publishes nothing and returns at once (58 ms measured, no 20 s delay), which leaves Codex's own prompt. An earlier version also required `working`; with a sibling pane in the same folder that was `working` while the asking pane was not (the state a stuck report leaves, or herdr's detection lagging), the request was published under the sibling and a phone Yes would have answered the wrong agent. The cost of the stricter rule: two Codex agents in one folder are never answerable from the phone under the daemon. On a Mac there is no `/proc`: start Codex with `codex --no-daemon`. herdr's own SessionStart reporter also attributes the session to the daemon starter's pane under the daemon (a herdr bug, worth reporting upstream; it does not affect Paddock's path).
- **Trust.** A non-managed Codex hook must be trusted. The trust is a hash of the hook's definition in `~/.codex/config.toml` (`[hooks.state."<hooks.json>:permission_request:0:0"] trusted_hash = "sha256:<hex>"`), so changing the command or the timeout asks for a review again; changing the script's content does not.
- **Exit codes differ.** Exit 2 from a Codex hook means *deny* (Claude Code ignores it for this event). The hook never exits 2. Anything else Codex cannot read (no output, exit 1, non-JSON) leaves the normal prompt.
- **A deny** carries `message: "Denied from the Paddock phone"` for Codex and opencode, so the model is told who said no; Claude Code's output is unchanged.
- Not verified: MCP tool prompts, `approvals_reviewer = "auto_review"` (with an automatic reviewer the dialog may never appear, and the hook would still delay each request by the window), a Mac, Codex 0.160.1's CLI.

## What the phone says, and what it cannot say

- **Where Review lands.** Review prompt on Home, and Review on a blocked agent's notification or widget, open the agent's **Output** tab when this phone can answer that agent (Pro, guarded answers wired, and the agent is Claude Code, Codex or opencode: `AnswerRules.canAnswerFromPhone`), because that tab carries the request entry; the Terminal is one tap away. Any other agent, or without Pro, still lands on the **Terminal** tab, observing. Reported by a user who tapped Review and found the terminal instead of the Yes/No page; `AnswersFlowTest` taps Review prompt and fails if the Terminal opens.

- **When the phone looks.** While an agent's Output tab (or the sheet) is open, the phone reads that agent's request files every 2 s when herdr says the agent is `blocked` or it is Codex, every 4 s for any other state, and every 600 ms while an answer is settling. It reads whatever herdr says: the status says an agent is waiting, not that the wait is a permission, and a request is offered the moment its file shows. A machine whose scripts are missing is asked every 15 s.

- An answer is journaled like every other operation (Requested, Sent, then Acknowledged, Rejected or Unknown), with the request id. The row reads "Yes written 14:03:12 · for the hook to hand to the agent, which this does not prove".
- After the write the sheet reads the host's files and says what became of the request: "Handed to the agent as Yes", then "The agent moved on" once herdr shows it past `blocked` (for Codex, which herdr keeps `working` throughout, only when the turn ends: idle or done).
- **Consumed is not applied.** If the desktop answered a moment before the phone, the agent kept the desktop's answer and ignored the hook's; no file says so. Every line that could suggest otherwise carries the sentence "If the desktop answered a moment earlier, the agent kept the desktop's answer." (Codex has no desktop prompt to answer meanwhile, so for Codex it cannot happen.) The Output tab's entry names the agent ("Codex is waiting for a Yes or No · Bash · Answer within 20 s"), taken from the kind herdr reports for the pane.
- A link that dies after the write leaves the row **Unknown**. Nothing is resent, ever. **Read the files** on the sheet reads the host's files, records what they say ("Read from the host's files: Handed to the agent as Yes…") and frees the row.
- A request whose reply was lost, a lost race (the writer says gone or expired), an expired request and a hook that ended are each drawn as what they are, with the terminal as the next step.
- Tool input is shown whole, scrollable, in a monospace slab; control, bidi and zero-width characters are shown as `\u{…}` so a command cannot look like another command.

## Limits

- Two sessions of the agent in one pane make the request ambiguous: both buttons stay off.
- **One machine at a time, and Android only.** The sheet and its Yes and No are the watched machine's: a push from another machine switches the watch, and then that agent's Output tab shows the entry (`alerts.md`, "Several machines"). An iPhone running the ntfy app gets the alert's heads-up only; there is no Paddock for iOS, so nothing on an iPhone can answer an agent.
- A hook killed with SIGKILL leaves its request `pending` on the host: the phone reads it as "hook ended" or expired and never offers it (the listing carries `hook_alive` and `expires_at`; a phone that read the file raw would show a request minus 218 s old).
- A request cut by the hook (input over 256 KiB), a request too large to read, or one with too little time left is shown, never answerable from the phone.
- For Claude Code and opencode the window is a ceiling on how long the hook holds the agent's call for the phone, not a delay: the desktop dialog is up the whole time. For Codex it is a delay of the desktop prompt.
- Housekeeping is lazy. A killed hook leaves its request `pending` (the phone reads it as "hook ended", never answerable); files older than an hour are deleted only when a later request is published in, or a decision is written for, the same pane. Pane directories are never removed, so a machine that has had many panes ask keeps a few small files each on the tmpfs until logout.
- Not measured on a physical phone yet; see the Phase 08 evidence report in the vault.

## Tests

| What | Where |
| --- | --- |
| Hook and writer: state machine, races, permissions, exit codes, no-window path, desktop-answered rule, supersede, the `--agent` modes (Codex window, no herdr writes, the shared-daemon pane, deny message) | `python3 tools/test-permission-hook.py` (91 tests; `--live` adds the real herdr with `PADDOCK_TEST_SOCKET` set), part of `tools/check.sh` |
| Pure rules, controller, presenter, host wrapper, journal, the per-agent setup texts, the plugin install | `core/src/test/.../answers/*`, `OperationPresenterTest`, `OperationJournalTest`, `HostScriptPinTest` (also pins what the hook may ask herdr and what the opencode plugin may run) |
| Real hook and writer against `paddock-test` | `integration/Phase08LiveTest.kt` (with `PADDOCK_TEST_SOCKET`) |
| Real Claude Code, 13 scenarios x 3 runs | `python3 paddock-harness/run-hook-scenarios.py` |
| The sheet and the setup screen | `DecisionSheetTest`, `GuardedAnswersTest` (`paddock-harness/run-ui-tests.sh`) |
| Through the app, over SSH, on an emulator | `paddock-harness/run-answers-e2e.sh` (`AnswersFlowTest`) |
