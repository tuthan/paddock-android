#!/usr/bin/env python3
"""TCP proxy that can silently drop traffic, to simulate a dead link without a RST (stands in for airplane mode).

  blackhole-proxy.py [LISTEN_PORT=2223] [TARGET_PORT=2222] [CONTROL_PORT=2224]

Connect to CONTROL_PORT and send one newline-terminated command:
  freeze       bytes in both directions are read and discarded and sockets stay open, so the client sees a stalled link,
               not a reset. Anything still held by `delay` is discarded too and is never delivered after a thaw.
  thaw         relay again (the old connections are broken by what was dropped; a client reconnects).
  delay MS     hold every byte going from the target to the client for MS milliseconds (0 turns it off). The client's own
               bytes are not held. A client that sends a request therefore sees the answer MS later, which leaves a window
               in which `freeze` loses the answer to a request that has already been delivered.
Listens on 127.0.0.1 only; the emulator reaches it at 10.0.2.2.
"""
import queue, socket, sys, threading, time

LISTEN = int(sys.argv[1]) if len(sys.argv) > 1 else 2223
TARGET = int(sys.argv[2]) if len(sys.argv) > 2 else 2222
CONTROL = int(sys.argv[3]) if len(sys.argv) > 3 else 2224
frozen = threading.Event()
delay_ms = [0]          # target -> client latency
generation = [0]        # bumped by every freeze: bytes queued before it are never delivered

def close_both(src, dst):
    for s in (src, dst):
        try: s.shutdown(socket.SHUT_RDWR)
        except OSError: pass

def pump(src, dst):
    """client -> target: forwarded at once, discarded while frozen."""
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
        close_both(src, dst)

def pump_delayed(src, dst):
    """target -> client: each chunk is released delay_ms after it arrived, in order, unless a freeze came first."""
    held = queue.Queue()

    def sender():
        while True:
            item = held.get()
            if item is None:
                return
            due, gen, data = item
            wait = due - time.monotonic()
            if wait > 0:
                time.sleep(wait)
            if frozen.is_set() or gen != generation[0]:
                continue
            try:
                dst.sendall(data)
            except OSError:
                return

    threading.Thread(target=sender, daemon=True).start()
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            if not frozen.is_set():
                held.put((time.monotonic() + delay_ms[0] / 1000.0, generation[0], data))
    except OSError:
        pass
    finally:
        held.put(None)
        close_both(src, dst)

def serve(port, handler):
    srv = socket.socket(); srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind(("127.0.0.1", port)); srv.listen(16)
    while True:
        conn, _ = srv.accept()
        threading.Thread(target=handler, args=(conn,), daemon=True).start()

def proxy(client):
    up = socket.create_connection(("127.0.0.1", TARGET))
    threading.Thread(target=pump, args=(client, up), daemon=True).start()
    pump_delayed(up, client)

def control(conn):
    with conn:
        line = conn.makefile().readline().strip()
        cmd, _, arg = line.partition(" ")
        if cmd == "freeze":
            generation[0] += 1; frozen.set()
        elif cmd == "thaw":
            frozen.clear()
        elif cmd == "delay" and arg.strip().isdigit():
            delay_ms[0] = int(arg)
        else:
            conn.sendall(f"unknown command: {line}\n".encode()); return
        conn.sendall(f"{line} at {time.time():.3f}\n".encode())
        print(f"{time.time():.3f} {line}", flush=True)

threading.Thread(target=serve, args=(CONTROL, control), daemon=True).start()
print(f"proxy :{LISTEN} -> :{TARGET}, control :{CONTROL}", flush=True)
serve(LISTEN, proxy)
