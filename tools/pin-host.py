#!/usr/bin/env python3
"""Rewrite the file hashes in host/SOURCE.json from the files in host/. A deliberate pin update: changing a host script bumps its VERSION
constant and its `*_version` key by hand, then this records the new hash; the app bundles only what the pin says (app/build.gradle.kts)
and HostScriptPinTest fails on any difference.

    tools/pin-host.py            writes host/SOURCE.json
    tools/pin-host.py --check    exits 1 if it would change
"""
import hashlib, json, os, sys

root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
host = os.path.join(root, "host")
path = os.path.join(host, "SOURCE.json")
data = json.load(open(path))
files = {}
for name in sorted(os.listdir(host)):
    if name == "SOURCE.json" or not os.path.isfile(os.path.join(host, name)):
        continue
    files["host/" + name] = "sha256:" + hashlib.sha256(open(os.path.join(host, name), "rb").read()).hexdigest()
if data["files"] == files:
    print("host/SOURCE.json is current")
    sys.exit(0)
if "--check" in sys.argv:
    print("host/SOURCE.json differs from the files in host/", file=sys.stderr)
    sys.exit(1)
data["files"] = files
open(path, "w").write(json.dumps(data, indent=2) + "\n")
print("wrote host/SOURCE.json (%d files)" % len(files))
