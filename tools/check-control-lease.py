#!/usr/bin/env python3
"""AC-05.6, host side: a phone that loses its link under control releases the terminal within the lease.

    tools/check-control-lease.py [--baseline]

Runs against the throwaway sshd (tools/test-sshd.sh) through the blackhole proxy (tools/blackhole-proxy.py), on a pane it
creates in the disposable session `paddock-test` and closes afterwards. An OpenSSH client stands in for the phone: it
runs `python3 host/paddock-control.py` on the "host" over SSH and sends the lease ping every 5 s, as the app does. The
proxy then freezes (no RST, like airplane mode) and the script polls with a desktop-style `terminal session control`
without `--takeover` until it is accepted, which is the moment the desktop would regain input. Prints the timestamps.
With --baseline it also runs plain `herdr terminal session control` over SSH, the way a lease-less client would, to show
that the freeze alone releases nothing.
"""
import json, os, queue, re, socket, subprocess, sys, tempfile, threading, time

import harness
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = harness.APP
SESSION = "paddock-test"
ENV = {k: v for k, v in os.environ.items() if not k.startswith("HERDR_")}
RUN = os.environ.get("TEST_SSHD_RUN", os.path.join(harness.OUT_BASE, "test-sshd"))
KEY = os.path.join(RUN, "lease_ed25519")
LEASE, PING = 15, 5
SCRIPT = os.path.join(ROOT, "host", "paddock-control.py")


def herdr(*a):
    return subprocess.run(["herdr", "--session", SESSION, *a], capture_output=True, text=True, env=ENV)


def proxy(cmd):
    s = socket.create_connection(("127.0.0.1", 2224), timeout=3); s.sendall((cmd + "\n").encode()); return s.makefile().readline().strip()


def ensure_infrastructure():
    if subprocess.run([os.path.join(harness.TOOLS, "test-sshd.sh"), "status"], capture_output=True).returncode != 0:
        subprocess.run([os.path.join(harness.TOOLS, "test-sshd.sh"), "start"], check=True)
    if subprocess.run(["ss", "-Hltn", "sport = :2224"], capture_output=True, text=True).stdout.strip() == "":
        subprocess.Popen([sys.executable, os.path.join(HERE, "blackhole-proxy.py"), "2223", "2222", "2224"], stdout=open(os.path.join(RUN, "proxy.log"), "w"), stderr=subprocess.STDOUT, start_new_session=True)
        time.sleep(1)
    if not os.path.exists(KEY):
        subprocess.run(["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "paddock-lease-check", "-f", KEY], check=True)
    subprocess.run([os.path.join(harness.TOOLS, "test-sshd.sh"), "authorize", KEY + ".pub"], check=True, capture_output=True)


def ssh_argv():
    return ["ssh", "-p", "2223", "-i", KEY, "-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null", "-o", "IdentitiesOnly=yes",
            "-o", "BatchMode=yes", "-o", "LogLevel=ERROR", "%s@127.0.0.1" % os.environ["USER"]]


class Phone:
    """The phone's side of one control session over SSH."""

    def __init__(self, remote_argv, ping):
        self.p = subprocess.Popen(ssh_argv() + [" ".join(remote_argv)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=ENV)
        self.lines = queue.Queue(); self.stop = threading.Event()
        threading.Thread(target=lambda: [self.lines.put(l) for l in iter(self.p.stdout.readline, b"")], daemon=True).start()
        if ping:
            threading.Thread(target=self._ping, daemon=True).start()

    def _ping(self):
        while not self.stop.wait(PING):
            try:
                self.p.stdin.write(b'{"type":"paddock.lease"}\n'); self.p.stdin.flush()
            except OSError:
                return

    def first(self):
        return json.loads(self.lines.get(timeout=10))

    def kill(self):
        self.stop.set()
        try: self.p.kill()
        except OSError: pass


def desktop_control_accepted(pane):
    r = subprocess.Popen(["herdr", "--session", SESSION, "terminal", "session", "control", pane, "--cols", "60", "--rows", "20"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=ENV)
    try:
        ok = json.loads(r.stdout.readline()).get("type") == "terminal.frame"
        if ok:
            r.stdin.write(b'{"type":"terminal.release"}\n'); r.stdin.flush(); time.sleep(0.2)
        return ok
    finally:
        r.kill()


def trial(label, pane, remote_argv, ping, limit):
    phone = Phone(remote_argv, ping)
    try:
        first = phone.first()
        assert first["type"] == "terminal.frame", first
        assert not desktop_control_accepted(pane), "the desktop must be refused while the phone controls"
        time.sleep(PING + 2)
        assert not desktop_control_accepted(pane), "the desktop must still be refused while the phone pings"
        frozen = proxy("freeze"); t0 = time.time()
        accepted = None
        while time.time() - t0 < limit:
            time.sleep(1)
            if desktop_control_accepted(pane):
                accepted = time.time() - t0; break
        proxy("thaw")
        print("%-28s link frozen at %s; desktop control %s" % (label, frozen.split(" at ")[1], ("accepted %.1f s later" % accepted) if accepted is not None else "still refused after %d s" % limit))
        return accepted
    finally:
        phone.kill(); time.sleep(1.5)


def main():
    ensure_infrastructure()
    pane0 = json.loads(herdr("pane", "list").stdout)["result"]["panes"][0]["pane_id"]
    pane = json.loads(herdr("pane", "split", pane0, "--direction", "right", "--no-focus").stdout)["result"]["pane"]["pane_id"]
    herdr_bin = subprocess.run(["which", "herdr"], capture_output=True, text=True, env=ENV).stdout.strip()
    failures = 0
    try:
        accepted = trial("with the lease (%d s)" % LEASE, pane, ["python3", SCRIPT, str(LEASE), herdr_bin, SESSION, pane, "60", "20"], True, 40)
        if accepted is None or accepted > 30:
            print("FAIL  AC-05.6: the desktop did not regain input within 30 s"); failures += 1
        else:
            print("PASS  AC-05.6 (host side): the desktop regained input %.1f s after the link died (lease %d s)" % (accepted, LEASE))
        if "--baseline" in sys.argv:
            trial("without a lease (plain herdr)", pane, [herdr_bin, "--session", SESSION, "terminal", "session", "control", pane, "--cols", "60", "--rows", "20"], False, 40)
    finally:
        proxy("thaw")
        herdr("pane", "close", pane)
    return failures


if __name__ == "__main__":
    sys.exit(main())
