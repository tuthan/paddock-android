#!/usr/bin/env python3
"""Release smoke on one emulator or phone (AC-10.6) and the runtime half of the privacy checklist (AC-10.4), against the SIGNED RELEASE APK.

  tools/run-release-smoke.py <adb-serial> <signed-apk> [out-dir]
  tools/run-release-smoke.py <adb-serial> --sockets-only [--minutes N] [out-dir]
  tools/run-release-smoke.py --self-test

The release APK is not debuggable, so nothing here uses run-as or instrumentation: the app is driven through adb and uiautomator dumps,
as paddock-harness/check-permission-flow.py does, and judged from what a user sees, from the host, and from the phone's own logs and sockets.

Host: an isolated HOME (/tmp/pdk-r10, short because unix socket paths are) with a herdr of its own (session paddock-test-rel10, created
and stopped here; the developer's sessions are never addressed), `claude` stand-ins first on PATH (tools/fake-agent.py: a real Claude
Code is never started), and the throwaway loopback sshd (tools/test-sshd.sh, port 2233).

Smoke (one pass, each line PASS or FAIL):
  install, launch, Add machine, create the phone key, authorize it on the throwaway sshd, connect, compare the fingerprint the phone shows
  with the sshd's, trust, install the relay (hash on screen equals host/SOURCE.json's pin), the herd with the blocked agent, its Output with the
  seeded output, the Terminal tab observing, an alert link (paddock://open) resolved against a fresh read, background and return.
Privacy (runtime, on the same session):
  the app is not debuggable; every socket the app's uid opened during the run goes to the sshd and to nothing else (sampled from
  /proc/net/{tcp,tcp6,udp,udp6} every 0.4 s); the window carries FLAG_SECURE on Output and not on Home; and the logcat of the whole run
  holds none of the markers seeded in the agent's output and title, no private-key text, and no password.

--sockets-only (default 6 minutes; Phase 16, AC-16.3, the `play` build's network): installs nothing, drives nothing, starts no sshd or herdr. A person
does the purchase sequence on the build Google Play installed (cold start, two idle minutes on Home with no machine, Settings, Restore purchase, a
licence-tester tip and its thank-you, a second Restore, two idle minutes) while this samples the app uid's sockets with the same sampler as the smoke,
then prints and writes <out-dir>/play-sockets.txt: every remote address beyond loopback with a reverse lookup ("no PTR" when there is none). It marks
the list NOT A MEASUREMENT when the installer is not com.android.vending, when no /proc/net row could be read, when the app was never seen running and
when the run was cut short. The sampler sees a socket only while it is open (every 0.4 s plus an adb round trip per file), so a connection that opens and closes between
two rounds is missing from the list: an empty list is what was seen, not a proof of absence. Exit 0 with a complete run, 1 when the list is marked NOT A
MEASUREMENT, 2 when the device or the package is not there.
--self-test feeds the parser, the report and the argument handling canned text; no device, no network.
"""
import ipaddress, os, re, signal, socket, subprocess, sys, tempfile, threading, time, json, xml.etree.ElementTree as ET

# ---------------------------------------------------------------- the pure part: arguments, /proc/net rows, the socket report (all of it under --self-test)
USAGE = "usage: run-release-smoke.py <adb-serial> <signed-apk> [out-dir]  |  <adb-serial> --sockets-only [--minutes N] [out-dir]  |  --self-test"
def parse_args(argv):
    """(serial, apk, out_dir, sockets_only, minutes) from the arguments after the program name. The smoke keeps its positional form; --sockets-only takes no APK."""
    a = list(argv); sockets_only = "--sockets-only" in a; minutes = 6.0
    if sockets_only: a.remove("--sockets-only")
    if "--minutes" in a:
        i = a.index("--minutes")
        if not sockets_only: raise SystemExit("--minutes belongs to --sockets-only\n" + USAGE)
        try: minutes = float(a[i + 1])
        except (IndexError, ValueError): raise SystemExit("--minutes needs a number\n" + USAGE)
        if not minutes > 0: raise SystemExit("--minutes must be above zero\n" + USAGE)
        del a[i:i + 2]
    if any(x.startswith("--") for x in a): raise SystemExit("unknown option " + next(x for x in a if x.startswith("--")) + "\n" + USAGE)
    if sockets_only:
        if not 1 <= len(a) <= 2: raise SystemExit(USAGE)
        return a[0], None, (a[1] if len(a) > 1 else None), True, minutes
    if not 2 <= len(a) <= 3: raise SystemExit(USAGE)
    return a[0], a[1], (a[2] if len(a) > 2 else None), False, minutes

def hexaddr(h, v6):
    ip, port = h.split(":")
    if not v6: b = bytes.fromhex(ip)[::-1]; return ".".join(map(str, b)) + ":" + str(int(port, 16))
    raw = bytes.fromhex(ip); words = [raw[i:i + 4][::-1] for i in range(0, 16, 4)]; b = b"".join(words)
    return str(ipaddress.IPv6Address(b)) + ":" + str(int(port, 16))

def parse_proc_net(text, proto, uid):
    """The remote endpoints ("ip:port") of the connected sockets that `uid` owns in one /proc/net/<proto> dump, and the number of rows read in all (any
    uid; zero means the file was not readable, which is not the same as an app with no sockets). Listening and unconnected rows (remote port 0) are skipped."""
    found, rows = [], 0
    for line in text.splitlines()[1:]:
        f = line.split()
        if len(f) < 8: continue
        rows += 1
        if f[7] != str(uid): continue
        remote = hexaddr(f[2], proto.endswith("6"))
        if remote.endswith(":0") or remote.startswith("0.0.0.0:") or remote.startswith(":::0"): continue
        found.append(remote)
    return found, rows

def remote_ip(remote):
    """The address of "ip:port" as an ipaddress object, an IPv4-mapped IPv6 address unwrapped to the IPv4 one."""
    ip = ipaddress.ip_address(remote.rsplit(":", 1)[0])
    return ip.ipv4_mapped if ip.version == 6 and ip.ipv4_mapped else ip

def render_socket_report(seen, meta, lookup):
    """(text, problems): the play-sockets.txt text for {(proto, "ip:port"): rounds seen} and the run's facts; `lookup(ip)` is the reverse lookup and raises
    when there is no PTR. `problems` lists why the run measured nothing; each is also in the text, in capitals, first."""
    problems = []
    if meta["installer"] != "com.android.vending": problems.append(f"the installer is {meta['installer']}, not com.android.vending, so the list does not describe the Play build")
    if meta["rows"] == 0: problems.append("no /proc/net row of any uid could be read, so the list says nothing")
    if meta["running"] == 0: problems.append("the app was never seen running, so the list says nothing")
    if meta["early"]: problems.append(f"the run stopped after {meta['seconds']:.0f} s of the {meta['asked']:.0f} s asked, so the sequence is incomplete")
    remote = {k: n for k, n in seen.items() if not remote_ip(k[1]).is_loopback}
    names = {}
    for ip in sorted({str(remote_ip(r)) for _, r in remote}):
        try: names[ip] = lookup(ip) or "no PTR"
        except Exception: names[ip] = "no PTR"
    out = [f"NOT A MEASUREMENT: {x}" for x in problems]
    out += [f"Sockets of {meta['pkg']} (uid {meta['uid']}) on {meta['serial']}; installer {meta['installer']}",
            f"sampled from /proc/net/{{tcp,tcp6,udp,udp6}} from {meta['start']}: {meta['seconds']:.0f} s of {meta['asked']:.0f} s asked, {meta['samples']} rounds, {meta['rows']} rows read, app seen running in {meta['running']} of {meta['checks']} checks",
            "A socket is seen only while it is open: a connection that opens and closes between two rounds is not here. An empty list is what was seen, not a proof of absence.",
            f"remote addresses beyond loopback: {len(remote)}"]
    for (proto, r), n in sorted(remote.items()): out.append(f"  {proto:<4} {r}  seen in {n} rounds  PTR {names[str(remote_ip(r))]}")
    out.append("result: none beyond loopback (the SSH host, if a machine was added, would be listed above)" if not remote else
               "result: every address above goes into the privacy checklist, the policy and the Data safety answers by name (docs/billing.md)")
    return "\n".join(out) + "\n", problems

def self_test():
    bad = []
    def t(name, ok, detail=""):
        print(("PASS " if ok else "FAIL ") + name + (f" - {detail}" if detail and not ok else "")); ok or bad.append(name)
    def refused(argv):
        try: parse_args(argv); return False
        except SystemExit as e: return bool(e.code)
    t("a v4 address is read little-endian", hexaddr("0202000A:08D9", False) == "10.0.2.2:2265", hexaddr("0202000A:08D9", False))
    t("a v6 loopback is ::1", hexaddr("00000000000000000000000001000000:1F90", True) == "::1:8080", hexaddr("00000000000000000000000001000000:1F90", True))
    t("a v4-mapped v6 address is the v4 address", str(remote_ip(hexaddr("0000000000000000FFFF00000202000A:08D9", True))) == "10.0.2.2")
    HDR = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n"
    row = lambda sl, loc, rem, uid: f"   {sl}: {loc} {rem} 01 00000000:00000000 00:00000000 00000000 {uid:>5}        0 {1000 + sl} 1 0000000000000000 20 4 30 10 -1\n"
    tcp = HDR + row(0, "0100007F:0035", "00000000:0000", 10179) + row(1, "0F02000A:C350", "0202000A:08D9", 10179) + row(2, "0F02000A:C351", "08080808:01BB", 10180) + row(3, "0F02000A:C352", "8EFA0F2B:01BB", 10179) + "short line\n"
    found, rows = parse_proc_net(tcp, "tcp", 10179)
    t("tcp: the app's connected sockets only, the listener and another uid's left out", found == ["10.0.2.2:2265", "43.15.250.142:443"], str(found))
    t("tcp: every well-formed row is counted, whatever its uid", rows == 4, str(rows))
    found, rows = parse_proc_net(HDR + row(0, "0000000000000000FFFF00000F02000A:C350", "0000000000000000FFFF00000202000A:08D9", 10179) + row(1, "00000000000000000000000001000000:C351", "00000000000000000000000001000000:1F90", 10179), "tcp6", 10179)
    t("tcp6: a mapped address and a loopback are both returned (loopback is filtered later)", len(found) == 2 and str(remote_ip(found[0])) == "10.0.2.2" and remote_ip(found[1]).is_loopback, str(found))
    found, rows = parse_proc_net(HDR + row(0, "0F02000A:0044", "00000000:0000", 10179) + row(1, "0F02000A:C353", "01010101:0035", 10179), "udp", 10179)
    t("udp: an unconnected socket is skipped, a connected one is returned", found == ["1.1.1.1:53"], str(found))
    t("a dump with a header and no rows reads zero rows", parse_proc_net(HDR, "tcp", 10179) == ([], 0))
    t("an empty dump (adb returned nothing) reads zero rows", parse_proc_net("", "tcp6", 10179) == ([], 0))
    meta = dict(pkg="io.github.tuthan.paddock", uid=10179, serial="TESTSERIAL", installer="com.android.vending", start="2026-10-12 21:00:00", seconds=360, asked=360, samples=480, rows=5230, running=36, checks=36, early=False)
    def lookup(ip):
        if ip == "142.250.4.95": return "sin04s.1e100.net"
        raise socket.herror(1, "Unknown host")
    seen = {("tcp", "127.0.0.1:5037"): 9, ("tcp6", "::1:8080"): 2, ("tcp6", "::ffff:142.250.4.95:443"): 31, ("udp", "10.0.2.3:53"): 4}
    text, problems = render_socket_report(seen, meta, lookup)
    t("report: loopback is not listed, the other two are, counted", "remote addresses beyond loopback: 2" in text and "127.0.0.1" not in text and "::1:" not in text, text)
    t("report: a name is shown and a failed lookup says no PTR", "PTR sin04s.1e100.net" in text and "10.0.2.3:53  seen in 4 rounds  PTR no PTR" in text, text)
    t("report: a complete Play run has no problem and says where the addresses go", problems == [] and "NOT A MEASUREMENT" not in text and "privacy checklist" in text)
    text, problems = render_socket_report({("tcp", "127.0.0.1:5037"): 9}, meta, lookup)
    t("report: an empty list says so and does not claim proof", "remote addresses beyond loopback: 0" in text and "result: none beyond loopback" in text and "not a proof of absence" in text and problems == [])
    text, problems = render_socket_report({}, dict(meta, rows=0, running=0, early=True, seconds=95, installer="null"), lookup)
    t("report: a sideloaded installer, nothing readable, app never running and an early stop are each a problem, said first", len(problems) == 4 and text.startswith("NOT A MEASUREMENT: the installer is null") and "after 95 s of the 360 s asked" in text, text)
    with tempfile.TemporaryDirectory() as d:
        write_text(os.path.join(d, "sub"), "play-sockets.txt", "x\n")
        t("write_text makes the out-dir and writes play-sockets.txt", open(os.path.join(d, "sub", "play-sockets.txt")).read() == "x\n")
    t("args: the smoke's own form is unchanged", parse_args(["emulator-5554", "a.apk"]) == ("emulator-5554", "a.apk", None, False, 6.0) and parse_args(["s", "a.apk", "out"]) == ("s", "a.apk", "out", False, 6.0))
    t("args: --sockets-only has no APK and defaults to 6 minutes", parse_args(["s", "--sockets-only"]) == ("s", None, None, True, 6.0))
    t("args: --minutes and an out-dir, in either order", parse_args(["s", "--sockets-only", "--minutes", "2.5", "out"]) == ("s", None, "out", True, 2.5) and parse_args(["--minutes", "1", "s", "out", "--sockets-only"]) == ("s", None, "out", True, 1.0))
    t("args: refused: none, --minutes alone, a bad number, zero, an unknown option, a third positional with --sockets-only",
      all(refused(a) for a in ([], ["s"], ["s", "a.apk", "--minutes", "3"], ["s", "--sockets-only", "--minutes", "x"], ["s", "--sockets-only", "--minutes", "0"], ["s", "--sockets-only", "--minutes"], ["s", "a.apk", "--wat"], ["s", "--sockets-only", "a", "b"])))
    print(f"\n{'all passed' if not bad else str(len(bad)) + ' FAILED'}"); return 1 if bad else 0

def write_text(out_dir, name, text):
    os.makedirs(out_dir, exist_ok=True)
    with open(os.path.join(out_dir, name), "w") as f: f.write(text)

if sys.argv[1:] == ["--self-test"]: sys.exit(self_test())

SERIAL, APK, OUT_ARG, SOCKETS_ONLY, MINUTES = parse_args(sys.argv[1:])
if APK: APK = os.path.abspath(APK)
HERE = os.path.dirname(os.path.abspath(__file__)); ROOT = os.path.dirname(HERE)
OUT = os.path.abspath(OUT_ARG) if OUT_ARG else os.path.join(ROOT, "build", ("play-sockets-" if SOCKETS_ONLY else "release-smoke-") + time.strftime("%Y%m%d-%H%M%S") + "-" + SERIAL.replace("emulator-", ""))
os.makedirs(OUT, exist_ok=True)
ADB = [os.path.expanduser("~/Android/Sdk/platform-tools/adb"), "-s", SERIAL]
PKG = "io.github.tuthan.paddock"
HH = "/tmp/pdk-r10"; SESSION = "paddock-test-rel10"; PORT = "2233"
HERDR_BIN = os.environ.get("PADDOCK_HERDR", "/usr/bin/herdr")
RAND = format(int.from_bytes(os.urandom(4), "big"), "08x")
TITLE_MARK = f"TITLEMARK{RAND}"; OUT_MARK = f"OUTMARK{RAND}"
results = []

def check(name, ok, detail=""):
    results.append((name, bool(ok), detail)); print(("PASS " if ok else "FAIL ") + name + (f" - {detail}" if detail else ""), flush=True)

def adb(*a, check_=True, text=True):
    return subprocess.run(ADB + list(a), capture_output=True, text=text, check=check_).stdout

# ---------------------------------------------------------------- host
env_clean = {k: v for k, v in os.environ.items() if not k.startswith("HERDR_")}
HENV = dict(env_clean, HOME=HH, XDG_CONFIG_HOME=f"{HH}/.config", XDG_DATA_HOME=f"{HH}/.local/share", XDG_STATE_HOME=f"{HH}/.local/state", PATH=f"{HH}/bin:" + os.environ["PATH"])
SSHD_ENV = dict(os.environ, TEST_SSHD_RUN=os.path.join(ROOT, "build", "e2e-sshd-r10"), TEST_SSHD_PORT=PORT, TEST_SSHD_HOME=HH)
SSHD_LOG = os.path.join(SSHD_ENV["TEST_SSHD_RUN"], "sshd.log")

def H(*a, check_=True):
    r = subprocess.run([HERDR_BIN, "--session", SESSION, *a], env=HENV, capture_output=True, text=True)
    if check_ and r.returncode != 0: raise SystemExit(f"herdr {' '.join(a)} failed: {r.stderr or r.stdout}")
    return r.stdout

def jq(text):
    return json.loads(text)

procs = []
def setup_host():
    subprocess.run(["rm", "-rf", HH]); os.makedirs(f"{HH}/bin"); os.makedirs(f"{HH}/.local/bin"); os.makedirs(f"{HH}/work")
    for d in (f"{HH}/bin/claude", f"{HH}/.local/bin/claude"): os.symlink(os.path.join(HERE, "fake-agent.py"), d)
    server = subprocess.Popen([HERDR_BIN, "--session", SESSION, "server"], env=dict(HENV, HERDR_SOCKET_PATH=f"{HH}/.config/herdr/sessions/{SESSION}/herdr.sock"), cwd=f"{HH}/work",
                              stdout=open(f"{OUT}/herdr-server.log", "w"), stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True)
    procs.append(server)
    # `herdr status` exits 0 before the socket answers, so readiness is a request that the server itself answers.
    for _ in range(60):
        if subprocess.run([HERDR_BIN, "--session", SESSION, "workspace", "list"], env=HENV, capture_output=True).returncode == 0: break
        time.sleep(0.5)
    else: raise SystemExit("the disposable herdr did not come up")
    H("workspace", "create", "--cwd", f"{HH}/work", "--label", "release-smoke", "--no-focus")
    base = jq(H("pane", "list"))["result"]["panes"][0]["pane_id"]
    # A blocked agent whose title and output carry seeded markers: the herd must show the title, Output must show the text, and the log must show neither.
    H("pane", "run", base, f"printf '\\033]0;{TITLE_MARK}\\007'; echo {OUT_MARK}-line-one; echo {OUT_MARK}-line-two; FAKE_AGENT_LOG={OUT}/agent.log {HH}/bin/claude")
    for _ in range(40):
        if "fake agent ready" in H("pane", "read", base, "--source", "recent", "--lines", "20", check_=False): break
        time.sleep(0.25)
    H("pane", "report-agent", base, "--source", f"smoke-{RAND}", "--agent", "claude", "--state", "blocked", "--message", '{"kind":"release-smoke"}', "--seq", "1")
    info = jq(H("pane", "get", base))["result"]["pane"]
    return base, info

# The sshd's directory is cleared before the sshd starts: clearing it afterwards would delete the host key and the pid file of the running daemon
# (the fingerprint read here would then not be the one the phone sees, and `stop` could not find the process).
if not SOCKETS_ONLY:
    subprocess.run(["rm", "-rf", SSHD_ENV["TEST_SSHD_RUN"]])
    subprocess.run([os.path.join(HERE, "test-sshd.sh"), "start"], env=SSHD_ENV, check=True, capture_output=True)
    FP = subprocess.run([os.path.join(HERE, "test-sshd.sh"), "fingerprint"], env=SSHD_ENV, capture_output=True, text=True).stdout.split()[1]

def teardown():
    subprocess.run([HERDR_BIN, "session", "stop", SESSION, "--json"], env=HENV, capture_output=True)
    subprocess.run([HERDR_BIN, "session", "delete", SESSION, "--json"], env=HENV, capture_output=True)
    subprocess.run([os.path.join(HERE, "test-sshd.sh"), "stop"], env=SSHD_ENV, capture_output=True)
    for p in procs:
        try: os.killpg(p.pid, signal.SIGTERM)
        except Exception: pass
    if HH == "/tmp/pdk-r10": subprocess.run(["rm", "-rf", HH])

# ---------------------------------------------------------------- phone helpers
def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return ET.fromstring(adb("exec-out", "cat", "/sdcard/ui.xml"))

def nodes(root):
    for n in root.iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups()); yield n, ((x1 + x2) // 2, (y1 + y2) // 2)

def all_text(root):
    return " | ".join(v for n, _ in nodes(root) for v in (n.get("text", ""), n.get("content-desc", "")) if v)

def find(root, text, exact=False):
    for n, c in nodes(root):
        for attr in ("text", "content-desc", "hint"):
            v = n.get(attr, "").replace("’", "'"); want = text.replace("’", "'")
            if v and (v == want if exact else want.lower() in v.lower()): return n, c
    return None

def shot(name):
    with open(f"{OUT}/{name}.png", "wb") as f: f.write(subprocess.run(ADB + ["exec-out", "screencap", "-p"], capture_output=True).stdout)

def wait(text, secs=15, exact=False):
    end = time.time() + secs
    while time.time() < end:
        f = find(dump(), text, exact)
        if f: return f
        time.sleep(0.7)
    shot("timeout-" + re.sub(r"[^a-z0-9]+", "-", text.lower())[:30]); return None

def tap(text, secs=15, exact=False):
    f = wait(text, secs, exact)
    if not f: raise SystemExit(f"FAIL: timed out waiting for {text!r}")
    adb("shell", "input", "tap", str(f[1][0]), str(f[1][1])); time.sleep(0.7)

def type_into(label, value):
    tap(label); adb("shell", "input", "text", value.replace(" ", "%s")); time.sleep(0.4)

def scroll_down(): adb("shell", "input", "swipe", "540", "1500", "540", "700", "300"); time.sleep(0.5)

# ---------------------------------------------------------------- sockets of the app's uid
APP_UID = None
sockets = {}   # (proto, remote) -> count
samples = 0; rows_read = 0   # rounds taken, and /proc/net rows read (any uid): zero rows means the files were not readable
stop_sampling = threading.Event()
def sample_sockets():
    global samples, rows_read
    while not stop_sampling.is_set():
        for proto, path in (("tcp", "tcp"), ("tcp6", "tcp6"), ("udp", "udp"), ("udp6", "udp6")):
            try: text = adb("shell", "cat", f"/proc/net/{path}", check_=False)
            except Exception: continue
            found, rows = parse_proc_net(text, proto, APP_UID); rows_read += rows
            for remote in found: sockets[(proto, remote)] = sockets.get((proto, remote), 0) + 1
        samples += 1
        time.sleep(0.4)

def sockets_only():
    """--sockets-only: no install, no drive; sample the app uid's sockets for MINUTES while a person uses the build Google Play installed."""
    global APP_UID
    try: line = [l for l in adb("shell", "pm", "list", "packages", "-i", "-U", PKG).splitlines() if f"package:{PKG} " in l]
    except (subprocess.CalledProcessError, OSError) as e: print(f"cannot reach {SERIAL} with adb: {e}", file=sys.stderr); return 2
    if not line: print(f"{PKG} is not installed on {SERIAL}: install it from Google Play first (nothing is installed here)", file=sys.stderr); return 2
    APP_UID = int(re.search(r"uid:(\d+)", line[0]).group(1))
    installer = (re.search(r"installer=(\S+)", line[0]) or [None, "unknown"])[1]
    print(f"{PKG} uid {APP_UID} on {SERIAL}, installed by {installer}" + ("" if installer == "com.android.vending" else "  <- NOT Google Play: this will not describe the Play build"))
    print(f"Sampling for {MINUTES:g} minutes. Now, on the phone: cold start the app; two minutes idle on Home with no machine; Settings; Restore purchase;\n"
          "a licence-tester tip purchase and its thank-you; a second Restore; two minutes idle. Ctrl-C ends the run early (the file then says so).", flush=True)
    start = time.time(); started = time.strftime("%Y-%m-%d %H:%M:%S"); asked = MINUTES * 60; checks = running = 0; early = False
    last_check = last_print = -1e9
    threading.Thread(target=sample_sockets, daemon=True).start()
    try:
        while (elapsed := time.time() - start) < asked:
            if elapsed - last_check >= 10:   # is the app running at all? an empty list from an app that was never started measures nothing
                last_check = elapsed; checks += 1; running += bool(adb("shell", "pidof", PKG, check_=False).strip())
            if elapsed - last_print >= 30: last_print = elapsed; print(f"  {elapsed:.0f} s, {len(sockets)} socket(s) seen so far", flush=True)
            time.sleep(1)
    except KeyboardInterrupt: early = True
    stop_sampling.set(); time.sleep(0.6)
    meta = dict(pkg=PKG, uid=APP_UID, serial=SERIAL, installer=installer, start=started, seconds=time.time() - start, asked=asked, samples=samples, rows=rows_read, running=running, checks=checks, early=early)
    def lookup(ip): return socket.gethostbyaddr(ip)[0]
    text, problems = render_socket_report(dict(sockets), meta, lookup)
    print("\n" + text, end="")
    write_text(OUT, "play-sockets.txt", text); print(f"written to {os.path.join(OUT, 'play-sockets.txt')}")
    return 1 if problems else 0

# ---------------------------------------------------------------- run
def main():
    global APP_UID
    base, pane = setup_host()
    terminal_id = pane.get("terminal_id") or pane.get("terminal", {}).get("id")
    print(f"host: {H('--version', check_=False).strip().splitlines()[0] if H('--version', check_=False).strip() else HERDR_BIN}; pane {base} terminal {terminal_id}; sshd fp {FP}")

    adb("shell", "settings", "put", "secure", "stylus_handwriting_enabled", "0", check_=False)
    adb("shell", "am", "force-stop", PKG, check_=False)
    adb("uninstall", PKG, check_=False)
    inst = adb("install", "-r", APK)
    check("the signed release APK installs", "Success" in inst, inst.strip()[:80])
    adb("shell", "pm", "grant", PKG, "android.permission.ACCESS_LOCAL_NETWORK", check_=False)
    flags = adb("shell", "dumpsys", "package", PKG)
    check("the installed app is not debuggable", "DEBUGGABLE" not in flags.split("flags=[")[1].split("]")[0] if "flags=[" in flags else "debuggable" not in flags.lower(),
          re.search(r"flags=\[[^\]]*\]", flags).group(0)[:100] if re.search(r"flags=\[[^\]]*\]", flags) else "")
    run_as = subprocess.run(ADB + ["shell", "run-as", PKG, "ls"], capture_output=True, text=True)
    check("run-as is refused (a release build exposes no private files)", run_as.returncode != 0 or "not debuggable" in (run_as.stdout + run_as.stderr) or "Package" in (run_as.stdout + run_as.stderr), (run_as.stdout + run_as.stderr).strip()[:80])
    uid_line = [l for l in adb("shell", "pm", "list", "packages", "-U", PKG).splitlines() if f"package:{PKG} " in l]
    APP_UID = int(re.search(r"uid:(\d+)", uid_line[0]).group(1))
    adb("logcat", "-c", check_=False)
    threading.Thread(target=sample_sockets, daemon=True).start()

    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity"); time.sleep(2.5)
    check("launch shows Welcome", wait("Enter the address", 15) is not None)
    shot("00-welcome")
    tap("Enter the address")
    check("Enter the address leads to Add machine", wait("Add a machine", 15) is not None)
    shot("01-add-machine")
    type_into("Host or IP address", "10.0.2.2"); type_into("User", os.environ.get("USER", "jdoe"))
    tap("22", exact=True); adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    for _ in range(6): adb("shell", "input", "keyevent", "KEYCODE_DEL")
    adb("shell", "input", "text", PORT); time.sleep(0.4)
    adb("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(0.5)
    for _ in range(5):
        if find(dump(), "Create this phone's key", exact=True): break
        scroll_down()
    f = find(dump(), "Create this phone's key", exact=True)
    if f: adb("shell", "input", "tap", str(f[1][0]), str(f[1][1])); time.sleep(1.5)
    key = None
    for _ in range(6):
        m = re.search(r"ecdsa-sha2-nistp256 [A-Za-z0-9+/=]+(?: [A-Za-z0-9@._-]+)?", all_text(dump()))
        if m: key = m.group(0); break
        scroll_down()
    check("the phone shows its public key", key is not None, (key or "")[:60])
    shot("02-key")
    if key:
        keyfile = os.path.join(OUT, "phone.pub"); open(keyfile, "w").write(key + "\n")
        subprocess.run([os.path.join(HERE, "test-sshd.sh"), "authorize", keyfile], env=SSHD_ENV, check=True, capture_output=True)
    tap("Connect", exact=True)
    dlg = wait("Trust and connect", 25)
    txt = all_text(dump())
    check("the fingerprint dialog shows the sshd's own fingerprint", FP in txt, FP[:30] + "…")
    shot("03-fingerprint")
    if dlg: tap("Trust and connect")
    q = wait("Install the relay", 25)
    pin = re.search(r'"host/paddock-relay.py"\s*:\s*"sha256:([0-9a-f]{64})"', open(os.path.join(ROOT, "host", "SOURCE.json")).read()).group(1)
    check("the relay install question shows the hash the build pinned", q is not None and pin in all_text(dump()), pin[:16] + "…")
    shot("04-relay-install")
    if q: tap("Install the relay", exact=True)   # the heading "Install the relay on 10.0.2.2?" contains the text too; only the button is an exact match
    herd = wait(TITLE_MARK, 40)
    check("the herd lists the blocked agent by its (seeded) title", herd is not None)
    check("the row says Blocked and that the phone observed it", "Blocked" in all_text(dump()) and "observed" in all_text(dump()))
    shot("05-herd")
    if herd: adb("shell", "input", "tap", str(herd[1][0]), str(herd[1][1])); time.sleep(2)
    out = wait(OUT_MARK, 20)
    check("Output shows the agent's recent lines", out is not None)
    secure_out = window_secure()
    shot("06-output")
    if find(dump(), "Terminal", exact=True): tap("Terminal", exact=True); time.sleep(2.5)
    t = all_text(dump()).lower()
    check("the Terminal tab opens read-only (observing)", "observ" in t or "request control" in t or "read-only" in t, t[t.find("observ"):t.find("observ") + 50] if "observ" in t else "")
    shot("07-terminal")
    # Back one step at a time until the herd is the screen (the bottom bar, with its Spaces tab, is on the top-level screens only), and no further:
    # one Back too many would leave the app and make the FLAG_SECURE reading below a reading of the launcher.
    for _ in range(3):
        adb("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1.5)
        if find(dump(), "Spaces", exact=True): break
    shot("07b-home-again")
    check("back on Home the herd is listed again", find(dump(), "Spaces", exact=True) is not None and find(dump(), TITLE_MARK) is not None)
    # Home shows the blocked agent's captured prompt (agent output), so by design the window is protected there too. The flag must come off when
    # the prompt is gone: the agent is reported idle, Home must stop saying Blocked, and only then is the window read again.
    secure_home_prompt = window_secure()
    check("FLAG_SECURE is on while Output is shown", secure_out is True, f"{secure_out}")
    check("FLAG_SECURE is on on Home while the captured prompt is shown", secure_home_prompt is True, f"{secure_home_prompt}")
    H("pane", "report-agent", base, "--source", f"smoke-{RAND}", "--agent", "claude", "--state", "idle", "--seq", "2")
    for _ in range(40):
        if "Blocked" not in all_text(dump()): break
        time.sleep(0.5)
    time.sleep(1)
    secure_home_clear = window_secure()
    shot("07c-home-no-prompt")
    check("FLAG_SECURE is off on Home once no prompt is shown", "Blocked" not in all_text(dump()) and secure_home_clear is False, f"{secure_home_clear}")
    H("pane", "report-agent", base, "--source", f"smoke-{RAND}", "--agent", "claude", "--state", "blocked", "--message", '{"kind":"release-smoke"}', "--seq", "3")

    # an alert link, as a notification's Review fires it: resolved against a fresh read before anything is said about the agent
    adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(1)
    link = f"paddock://open?h=10-0-2-2&s={SESSION}&t={terminal_id}&p={base}&st=blocked&at={int(time.time())}&n=7"
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", f"'{link}'", PKG)
    landed = wait("Opened from an alert", 30)
    check("an alert link opens the agent after a fresh read, still blocked", landed is not None and "still blocked" in all_text(dump()).lower(), all_text(dump())[:0])
    shot("08-alert-link")
    bad = f"paddock://open?h=10-0-2-2&s={SESSION}&t=nonexistent&p=w9:p9&st=blocked&at={int(time.time())}&n=8"
    adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(1)
    adb("shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", f"'{bad}'", PKG)
    gone = wait("No longer observed", 30)
    check("a link to an agent that is not there says so and opens no answer surface", gone is not None)
    shot("09-alert-link-gone")

    stop_sampling.set(); time.sleep(0.6)
    # ---- the privacy half, from what the phone and the host recorded
    expected = {("tcp", "10.0.2.2:" + PORT), ("tcp6", "::ffff:10.0.2.2:" + PORT)}
    stray = sorted(f"{p} {r}" for (p, r) in sockets if (p, r) not in expected)
    check("every socket the app opened went to the sshd and to nothing else", not stray and any(k in expected for k in sockets),
          "; ".join(f"{p} {r}×{c}" for (p, r), c in sorted(sockets.items()))[:300] if sockets else "no socket sampled")
    open(f"{OUT}/sockets.txt", "w").write("".join(f"{p} {r} seen in {c} samples\n" for (p, r), c in sorted(sockets.items())))
    log = adb("logcat", "-d", "-v", "threadtime", check_=False)
    open(f"{OUT}/logcat.txt", "w").write(log)
    app_log = "\n".join(l for l in log.splitlines() if PKG in l or "Paddock" in l or "paddock" in l)
    leaks = [m for m in (TITLE_MARK, OUT_MARK, "PRIVATE KEY", "BEGIN OPENSSH", "BEGIN EC", "password=") if m in log]
    check("the logcat of the whole run holds none of the seeded markers, no private-key text and no password", not leaks, ", ".join(leaks) or f"{len(log.splitlines())} lines, {len(app_log.splitlines())} mention the app")
    sshd = open(SSHD_LOG).read() if os.path.exists(SSHD_LOG) else ""
    check("the sshd saw this phone's key accepted", "Accepted publickey" in sshd, [l for l in sshd.splitlines() if "Accepted publickey" in l][:1].__repr__()[:100])
    wanted = {"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"}
    granted = set(re.findall(r"(android\.permission\.[A-Z_]+): granted=true", adb("shell", "dumpsys", "package", PKG)))
    check("what the phone granted is within the manifest's five (CAMERA is asked for only in the scanner)", granted <= wanted | {"android.permission.POST_NOTIFICATIONS", "android.permission.ACCESS_LOCAL_NETWORK", "android.permission.USE_BIOMETRIC"}, ", ".join(sorted(g.rsplit(".", 1)[1] for g in granted)))

def window_secure():
    """Whether the app's window carries FLAG_SECURE, from the window manager's own dump; None when it cannot be read."""
    text = adb("shell", "dumpsys", "window", "windows", check_=False)
    blocks = re.split(r"\n  Window #", text)
    for b in blocks:
        if f"{PKG}/{PKG}.MainActivity" in b.split("\n")[0] or (PKG in b.split("\n")[0]):
            m = re.search(r"\bfl=([^\n]*)", b)
            if m:
                flags = m.group(1).split()[0]
                # Android 10 and later print the flag names; Android 8 prints the mask in hex (FLAG_SECURE is 0x2000).
                return bool(int(flags[1:], 16) & 0x2000) if flags.startswith("#") else "SECURE" in m.group(1)
    m = re.search(r"mCurrentFocus=.*", text)
    return None

if SOCKETS_ONLY: sys.exit(sockets_only())
try:
    main()
finally:
    stop_sampling.set()
    teardown()
fails = [r for r in results if not r[1]]
open(f"{OUT}/RESULT.txt", "w").write("".join(("PASS " if ok else "FAIL ") + n + (f" - {d}" if d else "") + "\n" for n, ok, d in results))
print(f"\n{len(results) - len(fails)} passed, {len(fails)} failed; results in {OUT}")
sys.exit(1 if fails else 0)
