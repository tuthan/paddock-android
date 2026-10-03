#!/usr/bin/env python3
"""Tests of host/paddock-claude-permission-hook.py and host/paddock-decide.py. Never touches the default herdr session.

    tools/test-permission-hook.py            publication, every rename and its loser, expiry and grace on a fake clock, the desktop
                                             answering first, truncation, orphans, the fifty-iteration race (about 60 seconds)
    tools/test-permission-hook.py --live     adds the hook's status read against the disposable session: PADDOCK_TEST_SOCKET must be
                                             .../sessions/paddock-test[-suffix]/herdr.sock, like the Kotlin integration tests

The scripts run as real processes in a throwaway XDG_RUNTIME_DIR and XDG_CONFIG_HOME; herdr is a unix socket server that answers
`agent.get` the way 0.9.1 does. The hook's wait loop is also driven in-process on a fake clock, so the lapse, the two-second grace
and the desktop-answered rule are tested at exact instants.
"""
import importlib.util
import json
import os
import re
import shutil
import signal
import socketserver
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
HOOK = os.path.join(HERE, "..", "host", "paddock-claude-permission-hook.py")
DECIDE = os.path.join(HERE, "..", "host", "paddock-decide.py")


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


H = load("permission_hook", HOOK)
D = load("decide", DECIDE)

LIVE = "--live" in sys.argv
if LIVE:
    sys.argv.remove("--live")

UUID4 = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\Z")
SESSION, PANE, CLAUDE = "paddock-test", "w1:p2", "claude-session-1"


def put(path, content, mode=0o600):
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC
    fd = os.open(path, flags, mode)
    with os.fdopen(fd, "wb" if isinstance(content, bytes) else "w") as f:
        f.write(content)


def hook_input(tool="Bash", tool_input=None, session=CLAUDE, event="PermissionRequest", mode="default"):
    return {"session_id": session, "cwd": "/tmp", "permission_mode": mode, "hook_event_name": event, "tool_name": tool,
            "tool_input": {"command": "ls -la", "description": "list"} if tool_input is None else tool_input}


class FakeHerdr:
    """A unix socket server that answers agent.get with a status the test sets and then closes the connection, like herdr 0.9.1."""

    def __init__(self, directory):
        self.path = os.path.join(directory, "herdr.sock")
        self.status = "working"
        self.requests = []
        owner = self

        class Handler(socketserver.StreamRequestHandler):
            def handle(self):
                for line in self.rfile:      # one request, one reply, then the connection ends
                    request = json.loads(line)
                    owner.requests.append(request)
                    status = owner.status
                    if status is None:
                        reply = {"id": request["id"], "error": {"code": "agent_not_found", "message": "no agent"}}
                    else:
                        reply = {"id": request["id"], "result": {"type": "agent_info", "agent": {"agent_status": status, "pane_id": request["params"]["target"]}}}
                    self.wfile.write((json.dumps(reply) + "\n").encode())
                    return

        self.server = socketserver.ThreadingUnixStreamServer(self.path, Handler)
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


class Sandbox:
    """A runtime directory, a configuration directory and a fake herdr, with the environment a hook would run in."""

    def __init__(self, window=60, config=True, herdr=True):
        self.dir = tempfile.mkdtemp(prefix="paddock-hook-test-")
        self.run, self.cfg = os.path.join(self.dir, "run"), os.path.join(self.dir, "cfg")
        os.mkdir(self.run, 0o700)
        os.makedirs(os.path.join(self.cfg, "paddock"))
        self.herdr = FakeHerdr(self.dir) if herdr else None
        if config:
            self.write_config("window_seconds = %d\n" % window)

    def write_config(self, text, mode=0o600):
        path = os.path.join(self.cfg, "paddock", "hook.toml")
        put(path, text, mode)
        os.chmod(path, mode)

    def env(self, **over):
        env = {"PATH": os.environ.get("PATH", "/usr/bin"), "HOME": self.dir, "XDG_RUNTIME_DIR": self.run, "XDG_CONFIG_HOME": self.cfg,
               "HERDR_ENV": "1", "HERDR_PANE_ID": PANE, "HERDR_SESSION": SESSION}
        if self.herdr:
            env["HERDR_SOCKET_PATH"] = self.herdr.path
        env.update(over)
        return {k: v for k, v in env.items() if v is not None}

    @property
    def requests_dir(self):
        return os.path.join(self.run, "paddock", SESSION, PANE)

    def hook(self, data=None, raw=None, **over):
        payload = raw if raw is not None else json.dumps(data if data is not None else hook_input()).encode()
        return subprocess.Popen([sys.executable, HOOK], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                env=self.env(**over), cwd=self.dir), payload

    def run_hook(self, data=None, raw=None, timeout=30, **over):
        proc, payload = self.hook(data, raw, **over)
        out, err = proc.communicate(payload, timeout=timeout)
        return proc.returncode, out.decode(), err.decode()

    def decide(self, args=None, stdin=None, **over):
        args = args if args is not None else ["decide", SESSION, PANE, CLAUDE, self.request_id()]
        body = stdin if stdin is not None else json.dumps({"behavior": "allow", "phone": "pixel", "at": "2026-10-03T00:00:00Z"})
        proc = subprocess.Popen([sys.executable, DECIDE, *args], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=self.env(**over))
        return proc, body.encode()

    def run_decide(self, args=None, stdin=None, **over):
        proc, body = self.decide(args, stdin, **over)
        out, err = proc.communicate(body, timeout=30)
        return proc.returncode, out.decode(), err.decode()

    def names(self):
        try:
            return sorted(os.listdir(self.requests_dir))
        except FileNotFoundError:
            return []

    def request_id(self, state="pending"):
        for name in self.names():
            if name.endswith(".%s.json" % state):
                return name.split(".")[0]
        raise AssertionError("no %s request in %s" % (state, self.names()))

    def wait_for(self, state, timeout=10):
        end = time.time() + timeout
        while time.time() < end:
            for name in self.names():
                if name.endswith(".%s.json" % state):
                    return name.split(".")[0]
            time.sleep(0.01)
        raise AssertionError("no %s request appeared: %s" % (state, self.names()))

    def read(self, name):
        with open(os.path.join(self.requests_dir, name)) as f:
            return json.load(f)

    def close(self):
        if self.herdr:
            self.herdr.close()
        shutil.rmtree(self.dir, True)


class SandboxCase(unittest.TestCase):
    def sandbox(self, **kw):
        sb = Sandbox(**kw)
        self.addCleanup(sb.close)
        return sb


class FakeTime:
    """A clock where sleeping advances time and events fire at their instants."""

    def __init__(self):
        self.t = 0.0
        self.events = []

    def at(self, seconds, action):
        self.events.append((seconds, action))
        self.events.sort(key=lambda e: e[0])

    def monotonic(self):
        return self.t

    def time(self):
        return 1_000_000.0 + self.t

    def sleep(self, seconds):
        target = self.t + seconds
        while self.events and self.events[0][0] <= target:
            instant, action = self.events.pop(0)
            self.t = max(self.t, instant)
            action()
        self.t = target


class ScriptedHerdr:
    """Answers each status() call with the next entry of a script (the last one repeats); records the fake time of each call."""

    def __init__(self, clock, script):
        self.clock, self.script, self.calls = clock, list(script), []

    def status(self):
        self.calls.append(self.clock.t)
        return self.script.pop(0) if len(self.script) > 1 else self.script[0]

    def close(self):
        pass


# ---------------------------------------------------------------------------------------------------------------------------
# The hook: when it does nothing at all


class QuietTests(SandboxCase):
    def assertQuiet(self, sb, result):
        rc, out, err = result
        self.assertEqual((rc, out), (0, ""))
        self.assertEqual(sb.names(), [])
        self.assertFalse(os.path.exists(os.path.join(sb.run, "paddock")), "no directory was made")

    def test_outside_herdr_nothing_happens(self):
        sb = self.sandbox()
        self.assertQuiet(sb, sb.run_hook(HERDR_ENV=None))
        self.assertQuiet(sb, sb.run_hook(HERDR_ENV="0"))
        self.assertQuiet(sb, sb.run_hook(HERDR_PANE_ID=None))

    def test_the_default_is_off(self):
        sb = self.sandbox(config=False)
        self.assertQuiet(sb, sb.run_hook())
        sb.write_config("")
        self.assertQuiet(sb, sb.run_hook())
        sb.write_config("window_seconds = 0\n")
        self.assertQuiet(sb, sb.run_hook())

    def test_a_bad_or_unsafe_configuration_means_off(self):
        sb = self.sandbox()
        for text in ('window_seconds = "60"\n', "window_seconds = -1\n", "window_seconds = 301\n", "window_seconds = true\n", "window_seconds = 1.5\n",
                     "window_seconds = 60\nallowed_tools = 3\n", "window_seconds = 60\nallowed_tools = [1]\n", "not toml at all [[[\n"):
            sb.write_config(text)
            self.assertQuiet(sb, sb.run_hook())
        sb.write_config("window_seconds = 60\n", 0o666)       # another user could widen who is asked
        self.assertQuiet(sb, sb.run_hook())
        sb.write_config("window_seconds = 60\n", 0o664)
        self.assertQuiet(sb, sb.run_hook())

    def test_other_events_and_tools_and_unusable_input(self):
        sb = self.sandbox(window=1)
        self.assertQuiet(sb, sb.run_hook(hook_input(event="PreToolUse")))
        self.assertQuiet(sb, sb.run_hook(raw=b"not json"))
        self.assertQuiet(sb, sb.run_hook(raw=b"[]"))
        self.assertQuiet(sb, sb.run_hook(raw=b""))
        bad = hook_input()
        del bad["session_id"]
        self.assertQuiet(sb, sb.run_hook(bad))
        sb.write_config('window_seconds = 1\nallowed_tools = ["Edit"]\n')
        self.assertQuiet(sb, sb.run_hook(hook_input(tool="Bash")))

    def test_a_hostile_pane_or_session_never_leaves_the_runtime_directory(self):
        sb = self.sandbox(window=1)
        for pane in ("../../etc", "a/b", "..", ".", "", "a" * 65, "w1 p2", "w1:p2\n"):
            self.assertQuiet(sb, sb.run_hook(HERDR_PANE_ID=pane))
        # a hostile session name is not trusted either: it falls back to the socket path and then to "default"
        rc, out, err = sb.run_hook(HERDR_SESSION="../x", HERDR_SOCKET_PATH=None)
        self.assertEqual((rc, out), (0, ""))
        self.assertTrue(os.path.isdir(os.path.join(sb.run, "paddock", "default", PANE)))
        self.assertFalse(os.path.exists(os.path.join(sb.run, "x")))


# ---------------------------------------------------------------------------------------------------------------------------
# The hook: what it publishes


class PublicationTests(SandboxCase):
    def test_the_request_is_published_complete_private_and_named_for_its_state(self):
        sb = self.sandbox(window=2)
        proc, payload = sb.hook(hook_input(tool_input={"command": "rm -rf build", "description": "clean"}, mode="acceptEdits"))
        proc.stdin.write(payload)
        proc.stdin.close()
        request_id = sb.wait_for("pending")
        self.assertRegex(request_id, UUID4)
        body = sb.read(request_id + ".pending.json")
        self.assertEqual(body["v"], 1)
        self.assertEqual((body["claude_session_id"], body["herdr_session"], body["pane_id"], body["tool_name"], body["permission_mode"], body["truncated"]),
                         (CLAUDE, SESSION, PANE, "Bash", "acceptEdits", False))
        self.assertEqual(body["tool_input"], {"command": "rm -rf build", "description": "clean"})
        self.assertEqual(body["expires_at"] - body["created_at"], 2000)
        self.assertLess(abs(body["created_at"] - time.time() * 1000), 5000)
        self.assertEqual(set(body), {"v", "request_id", "claude_session_id", "herdr_session", "pane_id", "tool_name", "tool_input", "permission_mode", "created_at", "expires_at", "truncated", "pid"})
        # AC-08.5: a 0600 file in a 0700 directory, at every level below the runtime root
        self.assertEqual(stat.S_IMODE(os.stat(os.path.join(sb.requests_dir, request_id + ".pending.json")).st_mode), 0o600)
        path = sb.run
        for part in ("paddock", SESSION, PANE):
            path = os.path.join(path, part)
            self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o700, path)
        self.assertEqual([n for n in sb.names() if n.endswith(".tmp")], [])
        proc.communicate(timeout=10)

    def test_the_directory_is_private_even_under_a_permissive_umask(self):
        sb = self.sandbox(window=1)
        proc, payload = sb.hook()
        proc.stdin.write(payload)
        proc.stdin.close()
        sb.wait_for("pending")
        self.assertEqual(stat.S_IMODE(os.stat(os.path.join(sb.run, "paddock")).st_mode), 0o700)
        proc.communicate(timeout=10)

    def test_a_directory_left_too_open_is_closed(self):
        sb = self.sandbox(window=1)
        os.makedirs(sb.requests_dir, mode=0o755)
        os.chmod(sb.requests_dir, 0o755)
        os.chmod(os.path.dirname(sb.requests_dir), 0o755)
        rc, out, _ = sb.run_hook()
        self.assertEqual((rc, out), (0, ""))
        self.assertEqual(stat.S_IMODE(os.stat(sb.requests_dir).st_mode), 0o700)

    def test_the_session_comes_from_herdr_session_then_the_socket_path_then_default(self):
        sb = self.sandbox(window=1)
        sb.run_hook(HERDR_SESSION="work-2")
        self.assertTrue(os.path.isdir(os.path.join(sb.run, "paddock", "work-2", PANE)))
        sb.run_hook(HERDR_SESSION=None, HERDR_SOCKET_PATH="/home/u/.config/herdr/sessions/from-socket/herdr.sock")
        self.assertTrue(os.path.isdir(os.path.join(sb.run, "paddock", "from-socket", PANE)))
        sb.run_hook(HERDR_SESSION=None, HERDR_SOCKET_PATH="/home/u/.config/herdr/herdr.sock")
        self.assertTrue(os.path.isdir(os.path.join(sb.run, "paddock", "default", PANE)))

    def test_oversized_tool_input_is_cut_and_marked_so_it_is_never_answerable(self):
        sb = self.sandbox(window=2)
        big = "x" * (H.MAX_INPUT * 2)
        proc, payload = sb.hook(hook_input(tool_input={"content": big}))
        proc.stdin.write(payload)
        proc.stdin.close()
        request_id = sb.wait_for("pending")
        path = os.path.join(sb.requests_dir, request_id + ".pending.json")
        self.assertLess(os.path.getsize(path), D.READ_LIMIT, "a truncated request still fits the phone's bounded read")
        body = sb.read(request_id + ".pending.json")
        self.assertTrue(body["truncated"])
        self.assertIsInstance(body["tool_input"], str)
        self.assertEqual(len(body["tool_input"]), H.MAX_INPUT)
        rc, out, err = sb.run_decide(["decide", SESSION, PANE, CLAUDE, request_id])
        self.assertEqual(rc, D.EXIT_TRUNCATED, err)
        self.assertIn(request_id + ".pending.json", sb.names(), "a refusal leaves the request pending")
        proc.communicate(timeout=10)

    def test_input_just_under_the_limit_is_kept_whole(self):
        sb = self.sandbox(window=2)
        proc, payload = sb.hook(hook_input(tool_input={"content": "y" * (H.MAX_INPUT - 100)}))
        proc.stdin.write(payload)
        proc.stdin.close()
        request_id = sb.wait_for("pending")
        body = sb.read(request_id + ".pending.json")
        self.assertFalse(body["truncated"])
        self.assertEqual(len(body["tool_input"]["content"]), H.MAX_INPUT - 100)
        self.assertLess(os.path.getsize(os.path.join(sb.requests_dir, request_id + ".pending.json")), D.READ_LIMIT)
        proc.communicate(timeout=10)

    def test_non_ascii_and_odd_input_survives_the_round_trip(self):
        sb = self.sandbox(window=2)
        text = "echo 'héllo ☃ \U0001f600' && printf '\\n'"
        proc, payload = sb.hook(raw=json.dumps(hook_input(tool_input={"command": text})).encode())
        proc.stdin.write(payload)
        proc.stdin.close()
        request_id = sb.wait_for("pending")
        self.assertEqual(sb.read(request_id + ".pending.json")["tool_input"]["command"], text)
        proc.communicate(timeout=10)

    def test_a_request_is_never_visible_half_written(self):
        sb = self.sandbox(window=1)
        stop, bad, seen = threading.Event(), [], []

        def poll():
            while not stop.is_set():
                for name in sb.names():
                    if name.endswith(".pending.json"):
                        try:
                            body = sb.read(name)
                            seen.append(len(json.dumps(body)))
                        except FileNotFoundError:
                            pass
                        except ValueError as e:
                            bad.append((name, str(e)))

        t = threading.Thread(target=poll)
        t.start()
        try:
            for _ in range(8):
                sb.run_hook(hook_input(tool_input={"content": "z" * 200_000}))
        finally:
            stop.set()
            t.join()
        self.assertTrue(seen, "the poller saw requests")
        self.assertEqual(bad, [])

    def test_the_hook_does_not_report_to_herdr_and_asks_only_for_a_status(self):
        sb = self.sandbox(window=1)
        sb.run_hook()
        methods = {r["method"] for r in sb.herdr.requests}
        self.assertTrue(methods <= {"agent.get"}, methods)


# ---------------------------------------------------------------------------------------------------------------------------
# The hook: the real thing, with real timing


class WindowTests(SandboxCase):
    def test_a_lapsed_window_expires_the_request_and_prints_nothing(self):
        sb = self.sandbox(window=1)
        started = time.time()
        rc, out, err = sb.run_hook()
        took = time.time() - started
        self.assertEqual((rc, out), (0, ""))
        self.assertGreaterEqual(took, 1.0)
        self.assertLess(took, 3.0)
        names = sb.names()
        self.assertEqual(len(names), 1, names)
        self.assertTrue(names[0].endswith(".expired.json"), names)

    def test_a_phone_yes_is_printed_as_claude_codes_decision_and_consumed(self):
        sb = self.sandbox(window=10)
        proc, payload = sb.hook()
        proc.stdin.write(payload)
        proc.stdin.close()
        request_id = sb.wait_for("pending")
        rc, out, err = sb.run_decide(["decide", SESSION, PANE, CLAUDE, request_id])
        self.assertEqual(rc, 0, err)
        self.assertEqual(json.loads(out), {"state": "decided", "request_id": request_id, "behavior": "allow"})
        stdout, _ = proc.communicate(timeout=10)
        self.assertEqual(proc.returncode, 0)
        self.assertEqual(json.loads(stdout), {"hookSpecificOutput": {"hookEventName": "PermissionRequest", "decision": {"behavior": "allow"}}})
        self.assertEqual(sb.names(), [request_id + ".consumed.json", request_id + ".decision.json"])

    def test_a_phone_no_is_a_deny_and_nothing_else(self):
        sb = self.sandbox(window=10)
        proc, payload = sb.hook()
        proc.stdin.write(payload)
        proc.stdin.close()
        request_id = sb.wait_for("pending")
        rc, _, err = sb.run_decide(["decide", SESSION, PANE, CLAUDE, request_id], json.dumps({"behavior": "deny", "phone": "pixel", "at": "x"}))
        self.assertEqual(rc, 0, err)
        stdout, _ = proc.communicate(timeout=10)
        decision = json.loads(stdout)["hookSpecificOutput"]
        self.assertEqual(decision, {"hookEventName": "PermissionRequest", "decision": {"behavior": "deny"}})
        self.assertNotIn(b"updatedInput", stdout)
        self.assertNotIn(b"updatedPermissions", stdout)

    def test_a_decision_for_another_request_is_not_honoured(self):
        sb = self.sandbox(window=2)
        proc, payload = sb.hook()
        proc.stdin.write(payload)
        proc.stdin.close()
        request_id = sb.wait_for("pending")
        other = "11111111-1111-4111-8111-111111111111"
        put(os.path.join(sb.requests_dir, request_id + ".decision.json"), json.dumps({"request_id": other, "behavior": "allow"}))
        stdout, _ = proc.communicate(timeout=10)
        self.assertEqual(stdout, b"")
        self.assertIn(request_id + ".expired.json", sb.names())

    def test_a_garbled_or_partial_decision_is_not_honoured(self):
        sb = self.sandbox(window=1)
        proc, payload = sb.hook()
        proc.stdin.write(payload)
        proc.stdin.close()
        request_id = sb.wait_for("pending")
        put(os.path.join(sb.requests_dir, request_id + ".decision.json"), '{"request_id": "%s", "behav' % request_id)
        stdout, _ = proc.communicate(timeout=10)
        self.assertEqual(stdout, b"")

    def test_a_termination_signal_marks_the_request_expired_and_prints_nothing(self):
        for name in ("SIGTERM", "SIGHUP"):
            sb = self.sandbox(window=30)
            proc, payload = sb.hook()
            proc.stdin.write(payload)
            proc.stdin.close()
            request_id = sb.wait_for("pending")
            time.sleep(0.3)
            proc.send_signal(getattr(signal, name))
            stdout, _ = proc.communicate(timeout=10)
            self.assertEqual((proc.returncode, stdout), (0, b""), name)
            self.assertEqual(sb.names(), [request_id + ".expired.json"], name)

    def test_a_decision_the_hook_cannot_deliver_is_not_left_looking_consumed(self):
        sb = self.sandbox(window=10)
        proc, payload = sb.hook()
        proc.stdin.write(payload)
        proc.stdin.close()
        request_id = sb.wait_for("pending")
        proc.stdout.close()      # Claude Code went away: printing the decision fails
        rc, _, err = sb.run_decide(["decide", SESSION, PANE, CLAUDE, request_id])
        self.assertEqual(rc, 0, err)
        proc.wait(timeout=10)
        proc.stderr.close()
        self.assertEqual(proc.returncode, 0)
        self.assertEqual(sb.names(), [request_id + ".decision.json", request_id + ".expired.json"])

    def test_the_desktop_answering_first_is_noticed_and_a_late_phone_decision_is_refused(self):
        sb = self.sandbox(window=20)
        sb.herdr.status = "blocked"
        proc, payload = sb.hook()
        proc.stdin.write(payload)
        proc.stdin.close()
        request_id = sb.wait_for("pending")
        time.sleep(0.8)                      # the hook has seen the pane blocked
        sb.herdr.status = "working"          # the desktop answered; the agent moves on
        stdout, _ = proc.communicate(timeout=10)
        self.assertEqual((proc.returncode, stdout), (0, b""))
        self.assertEqual(sb.names(), [request_id + ".expired.json"])
        rc, _, err = sb.run_decide(["decide", SESSION, PANE, CLAUDE, request_id])
        self.assertEqual(rc, D.EXIT_GONE, err)

    def test_a_pane_that_never_showed_blocked_waits_out_the_whole_window(self):
        sb = self.sandbox(window=2)
        sb.herdr.status = "idle"
        started = time.time()
        rc, out, _ = sb.run_hook()
        self.assertEqual((rc, out), (0, ""))
        self.assertGreaterEqual(time.time() - started, 2.0)

    def test_herdr_not_answering_never_ends_the_wait_early(self):
        sb = self.sandbox(window=2, herdr=False)
        started = time.time()
        rc, out, _ = sb.run_hook(HERDR_SOCKET_PATH=os.path.join(sb.dir, "nothing.sock"))
        self.assertEqual((rc, out), (0, ""))
        self.assertGreaterEqual(time.time() - started, 2.0)

    def test_old_request_files_are_removed_when_a_new_request_is_made(self):
        sb = self.sandbox(window=1)
        os.makedirs(sb.requests_dir, mode=0o700)
        old = "22222222-2222-4222-8222-222222222222"
        keep = "33333333-3333-4333-8333-333333333333"
        for name in (old + ".expired.json", old + ".decision.json", old + ".decision.tmp", keep + ".expired.json", "notes.txt"):
            put(os.path.join(sb.requests_dir, name), "{}")
        for name in (old + ".expired.json", old + ".decision.json", old + ".decision.tmp", "notes.txt"):
            os.utime(os.path.join(sb.requests_dir, name), (time.time() - 7200, time.time() - 7200))
        sb.run_hook()
        names = sb.names()
        self.assertNotIn(old + ".expired.json", names)
        self.assertNotIn(old + ".decision.json", names)
        self.assertNotIn(old + ".decision.tmp", names)
        self.assertIn(keep + ".expired.json", names)
        self.assertIn("notes.txt", names, "a file that is not a request is never touched")


# ---------------------------------------------------------------------------------------------------------------------------
# The hook's wait loop on a fake clock: expiry, grace, and the desktop answering first


class FakeClockTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="paddock-hook-fake-")
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.id = "44444444-4444-4444-8444-444444444444"
        self.request = H.Request(self.dir, self.id)
        put(self.request.pending, json.dumps({"v": 1}))
        self.clock = FakeTime()
        real = H.time
        H.time = self.clock
        self.addCleanup(setattr, H, "time", real)

    def claim(self, behavior=None, decide_at=None):
        """The phone claims now and, if [decide_at] is given, publishes its decision then."""
        def claim():
            os.rename(self.request.pending, self.request.claimed)
        def decide():
            put(self.request.decision, json.dumps({"request_id": self.id, "behavior": behavior}))
        return claim, decide

    def files(self):
        return sorted(n.split(".", 1)[1] for n in os.listdir(self.dir))

    def wait(self, window, statuses=("working",)):
        herdr = ScriptedHerdr(self.clock, statuses)
        return H.wait(self.request, herdr, window), herdr

    def test_nobody_answers_the_request_expires_exactly_at_the_window(self):
        result, _ = self.wait(30)
        self.assertIsNone(result)
        self.assertGreaterEqual(self.clock.t, 30)
        self.assertLess(self.clock.t, 30.5)
        self.assertEqual(self.files(), ["expired.json"])

    def test_a_decision_in_time_is_honoured_without_waiting_for_the_window(self):
        claim, decide = self.claim("deny")
        self.clock.at(5.0, claim)
        self.clock.at(5.1, decide)
        result, _ = self.wait(30)
        self.assertEqual(result, "deny")
        self.assertLess(self.clock.t, 6)

    def test_a_claim_just_before_the_lapse_with_its_decision_inside_the_grace_is_honoured(self):
        claim, decide = self.claim("allow")
        self.clock.at(29.9, claim)
        self.clock.at(31.0, decide)      # after the window, inside the 2 s grace
        result, _ = self.wait(30)
        self.assertEqual(result, "allow")
        self.assertLess(self.clock.t, 31.5)

    def test_a_claim_with_no_decision_by_the_end_of_the_grace_is_expired(self):
        claim, _ = self.claim()
        self.clock.at(29.9, claim)
        result, _ = self.wait(30)
        self.assertIsNone(result)
        self.assertGreaterEqual(self.clock.t, 32.0)
        self.assertEqual(self.files(), ["expired.json"], "the claimed file was the one expired")

    def test_a_decision_after_the_grace_is_not_honoured_and_changes_nothing(self):
        claim, decide = self.claim("allow")
        self.clock.at(29.9, claim)
        self.clock.at(32.5, decide)
        result, _ = self.wait(30)
        self.assertIsNone(result)
        self.clock.sleep(5)              # the decision lands; nothing reads it
        self.assertEqual(self.files(), ["decision.json", "expired.json"], "an orphan, which `decide` removes after an hour")

    def test_a_claim_after_the_lapse_loses(self):
        self.wait(30)
        with self.assertRaises(FileNotFoundError):
            os.rename(self.request.pending, self.request.claimed)

    def test_the_desktop_answering_first_expires_the_request_after_two_status_reads(self):
        result, herdr = self.wait(60, ["working", "blocked", "blocked", "working", "working"])
        self.assertIsNone(result)
        self.assertEqual(self.files(), ["expired.json"])
        self.assertLess(self.clock.t, 5, "well before the window")
        self.assertEqual(len(herdr.calls), 5)

    def test_a_request_expired_by_a_newer_one_ends_the_wait_at_once(self):
        self.clock.at(3.0, lambda: os.rename(self.request.pending, os.path.join(self.dir, self.id + ".expired.json")))
        result, _ = self.wait(60, ["blocked"])
        self.assertIsNone(result)
        self.assertLess(self.clock.t, 4, "well before the window")
        self.assertEqual(self.files(), ["expired.json"])

    def test_one_stray_non_blocked_read_does_not_end_the_wait(self):
        result, _ = self.wait(10, ["blocked", "working", "blocked", "blocked", "working", "blocked"])
        self.assertIsNone(result)
        self.assertGreaterEqual(self.clock.t, 10, "the streak was reset by the blocked read in between")

    def test_a_pane_never_seen_blocked_is_never_expired_early(self):
        result, _ = self.wait(10, ["working", "idle", "working", "idle"])
        self.assertIsNone(result)
        self.assertGreaterEqual(self.clock.t, 10)

    def test_unknown_and_unreachable_reads_say_nothing(self):
        result, _ = self.wait(10, ["blocked", None, "unknown", None, "unknown", "blocked"])
        self.assertGreaterEqual(self.clock.t, 10)

    def test_a_claim_in_flight_when_the_desktop_answered_is_expired_and_its_decision_is_never_honoured(self):
        claim, decide = self.claim("allow")
        self.clock.at(1.0, claim)
        self.clock.at(10.0, decide)
        result, _ = self.wait(60, ["blocked", "blocked", "blocked", "working", "working"])
        self.assertIsNone(result)
        self.assertEqual(self.files(), ["expired.json"])
        self.assertLess(self.clock.t, 5)

    def test_a_decision_wins_over_the_desktop_noticed_at_the_same_instant(self):
        claim, decide = self.claim("deny")
        self.clock.at(0.0, claim)
        self.clock.at(0.0, decide)
        result, _ = self.wait(30, ["working"])
        self.assertEqual(result, "deny")

    def test_status_is_read_about_twice_a_second_and_not_faster(self):
        _, herdr = self.wait(10, ["working"])
        self.assertTrue(15 <= len(herdr.calls) <= 22, len(herdr.calls))
        gaps = [b - a for a, b in zip(herdr.calls, herdr.calls[1:])]
        self.assertGreaterEqual(min(gaps), 0.5)


# ---------------------------------------------------------------------------------------------------------------------------
# The decision writer


def pending_body(rid, **over):
    now = int(time.time() * 1000)
    body = {"v": 1, "request_id": rid, "claude_session_id": CLAUDE, "herdr_session": SESSION, "pane_id": PANE, "tool_name": "Bash",
            "tool_input": {"command": "ls"}, "permission_mode": "default", "created_at": now, "expires_at": now + 60_000, "truncated": False}
    body.update(over)
    return body


def make_request(sb, rid=None, state="pending", pid=None, **over):
    request_id = rid or str(uuid.uuid4())
    if not os.path.isdir(sb.requests_dir):
        os.makedirs(sb.requests_dir, mode=0o700)
        os.chmod(sb.requests_dir, 0o700)
    body = pending_body(request_id, **over)
    if pid is not None:
        body["pid"] = pid
    put(os.path.join(sb.requests_dir, "%s.%s.json" % (request_id, state)), json.dumps(body))
    return request_id


class SupersedeTests(SandboxCase):
    """The desktop answers a request and the next prompt follows at once: herdr's status goes blocked, working, blocked, and the older hook
    never sees two reads away from blocked. The newer request expires it (found by the scenario suite against real Claude Code)."""

    def start(self, sb, command):
        proc, payload = sb.hook(hook_input(tool_input={"command": command}))
        proc.stdin.write(payload)
        proc.stdin.close()
        return proc

    def test_a_newer_request_of_the_same_session_expires_the_older_pending_one_and_its_hook_leaves(self):
        sb = self.sandbox(window=30)
        first = self.start(sb, "echo one")
        old = sb.wait_for("pending")
        second = self.start(sb, "echo two")
        end = time.time() + 10
        new = None
        while time.time() < end and not new:
            new = next((n.split(".")[0] for n in sb.names() if n.endswith(".pending.json") and not n.startswith(old)), None)
            time.sleep(0.01)
        self.assertIsNotNone(new, sb.names())
        self.assertTrue(os.path.exists(os.path.join(sb.requests_dir, old + ".expired.json")), sb.names())
        self.assertFalse(os.path.exists(os.path.join(sb.requests_dir, old + ".pending.json")))
        started = time.time()
        out, _ = first.communicate(timeout=5)
        self.assertEqual((first.returncode, out), (0, b""), "the older hook printed nothing and left")
        self.assertLess(time.time() - started, 2, "it did not wait out its window")
        rc, out, err = sb.run_decide(["decide", SESSION, PANE, CLAUDE, new])
        self.assertEqual(rc, 0, err)
        stdout, _ = second.communicate(timeout=10)
        self.assertEqual(json.loads(stdout)["hookSpecificOutput"]["decision"]["behavior"], "allow")
        rc, out, err = sb.run_decide(["decide", SESSION, PANE, CLAUDE, old])
        self.assertEqual(rc, 3, "the expired request is gone for the writer: %s" % err)

    def test_the_expired_older_request_never_comes_back_as_the_candidate(self):
        sb = self.sandbox(window=30)
        first = self.start(sb, "echo one")
        old = sb.wait_for("pending")
        second = self.start(sb, "echo two")
        deadline = time.time() + 10
        new = None
        while time.time() < deadline and not new:
            new = next((n.split(".")[0] for n in sb.names() if n.endswith(".pending.json") and not n.startswith(old)), None)
            time.sleep(0.01)
        self.assertIsNotNone(new, sb.names())
        rc, out, err = sb.run_decide(["decide", SESSION, PANE, CLAUDE, new])
        self.assertEqual(rc, 0, err)
        second.communicate(timeout=10)
        first.communicate(timeout=5)
        listing = json.loads(subprocess.run([sys.executable, DECIDE, "list", SESSION, PANE], env=sb.env(), capture_output=True, text=True).stdout)
        self.assertIsNone(listing["candidate"], "nothing is pending: the older request did not take the newest's place")

    def test_a_pending_request_of_another_claude_session_is_left_alone(self):
        sb = self.sandbox(window=1)
        other = make_request(sb, claude_session_id="another-session", pid=os.getpid())
        rc, out, err = sb.run_hook()
        self.assertEqual((rc, out), (0, ""))
        self.assertTrue(os.path.exists(os.path.join(sb.requests_dir, other + ".pending.json")), sb.names())

    def test_claimed_consumed_and_expired_requests_and_unreadable_files_are_not_touched(self):
        sb = self.sandbox(window=1)
        claimed = make_request(sb, state="claimed")
        consumed = make_request(sb, state="consumed")
        garbage = str(uuid.uuid4())
        put(os.path.join(sb.requests_dir, garbage + ".pending.json"), "not json at all")
        huge = str(uuid.uuid4())
        put(os.path.join(sb.requests_dir, huge + ".pending.json"), json.dumps(pending_body(huge, tool_input={"command": "x" * (310 * 1024)})))
        sb.run_hook()
        names = sb.names()
        for rid, state in ((claimed, "claimed"), (consumed, "consumed"), (garbage, "pending"), (huge, "pending")):
            self.assertIn("%s.%s.json" % (rid, state), names)

    def test_the_hook_only_ever_renames_for_this(self):
        # the older request's content is left exactly as it was written: only its name changed
        sb = self.sandbox(window=1)
        old = make_request(sb)
        def content(state):
            with open(os.path.join(sb.requests_dir, "%s.%s.json" % (old, state)), "rb") as f:
                return f.read()
        before = content("pending")
        sb.run_hook()
        self.assertEqual(content("expired"), before)


class DecideTests(SandboxCase):
    def decide(self, sb, request_id, behavior="allow", **kw):
        return sb.run_decide(["decide", SESSION, PANE, kw.get("claude", CLAUDE), request_id], json.dumps({"behavior": behavior, "phone": "pixel 9", "at": "2026-10-03T01:02:03Z"}))

    def test_a_decision_claims_the_request_and_publishes_one_private_decision_file(self):
        sb = self.sandbox(herdr=False)
        rid = make_request(sb)
        rc, out, err = self.decide(sb, rid)
        self.assertEqual((rc, err), (0, ""))
        self.assertEqual(json.loads(out), {"state": "decided", "request_id": rid, "behavior": "allow"})
        self.assertEqual(sb.names(), [rid + ".claimed.json", rid + ".decision.json"])
        decision = sb.read(rid + ".decision.json")
        self.assertEqual({k: decision[k] for k in ("v", "request_id", "behavior", "phone", "at")}, {"v": 1, "request_id": rid, "behavior": "allow", "phone": "pixel 9", "at": "2026-10-03T01:02:03Z"})
        self.assertEqual(stat.S_IMODE(os.stat(os.path.join(sb.requests_dir, rid + ".decision.json")).st_mode), 0o600)
        self.assertEqual([n for n in sb.names() if n.endswith(".tmp")], [])

    def test_a_second_tap_is_refused_and_the_first_decision_stands(self):
        sb = self.sandbox(herdr=False)
        rid = make_request(sb)
        self.assertEqual(self.decide(sb, rid, "allow")[0], 0)
        rc, out, err = self.decide(sb, rid, "deny")
        self.assertEqual((rc, out), (D.EXIT_GONE, ""))
        self.assertIn("already claimed", err)
        self.assertEqual(sb.read(rid + ".decision.json")["behavior"], "allow")

    def test_every_settled_state_is_gone(self):
        sb = self.sandbox(herdr=False)
        for i, state in enumerate(("claimed", "consumed", "expired")):
            rid = make_request(sb, "66666666-6666-4666-8666-66666666666%d" % i, state=state)
            self.assertEqual(self.decide(sb, rid)[0], D.EXIT_GONE, state)
        self.assertEqual(self.decide(sb, "77777777-7777-4777-8777-777777777777")[0], D.EXIT_GONE, "no such request")
        self.assertEqual(sb.run_decide(["decide", SESSION, "w9:p9", CLAUDE, "77777777-7777-4777-8777-777777777777"])[0], D.EXIT_GONE, "no such pane")

    def test_an_expired_request_is_refused_and_left_pending(self):
        sb = self.sandbox(herdr=False)
        now = int(time.time() * 1000)
        rid = make_request(sb, created_at=now - 70_000, expires_at=now - 10_000)
        rc, _, err = self.decide(sb, rid)
        self.assertEqual(rc, D.EXIT_EXPIRED, err)
        self.assertEqual(sb.names(), [rid + ".pending.json"])

    def test_the_last_millisecond_of_the_window_is_the_hosts_to_judge(self):
        sb = self.sandbox(herdr=False)
        now = int(time.time() * 1000)
        self.assertEqual(self.decide(sb, make_request(sb, "88888888-8888-4888-8888-888888888881", expires_at=now + 5_000))[0], 0)
        self.assertEqual(self.decide(sb, make_request(sb, "88888888-8888-4888-8888-888888888882", expires_at=now))[0], D.EXIT_EXPIRED)

    def test_a_truncated_request_is_refused_and_left_pending(self):
        sb = self.sandbox(herdr=False)
        rid = make_request(sb, truncated=True)
        rc, _, err = self.decide(sb, rid)
        self.assertEqual(rc, D.EXIT_TRUNCATED, err)
        self.assertEqual(sb.names(), [rid + ".pending.json"])

    def test_malformed_requests_are_refused_and_left_pending(self):
        sb = self.sandbox(herdr=False)
        os.makedirs(sb.requests_dir, mode=0o700)
        os.chmod(sb.requests_dir, 0o700)

        def body(rid, **over):
            return json.dumps(pending_body(rid, **over)).encode()

        def fake_id(n):
            return "99999999-9999-4999-8999-99999999999%d" % n

        cases = [
            ("garbage", b"not json"),
            ("list", b"[]"),
            ("empty", b""),
            ("cut", body(fake_id(3))[:60]),
            ("oversize", b" " * (D.READ_LIMIT + 10)),
            ("wrong version", body(fake_id(5), v=2)),
            ("no truncated flag", json.dumps({k: v for k, v in pending_body(fake_id(6)).items() if k != "truncated"}).encode()),
            ("string expiry", body(fake_id(7), expires_at="soon")),
            ("bool expiry", body(fake_id(8), expires_at=True)),
        ]
        for i, (label, content) in enumerate(cases):
            rid = fake_id(i)
            put(os.path.join(sb.requests_dir, rid + ".pending.json"), content)
            rc, _, err = self.decide(sb, rid)
            self.assertEqual(rc, D.EXIT_MALFORMED, "%s: %s" % (label, err))
            self.assertIn(rid + ".pending.json", sb.names(), label)
            self.assertNotIn(rid + ".claimed.json", sb.names(), label)

    def test_a_request_for_another_claude_session_or_pane_is_refused(self):
        sb = self.sandbox(herdr=False)
        rid = make_request(sb, claude_session_id="someone-elses")
        rc, _, err = self.decide(sb, rid)
        self.assertEqual(rc, D.EXIT_MALFORMED, err)
        rid2 = make_request(sb, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa2", pane_id="w1:p9")
        self.assertEqual(self.decide(sb, rid2)[0], D.EXIT_MALFORMED)
        rid3 = make_request(sb, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa3", request_id="aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa9")
        self.assertEqual(self.decide(sb, rid3)[0], D.EXIT_MALFORMED)
        rid4 = make_request(sb, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaa4", herdr_session="other")
        self.assertEqual(self.decide(sb, rid4)[0], D.EXIT_MALFORMED)
        self.assertEqual(sb.names().count(rid + ".pending.json"), 1)

    def test_usage_errors(self):
        sb = self.sandbox(herdr=False)
        rid = make_request(sb)
        good = json.dumps({"behavior": "allow", "phone": "p", "at": "t"})
        for args in ([], ["decide"], ["list"], ["list", SESSION], ["decide", SESSION, PANE, CLAUDE], ["frobnicate", SESSION, PANE],
                     ["decide", "../x", PANE, CLAUDE, rid], ["decide", SESSION, "../x", CLAUDE, rid], ["decide", SESSION, PANE, "a b", rid],
                     ["decide", SESSION, PANE, CLAUDE, "not-a-uuid"], ["decide", SESSION, PANE, CLAUDE, rid.upper()], ["decide", SESSION, PANE, CLAUDE, "../" + rid]):
            rc, out, err = sb.run_decide(args, good)
            self.assertEqual((rc, out), (D.EXIT_USAGE, ""), args)
            self.assertTrue(err.startswith("paddock-decide: usage:"), (args, err))
        for stdin in ("", "nope", "[]", '{"behavior":"maybe"}', '{"behavior":"allow","phone":5}', '{"behavior":"allow","phone":"%s"}' % ("p" * 65),
                      '{"behavior":"allow","at":"%s"}' % ("a" * 41), '{"behavior":"allow","pad":"%s"}' % ("x" * 9000), '{"behavior":"allow\\u0000"}'):
            rc, _, err = self.decide_raw(sb, rid, stdin)
            self.assertEqual(rc, D.EXIT_USAGE, (stdin[:30], err))
        self.assertEqual(sb.names(), [rid + ".pending.json"], "bad input never claims")

    def decide_raw(self, sb, rid, stdin):
        return sb.run_decide(["decide", SESSION, PANE, CLAUDE, rid], stdin)

    def test_extra_fields_in_the_decision_are_ignored_and_never_copied(self):
        sb = self.sandbox(herdr=False)
        rid = make_request(sb)
        rc, _, err = self.decide_raw(sb, rid, json.dumps({"behavior": "deny", "phone": "p", "at": "t", "updatedInput": {"command": "rm -rf /"}, "updatedPermissions": {"x": 1}}))
        self.assertEqual(rc, 0, err)
        with open(os.path.join(sb.requests_dir, rid + ".decision.json")) as f:
            text = f.read()
        self.assertNotIn("updatedInput", text)
        self.assertNotIn("rm -rf", text)

    def test_a_decision_directory_that_is_not_private_is_not_trusted(self):
        sb = self.sandbox(herdr=False)
        rid = make_request(sb)
        os.chmod(sb.requests_dir, 0o755)
        self.assertEqual(self.decide(sb, rid)[0], D.EXIT_GONE)
        self.assertEqual(sb.names(), [rid + ".pending.json"])

    def test_a_symlinked_request_is_never_followed(self):
        sb = self.sandbox(herdr=False)
        os.makedirs(sb.requests_dir, mode=0o700)
        os.chmod(sb.requests_dir, 0o700)
        rid = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbb1"
        target = os.path.join(sb.dir, "elsewhere.json")
        put(target, json.dumps(pending_body(rid)))
        os.symlink(target, os.path.join(sb.requests_dir, rid + ".pending.json"))
        self.assertEqual(self.decide(sb, rid)[0], D.EXIT_GONE)

    def test_orphans_and_old_files_are_deleted_by_the_next_decision_and_nothing_else_is(self):
        sb = self.sandbox(herdr=False)
        rid = make_request(sb)
        old, fresh = "cccccccc-cccc-4ccc-8ccc-ccccccccccc1", "cccccccc-cccc-4ccc-8ccc-ccccccccccc2"
        for name in (old + ".expired.json", old + ".decision.json", old + ".decision.tmp", fresh + ".expired.json", fresh + ".decision.json", "keep.txt"):
            put(os.path.join(sb.requests_dir, name), "{}")
        for name in (old + ".expired.json", old + ".decision.json", old + ".decision.tmp", "keep.txt"):
            os.utime(os.path.join(sb.requests_dir, name), (time.time() - 7200, time.time() - 7200))
        self.assertEqual(self.decide(sb, rid)[0], 0)
        names = sb.names()
        for gone in (old + ".expired.json", old + ".decision.json", old + ".decision.tmp"):
            self.assertNotIn(gone, names)
        for kept in (fresh + ".expired.json", fresh + ".decision.json", "keep.txt", rid + ".claimed.json", rid + ".decision.json"):
            self.assertIn(kept, names)


class DeadHookTests(SandboxCase):
    def dead_pid(self):
        child = subprocess.Popen(["true"])
        child.wait()
        return child.pid

    def test_a_request_whose_hook_has_ended_is_refused_and_left_pending(self):
        sb = self.sandbox(herdr=False)
        rid = make_request(sb, pid=self.dead_pid())
        rc, _, err = sb.run_decide(["decide", SESSION, PANE, CLAUDE, rid])
        self.assertEqual(rc, D.EXIT_EXPIRED, err)
        self.assertIn("hook", err)
        self.assertEqual(sb.names(), [rid + ".pending.json"])

    def test_a_pid_that_now_belongs_to_another_program_is_not_the_hook(self):
        sb = self.sandbox(herdr=False)
        rid = make_request(sb, pid=os.getpid())         # alive, but this is the test runner, not a hook
        self.assertEqual(sb.run_decide(["decide", SESSION, PANE, CLAUDE, rid])[0], D.EXIT_EXPIRED)

    def test_a_request_with_no_pid_is_judged_by_its_deadline_alone(self):
        sb = self.sandbox(herdr=False)
        rid = make_request(sb)
        self.assertEqual(sb.run_decide(["decide", SESSION, PANE, CLAUDE, rid])[0], 0)

    def test_the_listing_says_whether_the_hook_is_alive(self):
        sb = self.sandbox(herdr=False)
        gone = make_request(sb, "ffffffff-ffff-4fff-8fff-fffffffffff8", pid=self.dead_pid())
        unknown = make_request(sb, "ffffffff-ffff-4fff-8fff-fffffffffff9")
        settled = make_request(sb, "ffffffff-ffff-4fff-8fff-fffffffffffa", state="expired", pid=self.dead_pid())
        rc, out, err = sb.run_decide(["list", SESSION, PANE], "")
        self.assertEqual((rc, err), (0, ""))
        by_id = {r["request_id"]: r for r in json.loads(out)["requests"]}
        self.assertIs(by_id[gone]["hook_alive"], False)
        self.assertIsNone(by_id[unknown]["hook_alive"])
        self.assertIsNone(by_id[settled]["hook_alive"], "only a request that still waits has a hook to ask about")

    def test_a_hook_that_was_killed_is_seen_as_gone_and_a_live_one_as_alive(self):
        sb = self.sandbox(window=30)
        proc, payload = sb.hook()
        proc.stdin.write(payload)
        proc.stdin.close()
        rid = sb.wait_for("pending")
        body = sb.read(rid + ".pending.json")
        self.assertEqual(body["pid"], proc.pid)
        rc, out, _ = sb.run_decide(["list", SESSION, PANE], "")
        self.assertIs(json.loads(out)["requests"][0]["hook_alive"], True)
        proc.kill()
        proc.communicate()
        rc, out, _ = sb.run_decide(["list", SESSION, PANE], "")
        self.assertIs(json.loads(out)["requests"][0]["hook_alive"], False)
        self.assertEqual(json.loads(out)["requests"][0]["state"], "pending", "a killed hook cannot mark its own request")
        self.assertEqual(sb.run_decide(["decide", SESSION, PANE, CLAUDE, rid])[0], D.EXIT_EXPIRED)


class SessionKeyTests(SandboxCase):
    def test_two_herdr_sessions_that_reuse_a_pane_id_never_share_a_directory(self):
        sb = self.sandbox(window=2)
        procs = []
        for name in ("session-a", "session-b"):
            proc, payload = sb.hook(HERDR_SESSION=name)
            proc.stdin.write(payload)
            proc.stdin.close()
            procs.append(proc)
        end = time.time() + 10
        while time.time() < end and not all(os.path.isdir(os.path.join(sb.run, "paddock", n, PANE)) and os.listdir(os.path.join(sb.run, "paddock", n, PANE)) for n in ("session-a", "session-b")):
            time.sleep(0.02)
        a = os.listdir(os.path.join(sb.run, "paddock", "session-a", PANE))
        b = os.listdir(os.path.join(sb.run, "paddock", "session-b", PANE))
        self.assertEqual((len(a), len(b)), (1, 1))
        self.assertNotEqual(a, b)
        rid_a = a[0].split(".")[0]
        # session B's writer cannot see or answer session A's request
        rc, _, _ = sb.run_decide(["decide", "session-b", PANE, CLAUDE, rid_a])
        self.assertEqual(rc, D.EXIT_GONE)
        for p in procs:
            p.communicate(timeout=10)


class ListTests(SandboxCase):
    def listing(self, sb, pane=PANE):
        rc, out, err = sb.run_decide(["list", SESSION, pane], "")
        self.assertEqual((rc, err), (0, ""))
        return json.loads(out)

    def test_no_directory_is_an_empty_listing_with_the_hosts_clock(self):
        sb = self.sandbox(herdr=False)
        listing = self.listing(sb)
        self.assertEqual((listing["v"], listing["requests"], listing["candidate"]), (1, [], None))
        self.assertLess(abs(listing["now_ms"] - time.time() * 1000), 5000)

    def test_the_newest_pending_request_is_the_candidate_and_carries_its_whole_body(self):
        sb = self.sandbox(herdr=False)
        now = int(time.time() * 1000)
        older = make_request(sb, "dddddddd-dddd-4ddd-8ddd-ddddddddddd1", created_at=now - 20_000, expires_at=now + 40_000, tool_input={"command": "older"})
        newer = make_request(sb, "dddddddd-dddd-4ddd-8ddd-ddddddddddd2", created_at=now - 5_000, expires_at=now + 55_000, tool_input={"command": "newer"})
        listing = self.listing(sb)
        self.assertEqual([r["request_id"] for r in listing["requests"]], [newer, older])
        self.assertEqual(listing["candidate"]["request_id"], newer)
        self.assertTrue(listing["candidate"]["complete"])
        self.assertEqual(listing["candidate"]["body"]["tool_input"], {"command": "newer"})
        self.assertEqual(listing["requests"][0]["state"], "pending")
        self.assertNotIn("body", listing["requests"][0])

    def test_settled_requests_are_listed_with_their_state_and_never_become_the_candidate(self):
        sb = self.sandbox(herdr=False)
        now = int(time.time() * 1000)
        ids = {s: make_request(sb, "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeee%d" % i, state=s, created_at=now - 1000 * (i + 1)) for i, s in enumerate(("pending", "claimed", "consumed", "expired"))}
        put(os.path.join(sb.requests_dir, ids["claimed"] + ".decision.json"), json.dumps({"request_id": ids["claimed"], "behavior": "deny"}))
        listing = self.listing(sb)
        by_id = {r["request_id"]: r for r in listing["requests"]}
        for state, rid in ids.items():
            self.assertEqual(by_id[rid]["state"], state)
        self.assertEqual(by_id[ids["claimed"]]["decision"], "deny")
        self.assertIsNone(by_id[ids["pending"]]["decision"])
        self.assertEqual(listing["candidate"]["request_id"], ids["pending"])

    def test_nothing_pending_means_no_candidate(self):
        sb = self.sandbox(herdr=False)
        make_request(sb, "ffffffff-ffff-4fff-8fff-fffffffffff1", state="expired")
        self.assertIsNone(self.listing(sb)["candidate"])

    def test_a_request_too_big_to_read_whole_is_candidate_but_incomplete(self):
        sb = self.sandbox(herdr=False)
        os.makedirs(sb.requests_dir, mode=0o700)
        os.chmod(sb.requests_dir, 0o700)
        rid = "ffffffff-ffff-4fff-8fff-fffffffffff2"
        put(os.path.join(sb.requests_dir, rid + ".pending.json"), b" " * (D.READ_LIMIT + 1))
        listing = self.listing(sb)
        self.assertEqual(listing["candidate"], {"request_id": rid, "complete": False, "body": None})

    def test_a_truncated_request_is_listed_as_truncated(self):
        sb = self.sandbox(herdr=False)
        make_request(sb, "ffffffff-ffff-4fff-8fff-fffffffffff3", truncated=True, tool_input="cut")
        entry = self.listing(sb)["requests"][0]
        self.assertTrue(entry["truncated"])

    def test_files_that_are_not_requests_and_other_panes_are_not_listed(self):
        sb = self.sandbox(herdr=False)
        make_request(sb, "ffffffff-ffff-4fff-8fff-fffffffffff4")
        put(os.path.join(sb.requests_dir, "readme.txt"), "x")
        put(os.path.join(sb.requests_dir, "ffffffff-ffff-4fff-8fff-fffffffffff5.pending.json.tmp"), "x")
        put(os.path.join(sb.requests_dir, "FFFFFFFF-FFFF-4FFF-8FFF-FFFFFFFFFFF6.pending.json"), "{}")
        self.assertEqual(len(self.listing(sb)["requests"]), 1)
        self.assertEqual(self.listing(sb, "w9:p9")["requests"], [])

    def test_listing_reads_only(self):
        sb = self.sandbox(herdr=False)
        old = "ffffffff-ffff-4fff-8fff-fffffffffff7"
        make_request(sb, old, state="expired")
        os.utime(os.path.join(sb.requests_dir, old + ".expired.json"), (time.time() - 7200, time.time() - 7200))
        before = sb.names()
        self.listing(sb)
        self.assertEqual(sb.names(), before, "only `decide` ever deletes")


# ---------------------------------------------------------------------------------------------------------------------------
# AC-08.7: two writers, one decision


class RaceTests(SandboxCase):
    def test_two_concurrent_writers_one_wins_and_the_hook_consumes_exactly_that_decision(self):
        sb = self.sandbox(window=20)
        for i in range(50):
            proc, payload = sb.hook()
            proc.stdin.write(payload)
            proc.stdin.close()
            rid = sb.wait_for("pending")
            args = ["decide", SESSION, PANE, CLAUDE, rid]
            a, body_a = sb.decide(args, json.dumps({"behavior": "allow", "phone": "A", "at": "t"}))
            b, body_b = sb.decide(args, json.dumps({"behavior": "deny", "phone": "B", "at": "t"}))
            for p, body in ((a, body_a), (b, body_b)):      # both are up and waiting on stdin: release them together
                p.stdin.write(body)
            a.stdin.close()
            b.stdin.close()
            codes = {"allow": a.wait(timeout=20), "deny": b.wait(timeout=20)}
            for p in (a, b):
                p.stdout.close()
                p.stderr.close()
            winners = [k for k, v in codes.items() if v == 0]
            losers = [k for k, v in codes.items() if v == D.EXIT_GONE]
            self.assertEqual((len(winners), len(losers)), (1, 1), (i, codes))
            stdout, _ = proc.communicate(timeout=20)
            printed = json.loads(stdout)["hookSpecificOutput"]["decision"]["behavior"]
            self.assertEqual(printed, winners[0], i)
            self.assertEqual(sb.read(rid + ".decision.json")["behavior"], winners[0], i)
            self.assertEqual(sorted(n for n in sb.names() if n.startswith(rid)), [rid + ".consumed.json", rid + ".decision.json"], i)
            for name in sb.names():
                os.unlink(os.path.join(sb.requests_dir, name))

    def test_a_claim_racing_the_lapse_is_honoured_or_refused_never_both_and_never_lost(self):
        sb = self.sandbox(window=1)
        honoured = refused = 0
        for i in range(20):
            proc, payload = sb.hook()
            proc.stdin.write(payload)
            proc.stdin.close()
            rid = sb.wait_for("pending")
            expires = sb.read(rid + ".pending.json")["expires_at"] / 1000.0
            delay = expires - time.time() + (i - 10) * 0.012      # from 120 ms before the lapse to 100 ms after it
            if delay > 0:
                time.sleep(delay)
            rc, _, err = sb.run_decide(["decide", SESSION, PANE, CLAUDE, rid])
            stdout, _ = proc.communicate(timeout=20)
            names = sorted(n for n in sb.names() if n.startswith(rid))
            if rc == 0:
                honoured += 1
                self.assertEqual(json.loads(stdout)["hookSpecificOutput"]["decision"]["behavior"], "allow", (i, err))
                self.assertEqual(names, [rid + ".consumed.json", rid + ".decision.json"], i)
            else:
                refused += 1
                self.assertIn(rc, (D.EXIT_GONE, D.EXIT_EXPIRED), (i, err))
                self.assertEqual(stdout, b"", i)
                self.assertEqual(names, [rid + ".expired.json"], i)
            for name in sb.names():
                os.unlink(os.path.join(sb.requests_dir, name))
        self.assertGreater(honoured, 0)
        self.assertGreater(refused, 0)


# ---------------------------------------------------------------------------------------------------------------------------
# The hook's status read against a real herdr


@unittest.skipUnless(LIVE, "pass --live to run against the disposable session")
class LiveTests(unittest.TestCase):
    SOCKET_RE = re.compile(r"^.*/sessions/(paddock-test(?:-[a-z0-9]+)?)/herdr\.sock$")

    def setUp(self):
        sock = os.environ.get("PADDOCK_TEST_SOCKET", "")
        m = self.SOCKET_RE.match(sock)
        if not m:
            self.skipTest("PADDOCK_TEST_SOCKET is not a disposable session socket")
        self.sock, self.session = sock, m.group(1)
        self.base = self.herdr("pane", "list")["result"]["panes"][0]["pane_id"]

    def herdr(self, *args, check=True):
        out = subprocess.run(["herdr", "--session", self.session, *args], capture_output=True, text=True, timeout=30)
        if check and out.returncode != 0:
            self.fail("herdr %s: %s" % (args, out.stderr))
        return json.loads(out.stdout) if out.stdout.strip().startswith("{") else out.stdout

    def test_the_status_read_matches_what_real_herdr_answers(self):
        pane = self.herdr("pane", "split", self.base, "--direction", "right", "--no-focus")["result"]["pane"]["pane_id"]
        self.addCleanup(lambda: self.herdr("pane", "close", pane, check=False))
        client = H.Herdr(self.sock, pane)
        self.addCleanup(client.close)
        self.assertIsNone(client.status(), "a plain shell has no agent: agent_not_found reads as no answer")
        seq = int(time.time() * 1000)
        for i, state in enumerate(("blocked", "working", "idle", "blocked")):
            self.herdr("pane", "report-agent", pane, "--source", "paddock-hook-live-%d" % os.getpid(), "--agent", "claude", "--state", state, "--seq", str(seq + i))
            time.sleep(0.4)
            self.assertEqual(client.status(), state)
        self.assertIsNone(H.Herdr(self.sock, "w999:p999").status())
        self.assertIsNone(H.Herdr("/nonexistent/herdr.sock", pane).status())


if __name__ == "__main__":
    unittest.main(verbosity=1)
