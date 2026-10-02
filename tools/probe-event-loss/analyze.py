#!/usr/bin/env python3
"""analyze.py PREFIX: classify every line of PREFIX.raw (+ .post.raw); report delivered seqs, gaps, non-event lines."""
import json, re, sys
pre = sys.argv[1]
seqs = []; other = []; names = {}
for suffix in (".raw", ".post.raw"):
    try: data = open(pre + suffix, "rb").read()
    except FileNotFoundError: continue
    for ln in data.split(b"\n"):
        if not ln: continue
        try: j = json.loads(ln)
        except Exception: other.append((suffix, ln[:200])); continue
        ev = j.get("event"); names[ev if ev else "<no event key: %s>" % list(j)[:3]] = names.get(ev if ev else "<no event key: %s>" % list(j)[:3], 0) + 1
        a = (j.get("data") or {}).get("agent") if isinstance(j.get("data"), dict) else None
        if ev == "pane.agent_status_changed" and a and re.fullmatch(r"e\d+", a):
            seqs.append((int(a[1:]), suffix))
        elif ev != "pane.agent_status_changed" and ev not in (None,) and "_" in ev or (ev and "." in ev and ev != "pane.agent_status_changed"):
            pass
        if ev is None: other.append((suffix, ln[:300]))
print("event names:", names)
print("non-event / unparsable lines:", other[:10], "count", len(other))
if seqs:
    first = [s for s, suf in seqs if suf == ".raw"]
    allv = [s for s, _ in seqs]
    uniq = sorted(set(allv))
    print("status events with seq label: %d (unique %d), in .raw %d" % (len(allv), len(uniq), len(first)))
    print("seq range delivered: %d..%d" % (uniq[0], uniq[-1]))
    missing = [x for x in range(uniq[0], uniq[-1] + 1) if x not in set(uniq)]
    print("missing inside range: %d" % len(missing))
    # contiguous runs of delivered seqs, in arrival order
    runs = []; start = prev = None
    for s in allv:
        if prev is not None and s == prev + 1: prev = s; continue
        if start is not None: runs.append((start, prev))
        start = prev = s
    runs.append((start, prev))
    print("arrival-order contiguous runs (first 12 of %d):" % len(runs), runs[:12])
    print("first 5 delivered:", allv[:5], "last 5:", allv[-5:])
