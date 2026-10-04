"""Which app checkout a Python check works against and where its output goes: the same rules as harness.sh.

    PADDOCK_APP          the paddock-android checkout (default: the checkout this file sits in); refused unless its settings.gradle.kts
                         names paddock-android
    PADDOCK_HARNESS_OUT  where run directories are written (default: <app>/build)

APP is the checkout, TOOLS its tools/ (the stand-ins that stay with the app), OUT_BASE the output base. TEST_SSHD_RUN gets the same
default as in harness.sh, so a child running test-sshd.sh agrees with this process about the run directory.
"""
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
APP = os.path.abspath(os.environ.get("PADDOCK_APP") or os.path.join(HERE, ".."))
try:
    with open(os.path.join(APP, "settings.gradle.kts")) as f:
        _named = 'rootProject.name = "paddock-android"' in f.read().splitlines()
except OSError:
    _named = False
if not _named:
    sys.stderr.write("not a paddock-android checkout: %s\n  set PADDOCK_APP to the checkout, or put it next to this directory as ../paddock-android\n" % APP)
    sys.exit(2)

TOOLS = os.path.join(APP, "tools")
OUT_BASE = os.path.abspath(os.environ.get("PADDOCK_HARNESS_OUT") or os.path.join(APP, "build"))
os.environ["PADDOCK_APP"] = APP
os.environ["PADDOCK_HARNESS_OUT"] = OUT_BASE
os.environ.setdefault("TEST_SSHD_RUN", os.path.join(OUT_BASE, "test-sshd"))
