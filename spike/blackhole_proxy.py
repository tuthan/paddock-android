#!/usr/bin/env python3
"""TCP proxy that can silently drop traffic, to simulate a dead link without a RST (stands in for airplane mode).

  blackhole_proxy.py [LISTEN_PORT=2223] [TARGET_PORT=2222] [CONTROL_PORT=2224]

Connect to CONTROL_PORT and send `freeze` or `thaw` (newline-terminated). While frozen, bytes in both
directions are read and discarded and sockets stay open, so the client sees a stalled link, not a reset.
Listens on 127.0.0.1 only; the emulator reaches it at 10.0.2.2.
"""
import socket, sys, threading, time

LISTEN = int(sys.argv[1]) if len(sys.argv) > 1 else 2223
TARGET = int(sys.argv[2]) if len(sys.argv) > 2 else 2222
CONTROL = int(sys.argv[3]) if len(sys.argv) > 3 else 2224
frozen = threading.Event()

def pump(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            if not frozen.is_set():
                dst.sendall(data)
    except OSError:
        pass
    finally:
        for s in (src, dst):
            try: s.shutdown(socket.SHUT_RDWR)
            except OSError: pass

def serve(port, handler):
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port)); srv.listen(16)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=handler, args=(conn,), daemon=True).start()

def proxy(client):
    up = socket.create_connection(("127.0.0.1", TARGET))
    threading.Thread(target=pump, args=(client, up), daemon=True).start()
    pump(up, client)

def control(conn):
    with conn:
        cmd = conn.makefile().readline().strip()
        if cmd == "freeze": frozen.set()
        elif cmd == "thaw": frozen.clear()
        conn.sendall(f"{cmd} at {time.time():.3f}\n".encode())
        print(f"{time.time():.3f} {cmd}", flush=True)

threading.Thread(target=serve, args=(CONTROL, control), daemon=True).start()
print(f"proxy :{LISTEN} -> :{TARGET}, control :{CONTROL}", flush=True)
serve(LISTEN, proxy)
