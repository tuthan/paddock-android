#!/usr/bin/env python3
"""Measures the host half of alert delivery (Phase 07, AC-07.2) against the disposable herdr session, in both delivery modes.

    PADDOCK_TEST_SOCKET=~/.config/herdr/sessions/paddock-test/herdr.sock tools/measure-alert-latency.py [--transitions 20] [--out DIR]

For each mode (the ntfy publish, and the UnifiedPush POST) it runs the real `host/paddock-alert-relay.py` against a loopback stub
and drives N blocked transitions with `pane report-agent` at random intervals, recording the time from the moment the transition
is reported to herdr to the moment the stub receives the message: the relay's whole contribution to latency. Then two outages: the
stub is down for 20 s (the message must arrive after it returns, late by the backoff), and down for longer than the expiry (the
message must be dropped, and nothing stale must arrive afterwards). Every miss is listed with its reason.

What this is not: delivery to a phone. The network between the relay and a real ntfy server, the server, the ntfy app, the phone's
battery state and Doze are not in it. Those need a physical phone and are measured separately (see the evidence report).

Only the disposable session is touched, on one pane this script creates and closes. Nothing is posted anywhere but loopback.
"""
import argparse, json, os, random, re, signal, socket, statistics, subprocess, sys, tempfile, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import harness
REPO = harness.APP
RELAY = os.path.join(REPO, "host", "paddock-alert-relay.py")
m = re.match(r"^.*/sessions/(paddock-test(?:-[a-z0-9]+)?)/herdr\.sock$", os.environ.get("PADDOCK_TEST_SOCKET", ""))
if not m:
    sys.exit("PADDOCK_TEST_SOCKET must be a disposable session socket (.../sessions/paddock-test[-suffix]/herdr.sock)")
SOCK, SESSION = os.environ["PADDOCK_TEST_SOCKET"], m.group(1)
ENV = {k: v for k, v in os.environ.items() if not k.startswith("HERDR_")}


def herdr(*args):
    out = subprocess.run(["herdr", "--session", SESSION, *args], capture_output=True, text=True, env=ENV)
    if out.returncode != 0:
        sys.exit("herdr %s failed: %s%s" % (args[:2], out.stdout, out.stderr))
    return json.loads(out.stdout) if out.stdout.strip().startswith("{") else out.stdout


class Stub:
    """A loopback HTTP server that records (arrival time, path, body) and can be taken down and brought back on its port."""

    def __init__(self):
        s = socket.socket(); s.bind(("127.0.0.1", 0)); self.port = s.getsockname()[1]; s.close()
        self.posts, self.server = [], None
        self.up()

    def up(self):
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", 0))).decode("utf-8", "replace")
                stub.posts.append((time.time(), self.path, body))
                self.send_response(200); self.end_headers(); self.wfile.write(b"{}")

            def log_message(self, *a):
                pass

        ThreadingHTTPServer.allow_reuse_address = True
        self.server = ThreadingHTTPServer(("127.0.0.1", self.port), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def down(self):
        self.server.shutdown(); self.server.server_close(); self.server = None


class Relay:
    def __init__(self, tmp, name, delivery, stub, endpoint_path, expiry=600, heartbeat=30):
        cfg = os.path.join(tmp, name + ".toml")
        endpoint_file = os.path.join(tmp, name + "-endpoint.json")
        text = 'socket = "%s"\nprofile = "measure-box"\nlabel = "measure"\ndelivery = "%s"\ndebounce_seconds = 1\nexpiry_seconds = %d\nheartbeat_seconds = %d\n' % (SOCK, delivery, expiry, heartbeat)
        if delivery == "ntfy":
            text += '[ntfy]\nurl = "http://127.0.0.1:%d"\ntopic = "measure-%s"\n' % (stub.port, name)
        else:
            with open(endpoint_file, "w") as f:
                json.dump({"endpoint": "http://127.0.0.1:%d%s" % (stub.port, endpoint_path)}, f)
            os.chmod(endpoint_file, 0o600)
            text += '[unifiedpush]\nendpoint_file = "%s"\n' % endpoint_file
        with open(cfg, "w") as f:
            f.write(text)
        self.log = open(os.path.join(tmp, name + ".log"), "w+")
        self.proc = subprocess.Popen([sys.executable, RELAY, "--config", cfg], stdout=self.log, stderr=subprocess.STDOUT, env=ENV)
        for _ in range(100):
            self.log.seek(0)
            if "started (version" in self.log.read():
                break
            time.sleep(0.1)
        time.sleep(3)  # the first read is a silent baseline

    def stop(self):
        self.proc.send_signal(signal.SIGTERM)
        try:
            self.proc.wait(10)
        except subprocess.TimeoutExpired:
            self.proc.kill()
        self.log.seek(0)
        return self.log.read()


def pct(values, p):
    s = sorted(values)
    return s[min(len(s) - 1, int(round(p / 100 * (len(s) - 1))))]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--transitions", type=int, default=20)
    ap.add_argument("--min-gap", type=float, default=3.0)
    ap.add_argument("--max-gap", type=float, default=15.0)
    ap.add_argument("--out", default=os.path.join(harness.OUT_BASE, "alert-latency-" + time.strftime("%Y%m%d-%H%M%S")))
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    tmp = tempfile.mkdtemp(prefix="alert-latency-")
    src = "alert-measure-%d" % time.time()
    seq = [100]

    def report(pane, state):
        seq[0] += 10
        herdr("pane", "report-agent", pane, "--source", src, "--agent", "claude", "--state", state, "--seq", str(seq[0]))

    base = herdr("pane", "list")["result"]["panes"][0]["pane_id"]
    pane = herdr("pane", "split", base, "--direction", "right", "--no-focus")["result"]["pane"]["pane_id"]
    term = herdr("pane", "get", pane)["result"]["pane"]["terminal_id"]
    report(pane, "idle")
    print("pane %s (%s) in %s; herdr %s" % (pane, term, SESSION, subprocess.run(["herdr", "--version"], capture_output=True, text=True, env=ENV).stdout.strip()))
    results, stub = {}, Stub()
    up_path = "/up-measure-" + str(os.getpid())
    try:
        for mode, delivery in (("ntfy publish", "ntfy"), ("UnifiedPush post", "unifiedpush")):
            stub.posts.clear()
            relay = Relay(tmp, delivery, delivery, stub, up_path)
            lat, misses = [], []
            for i in range(a.transitions):
                time.sleep(random.uniform(a.min_gap, a.max_gap))
                report(pane, "working"); time.sleep(1.3)
                before = len(stub.posts)
                t0 = time.time(); report(pane, "blocked")
                hit = None
                end = t0 + 60
                while time.time() < end and hit is None:
                    for t, path, body in stub.posts[before:]:
                        if (delivery == "ntfy" and ("t=%s&" % term) in json.loads(body).get("click", "")) or (delivery == "unifiedpush" and path == up_path):
                            hit = t; break
                    time.sleep(0.01)
                if hit is None:
                    misses.append({"n": i + 1, "reason": "no post within 60 s"})
                else:
                    lat.append((hit - t0) * 1000)
                print("%s %2d/%d  %s" % (delivery, i + 1, a.transitions, "%.0f ms" % lat[-1] if hit else "MISS"), flush=True)
            report(pane, "working")
            logtext = relay.stop()
            extra = len(stub.posts) - len(lat)
            results[mode] = {"transitions": a.transitions, "delivered": len(lat), "median_ms": round(statistics.median(lat)) if lat else None,
                             "p95_ms": round(pct(lat, 95)) if lat else None, "max_ms": round(max(lat)) if lat else None, "min_ms": round(min(lat)) if lat else None,
                             "misses": misses, "posts_not_matched_to_a_transition": extra, "relay_stats": [l for l in logtext.splitlines() if "stats" in l][-1:]}

        # Outage 1: the server is down for 20 s; the message must arrive after it returns, late by the backoff.
        for mode, delivery in (("ntfy publish", "ntfy"),):
            stub.posts.clear()
            relay = Relay(tmp, "outage1", delivery, stub, up_path)
            report(pane, "working"); time.sleep(1.3)
            stub.down()
            t0 = time.time(); report(pane, "blocked")
            time.sleep(20)
            t_up = time.time(); stub.up()
            got = None
            while time.time() < t_up + 120 and got is None:
                for t, path, body in stub.posts:
                    if ("t=%s&" % term) in json.loads(body).get("click", ""):
                        got = t
                time.sleep(0.05)
            results["outage: server down 20 s"] = {"delivered_after_return_ms": round((got - t_up) * 1000) if got else None, "from_transition_s": round(got - t0, 1) if got else None,
                                                    "posts": len([1 for t, p, b in stub.posts if ("t=%s&" % term) in json.loads(b).get("click", "")])}
            relay.stop()

        # Outage 2: the server is down for longer than the expiry; the message is dropped and nothing stale arrives later.
        stub.posts.clear()
        relay = Relay(tmp, "outage2", "ntfy", stub, up_path, expiry=10)
        report(pane, "working"); time.sleep(1.3)
        stub.down()
        report(pane, "blocked")
        time.sleep(25)
        stub.up(); time.sleep(20)
        late = [1 for t, p, b in stub.posts if ("t=%s&" % term) in json.loads(b).get("click", "")]
        logtext = relay.stop()
        results["outage: server down past the 10 s expiry"] = {"stale_deliveries_after_return": len(late), "relay_stats": [l for l in logtext.splitlines() if "stats" in l][-1:]}
    finally:
        seq[0] += 10
        subprocess.run(["herdr", "--session", SESSION, "pane", "release-agent", pane, "--source", src, "--agent", "claude", "--seq", str(seq[0])], capture_output=True, env=ENV)
        subprocess.run(["herdr", "--session", SESSION, "pane", "close", pane], capture_output=True, env=ENV)
        try:
            stub.down()
        except Exception:
            pass

    with open(os.path.join(a.out, "results.json"), "w") as f:
        json.dump(results, f, indent=2)
    lines = ["| Mode | Transitions | Delivered | Median | p95 | Max | Misses |", "| --- | --- | --- | --- | --- | --- | --- |"]
    for mode in ("ntfy publish", "UnifiedPush post"):
        r = results[mode]
        lines.append("| %s | %d | %d | %s ms | %s ms | %s ms | %d |" % (mode, r["transitions"], r["delivered"], r["median_ms"], r["p95_ms"], r["max_ms"], len(r["misses"])))
    with open(os.path.join(a.out, "table.md"), "w") as f:
        f.write("\n".join(lines) + "\n")
    print("\n".join(lines))
    print(json.dumps({k: v for k, v in results.items() if k.startswith("outage")}, indent=2))
    print("results in", a.out)


main()
