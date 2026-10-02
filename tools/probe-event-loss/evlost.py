#!/usr/bin/env python3
"""evlost.py: provoke a herdr 0.9.1 event-subscriber lag (stdlib only), session paddock-test-evlost only.

One run = one subscriber that stalls + one burst generator.
  evlost.py --subs status|lifecycle|both --rcvbuf BYTES --stall SECS --burst-kind report|split|tab|ws --burst-n N
            --burst-via sock|cli --read stop|slow --out PREFIX

Output: PREFIX.raw   exact bytes received from the socket after the ack (before/after the stall)
        PREFIX.log   timeline: ack, burst start/stop, stall end, per-read sizes, EOF/close, line counts
"""
import argparse, json, os, socket, subprocess, sys, threading, time

SESSION = "paddock-test-evlost"
SOCK = os.path.expanduser("~/.config/herdr/sessions/%s/herdr.sock" % SESSION)
H = ["herdr", "--session", SESSION]
T0 = time.monotonic()
LOGF = None


def log(msg):
    line = "%8.3f %s" % (time.monotonic() - T0, msg)
    print(line, flush=True)
    if LOGF:
        LOGF.write(line + "\n"); LOGF.flush()


def call(req, timeout=10.0):
    """One request on a fresh connection (herdr answers once and closes)."""
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(timeout)
    s.connect(SOCK)
    s.sendall(json.dumps(req).encode() + b"\n")
    buf = b""
    while b"\n" not in buf:
        c = s.recv(65536)
        if not c:
            break
        buf += c
    s.close()
    return buf.split(b"\n", 1)[0].decode(errors="replace")


def first_pane():
    return json.loads(call({"id": "p", "method": "pane.list", "params": {}}))["result"]["panes"][0]["pane_id"]


LIFE_TYPES = ["workspace.created", "workspace.closed", "tab.created", "tab.closed", "pane.created", "pane.closed",
              "pane.updated", "pane.focused", "pane.exited", "pane.agent_detected", "layout.updated"]


def subscriptions(kind, pane):
    subs = []
    if kind in ("lifecycle", "both"):
        subs += [{"type": t} for t in LIFE_TYPES]
    if kind in ("status", "both"):
        subs.append({"type": "pane.agent_status_changed", "pane_id": pane})
    return subs


def open_subscriber(kind, pane, rcvbuf):
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    if rcvbuf:
        s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, rcvbuf)
    log("subscriber SO_RCVBUF requested=%s effective=%d SO_SNDBUF=%d" % (
        rcvbuf, s.getsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF), s.getsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF)))
    s.connect(SOCK)
    req = {"id": "evlost-sub", "method": "events.subscribe", "params": {"subscriptions": subscriptions(kind, pane)}}
    s.sendall(json.dumps(req).encode() + b"\n")
    buf = b""
    s.settimeout(10)
    while b"\n" not in buf:
        c = s.recv(65536)
        if not c:
            raise SystemExit("EOF before ack: %r" % buf)
        buf += c
    ack, rest = buf.split(b"\n", 1)
    log("ACK %s (extra bytes after ack in same read: %d)" % (ack.decode(), len(rest)))
    return s, rest


class Burst:
    def __init__(self, kind, n, via, pane, threads):
        self.kind, self.n, self.via, self.pane, self.threads = kind, n, via, pane, threads
        self.sent = 0; self.errors = 0; self.lock = threading.Lock()
        self.seq = 1000000 + int(time.time()) % 100000 * 1000  # fresh, increasing; seq is tracked per source
        self.src = "evlost%d" % int(time.time())
        self.done = threading.Event(); self.t_start = None; self.t_end = None; self.last_err = None

    def next_seq(self):
        with self.lock:
            self.seq += 1
            return self.seq

    def one(self, i):
        k = self.kind
        if k == "report":
            # Serialize seq with state so each report is accepted and flips the state: working/idle alternate.
            with self.lock:
                self.seq += 1; seq = self.seq
            state = ("working", "idle", "blocked")[seq % 3]
            if self.via == "cli":
                r = subprocess.run(H + ["pane", "report-agent", self.pane, "--source", self.src, "--agent", "e%d" % seq,
                                        "--state", state, "--message", "{}", "--seq", str(seq)],
                                   capture_output=True, text=True)
                out = r.stdout
            else:
                out = call({"id": "b%d" % seq, "method": "pane.report_agent", "params": {
                    "pane_id": self.pane, "source": self.src, "agent": "e%d" % seq, "state": state,
                    "message": "{}", "seq": seq}})
            if '"error"' in out:
                raise RuntimeError(out[:200])
        elif k == "split":
            r = json.loads(call({"id": "s%d" % i, "method": "pane.split", "params": {
                "target_pane_id": self.pane, "direction": "right", "focus": False}}))
            pid = r["result"]["pane"]["pane_id"]
            call({"id": "c%d" % i, "method": "pane.close", "params": {"pane_id": pid}})
        elif k == "tab":
            r = json.loads(call({"id": "t%d" % i, "method": "tab.create", "params": {"focus": False, "cwd": "/tmp/paddock-test-ws"}}))
            tid = r["result"]["tab"]["tab_id"]
            call({"id": "tc%d" % i, "method": "tab.close", "params": {"tab_id": tid}})
        else:
            raise SystemExit("unknown burst kind")

    def worker(self):
        while True:
            with self.lock:
                if self.sent >= self.n:
                    return
                i = self.sent; self.sent += 1
            try:
                self.one(i)
            except Exception as e:
                with self.lock:
                    self.errors += 1; self.last_err = repr(e)
                if self.errors > 25:
                    return

    def run(self):
        self.t_start = time.monotonic()
        ts = [threading.Thread(target=self.worker) for _ in range(self.threads)]
        [t.start() for t in ts]; [t.join() for t in ts]
        self.t_end = time.monotonic()
        self.done.set()
        log("BURST done kind=%s requested=%d issued=%d errors=%d last_err=%s elapsed=%.2fs" % (
            self.kind, self.n, self.sent, self.errors, self.last_err, self.t_end - self.t_start))


def main():
    global LOGF
    ap = argparse.ArgumentParser()
    ap.add_argument("--subs", default="status", choices=["status", "lifecycle", "both"])
    ap.add_argument("--rcvbuf", type=int, default=0)
    ap.add_argument("--stall", type=float, default=5.0, help="seconds to not read after the burst began")
    ap.add_argument("--burst-kind", default="report", choices=["report", "split", "tab"])
    ap.add_argument("--burst-n", type=int, default=2000)
    ap.add_argument("--burst-via", default="sock", choices=["sock", "cli"])
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--read", default="stop", choices=["stop", "slow"],
                    help="stop: do not recv until the stall ends; slow: recv --slow-bytes every --slow-sleep s during the burst")
    ap.add_argument("--slow-bytes", type=int, default=256)
    ap.add_argument("--slow-sleep", type=float, default=0.05)
    ap.add_argument("--post-read", type=float, default=30.0, help="max seconds to keep reading after the stall")
    ap.add_argument("--idle", type=float, default=3.0, help="stop after this many idle seconds once the burst is done")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    LOGF = open(a.out + ".log", "w")
    log("args %s" % vars(a))
    pane = first_pane()
    log("pane %s" % pane)
    sub, rest = open_subscriber(a.subs, pane, a.rcvbuf)
    raw = open(a.out + ".raw", "wb")
    raw.write(rest)
    total = len(rest)
    burst = Burst(a.burst_kind, a.burst_n, a.burst_via, pane, a.threads)
    bt = threading.Thread(target=burst.run); bt.start()
    stall_until = time.monotonic() + a.stall
    log("subscriber STOPPED reading (mode=%s)" % a.read)
    if a.read == "slow":
        sub.settimeout(1)
        while time.monotonic() < stall_until:
            try:
                c = sub.recv(a.slow_bytes)
            except socket.timeout:
                continue
            if not c:
                log("EOF during slow read after %d bytes" % total); break
            raw.write(c); total += len(c)
            time.sleep(a.slow_sleep)
    else:
        time.sleep(a.stall)
    # kernel buffer state at the end of the stall (the burst may still be running)
    try:
        import fcntl, termios, struct
        buf = fcntl.ioctl(sub.fileno(), termios.FIONREAD, struct.pack("i", 0))
        log("bytes queued in subscriber socket at stall end (FIONREAD) = %d; burst_done=%s issued=%d" % (struct.unpack("i", buf)[0], burst.done.is_set(), burst.sent))
    except Exception as e:
        log("FIONREAD failed %r" % e)
    log("subscriber RESUMED reading; total bytes so far %d" % total)
    tl = open(a.out + ".timeline", "w")
    sub.settimeout(1.0)
    t_resume = time.monotonic()
    t_end = t_resume + a.post_read
    last_data = time.monotonic()
    reads = 0; eof = False; err = None; lines = 0
    while time.monotonic() < t_end:
        try:
            c = sub.recv(65536)
        except socket.timeout:
            if burst.done.is_set() and time.monotonic() - last_data > a.idle:
                log("recv idle %.1fs after burst finished; stopping read" % a.idle)
                break
            continue
        except OSError as e:
            err = repr(e); log("recv error %s" % err); break
        if not c:
            eof = True; log("EOF (server closed the connection) after %d bytes total" % total); break
        last_data = time.monotonic()
        raw.write(c); total += len(c); reads += 1; lines += c.count(b"\n")
        tl.write("%.3f read=%d bytes=%d cum_bytes=%d cum_lines=%d burst_issued=%d\n" % (time.monotonic() - T0, reads, len(c), total, lines, burst.sent))
    tl.close()
    bt.join()
    raw.close()
    log("DONE reads=%d total_bytes=%d eof=%s err=%s" % (reads, total, eof, err))
    # liveness probe: if the connection is still open, report 3 more states and see whether they arrive on it
    last_seq = None
    if not eof and not err and a.burst_kind == "report":
        for j in range(3):
            sq = burst.next_seq(); last_seq = sq
            call({"id": "post%d" % j, "method": "pane.report_agent", "params": {
                "pane_id": pane, "source": burst.src, "agent": "e%d" % sq, "state": ("working", "idle", "blocked")[sq % 3],
                "message": "{}", "seq": sq}})
        sub.settimeout(2.0)
        post = b""
        try:
            while True:
                c = sub.recv(65536)
                if not c:
                    log("POST-PROBE: EOF"); break
                post += c
        except socket.timeout:
            pass
        except OSError as e:
            log("POST-PROBE error %r" % e)
        log("POST-PROBE bytes=%d lines=%d (3 more reports sent, last seq %s)" % (len(post), post.count(b"\n"), last_seq))
        with open(a.out + ".post.raw", "wb") as f:
            f.write(post)
    elif a.burst_kind == "report":
        last_seq = burst.seq
    # recovery check: a fresh connection, session.snapshot, compared with what the burst last did
    time.sleep(1.0)
    snap_line = call({"id": "snap", "method": "session.snapshot", "params": {}})
    open(a.out + ".snapshot.json", "w").write(snap_line + "\n")
    snap = json.loads(snap_line)["result"]["snapshot"]
    panes = snap["panes"]
    log("SNAPSHOT fresh connection: workspaces=%d tabs=%d panes=%d pane_ids=%s" % (len(snap["workspaces"]), len(snap["tabs"]), len(panes), [p["pane_id"] for p in panes][:8]))
    for p in panes:
        if p["pane_id"] == pane:
            log("SNAPSHOT %s agent=%r agent_status=%r" % (pane, p.get("agent"), p.get("agent_status")))
            if last_seq is not None:
                exp_state = ("working", "idle", "blocked")[last_seq % 3]
                log("EXPECTED after last report seq %d: agent='e%d' agent_status=%r -> %s" % (last_seq, last_seq, exp_state,
                    "CONSISTENT" if (p.get("agent") == "e%d" % last_seq and p.get("agent_status") == exp_state) else "MISMATCH"))
    sub.close()


if __name__ == "__main__":
    main()
