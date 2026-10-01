#!/usr/bin/env python3
"""AC-04.5 on a real emulator IME and real rotation, driven through adb.

  tools/check-add-machine-ime.py [adb-serial] [out-dir]

1. With the keyboard up, Connect is still reachable: a tap on it is answered by the form's error text, not swallowed by the IME.
2. Typed values survive rotation to landscape and back, and Connect is on screen in both.
"""
import os, re, subprocess, sys, time, xml.etree.ElementTree as ET

SERIAL = sys.argv[1] if len(sys.argv) > 1 else "emulator-5572"
OUT = sys.argv[2] if len(sys.argv) > 2 else "build/ime-check"
os.makedirs(OUT, exist_ok=True)
ADB = [os.path.expanduser("~/Android/Sdk/platform-tools/adb"), "-s", SERIAL]
PKG = "io.github.tuthan.paddock"

def adb(*a):
    return subprocess.run(ADB + list(a), capture_output=True, text=True).stdout

def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    return ET.fromstring(adb("exec-out", "cat", "/sdcard/ui.xml"))

def find(root, text, exact=False):
    for n in root.iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if not m: continue
        x1, y1, x2, y2 = map(int, m.groups())
        for attr in ("text", "content-desc", "hint"):
            v = n.get(attr, "")
            if v and (v == text if exact else text.lower() in v.lower()):
                return ((x1 + x2) // 2, (y1 + y2) // 2), (x1, y1, x2, y2)
    return None

def wait(text, secs=12, exact=False):
    end = time.time() + secs
    while time.time() < end:
        f = find(dump(), text, exact)
        if f: return f
        time.sleep(0.6)
    shot("timeout"); raise SystemExit(f"FAIL: timed out waiting for {text!r}")

def tap(text, exact=False):
    (x, y), _ = wait(text, exact=exact); adb("shell", "input", "tap", str(x), str(y)); time.sleep(0.6)

def shot(name):
    with open(f"{OUT}/{name}.png", "wb") as f:
        f.write(subprocess.run(ADB + ["exec-out", "screencap", "-p"], capture_output=True).stdout)

def ime_shown():
    return "mInputShown=true" in adb("shell", "dumpsys", "input_method")

def screen():
    m = re.search(r"(\d+)x(\d+)", adb("shell", "wm", "size").splitlines()[-1]); return int(m[1]), int(m[2])

fails = []
def check(name, ok, detail=""):
    print(("PASS " if ok else "FAIL ") + name + (f" - {detail}" if detail else "")); 
    if not ok: fails.append(name)

orig_auto = adb("shell", "settings", "get", "system", "accelerometer_rotation").strip() or "1"
adb("shell", "settings", "put", "system", "accelerometer_rotation", "0"); adb("shell", "settings", "put", "system", "user_rotation", "0")
try:
    adb("shell", "pm", "clear", PKG); adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity"); time.sleep(2)
    wait("Add a machine")

    # 1. keyboard up, then press Connect on an empty form
    tap("Host or IP address"); time.sleep(1.0)
    check("keyboard is up", ime_shown())
    shot("ime-up")
    tap("Connect", exact=True); time.sleep(0.8)
    check("Connect answers while the keyboard is up (error shown)", find(dump(), "Enter a hostname or IP address.") is not None)

    # 2. rotation keeps the typed values
    adb("shell", "input", "text", "box.example.ts.net"); tap("User"); adb("shell", "input", "text", "jdoe")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    w, h = screen()
    adb("shell", "settings", "put", "system", "user_rotation", "1"); time.sleep(2.5)
    r = dump()
    host = find(r, "box.example.ts.net")
    c = find(r, "Connect", exact=True)
    # Landscape is short and the keyboard comes back with the focused field, so the User field can be scrolled out of view:
    # only the focused host field is asserted here; both values are asserted again after returning to portrait.
    check("the host value survives rotation to landscape", host is not None)
    check("Connect is on screen in landscape", c is not None and 0 <= c[1][1] and c[1][3] <= max(w, h))
    shot("landscape")
    adb("shell", "settings", "put", "system", "user_rotation", "0"); time.sleep(2.5)
    r = dump()
    check("host and user survive rotation back to portrait", find(r, "box.example.ts.net") is not None and find(r, "jdoe", exact=True) is not None)
    check("Connect is on screen in portrait", find(r, "Connect", exact=True) is not None)
finally:
    adb("shell", "settings", "put", "system", "user_rotation", "0")
    adb("shell", "settings", "put", "system", "accelerometer_rotation", orig_auto)

print("\nRESULT:", "all passed" if not fails else "FAILED: " + ", ".join(fails))
sys.exit(1 if fails else 0)
