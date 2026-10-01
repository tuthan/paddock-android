#!/usr/bin/env python3
"""AC-04.10 on an Android 17 emulator, driven through adb because the Compose test rule cannot run on API 37 (Espresso 3.5.0
calls a removed InputManager method; see the Phase 04 evidence). Uses uiautomator dumps to find views by text.

  tools/check-permission-flow.py [adb-serial] [out-dir]

Flow: clear app data, Add machine with a LAN address, press Connect, expect the system permission dialog in context,
deny it, expect the recovery row on Add machine, then add a VPN-style machine and expect the recovery row in Settings.
"""
import re, subprocess, sys, time, xml.etree.ElementTree as ET, os

SERIAL = sys.argv[1] if len(sys.argv) > 1 else "emulator-5574"
OUT = sys.argv[2] if len(sys.argv) > 2 else "build/permission-flow"
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
            if v and (v == text if exact else text.lower() in v.lower()):
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

bad = [n for n, ok, _ in results if not ok]
print("\nRESULT:", "all passed" if not bad else "FAILED: " + ", ".join(bad))
sys.exit(1 if bad else 0)
