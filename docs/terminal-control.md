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
- **An observer is free-form.** It is drawn for any `--cols/--rows` as a top-left crop of the real terminal. Paddock draws it at the terminal's own size and lets the user pan and change the text size; the phone's viewport only matters when the size is unknown.
- **Owner conflict is not an error exit.** Control without `--takeover` while another client is attached prints `terminal.closed` with `terminal attach failed: terminal term_X already has an attached client; retry with --takeover` and exits 0. That text is shown to the user as data.
- **Takeover ends the other client** (a desktop `terminal attach` exits with `terminal attach taken over`). **Release** returns `terminal.closed` with reason `detached`. **stdin EOF** also detaches. A same-size `terminal.resize` answers with a full frame, which is how a controller resyncs after a gap.
- **A dead link keeps control.** If the phone vanishes without closing the SSH connection, the remote control process keeps the terminal until sshd notices, which was longer than 40 s in the measurement (with default sshd settings it is until TCP gives up, which can be hours). Hence the helper:

## The lease helper

`host/paddock-control.py` (pinned in `host/SOURCE.json`, installed only after the user agrees, hash re-checked before every use) runs exactly one herdr control command and forwards only JSON object lines of the four command types. The app sends `{"type":"paddock.lease"}` every 5 s; if 15 s pass with nothing from the phone, or stdin closes, or a signal arrives, the helper sends `terminal.release`, waits up to 3 s for herdr to say goodbye, and kills it. Reads and writes are non-blocking with bounded buffers, so a phone that stops reading cannot stall the lease clock.

Measured with real herdr over a throwaway sshd and a blackhole proxy (no RST, like airplane mode): with the helper, a desktop-style control without takeover was accepted 13.0 s and 13.8 s after the link died (`tools/check-control-lease.py`), and 14.3 to 15.4 s through the whole app on emulators (`tools/run-terminal-e2e.sh`); without it, control was still refused after 40 s.

## What the screen guarantees

- Observing sends nothing; the key strip and the hardware keyboard are inert unless this phone controls the terminal.
- Request control never uses takeover; Take over is a separate button on the refusal. The refusal stays on screen until the user acts.
- Resize to fit, and a control request that would resize an unreadable-size terminal, ask first and say the desktop changes too.
- One terminal channel per host; closing the tab, leaving the screen or going to the background releases control. A rotation keeps the session. Coming back never regains control by itself.
- Frames and grids exist only in memory and are cleared on release.

## Not covered

- Mouse (herdr 0.9.3). Soft-keyboard text entry (the composer is Phase 06). Application cursor-key and bracketed-paste modes (frames do not carry them, so arrows are sent in the CSI form and paste is not wrapped).
- After a silent link loss the app learns of it from the SSH keepalive; the host has usually already given the terminal back by then.
- Physical-phone measurements: airplane mode on a real device and the 200-line scroll timing (AC-05.6 and AC-05.8) are open until a phone is available.
