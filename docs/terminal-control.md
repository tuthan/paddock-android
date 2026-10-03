# Terminal observe and control (Phase 05)

How the Terminal tab talks to herdr, what was measured against herdr 0.9.1 to design it, and what is still open. The engine choice is in `terminal-engine-decision.md`.

## The channels

| What | Command on the host | Who may run it |
| --- | --- | --- |
| Observe | `herdr --session S terminal session observe PANE --cols C --rows R` | any number of clients; read-only; never changes the terminal |
| Control | `python3 ~/.local/share/paddock/paddock-control.py LEASE HERDR S PANE C R [takeover]`, which runs `herdr --session S terminal session control PANE [--takeover] --cols C --rows R` | one owner of the terminal's input at a time |

Both stream newline-delimited JSON on stdout: `terminal.frame` (`seq`, `encoding` "ansi", `full`, `width`, `height`, base64 `bytes`) and `terminal.closed` (`reason`). Control reads `terminal.input` (base64 bytes), `terminal.resize`, `terminal.scroll`, `terminal.release` on stdin. `terminal.mouse` is only in herdr 0.9.3; 0.9.1 ignores it with a line on stderr, and nothing in the app sends it.

A frame is a cell patch: cursor positioning, SGR (including 256-colour and true colour), `2J`, `?25`, `?2026` and an empty OSC 8. There is no scrolling, erase-line or alternate screen, so the app's engine (`VtEngine`) is small. Wide characters are placed by explicit column.

## Facts measured on herdr 0.9.1

- **Control attach sets the PTY to the controller's `--cols/--rows`.** There is no "keep size" option. To avoid resizing the desktop, Paddock attaches at the terminal's own size. That size is not in the API (`pane get` has rows only), so `PtyProbe` reads it: `pane process-info` names a process of the pane, `/proc/<pid>/stat` gives its controlling terminal, `stty -F /dev/pts/N size` prints the size. If any step fails the size is unknown, and the app warns that taking control sets the terminal to the phone's size before it asks.
- **`shell_pid` is not always the shell.** herdr reports the leader of the terminal's foreground process group, so while a job runs it can be a process whose stdin is a pipe. Reading `/proc/<pid>/fd/0` failed in about one live run in four for that reason; the controlling terminal is the same for the whole group, so that is what the probe reads, with one retry for a job that ends between the calls.
- **An observer does not notice a closed channel.** It never reads its stdin, so after the SSH channel closes it lives until the pane next draws and its write fails (found 2026-10-02: one stray process per closed Terminal view on an idle pane). Paddock runs it under `HerdrCli.OBSERVE_WATCH`, a constant `/bin/sh -c` script that ends it when its own stdin reaches end of file and exits with the observer's status when the observer ends first; `ObserveWatchTest` runs the script for real.
- **An observer is free-form.** It is drawn for any `--cols/--rows` as a top-left crop of the real terminal. Paddock draws it at the terminal's own size and lets the user pan and change the text size; the phone's viewport only matters when the size is unknown.
- **Owner conflict is not an error exit.** Control without `--takeover` while another client is attached prints `terminal.closed` with `terminal attach failed: terminal term_X already has an attached client; retry with --takeover` and exits 0. That text is shown to the user as data.
- **Takeover ends the other client** (a desktop `terminal attach` exits with `terminal attach taken over`). **Release** returns `terminal.closed` with reason `detached`. **stdin EOF** also detaches. A same-size `terminal.resize` answers with a full frame, which is how a controller resyncs after a gap.
- **A dead link keeps control.** If the phone vanishes without closing the SSH connection, the remote control process keeps the terminal until sshd notices, which was longer than 40 s in the measurement (with default sshd settings it is until TCP gives up, which can be hours). Hence the helper:

## The lease helper

`host/paddock-control.py` (pinned in `host/SOURCE.json`, installed only after the user agrees, hash re-checked before every use) runs exactly one herdr control command and forwards only JSON object lines of the four command types. The app sends `{"type":"paddock.lease"}` every 5 s; if 15 s pass with nothing from the phone, or stdin closes, or a signal arrives, the helper sends `terminal.release`, waits up to 3 s for herdr to say goodbye, and kills it. Reads and writes are non-blocking with bounded buffers, so a phone that stops reading cannot stall the lease clock.

Measured with real herdr over a throwaway sshd and a blackhole proxy (no RST, like airplane mode): with the helper, a desktop-style control without takeover was accepted 13.0 s and 13.8 s after the link died (`tools/check-control-lease.py`), and 14.3 to 15.4 s through the whole app on emulators (`tools/run-terminal-e2e.sh`); without it, control was still refused after 40 s.

## The Android keyboard

While this phone controls the terminal, **Keyboard** (beside Release) shows the on-screen keyboard and **Hide keyboard** puts it away; it is not offered while observing, and releasing hides it. A terminal has no text field, so the keyboard types into a hidden one (`SoftKeyboardInput`): one dp, transparent, hidden from TalkBack, holding two zero-width characters that no one sees. Each edit is read against them (`SoftInput.keys`), sent as keys (text as UTF-8, a line break as Enter, a deleted zero-width character as Backspace) and undone inside the edit itself, so nothing typed stays in the field and Backspace works on an empty line. The field asks for a password-type input with no correction, so the keyboard does not suggest, capitalise or compose.

The key strip has what a phone keyboard lacks. Row 1 is the design's eight (Esc, Enter, the four arrows, Tab, Ctrl+C). Under control a second row adds sticky **Ctrl**, **Alt** and **Shift**, Home, End, PgUp, PgDn, Ins, Del (forward delete) and F1 to F12; the row scrolls sideways and costs the terminal one row of height (48 dp, or 40 dp while the keyboard is up, see below). Observing shows row 1 only, inert.

### When the keyboard is up: room for the terminal

Gboard takes a third of the screen, and the agent screen's top (a two-line title, its context line, the state chip, the Output and Terminal tabs) and a wrapped row of controls took most of the rest: on a phone the grid was left about one line tall. While the Terminal tab and the keyboard are both showing (`WindowInsets.isImeVisible`; Hide keyboard brings everything back):

- **The top shrinks to one line**: back, the title on one line (it stays, because it says which terminal the keys go to) and the state as a dot and a word. The context line, the age of the observation and the tabs are hidden (`AgentOutput` `terminalFocus`). An agent that has gone keeps its full header.
- **The controls are one line** of 36 dp buttons that scrolls sideways if the four do not fit (`TerminalTab` `compact`); the pill says `control · 208×79`, and its spoken text is unchanged.
- **The key strip is two 40 dp rows with no note under it** (`KeyStrip` `dense`; Gboard's own keys are about 46 dp). The keys, their order and their spoken text are the same; each key is still at least 48 dp wide.
- **The grid follows the cursor** (`TerminalCanvas` `followCursor`): a terminal larger than the view (a 208×79 pane on a phone) used to show its top rows while the person typed at the bottom. Under control the grid moves the least it has to so the cursor's cell is in view, and again after every key sent. Moving the grid by hand holds it where it was left until the next key, the keyboard coming or going, or a double tap.

Measured on the API 36 emulator with Gboard (`tools/run-terminal-e2e.sh`): the grid is 344 dp tall with the keyboard up and the Esc key 40 dp (`TerminalTabTest` and `AgentOutputTest` cover the layout without a keyboard; `FollowCursorTest` the movement).

The icon on the "in control" pill and on Request control is a keyboard (`PaddockIcons.Keyboard`). It was the key glyph, which at 14 dp reads as a wrench or a settings icon; the key stays where it means the SSH key.

A modifier is armed by a tap (accent border, announced as a switch: "Shift, for the next key", armed or off; a second tap disarms it) and **the next key spends all of them**, whichever key it is:

- a key on the strip is encoded with the armed modifiers as xterm does it (`KeyEncoder.named`): Shift+Tab is `ESC [ Z`, Ctrl+Right `ESC [ 1;5 C`, Shift+PgUp `ESC [ 5;2 ~`, Alt+Enter `ESC CR`, F5 with Ctrl `ESC [ 15;5 ~`. Ctrl+C is a chord of its own and does not change, but it spends them;
- a character typed on the Android keyboard: Ctrl gives its control code (c is 0x03), Alt prefixes ESC (Alt+b is `ESC b`), Shift upper-cases a letter (the keyboard has already chosen the case of anything else); on a character a modifier means nothing to, the character is sent as it is and the modifier is still spent. Enter and Backspace from the keyboard take the modifiers too (Alt+Backspace is `ESC DEL`, readline's delete-word; Ctrl+Backspace is 0x08). This is a change from the first sticky Ctrl, which only a character spent;
- a key event the keyboard sends (Gboard's Enter and Backspace, a Bluetooth keyboard's keys in the hidden field) joins the armed modifiers to the ones the event carries.

Not built: locking a modifier on (a tap arms for one key only), and sticky modifiers for a hardware keyboard whose focus is on the terminal itself rather than the hidden field. Letters, digits and punctuation are not on the strip: Gboard has them. A hardware keyboard keeps working: keys that are not text (arrows, Esc, Ctrl chords) are encoded as before, printable keys go through the field, and nothing is sent twice.

**A bug the device found.** The first version put the field back to its two characters in a state update after each edit. A keyboard commits several edits in one frame, so the next edit was read against text already sent and everything before it was sent again: `echo soft-e2e` reached the pane as `eecho sofftft-ft-e2e` (a device flow with Gboard on the API 36 emulator). Compose's `performTextInput` waits between edits and could not show it. The fix undoes each edit in an `InputTransformation`, which sees it synchronously. `SoftKeyboardInputTest` commits several edits to the field's `InputConnection` in one UI-thread turn, the way an input method does (7 of 8 first cases failed on the first version), and `tools/run-terminal-e2e.sh` injects a line through the system input pipeline and checks the pane. Real taps on Gboard's keys (`l`, `s`, space, Backspace, `d`, `f`, Enter on the API 36 emulator) produced `6c 73 20 7f 64 66 0d`, once each.

**Not supported:** a keyboard that composes text despite the request (it replaces the word it is typing with each letter). Compose does not let an input transformation see the composition, so each step would be read as new text and its letters sent again. Gboard commits directly in such a field. If a phone's keyboard shows the symptom (repeated letters), use another keyboard for the terminal. Voice typing, glide typing and emoji panels are untested. Not tried on a physical phone.

## What the screen guarantees

- Observing sends nothing; the key strip, the Android keyboard and the hardware keyboard are inert unless this phone controls the terminal.
- Request control never uses takeover; Take over is a separate button on the refusal. The refusal stays on screen until the user acts.
- Resize to fit, and a control request that would resize an unreadable-size terminal, ask first and say the desktop changes too.
- One terminal channel per host; closing the tab, leaving the screen or going to the background releases control. A rotation keeps the session. Coming back never regains control by itself.
- Frames and grids exist only in memory and are cleared on release.

## Not covered

- Mouse (herdr 0.9.3). Application cursor-key and bracketed-paste modes (frames do not carry them, so arrows are sent in the CSI form and paste is not wrapped).
- After a silent link loss the app learns of it from the SSH keepalive; the host has usually already given the terminal back by then.
- Physical-phone measurements: airplane mode on a real device and the 200-line scroll timing (AC-05.6 and AC-05.8) are open until a phone is available.
