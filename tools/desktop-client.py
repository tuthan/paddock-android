#!/usr/bin/env python3
"""A stand-in for the herdr desktop client that owns a terminal's input, for the Phase 05 integration tests.

    tools/desktop-client.py TERMINAL_ID [ROWS COLS]

Runs `herdr --session paddock-test[-suffix] terminal attach TERMINAL_ID` inside a pseudo-terminal of ROWS x COLS (default
30 x 100), the way a desktop client attaches, and answers commands on stdin, one per line, with one JSON object per line
on stdout:

    type TEXT     write TEXT and Enter to the attached terminal
    size R C      resize the pseudo-terminal (the client follows)
    status        {"event": "status", "alive": bool, "exit": int|null, "tail": the last printable output}
    quit          kill the client and exit

The session comes from PADDOCK_TEST_SESSION (default paddock-test) and must be a disposable paddock-test session: the
default session is refused. Nothing here touches any other terminal.
"""
import fcntl, json, os, pty, re, select, signal, struct, sys, termios, time

SESSION = re.compile(r"paddock-test(-[a-z0-9]+)?\Z")
TERMINAL = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}\Z")


def say(**kw):
    sys.stdout.write(json.dumps(kw) + "\n")
    sys.stdout.flush()


def main():
    session = os.environ.get("PADDOCK_TEST_SESSION", "paddock-test")
    if not SESSION.match(session):
        say(event="error", message="refusing session %r: only paddock-test sessions" % session)
        return 2
    if len(sys.argv) not in (2, 4) or not TERMINAL.match(sys.argv[1]):
        say(event="error", message="usage: desktop-client.py TERMINAL_ID [ROWS COLS]")
        return 2
    terminal = sys.argv[1]
    rows, cols = (int(sys.argv[2]), int(sys.argv[3])) if len(sys.argv) == 4 else (30, 100)
    herdr = os.environ.get("PADDOCK_HERDR", "/usr/bin/herdr")
    pid, fd = pty.fork()
    if pid == 0:
        for k in [k for k in os.environ if k.startswith("HERDR_")]:
            del os.environ[k]   # a nested-herdr guard otherwise refuses to attach
        os.environ["TERM"] = "xterm-256color"
        os.execv(herdr, [herdr, "--session", session, "terminal", "attach", terminal])
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))
    os.kill(pid, signal.SIGWINCH)
    exit_code = None
    tail = b""

    def pump(wait):
        nonlocal tail
        end = time.time() + wait
        while time.time() < end:
            if not select.select([fd], [], [], 0.05)[0]:
                continue
            try:
                data = os.read(fd, 65536)
            except OSError:
                return
            if not data:
                return
            tail = (tail + data)[-4096:]

    def alive():
        nonlocal exit_code
        if exit_code is not None:
            return False
        done, status = os.waitpid(pid, os.WNOHANG)
        if done:
            exit_code = os.waitstatus_to_exitcode(status)
            return False
        return True

    pump(1.0)
    say(event="ready", alive=alive())
    for line in sys.stdin:
        cmd, _, arg = line.rstrip("\n").partition(" ")
        if cmd == "type":
            try:
                os.write(fd, arg.encode() + b"\r")
            except OSError:
                pass
            pump(0.6)
            say(event="typed", alive=alive())
        elif cmd == "size":
            r, c = (int(x) for x in arg.split())
            fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", r, c, 0, 0))
            if alive():
                os.kill(pid, signal.SIGWINCH)
            pump(0.6)
            say(event="sized", alive=alive())
        elif cmd == "status":
            pump(0.3)
            text = re.sub(r"\x1b\[[0-9;?]*[ -/]*[@-~]|\x1b\][^\x1b\x07]*(\x07|\x1b\\)", "", tail.decode(errors="replace"))
            say(event="status", alive=alive(), exit=exit_code, tail=text[-300:])
        elif cmd == "quit":
            break
    if alive():
        os.kill(pid, signal.SIGKILL)
        os.waitpid(pid, 0)
    return 0


if __name__ == "__main__":
    sys.exit(main())
