#!/usr/bin/env python3
"""paddock-decide: list and answer the Claude Code permission requests that paddock-claude-permission-hook.py publishes
(Python 3 standard library only).

    paddock-decide.py list   HERDR_SESSION PANE_ID
    paddock-decide.py decide HERDR_SESSION PANE_ID CLAUDE_SESSION_ID REQUEST_ID     stdin: {"behavior":"allow"|"deny","phone":"...","at":"..."}

Requests live in $XDG_RUNTIME_DIR/paddock/<herdr session>/<pane id>/ (0700, a tmpfs that is cleared at logout). One file per
request carries its state in the name, `<request id>.<state>.json`, and every move between states is a rename(2), so two
processes can never both win:

    pending   the hook published the request (it wrote a .tmp file, then renamed it)
    claimed   this script renamed pending to claimed; ENOENT means somebody else, or the hook's expiry, got there first
    decision  this script's answer, written beside `claimed` as .tmp and renamed into place
    consumed  the hook renamed claimed after it read the decision and printed it
    expired   the hook renamed pending (the window lapsed or the desktop answered) or claimed (no decision in the grace period)

`list` only reads. `decide` refuses, leaving the request pending, when the request is truncated, expired by this host's clock,
malformed, published by a hook that has since ended (its pid is in the request), or names another Claude session or pane than
the arguments; otherwise it claims the request and publishes the
decision. Files older than an hour are deleted by `decide`, which is the only deletion this script makes. Nothing the
phone sends is ever logged, and nothing but a decision file is ever written.

Exit codes: 0 done; 2 usage or bad stdin; 3 the request is already claimed, expired, consumed or never existed; 4 the
request has expired or its hook has ended; 5 the request's tool input was truncated, so it is never answerable from a phone; 6 the request is
malformed or does not match the arguments; 7 an I/O error. One line on stderr says which.
"""
import json
import os
import re
import stat
import sys
import time

VERSION = 1
EXIT_USAGE, EXIT_GONE, EXIT_EXPIRED, EXIT_TRUNCATED, EXIT_MALFORMED, EXIT_IO = 2, 3, 4, 5, 6, 7

FILE = re.compile(r"([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\.(pending|claimed|decision|consumed|expired)\.json\Z")
TMP = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\.[a-z]+\.tmp\Z")
REQUEST_ID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\Z")
SESSION = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}\Z")
PANE = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_:.-]{0,63}\Z")
CLAUDE_SESSION = re.compile(r"[A-Za-z0-9_][A-Za-z0-9_.:-]{0,127}\Z")

READ_LIMIT = 300 * 1024      # a request file is at most 256 KiB of tool input plus about 1 KiB; past this it is never read whole
LIST_LIMIT = 40              # request files looked at by `list`, newest first
STALE_SECONDS = 3600         # a request file this old belongs to a request that has been settled for a long time
STATES = ("consumed", "expired", "claimed", "pending")   # precedence when one request somehow shows two files


def fail(code, word, message):
    sys.stderr.write("paddock-decide: %s: %s\n" % (word, message))
    sys.exit(code)


def now_ms():
    return int(time.time() * 1000)


def runtime_root():
    base = os.environ.get("XDG_RUNTIME_DIR") or "/run/user/%d" % os.getuid()
    return os.path.join(base, "paddock")


def request_dir(session, pane):
    return os.path.join(runtime_root(), session, pane)


def own_directory(path):
    """True when [path] is a directory of this user that nobody else can enter."""
    try:
        st = os.lstat(path)
    except OSError:
        return False
    return stat.S_ISDIR(st.st_mode) and st.st_uid == os.geteuid() and not st.st_mode & 0o077


def own_file(path):
    try:
        st = os.lstat(path)
    except OSError:
        return None
    return st if stat.S_ISREG(st.st_mode) and st.st_uid == os.geteuid() else None


def read_bounded(path, limit=READ_LIMIT):
    """(bytes, complete). Reads at most [limit] bytes; complete is false when the file is longer."""
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_CLOEXEC", 0)
    fd = os.open(path, flags)
    try:
        data = os.read(fd, limit + 1)
    finally:
        os.close(fd)
    return data[:limit], len(data) <= limit


def prune(directory, now_seconds):
    """Deletes request and temp files older than STALE_SECONDS: settled requests and the orphans a lapsed request leaves."""
    try:
        names = os.listdir(directory)
    except OSError:
        return
    for name in names:
        if not (FILE.match(name) or TMP.match(name)):
            continue
        path = os.path.join(directory, name)
        st = own_file(path)
        if st is not None and now_seconds - st.st_mtime > STALE_SECONDS:
            try:
                os.unlink(path)
            except OSError:
                pass


def hook_alive(pid):
    """Whether [pid] is still a running paddock-claude-permission-hook of this user. A killed hook leaves its request pending, and
    nothing would ever read an answer to it. Without /proc only the existence of the process can be told."""
    if isinstance(pid, bool) or not isinstance(pid, int) or pid <= 0:
        return None
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return False       # the number now belongs to somebody else's process
    except OSError:
        return None
    if not os.path.isdir("/proc/self"):
        return True
    try:
        with open("/proc/%d/cmdline" % pid, "rb") as f:
            return b"paddock-claude-permission-hook" in f.read(4096)
    except OSError:
        return False


def parse_request(raw):
    """The request object, or None when the bytes are not a request this script understands."""
    try:
        body = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return None
    if not isinstance(body, dict) or body.get("v") != VERSION:
        return None
    for key in ("request_id", "claude_session_id", "pane_id", "tool_name"):
        if not isinstance(body.get(key), str):
            return None
    for key in ("created_at", "expires_at"):
        value = body.get(key)
        if isinstance(value, bool) or not isinstance(value, int):
            return None
    if not isinstance(body.get("truncated"), bool):
        return None
    return body


def cmd_list(session, pane):
    directory = request_dir(session, pane)
    now = now_ms()
    out = {"v": VERSION, "now_ms": now, "requests": [], "candidate": None}
    if not own_directory(directory):
        print(json.dumps(out, separators=(",", ":")))
        return
    seen = []
    try:
        names = os.listdir(directory)
    except OSError:
        names = []
    for name in names:
        match = FILE.match(name)
        if not match:
            continue
        st = own_file(os.path.join(directory, name))
        if st is not None:
            seen.append((st.st_mtime, name, match.group(1), match.group(2), st.st_size))
    seen.sort(reverse=True)
    requests = {}
    decisions = {}
    for mtime, name, request_id, state, size in seen[:LIST_LIMIT]:
        path = os.path.join(directory, name)
        if state == "decision":
            try:
                raw, complete = read_bounded(path, 4096)
                decision = json.loads(raw.decode("utf-8")) if complete else None
                decisions[request_id] = decision.get("behavior") if isinstance(decision, dict) and decision.get("behavior") in ("allow", "deny") else None
            except (OSError, ValueError, UnicodeDecodeError):
                decisions[request_id] = None
            continue
        entry = {"request_id": request_id, "state": state, "size": size, "mtime_ms": int(mtime * 1000),
                 "created_at": None, "expires_at": None, "claude_session_id": None, "truncated": None, "decision": None,
                 "tool_name": None, "complete": False, "hook_alive": None}
        body = None
        try:
            raw, complete = read_bounded(path)
            entry["complete"] = complete
            body = parse_request(raw) if complete else None
        except OSError:
            body = None
        if body is not None:
            entry.update(created_at=body["created_at"], expires_at=body["expires_at"], claude_session_id=body["claude_session_id"],
                         truncated=body["truncated"], tool_name=body["tool_name"])
            entry["body"] = body if state == "pending" else None
            if state in ("pending", "claimed"):
                entry["hook_alive"] = hook_alive(body.get("pid"))
        previous = requests.get(request_id)
        if previous is None or STATES.index(state) < STATES.index(previous["state"]):
            requests[request_id] = entry
    for request_id, entry in requests.items():
        entry["decision"] = decisions.get(request_id)
    ordered = sorted(requests.values(), key=lambda e: (e["created_at"] if e["created_at"] is not None else e["mtime_ms"]), reverse=True)
    pendings = [e for e in ordered if e["state"] == "pending"]
    if pendings:
        newest = pendings[0]
        out["candidate"] = {"request_id": newest["request_id"], "complete": newest["complete"], "body": newest.get("body")}
    for entry in ordered:
        entry.pop("body", None)
    out["requests"] = ordered
    print(json.dumps(out, separators=(",", ":")))


def read_decision_input():
    try:
        raw = sys.stdin.buffer.read(8192 + 1)
    except OSError:
        fail(EXIT_USAGE, "usage", "cannot read stdin")
    if len(raw) > 8192:
        fail(EXIT_USAGE, "usage", "the decision is too long")
    try:
        body = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        fail(EXIT_USAGE, "usage", "stdin is not JSON")
    if not isinstance(body, dict) or body.get("behavior") not in ("allow", "deny"):
        fail(EXIT_USAGE, "usage", "behavior must be allow or deny")
    phone = body.get("phone", "")
    at = body.get("at", "")
    if not isinstance(phone, str) or not isinstance(at, str) or len(phone) > 64 or len(at) > 40:
        fail(EXIT_USAGE, "usage", "phone and at must be short strings")
    return body["behavior"], phone, at


def cmd_decide(session, pane, claude_session, request_id):
    behavior, phone, at = read_decision_input()
    directory = request_dir(session, pane)
    if not own_directory(directory):
        fail(EXIT_GONE, "gone", "no requests for this pane")
    prune(directory, time.time())
    pending = os.path.join(directory, request_id + ".pending.json")
    claimed = os.path.join(directory, request_id + ".claimed.json")
    decision = os.path.join(directory, request_id + ".decision.json")
    tmp = os.path.join(directory, request_id + ".decision.tmp")
    if own_file(pending) is None:
        fail(EXIT_GONE, "gone", "already claimed, expired or consumed")
    try:
        raw, complete = read_bounded(pending)
    except FileNotFoundError:
        fail(EXIT_GONE, "gone", "already claimed, expired or consumed")
    except OSError as e:
        fail(EXIT_IO, "io", "cannot read the request: %s" % e.strerror)
    body = parse_request(raw) if complete else None
    if body is None:
        fail(EXIT_MALFORMED, "malformed", "the request cannot be read as a whole")
    if body["request_id"] != request_id or body["pane_id"] != pane or body["claude_session_id"] != claude_session or body.get("herdr_session") != session:
        fail(EXIT_MALFORMED, "malformed", "the request belongs to another pane or Claude session")
    if body["truncated"]:
        fail(EXIT_TRUNCATED, "truncated", "the tool input was too large to show in full")
    now = now_ms()
    if body["expires_at"] <= now:
        fail(EXIT_EXPIRED, "expired", "the request's window has passed")
    if hook_alive(body.get("pid")) is False:
        fail(EXIT_EXPIRED, "expired", "the hook that published the request has ended")
    try:
        os.rename(pending, claimed)
    except FileNotFoundError:
        fail(EXIT_GONE, "gone", "already claimed, expired or consumed")
    except OSError as e:
        fail(EXIT_IO, "io", "cannot claim the request: %s" % e.strerror)
    answer = {"v": VERSION, "request_id": request_id, "behavior": behavior, "phone": phone, "at": at, "decided_at": now_ms()}
    try:
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_CLOEXEC", 0), 0o600)
        try:
            os.write(fd, json.dumps(answer, separators=(",", ":")).encode("utf-8"))
        finally:
            os.close(fd)
        os.rename(tmp, decision)
    except OSError as e:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        fail(EXIT_IO, "io", "claimed the request but could not write the decision: %s" % e.strerror)
    print(json.dumps({"state": "decided", "request_id": request_id, "behavior": behavior}, separators=(",", ":")))


def main(argv):
    if len(argv) >= 2 and argv[1] == "list" and len(argv) == 4:
        session, pane = argv[2], argv[3]
        if not (SESSION.match(session) and PANE.match(pane)):
            fail(EXIT_USAGE, "usage", "invalid session or pane")
        return cmd_list(session, pane)
    if len(argv) >= 2 and argv[1] == "decide" and len(argv) == 6:
        session, pane, claude_session, request_id = argv[2:6]
        if not (SESSION.match(session) and PANE.match(pane) and CLAUDE_SESSION.match(claude_session) and REQUEST_ID.match(request_id)):
            fail(EXIT_USAGE, "usage", "invalid session, pane, Claude session or request id")
        return cmd_decide(session, pane, claude_session, request_id)
    fail(EXIT_USAGE, "usage", "paddock-decide.py list SESSION PANE | decide SESSION PANE CLAUDE_SESSION REQUEST_ID")


if __name__ == "__main__":
    main(sys.argv)
