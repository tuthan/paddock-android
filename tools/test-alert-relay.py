#!/usr/bin/env python3
"""Tests of host/paddock-alert-relay.py. Never touches the default herdr session.

    tools/test-alert-relay.py            configuration, decisions, queue, delivery and the whole relay against a fake herdr (about 20 seconds)
    tools/test-alert-relay.py --live     adds the run against the disposable session: PADDOCK_TEST_SOCKET must be
                                         .../sessions/paddock-test[-suffix]/herdr.sock, like the Kotlin integration tests

The fake herdr is a unix socket server that answers `session.snapshot` and `events.subscribe` as herdr 0.9.1 does (an
acknowledgement, then one line per event; a status subscription without a pane is refused). The notifier is a loopback HTTP
server that records what it is sent, so what leaves the machine is read as the notifier would read it.
"""
import asyncio
import importlib.util
import json
import os
import re
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

def put(path, content, mode="w"):
    with open(path, mode) as f:
        f.write(content)


HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "..", "host", "paddock-alert-relay.py")

spec = importlib.util.spec_from_file_location("alert_relay", SCRIPT)
R = importlib.util.module_from_spec(spec)
spec.loader.exec_module(R)
R.COALESCE = 0.01
R.RECONNECT_BASE = 0.05

LIVE = "--live" in sys.argv
if LIVE:
    sys.argv.remove("--live")

BASE = {"socket": "/home/u/.config/herdr/herdr.sock", "profile": "workstation", "label": "ws", "delivery": "ntfy",
        "ntfy": {"url": "https://ntfy.example.org", "topic": "paddock-abc123"}}


def cfg(**over):
    data = json.loads(json.dumps(BASE))
    data.update(over)
    return R.parse_config(data)


def refuses(test, data, mode=0o600, fragment=None):
    with test.assertRaises(R.ConfigError) as caught:
        R.parse_config(data, mode)
    if fragment:
        test.assertIn(fragment, str(caught.exception))


class ConfigTests(unittest.TestCase):
    def test_a_good_ntfy_config(self):
        c = cfg()
        self.assertEqual((c.profile, c.session, c.delivery, c.alert_on, c.debounce, c.expiry), ("workstation", "default", "ntfy", ("blocked", "done"), 15.0, 600.0))
        self.assertEqual(c.ntfy_url, "https://ntfy.example.org")

    def test_the_session_comes_from_the_socket_path(self):
        self.assertEqual(cfg(socket="/home/u/.config/herdr/sessions/paddock-test/herdr.sock").session, "paddock-test")
        self.assertEqual(cfg(socket="/home/u/.config/herdr/herdr.sock").session, "default")
        self.assertEqual(cfg(socket="/home/u/.config/herdr/sessions/my.work-2/herdr.sock").session, "my.work-2")  # herdr allows dots in names

    def test_unifiedpush_config(self):
        c = R.parse_config({**{k: v for k, v in BASE.items() if k != "ntfy"}, "delivery": "unifiedpush", "unifiedpush": {"endpoint_file": "/home/u/.config/paddock/push-endpoint.json"}})
        self.assertEqual(c.delivery, "unifiedpush")
        self.assertTrue(c.endpoint_file.endswith("push-endpoint.json"))

    def test_hostile_values_are_refused(self):
        for key, value in [
            ("socket", "relative/herdr.sock"), ("socket", "/home/u/not-herdr.sock"), ("socket", "/home/u/herdr.sock\0"), ("socket", 5),
            ("profile", "Workstation"), ("profile", ""), ("profile", "../x"), ("profile", "a" * 42), ("profile", "-a"), ("profile", 7),
            ("delivery", "email"), ("delivery", None),
            ("alert_on", []), ("alert_on", ["working"]), ("alert_on", ["blocked", "blocked"]), ("alert_on", "blocked"),
            ("debounce_seconds", -1), ("debounce_seconds", True), ("debounce_seconds", "5"), ("debounce_seconds", 99999),
            ("expiry_seconds", 1), ("heartbeat_seconds", 0), ("label", ""), ("label", "\n\t"), ("state_file", "relative.json"),
            ("allow_insecure_http", "yes"), ("extra", 1),
        ]:
            with self.subTest(key=key, value=value):
                refuses(self, {**BASE, key: value})

    def test_ntfy_table_is_checked(self):
        for table in [None, {}, {"url": "https://x.example"}, {"topic": "t"}, {"url": "ftp://x.example", "topic": "t"},
                      {"url": "https://u:p@x.example", "topic": "t"}, {"url": "https://x.example?a=b", "topic": "t"}, {"url": "https://x.example#f", "topic": "t"},
                      {"url": "https://x.example", "topic": "has space"}, {"url": "https://x.example", "topic": "a/b"}, {"url": "https://x.example", "topic": "t" * 65},
                      {"url": "https://x.example", "topic": "t", "token": "a b"}, {"url": "https://x.example", "topic": "t", "token": "a\nb"},
                      {"url": "https://x.example", "topic": "t", "extra": 1}]:
            with self.subTest(table=table):
                refuses(self, {**BASE, "ntfy": table})

    def test_plain_http_only_for_loopback_or_when_allowed_and_never_with_a_token(self):
        refuses(self, {**BASE, "ntfy": {"url": "http://ntfy.lan", "topic": "t"}}, fragment="http")
        self.assertEqual(R.parse_config({**BASE, "ntfy": {"url": "http://127.0.0.1:8080", "topic": "t"}}).ntfy_url, "http://127.0.0.1:8080")
        self.assertEqual(R.parse_config({**BASE, "allow_insecure_http": True, "ntfy": {"url": "http://ntfy.lan", "topic": "t"}}).ntfy_url, "http://ntfy.lan")
        refuses(self, {**BASE, "allow_insecure_http": True, "ntfy": {"url": "http://ntfy.lan", "topic": "t", "token": "tk_x"}})

    def test_a_token_in_a_readable_file_is_refused(self):
        data = {**BASE, "ntfy": {"url": "https://x.example", "topic": "t", "token": "tk_secret"}}
        R.parse_config(data, 0o600)
        for mode in (0o640, 0o604, 0o644, 0o660):
            refuses(self, data, mode, fragment="readable")
        R.parse_config({**BASE}, 0o644)  # no token, no problem

    def test_files_and_toml(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "c.toml")
            put(path, 'socket = "/home/u/.config/herdr/herdr.sock"\nprofile = "w"\ndelivery = "ntfy"\n[ntfy]\nurl = "https://x.example"\ntopic = "t"\n')
            self.assertEqual(R.load_config(path).profile, "w")
            put(path, "not = [valid")
            with self.assertRaises(R.ConfigError):
                R.load_config(path)
            put(path, b"\xff\xfe", "wb")
            with self.assertRaises(R.ConfigError):
                R.load_config(path)
            put(path, "#" * 70000)
            with self.assertRaises(R.ConfigError):
                R.load_config(path)
            with self.assertRaises(R.ConfigError):
                R.load_config(os.path.join(d, "missing.toml"))


class MessageTests(unittest.TestCase):
    ALERT = R.Alert("term_65cdbb19f1e6944d", "w2:p12C", "blocked", 1790948507)

    def test_the_link_names_a_target_and_what_the_relay_saw(self):
        link = R.build_link(cfg(), self.ALERT, 42)
        parts = urllib.parse.urlsplit(link)
        self.assertEqual((parts.scheme, parts.netloc), ("paddock", "open"))
        q = dict(urllib.parse.parse_qsl(parts.query, strict_parsing=True))
        self.assertEqual(q, {"h": "workstation", "s": "default", "t": "term_65cdbb19f1e6944d", "p": "w2:p12C", "st": "blocked", "at": "1790948507", "n": "42"})
        self.assertNotIn(" ", link)
        self.assertIn("w2%3Ap12C", link)
        # the Kotlin side (DeepLinkTest.exactlyWhatTheHostRelayWritesParses) parses this very string
        self.assertEqual(R.build_link(cfg(), R.Alert("term_65cdbb19f1e6944d", "w2:p12C", "blocked", 1790948507), 42),
                         "paddock://open?h=workstation&s=default&t=term_65cdbb19f1e6944d&p=w2%3Ap12C&st=blocked&at=1790948507&n=42")

    def test_no_field_is_phone_local_or_text_from_a_pane(self):
        q = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(R.build_link(cfg(), self.ALERT, 1)).query))
        self.assertNotIn("e", q)       # no epoch: the relay cannot know the phone's
        self.assertEqual(set(q), {"h", "s", "t", "p", "st", "at", "n"})

    def test_the_ntfy_message_is_generic(self):
        body = R.ntfy_payload(cfg(), self.ALERT, 7)
        self.assertEqual(body["title"], "Paddock: attention on ws")
        self.assertEqual(body["message"], "An agent needs you.")
        self.assertEqual(body["topic"], "paddock-abc123")
        self.assertEqual(body["click"], R.build_link(cfg(), self.ALERT, 7))
        self.assertEqual(body["actions"], [{"action": "view", "label": "Review", "url": body["click"], "clear": True}])
        self.assertEqual(set(body), {"topic", "title", "message", "priority", "tags", "click", "actions"})
        done = R.ntfy_payload(cfg(), R.Alert("t", "p", "done", 1), 8)
        self.assertEqual(done["message"], "An agent finished.")
        self.assertLess(done["priority"], body["priority"])

    def test_the_unifiedpush_payload_has_no_content(self):
        self.assertEqual(R.unifiedpush_payload(cfg(), "abcd"), {"v": 1, "h": "workstation", "n": "abcd"})

    def test_labels_are_made_printable(self):
        self.assertEqual(R.printable_label("a\r\nb\x00c" + "x" * 80), "abc" + "x" * 37)


def agent(tid, status, seq=None, pane=None):
    return R.Agent(tid, pane or "w1:" + tid, status, seq)


class TrackerTests(unittest.TestCase):
    def setUp(self):
        self.t = R.Tracker(("blocked", "done"), 15)
        self.now = 1000.0

    def snap(self, *agents, advance=0):
        self.now += advance
        return self.t.snapshot(list(agents), self.now, 1_790_000_000 + self.now)

    def event(self, pane, status, advance=0):
        self.now += advance
        return self.t.status_event(pane, status, self.now, 1_790_000_000 + self.now)

    def test_the_first_read_is_a_silent_baseline(self):
        self.assertEqual(self.snap(agent("a", "blocked", 1), agent("b", "done", 2), agent("c", "idle", 3)), [])

    def test_a_new_blocked_alerts_once_however_often_it_is_read(self):
        self.snap(agent("a", "idle", 1))
        self.assertEqual([x.state for x in self.snap(agent("a", "blocked", 2), advance=1)], ["blocked"])
        self.assertEqual(self.snap(agent("a", "blocked", 2), advance=1), [])
        self.assertEqual(self.snap(agent("a", "blocked", 2), advance=60), [])

    def test_only_blocked_and_done_alert(self):
        self.snap(agent("a", "unknown", 1))
        for n, status in enumerate(["idle", "working", "unknown", "idle"], start=2):
            self.assertEqual(self.snap(agent("a", status, n), advance=20), [])
        self.assertEqual([x.state for x in self.snap(agent("a", "done", 9), advance=20)], ["done"])

    def test_alert_on_can_be_narrowed(self):
        self.t = R.Tracker(("blocked",), 15)
        self.snap(agent("a", "working", 1))
        self.assertEqual(self.snap(agent("a", "done", 2), advance=20), [])
        self.assertEqual(len(self.snap(agent("a", "blocked", 3), advance=20)), 1)

    def test_a_flap_inside_the_debounce_window_is_one_alert_and_after_it_two(self):
        self.snap(agent("a", "idle", 1))
        self.assertEqual(len(self.snap(agent("a", "blocked", 2), advance=1)), 1)
        self.snap(agent("a", "working", 3), advance=1)
        self.assertEqual(self.snap(agent("a", "blocked", 4), advance=1), [])
        self.assertEqual(self.t.debounced, 1)
        self.snap(agent("a", "working", 5), advance=1)
        self.assertEqual(len(self.snap(agent("a", "blocked", 6), advance=30)), 1)

    def test_two_terminals_do_not_debounce_each_other(self):
        self.snap(agent("a", "idle", 1), agent("b", "idle", 2))
        out = self.snap(agent("a", "blocked", 3), agent("b", "blocked", 4), advance=1)
        self.assertEqual(sorted(x.terminal_id for x in out), ["a", "b"])

    def test_an_event_then_the_read_that_confirms_it_is_one_alert(self):
        self.snap(agent("a", "idle", 1, "w1:p1"))
        alerts, needs_read = self.event("w1:p1", "blocked", advance=1)
        self.assertEqual(([x.state for x in alerts], needs_read), (["blocked"], False))
        self.assertEqual(self.snap(agent("a", "blocked", 2, "w1:p1"), advance=1), [])  # the read fills in the sequence
        self.assertEqual(self.snap(agent("a", "blocked", 2, "w1:p1"), advance=60), [])

    def test_a_read_then_the_event_that_it_already_showed_is_one_alert(self):
        self.snap(agent("a", "idle", 1, "w1:p1"))
        self.assertEqual(len(self.snap(agent("a", "blocked", 2, "w1:p1"), advance=1)), 1)
        self.assertEqual(self.event("w1:p1", "blocked", advance=1), ([], False))

    def test_a_repeated_sample_is_not_a_transition(self):
        self.snap(agent("a", "blocked", 1, "w1:p1"))
        self.assertEqual(self.event("w1:p1", "blocked"), ([], False))

    def test_a_missed_round_trip_is_found_by_the_sequence(self):
        self.snap(agent("a", "blocked", 5))
        # blocked, then working and blocked again while events were being lost: same status, later sequence
        self.assertEqual(len(self.snap(agent("a", "blocked", 7), advance=60)), 1)

    def test_an_agent_that_appears_blocked_after_the_baseline_alerts(self):
        self.snap(agent("a", "idle", 1))
        self.assertEqual([x.terminal_id for x in self.snap(agent("a", "idle", 1), agent("b", "blocked", 2), advance=1)], ["b"])

    def test_a_pane_the_last_read_did_not_show_needs_a_read(self):
        self.snap(agent("a", "idle", 1, "w1:p1"))
        self.assertEqual(self.event("w9:p9", "blocked"), ([], True))

    def test_a_vanished_terminal_is_forgotten_and_may_alert_again_when_it_returns(self):
        self.snap(agent("a", "idle", 1))
        self.snap(agent("a", "blocked", 2), advance=1)
        self.snap(advance=1)
        self.assertEqual(len(self.snap(agent("a", "blocked", 3), advance=1)), 1)  # a new terminal under the same id is new

    def test_a_change_during_a_reconnect_gap_alerts_once(self):
        self.snap(agent("a", "working", 1))
        # the stream was down; the first read after reconnecting shows blocked
        self.assertEqual(len(self.snap(agent("a", "blocked", 2), advance=30)), 1)
        self.assertEqual(self.snap(agent("a", "blocked", 2), advance=1), [])

    def test_alert_carries_the_pane_and_the_wall_time(self):
        self.snap(agent("a", "idle", 1, "w1:p1"))
        (alert,) = self.snap(agent("a", "blocked", 2, "w1:p1"), advance=1)
        self.assertEqual((alert.terminal_id, alert.pane_id, alert.state, alert.at), ("a", "w1:p1", "blocked", 1_790_000_000 + 1001))


class OutboxTests(unittest.TestCase):
    def item(self, created=0.0):
        return R.Item(R.Alert("t", "p", "blocked", 1), 1, created)

    def test_items_expire_by_age_and_are_counted(self):
        box = R.Outbox(expiry=100)
        box.put(self.item(0)); box.put(self.item(60))
        self.assertIsNotNone(box.head(50))
        self.assertEqual(len(box.items), 2)
        box.expire(101)
        self.assertEqual((len(box.items), box.expired), (1, 1))
        box.expire(161)
        self.assertEqual((len(box.items), box.expired), (0, 2))

    def test_backoff_doubles_to_five_minutes_and_holds_the_order(self):
        box = R.Outbox(expiry=10**6, jitter=lambda: 0.0)
        first, second = self.item(0), self.item(0)
        box.put(first); box.put(second)
        delays, now = [], 0.0
        for _ in range(12):
            box.failed(first, now)
            delays.append(first.not_before - now)
            self.assertIsNone(box.head(now + delays[-1] - 0.001))
            now = first.not_before
        self.assertEqual(delays[:5], [1, 2, 4, 8, 16])
        self.assertEqual(max(delays), 300)
        self.assertIs(box.head(now), first)
        box.delivered(first)
        self.assertIs(box.head(now), second)

    def test_the_queue_is_bounded(self):
        box = R.Outbox(expiry=100, max_len=3)
        for i in range(5):
            box.put(R.Item(R.Alert("t%d" % i, "p", "blocked", 1), i, 0))
        self.assertEqual([i.n for i in box.items], [2, 3, 4])
        self.assertEqual(box.dropped, 2)


class SequenceTests(unittest.TestCase):
    def test_it_only_rises_across_restarts_and_a_lost_file(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "s", "state.json")
            a = R.Sequence(path, wall=lambda: 1000)
            first = [a.next(), a.next(), a.next()]
            self.assertEqual(first, [1001, 1002, 1003])
            b = R.Sequence(path, wall=lambda: 500)  # the clock went back
            self.assertEqual(b.next(), 1004)
            self.assertEqual(stat.S_IMODE(os.stat(path).st_mode), 0o600)
            os.unlink(path)
            c = R.Sequence(path, wall=lambda: 2000)
            self.assertEqual(c.next(), 2001)
            put(path, "{broken")
            self.assertEqual(R.Sequence(path, wall=lambda: 3000).next(), 3001)
            put(path, '{"seq": "x"}')
            self.assertEqual(R.Sequence(path, wall=lambda: 4000).next(), 4001)


# --- a loopback notifier ----------------------------------------------------------------------------------------------------

class Stub:
    """An HTTP server that records every POST. [status] and [redirect] make it misbehave."""

    def __init__(self):
        self.posts, self.status, self.redirect = [], 200, None
        stub = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
                stub.posts.append({"path": self.path, "headers": dict(self.headers), "body": body})
                if stub.redirect:
                    self.send_response(302); self.send_header("Location", stub.redirect); self.end_headers(); return
                self.send_response(stub.status); self.end_headers(); self.wfile.write(b"{}")

            def log_message(self, *a):
                pass

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.url = "http://127.0.0.1:%d" % self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown(); self.server.server_close()

    def json(self):
        return [json.loads(p["body"]) for p in self.posts]


class NotifierTests(unittest.TestCase):
    def setUp(self):
        self.stub = Stub()
        self.addCleanup(self.stub.close)
        self.item = R.Item(R.Alert("term_x", "w1:p1", "blocked", 1790000000), 9, 0)

    def ntfy(self, **table):
        return R.Notifier(R.parse_config({**BASE, "ntfy": {"url": self.stub.url, "topic": "paddock-t", **table}}, 0o600))

    def test_it_posts_the_json_message(self):
        self.assertTrue(self.ntfy().post(self.item))
        (post,) = self.stub.posts
        self.assertEqual(post["path"], "/")
        self.assertEqual(post["headers"]["Content-Type"], "application/json")
        self.assertNotIn("Authorization", post["headers"])
        self.assertEqual(json.loads(post["body"])["topic"], "paddock-t")

    def test_a_token_is_sent_as_a_bearer_token(self):
        self.assertTrue(self.ntfy(token="tk_abc").post(self.item))
        self.assertEqual(self.stub.posts[0]["headers"]["Authorization"], "Bearer tk_abc")

    def test_a_refusal_or_an_unreachable_server_is_false(self):
        self.stub.status = 500
        self.assertFalse(self.ntfy().post(self.item))
        self.stub.status = 403
        self.assertFalse(self.ntfy().post(self.item))
        self.stub.close()
        self.assertFalse(self.ntfy().post(self.item))

    def test_a_redirect_is_not_followed(self):
        other = Stub()
        self.addCleanup(other.close)
        self.stub.redirect = other.url + "/stolen"
        self.assertFalse(self.ntfy(token="tk_abc").post(self.item))
        self.assertEqual(other.posts, [])

    def test_unifiedpush_posts_no_content_to_the_registered_endpoint(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "push-endpoint.json")
            conf = R.parse_config({**{k: v for k, v in BASE.items() if k != "ntfy"}, "delivery": "unifiedpush", "unifiedpush": {"endpoint_file": path}})
            notifier = R.Notifier(conf)
            self.assertFalse(notifier.post(self.item))  # not registered yet
            put(path, json.dumps({"endpoint": self.stub.url + "/up/abc"}))
            self.assertTrue(notifier.post(self.item))
            (post,) = self.stub.posts
            self.assertEqual(post["path"], "/up/abc")
            body = json.loads(post["body"])
            self.assertEqual(set(body), {"v", "h", "n"})
            self.assertEqual((body["v"], body["h"]), (1, "workstation"))
            self.assertRegex(body["n"], r"^[0-9a-f]{16}$")

    def test_a_hostile_endpoint_file_is_not_used(self):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "e.json")
            for content in ['{"endpoint": "http://evil.example/x"}', '{"endpoint": "https://u:p@x.example/"}', '{"endpoint": 5}', "[]", "{", '{"endpoint": "file:///etc/passwd"}']:
                put(path, content)
                self.assertIsNone(R.read_endpoint(path), content)
            put(path, '{"endpoint": "https://push.example/up/1"}')
            self.assertEqual(R.read_endpoint(path), "https://push.example/up/1")
            self.assertIsNone(R.read_endpoint(os.path.join(d, "missing.json")))


# --- a fake herdr ---------------------------------------------------------------------------------------------------------

class FakeStream:
    def __init__(self, kind, panes, writer):
        self.kind, self.panes, self.writer, self.open = kind, panes, writer, True


class FakeHerdr:
    def __init__(self, path):
        self.path = path
        self.agents = []
        self.streams = []
        self.requests = []
        self.refuse_status = 0
        self.server = None

    async def start(self):
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        self.server = await asyncio.start_unix_server(self.handle, self.path)

    async def stop(self):
        for s in self.streams:
            s.writer.close()
        self.server.close()
        try:
            await asyncio.wait_for(self.server.wait_closed(), 2)
        except asyncio.TimeoutError:
            pass

    def snapshot_body(self):
        return {"id": "x", "result": {"type": "session_snapshot", "snapshot": {"version": "0.9.1", "protocol": 22, "panes": [], "agents": [
            {"pane_id": a[1], "terminal_id": a[0], "agent_status": a[2], "state_change_seq": a[3], "workspace_id": "w1", "tab_id": "w1:t1", "title": "SECRET PROMPT TEXT", "cwd": "/secret/project"}
            for a in self.agents]}}}

    async def handle(self, reader, writer):
        line = await reader.readline()
        try:
            req = json.loads(line)
        except ValueError:
            writer.close(); return
        self.requests.append(req)
        if req["method"] == "session.snapshot":
            writer.write(json.dumps(self.snapshot_body()).encode() + b"\n")
            await writer.drain(); writer.close(); return
        subs = req["params"]["subscriptions"]
        kind = "status" if subs and subs[0]["type"] == "pane.agent_status_changed" else "lifecycle"
        if kind == "status" and self.refuse_status > 0:
            self.refuse_status -= 1
            writer.write(b'{"id":"","error":{"code":"invalid_request","message":"pane gone"}}\n')
            await writer.drain(); writer.close(); return
        stream = FakeStream(kind, {s.get("pane_id") for s in subs}, writer)
        self.streams.append(stream)
        writer.write(b'{"id":"x","result":{"type":"subscription_started"}}\n')
        await writer.drain()
        try:
            await reader.read()  # until the client closes
        finally:
            stream.open = False
            writer.close()

    def live(self, kind):
        return [s for s in self.streams if s.kind == kind and s.open and not s.writer.is_closing()]

    async def status(self, pane, status):
        for s in self.live("status"):
            if pane in s.panes:
                s.writer.write(json.dumps({"event": "pane.agent_status_changed", "data": {"agent": "claude", "agent_status": status, "pane_id": pane, "workspace_id": "w1"}}).encode() + b"\n")
                await s.writer.drain()

    async def lifecycle(self, name="pane_created"):
        for s in self.live("lifecycle"):
            s.writer.write(json.dumps({"event": name, "data": {"pane_id": "w1:p9", "workspace_id": "w1"}}).encode() + b"\n")
            await s.writer.drain()

    async def raw(self, kind, data):
        for s in self.live(kind):
            s.writer.write(data); await s.writer.drain()

    def cut(self, kind):
        for s in self.live(kind):
            s.writer.close()


class Recorder:
    """Stands in for the notifier: remembers items, answers from [results] (then True)."""

    def __init__(self):
        self.items, self.results = [], []

    def post(self, item):
        self.items.append(item)
        return self.results.pop(0) if self.results else True


async def until(test, predicate, timeout=5.0):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if predicate():
            return
        await asyncio.sleep(0.01)
    test.fail("timed out waiting for the condition")


async def settle(seconds=0.3):
    await asyncio.sleep(seconds)


class RelayWithFakeHerdrTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.dir = tempfile.mkdtemp(prefix="paddock-alert-test-")
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.sock = os.path.join(self.dir, "sessions", "paddock-test", "herdr.sock")
        self.herdr = FakeHerdr(self.sock)
        await self.herdr.start()
        self.notes = Recorder()
        self.tasks = []

    async def asyncTearDown(self):
        for t in self.tasks:
            t.cancel()
        await asyncio.gather(*self.tasks, return_exceptions=True)
        await self.herdr.stop()

    def relay(self, **over):
        conf = R.parse_config({**BASE, "socket": self.sock, "debounce_seconds": 0, "heartbeat_seconds": 1, "state_file": os.path.join(self.dir, "state.json"), **over})
        relay = R.Relay(conf, notifier=self.notes)
        self.tasks.append(asyncio.ensure_future(relay.run()))
        return relay

    def states(self):
        return [(i.alert.terminal_id, i.alert.state) for i in self.notes.items]

    async def up(self, relay):
        await until(self, lambda: relay.connected.is_set() and self.herdr.live("status"))
        await settle(0.1)

    async def test_one_alert_per_new_blocked_and_none_for_other_states(self):
        self.herdr.agents = [("ta", "w1:p1", "idle", 10)]
        relay = self.relay()
        await self.up(relay)
        self.assertEqual(self.notes.items, [])  # the baseline is silent
        for state in ("working", "idle", "unknown"):
            await self.herdr.status("w1:p1", state)
        await settle()
        self.assertEqual(self.notes.items, [])
        self.herdr.agents = [("ta", "w1:p1", "blocked", 11)]
        await self.herdr.status("w1:p1", "blocked")
        await until(self, lambda: len(self.notes.items) == 1)
        await settle(1.5)  # a heartbeat read confirms the same state: no second alert
        self.assertEqual(self.states(), [("ta", "blocked")])
        item = self.notes.items[0]
        self.assertEqual((item.alert.pane_id, item.n > 0), ("w1:p1", True))

    async def test_a_repeated_sample_of_the_baseline_state_is_ignored(self):
        self.herdr.agents = [("ta", "w1:p1", "blocked", 10)]
        relay = self.relay()
        await self.up(relay)
        await self.herdr.status("w1:p1", "blocked")
        await settle(1.2)
        self.assertEqual(self.notes.items, [])

    async def test_done_alerts_too(self):
        self.herdr.agents = [("ta", "w1:p1", "working", 10)]
        relay = self.relay()
        await self.up(relay)
        self.herdr.agents = [("ta", "w1:p1", "done", 11)]
        await self.herdr.status("w1:p1", "done")
        await until(self, lambda: len(self.notes.items) == 1)
        self.assertEqual(self.states(), [("ta", "done")])

    async def test_a_transition_whose_event_was_lost_is_found_by_the_heartbeat(self):
        self.herdr.agents = [("ta", "w1:p1", "working", 10)]
        relay = self.relay()
        await self.up(relay)
        self.herdr.agents = [("ta", "w1:p1", "blocked", 11)]  # no event is sent
        await until(self, lambda: len(self.notes.items) == 1, timeout=4)
        await settle(1.2)
        self.assertEqual(self.states(), [("ta", "blocked")])

    async def test_a_new_agent_pane_gets_a_status_subscription_and_its_events_alert(self):
        self.herdr.agents = [("ta", "w1:p1", "idle", 10)]
        relay = self.relay()
        await self.up(relay)
        first = list(self.herdr.live("status"))
        self.herdr.agents = [("ta", "w1:p1", "idle", 10), ("tb", "w1:p2", "idle", 11)]
        await self.herdr.lifecycle("pane_created")
        await until(self, lambda: any(s not in first and s.panes == {"w1:p1", "w1:p2"} for s in self.herdr.live("status")))
        await until(self, lambda: not any(s.open for s in first))  # the old stream is closed after the new one is acknowledged
        await self.herdr.status("w1:p2", "blocked")
        self.herdr.agents = [("ta", "w1:p1", "idle", 10), ("tb", "w1:p2", "blocked", 12)]
        await until(self, lambda: len(self.notes.items) == 1)
        self.assertEqual(self.states(), [("tb", "blocked")])

    async def test_a_status_subscription_refused_for_a_vanished_pane_is_retried_after_a_read(self):
        self.herdr.agents = [("ta", "w1:p1", "idle", 10), ("tb", "w1:p2", "idle", 11)]
        self.herdr.refuse_status = 1
        relay = self.relay()
        await until(self, lambda: relay.connected.is_set() and self.herdr.live("status"))
        self.assertEqual(relay.reconnects, 0)  # a refusal is retried on the same connection

    async def test_a_cut_stream_reconnects_and_a_change_during_the_gap_alerts_once(self):
        self.herdr.agents = [("ta", "w1:p1", "working", 10)]
        relay = self.relay()
        await self.up(relay)
        self.herdr.agents = [("ta", "w1:p1", "blocked", 11)]
        self.herdr.cut("lifecycle")
        await until(self, lambda: relay.reconnects == 1)
        await until(self, lambda: relay.connected.is_set() and len(self.notes.items) == 1)
        await settle(1.2)
        self.assertEqual(self.states(), [("ta", "blocked")])

    async def test_a_cut_status_stream_reconnects_too(self):
        self.herdr.agents = [("ta", "w1:p1", "idle", 10)]
        relay = self.relay()
        await self.up(relay)
        self.herdr.cut("status")
        await until(self, lambda: relay.reconnects == 1)
        await self.up(relay)
        self.herdr.agents = [("ta", "w1:p1", "blocked", 11)]
        await self.herdr.status("w1:p1", "blocked")
        await until(self, lambda: len(self.notes.items) == 1)

    async def test_events_lost_reconnects_if_a_later_herdr_sends_it(self):
        self.herdr.agents = [("ta", "w1:p1", "idle", 10)]
        relay = self.relay()
        await self.up(relay)
        await self.herdr.raw("lifecycle", b'{"id":"","error":{"code":"events_lost","message":"x"}}\n')
        await until(self, lambda: relay.reconnects == 1)

    async def test_a_half_delivered_line_is_dropped(self):
        self.herdr.agents = [("ta", "w1:p1", "idle", 10)]
        relay = self.relay()
        await self.up(relay)
        await self.herdr.raw("status", b'{"event":"pane.agent_status_changed","data":{"pane_id":"w1:p1","agent_status":"blocked"')
        self.herdr.cut("status")
        await until(self, lambda: relay.reconnects == 1)
        await settle(0.3)
        self.assertEqual(self.notes.items, [])

    async def test_garbage_on_a_stream_is_ignored(self):
        self.herdr.agents = [("ta", "w1:p1", "idle", 10)]
        relay = self.relay()
        await self.up(relay)
        await self.herdr.raw("status", b'not json\n[1,2]\n{"event":5}\n{"event":"pane.agent_status_changed","data":"x"}\n{"event":"pane.agent_status_changed","data":{"pane_id":7}}\n')
        await settle(0.3)
        self.assertEqual((self.notes.items, relay.reconnects), ([], 0))

    async def test_a_failed_post_is_retried_once_and_expires_when_old(self):
        self.herdr.agents = [("ta", "w1:p1", "idle", 10)]
        self.notes.results = [False]
        relay = self.relay()
        relay.outbox.jitter = lambda: 0.0
        await self.up(relay)
        self.herdr.agents = [("ta", "w1:p1", "blocked", 11)]
        await self.herdr.status("w1:p1", "blocked")
        await until(self, lambda: len(self.notes.items) == 2, timeout=4)  # the retry after 1 second
        self.assertEqual(relay.posted, 1)
        self.assertEqual(self.notes.items[0].n, self.notes.items[1].n)  # the same message, not a second one

    async def test_the_message_never_carries_text_from_the_snapshot(self):
        self.herdr.agents = [("ta", "w1:p1", "idle", 10)]
        relay = self.relay()
        await self.up(relay)
        self.herdr.agents = [("ta", "w1:p1", "blocked", 11)]
        await self.herdr.status("w1:p1", "blocked")
        await until(self, lambda: len(self.notes.items) == 1)
        conf = relay.cfg
        text = json.dumps(R.ntfy_payload(conf, self.notes.items[0].alert, self.notes.items[0].n))
        self.assertNotIn("SECRET", text)
        self.assertNotIn("/secret", text)

    async def test_it_waits_for_herdr_and_starts_when_it_appears(self):
        await self.herdr.stop()
        os.unlink(self.sock) if os.path.exists(self.sock) else None
        relay = self.relay()
        await settle(0.4)
        self.assertFalse(relay.connected.is_set())
        self.herdr = FakeHerdr(self.sock)
        self.herdr.agents = [("ta", "w1:p1", "idle", 10)]
        await self.herdr.start()
        await until(self, lambda: relay.connected.is_set(), timeout=8)

    async def test_it_never_asks_herdr_for_pane_text(self):
        self.herdr.agents = [("ta", "w1:p1", "idle", 10)]
        relay = self.relay()
        await self.up(relay)
        await self.herdr.status("w1:p1", "blocked")
        await settle(1.2)
        methods = {r["method"] for r in self.herdr.requests}
        self.assertEqual(methods, {"session.snapshot", "events.subscribe"})


class ProcessTests(unittest.TestCase):
    """The script as a service runs it: a child process with a config file."""

    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="paddock-alert-proc-")
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.stub = Stub()
        self.addCleanup(self.stub.close)

    def config(self, sock, extra=""):
        path = os.path.join(self.dir, "alert-relay.toml")
        with open(path, "w") as f:
            f.write('socket = "%s"\nprofile = "workstation"\nlabel = "ws"\ndelivery = "ntfy"\ndebounce_seconds = 0\nheartbeat_seconds = 1\nstate_file = "%s"\n%s\n[ntfy]\nurl = "%s"\ntopic = "paddock-t"\ntoken = "tk_supersecret"\n'
                    % (sock, os.path.join(self.dir, "state.json"), extra, self.stub.url))
        os.chmod(path, 0o600)
        return path

    def run_script(self, *args, **kw):
        return subprocess.run([sys.executable, SCRIPT, *args], capture_output=True, text=True, timeout=30, **kw)

    def test_usage_version_and_a_bad_config(self):
        self.assertEqual(self.run_script("--version").stdout.strip(), "paddock-alert-relay %d" % R.VERSION)
        self.assertEqual(self.run_script("--bogus").returncode, 2)
        bad = os.path.join(self.dir, "bad.toml")
        put(bad, 'profile = "x"\ntoken = "tk_supersecret"\n')
        out = self.run_script("--config", bad, "--check")
        self.assertEqual(out.returncode, 2)
        self.assertNotIn("tk_supersecret", out.stderr + out.stdout)
        self.assertEqual(self.run_script("--config", os.path.join(self.dir, "missing.toml"), "--check").returncode, 2)

    def test_a_readable_config_with_a_token_is_refused_and_the_token_never_printed(self):
        path = self.config(os.path.join(self.dir, "herdr.sock"))
        os.chmod(path, 0o644)
        out = self.run_script("--config", path, "--check")
        self.assertEqual(out.returncode, 2)
        self.assertNotIn("tk_supersecret", out.stderr + out.stdout)

    def test_check_reports_whether_herdr_answers(self):
        sock = os.path.join(self.dir, "sessions", "paddock-test", "herdr.sock")
        path = self.config(sock)
        self.assertEqual(self.run_script("--config", path, "--check").returncode, 3)

        async def scenario():
            herdr = FakeHerdr(sock)
            await herdr.start()
            try:
                p = await asyncio.create_subprocess_exec(sys.executable, SCRIPT, "--config", path, "--check", stderr=asyncio.subprocess.PIPE)
                _, err = await p.communicate()
                return p.returncode, err.decode()
            finally:
                await herdr.stop()

        code, err = asyncio.run(scenario())
        self.assertEqual(code, 0, err)
        self.assertIn("session paddock-test", err)

    def test_the_running_process_posts_once_logs_no_payload_and_stops_on_sigterm(self):
        sock = os.path.join(self.dir, "sessions", "paddock-test", "herdr.sock")
        path = self.config(sock)

        async def scenario():
            herdr = FakeHerdr(sock)
            herdr.agents = [("term_ta", "w1:p1", "idle", 10)]
            await herdr.start()
            p = await asyncio.create_subprocess_exec(sys.executable, SCRIPT, "--config", path, stderr=asyncio.subprocess.PIPE)
            try:
                await until(self, lambda: herdr.live("status"), timeout=10)
                await settle(0.2)
                herdr.agents = [("term_ta", "w1:p1", "blocked", 11)]
                await herdr.status("w1:p1", "blocked")
                await until(self, lambda: len(self.stub.posts) >= 1, timeout=10)
                await settle(1.5)
                p.send_signal(signal.SIGTERM)
                err = await asyncio.wait_for(p.stderr.read(), 10)
                await asyncio.wait_for(p.wait(), 10)
                return p.returncode, err.decode()
            finally:
                if p.returncode is None:
                    p.kill()
                await herdr.stop()

        code, err = asyncio.run(scenario())
        self.assertEqual(code, 0, err)
        self.assertEqual(len(self.stub.posts), 1)
        post = self.stub.posts[0]
        self.assertEqual(post["headers"].get("Authorization"), "Bearer tk_supersecret")  # plain http is allowed to loopback only
        body = json.loads(post["body"])
        self.assertEqual(body["title"], "Paddock: attention on ws")
        for secret in ("term_ta", "w1:p1", "SECRET", "tk_supersecret", "workstation"):
            self.assertNotIn(secret, err)


@unittest.skipUnless(LIVE, "pass --live to run against the disposable session")
class LiveTests(unittest.TestCase):
    """The relay against a real herdr server, in a session that can only be paddock-test or paddock-test-<suffix>."""

    SOCKET_RE = re.compile(r"^.*/sessions/(paddock-test(?:-[a-z0-9]+)?)/herdr\.sock$")

    def setUp(self):
        sock = os.environ.get("PADDOCK_TEST_SOCKET", "")
        m = self.SOCKET_RE.match(sock)
        if not m:
            self.skipTest("PADDOCK_TEST_SOCKET is not a disposable session socket")
        self.sock, self.session = sock, m.group(1)
        self.dir = tempfile.mkdtemp(prefix="paddock-alert-live-")
        self.addCleanup(shutil.rmtree, self.dir, True)
        self.stub = Stub()
        self.addCleanup(self.stub.close)
        self.base = self.herdr("pane", "list")["result"]["panes"][0]["pane_id"]

    def herdr(self, *args, check=True):
        out = subprocess.run(["herdr", "--session", self.session, *args], capture_output=True, text=True, timeout=30)
        if check and out.returncode != 0:
            self.fail("herdr %s: %s" % (args, out.stderr))
        return json.loads(out.stdout) if out.stdout.strip().startswith("{") else out.stdout

    def test_one_message_per_blocked_transition_and_never_pane_text(self):
        pane = self.herdr("pane", "split", self.base, "--direction", "right", "--no-focus")["result"]["pane"]
        pane_id, terminal_id = pane["pane_id"], pane["terminal_id"]
        self.addCleanup(lambda: self.herdr("pane", "close", pane_id, check=False))
        marker = "SECRET-MARKER-%d" % os.getpid()
        self.herdr("pane", "run", pane_id, "echo %s" % marker)  # text in the pane the relay must never read
        path = os.path.join(self.dir, "alert-relay.toml")
        with open(path, "w") as f:
            f.write('socket = "%s"\nprofile = "workstation"\nlabel = "ws"\ndelivery = "ntfy"\ndebounce_seconds = 0\nheartbeat_seconds = 1\nstate_file = "%s"\n[ntfy]\nurl = "%s"\ntopic = "paddock-live"\n' % (self.sock, os.path.join(self.dir, "state.json"), self.stub.url))
        relay = subprocess.Popen([sys.executable, SCRIPT, "--config", path], stderr=subprocess.PIPE, text=True)
        self.addCleanup(lambda: (relay.kill(), relay.wait()))
        seq = [0]

        def report(state, message=None):
            seq[0] += 1
            self.herdr("pane", "report-agent", pane_id, "--source", "paddock-alert-live", "--agent", "claude", "--state", state,
                       "--message", message or marker, "--seq", str(seq[0]))
            time.sleep(0.5)

        time.sleep(1.5)  # subscribed and baselined
        report("idle")
        time.sleep(2.5)  # the relay learns of the new agent pane (an event, or at the latest the next heartbeat) and subscribes to it
        self.assertEqual(self.stub.posts, [])
        report("blocked")
        report("blocked")          # the same state again: herdr emits nothing, the relay posts nothing
        report("working")
        report("unknown")
        report("idle")
        deadline = time.time() + 5
        while len(self.stub.posts) < 1 and time.time() < deadline:
            time.sleep(0.1)
        self.assertEqual(len(self.stub.posts), 1, "exactly one message for one blocked transition")
        for _ in range(2):
            report("blocked")
            report("working")
        time.sleep(1.5)
        bodies = self.stub.json()
        self.assertEqual(len(bodies), 3, [b["message"] for b in bodies])
        ns = []
        for body in bodies:
            self.assertNotIn(marker, json.dumps(body))
            q = dict(urllib.parse.parse_qsl(urllib.parse.urlsplit(body["click"]).query))
            self.assertEqual((q["h"], q["s"], q["t"], q["p"], q["st"]), ("workstation", self.session, terminal_id, pane_id, "blocked"))
            self.assertLess(abs(int(q["at"]) - time.time()), 60)
            ns.append(int(q["n"]))
        self.assertEqual(ns, sorted(set(ns)))
        relay.send_signal(signal.SIGTERM)
        err = relay.communicate(timeout=10)[1]
        self.assertEqual(relay.returncode, 0)
        for secret in (marker, terminal_id, pane_id):
            self.assertNotIn(secret, err)


if __name__ == "__main__":
    unittest.main(verbosity=1)
