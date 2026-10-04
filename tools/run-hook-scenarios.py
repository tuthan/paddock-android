#!/usr/bin/env python3
"""Phase 08's scenario suite: the real hook and the real decision writer against a real Claude Code session, three runs each.

    tools/run-hook-scenarios.py [--runs 3] [--only NAME[,NAME]] [--out DIR] [--model haiku]

Every run splits a pane of the disposable herdr session `paddock-test` (the session name is fixed in this script; nothing else
is ever touched), starts `claude --model haiku` in /tmp/paddock-test-ws with a project-level settings file that registers the
hook, asks it to run one harmless `touch` through its Bash tool, and plays the phone with `paddock-decide.py`. What the host
could see is read back from the request files, the marker file the command creates, the pane's screen and herdr's status.
The phone's own half (the sheet, the journal) is covered by the Kotlin tests and the device flow.

Nothing in the user's own ~/.claude or ~/.config is written: the hook's configuration is in a scratch XDG_CONFIG_HOME named in
the settings command, and the session's own claude config lives under the user's account as usual (the workspace was already
trusted by the first probe). Prompt and screen text from the disposable Claude are never copied into the log beyond booleans.
"""
import argparse
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import time

import harness

ROOT = harness.APP
HOOK = os.path.join(ROOT, "host", "paddock-claude-permission-hook.py")
DECIDE = os.path.join(ROOT, "host", "paddock-decide.py")
SESSION = "paddock-test"                 # the only herdr session this script ever talks to
WORKSPACE = "/tmp/paddock-test-ws"
DIALOG = "Do you want to proceed?"


def herdr(*args, check=True):
    out = subprocess.run(["herdr", "--session", SESSION, *args], capture_output=True, text=True, timeout=60)
    if check and out.returncode != 0:
        raise RuntimeError("herdr %s: %s" % (" ".join(args), out.stderr.strip()))
    return json.loads(out.stdout) if out.stdout.strip().startswith("{") else out.stdout


class Harness:
    def __init__(self, out, model):
        self.out, self.model = out, model
        self.cfg = os.path.join(out, "cfg")
        self.markers = os.path.join(WORKSPACE, "hook-markers")
        os.makedirs(os.path.join(self.cfg, "paddock"), exist_ok=True)
        os.makedirs(self.markers, exist_ok=True)
        self.base = herdr("pane", "list")["result"]["panes"][0]["pane_id"]
        self.log = []
        self.last_miss = None
        self.reasks = 0
        self.reask_notes = []

    # -- configuration of the hook under test
    def configure(self, window, claude_timeout=None):
        path = os.path.join(self.cfg, "paddock", "hook.toml")
        with open(path, "w") as f:
            f.write("window_seconds = %d\n" % window)
        os.chmod(path, 0o600)
        timeout = claude_timeout if claude_timeout is not None else window + 5
        settings = {"hooks": {"PermissionRequest": [{"hooks": [{"type": "command", "timeout": timeout,
                    "command": "XDG_CONFIG_HOME=%s python3 %s" % (self.cfg, HOOK)}]}]}}
        os.makedirs(os.path.join(WORKSPACE, ".claude"), exist_ok=True)
        with open(os.path.join(WORKSPACE, ".claude", "settings.json"), "w") as f:
            json.dump(settings, f)

    def unconfigure(self):
        try:
            os.unlink(os.path.join(WORKSPACE, ".claude", "settings.json"))
        except FileNotFoundError:
            pass

    # -- one Claude Code session in a pane of our own
    def open(self):
        pane = herdr("pane", "split", self.base, "--direction", "down", "--no-focus")["result"]["pane"]["pane_id"]
        self.pane = pane
        herdr("pane", "run", pane, "cd %s && claude --model %s" % (WORKSPACE, self.model))
        end = time.time() + 40
        while time.time() < end:
            if "? for shortcuts" in self.screen() and self.status() == "idle":
                return
            time.sleep(0.5)
        raise RuntimeError("claude did not become ready in %s" % pane)

    def close(self):
        pane, self.pane = getattr(self, "pane", None), None
        if pane:
            herdr("pane", "send-text", pane, "/exit", check=False)
            herdr("pane", "send-keys", pane, "Enter", check=False)
            time.sleep(1.5)
            herdr("pane", "close", pane, check=False)

    def screen(self, lines=60):
        return herdr("pane", "read", self.pane, "--lines", str(lines), check=False)

    def status(self):
        try:
            return herdr("agent", "get", self.pane)["result"]["agent"]["agent_status"]
        except Exception:
            return None

    def wait(self, condition, timeout, step=0.1):
        end = time.time() + timeout
        while time.time() < end:
            value = condition()
            if value:
                return value
            time.sleep(step)
        return None

    def ask(self, *markers):
        """Asks for one `touch` per marker, each through its own Bash call, and returns the marker paths."""
        paths = [os.path.join(self.markers, m) for m in markers]
        if len(paths) == 1:
            text = "Use the Bash tool to run exactly: touch %s . Say nothing else." % paths[0]
        else:
            text = "Make %d separate Bash tool calls, in this order, each running exactly one of these commands: %s . Say nothing else." % (
                len(paths), " ; ".join("touch " + p for p in paths))
        for p in paths:
            try:
                os.unlink(p)
            except FileNotFoundError:
                pass
        herdr("pane", "send-text", self.pane, text)
        herdr("pane", "send-keys", self.pane, "Enter")
        self._last_ask = (paths, text)
        return paths

    def desktop_yes(self):
        herdr("pane", "send-keys", self.pane, "Enter")

    # -- the phone's side, played with the real script
    def listing(self):
        out = subprocess.run([sys.executable, DECIDE, "list", SESSION, self.pane], capture_output=True, text=True, timeout=30)
        return json.loads(out.stdout)

    def candidate(self, timeout=25, after_ids=()):
        def look():
            c = self.listing().get("candidate")
            return c if c and c["complete"] and c["request_id"] not in after_ids else None
        # The small model sometimes ends its turn without calling the tool at all (seen in about one run in twenty: agent idle, no dialog, no
        # request directory, no hook process). That is the model, not the hook, so a fresh scenario that sees exactly that is asked again, at
        # most twice, and every re-ask is counted and printed beside the run. A scenario waiting for its *second* request is never re-asked.
        end = time.time() + timeout
        found = None
        while True:
            found = self.wait(look, max(0.0, min(12.0, end - time.time())))
            if found or time.time() >= end:
                break
            if (not after_ids and self.reasks < 2 and getattr(self, "_last_ask", None) and self.status() == "idle" and not self.dialog()
                    and not (os.path.isdir(self.request_dir()) and os.listdir(self.request_dir()))):
                # Was the prompt typed and never submitted (a lost Enter), or submitted and ignored? A boolean, never the screen text.
                self.reask_notes.append({"after_s": round(time.time() - (end - timeout), 1), "our_text_still_in_the_input_box": self._last_ask[1][:40] in self.screen(80)})
                for p in self._last_ask[0]:
                    try:
                        os.unlink(p)
                    except FileNotFoundError:
                        pass
                herdr("pane", "send-text", self.pane, self._last_ask[1])
                herdr("pane", "send-keys", self.pane, "Enter")
                self.reasks += 1
                end = time.time() + timeout
        # When nothing was published, record what the host could see (booleans, a status word, file names and a count; never screen text),
        # so a run that never reached its dialog can be told from a hook that did not run.
        self.last_miss = None if found else {"dialog_showing": self.dialog(), "agent_status": self.status(), "request_files": sorted(os.listdir(self.request_dir())) if os.path.isdir(self.request_dir()) else None,
                                             "hook_processes": len(self.hook_pids())}
        return found

    def decide(self, candidate, behavior="allow"):
        body = candidate["body"]
        out = subprocess.run([sys.executable, DECIDE, "decide", SESSION, self.pane, body["claude_session_id"], candidate["request_id"]],
                             input=json.dumps({"behavior": behavior, "phone": "scenario-suite", "at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}),
                             capture_output=True, text=True, timeout=30)
        return out.returncode

    def state_of(self, request_id):
        for r in self.listing()["requests"]:
            if r["request_id"] == request_id:
                return r["state"]
        return None

    def wait_state(self, request_id, states, timeout):
        return self.wait(lambda: self.state_of(request_id) in states and self.state_of(request_id), timeout)

    def wait_file(self, path, timeout):
        return self.wait(lambda: os.path.exists(path), timeout)

    def dialog(self):
        return DIALOG in self.screen(30)

    def request_dir(self):
        return os.path.join(os.environ.get("XDG_RUNTIME_DIR") or "/run/user/%d" % os.getuid(), "paddock", SESSION, self.pane)

    def hook_pids(self):
        out = subprocess.run(["ps", "-eo", "pid,args"], capture_output=True, text=True).stdout
        return [int(l.split()[0]) for l in out.splitlines() if "paddock-claude-permission-hook.py" in l and "python3" in l and "XDG_CONFIG_HOME" not in l and str(os.getpid()) != l.split()[0]]


class Check:
    def __init__(self, name):
        self.name, self.items, self.facts = name, [], {}

    def expect(self, what, ok):
        self.items.append({"expect": what, "ok": bool(ok)})
        return ok

    @property
    def ok(self):
        return all(i["ok"] for i in self.items)


# ---------------------------------------------------------------------------------------------------------------------------
# Scenarios. Each takes (harness, run number) and returns a Check.


def s_yes_binds_to_the_displayed_request(h, n):
    """AC-08.1: the phone's Yes allows exactly the invocation whose request id it read, and the desktop dialog is gone."""
    c = Check("yes_binds_to_the_displayed_request")
    h.configure(30)
    h.open()
    try:
        (marker,) = h.ask("yes-%d.txt" % n)
        t0 = time.time()
        cand = h.candidate()
        if not c.expect("a request is published", cand):
            return c
        c.facts["pending_after_s"] = round(time.time() - t0, 2)
        c.expect("the displayed input is the command asked for", marker in json.dumps(cand["body"]["tool_input"]))
        c.expect("herdr reports the pane blocked by itself while the hook waits", h.wait(lambda: h.status() == "blocked", 15))
        c.expect("the desktop dialog is showing while the hook waits", h.dialog())
        t1 = time.time()
        rc = h.decide(cand, "allow")
        c.expect("the writer accepts", rc == 0)
        c.expect("Claude Code ran the command without a desktop key", h.wait_file(marker, 15))
        c.facts["decide_to_effect_s"] = round(time.time() - t1, 2)
        c.expect("the request is consumed", h.wait_state(cand["request_id"], ("consumed",), 5))
        c.expect("no dialog is left", h.wait(lambda: not h.dialog(), 5))
        c.expect("the agent returns to idle", h.wait(lambda: h.status() == "idle", 15))
    finally:
        h.close()
    return c


def s_no_denies_and_runs_nothing(h, n):
    c = Check("no_denies_and_runs_nothing")
    h.configure(30)
    h.open()
    try:
        (marker,) = h.ask("no-%d.txt" % n)
        cand = h.candidate()
        if not c.expect("a request is published", cand):
            return c
        c.expect("the writer accepts", h.decide(cand, "deny") == 0)
        c.expect("the request is consumed", h.wait_state(cand["request_id"], ("consumed",), 8))
        c.expect("Claude Code reports the denial by the hook", h.wait(lambda: "Denied by PermissionRequest hook" in h.screen(40), 15))
        c.expect("the command did not run", not os.path.exists(marker))
        c.expect("the agent returns to idle", h.wait(lambda: h.status() == "idle", 15))
    finally:
        h.close()
    return c


def s_desktop_answers_while_the_phone_taps(h, n):
    c = Check("desktop_answers_while_the_phone_taps")
    h.configure(40)
    h.open()
    try:
        (marker,) = h.ask("desktop-%d.txt" % n)
        cand = h.candidate()
        if not c.expect("a request is published", cand):
            return c
        c.expect("the pane is blocked", h.wait(lambda: h.status() == "blocked", 15))
        time.sleep(1.0)
        h.desktop_yes()
        t0 = time.time()
        c.expect("the desktop answer ran the command", h.wait_file(marker, 15))
        expired = h.wait_state(cand["request_id"], ("expired",), 10)
        c.expect("the hook noticed and expired the request", expired)
        c.facts["desktop_answer_to_expired_s"] = round(time.time() - t0, 2)
        c.expect("the phone's tap is refused (exit 3: gone)", h.decide(cand, "deny") == 3)
        c.expect("the refused tap changed nothing: the command ran once, the request stays expired", os.path.exists(marker) and h.state_of(cand["request_id"]) == "expired")
        c.expect("no candidate is offered", h.listing()["candidate"] is None)
    finally:
        h.close()
    return c


def s_phone_taps_inside_the_desktop_gap(h, n):
    """Not an assertion about who wins: records what happens when the tap lands within a moment of the desktop's own answer."""
    c = Check("phone_taps_inside_the_desktop_gap")
    h.configure(40)
    h.open()
    try:
        (marker,) = h.ask("gap-%d.txt" % n)
        cand = h.candidate()
        if not c.expect("a request is published", cand):
            return c
        h.wait(lambda: h.status() == "blocked", 15)
        time.sleep(1.0)
        h.desktop_yes()
        time.sleep(0.15)
        rc = h.decide(cand, "deny")
        c.facts["tap_150ms_after_desktop_exit"] = rc
        time.sleep(4)
        c.facts["command_ran"] = os.path.exists(marker)
        c.facts["final_state"] = h.state_of(cand["request_id"])
        c.expect("the command ran exactly because the desktop said yes, whatever the phone's late deny did", os.path.exists(marker))
        c.expect("the request ended settled, never pending", h.state_of(cand["request_id"]) in ("expired", "consumed"))
    finally:
        h.close()
    return c


def s_claim_just_before_the_lapse(h, n):
    c = Check("claim_just_before_the_lapse")
    h.configure(10)
    h.open()
    try:
        (marker,) = h.ask("lapse-in-%d.txt" % n)
        cand = h.candidate()
        if not c.expect("a request is published", cand):
            return c
        wait_s = cand["body"]["expires_at"] / 1000.0 - time.time() - 0.4
        time.sleep(max(0, wait_s))
        rc = h.decide(cand, "allow")
        c.facts["claim_ms_before_lapse"] = 400
        c.expect("the writer claimed in time", rc == 0)
        c.expect("the hook honoured it inside its grace and Claude ran the command with no desktop key", h.wait_file(marker, 15))
        c.expect("the request is consumed", h.wait_state(cand["request_id"], ("consumed",), 8))
    finally:
        h.close()
    return c


def s_claim_just_after_the_lapse(h, n):
    c = Check("claim_just_after_the_lapse")
    h.configure(6)
    h.open()
    try:
        (marker,) = h.ask("lapse-out-%d.txt" % n)
        cand = h.candidate()
        if not c.expect("a request is published", cand):
            return c
        time.sleep(max(0, cand["body"]["expires_at"] / 1000.0 - time.time()) + 0.5)
        c.expect("the request expired", h.state_of(cand["request_id"]) == "expired")
        rc = h.decide(cand, "allow")
        c.facts["late_exit"] = rc
        c.expect("the writer is refused (3 gone)", rc == 3)
        time.sleep(1.0)
        c.expect("the desktop dialog is still there and the command has not run", h.dialog() and not os.path.exists(marker))
        h.desktop_yes()
        c.expect("the desktop can still answer", h.wait_file(marker, 15))
    finally:
        h.close()
    return c


def s_a_new_request_replaces_the_old(h, n):
    """Claude Code asks for its second tool only after the first was answered, so two requests are never pending for long: the first is
    answered at the desktop, and the phone must be offered the second (never the first), bind its Yes to the second, and be refused
    for the first."""
    c = Check("a_new_request_replaces_the_old")
    h.configure(40)
    h.open()
    try:
        first, second = h.ask("replace-a-%d.txt" % n, "replace-b-%d.txt" % n)
        old = h.candidate()
        if not c.expect("the first request is published", old):
            return c
        c.expect("the first request shows the first command", first in json.dumps(old["body"]["tool_input"]))
        c.expect("the dialog is up for the first request", h.wait(h.dialog, 5))
        h.desktop_yes()
        c.expect("the first command ran from the desktop's answer", h.wait_file(first, 15))
        new = h.candidate(timeout=25, after_ids=(old["request_id"],))
        if not c.expect("a second request is published", new):
            return c
        listing = h.listing()
        pendings = [r for r in listing["requests"] if r["state"] == "pending"]
        c.facts["pending_when_second_seen"] = len(pendings)
        c.expect("the candidate is the newest pending request", new["request_id"] == max(pendings, key=lambda r: r["created_at"])["request_id"])
        c.expect("the second request shows the second command and not the first", second in json.dumps(new["body"]["tool_input"]) and first not in json.dumps(new["body"]["tool_input"]))
        # The first hook may not notice the desktop's answer at all when the next prompt follows at once (herdr's status goes blocked,
        # working, blocked: never two reads away from blocked). The second request therefore expires every older pending request of the
        # same Claude session when it is published, so the first is already expired when the second can be seen.
        c.expect("the first request was expired by the second's publication, not left pending beside it", h.state_of(old["request_id"]) == "expired")
        c.expect("no decision exists for the first request", not os.path.exists(os.path.join(h.request_dir(), old["request_id"] + ".decision.json")))
        c.expect("the first hook left by itself and printed nothing", h.wait(lambda: not h.hook_pids() or len(h.hook_pids()) == 1, 5))
        c.expect("the second command has not run before the phone answers", not os.path.exists(second))
        c.expect("the writer refuses an answer to the first request (it is expired)", h.decide(old, "allow") != 0)
        c.expect("the writer accepts the second", h.decide(new, "allow") == 0)
        c.expect("exactly the command it displayed ran, through the phone", h.wait_file(second, 15))
        c.expect("the first request stayed expired, never consumed", h.state_of(old["request_id"]) == "expired")
    finally:
        h.close()
    return c


def s_disconnect_after_submission(h, n):
    c = Check("disconnect_after_submission")
    h.configure(30)
    h.open()
    try:
        (marker,) = h.ask("cut-%d.txt" % n)
        cand = h.candidate()
        if not c.expect("a request is published", cand):
            return c
        subprocess.run([sys.executable, DECIDE, "decide", SESSION, h.pane, cand["body"]["claude_session_id"], cand["request_id"]],
                       input=json.dumps({"behavior": "allow", "phone": "cut", "at": "x"}), capture_output=True, text=True, timeout=30)
        # the phone never saw the answer; on reconnect it reads the file names
        state = h.wait_state(cand["request_id"], ("consumed", "expired"), 10)
        c.expect("a later read says Consumed", state == "consumed")
        c.expect("and the command ran", h.wait_file(marker, 10))
    finally:
        h.close()
    return c


def s_duplicate_tap(h, n):
    c = Check("duplicate_tap")
    h.configure(30)
    h.open()
    try:
        (marker,) = h.ask("dup-%d.txt" % n)
        cand = h.candidate()
        if not c.expect("a request is published", cand):
            return c
        first = h.decide(cand, "allow")
        second = h.decide(cand, "deny")
        c.expect("the first tap wins", first == 0)
        c.expect("the second is refused (exit 3)", second == 3)
        c.expect("the first outcome stands: the command ran", h.wait_file(marker, 15))
        c.expect("and the request is consumed once", h.wait_state(cand["request_id"], ("consumed",), 8))
        decisions = [f for f in os.listdir(os.path.join(os.environ.get("XDG_RUNTIME_DIR") or "/run/user/%d" % os.getuid(), "paddock", SESSION, h.pane)) if f.endswith(".decision.json")]
        c.expect("one decision file for the request", len(decisions) == 1)
    finally:
        h.close()
    return c


def s_stale_after_settlement(h, n):
    c = Check("stale_after_settlement")
    h.configure(30)
    h.open()
    try:
        (marker,) = h.ask("stale-%d.txt" % n)
        cand = h.candidate()
        if not c.expect("a request is published", cand):
            return c
        h.decide(cand, "allow")
        h.wait_file(marker, 15)
        h.wait_state(cand["request_id"], ("consumed",), 8)
        c.expect("no pending request remains", h.listing()["candidate"] is None)
        c.expect("answering the settled request is refused: nothing is pending (exit 3)", h.decide(cand, "allow") == 3)
    finally:
        h.close()
    return c


def s_hook_killed(h, n):
    c = Check("hook_killed")
    h.configure(40)
    h.open()
    try:
        (marker,) = h.ask("killed-%d.txt" % n)
        cand = h.candidate()
        if not c.expect("a request is published", cand):
            return c
        before = [r for r in h.listing()["requests"] if r["request_id"] == cand["request_id"]]
        c.expect("while the hook runs the listing says so", before and before[0].get("hook_alive") is True)
        pids = h.wait(lambda: h.hook_pids(), 5)
        c.expect("the hook process is found", pids)
        for pid in pids or []:
            os.kill(pid, signal.SIGKILL)
        time.sleep(1.0)
        c.expect("Claude Code is still at its normal dialog", h.dialog())
        c.expect("the pane stays blocked, not hung", h.status() == "blocked")
        after = [r for r in h.listing()["requests"] if r["request_id"] == cand["request_id"]]
        c.facts["request_state_after_kill"] = after[0]["state"] if after else None
        c.facts["hook_alive_after_kill"] = after[0].get("hook_alive") if after else None
        c.expect("the listing now says the hook is gone", after and after[0].get("hook_alive") is False)
        c.expect("the writer refuses a Yes for a request whose hook has ended (expired)", h.decide(cand, "allow") == 4)
        c.expect("nothing ran from the refused answer", not os.path.exists(marker))
        h.desktop_yes()
        c.expect("the desktop's answer runs the command", h.wait_file(marker, 15))
        c.expect("the agent returns to idle", h.wait(lambda: h.status() == "idle", 15))
    finally:
        h.close()
    return c


def s_hook_timeout(h, n):
    c = Check("hook_timeout")
    h.configure(30, claude_timeout=3)        # Claude Code gives up on the hook after 3 s although the window is 30
    h.open()
    try:
        (marker,) = h.ask("timeout-%d.txt" % n)
        cand = h.candidate()
        if not c.expect("a request is published", cand):
            return c
        time.sleep(6)
        c.expect("Claude Code is still at its normal dialog", h.dialog())
        c.facts["request_state_after_timeout"] = h.state_of(cand["request_id"])
        c.facts["hook_still_running"] = bool(h.hook_pids())
        h.desktop_yes()
        c.expect("the desktop's answer runs the command", h.wait_file(marker, 15))
        c.expect("the agent returns to idle", h.wait(lambda: h.status() == "idle", 15))
    finally:
        h.close()
    return c


def s_window_off_changes_nothing(h, n):
    c = Check("window_off_changes_nothing")
    h.configure(0)
    h.open()
    try:
        (marker,) = h.ask("off-%d.txt" % n)
        c.expect("the pane reaches blocked", h.wait(lambda: h.status() == "blocked", 20))
        c.expect("the dialog shows", h.wait(h.dialog, 5))
        c.expect("no request file was made", h.listing()["requests"] == [] and h.listing()["candidate"] is None)
        h.desktop_yes()
        c.expect("the desktop answer runs the command", h.wait_file(marker, 15))
    finally:
        h.close()
    return c


SCENARIOS = [s_yes_binds_to_the_displayed_request, s_no_denies_and_runs_nothing, s_desktop_answers_while_the_phone_taps,
             s_phone_taps_inside_the_desktop_gap, s_claim_just_before_the_lapse, s_claim_just_after_the_lapse,
             s_a_new_request_replaces_the_old, s_disconnect_after_submission, s_duplicate_tap, s_stale_after_settlement,
             s_hook_killed, s_hook_timeout, s_window_off_changes_nothing]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--only", default="")
    ap.add_argument("--out", default=os.path.join(harness.OUT_BASE, "hook-scenarios"))
    ap.add_argument("--model", default="haiku")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    only = [s for s in args.only.split(",") if s]
    h = Harness(args.out, args.model)
    results, failed = [], 0
    try:
        for scenario in SCENARIOS:
            name = scenario.__name__[2:]
            if only and not any(o in name for o in only):
                continue
            for n in range(1, args.runs + 1):
                started = time.time()
                h.last_miss = None
                h.reasks = 0
                h.reask_notes = []
                h._last_ask = None
                try:
                    check = scenario(h, n)
                    error = None
                except Exception as e:      # a harness fault is reported as a failed run, never hidden
                    check, error = Check(name), "%s: %s" % (type(e).__name__, e)
                    check.expect("the run completed", False)
                    h.close()
                if h.reasks:
                    check.facts["model_reasked"] = h.reasks
                    check.facts["reask_notes"] = h.reask_notes
                row = {"scenario": name, "run": n, "ok": check.ok and error is None, "seconds": round(time.time() - started, 1), "checks": check.items, "facts": check.facts}
                if error:
                    row["error"] = error
                if not row["ok"] and h.last_miss:
                    row["miss"] = h.last_miss
                    print("       nothing was published; the host saw: %s" % json.dumps(h.last_miss), flush=True)
                results.append(row)
                failed += 0 if row["ok"] else 1
                print("%-4s %-45s run %d  %5.1fs  %s" % ("PASS" if row["ok"] else "FAIL", name, n, row["seconds"], json.dumps(check.facts) if check.facts else ""), flush=True)
                for item in check.items:
                    if not item["ok"]:
                        print("       not met: %s" % item["expect"], flush=True)
                if error:
                    print("       %s" % error, flush=True)
    finally:
        h.close()
        h.unconfigure()
        shutil.rmtree(h.markers, ignore_errors=True)
    with open(os.path.join(args.out, "scenarios.json"), "w") as f:
        json.dump({"date": time.strftime("%Y-%m-%d"), "claude": subprocess.run(["claude", "--version"], capture_output=True, text=True).stdout.strip(),
                   "herdr": subprocess.run(["herdr", "--version"], capture_output=True, text=True).stdout.strip(), "results": results}, f, indent=1)
    print("\n%d runs, %d failed" % (len(results), failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
