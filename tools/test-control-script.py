#!/usr/bin/env python3
"""Self-test of host/paddock-control.py against a fake herdr. Never touches the real herdr or any session.

    tools/test-control-script.py

Each case runs the script as a child process with a fake `herdr` first, so the lease timing, the argv the script builds,
what it forwards and what it drops are all observed from outside, as the phone sees them. About 40 seconds.
"""
import json, os, signal, stat, subprocess, sys, tempfile, textwrap, time, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "..", "host", "paddock-control.py")

FAKE = textwrap.dedent('''\
    #!/usr/bin/env python3
    import json, os, sys, threading, time
    log = open(os.environ["FAKE_LOG"], "a")
    def note(kind, value): log.write(json.dumps([kind, value]) + "\\n"); log.flush()
    note("argv", sys.argv[1:])
    mode = os.environ.get("FAKE_MODE", "normal")
    # One writer at a time, and nothing after the closing frame: real herdr says terminal.closed last. Without the lock the
    # chatty thread, waiting on the same stdout, could slip one more frame in between the closing frame and os._exit.
    emit_lock, closing = threading.Lock(), [False]
    def emit(obj, last=False):
        with emit_lock:
            if closing[0]: return
            closing[0] = last
            sys.stdout.write(json.dumps(obj) + "\\n"); sys.stdout.flush()
    emit({"type": "terminal.frame", "seq": 1, "encoding": "ansi", "full": True, "width": 60, "height": 20, "bytes": ""})
    if mode == "exit3": sys.exit(3)
    if mode == "chatty":
        def spew():
            blob = "x" * 8192
            while True:
                emit({"type": "terminal.frame", "seq": 2, "encoding": "ansi", "full": False, "width": 60, "height": 20, "bytes": blob})
        threading.Thread(target=spew, daemon=True).start()
    for line in sys.stdin:
        note("line", line.rstrip("\\n"))
        try: obj = json.loads(line)
        except ValueError: continue
        if obj.get("type") == "terminal.release" and mode != "stubborn":
            emit({"type": "terminal.closed", "reason": "detached"}, last=True); os._exit(0)
        if obj.get("type") == "terminal.input":
            emit({"type": "terminal.frame", "seq": 2, "encoding": "ansi", "full": False, "width": 60, "height": 20, "bytes": obj.get("bytes", "")})
    # stdin closed: herdr detaches the controller
    if mode == "stubborn": time.sleep(60)
    note("eof", True)
    emit({"type": "terminal.closed", "reason": "detached"}, last=True)
''')


class Run:
    """The script under test, with its fake herdr, pipes and a log of what the fake saw."""

    def __init__(self, test, args=None, mode="normal", lease=5, pane="w1:p1", session="paddock-test", extra=()):
        self.dir = tempfile.mkdtemp(prefix="paddock-control-test-")
        test.addCleanup(self.cleanup)
        self.bin = os.path.join(self.dir, "herdr")
        with open(self.bin, "w") as f:
            f.write(FAKE)
        os.chmod(self.bin, os.stat(self.bin).st_mode | stat.S_IXUSR)
        self.log = os.path.join(self.dir, "log")
        env = dict(os.environ, FAKE_LOG=self.log, FAKE_MODE=mode)
        argv = args if args is not None else [str(lease), self.bin, session, pane, "60", "20", *extra]
        self.started = time.monotonic()
        self.p = subprocess.Popen([sys.executable, SCRIPT, *argv], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)

    def cleanup(self):
        try:
            self.p.kill()
        except OSError:
            pass
        self.p.wait()
        for s in (self.p.stdin, self.p.stdout, self.p.stderr):
            try:
                s.close()
            except Exception:
                pass

    def send(self, obj_or_bytes):
        data = obj_or_bytes if isinstance(obj_or_bytes, bytes) else (json.dumps(obj_or_bytes) + "\n").encode()
        self.p.stdin.write(data)
        self.p.stdin.flush()

    def read_line(self, timeout=5.0):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if select_readable(self.p.stdout, 0.1):
                line = self.p.stdout.readline()
                if line:
                    return json.loads(line)
                return None
        raise AssertionError("no line within %.1fs" % timeout)

    def wait_exit(self, timeout):
        try:
            return self.p.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            raise AssertionError("still running after %.1fs" % timeout)

    def elapsed(self):
        return time.monotonic() - self.started

    def seen(self):
        if not os.path.exists(self.log):
            return []
        with open(self.log) as f:
            return [json.loads(l) for l in f.read().splitlines()]

    def lines(self):
        return [v for k, v in self.seen() if k == "line"]

    def argv(self):
        return [v for k, v in self.seen() if k == "argv"]


def select_readable(f, timeout):
    import select
    return bool(select.select([f], [], [], timeout)[0])


class ControlScript(unittest.TestCase):
    # --- arguments ---

    def refuses(self, args):
        r = Run(self, args=args)
        self.assertEqual(r.wait_exit(3), 2, args)
        self.assertEqual(r.argv(), [], "herdr must not be started for %r" % (args,))
        self.assertIn(b"paddock-control:", r.p.stderr.read())

    def test_arguments_are_validated_before_anything_runs(self):
        good = ["5", "/bin/true", "s", "w1:p1", "60", "20"]
        self.refuses([])
        self.refuses(good[:5])
        self.refuses(good + ["takeover", "x"])
        self.refuses(good + ["--takeover"])
        for i, bad in ((0, "4"), (0, "121"), (0, "five"), (4, "0"), (4, "1001"), (5, "0"), (5, "501"), (4, "x"),
                       (2, "-x"), (2, "a b"), (2, "a;b"), (2, ""), (3, "-p"), (3, "w1 p1"), (3, "$(id)"), (3, "")):
            args = list(good); args[i] = bad
            self.refuses(args)

    def test_herdr_must_be_an_absolute_executable_named_herdr(self):
        r = Run(self, args=["5", "herdr", "s", "w1:p1", "60", "20"]); self.assertEqual(r.wait_exit(3), 2)
        r = Run(self, args=["5", "/bin/true", "s", "w1:p1", "60", "20"]); self.assertEqual(r.wait_exit(3), 2)
        r = Run(self, args=["5", "/nonexistent/herdr", "s", "w1:p1", "60", "20"]); self.assertEqual(r.wait_exit(3), 2)

    def test_the_command_is_herdrs_control_session_and_nothing_else(self):
        r = Run(self, session="paddock-test", pane="w2:p5P")
        r.read_line()
        self.assertEqual(r.argv()[0], ["--session", "paddock-test", "terminal", "session", "control", "w2:p5P", "--cols", "60", "--rows", "20"])
        r.send({"type": "terminal.release"}); r.wait_exit(5)

    def test_takeover_is_only_added_when_asked_for(self):
        r = Run(self, extra=("takeover",))
        r.read_line()
        self.assertEqual(r.argv()[0], ["--session", "paddock-test", "terminal", "session", "control", "w1:p1", "--takeover", "--cols", "60", "--rows", "20"])
        r.send({"type": "terminal.release"}); r.wait_exit(5)

    # --- pass-through ---

    def test_frames_and_commands_pass_through_unchanged(self):
        r = Run(self)
        self.assertEqual(r.read_line()["type"], "terminal.frame")
        r.send({"type": "terminal.input", "bytes": "aGk="})
        echoed = r.read_line()
        self.assertEqual(echoed["bytes"], "aGk=")
        self.assertEqual(r.lines(), ['{"type": "terminal.input", "bytes": "aGk="}'])
        r.send({"type": "terminal.release"})
        self.assertEqual(r.read_line(), {"type": "terminal.closed", "reason": "detached"})
        self.assertEqual(r.wait_exit(5), 0)

    def test_every_command_type_is_forwarded(self):
        r = Run(self); r.read_line()
        cmds = [{"type": "terminal.resize", "cols": 50, "rows": 15}, {"type": "terminal.scroll", "direction": "up", "lines": 3},
                {"type": "terminal.mouse", "action": "down", "button": "left", "column": 1, "row": 1}, {"type": "terminal.input", "bytes": "AA=="}]
        for c in cmds:
            r.send(c)
        r.send({"type": "terminal.release"}); r.wait_exit(5)
        self.assertEqual([json.loads(l) for l in r.lines()], cmds + [{"type": "terminal.release"}])

    def test_the_lease_ping_is_consumed_and_never_forwarded(self):
        r = Run(self); r.read_line()
        r.send({"type": "paddock.lease"}); r.send({"type": "paddock.lease"})
        r.send({"type": "terminal.release"}); r.wait_exit(5)
        self.assertEqual(r.lines(), ['{"type": "terminal.release"}'])

    def test_anything_that_is_not_a_control_command_is_dropped(self):
        r = Run(self); r.read_line()
        for junk in (b"not json\n", b"[1,2,3]\n", b"\n", b'{"type":"shell.exec","cmd":"id"}\n', b'{"no":"type"}\n', b'"string"\n', b'{"type":["terminal.input"]}\n', b"\xff\xfe\n"):
            r.send(junk)
        r.send({"type": "terminal.release"}); r.wait_exit(5)
        self.assertEqual(r.lines(), ['{"type": "terminal.release"}'])

    def test_a_partial_line_waits_for_its_newline(self):
        r = Run(self); r.read_line()
        r.send(b'{"type":"terminal.inp'); time.sleep(0.4)
        self.assertEqual(r.lines(), [])
        r.send(b'ut","bytes":"AA=="}\n'); self.assertEqual(r.read_line()["bytes"], "AA==")
        r.send({"type": "terminal.release"}); r.wait_exit(5)

    def test_a_child_that_exits_ends_the_script_with_its_status(self):
        r = Run(self, mode="exit3")
        self.assertEqual(r.read_line()["type"], "terminal.frame")
        self.assertEqual(r.wait_exit(5), 3)

    # --- the lease ---

    def test_silence_releases_the_control_session_when_the_lease_runs_out(self):
        r = Run(self, lease=5); r.read_line()
        closed = r.read_line(timeout=9)
        self.assertEqual(closed, {"type": "terminal.closed", "reason": "detached"})
        took = r.elapsed()
        self.assertGreaterEqual(took, 4.5, "released before the lease ran out"); self.assertLessEqual(took, 7.5, "released too late: %.1fs" % took)
        self.assertEqual(r.wait_exit(5), 0)
        self.assertEqual(r.lines()[-1], '{"type":"terminal.release"}')
        self.assertIn(b"lease expired", r.p.stderr.read())

    def test_a_lease_ping_extends_it_and_stopping_the_pings_ends_it(self):
        r = Run(self, lease=5); r.read_line()
        for _ in range(4):
            time.sleep(2.0); r.send({"type": "paddock.lease"})
        self.assertIsNone(r.p.poll(), "released while the phone was still pinging (%.1fs)" % r.elapsed())
        self.assertNotIn("eof", [k for k, _ in r.seen()])
        self.assertEqual(r.lines(), [])
        last_ping = time.monotonic()
        r.read_line(timeout=9)
        self.assertLessEqual(time.monotonic() - last_ping, 7.5)
        self.assertEqual(r.wait_exit(5), 0)

    def test_a_command_counts_as_proof_of_life(self):
        r = Run(self, lease=5); r.read_line()
        for _ in range(3):
            time.sleep(2.0); r.send({"type": "terminal.input", "bytes": "AA=="}); r.read_line()
        self.assertIsNone(r.p.poll())
        r.send({"type": "terminal.release"}); r.wait_exit(5)

    def test_a_closed_stdin_releases_at_once(self):
        r = Run(self, lease=120); r.read_line()
        t = time.monotonic(); r.p.stdin.close()
        self.assertEqual(r.read_line(timeout=3), {"type": "terminal.closed", "reason": "detached"})
        self.assertLess(time.monotonic() - t, 2.0)
        self.assertEqual(r.wait_exit(5), 0)

    def test_sigterm_releases(self):
        r = Run(self, lease=120); r.read_line()
        r.p.send_signal(signal.SIGTERM)
        self.assertEqual(r.read_line(timeout=3), {"type": "terminal.closed", "reason": "detached"})
        self.assertEqual(r.wait_exit(5), 0)

    def test_a_herdr_that_ignores_the_release_is_killed_after_the_grace(self):
        r = Run(self, lease=120, mode="stubborn"); r.read_line()
        t = time.monotonic(); r.p.stdin.close()
        code = r.wait_exit(8)
        took = time.monotonic() - t
        self.assertGreaterEqual(took, 2.5); self.assertLess(took, 6.0)
        self.assertNotEqual(code, 0, "a killed child is not a clean exit")

    def test_the_phone_closing_stdout_releases(self):
        r = Run(self, lease=120); r.read_line()
        r.p.stdout.close()
        r.send({"type": "terminal.input", "bytes": "AA=="})   # the echo has nowhere to go
        self.assertEqual(r.wait_exit(8), 0)
        self.assertEqual(r.lines()[-1], '{"type":"terminal.release"}', "the child was released")

    def test_a_phone_that_stopped_reading_cannot_stall_the_lease(self):
        # The link is dead in one direction: herdr keeps producing frames, nothing reads them, and the pipe to sshd fills.
        # A blocking write here would freeze the script and with it the lease; the release must still happen on time.
        r = Run(self, lease=5, mode="chatty")
        time.sleep(7.5)                                    # past the lease; stdout was never read
        self.assertIn('{"type":"terminal.release"}', r.lines(), "the release reached herdr although the phone stopped reading")
        data = r.p.stdout.read()                            # now drain whatever was queued
        self.assertIn(b"terminal.closed", data[-200:] if len(data) > 200 else data)
        self.assertEqual(r.wait_exit(8), 0)

    def test_an_oversized_line_is_refused_and_releases(self):
        r = Run(self, lease=120); r.read_line()
        try:
            r.send(b"x" * (3 << 20))
        except BrokenPipeError:
            pass
        self.assertEqual(r.wait_exit(8), 0)
        self.assertEqual(r.lines()[-1], '{"type":"terminal.release"}', "the child was released")
        self.assertIn(b"line too long", r.p.stderr.read())


if __name__ == "__main__":
    unittest.main(verbosity=2)
