#!/usr/bin/env python3
"""AC-08.4, whether the hook delays the desktop's own dialog: a real Claude Code session in the disposable herdr session, asked to run
one harmless `touch`, timed from the Enter that submits the prompt to the permission dialog being on the pane's screen.

    python3 tools/measure-hook-desktop-delay.py [--samples 6] [--out DIR]

Three conditions, interleaved so drift in the model's own latency falls on all of them: `none` (no hook registered), `window 0`
(registered, does nothing) and `window 60` (registered, publishes the request and waits; nobody answers until the dialog is seen).
The model's time to produce the tool call varies by a second or more from run to run, so the table gives min, median and max rather
than a verdict on tens of milliseconds; the scenario suite's `the desktop dialog is showing while the hook waits` is the structural
check. Only `paddock-test` is touched; screen text is never written to the log."""
import argparse, importlib.util, json, os, statistics, sys, time, shutil

import harness
HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("scenarios", os.path.join(HERE, "run-hook-scenarios.py"))
scen = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scen)


def one(h, condition, n):
    if condition == "none":
        h.unconfigure()
    else:
        h.configure(0 if condition == "window 0" else 60)
    h.open()
    try:
        (marker,) = h.ask("delay-%s-%d.txt" % (condition.replace(" ", ""), n))
        t0 = time.time()
        seen = h.wait(h.dialog, 40, step=0.05)
        dialog_s = time.time() - t0 if seen else None
        blocked_s = (time.time() - t0) if h.wait(lambda: h.status() == "blocked", 10, step=0.05) else None
        if seen:
            h.desktop_yes()
            h.wait_file(marker, 20)
        return dialog_s, blocked_s
    finally:
        h.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--samples", type=int, default=6)
    ap.add_argument("--out", default=os.path.join(harness.OUT_BASE, "hook-delay"))
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    h = scen.Harness(args.out, "haiku")
    conditions = ["none", "window 0", "window 60"]
    samples = {c: [] for c in conditions}
    try:
        for n in range(1, args.samples + 1):
            for c in conditions:
                d, b = one(h, c, n)
                samples[c].append({"dialog_s": None if d is None else round(d, 3), "blocked_s": None if b is None else round(b, 3)})
                print("run %d %-10s dialog %s s, blocked %s s" % (n, c, samples[c][-1]["dialog_s"], samples[c][-1]["blocked_s"]), flush=True)
    finally:
        h.close(); h.unconfigure(); shutil.rmtree(h.markers, ignore_errors=True)
    print("\n%-10s %3s %8s %8s %8s   (seconds from submit to the dialog on screen)" % ("condition", "n", "min", "median", "max"))
    table = {}
    for c in conditions:
        v = sorted(s["dialog_s"] for s in samples[c] if s["dialog_s"] is not None)
        table[c] = {"n": len(v), "missed": len(samples[c]) - len(v), "min": v[0] if v else None, "median": round(statistics.median(v), 3) if v else None, "max": v[-1] if v else None}
        print("%-10s %3d %8s %8s %8s   missed %d" % (c, len(v), table[c]["min"], table[c]["median"], table[c]["max"], table[c]["missed"]))
    with open(os.path.join(args.out, "hook-delay.json"), "w") as f:
        json.dump({"date": time.strftime("%Y-%m-%d"), "table": table, "samples": samples}, f, indent=1)
    return 0


if __name__ == "__main__":
    sys.exit(main())
