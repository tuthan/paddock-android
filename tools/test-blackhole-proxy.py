#!/usr/bin/env python3
"""Self-test of tools/blackhole-proxy.py against a local echo server. Touches nothing but loopback ports it picks itself.

    tools/test-blackhole-proxy.py

The device flow for Phase 06 relies on one behaviour above all: with `delay` on, a request reaches the target at once, the
answer is held, and a `freeze` in that window loses the answer for good (a later `thaw` does not deliver it).
"""
import os, socket, subprocess, sys, threading, time, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
PROXY = os.path.join(HERE, "blackhole-proxy.py")


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0)); return s.getsockname()[1]


class Target:
    """Answers every chunk it gets with the same bytes upper-cased, and remembers what it received and when."""

    def __init__(self):
        self.srv = socket.socket(); self.srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.srv.bind(("127.0.0.1", 0)); self.srv.listen(4)
        self.port = self.srv.getsockname()[1]
        self.received = []
        threading.Thread(target=self.serve, daemon=True).start()

    def serve(self):
        while True:
            try: conn, _ = self.srv.accept()
            except OSError: return
            threading.Thread(target=self.handle, args=(conn,), daemon=True).start()

    def handle(self, conn):
        with conn:
            while True:
                try: data = conn.recv(4096)
                except OSError: return
                if not data: return
                self.received.append((time.monotonic(), data))
                conn.sendall(data.upper())


class ProxyTest(unittest.TestCase):
    def setUp(self):
        self.target = Target()
        self.listen, self.control = free_port(), free_port()
        self.proc = subprocess.Popen([sys.executable, PROXY, str(self.listen), str(self.target.port), str(self.control)],
                                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        self.proc.stdout.readline()                          # "proxy :… -> :…"
        self.addCleanup(self.stop_proxy)
        self.addCleanup(self.target.srv.close)

    def stop_proxy(self):
        self.proc.kill(); self.proc.wait(); self.proc.stdout.close()

    def ctl(self, command):
        with socket.create_connection(("127.0.0.1", self.control), timeout=3) as s:
            s.sendall((command + "\n").encode()); return s.makefile().readline().strip()

    def client(self):
        s = socket.create_connection(("127.0.0.1", self.listen), timeout=3)
        self.addCleanup(s.close)
        return s

    def read_for(self, s, seconds):
        s.settimeout(0.05); end = time.monotonic() + seconds; got = b""
        while time.monotonic() < end:
            try:
                chunk = s.recv(4096)
                if not chunk: break
                got += chunk
            except socket.timeout:
                pass
        return got

    def test_it_relays_untouched_by_default(self):
        c = self.client(); t0 = time.monotonic()
        c.sendall(b"hello"); self.assertEqual(self.read_for(c, 0.4), b"HELLO")
        self.assertLess(time.monotonic() - t0, 0.8)

    def test_delay_holds_the_answer_but_not_the_request(self):
        self.assertTrue(self.ctl("delay 600").startswith("delay 600 at "))
        c = self.client(); sent = time.monotonic()
        c.sendall(b"ping")
        time.sleep(0.15)
        self.assertEqual(len(self.target.received), 1, "the request reached the target at once")
        self.assertLess(self.target.received[0][0] - sent, 0.3)
        self.assertEqual(self.read_for(c, 0.2), b"", "the answer is still held")
        self.assertEqual(self.read_for(c, 0.8), b"PING", "and arrives after the delay")

    def test_a_freeze_inside_the_window_loses_the_answer_for_good(self):
        self.ctl("delay 800")
        c = self.client(); c.sendall(b"request")
        time.sleep(0.2)
        self.assertEqual([d for _, d in self.target.received], [b"request"], "the request was delivered")
        self.ctl("freeze")
        self.assertEqual(self.read_for(c, 1.2), b"", "the held answer was discarded")
        self.ctl("thaw")
        self.assertEqual(self.read_for(c, 1.2), b"", "and a thaw does not bring it back")

    def test_freeze_discards_both_directions_and_thaw_relays_again(self):
        c = self.client(); c.sendall(b"one"); self.assertEqual(self.read_for(c, 0.3), b"ONE")
        self.ctl("freeze")
        c.sendall(b"two"); self.assertEqual(self.read_for(c, 0.4), b"")
        self.assertEqual([d for _, d in self.target.received], [b"one"], "nothing got through while frozen")
        self.ctl("thaw")
        c2 = self.client(); c2.sendall(b"three"); self.assertEqual(self.read_for(c2, 0.4), b"THREE")

    def test_delay_zero_turns_it_off_and_order_is_kept(self):
        self.ctl("delay 300")
        c = self.client(); c.sendall(b"a"); time.sleep(0.05)
        self.ctl("delay 0"); c.sendall(b"b")
        self.assertEqual(self.read_for(c, 0.8), b"AB", "the earlier chunk is not overtaken by the later one")

    def test_an_unknown_command_is_refused(self):
        self.assertTrue(self.ctl("explode").startswith("unknown command"))
        self.assertTrue(self.ctl("delay soon").startswith("unknown command"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
