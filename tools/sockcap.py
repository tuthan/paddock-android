#!/usr/bin/env python3
"""Raw herdr socket helper for fixture capture (stdlib only).

  sockcap.py SOCK request   JSON            one request, print the first response line
  sockcap.py SOCK subscribe JSON SECONDS    send JSON, print every line for SECONDS
  sockcap.py SOCK raw       TEXT            send TEXT + newline verbatim, print the first line
"""
import socket, sys, time

def connect(path):
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.connect(path)
    return s

def lines(s, deadline):
    buf = b""
    while True:
        left = deadline - time.monotonic()
        if left <= 0:
            return
        s.settimeout(left)
        try:
            chunk = s.recv(65536)
        except socket.timeout:
            return
        if not chunk:
            if buf:
                yield buf.decode(errors="replace")
            return
        buf += chunk
        while b"\n" in buf:
            line, buf = buf.split(b"\n", 1)
            yield line.decode(errors="replace")

def main():
    sock, mode, payload = sys.argv[1], sys.argv[2], sys.argv[3]
    secs = float(sys.argv[4]) if len(sys.argv) > 4 else 5.0
    s = connect(sock)
    s.sendall(payload.encode() + b"\n")
    if mode in ("request", "raw"):
        for line in lines(s, time.monotonic() + 5):
            print(line, flush=True)
            break
    elif mode == "subscribe":
        for line in lines(s, time.monotonic() + secs):
            print(line, flush=True)
    else:
        sys.exit("unknown mode")

if __name__ == "__main__":
    main()
