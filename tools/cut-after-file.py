#!/usr/bin/env python3
"""Freezes the proxy's link the moment a file matching a glob appears (Phase 08's device flow for an answer whose reply is lost).

    cut-after-file.py GLOB CONTROL_PORT [TIMEOUT_SECONDS=120]

The other half of `cut-after-submit.py`, for the answer to a permission request: `paddock-decide.py decide` writes the decision file
and only then prints `decided`. With `delay` on in the proxy that reply is held for the delay; this script connects to the proxy's
control port first (so the freeze costs one send), polls for the decision file, and sends `freeze` the instant it exists. The reply
is discarded with whatever else the proxy held, so the phone is left with a write that landed and no word of it. Prints one line, the
time the file was first seen, the time the freeze was sent, the gap and the file's own mtime; exits 0, or 1 on timeout.
"""
import glob, os, socket, sys, time

pattern, control = sys.argv[1], int(sys.argv[2])
timeout = float(sys.argv[3]) if len(sys.argv) > 3 else 120.0
sock = socket.create_connection(("127.0.0.1", control), timeout=5)
end = time.monotonic() + timeout
while time.monotonic() < end:
    found = glob.glob(pattern)
    if found:
        seen = time.time()
        sock.sendall(b"freeze\n")
        sent = time.time()
        reply = sock.makefile().readline().strip()
        try:
            mtime = os.stat(found[0]).st_mtime
        except OSError:
            mtime = seen
        print(f"file_mtime={mtime:.3f} seen_t={seen:.3f} freeze_sent_t={sent:.3f} gap_ms={(sent - mtime) * 1000:.0f} proxy={reply!r}", flush=True)
        sys.exit(0)
    time.sleep(0.001)
print("timed out waiting for the file", flush=True)
sys.exit(1)
