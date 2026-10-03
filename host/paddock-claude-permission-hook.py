#!/usr/bin/env python3
"""paddock-claude-permission-hook: a Claude Code PermissionRequest hook that lets a phone answer one permission prompt
(Python 3 standard library only; Python 3.11 or newer for the configuration).

Register it yourself in ~/.claude/settings.json beside any other hook (the timeout is the window plus five seconds):

    {"hooks": {"PermissionRequest": [{"hooks": [{"type": "command",
        "command": "python3 /home/you/.local/share/paddock/paddock-claude-permission-hook.py", "timeout": 65}]}]}}

and turn it on in ~/.config/paddock/hook.toml (the default is off: no window, the hook does nothing):

    window_seconds = 60                      # 0 to 300; how long a phone has to answer
    allowed_tools = ["Bash", "Edit"]         # optional; the default is every tool

Claude Code shows its own dialog while this hook waits, so the desktop is never blocked: whoever answers first wins. The
hook mints a request id, publishes the request as a file under $XDG_RUNTIME_DIR/paddock/<herdr session>/<pane id>/ (see
paddock-decide.py for the states and the rename protocol; the request carries this process's pid so a writer can tell that the
hook ended), and waits up to the window for a decision file carrying that id.
A decision becomes Claude Code's answer to this one invocation and nothing else: allow or deny, never updatedInput, never a
permission rule. When the window lapses, or herdr shows the pane's agent moving on from `blocked` (the desktop answered:
Claude Code does not stop a hook when that happens, and a decision printed afterwards is ignored), the request is marked
expired and the hook prints nothing, which leaves the normal dialog. A newer request of the same Claude session in the same pane
supersedes an older pending one (the dialog it belonged to is gone), and the older hook then stops waiting. Any exception, a timeout and a signal all end the same
way: no output, exit 0, so a failure can only fall back to the desktop dialog. Exit code 2 is never used; Claude Code does
not honour it for this event.

Exits at once, printing nothing, unless HERDR_ENV is 1, the window is above zero and the tool is allowed. It never calls
herdr to report a state: herdr 0.9.1 detects `blocked` from the screen by itself and ignores reports for a pane whose agent it
detects (Phase 08 evidence). It reads the pane's status over herdr's own socket, read-only, to notice a desktop answer.
"""
import json
import os
import sys
import time

VERSION = 1
MAX_INPUT = 256 * 1024         # tool input kept in the request; past this it is cut and the request is never answerable
MAX_STDIN = 16 * 1024 * 1024
MAX_WINDOW = 300
GRACE = 2.0                    # a claim that races the lapse gets this long for its decision file to appear
POLL = 0.2                     # how often the decision file is looked for
STATUS_POLL = 0.5              # how often herdr is asked how the pane's agent is doing
STALE_SECONDS = 3600
READ_LIMIT = 300 * 1024        # the most of an older request's file read to learn which Claude session it belongs to


class Terminated(BaseException):
    """A termination signal; ends the wait like any other failure, after the request is marked expired."""


def _on_signal(signum, frame):
    raise Terminated()


def config_path():
    base = os.environ.get("XDG_CONFIG_HOME") or os.path.join(os.path.expanduser("~"), ".config")
    return os.path.join(base, "paddock", "hook.toml")


def load_config():
    """(window seconds, allowed tools or None). Anything wrong, missing or unsafe means the hook is off."""
    path = config_path()
    try:
        st = os.stat(path)
    except OSError:
        return 0, None
    if st.st_uid != os.geteuid() or st.st_mode & 0o022:
        return 0, None
    import tomllib
    with open(path, "rb") as f:
        data = tomllib.load(f)
    window = data.get("window_seconds", 0)
    if isinstance(window, bool) or not isinstance(window, int) or not 0 <= window <= MAX_WINDOW:
        return 0, None
    tools = data.get("allowed_tools")
    if tools is not None and not (isinstance(tools, list) and all(isinstance(t, str) for t in tools)):
        return 0, None
    return window, tools


def new_request_id():
    """A random (version 4) UUID, built here because importing uuid costs more than the rest of the no-window path."""
    b = bytearray(os.urandom(16))
    b[6] = (b[6] & 0x0F) | 0x40
    b[8] = (b[8] & 0x3F) | 0x80
    h = b.hex()
    return "%s-%s-%s-%s-%s" % (h[:8], h[8:12], h[12:16], h[16:20], h[20:])


def valid(pattern, text):
    return isinstance(text, str) and pattern.match(text) is not None


def herdr_session(environ, patterns):
    name = environ.get("HERDR_SESSION")
    if name and patterns["session"].match(name):
        return name
    socket_path = environ.get("HERDR_SOCKET_PATH") or ""
    parts = socket_path.split("/")
    if len(parts) >= 3 and parts[-1] == "herdr.sock" and parts[-3] == "sessions" and patterns["session"].match(parts[-2]):
        return parts[-2]
    return "default"


def make_directory(base, parts):
    """Creates each of [parts] below [base] as 0700 (os.makedirs would leave the middle ones at the umask) and checks that the
    result belongs to this user alone. Returns the leaf."""
    import stat
    path = base
    for part in parts:
        path = os.path.join(path, part)
        try:
            os.mkdir(path, 0o700)
        except FileExistsError:
            pass
        st = os.lstat(path)
        if not stat.S_ISDIR(st.st_mode) or st.st_uid != os.geteuid():
            raise OSError("not our directory")
        if st.st_mode & 0o077:
            os.chmod(path, 0o700)
            if os.lstat(path).st_mode & 0o077:
                raise OSError("cannot make the directory private")
    return path


def prune(directory, patterns):
    now = time.time()
    try:
        names = os.listdir(directory)
    except OSError:
        return
    for name in names:
        if patterns["file"].match(name) or patterns["tmp"].match(name):
            path = os.path.join(directory, name)
            try:
                if now - os.lstat(path).st_mtime > STALE_SECONDS:
                    os.unlink(path)
            except OSError:
                pass


def supersede(directory, patterns, claude_session):
    """Marks every older pending request of this Claude session in this pane expired.

    Claude Code makes its next tool call only after the dialog for the one before it was resolved, so a request of the same session that
    is still pending belongs to a dialog that is gone. The usual case is the desktop answering it and the next prompt following at once:
    herdr's status then never shows two reads away from `blocked` (it is `blocked` again), so the older hook goes on waiting out its
    window and its request would reappear as the newest pending one once this one was answered. The move is one rename, the same one
    the older hook's own expiry makes; if the phone claimed it first the rename fails and nothing changes. A request of another Claude
    session is left alone, and expiring one only ever hands that dialog back to the desktop."""
    try:
        names = os.listdir(directory)
    except OSError:
        return
    for name in names:
        if not name.endswith(".pending.json") or not patterns["file"].match(name):
            continue
        path = os.path.join(directory, name)
        try:
            with open(path, "rb") as f:
                raw = f.read(READ_LIMIT + 1)
            body = json.loads(raw.decode("utf-8")) if len(raw) <= READ_LIMIT else None
            if isinstance(body, dict) and body.get("claude_session_id") == claude_session:
                os.rename(path, os.path.join(directory, name.split(".")[0] + ".expired.json"))
        except (OSError, ValueError):
            pass


class Herdr:
    """Read-only questions to herdr's socket: how is the agent in this pane doing? Every failure answers None.

    herdr 0.9.1 closes a connection after the first reply, so each question is its own connection."""

    def __init__(self, socket_path, pane):
        self.socket_path, self.pane = socket_path, pane

    def status(self):
        if not self.socket_path:
            return None
        try:
            import socket
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as sock:
                sock.settimeout(0.3)
                sock.connect(self.socket_path)
                request = {"id": "paddock-hook", "method": "agent.get", "params": {"target": self.pane}}
                sock.sendall((json.dumps(request) + "\n").encode("utf-8"))
                buffer = b""
                while b"\n" not in buffer:
                    chunk = sock.recv(65536)
                    if not chunk:
                        break
                    buffer += chunk
                    if len(buffer) > 1 << 20:
                        return None
            status = json.loads(buffer.partition(b"\n")[0].decode("utf-8"))["result"]["agent"]["agent_status"]
            return status if isinstance(status, str) else None
        except Exception:
            return None

    def close(self):
        pass


class Request:
    def __init__(self, directory, request_id):
        self.directory, self.id = directory, request_id
        self.pending, self.claimed, self.expired, self.consumed = (os.path.join(directory, "%s.%s.json" % (request_id, s)) for s in ("pending", "claimed", "expired", "consumed"))
        self.decision = os.path.join(directory, "%s.decision.json" % request_id)
        self.settled = False

    def read_decision(self):
        """'allow' or 'deny' when a complete, matching decision file exists, else None."""
        try:
            with open(self.decision, "rb") as f:
                raw = f.read(8193)
            if len(raw) > 8192:
                return None
            body = json.loads(raw.decode("utf-8"))
        except (OSError, ValueError):
            return None
        if isinstance(body, dict) and body.get("request_id") == self.id and body.get("behavior") in ("allow", "deny"):
            return body["behavior"]
        return None

    def expire(self, grace):
        """Marks the request expired. Returns a decision to honour when a claim raced the lapse and its decision arrived in time."""
        try:
            os.rename(self.pending, self.expired)
            self.settled = True
            return None
        except FileNotFoundError:
            pass
        if grace:
            end = time.monotonic() + GRACE
            while True:
                behavior = self.read_decision()
                if behavior:
                    return behavior
                if time.monotonic() >= end:
                    break
                time.sleep(0.05)
        try:
            os.rename(self.claimed, self.expired)
        except FileNotFoundError:
            pass
        self.settled = True
        return None

    def consume(self):
        try:
            os.rename(self.claimed, self.consumed)
        except FileNotFoundError:
            pass
        self.settled = True


def honour(request, behavior):
    sys.stdout.write(json.dumps({"hookSpecificOutput": {"hookEventName": "PermissionRequest", "decision": {"behavior": behavior}}}))
    sys.stdout.flush()
    request.consume()


def wait(request, herdr, window):
    """Waits up to [window] seconds. Returns the behavior to print, or None to leave the dialog to the desktop."""
    deadline = time.monotonic() + window
    next_status = 0.0
    seen_blocked, moved_on = False, 0
    while True:
        behavior = request.read_decision()
        if behavior:
            return behavior
        if not os.path.exists(request.pending) and not os.path.exists(request.claimed):
            # Somebody else expired it (a newer request of this session superseded it): nothing waits for a decision any more.
            request.settled = True
            return None
        now = time.monotonic()
        if now >= deadline:
            return request.expire(grace=True)
        if now >= next_status:
            next_status = now + STATUS_POLL
            status = herdr.status()
            if status == "blocked":
                seen_blocked, moved_on = True, 0
            elif seen_blocked and status in ("working", "idle", "done"):
                moved_on += 1
                if moved_on >= 2:
                    # The desktop answered. A decision arriving now would be ignored by Claude Code, so it is never honoured.
                    return request.expire(grace=False)
        time.sleep(POLL)


def run():
    os.umask(0o077)
    environ = os.environ
    raw = sys.stdin.buffer.read(MAX_STDIN + 1)
    if environ.get("HERDR_ENV") != "1" or not environ.get("HERDR_PANE_ID") or not os.path.exists(config_path()):
        return
    window, allowed = load_config()
    if window <= 0 or len(raw) > MAX_STDIN:
        return
    import re
    patterns = {
        "session": re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}\Z"),
        "pane": re.compile(r"[A-Za-z0-9_][A-Za-z0-9_:.-]{0,63}\Z"),
        "claude": re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.:-]{0,127}\Z"),
        "file": re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\.(pending|claimed|decision|consumed|expired)\.json\Z"),
        "tmp": re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\.[a-z]+\.tmp\Z"),
    }
    data = json.loads(raw.decode("utf-8"))
    if not isinstance(data, dict) or data.get("hook_event_name") != "PermissionRequest":
        return
    tool, claude_session, mode = data.get("tool_name"), data.get("session_id"), data.get("permission_mode")
    pane = environ["HERDR_PANE_ID"]
    session = herdr_session(environ, patterns)
    if not isinstance(tool, str) or not tool or len(tool) > 200 or not valid(patterns["claude"], claude_session) or not valid(patterns["pane"], pane):
        return
    if allowed is not None and tool not in allowed:
        return
    mode = mode if isinstance(mode, str) and len(mode) <= 64 else ""

    encoded = json.dumps(data.get("tool_input"), ensure_ascii=True, separators=(",", ":"))
    truncated = len(encoded) > MAX_INPUT
    tool_input = encoded[:MAX_INPUT] if truncated else data.get("tool_input")

    directory = make_directory(environ.get("XDG_RUNTIME_DIR") or "/run/user/%d" % os.getuid(), ("paddock", session, pane))
    prune(directory, patterns)
    supersede(directory, patterns, claude_session)

    request_id = new_request_id()
    request = Request(directory, request_id)
    created = int(time.time() * 1000)
    body = {"v": VERSION, "request_id": request_id, "claude_session_id": claude_session, "herdr_session": session, "pane_id": pane,
            "tool_name": tool, "tool_input": tool_input, "permission_mode": mode, "created_at": created,
            "expires_at": created + window * 1000, "truncated": truncated, "pid": os.getpid()}
    tmp = os.path.join(directory, request_id + ".pending.tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_CLOEXEC", 0), 0o600)
    try:
        os.write(fd, json.dumps(body, ensure_ascii=True, separators=(",", ":")).encode("ascii"))
    finally:
        os.close(fd)
    os.rename(tmp, request.pending)

    import signal
    for name in ("SIGTERM", "SIGHUP", "SIGINT"):
        signal.signal(getattr(signal, name), _on_signal)
    herdr = Herdr(environ.get("HERDR_SOCKET_PATH"), pane)
    try:
        behavior = wait(request, herdr, window)
        if behavior:
            honour(request, behavior)
    except BaseException:
        if not request.settled:
            try:
                request.expire(grace=False)
            except BaseException:
                pass
        raise
    finally:
        herdr.close()


def main():
    try:
        run()
    except BaseException:
        pass
    try:
        sys.stdout.flush()
    except BaseException:
        pass
    os._exit(0)     # a closed stdout would otherwise turn the interpreter's own final flush into exit status 120


if __name__ == "__main__":
    main()
