# Guarded native answers (Phase 08)

A native **Yes** or **No** on the phone for one kind of question: Claude Code's permission prompt. Everything else an agent asks stays manual, in the terminal. An answer is bound to exactly one request, by an id the hook mints, and Paddock never sends a key to give it.

## What it is, and what it is not

- **Only Claude Code's permission prompts.** A `PermissionRequest` hook on the machine publishes the request; the phone answers it through a second script. No other agent, no other prompt, no free text.
- **One request, one answer.** The answer carries the request id from the very file the phone read, and a tap is bound to the id the screen drew: if the sheet has moved to another request by then, nothing is sent. While the request on screen is still live, a newer one is only offered (the user taps to review it); when the one on screen has expired, the sheet moves to the new one, and Yes and No stay off for 1.5 seconds so a finger on its way to the old content cannot land on the new.
- **Never "always allow".** The hook's output has one shape, `{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"|"deny"}}}`: no `updatedInput`, no permission rule.
- **No key is ever sent.** The answering code holds no herdr client (a unit test pins that). The phone writes one decision file over SSH; the hook, running inside Claude Code's own call, hands it back.
- **The desktop is never blocked.** Claude Code shows its own dialog while the hook waits, so whoever answers first wins. A failure of any kind (no config, a timeout, a kill, an exception) prints nothing and exits 0, which leaves the normal dialog.
- **Off until set up, on the machine, by you.** Paddock installs two pinned files after a confirmation. It does not register the hook, write the hook's configuration or touch `~/.claude/settings.json`; it shows the two texts to paste.

## Setup

In the app: **Settings > Guarded answers**.

1. The screen shows the SHA-256 of both scripts (`paddock-claude-permission-hook.py`, `paddock-decide.py`), what is on the host now (missing, current, or a different file that is never run), and installs only those two files, owner-only, after a confirmation that repeats paths and hashes.
2. Paste the **configuration command** on the machine. It writes `~/.config/paddock/hook.toml` (`window_seconds`, 1 to 300; the optional `allowed_tools` list) only if none exists, mode 600. The hook ignores a configuration that is not yours or is group- or world-writable. No file, no window, or `window_seconds = 0` means the hook does nothing.
3. Register the hook: merge the shown JSON under `hooks.PermissionRequest` in `~/.claude/settings.json`. Claude Code's `timeout` is the window plus five seconds.
4. Start a new Claude Code session: Claude Code reads its hook settings at session start.

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
- herdr detects `blocked` from the screen by itself and **ignores `pane report-agent` for a pane whose agent it detects**, so the hook reports nothing; Phase 07's alerts already fire on herdr's own `blocked`. The hook reads `agent.get` over herdr's socket (one connection per question: herdr closes it after one reply) to notice a desktop answer: it has seen `blocked`, then two reads of `working`, `idle` or `done`.
- `HERDR_ENV`, `HERDR_PANE_ID`, `HERDR_SOCKET_PATH` are set in a pane's processes; `HERDR_SESSION` only in a named session, so the session directory component comes from the socket path (`.../sessions/<name>/herdr.sock`, else `default`).

## What the phone says, and what it cannot say

- An answer is journaled like every other operation (Requested, Sent, then Acknowledged, Rejected or Unknown), with the request id. The row reads "Yes written 14:03:12 · for the hook to hand to Claude Code, which this does not prove".
- After the write the sheet reads the host's files and says what became of the request: "Handed to Claude Code as Yes", then "The agent moved on" once herdr shows it past `blocked`.
- **Consumed is not applied.** If the desktop answered a moment before the phone, Claude Code kept the desktop's answer and ignored the hook's; no file says so. Every line that could suggest otherwise carries the sentence "If the desktop answered a moment earlier, Claude Code kept the desktop's answer."
- A link that dies after the write leaves the row **Unknown**. Nothing is resent, ever. **Read the files** on the sheet reads the host's files, records what they say ("Read from the host's files: Handed to Claude Code as Yes…") and frees the row.
- A request whose reply was lost, a lost race (the writer says gone or expired), an expired request and a hook that ended are each drawn as what they are, with the terminal as the next step.
- Tool input is shown whole, scrollable, in a monospace slab; control, bidi and zero-width characters are shown as `\u{…}` so a command cannot look like another command.

## Limits

- Two Claude Code sessions in one pane make the request ambiguous: both buttons stay off.
- A request cut by the hook (input over 256 KiB), a request too large to read, or one with too little time left is shown, never answerable from the phone.
- The window is a ceiling on how long the hook holds Claude Code's call for the phone, not a delay: the desktop dialog is up the whole time.
- Housekeeping is lazy. A killed hook leaves its request `pending` (the phone reads it as "hook ended", never answerable); files older than an hour are deleted only when a later request is published in, or a decision is written for, the same pane. Pane directories are never removed, so a machine that has had many panes ask keeps a few small files each on the tmpfs until logout.
- Not measured on a physical phone yet; see the Phase 08 evidence report in the vault.

## Tests

| What | Where |
| --- | --- |
| Hook and writer: state machine, races, permissions, exit codes, no-window path, desktop-answered rule, supersede | `python3 tools/test-permission-hook.py` (74 tests; `--live` adds the real herdr with `PADDOCK_TEST_SOCKET` set), part of `tools/check.sh` |
| Pure rules, controller, presenter, host wrapper, journal | `core/src/test/.../answers/*`, `OperationPresenterTest`, `OperationJournalTest`, `HostScriptPinTest` |
| Real hook and writer against `paddock-test` | `integration/Phase08LiveTest.kt` (with `PADDOCK_TEST_SOCKET`) |
| Real Claude Code, 13 scenarios x 3 runs | `python3 paddock-harness/run-hook-scenarios.py` |
| The sheet and the setup screen | `DecisionSheetTest`, `GuardedAnswersTest` (`paddock-harness/run-ui-tests.sh`) |
| Through the app, over SSH, on an emulator | `paddock-harness/run-answers-e2e.sh` (`AnswersFlowTest`) |
