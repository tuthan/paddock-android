#!/usr/bin/env python3
"""relay_probe.py: same stall, but through host/paddock-relay.py (stdio pipe) as the app sees it.
  relay_probe.py --subs lifecycle|status --stall S --burst-kind split|report --burst-n N --out PREFIX
The consumer of the relay's stdout stops reading for --stall seconds (the stdout pipe, ~64 KiB, is the only buffer
besides the unix socket), then reads to EOF. Records bytes, EOF, relay exit status."""
import argparse, json, os, subprocess, sys, threading, time, select
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import evlost, harness
ap = argparse.ArgumentParser()
ap.add_argument("--subs", default="lifecycle"); ap.add_argument("--stall", type=float, default=40)
ap.add_argument("--burst-kind", default="split"); ap.add_argument("--burst-n", type=int, default=800)
ap.add_argument("--threads", type=int, default=2); ap.add_argument("--out", required=True)
a = ap.parse_args()
evlost.LOGF = open(a.out + ".log", "w")
pane = evlost.first_pane()
p = subprocess.Popen(["python3", os.path.join(harness.APP, "host", "paddock-relay.py"), evlost.SOCK],
                     stdin=subprocess.PIPE, stdout=subprocess.PIPE)
req = {"id": "evlost-relay", "method": "events.subscribe", "params": {"subscriptions": evlost.subscriptions(a.subs, pane)}}
p.stdin.write(json.dumps(req).encode() + b"\n"); p.stdin.flush()
buf = b""
while b"\n" not in buf:
    buf += os.read(p.stdout.fileno(), 65536)
ack, rest = buf.split(b"\n", 1)
evlost.log("relay ACK %s (+%d bytes)" % (ack.decode(), len(rest)))
burst = evlost.Burst(a.burst_kind, a.burst_n, "sock", pane, a.threads)
bt = threading.Thread(target=burst.run); bt.start()
evlost.log("consumer of relay stdout STOPPED reading for %.0fs" % a.stall)
time.sleep(a.stall)
bt.join()
raw = open(a.out + ".raw", "wb"); raw.write(rest); total = len(rest)
t0 = time.monotonic()
while True:
    r, _, _ = select.select([p.stdout.fileno()], [], [], 10)
    if not r:
        evlost.log("no data for 10s, relay still alive=%s" % (p.poll() is None)); break
    c = os.read(p.stdout.fileno(), 65536)
    if not c:
        evlost.log("EOF on relay stdout after %d bytes (%.1fs after resume)" % (total, time.monotonic() - t0)); break
    raw.write(c); total += len(c)
raw.close()
try:
    rc = p.wait(timeout=5)
except subprocess.TimeoutExpired:
    rc = None
evlost.log("relay exit code %s; total bytes %d" % (rc, total))
if rc is None:
    p.kill()
d = open(a.out + ".raw", "rb").read()
evlost.log("raw ends with newline=%s lines=%d last 80 bytes=%r" % (d.endswith(b"\n"), d.count(b"\n"), d[-80:]))
