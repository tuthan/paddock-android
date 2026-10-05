#!/usr/bin/env python3
"""Static privacy and manifest inspection of a built APK (the Phase 10 checklist's "manifest inspection", AC-10.4).

  tools/check-release-apk.py <apk> [report-file] [--flavor foss|play]

--flavor foss (the default, and the strict one: GitHub, F-Droid, IzzyOnDroid) keeps the whole deny list below, Play Billing, Play services and
Google's datatransport included. --flavor play checks the Play build against the named additions in PLAY_* below, written from a built play APK
(Phase 13, 2026-10-04) and never allowed by a broad prefix: the one extra permission, the two extra <queries> intents, the six extra components
(all not exported) and the dex packages under the billing, Play-services and datatransport prefixes. Anything else that appears fails, and the list
is extended by a person reading the diff, not by the script.

Checks, each printed as PASS or FAIL with what was found; the exit status is 1 if any FAIL:
  - the permission set is exactly the four the design names (nothing else, no READ_LOGS, no storage, no contacts, no location); play adds com.android.vending.BILLING and nothing else;
  - not debuggable; allowBackup false; usesCleartextTraffic false; a network security config and both backup rule files are present;
  - the exported components are exactly the launcher activity, the UnifiedPush receiver and the three widget providers, plus the profile installer's
    receiver only while it requires the DUMP permission;
  - no analytics, crash-reporting, advertising or attribution SDK is in the dex (the package prefixes below), and no development
    tooling (the Compose tooling and test manifest) is in a release build;
  - every host script in assets/ has the SHA-256 that host/SOURCE.json pins, and its .sha256 sibling says the same;
  - every URL string in the dex and assets is listed, and none is on a host outside the allow-list (XML namespaces and licence links).
Not checked here (they need a running app): the sockets the app opens, FLAG_SECURE on the window, and the log; tools/run-release-smoke.py does those.
"""
import hashlib, json, os, re, subprocess, sys, zipfile

args = sys.argv[1:]
FLAVOR = "foss"
if "--flavor" in args:
    i = args.index("--flavor")
    FLAVOR = args[i + 1] if i + 1 < len(args) else ""
    del args[i:i + 2]
if FLAVOR not in ("foss", "play") or not args:
    sys.exit("usage: check-release-apk.py <apk> [report-file] [--flavor foss|play]")
APK = args[0]
REPORT = args[1] if len(args) > 1 else None
PLAY = FLAVOR == "play"

# What the play flavor adds over foss, written from a built play APK (Phase 13) and named in full; see docs/billing.md.
PLAY_PERMISSIONS = ["com.android.vending.BILLING"]
PLAY_QUERY_ACTIONS = ["com.android.vending.billing.InAppBillingService.BIND", "com.google.android.apps.play.billingtestcompanion.BillingOverrideService.BIND"]
PLAY_COMPONENTS = sorted([
    "com.android.billingclient.api.ProxyBillingActivity", "com.android.billingclient.api.ProxyBillingActivityV2",
    "com.google.android.gms.common.api.GoogleApiActivity",
    "com.google.android.datatransport.runtime.backends.TransportBackendDiscovery",
    "com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService",
    "com.google.android.datatransport.runtime.scheduling.jobscheduling.AlarmManagerSchedulerBroadcastReceiver",
])
PLAY_DEX_PACKAGES = {
    "com/android/billingclient/api", "com/google/android/datatransport/cct", "com/google/android/datatransport/runtime/backends",
    "com/google/android/datatransport/runtime/scheduling/jobscheduling", "com/google/android/gms/auth/api/signin", "com/google/android/gms/common",
    "com/google/android/gms/common/annotation", "com/google/android/gms/common/api", "com/google/android/gms/common/internal",
    "com/google/android/gms/common/util", "com/google/android/gms/dynamite",
}
# The prefixes the play flavor may carry, each only as the packages named above.
PLAY_PREFIXES = (b"com/android/billingclient", b"com/google/android/gms", b"com/google/android/datatransport")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AAPT2 = os.path.join(os.environ.get("ANDROID_HOME", os.path.expanduser("~/Android/Sdk")), "build-tools/36.0.0/aapt2")

lines, failed = [], False
def say(ok, what, detail=""):
    global failed
    failed |= not ok
    lines.append(f"{'PASS' if ok else 'FAIL'}  {what}" + (f": {detail}" if detail else ""))

def aapt(*args):
    return subprocess.run([AAPT2, *args], capture_output=True, text=True, check=True).stdout

z = zipfile.ZipFile(APK)
names = z.namelist()

# ---- manifest ----
tree = aapt("dump", "xmltree", "--file", "AndroidManifest.xml", APK)
perms = sorted(set(re.findall(r'uses-permission[^\n]*\n\s+A: http://schemas.android.com/apk/res/android:name\(0x01010003\)="([^"]+)"', tree)))
EXPECTED = sorted(["android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.POST_NOTIFICATIONS", "android.permission.ACCESS_LOCAL_NETWORK"] + (PLAY_PERMISSIONS if PLAY else []))
# AGP adds a signature-level DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION for the app's own receivers; it is the app's, not a grant.
perms = [p for p in perms if not p.endswith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")]
say(perms == EXPECTED, "permission set is exactly the four named" if not PLAY else "permission set is exactly the four named plus BILLING", ", ".join(p.rsplit(".", 1)[1] for p in perms))

def attr(name):
    m = re.search(r'A: http://schemas.android.com/apk/res/android:%s\([^)]*\)=(?:"([^"]*)"|\(type 0x\w+\)(0x\w+))' % name, tree)
    return None if not m else (m.group(1) if m.group(1) is not None else m.group(2))
app = tree[tree.index("E: application"):]
def app_attr(name):
    m = re.search(r'android:%s\(0x[0-9a-f]+\)=(true|false)' % name, app.split("E: activity")[0])
    return None if not m else m.group(1) == "true"
dbg = app_attr("debuggable")
say(dbg in (None, False), "not debuggable", "attribute absent" if dbg is None else str(dbg).lower())
say(app_attr("allowBackup") is False, "allowBackup is false", str(app_attr("allowBackup")))
say(app_attr("usesCleartextTraffic") is False, "usesCleartextTraffic is false", str(app_attr("usesCleartextTraffic")))
say("networkSecurityConfig" in app, "a network security config is declared")
# Resource shrinking shortens file paths (res/xml/backup_rules.xml becomes res/Qq.xml), so each file is found through the resource table.
table = aapt("dump", "resources", APK)
for r in ("network_security_config", "backup_rules", "data_extraction_rules"):
    m = re.search(r"resource 0x[0-9a-f]+ xml/%s\n\s+\(\) \(file\) (\S+) type=XML" % r, table)
    say(bool(m) and m.group(1) in names, f"xml/{r} is in the APK", m.group(1) if m else "not in the resource table")

# exported components: walk the application's children
exported = []
comp = None
for line in app.splitlines():
    m = re.match(r"\s+E: (activity|receiver|service|provider)\b", line)
    if m: comp = {"kind": m.group(1), "name": None, "exported": None, "permission": None}; exported.append(comp); continue
    if comp is not None:
        n = re.search(r'android:name\(0x01010003\)="([^"]+)"', line)
        if n and comp["name"] is None: comp["name"] = n.group(1)
        pm = re.search(r'android:permission\(0x01010006\)="([^"]+)"', line)
        if pm and comp["permission"] is None: comp["permission"] = pm.group(1)
        e = re.search(r'android:exported\(0x01010010\)=(true|false)', line)
        if e: comp["exported"] = e.group(1) == "true"
        if re.match(r"\s+E: (intent-filter|meta-data)", line) and comp["exported"] is None: pass
# The one library component that is exported: Compose's profile installer receiver, which the platform lets only a caller holding the signature-level
# DUMP permission (the shell, i.e. adb) reach. It is accepted only with that guard in place.
PROFILE = "androidx.profileinstaller.ProfileInstallReceiver"
got = sorted(c["name"].replace("io.github.tuthan.paddock", "") for c in exported if c["exported"] and not (c["name"] == PROFILE and c["permission"] == "android.permission.DUMP"))
WANT = sorted([".MainActivity", ".notify.UnifiedPushReceiver", ".widget.SummaryWidgetProvider", ".widget.CountWidgetProvider", ".widget.StripWidgetProvider"])
say(got == WANT, "exported components are the five named (and the profile installer's DUMP-guarded receiver)", ", ".join(got))
# Components from the billing, Play-services and datatransport libraries: none in foss; in play exactly the named six, all not exported.
LIB = ("com.android.billingclient.", "com.google.android.gms.", "com.google.android.datatransport.")
lib_components = sorted(c["name"] for c in exported if c["name"].startswith(LIB))
if PLAY:
    say(lib_components == PLAY_COMPONENTS and not any(c["exported"] for c in exported if c["name"].startswith(LIB)),
        "library components are exactly the six named, none exported", ", ".join(n.rsplit(".", 1)[1] for n in lib_components))
else:
    say(not lib_components, "no billing, Play-services or datatransport component", ", ".join(lib_components))
# <queries>: UnifiedPush's distributor lookup; play adds the two billing intents.
qm = re.search(r"\n(\s*)E: queries[^\n]*\n(.*?)(?=\n\1E: |\n\1?\s*E: application)", tree, re.S)
qactions = sorted(set(re.findall(r'android:name\(0x01010003\)="([^"]+)"', qm.group(2)))) if qm else []
WANT_Q = sorted(["org.unifiedpush.android.distributor.REGISTER"] + (PLAY_QUERY_ACTIONS if PLAY else []))
say(qactions == WANT_Q, "<queries> intents are exactly the named ones", ", ".join(qactions))
implicit = sorted(c["name"] for c in exported if c["exported"] is None)
say(not implicit, "every component says explicitly whether it is exported", ", ".join(implicit) or f"{len(exported)} components")

# ---- dex content ----
dex_names = [n for n in names if re.fullmatch(r"classes\d*\.dex", n)]
blob = b"".join(z.read(n) for n in dex_names)
DENY = [b"com/google/firebase", b"com/google/android/gms", b"io/sentry", b"com/crashlytics", b"io/fabric", b"com/bugsnag", b"com/facebook", b"com/adjust",
        b"com/amplitude", b"com/mixpanel", b"io/appcenter", b"com/microsoft/appcenter", b"com/segment", b"com/appsflyer", b"com/flurry", b"com/onesignal",
        b"com/google/android/play/core", b"com/android/billingclient", b"com/google/android/datatransport", b"androidx/work/", b"androidx/glance/",
        b"androidx/compose/ui/tooling", b"androidx/compose/ui/test"]
if PLAY:
    # Types the dex names under the three play prefixes, as packages; each must be on the named list. R8 renames library internals, so the list is the
    # packages that keep their names: the billing API classes, Play-services common and the datatransport entry points.
    descriptors = {m.decode() for m in re.findall(rb"L((?:com/android/billingclient|com/google/android/gms|com/google/android/datatransport)[A-Za-z0-9_/$]*);", blob)}
    packages = {d.rsplit("/", 1)[0] for d in descriptors}
    unlisted = sorted(packages - PLAY_DEX_PACKAGES)
    say(not unlisted and "com/android/billingclient/api" in packages, "dex packages under the billing, Play-services and datatransport prefixes are the named ones, billing is present",
        ", ".join(unlisted) or f"{len(packages)} packages")
    hits = [d.decode() for d in DENY if d in blob and not d.startswith(PLAY_PREFIXES)]
else:
    hits = [d.decode() for d in DENY if d in blob]
say(not hits, "no analytics, crash, ad, billing or tooling SDK in the dex" if not PLAY else "no analytics, crash, ad or tooling SDK in the dex, and no firebase package", ", ".join(hits) or f"{len(dex_names)} dex file(s) scanned")

# ---- host scripts ----
src = json.load(open(os.path.join(ROOT, "host/SOURCE.json")))["files"]
bad = []
for path, pin in sorted(src.items()):
    name = os.path.basename(path)
    asset = f"assets/{name}"
    if asset not in names: bad.append(f"{name} missing from assets"); continue
    want = pin.split(":", 1)[1]; have = hashlib.sha256(z.read(asset)).hexdigest()
    sib = "assets/" + (name.removesuffix(".py") + ".sha256" if name in ("paddock-relay.py", "paddock-control.py") else name + ".sha256")
    side = z.read(sib).decode().strip() if sib in names else None
    if have != want: bad.append(f"{name}: asset hash {have[:12]} != pin {want[:12]}")
    if side != want: bad.append(f"{name}: {sib} says {side and side[:12]}")
say(not bad, f"all {len(src)} host scripts match host/SOURCE.json and their .sha256 files", "; ".join(bad))

# ---- URLs ----
urls = set(re.findall(rb"https?://[A-Za-z0-9._~:/?#@!$&'()*+,;=%-]+", blob))
for n in names:
    if n.startswith("assets/") and not n.endswith((".ttf", ".png")): urls |= set(re.findall(rb"https?://[A-Za-z0-9._~:/?#@!$&'()*+,;=%-]+", z.read(n)))
hosts = {}
for u in urls:
    h = re.match(rb"https?://([^/:?#]+)", u).group(1).decode()
    hosts.setdefault(h, []).append(u.decode())
# Documentation links that libraries carry in their error messages (Tink, Kotlin, AndroidX), and this project's own example configuration: strings, not endpoints the app calls.
ALLOWED = {"developers.google.com", "cloud.google.com", "goo.gle", "r.android.com", "youtrack.jetbrains.com", "ntfy.example.org", "schemas.android.com", "www.w3.org", "www.apache.org", "scripts.sil.org", "openfontlicense.org", "github.com", "raw.githubusercontent.com", "tools.ietf.org", "www.ietf.org", "datatracker.ietf.org", "ntfy.sh", "unifiedpush.org", "developer.android.com", "www.unicode.org", "xmlpull.org", "www.xmlpull.org", "xml.org", "apache.org", "www.opensource.org", "opensource.org", "example.com", "localhost", "127.0.0.1", "www.android.com", "android.com", "creativecommons.org", "www.gnu.org", "spdx.org", "www.eclipse.org", "kotlinlang.org"}
unknown = {h: u for h, u in hosts.items() if h not in ALLOWED}
say(not unknown, f"{len(urls)} URL strings in the dex and assets, all on the allow-list", "; ".join(f"{h}: {u[0]}" for h, u in sorted(unknown.items())))
lines.append("URL hosts seen: " + ", ".join(f"{h} ({len(u)})" for h, u in sorted(hosts.items())))

badging = aapt("dump", "badging", APK)
lines.append("badging: " + "; ".join(re.findall(r"(package: name='[^']+' versionCode='\d+' versionName='[^']+')", badging) + re.findall(r"(sdkVersion:'\d+'|targetSdkVersion:'\d+')", badging)))
lines.append("native libs: " + (", ".join(sorted({n.split('/')[1] + '/' + os.path.basename(n) for n in names if n.startswith('lib/')})) or "none"))
lines.append(f"apk size: {os.path.getsize(APK)} bytes; sha256 {hashlib.sha256(open(APK, 'rb').read()).hexdigest()}")
out = "\n".join(lines)
print(out)
if REPORT: open(REPORT, "w").write(out + "\n")
sys.exit(1 if failed else 0)
