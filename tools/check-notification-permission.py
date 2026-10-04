#!/usr/bin/env python3
"""AC-07.5 on an emulator, driven through adb with uiautomator dumps: a denied notification permission has a recovery path.

  tools/check-notification-permission.py [adb-serial] [out-dir]

Flow on Android 13 or later (the runtime permission): clear app data and revoke POST_NOTIFICATIONS, add a machine by a VPN-style
name (it needs no local-network grant and no host; Settings only needs the machine list), open Settings, turn "Alerts from this app"
on. The real system dialog must appear in context. Deny it: the recovery row offers "Allow notifications" (the system will show the
dialog once more). Ask again and deny again: the system now refuses to show it, so the row must change to "Open settings". Follow it
to the app's system notification page, allow notifications there, come back, and the row must be gone, in the same process.
Then turn off the "Needs you" channel on its system page: back in Settings the row must name the channel and offer "Open channel
settings", which lands on that channel's page; turning it on there removes the row.

Android 12 and earlier have no runtime permission; there the same script checks the other disabled case: notifications turned off
for the whole app on its system page. The row must say so and offer "Open settings".
"""
import os, re, subprocess, sys, time, xml.etree.ElementTree as ET

SERIAL = sys.argv[1] if len(sys.argv) > 1 else "emulator-5570"
OUT = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.environ.get("PADDOCK_HARNESS_OUT", "build"), "notification-permission")
os.makedirs(OUT, exist_ok=True)
ADB = [os.path.expanduser("~/Android/Sdk/platform-tools/adb"), "-s", SERIAL]
PKG = "io.github.tuthan.paddock"


def adb(*a, check=True):
    return subprocess.run(ADB + list(a), capture_output=True, text=True, check=check).stdout


def dump():
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
        try:
            return ET.fromstring(adb("exec-out", "cat", "/sdcard/ui.xml"))
        except ET.ParseError:
            time.sleep(0.5)
    raise SystemExit("FAIL: could not read the screen")


def nodes(root):
    for n in root.iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            yield n, ((x1 + x2) // 2, (y1 + y2) // 2)


def find(root, text, exact=False):
    want = text.replace("’", "'").lower()
    for n, c in nodes(root):
        for attr in ("text", "content-desc"):
            v = n.get(attr, "").replace("’", "'").lower()
            if v and (v == want if exact else want in v):
                return n, c
    return None


def shot(name):
    with open(f"{OUT}/{name}.png", "wb") as f:
        f.write(subprocess.run(ADB + ["exec-out", "screencap", "-p"], capture_output=True).stdout)


def wait(text, secs=15, exact=False):
    end = time.time() + secs
    while time.time() < end:
        f = find(dump(), text, exact)
        if f:
            return f
        time.sleep(0.7)
    shot("timeout-" + re.sub(r"[^a-z0-9]+", "-", text.lower())[:30])
    raise SystemExit(f"FAIL: timed out waiting for {text!r}")


def tap(text, secs=15, exact=False):
    n, (x, y) = wait(text, secs, exact)
    adb("shell", "input", "tap", str(x), str(y)); time.sleep(0.8)


def type_into(label, value):
    tap(label); adb("shell", "input", "text", value.replace(" ", "%s")); time.sleep(0.4)


def scroll_down():
    adb("shell", "input", "swipe", "540", "1700", "540", "700", "300"); time.sleep(0.6)


def scroll_to(text):
    for _ in range(6):
        if find(dump(), text):
            return True
        scroll_down()
    return False


def pid():
    return adb("shell", "pidof", PKG, check=False).strip()


results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(("PASS " if ok else "FAIL ") + name + (f" - {detail}" if detail else ""), flush=True)


sdk = int(adb("shell", "getprop", "ro.build.version.sdk").strip() or 0)
adb("shell", "settings", "put", "secure", "stylus_handwriting_enabled", "0", check=False)


def fresh_app_with_a_machine():
    adb("shell", "am", "force-stop", "com.android.settings")  # a page left open by an earlier run would be reused by the next launch
    adb("shell", "am", "force-stop", PKG)
    adb("shell", "pm", "clear", PKG)
    if sdk >= 33:
        adb("shell", "pm", "revoke", PKG, "android.permission.POST_NOTIFICATIONS", check=False)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity"); time.sleep(2.5)

    # a machine, so Settings exists
    wait("Add a machine")
    type_into("Host or IP address", "box.example.ts.net")
    type_into("User", "jdoe")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    for _ in range(4):
        if find(dump(), "Create this phone's key", exact=True):
            break
        scroll_down()
    f = find(dump(), "Create this phone's key", exact=True)
    if f:
        adb("shell", "input", "tap", str(f[1][0]), str(f[1][1])); time.sleep(1.0)
    for _ in range(3):  # a tap that lands while the keyboard is still closing does nothing; the form stays up
        tap("Connect", exact=True)
        time.sleep(2.0)
        if find(dump(), "Trust and connect"):
            tap("Trust and connect", secs=8)
        if find(dump(), "Add a machine") is None:
            break
    wait("Settings", secs=20); tap("Settings", exact=True)
    wait("Reconnect")


def system_page(*extra):
    adb("shell", "am", "force-stop", "com.android.settings")
    adb("shell", "am", "start", "-a", extra[0], "--es", "android.provider.extra.APP_PACKAGE", PKG, *extra[1:]); time.sleep(2)


def back_to_paddock():
    adb("shell", "am", "force-stop", "com.android.settings")
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity"); time.sleep(2)


def toggle_on_system_page(label, secs=10, exact=False):
    n, (x, y) = wait(label, secs, exact)
    adb("shell", "input", "tap", str(x), str(y)); time.sleep(1.5)


if sdk < 33:
    # No runtime permission here: the disabled case is notifications switched off for the whole app on its system page.
    fresh_app_with_a_machine()
    scroll_to("Alerts from this app")
    tap("Alerts from this app")  # no dialog here: the row only shows while the user has asked for alerts
    time.sleep(1)
    shot("alerts-on-api-lt33")
    check("with notifications on there is no recovery row", find(dump(), "in system settings") is None and find(dump(), "needs your permission") is None)
    system_page("android.settings.APP_NOTIFICATION_SETTINGS")
    shot("system-page-api-lt33")
    toggle_on_system_page("On", exact=True)  # the master switch is labelled On or Off here
    shot("system-page-off")
    back_to_paddock()
    scroll_to("Notifications are turned off for Paddock in system settings")
    shot("recovery-app-off")
    check("turned off for the whole app: the row says so and offers Open settings",
          find(dump(), "Notifications are turned off for Paddock in system settings") is not None and find(dump(), "Open settings", exact=True) is not None)
    tap("Open settings", exact=True); time.sleep(2)
    check("Open settings lands on Paddock's own notification page", find(dump(), "Paddock") is not None and find(dump(), "Off", exact=True) is not None)
    toggle_on_system_page("Off", exact=True)
    back_to_paddock()
    scroll_to("Alerts from this app")
    check("turned on again: the row is gone", find(dump(), "in system settings") is None)
    print("ALL CHECKS PASSED" if all(results) else "SOME CHECKS FAILED")
    sys.exit(0 if all(results) else 1)

fresh_app_with_a_machine()
check("the permission is not granted before the user asks", "POST_NOTIFICATIONS: granted=true" not in adb("shell", "dumpsys", "package", PKG))

# 1. turning alerts on asks, in context, with the real system dialog
scroll_to("Alerts from this app")
shot("settings-alerts-off")
tap("Alerts from this app")
dlg = wait("notifications", secs=10)
shot("dialog-first")
txt = " | ".join(n.get("text", "") for n, _ in nodes(dump()) if n.get("text"))
check("the system permission dialog appears when Alerts from this app is turned on", "send you notifications" in txt.lower() or "allow" in txt.lower() and "notifications" in txt.lower(), txt[:120])


def deny():
    for label in ("Don't allow", "Don’t allow", "Deny"):
        f = find(dump(), label)
        if f:
            adb("shell", "input", "tap", str(f[1][0]), str(f[1][1])); time.sleep(1.0); return True
    return False


check("denying is possible from the dialog", deny())
scroll_to("Paddock needs your permission to show notifications")
shot("recovery-ask")
check("the first denial leaves a recovery row that offers the dialog again", find(dump(), "Paddock needs your permission to show notifications") is not None and find(dump(), "Allow notifications", exact=True) is not None)

# 2. ask again, deny again: the system will not show the dialog a third time, so the row must say where to go
tap("Allow notifications", exact=True)
wait("notifications", secs=10)
shot("dialog-second")
deny()
scroll_to("Notifications are blocked for Paddock in system settings")
shot("recovery-settings")
check("after the second denial the row sends the user to system settings", find(dump(), "Notifications are blocked for Paddock in system settings") is not None and find(dump(), "Open settings", exact=True) is not None)

# 3. the system page: allow there, come back, the row is gone, in the same process
before = pid()
tap("Open settings", exact=True)
time.sleep(2)
shot("system-page")
check("Open settings lands on Paddock's own notification page", find(dump(), "Paddock") is not None and (find(dump(), "All Paddock notifications") is not None or find(dump(), "Notifications") is not None))
f = find(dump(), "All Paddock notifications") or find(dump(), "Allow notifications")
if f:
    adb("shell", "input", "tap", str(f[1][0]), str(f[1][1])); time.sleep(1.5)
shot("system-page-after")
check("allowing there grants the permission", "POST_NOTIFICATIONS: granted=true" in adb("shell", "dumpsys", "package", PKG))
for _ in range(4):
    adb("shell", "input", "keyevent", "KEYCODE_BACK"); time.sleep(1.0)
    if PKG in " ".join(l for l in adb("shell", "dumpsys", "activity", "activities").splitlines() if "topResumedActivity" in l):
        break
time.sleep(1.5)
gone = find(dump(), "Notifications are blocked for Paddock in system settings") is None and find(dump(), "Paddock needs your permission to show notifications") is None
shot("recovered")
check("back in Settings the recovery row is gone (read again on resume)", gone)
check("the app was not restarted by the grant", pid() == before and before != "", f"pid {before} -> {pid()}")

# 4. one alert channel turned off on the system's channel page: the row names it and goes to that page
system_page("android.settings.CHANNEL_NOTIFICATION_SETTINGS", "--es", "android.provider.extra.CHANNEL_ID", "needs_you")
shot("channel-page")
toggle_on_system_page("Show notifications")
shot("channel-page-off")
back_to_paddock()
scroll_to('The "Needs you" channel is turned off in system settings')
shot("recovery-channel-off")
check("a channel turned off: the row names it and offers Open channel settings",
      find(dump(), 'The "Needs you" channel is turned off in system settings') is not None and find(dump(), "Open channel settings", exact=True) is not None)
tap("Open channel settings", exact=True); time.sleep(2)
shot("channel-page-from-row")
check("Open channel settings lands on that channel's page", find(dump(), "Needs you") is not None and find(dump(), "Show notifications") is not None)
toggle_on_system_page("Show notifications")
back_to_paddock()
scroll_to("Alerts from this app")
shot("recovered-channel")
check("the channel turned on again: the row is gone", find(dump(), "channel is turned off") is None)

print("ALL CHECKS PASSED" if all(results) else "SOME CHECKS FAILED")
sys.exit(0 if all(results) else 1)
