# Prompts, keys, focus and the operation journal (Phase 06)

What the phone may send to a herdr agent, when, and how it records what it did. The wire facts were measured against herdr 0.9.1 and are also in the vault's mapping note.

## The four operations

| Operation | herdr call | Gate |
| --- | --- | --- |
| Prompt | `agent.prompt` on the relay socket, text in the request's JSON (stdin of the relay, never argv) | agent known and ready by a read made in the last 2 s (see "Readiness"), text not empty, no operation already running on the terminal, no unknown outcome waiting |
| Esc, Ctrl+C | `agent.send_keys` with `keys: ["esc"]` / `["ctrl+c"]` | Manual input mode, entered by the user, plus a read made after entering; no agent status closes it |
| Desktop focus | `agent.focus` | no agent-state condition; the first tap ever asks (see below) |

All four go through one path (`AgentOperations`): resolve the pane from the terminal's identity (host, session, terminal id), refuse a pane that now holds another terminal, write the journal row, write to the relay, settle the row from the answer. Nothing is retried and nothing is sent twice.

`ComposerRules`, `ManualInputRules` and `FocusRules` share one function (`terminalBlock`) for the conditions every operation has in common: agent gone, link not live, connection re-established since the screen was opened, no read yet, an operation in flight, an unknown outcome waiting for a re-read. A sentence cannot drift between the composer and the key panel.

## Readiness for a prompt

`isReady(agent, readAt, now, requireHints)`: the read must be at most 2 s old, measured from the start of the preflight read (a slow link that takes more than 2 s for the read refuses the send with the "read more than 2 seconds ago" sentence rather than sending on stale information). `interactive_ready` and `launch_pending` are optional in herdr's schema: absent hints pass and the outcome line says they were absent; explicit `false`/`true` fail. A working, blocked or unknown agent is not ready.

## herdr facts that shaped this

- `agent.prompt` is accepted only for a pane whose foreground process is a known agent CLI (`claude`, `codex`, ...). A plain shell pane answers `agent_not_ready`, "is not an active named agent". The tests therefore run `tools/fake-agent.py` under the name `claude` in a pane they create and report that pane as an agent; the script logs every key and submission outside the pane, so "exactly once" is a count.
- **herdr follows the agent's own bracketed-paste mode** (measured on 0.9.1, `Phase06LiveTest.aLineBreakStaysInsideOneSubmissionOnlyForAnAgentThatEnabledBracketedPaste`). To an agent that asked the terminal for bracketed paste, `agent.prompt` with `alpha\nbeta` arrives as one submission with the line break inside; to an agent that did not, it arrives as two submissions, `alpha` and `beta`, about 300 ms apart. Paddock cannot see the mode (`terminal.frame` does not carry it), so the composer's fact line says that an agent that does not accept pasted text may take a line break as Enter instead of promising one submission.
- A successful answer acknowledges that herdr wrote the bytes. It is not a receipt for any agent turn, and the line says so: "Prompt sent 14:03:12 · accepted by herdr, which is not a receipt for any turn".
- All four refusals are captured fixtures (`fixtures/herdr-0.9.1/operation-*.json`) and each has its own sentence, with nothing typed: `agent_blocked` ("is blocked and requires interactive input", never retried), `agent_not_found`, `agent_not_ready` (this is also what a prompt to a pane that is not an agent gets: "is not an active named agent"), and `invalid_key` for `send_keys`. Any other code is shown with herdr's own message.

## The journal

`operations.json` in the app's private files, written atomically. One row per operation: host, session, terminal id, the phone-local epoch the terminal was resolved in, kind, outcome, times, the pane id written to, `state_change_seq` at the send, and the prompt's SHA-256.

- Written ahead: `Requested` before anything else, `Sent` before the first byte leaves (`RelayClient.call(beforeWrite)`), then `Acknowledged`, `Rejected` (herdr said no, certain), `NotSent` (certain: nothing went), or `Unknown`.
- On restart: `Requested` becomes `NotSent` (the process died before any byte), `Sent` becomes `Unknown` (it may have arrived).
- `Unknown` is never deleted and never retried. It holds the terminal: no prompt, key or focus until the user re-reads the agent. The row keeps its outcome; a re-read adds the time it was resolved.
- The prompt text is stored only when Settings has "Keep prompt text" on at send time. Otherwise the row holds the hash and nothing else. Activity never shows prompt text, kept or not.

## Unknown outcome and Re-read

The line is exactly `prompt sent 14:03:12 · outcome unknown · re-read before sending again` (`Esc`, `Ctrl+C` and `desktop focus` for the others). It is the journal's own sentence, so it survives a restart. The only action is **Re-read**: one read of the agent that frees every waiting row of that terminal under the connection now installed (the key carries the new epoch, so a row written before a reconnect is still freed). The report says when it read and what herdr reports, then, for a prompt whose text was kept, whether that text appears in the last 200 lines of output the screen already holds (whitespace and wrapping ignored, otherwise an exact match). It never says the prompt was or was not received; a found line does not say the agent took it as a prompt, and a missing line does not say it was not received. A prompt kept only as a hash says it cannot look.

## The composer's text (the draft)

The unsent text is `PromptDraft`, held by the root above the composer screen, for one terminal. It survives everything that leaves the composer: Edit snippets, Back to the agent, a rotation, and the stretch after a link loss when the composer is replaced by "This agent is not available right now". Another agent never inherits it. It is emptied only by a prompt herdr accepted (a newly accepted row; the same accepted outcome seen again when the composer reopens clears nothing), and kept through a refusal, a failure and an unknown outcome, so after a re-read the user can send it again by their own decision. It lives in memory and in Android's saved instance state; Paddock writes it to no file. (Found by the device flow: the text lived in a `rememberSaveable` inside the composer's route, so it was gone after a reconnect and after a trip to Edit snippets.)

## "No progress observed"

An accepted prompt whose agent still reports the `state_change_seq` it had at the send, five seconds later, gets the label "no progress observed" on its line. It is a label for prompts only; it changes no gate and is never shown for an unknown outcome.

## Manual input mode

Esc and Ctrl+C exist only inside it. The user chooses it on the agent screen; it starts with a read (`host.refresh()`), and the keys stay off until a read made after entering is installed. It lives in the process (a rotation keeps it), is never restored after the app is killed, and ends when the user leaves the agent, closes the composer or opens another agent. The composer's Esc follows the same mode and gate.

## Desktop focus

`agent focus` moves herdr's cursor on the desktop to the agent and, if the agent had finished, marks that completion as seen on the machine. The first tap ever asks, with Cancel as the focused choice and nothing remembered on Cancel. Agreeing is remembered across restarts (`desktopFocusConfirmed` in `settings.json`; a damaged value asks again). It is not a Settings toggle.

## Activity

Journal rows appear under **All** and **From this phone**, worded by what was asked with the outcome as a suffix ("You prompted claude (outcome unknown)"), the journal's own sentence as detail. An unknown row of a listed agent has a Re-read button outside its merged description; the button re-reads, then opens the agent, where the report is.

## Tests

| Tier | What |
| --- | --- |
| JVM | gate matrices (`ComposerRulesTest`, `ManualInputTest`, `FocusRulesTest`), the journal state machine and restart recovery, `AgentOperationsTest` (27), `SendControllerTest`, `PromptTextCheckTest`, `OperationPresenterTest`, Activity and settings |
| JVM live | `Phase06LiveTest` against `paddock-test` with the fake agent: one submission per prompt, Esc and Ctrl+C counted outside the pane, focus, unknown outcome and re-read through the real relay |
| Compose | `ManualInputPanelTest`, `ComposerTest`, `ActivityLogTest`, 217 cases in the `ui` package on API 26 and API 36, including 200% font |
| Emulator, whole app | `tools/run-operations-e2e.sh <serial>`: Add machine over SSH, Manual input and Esc, the focus question, prompts, the journal read from the phone with `run-as`, a link cut after the write, an app restart, Re-read, Ctrl+C |

## The link cut (AC-06.3)

The ack of a prompt returns within milliseconds, so a plain freeze cannot land between the write and the answer reliably. `tools/blackhole-proxy.py` therefore takes `delay MS` (latency from the host to the phone) and `freeze`/`thaw`; `tools/cut-after-submit.py` pre-connects to its control port, waits for the fake agent to log the Nth submission and freezes the link at once, discarding the held answer. The phone sees a request that arrived and no answer, which is exactly the case the journal calls Unknown. `tools/test-blackhole-proxy.py` (part of `tools/check.sh`) tests the proxy itself.
