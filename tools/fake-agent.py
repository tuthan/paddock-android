#!/usr/bin/env python3
"""A stand-in for an agent CLI, for the disposable herdr session only (Phase 06 tests).

herdr accepts `agent prompt` only when the pane's foreground process is a known agent, so the tests run this script
under a name herdr knows (a symlink called `claude` or `codex`) inside a pane they created, then report that pane as
an agent. It behaves like a TUI: raw terminal, bracketed paste on, one submission per Enter. Every event is appended
as one JSON line to $FAKE_AGENT_LOG, outside the pane, so a test can count what actually arrived without parsing a
screen. It never runs anything it receives.

  FAKE_AGENT_LOG    file to append events to (optional)
  FAKE_AGENT_PASTE  1 (default) asks the terminal for bracketed paste like a real TUI; 0 does not
"""
import codecs
import json
import os
import select
import sys
import termios
import time
import tty

LOG = os.environ.get("FAKE_AGENT_LOG")
PASTE_ON, PASTE_OFF = b"\x1b[200~", b"\x1b[201~"


def log(event, **fields):
    if LOG:
        with open(LOG, "a") as f:
            f.write(json.dumps({"event": event, "t": time.time(), **fields}) + "\n")


def say(text):
    os.write(1, text.replace("\n", "\r\n").encode())


def main():
    fd = sys.stdin.fileno()
    saved = termios.tcgetattr(fd)
    tty.setraw(fd)
    decoder = codecs.getincrementaldecoder("utf-8")("replace")
    try:
        if os.environ.get("FAKE_AGENT_PASTE", "1") == "1":
            os.write(1, b"\x1b[?2004h")
        say("fake agent ready\n")
        log("ready", pid=os.getpid())
        pending, typed, submissions = b"", "", 0
        while True:
            more = os.read(fd, 4096)
            if not more:
                return
            pending += more
            while pending:
                if pending.startswith(PASTE_ON):
                    end = pending.find(PASTE_OFF)
                    if end < 0:
                        break  # the rest of the paste has not arrived
                    typed += pending[len(PASTE_ON):end].decode("utf-8", "replace")
                    pending = pending[end + len(PASTE_OFF):]
                elif pending[0] == 0x1B:
                    if len(pending) < len(PASTE_ON) and PASTE_ON.startswith(pending):
                        # A lone Esc, or the start of a paste marker: wait a moment for the rest of it.
                        if select.select([fd], [], [], 0.08)[0]:
                            break
                        log("esc")
                        say("[esc]\n")
                    else:
                        log("escape-sequence", bytes=pending[:8].hex())
                    pending = b""
                elif pending[0] == 0x03:
                    log("ctrl-c")
                    say("[ctrl-c]\n")
                    return
                elif pending[0] in (0x0D, 0x0A):
                    submissions += 1
                    log("submit", text=typed, n=submissions)
                    say("GOT %d: %s\n" % (submissions, typed))
                    typed, pending = "", pending[1:]
                else:
                    typed += decoder.decode(pending[:1])
                    pending = pending[1:]
    finally:
        termios.tcsetattr(fd, termios.TCSADRAIN, saved)
        os.write(1, b"\x1b[?2004l")


if __name__ == "__main__":
    main()
