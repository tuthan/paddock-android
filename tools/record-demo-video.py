#!/usr/bin/env python3
"""Records the demo machine's walk-through on an emulator: the video on the product page (docs/demo.md, "The recording").

    tools/record-demo-video.py <serial> [out.mp4]      out.mp4 defaults to demo.mp4; the poster is written beside it as <name>-poster.jpg

What it does, in one take of about 90 seconds: the first screen, Try a demo, Home, the working agent's Output and its read-only terminal, the codex agent
that blocks at 20 seconds, Review prompt, an idle agent's Output, a prompt typed and sent, the agent working and then done, Spaces, Home. Taps go to the
node with that text (a fresh uiautomator dump each time), so a moved button is found; a renamed one stops the script, which is the point.

Prepare the emulator first. This script sets the display, the theme and the status bar, but it does not install the app or change its settings:
  - install a foss build (debug is fine) and `pm clear io.github.tuthan.paddock`, so the first screen is Welcome;
  - Settings > Protect sensitive screens OFF (turn it on again after). While it is on, Android records the Output, composer and terminal screens as black;
    the script runs ffmpeg's blackdetect over the result and fails on a black stretch of a second or more.
Needs adb (PATH or ANDROID_HOME/platform-tools) and ffmpeg. No other dependency; it opens no network connection of its own.
The video it writes is silent; tools/make-demo-soundtrack.py puts the sound on it and its times file (tools/demo-soundtrack.json) must match the new take.
"""
import os, re, shutil, subprocess, sys, time

ADB = shutil.which("adb") or os.path.join(os.environ.get("ANDROID_HOME", os.path.expanduser("~/Android/Sdk")), "platform-tools", "adb")
PKG = "io.github.tuthan.paddock"
SIZE = (1080, 2160)


def main(argv):
    if not argv or argv[0].startswith("-"):
        sys.exit(__doc__)
    serial = argv[0]
    out = argv[1] if len(argv) > 1 else "demo.mp4"
    poster = os.path.splitext(out)[0] + "-poster.jpg"
    for tool in (ADB, "ffmpeg"):
        if not shutil.which(tool) and not os.path.exists(tool):
            sys.exit(f"{tool} not found")
    raw = out + ".raw.mp4"

    def adb(*a): return subprocess.run([ADB, "-s", serial, *a], capture_output=True)
    def sh(*a): return adb("shell", *a)

    def nodes():
        dump = adb("exec-out", "uiautomator", "dump", "/dev/tty").stdout.decode("utf-8", "replace")
        for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump):
            t, d, x1, y1, x2, y2 = m.groups()
            yield t, d, int(x1), int(y1), int(x2), int(y2)

    def texts(): return [t or d for t, d, *_ in nodes() if t or d]

    def tap(want, pause=1.0, tries=10):
        for _ in range(tries):
            for t, d, x1, y1, x2, y2 in nodes():
                if want.lower() in (t + "\n" + d).lower() and x2 > x1 and 0 <= y1 < y2 <= SIZE[1]:
                    sh("input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
                    time.sleep(pause)
                    return
            time.sleep(0.7)
        sys.exit(f"nothing to tap that says '{want}'")

    def wait_for(text, secs=45):
        end = time.time() + secs
        while time.time() < end:
            if any(text.lower() in t.lower() for t in texts()):
                return
            time.sleep(0.5)
        sys.exit(f"'{text}' never appeared")

    def swipe(y1, y2, ms=450): sh("input", "swipe", "540", str(y1), "540", str(y2), str(ms))

    # The same look every time: 1080x2160, dark, the clock at 9:41 with full signal and battery and no notification icons.
    sh("wm", "size", f"{SIZE[0]}x{SIZE[1]}")
    sh("cmd", "uimode", "night", "yes")
    sh("settings", "put", "global", "sysui_demo_allowed", "1")
    for extra in (["-e", "command", "enter"], ["-e", "command", "clock", "-e", "hhmm", "0941"],
                  ["-e", "command", "battery", "-e", "level", "100", "-e", "plugged", "false"],
                  ["-e", "command", "network", "-e", "wifi", "show", "-e", "level", "4", "-e", "fully", "true"],
                  ["-e", "command", "network", "-e", "mobile", "hide"], ["-e", "command", "notifications", "-e", "visible", "false"]):
        sh("am", "broadcast", "-a", "com.android.systemui.demo", *extra)

    sh("am", "force-stop", PKG)
    time.sleep(0.8)
    sh("am", "start", "-n", f"{PKG}/.MainActivity")
    time.sleep(2.5)
    rec = subprocess.Popen([ADB, "-s", serial, "shell", "screenrecord", "--size", f"{SIZE[0]}x{SIZE[1]}", "--bit-rate", "8000000", "--time-limit", "170", "/sdcard/demo.mp4"])
    try:
        time.sleep(1.5)                                          # the first screen
        swipe(1500, 600, 700); time.sleep(1.4)                   # down to Try a demo
        tap("Try a demo", 4.5)                                   # the demo's Home, on its clock
        time.sleep(1.5)
        tap("Refactor order validation", 3.2)                    # the working agent's Output
        tap("Terminal", 3.0)                                     # its terminal, read-only
        tap("Back", 1.5)
        wait_for("Needs you, 1"); time.sleep(3.0)                # the codex agent blocks at 20 seconds
        tap("Review prompt", 4.5)                                # its question, in the terminal
        tap("Back", 1.5)
        swipe(1500, 700); time.sleep(1.2)
        tap("Keep the test suite green", 3.2)                    # an idle agent's Output
        tap("Write a prompt for opencode", 2.0)                  # the composer
        tap("Ask opencode", 1.2)                                 # focus the field; a typed word needs it
        words = ["Add", "a", "test", "for", "the", "empty", "basket"]
        for i, w in enumerate(words):
            sh("input", "text", w)
            if i < len(words) - 1:
                sh("input", "keyevent", "62")
            time.sleep(0.18)
        time.sleep(1.0)
        tap("Send prompt", 1.6)
        tap("Back", 1.2)                                         # back to the agent's Output
        wait_for("nothing ran"); time.sleep(3.2)                 # the demo's agent works for 8 seconds, then answers
        tap("Back", 1.5)
        sh("input", "tap", "540", "2042"); time.sleep(3.0)       # Spaces (the bottom bar: Herd, Spaces, Activity)
        sh("input", "tap", "190", "2042"); time.sleep(2.0)       # Home again
    finally:
        sh("kill -2 $(pidof screenrecord)")
        try:
            rec.wait(timeout=30)
        except subprocess.TimeoutExpired:
            rec.kill()
    time.sleep(1.0)
    adb("pull", "/sdcard/demo.mp4", raw)
    sh("rm", "/sdcard/demo.mp4")

    black = subprocess.run(["ffmpeg", "-v", "info", "-i", raw, "-vf", "blackdetect=d=1:pix_th=0.05", "-an", "-f", "null", "-"], capture_output=True, text=True).stderr
    if "black_start" in black:
        sys.exit("the recording has a black stretch: is Settings > Protect sensitive screens still on? (kept: " + raw + ")")
    # Half size, 30 fps, no sound yet (tools/make-demo-soundtrack.py adds it), faststart: about a megabyte for 90 seconds, and it plays in every browser.
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", "0.5", "-i", raw, "-an", "-vf", "scale=540:1080:flags=lanczos,fps=30", "-c:v", "libx264", "-preset", "slow",
                    "-crf", "27", "-profile:v", "main", "-pix_fmt", "yuv420p", "-movflags", "+faststart", out], check=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-ss", "27", "-i", raw, "-frames:v", "1", "-vf", "scale=540:1080:flags=lanczos", "-q:v", "3", poster], check=True)
    os.remove(raw)
    print(f"{out} ({os.path.getsize(out) // 1024} KB) and {poster}")


if __name__ == "__main__":
    main(sys.argv[1:])
