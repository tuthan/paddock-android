#!/usr/bin/env python3
"""paddock-alert-relay: tells a phone that a herdr agent needs attention (Python 3.11 or newer, standard library only).

    paddock-alert-relay.py [--config FILE] [--check]

Runs as a user service beside herdr. It watches one herdr session and, when an agent newly becomes `blocked` or `done`,
posts one generic message to the notifier named in the configuration: an ntfy topic, or a UnifiedPush endpoint the phone
registered. The message names no agent, no prompt and no output. Its link names a terminal, the state the relay saw and
when; the phone resolves that against a fresh read, so a message is a hint and never authority to send anything.

It uses only the lifecycle and status event streams and `session.snapshot`: it never reads pane text. herdr 0.9.1 never
sends `events_lost` (a subscriber that lags silently loses events, one that stalls is cut off), so events are only
invalidations: any event, a closed stream and a heartbeat all lead to a snapshot read, and a read that finds a state the
events missed alerts the same way. A reconnect keeps what was seen, so a change that happened while a stream was down is
alerted on once, not lost and not repeated. The process logs counts and reason codes, never payloads, ids or the token.

Configuration, default ~/.config/paddock/alert-relay.toml (see host/alert-relay.example.toml):

    socket = "/home/you/.config/herdr/herdr.sock"   # required
    profile = "workstation"                         # required: the id the phone chose for this machine
    delivery = "ntfy"                               # "ntfy" or "unifiedpush"
    [ntfy]            url, topic, token (optional)
    [unifiedpush]     endpoint_file
"""
import asyncio
import json
import os
import random
import re
import secrets
import signal
import socket
import stat
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import OrderedDict, deque
from dataclasses import dataclass, field

try:
    import tomllib
except ImportError:  # Python older than 3.11
    sys.stderr.write("paddock-alert-relay: needs Python 3.11 or newer (tomllib)\n")
    sys.exit(2)

VERSION = 1
DEFAULT_CONFIG = "~/.config/paddock/alert-relay.toml"
DEFAULT_STATE = "~/.local/state/paddock/alert-relay.json"

PROFILE_RE = re.compile(r"[a-z0-9][a-z0-9-]{0,40}\Z")
TOPIC_RE = re.compile(r"[A-Za-z0-9_-]{1,64}\Z")
SESSION_RE = re.compile(r"(?:.*/)?sessions/([A-Za-z0-9][A-Za-z0-9._-]{0,63})/herdr\.sock\Z")
ALERTABLE = ("blocked", "done")
STATUSES = ("idle", "working", "blocked", "done", "unknown")

LINE_LIMIT = 1 << 20          # an event line
SNAPSHOT_LIMIT = 8 << 20      # a snapshot response has its own budget (the Paddock client uses the same)
MAX_AGENTS = 2000
MAX_QUEUE = 100
BACKOFF_CAP = 300             # delivery retries: seconds
RECONNECT_BASE = 1.0
RECONNECT_CAP = 10            # herdr reconnects: a host that just rebooted is back within a minute
ACK_TIMEOUT = 10.0
READ_TIMEOUT = 15.0
HTTP_TIMEOUT = 10.0
COALESCE = 0.25               # events inside this window cost one snapshot read
STATS_EVERY = 300.0

# Topology changes only: status changes arrive on the status streams, and the heartbeat covers a lost event.
LIFECYCLE_TYPES = ("pane.created", "pane.closed", "pane.agent_detected", "pane.exited", "pane.moved", "tab.closed", "workspace.closed")


def log(message):
    sys.stderr.write("paddock-alert-relay: %s\n" % message)
    sys.stderr.flush()


class ConfigError(Exception):
    pass


# --- configuration -------------------------------------------------------------------------------------------------------

@dataclass(frozen=True)
class Config:
    socket: str
    profile: str
    session: str
    label: str
    delivery: str
    alert_on: tuple
    debounce: float
    expiry: float
    heartbeat: float
    state_file: str
    ntfy_url: str = ""
    ntfy_topic: str = ""
    ntfy_token: str = ""
    endpoint_file: str = ""


TOP_KEYS = {"socket", "profile", "label", "delivery", "alert_on", "debounce_seconds", "expiry_seconds", "heartbeat_seconds",
            "state_file", "allow_insecure_http", "ntfy", "unifiedpush"}
NTFY_KEYS = {"url", "topic", "token"}
UP_KEYS = {"endpoint_file"}


def printable_label(text):
    cleaned = "".join(c for c in text if c.isprintable() and c not in "\r\n")
    return cleaned.strip()[:40]


def _number(table, key, default, low, high):
    value = table.get(key, default)
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not low <= value <= high:
        raise ConfigError("%s must be a number from %s to %s" % (key, low, high))
    return float(value)


def _only(table, allowed, where):
    extra = sorted(set(table) - allowed)
    if extra:
        raise ConfigError("unknown key %s%s" % (where, extra[0]))


def _is_loopback(host):
    return host in ("localhost", "127.0.0.1", "::1", "[::1]")


def parse_config(data, mode=0o600):
    """Validates a parsed TOML document. [mode] is the file's permission bits: a token in a readable file is refused."""
    if not isinstance(data, dict):
        raise ConfigError("not a table")
    _only(data, TOP_KEYS, "")
    sock = data.get("socket")
    if not isinstance(sock, str) or "\0" in sock or not os.path.isabs(os.path.expanduser(sock)) \
            or os.path.basename(os.path.expanduser(sock)) != "herdr.sock":
        raise ConfigError("socket must be an absolute path ending in herdr.sock")
    sock = os.path.expanduser(sock)
    profile = data.get("profile")
    if not isinstance(profile, str) or not PROFILE_RE.match(profile):
        raise ConfigError("profile must be lowercase letters, digits and dashes, at most 41 characters (the id the phone chose)")
    label = data.get("label")
    if label is None:
        label = printable_label(socket.gethostname()) or "host"
    elif not isinstance(label, str) or not printable_label(label):
        raise ConfigError("label must be non-empty text")
    else:
        label = printable_label(label)
    delivery = data.get("delivery")
    if delivery not in ("ntfy", "unifiedpush"):
        raise ConfigError('delivery must be "ntfy" or "unifiedpush"')
    alert_on = data.get("alert_on", list(ALERTABLE))
    if not isinstance(alert_on, list) or not alert_on or any(a not in ALERTABLE for a in alert_on) or len(set(alert_on)) != len(alert_on):
        raise ConfigError('alert_on must list "blocked" and/or "done"')
    debounce = _number(data, "debounce_seconds", 15, 0, 3600)
    expiry = _number(data, "expiry_seconds", 600, 10, 86400)
    heartbeat = _number(data, "heartbeat_seconds", 30, 1, 600)
    state_file = data.get("state_file", DEFAULT_STATE)
    if not isinstance(state_file, str) or not os.path.isabs(os.path.expanduser(state_file)):
        raise ConfigError("state_file must be an absolute path")
    allow_http = data.get("allow_insecure_http", False)
    if not isinstance(allow_http, bool):
        raise ConfigError("allow_insecure_http must be true or false")
    ntfy_url = ntfy_topic = ntfy_token = endpoint_file = ""
    if delivery == "ntfy":
        table = data.get("ntfy")
        if not isinstance(table, dict):
            raise ConfigError("[ntfy] is required for delivery = \"ntfy\"")
        _only(table, NTFY_KEYS, "ntfy.")
        ntfy_url, ntfy_topic, ntfy_token = table.get("url"), table.get("topic"), table.get("token", "")
        if not isinstance(ntfy_url, str):
            raise ConfigError("ntfy.url is required")
        parts = urllib.parse.urlsplit(ntfy_url)
        if parts.scheme not in ("http", "https") or not parts.hostname or parts.username or parts.password or parts.query or parts.fragment:
            raise ConfigError("ntfy.url must be http(s)://host[:port][/path] with no credentials, query or fragment")
        if parts.scheme == "http" and not _is_loopback(parts.hostname) and (not allow_http or ntfy_token):
            raise ConfigError("ntfy.url over http needs allow_insecure_http = true, and never carries a token")
        ntfy_url = ntfy_url.rstrip("/")
        if not isinstance(ntfy_topic, str) or not TOPIC_RE.match(ntfy_topic):
            raise ConfigError("ntfy.topic must be letters, digits, dashes and underscores, at most 64 characters")
        if not isinstance(ntfy_token, str) or len(ntfy_token) > 512 or any(c in ntfy_token for c in "\r\n\0 "):
            raise ConfigError("ntfy.token is not a token")
        if ntfy_token and mode & 0o077:
            raise ConfigError("the file holds a token and is readable by others (chmod 600)")
    else:
        table = data.get("unifiedpush")
        if not isinstance(table, dict):
            raise ConfigError("[unifiedpush] is required for delivery = \"unifiedpush\"")
        _only(table, UP_KEYS, "unifiedpush.")
        endpoint_file = table.get("endpoint_file")
        if not isinstance(endpoint_file, str) or not os.path.isabs(os.path.expanduser(endpoint_file)):
            raise ConfigError("unifiedpush.endpoint_file must be an absolute path")
        endpoint_file = os.path.expanduser(endpoint_file)
    match = SESSION_RE.match(sock)
    return Config(
        socket=sock, profile=profile, session=match.group(1) if match else "default", label=label, delivery=delivery,
        alert_on=tuple(alert_on), debounce=debounce, expiry=expiry, heartbeat=heartbeat,
        state_file=os.path.expanduser(state_file), ntfy_url=ntfy_url, ntfy_topic=ntfy_topic, ntfy_token=ntfy_token,
        endpoint_file=endpoint_file,
    )


def load_config(path):
    path = os.path.expanduser(path)
    try:
        st = os.stat(path)
        with open(path, "rb") as f:
            raw = f.read(64 * 1024 + 1)
    except OSError:
        raise ConfigError("cannot read %s" % path)
    if len(raw) > 64 * 1024:
        raise ConfigError("the file is too large")
    try:
        data = tomllib.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, tomllib.TOMLDecodeError):
        raise ConfigError("not valid TOML")
    return parse_config(data, stat.S_IMODE(st.st_mode))


# --- what an alert looks like ---------------------------------------------------------------------------------------------

@dataclass(frozen=True)
class Agent:
    terminal_id: str
    pane_id: str
    status: str
    seq: object = None


@dataclass(frozen=True)
class Alert:
    terminal_id: str
    pane_id: str
    state: str
    at: int


def build_link(cfg, alert, n):
    """The hint's link. Names a target and what the relay saw; never anything phone-local, never any text from a pane."""
    query = urllib.parse.urlencode(
        [("h", cfg.profile), ("s", cfg.session), ("t", alert.terminal_id), ("p", alert.pane_id), ("st", alert.state),
         ("at", str(alert.at)), ("n", str(n))],
        quote_via=urllib.parse.quote,
    )
    return "paddock://open?" + query


def ntfy_payload(cfg, alert, n):
    link = build_link(cfg, alert, n)
    blocked = alert.state == "blocked"
    return {
        "topic": cfg.ntfy_topic,
        "title": "Paddock: attention on %s" % cfg.label,
        "message": "An agent needs you." if blocked else "An agent finished.",
        "priority": 4 if blocked else 3,
        "tags": ["paddock"],
        "click": link,
        "actions": [{"action": "view", "label": "Review", "url": link, "clear": True}],
    }


def unifiedpush_payload(cfg, nonce):
    return {"v": 1, "h": cfg.profile, "n": nonce}


# --- the pure decision: which observations are new alerts -------------------------------------------------------------------

@dataclass
class Seen:
    status: str
    seq: object
    pane_id: str


class Tracker:
    """Turns snapshots and status events into alerts. No I/O, no clock of its own: [now] is monotonic seconds, [wall] unix seconds.

    A terminal's status or its `state_change_seq` differing from what was last seen is a transition. The first snapshot of the
    process is a silent baseline, so starting the relay never invents history; later reads (after a reconnect too) compare
    against what was seen. An event carries a status and no sequence, so the sequence it leaves unknown is filled in, without a
    second alert, by the next read. A (terminal, state) pair alerts at most once per debounce window.
    """

    def __init__(self, alert_on, debounce):
        self.alert_on = set(alert_on)
        self.debounce = debounce
        self.state = {}
        self.pane_to_terminal = {}
        self.baselined = False
        self.last_post = {}
        self.debounced = 0

    def _consider(self, terminal_id, pane_id, status, now, wall):
        if status not in self.alert_on:
            return None
        key = (terminal_id, status)
        last = self.last_post.get(key)
        if last is not None and now - last < self.debounce:
            self.debounced += 1
            return None
        self.last_post[key] = now
        return Alert(terminal_id, pane_id, status, int(wall))

    def snapshot(self, agents, now, wall):
        alerts = []
        present = set()
        for a in agents:
            present.add(a.terminal_id)
            prev = self.state.get(a.terminal_id)
            if prev is None:
                changed = self.baselined
            else:
                changed = prev.status != a.status or (prev.seq is not None and a.seq is not None and prev.seq != a.seq)
            if changed:
                alert = self._consider(a.terminal_id, a.pane_id, a.status, now, wall)
                if alert:
                    alerts.append(alert)
            self.state[a.terminal_id] = Seen(a.status, a.seq, a.pane_id)
        for gone in set(self.state) - present:
            del self.state[gone]
        for key in [k for k in self.last_post if k[0] not in present or now - self.last_post[k] > max(self.debounce, 1) * 4]:
            del self.last_post[key]
        self.pane_to_terminal = {a.pane_id: a.terminal_id for a in agents}
        self.baselined = True
        return alerts

    def status_event(self, pane_id, status, now, wall):
        """Returns (alerts, needs_read). A pane the last read did not show cannot be matched to a terminal: that needs a read."""
        terminal_id = self.pane_to_terminal.get(pane_id)
        if terminal_id is None:
            return [], True
        prev = self.state.get(terminal_id)
        if prev is not None and prev.status == status:
            return [], False  # a repeated sample is not a new transition
        self.state[terminal_id] = Seen(status, None, pane_id)
        alert = self._consider(terminal_id, pane_id, status, now, wall)
        return ([alert] if alert else []), False


# --- the outbox: a bounded queue with expiry and backoff ---------------------------------------------------------------------

@dataclass
class Item:
    alert: Alert
    n: int
    created: float
    attempts: int = 0
    not_before: float = 0.0


class Outbox:
    def __init__(self, expiry, max_len=MAX_QUEUE, jitter=random.random):
        self.expiry, self.max_len, self.jitter = expiry, max_len, jitter
        self.items = deque()
        self.expired = 0
        self.dropped = 0

    def put(self, item):
        self.items.append(item)
        while len(self.items) > self.max_len:
            self.items.popleft()
            self.dropped += 1

    def expire(self, now):
        while self.items and now - self.items[0].created > self.expiry:
            self.items.popleft()
            self.expired += 1

    def head(self, now):
        """The oldest item, if its backoff has passed. Order is kept: a failing endpoint holds everything behind it."""
        self.expire(now)
        return self.items[0] if self.items and self.items[0].not_before <= now else None

    def wait(self, now):
        """Seconds until the head is due, or None when the queue is empty."""
        self.expire(now)
        return max(0.0, self.items[0].not_before - now) if self.items else None

    def delivered(self, item):
        if self.items and self.items[0] is item:
            self.items.popleft()

    def failed(self, item, now):
        item.attempts += 1
        item.not_before = now + min(BACKOFF_CAP, 2 ** (item.attempts - 1)) * (1 + 0.25 * self.jitter())


# --- persisted sequence ---------------------------------------------------------------------------------------------------

class Sequence:
    """The relay sequence `n`: strictly increasing across restarts, so a phone that drops repeats by it never drops a new alert.

    Without a state file it starts from the wall clock, which is above any earlier run's count unless that run alerted more than
    once a second for as long as it lived.
    """

    def __init__(self, path, wall=time.time):
        self.path = path
        last = 0
        try:
            with open(path, "rb") as f:
                value = json.loads(f.read(4096)).get("seq")
            if isinstance(value, int) and 0 <= value < 1 << 53:
                last = value
        except (OSError, ValueError, AttributeError):
            pass
        self.value = max(last, int(wall()))

    def next(self):
        self.value += 1
        try:
            os.makedirs(os.path.dirname(self.path), mode=0o700, exist_ok=True)
            tmp = self.path + ".tmp"
            fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(fd, "w") as f:
                f.write(json.dumps({"seq": self.value}))
            os.replace(tmp, self.path)
        except OSError:
            pass  # the sequence still rises in this process; a lost file only restarts it from the clock
        return self.value


# --- delivery ---------------------------------------------------------------------------------------------------------------

class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None  # a redirect would carry the token to another host


_opener = urllib.request.build_opener(_NoRedirect)


def http_post_json(url, body, headers, timeout=HTTP_TIMEOUT):
    request = urllib.request.Request(url, data=json.dumps(body).encode("utf-8"), method="POST",
                                     headers={"Content-Type": "application/json", **headers})
    try:
        with _opener.open(request, timeout=timeout) as response:
            response.read(1024)
            return 200 <= response.status < 300
    except urllib.error.HTTPError as e:
        e.close()
        return False
    except (urllib.error.URLError, OSError, ValueError):
        return False


def read_endpoint(path):
    """The UnifiedPush endpoint the phone wrote, or None when the file is missing or not an https (or loopback http) URL."""
    try:
        with open(path, "rb") as f:
            data = json.loads(f.read(8192))
        url = data["endpoint"]
        if not isinstance(url, str) or len(url) > 2048:
            return None
        parts = urllib.parse.urlsplit(url)
    except (OSError, ValueError, KeyError, TypeError):
        return None
    if parts.scheme == "https" or (parts.scheme == "http" and _is_loopback(parts.hostname or "")):
        if parts.hostname and not parts.username and not parts.password:
            return url
    return None


class Notifier:
    def __init__(self, cfg):
        self.cfg = cfg

    def post(self, item):
        """True when the notifier accepted it. Blocking: runs in a thread."""
        cfg = self.cfg
        if cfg.delivery == "ntfy":
            headers = {"Authorization": "Bearer " + cfg.ntfy_token} if cfg.ntfy_token else {}
            return http_post_json(cfg.ntfy_url, ntfy_payload(cfg, item.alert, item.n), headers)
        endpoint = read_endpoint(cfg.endpoint_file)
        if endpoint is None:
            return False  # the phone has not registered (or revoked): the item waits, then expires
        return http_post_json(endpoint, unifiedpush_payload(cfg, secrets.token_hex(8)), {})


# --- talking to herdr -------------------------------------------------------------------------------------------------------

class Lost(Exception):
    """A stream or a read failed. The reason is a short code, never text from herdr."""


def check_socket(path):
    real = os.path.realpath(path)
    if os.path.basename(real) != "herdr.sock":
        raise Lost("path_outside")
    try:
        st = os.stat(real)
    except OSError:
        raise Lost("socket_missing")
    if not stat.S_ISSOCK(st.st_mode) or st.st_uid != os.getuid():
        raise Lost("not_a_socket_of_this_user")
    return real


async def _read_line(reader):
    try:
        line = await reader.readline()
    except ValueError:
        raise Lost("line_too_long")
    return line if line.endswith(b"\n") else None  # an unterminated tail is a half-delivered line: dropped


def _request(method, params):
    return (json.dumps({"id": "paddock-alert-%d" % random.getrandbits(32), "method": method, "params": params}) + "\n").encode()


def _agents(result):
    out = []
    try:
        entries = result["snapshot"]["agents"]
    except (KeyError, TypeError):
        raise Lost("snapshot_shape")
    if not isinstance(entries, list):
        raise Lost("snapshot_shape")
    for e in entries[:MAX_AGENTS]:
        if not isinstance(e, dict):
            continue
        tid, pane, status, seq = e.get("terminal_id"), e.get("pane_id"), e.get("agent_status"), e.get("state_change_seq")
        if not isinstance(tid, str) or not isinstance(pane, str) or not tid or not pane:
            continue
        out.append(Agent(tid, pane, status if status in STATUSES else "unknown", seq if isinstance(seq, int) and not isinstance(seq, bool) else None))
    return out


class Herdr:
    def __init__(self, path):
        self.path = path

    async def snapshot(self):
        path = check_socket(self.path)
        try:
            reader, writer = await asyncio.wait_for(asyncio.open_unix_connection(path, limit=SNAPSHOT_LIMIT), ACK_TIMEOUT)
        except (OSError, asyncio.TimeoutError):
            raise Lost("connect_failed")
        try:
            writer.write(_request("session.snapshot", {}))
            await writer.drain()
            line = await asyncio.wait_for(_read_line(reader), READ_TIMEOUT)
        except (OSError, asyncio.TimeoutError):
            raise Lost("read_failed")
        finally:
            writer.close()
        if line is None:
            raise Lost("no_answer")
        try:
            message = json.loads(line)
        except ValueError:
            raise Lost("bad_json")
        if not isinstance(message, dict) or "result" not in message:
            raise Lost("refused")
        return _agents(message["result"])

    async def subscribe(self, subscriptions):
        """Opens a stream and waits for its acknowledgement. Returns (reader, writer); the caller owns both."""
        path = check_socket(self.path)
        try:
            reader, writer = await asyncio.wait_for(asyncio.open_unix_connection(path, limit=LINE_LIMIT), ACK_TIMEOUT)
        except (OSError, asyncio.TimeoutError):
            raise Lost("connect_failed")
        opened = False
        try:
            writer.write(_request("events.subscribe", {"subscriptions": subscriptions}))
            await writer.drain()
            line = await asyncio.wait_for(_read_line(reader), ACK_TIMEOUT)
            message = json.loads(line) if line else None
            if not isinstance(message, dict) or (message.get("result") or {}).get("type") != "subscription_started":
                raise Lost("refused")
            opened = True
        except (OSError, asyncio.TimeoutError):
            raise Lost("subscribe_failed")
        except ValueError:
            raise Lost("bad_json")
        finally:
            if not opened:
                writer.close()
        return reader, writer


class Stream:
    """One open subscription: its reader task feeds [inbox] with (stream, name, data) and a final (stream, None, reason)."""

    def __init__(self, kind, reader, writer, inbox):
        self.kind, self.reader, self.writer, self.inbox = kind, reader, writer, inbox
        self.closed = False
        self.task = asyncio.ensure_future(self._pump())

    async def _pump(self):
        reason = "ended"
        try:
            while True:
                line = await _read_line(self.reader)
                if line is None:
                    break
                try:
                    message = json.loads(line)
                except ValueError:
                    continue
                if not isinstance(message, dict):
                    continue
                if isinstance(message.get("error"), dict) and message["error"].get("code") == "events_lost":
                    reason = "events_lost"
                    break
                name = message.get("event")
                if isinstance(name, str):
                    self.inbox.put_nowait((self, name, message.get("data")))
        except Lost as e:
            reason = str(e)
        except (OSError, asyncio.CancelledError):
            reason = "read_failed"
        finally:
            self.inbox.put_nowait((self, None, reason))

    def close(self):
        self.closed = True
        self.task.cancel()
        try:
            self.writer.close()
        except OSError:
            pass


# --- the relay --------------------------------------------------------------------------------------------------------------

class Relay:
    def __init__(self, cfg, notifier=None, clock=time.monotonic, wall=time.time, sequence=None):
        self.cfg = cfg
        self.herdr = Herdr(cfg.socket)
        self.notifier = notifier or Notifier(cfg)
        self.clock, self.wall = clock, wall
        self.tracker = Tracker(cfg.alert_on, cfg.debounce)
        self.outbox = Outbox(cfg.expiry)
        self.sequence = sequence or Sequence(cfg.state_file, wall)
        self.wakeup = asyncio.Event()
        self.posted = 0
        self.failed = 0
        self.reconnects = 0
        self.connected = asyncio.Event()

    # delivery -------------------------------------------------------------------------------------------------------------

    def enqueue(self, alerts):
        for alert in alerts:
            self.outbox.put(Item(alert, self.sequence.next(), self.clock()))
        if alerts:
            self.wakeup.set()

    async def deliver(self):
        while True:
            item = self.outbox.head(self.clock())
            if item is None:
                self.wakeup.clear()
                wait = self.outbox.wait(self.clock())
                try:
                    await asyncio.wait_for(self.wakeup.wait(), timeout=wait if wait is not None else 60.0)
                except asyncio.TimeoutError:
                    pass
                continue
            ok = await asyncio.to_thread(self.notifier.post, item)
            if ok:
                self.outbox.delivered(item)
                self.posted += 1
            else:
                self.failed += 1
                self.outbox.failed(item, self.clock())

    # the watch --------------------------------------------------------------------------------------------------------------

    async def watch(self):
        """One connection's life. Returns or raises when it ends; the caller backs off and calls it again."""
        inbox = asyncio.Queue()
        lifecycle = status = None
        covered = frozenset()
        try:
            reader, writer = await self.herdr.subscribe([{"type": t} for t in LIFECYCLE_TYPES])
            lifecycle = Stream("lifecycle", reader, writer, inbox)
            dirty = True
            last_read = 0.0
            next_beat = self.clock() + self.cfg.heartbeat
            self.connected.set()
            while True:
                if dirty:
                    wait = COALESCE - (self.clock() - last_read)
                    if wait > 0:
                        await asyncio.sleep(wait)
                    dirty = False
                    last_read = self.clock()
                    self.enqueue(self.tracker.snapshot(await self.herdr.snapshot(), self.clock(), self.wall()))
                    wanted = frozenset(self.tracker.pane_to_terminal)
                    if wanted != covered:
                        status, covered = await self.cover(wanted, status, inbox)
                        dirty = True  # a pane added since the read had no subscription until now: read again
                        continue
                timeout = max(0.0, next_beat - self.clock())
                try:
                    stream, name, data = await asyncio.wait_for(inbox.get(), timeout=timeout)
                except asyncio.TimeoutError:
                    dirty = True
                    next_beat = self.clock() + self.cfg.heartbeat
                    continue
                if name is None:
                    if stream is lifecycle or stream is status:
                        raise Lost(data)
                    continue  # a replaced stream ending
                if stream is status and name in ("pane.agent_status_changed", "pane_agent_status_changed") and isinstance(data, dict):
                    pane, state = data.get("pane_id"), data.get("agent_status")
                    if isinstance(pane, str):
                        alerts, needs_read = self.tracker.status_event(pane, state if state in STATUSES else "unknown", self.clock(), self.wall())
                        self.enqueue(alerts)
                        dirty = dirty or needs_read
                elif stream is lifecycle:
                    dirty = True
                elif name == "events_lost":
                    raise Lost("events_lost")
        finally:
            self.connected.clear()
            for s in (lifecycle, status):
                if s is not None:
                    s.close()

    async def cover(self, wanted, old, inbox):
        """Moves the status stream to cover [wanted]: the new one is acknowledged before the old one is closed."""
        if not wanted:
            if old is not None:
                old.close()
            return None, frozenset()
        subscriptions = [{"type": "pane.agent_status_changed", "pane_id": p} for p in sorted(wanted)]
        try:
            reader, writer = await self.herdr.subscribe(subscriptions)
        except Lost as e:
            if str(e) != "refused":
                raise
            # A pane closed between the read and the request makes herdr refuse the whole request: read again, once.
            self.enqueue(self.tracker.snapshot(await self.herdr.snapshot(), self.clock(), self.wall()))
            wanted = frozenset(self.tracker.pane_to_terminal)
            if not wanted:
                if old is not None:
                    old.close()
                return None, frozenset()
            reader, writer = await self.herdr.subscribe([{"type": "pane.agent_status_changed", "pane_id": p} for p in sorted(wanted)])
        new = Stream("status", reader, writer, inbox)
        if old is not None:
            old.close()
        return new, wanted

    async def run(self):
        delivery = asyncio.ensure_future(self.deliver())
        stats = asyncio.ensure_future(self.report())
        fails = 0
        try:
            while True:
                started = self.clock()
                try:
                    await self.watch()
                    reason = "ended"
                except Lost as e:
                    reason = str(e)
                except (OSError, asyncio.TimeoutError):
                    reason = "io_error"
                if self.clock() - started > 30:
                    fails = 0
                fails += 1
                self.reconnects += 1
                delay = min(RECONNECT_CAP, RECONNECT_BASE * 2 ** (fails - 1))
                log("lost: %s; reconnecting in %gs" % (reason, delay))
                await asyncio.sleep(delay)
        finally:
            delivery.cancel()
            stats.cancel()

    async def report(self):
        while True:
            await asyncio.sleep(STATS_EVERY)
            log("stats posted=%d failed=%d queued=%d debounced=%d expired=%d dropped=%d reconnects=%d" % (
                self.posted, self.failed, len(self.outbox.items), self.tracker.debounced, self.outbox.expired, self.outbox.dropped, self.reconnects))


# --- entry point ------------------------------------------------------------------------------------------------------------

async def serve(cfg):
    relay = Relay(cfg)
    task = asyncio.ensure_future(relay.run())
    for sig in (signal.SIGTERM, signal.SIGINT):
        asyncio.get_running_loop().add_signal_handler(sig, task.cancel)
    await task


async def ping(cfg):
    await Herdr(cfg.socket).snapshot()


def main(argv):
    config_path, check = DEFAULT_CONFIG, False
    args = list(argv[1:])
    while args:
        a = args.pop(0)
        if a == "--config" and args:
            config_path = args.pop(0)
        elif a == "--check":
            check = True
        elif a == "--version":
            print("paddock-alert-relay %d" % VERSION)
            return 0
        else:
            log("usage: paddock-alert-relay.py [--config FILE] [--check] [--version]")
            return 2
    try:
        cfg = load_config(config_path)
    except ConfigError as e:
        log("config: %s" % e)
        return 2
    if check:
        try:
            asyncio.run(ping(cfg))
        except Lost as e:
            log("config ok; herdr not reachable (%s)" % e)
            return 3
        log("config ok; herdr reachable (session %s, delivery %s)" % (cfg.session, cfg.delivery))
        return 0
    log("started (version %d, session %s, delivery %s)" % (VERSION, cfg.session, cfg.delivery))
    try:
        asyncio.run(serve(cfg))
    except asyncio.CancelledError:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
