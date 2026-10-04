#!/usr/bin/env python3
"""Runs the alert relay under a transient systemd user unit against the disposable herdr session and checks what AC-07.6 asks
of a unit: the unit file verifies, the relay runs under the user manager, and after it is killed the manager brings it back and
it alerts again within a minute. It cannot reboot the machine: the reboot half of AC-07.6 is a host step (see docs/alerts.md).

    PADDOCK_TEST_SOCKET=.../sessions/paddock-test/herdr.sock tools/check-alert-unit.py

Only paddock-test or paddock-test-<suffix> is touched, and one transient unit named paddock-alert-relay-check, removed at the end.
"""
import json, os, re, shutil, signal, subprocess, sys, tempfile, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import harness
REPO = harness.APP
UNIT = "paddock-alert-relay-check"
m = re.match(r"^.*/sessions/(paddock-test(?:-[a-z0-9]+)?)/herdr\.sock$", os.environ.get("PADDOCK_TEST_SOCKET", ""))
if not m:
    sys.exit("PADDOCK_TEST_SOCKET must be a disposable session socket (.../sessions/paddock-test[-suffix]/herdr.sock)")
SOCK, SESSION = os.environ["PADDOCK_TEST_SOCKET"], m.group(1)


def sh(*argv, check=True):
    out = subprocess.run(argv, capture_output=True, text=True)
    if check and out.returncode != 0:
        sys.exit("%s failed: %s%s" % (argv[:3], out.stdout, out.stderr))
    return out


def herdr(*args):
    out = sh("herdr", "--session", SESSION, *args)
    return json.loads(out.stdout) if out.stdout.strip().startswith("{") else out.stdout


posts = []


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        posts.append((time.monotonic(), json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))))
        self.send_response(200); self.end_headers(); self.wfile.write(b"{}")

    def log_message(self, *a):
        pass


def main():
    work = tempfile.mkdtemp(prefix="paddock-alert-unit-")
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    pane = None
    try:
        # 1. the unit file, as shipped
        verify = sh("systemd-analyze", "--user", "verify", os.path.join(REPO, "host", "paddock-alert-relay.service"), check=False)
        problems = [l for l in (verify.stdout + verify.stderr).splitlines() if l.strip() and "paddock-alert-relay.py" not in l and "Executable" not in l]
        print("unit file verify:", "ok" if not problems else problems)
        assert not problems, problems
        config = os.path.join(work, "alert-relay.toml")
        with open(config, "w") as f:
            f.write('socket = "%s"\nprofile = "workstation"\nlabel = "unit-check"\ndelivery = "ntfy"\ndebounce_seconds = 0\nheartbeat_seconds = 2\nstate_file = "%s"\n[ntfy]\nurl = "http://127.0.0.1:%d"\ntopic = "paddock-unit"\n'
                    % (SOCK, os.path.join(work, "state.json"), server.server_address[1]))
        # 2. run it under the user manager with the shipped unit's restart policy
        sh("systemctl", "--user", "stop", UNIT + ".service", check=False)
        sh("systemd-run", "--user", "--unit", UNIT, "-p", "Restart=always", "-p", "RestartSec=5", "-p", "NoNewPrivileges=yes",
           sys.executable, os.path.join(REPO, "host", "paddock-alert-relay.py"), "--config", config)
        time.sleep(2)
        pid = int(sh("systemctl", "--user", "show", "-p", "MainPID", "--value", UNIT + ".service").stdout)
        print("running under the user manager, main pid", pid)
        base = herdr("pane", "list")["result"]["panes"][0]["pane_id"]
        pane = herdr("pane", "split", base, "--direction", "right", "--no-focus")["result"]["pane"]["pane_id"]
        seq = [0]

        def report(state):
            seq[0] += 1
            herdr("pane", "report-agent", pane, "--source", "paddock-alert-unit", "--agent", "claude", "--state", state, "--seq", str(seq[0]))

        report("idle"); time.sleep(3)
        report("blocked"); time.sleep(1.5)
        assert len(posts) == 1, "before the kill: %d posts" % len(posts)
        print("before the kill: 1 post")
        # 3. kill it hard; the manager restarts it after RestartSec
        killed = time.monotonic()
        os.kill(pid, signal.SIGKILL)
        time.sleep(0.5)
        state = sh("systemctl", "--user", "show", "-p", "ActiveState,SubState", UNIT + ".service").stdout.split()
        print("after SIGKILL:", state)
        before = len(posts)
        resumed = None
        deadline = killed + 60
        while time.monotonic() < deadline and resumed is None:
            report("idle"); time.sleep(1.2); report("blocked"); time.sleep(1.2)
            if len(posts) > before:
                resumed = posts[before][0] - killed
        assert resumed is not None, "no alert within a minute of the kill"
        newpid = int(sh("systemctl", "--user", "show", "-p", "MainPID", "--value", UNIT + ".service").stdout)
        print("restarted as pid %d; first alert %.1f s after the kill (limit 60)" % (newpid, resumed))
        assert newpid not in (0, pid)
        print("PASS: unit file verifies, relay runs under the user manager, resumed within a minute of SIGKILL")
    finally:
        sh("systemctl", "--user", "stop", UNIT + ".service", check=False)
        sh("systemctl", "--user", "reset-failed", UNIT + ".service", check=False)
        if pane:
            subprocess.run(["herdr", "--session", SESSION, "pane", "close", pane], capture_output=True)
        server.shutdown()
        shutil.rmtree(work, ignore_errors=True)


main()
