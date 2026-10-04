#!/usr/bin/env python3
"""AC-04.10 on an Android 17 emulator, driven through adb because the Compose test rule cannot run on API 37 (Espresso 3.5.0
calls a removed InputManager method; see the Phase 04 evidence). Uses uiautomator dumps to find views by text.

  tools/check-permission-flow.py [adb-serial] [out-dir]

Flow: clear app data, Add machine with a LAN address, press Connect, expect the system permission dialog in context,
deny it, expect the recovery row on Add machine, then add a VPN-style machine and expect the recovery row in Settings.
Then the other half of AC-02.10: with the grant revoked, press Connect for the throwaway sshd (tools/test-sshd.sh, loopback,
reached from the emulator at 10.0.2.2), tap Allow in the real system dialog, and expect the same process to go on connecting,
without a restart: the host's key is offered for trust (the app shows that only after the key exchange, so the OS let the
socket through) and the sshd log shows the connection. The sshd is started and stopped by this script on port 2236.
Finally the same two changes through the real system settings page (App info > Permissions > Nearby devices): deny in the
dialog, tap the recovery row's Open settings, choose Allow there, come back, and the same process connects; then revoke on the
page, which ends the process, and the next attempt is refused (0 new sshd connections) with the recovery row.
"""
import re, subprocess, sys, time, xml.etree.ElementTree as ET, os

import harness

SERIAL = sys.argv[1] if len(sys.argv) > 1 else "emulator-5574"
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(harness.OUT_BASE, "permission-flow")
os.makedirs(OUT, exist_ok=True)
ADB = [os.path.expanduser("~/Android/Sdk/platform-tools/adb"), "-s", SERIAL]
PKG = "io.github.tuthan.paddock"

def adb(*a, check=True):
    return subprocess.run(ADB + list(a), capture_output=True, text=True, check=check).stdout

def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return ET.fromstring(adb("exec-out", "cat", "/sdcard/ui.xml"))

def nodes(root):
    for n in root.iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            yield n, ((x1 + x2) // 2, (y1 + y2) // 2)

def find(root, text, exact=False):
    for n, c in nodes(root):
        for attr in ("text", "content-desc", "hint"):
            v = n.get(attr, "")
            v, want = v.replace("\u2019", "'"), text.replace("\u2019", "'")  # the system pages write "Don’t allow"
            if v and (v == want if exact else want.lower() in v.lower()):
                return n, c
    return None

def wait(text, secs=15, exact=False):
    end = time.time() + secs
    while time.time() < end:
        r = dump(); f = find(r, text, exact)
        if f: return f
        time.sleep(0.7)
    shot("timeout-" + re.sub(r"[^a-z0-9]+", "-", text.lower())[:30])
    raise SystemExit(f"FAIL: timed out waiting for {text!r}")

def tap(text, secs=15, exact=False):
    n, (x, y) = wait(text, secs, exact); adb("shell", "input", "tap", str(x), str(y)); time.sleep(0.6)

def type_into(label, value):
    tap(label); adb("shell", "input", "text", value.replace(" ", "%s")); time.sleep(0.4)

def shot(name):
    with open(f"{OUT}/{name}.png", "wb") as f:
        f.write(subprocess.run(ADB + ["exec-out", "screencap", "-p"], capture_output=True).stdout)

def scroll_down():
    adb("shell", "input", "swipe", "540", "1500", "540", "700", "300"); time.sleep(0.5)

def fill_machine(host, user):
    wait("Add a machine")
    type_into("Host or IP address", host)
    type_into("User", user)
    adb("shell", "input", "keyevent", "KEYCODE_BACK")  # hide the keyboard

def ensure_key():
    for _ in range(4):
        if find(dump(), "Create this phone's key", exact=True): break
        scroll_down()
    f = find(dump(), "Create this phone's key", exact=True)
    if f: adb("shell", "input", "tap", str(f[1][0]), str(f[1][1])); time.sleep(1.0)

# Gboard on an Android 17 image opens a "Try out your stylus" sheet over the first tapped text field and takes the typed text.
adb("shell", "settings", "put", "secure", "stylus_handwriting_enabled", "0", check=False)

results = []
def check(name, ok, detail=""):
    results.append((name, ok, detail)); print(("PASS " if ok else "FAIL ") + name + (f" - {detail}" if detail else ""))

adb("shell", "pm", "clear", PKG)
adb("shell", "pm", "revoke", PKG, "android.permission.ACCESS_LOCAL_NETWORK", check=False)
adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity"); time.sleep(2)

# 1. LAN address: the reason is shown before Connect, then the system dialog appears on Connect.
fill_machine("192.168.1.20", "jdoe")
r = dump()
check("reason shown on Add machine before Connect", find(r, "Android asks to let Paddock reach devices on your network") is not None)
ensure_key()
tap("Connect", exact=True)
dlg = wait("nearby devices", secs=10)
shot("permission-dialog")
txt = " | ".join(n.get("text", "") for n, _ in nodes(dump()) if n.get("text"))
check("the system permission dialog appears on Connect, over Add machine", "nearby devices" in txt.lower() and find(dump(), "Allow", exact=True) is not None, txt[:140])

# 2. Deny: recovery row on Add machine names the reason.
for label in ("Don’t allow", "Don't allow", "Deny"):
    f = find(dump(), label)
    if f: adb("shell", "input", "tap", str(f[1][0]), str(f[1][1])); break
time.sleep(1.0)
r = wait("Local-network access is off", secs=10)
shot("add-machine-denied")
check("denial shows the recovery row on Add machine", find(dump(), "Open settings") is not None)

# 3. A VPN-style address needs no grant and reaches Home; Settings then shows the recovery row.
adb("shell", "am", "force-stop", PKG)
adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity"); time.sleep(2)
wait("Add a machine")
type_into("Host or IP address", "box.example.ts.net")
type_into("User", "jdoe")
adb("shell", "input", "keyevent", "KEYCODE_BACK")
check("a VPN name needs no local-network access", find(dump(), "no local-network access needed") is not None)
ensure_key()
tap("Connect", exact=True)
time.sleep(1.5)
tap("Trust and connect", secs=8) if find(dump(), "Trust and connect") else None
wait("Settings", secs=15); tap("Settings", exact=True)
# The first section is always there; the recovery row may be below the fold, so scroll for it.
wait("Reconnect")
for _ in range(3):
    if find(dump(), "Local-network access is off"): break
    scroll_down()
shot("settings-denied")
check("denial shows the recovery row in Settings", find(dump(), "Local-network access is off") is not None and find(dump(), "Open settings") is not None)

# 4. Grant from the real dialog, then connect in the same process (AC-02.10, the other half).
SSHD_ENV = dict(os.environ, TEST_SSHD_RUN=os.path.join(harness.OUT_BASE, "perm-sshd"), TEST_SSHD_PORT="2236")
SSHD_LOG = os.path.join(SSHD_ENV["TEST_SSHD_RUN"], "sshd.log")

def replace_into(current, value):
    """Tap the editable field whose text is [current], clear it, type [value]."""
    tap(current, exact=True)
    adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
    for _ in range(10): adb("shell", "input", "keyevent", "KEYCODE_DEL")
    adb("shell", "input", "text", value); time.sleep(0.4)

subprocess.run([os.path.join(harness.TOOLS, "test-sshd.sh"), "start"], env=SSHD_ENV, check=True, capture_output=True)
try:
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "pm", "clear", PKG)
    adb("shell", "pm", "revoke", PKG, "android.permission.ACCESS_LOCAL_NETWORK", check=False)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity"); time.sleep(2)
    fill_machine("10.0.2.2", os.environ.get("USER", "jdoe"))
    replace_into("22", "2236")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    ensure_key()
    tap("Connect", exact=True)
    wait("nearby devices", secs=10)
    shot("grant-dialog")
    pid_before = adb("shell", "pidof", PKG).strip()
    tap("Allow", exact=True)
    seen = wait("Trust and connect", secs=25)
    shot("grant-then-trust")
    check("after Allow in the real dialog the same process goes on connecting: the host's key is offered for trust", seen is not None)
    check("the app was not restarted by the grant", adb("shell", "pidof", PKG).strip() == pid_before and pid_before != "", f"pid {pid_before}")
    log = open(SSHD_LOG).read() if os.path.exists(SSHD_LOG) else ""
    check("the sshd saw the connection", "Connection from" in log or "Connection closed by" in log or "Failed publickey" in log or "kex" in log.lower(), log.strip().splitlines()[-1][:120] if log.strip() else "empty log")
finally:
    subprocess.run([os.path.join(harness.TOOLS, "test-sshd.sh"), "stop"], env=SSHD_ENV, capture_output=True)

# 5. The same grant and revoke through the real system settings page (AC-02.10: "granting from the recovery row ... revoking
# in system settings produces the recovery row on the next attempt").
def pid(): return adb("shell", "pidof", PKG, check=False).strip()  # exits 1, with no output, when there is no such process
def fresh_settings(home=False):
    """The permission pages are the permission controller's, in a task of their own: stop both so the app's intent opens App info."""
    for pkg in ("com.android.settings", "com.google.android.permissioncontroller"):
        adb("shell", "am", "force-stop", pkg, check=False)
    if home: adb("shell", "input", "keyevent", "KEYCODE_HOME"); time.sleep(0.8)
def connections(): return open(SSHD_LOG).read().count("Connection from") if os.path.exists(SSHD_LOG) else 0

def deny_dialog():
    for label in ("Don't allow", "Deny"):
        f = find(dump(), label)
        if f: adb("shell", "input", "tap", str(f[1][0]), str(f[1][1])); return True
    return False

def settings_page_set(choice):
    """On the app's own system page pick [choice] ("Allow" or "Don't allow") under Permissions > Nearby devices."""
    if find(dump(), "Nearby devices access for this app") is None:  # the Settings task may already be on the last page it showed
        tap("Permissions", secs=10, exact=True)
        tap("Nearby devices", secs=10)
        wait("Nearby devices access for this app", secs=10)
    tap(choice, secs=10, exact=True)

def back_to_app():
    for _ in range(6):
        if PKG in adb("shell", "dumpsys", "activity", "activities"): 
            top = [l for l in adb("shell", "dumpsys", "activity", "activities").splitlines() if "topResumedActivity" in l or "mResumedActivity" in l]
            if top and PKG in top[0]: return True
        adb("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1.0)
    return False

def connect_to_throwaway_sshd():
    fill_machine("10.0.2.2", os.environ.get("USER", "jdoe"))
    replace_into("22", "2236")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    ensure_key()
    tap("Connect", exact=True)

subprocess.run([os.path.join(harness.TOOLS, "test-sshd.sh"), "start"], env=SSHD_ENV, check=True, capture_output=True)
try:
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "pm", "clear", PKG)
    adb("shell", "pm", "revoke", PKG, "android.permission.ACCESS_LOCAL_NETWORK", check=False)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity"); time.sleep(2)
    connect_to_throwaway_sshd()
    wait("nearby devices", secs=10)
    deny_dialog(); time.sleep(1.0)
    wait("Local-network access is off", secs=10)
    shot("settings-path-denied")
    before_pid = pid()
    fresh_settings()  # a Settings or permission-controller task left on a deep page would be resumed as it is
    tap("Open settings", exact=True)
    wait("Permissions", secs=10, exact=True)
    shot("settings-path-app-info")
    check("Open settings lands on the app's own system page", find(dump(), "App info") is not None or find(dump(), "Permissions", exact=True) is not None)
    settings_page_set("Allow")
    shot("settings-path-allowed")
    check("back from the settings page the app is on top again", back_to_app())
    check("granting on the settings page did not restart the app", pid() == before_pid and before_pid != "", f"pid {before_pid} -> {pid()}")
    gone = False
    for _ in range(12):
        if find(dump(), "Local-network access is off") is None: gone = True; break
        time.sleep(0.7)
    shot("settings-path-after-return")
    check("the recovery row is gone once the grant is on (read again on resume)", gone)
    seen_before = connections()
    tap("Connect", exact=True)
    seen = wait("Trust and connect", secs=25)
    shot("settings-path-trust")
    check("after granting on the settings page Connect goes on in the same process", seen is not None and pid() == before_pid)
    check("the sshd saw a new connection", connections() > seen_before, f"{seen_before} -> {connections()}")
    tap("Cancel", exact=True)

    # Revoke through the same page: the system ends the app's process, and the next attempt is refused.
    connections_at_revoke = connections()
    fresh_settings(home=True)
    adb("shell", "am", "start", "-a", "android.settings.APPLICATION_DETAILS_SETTINGS", "-d", f"package:{PKG}"); time.sleep(2)
    settings_page_set("Don't allow")
    ended = False
    for _ in range(12):
        if pid() == "": ended = True; break
        time.sleep(0.7)
    check("revoking on the settings page ends the app's process", ended)
    # The machine was saved when the grant was on, so the next launch is Home and its monitor makes the next attempt.
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity"); time.sleep(2)
    wait("Local-network access is off", secs=20)
    shot("settings-path-revoked")
    check("after the revoke the next attempt shows the recovery row on Home", find(dump(), "Open settings") is not None)
    time.sleep(3)
    check("and no connection reached the sshd", connections() == connections_at_revoke, f"{connections_at_revoke} -> {connections()}")
finally:
    subprocess.run([os.path.join(harness.TOOLS, "test-sshd.sh"), "stop"], env=SSHD_ENV, capture_output=True)

bad = [n for n, ok, _ in results if not ok]
print("\nRESULT:", "all passed" if not bad else "FAILED: " + ", ".join(bad))
sys.exit(1 if bad else 0)
