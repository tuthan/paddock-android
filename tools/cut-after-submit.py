#!/usr/bin/env python3
"""Freezes the proxy's link the moment the fake agent logs its Nth submission (Phase 06's device flow).

    cut-after-submit.py FAKE_AGENT_LOG N CONTROL_PORT [TIMEOUT_SECONDS=120]

With `delay` on, a prompt reaches the host at once and its answer is held in the proxy for the delay. This script is the other
half: it connects to the proxy's control port first (so the freeze costs one send, not a connect), polls the fake agent's
log, and sends `freeze` as soon as the Nth submission shows. The answer is then discarded with the rest of what the proxy
held, and the phone is left with a request that arrived and an answer that never will. Prints one line, the time the agent
logged the submission, the time the freeze was sent and the gap in milliseconds, and exits 0; exits 1 on timeout.
"""
import json, socket, sys, time

log, n, control = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
timeout = float(sys.argv[4]) if len(sys.argv) > 4 else 120.0


def submissions():
    try:
        with open(log) as f:
            rows = [json.loads(line) for line in f if '"submit"' in line]
    except (OSError, ValueError):
        return []
    return [r for r in rows if r.get("event") == "submit"]


sock = socket.create_connection(("127.0.0.1", control), timeout=5)
end = time.monotonic() + timeout
while time.monotonic() < end:
    rows = submissions()
    if len(rows) >= n:
        sock.sendall(b"freeze\n")
        sent = time.time()
        reply = sock.makefile().readline().strip()
        t = rows[n - 1]["t"]
        print(f"submit_t={t:.3f} freeze_sent_t={sent:.3f} gap_ms={(sent - t) * 1000:.0f} proxy={reply!r}", flush=True)
        sys.exit(0)
    time.sleep(0.002)
print(f"timed out waiting for submission {n}", flush=True)
sys.exit(1)
