#!/usr/bin/env python3
"""AC-08.4, the cost of the hook when it does nothing: wall time from spawn to exit, with no configuration, with `window_seconds = 0`
and, for scale, an interpreter that does nothing.

    python3 tools/measure-hook-overhead.py [--samples 20]

Each sample runs the shipped hook in a scratch XDG_CONFIG_HOME with herdr's environment for a pane and a real PermissionRequest on
stdin, exactly as Claude Code spawns it. Nothing touches herdr, ~/.claude or ~/.config/paddock. Prints a table and one JSON line."""
import argparse, json, os, statistics, subprocess, sys, tempfile, time

import harness
HOOK = os.path.join(harness.APP, "host", "paddock-claude-permission-hook.py")
REQUEST = json.dumps({"session_id": "overhead-1", "transcript_path": "/tmp/none", "cwd": "/tmp", "permission_mode": "default",
                      "hook_event_name": "PermissionRequest", "tool_name": "Bash",
                      "tool_input": {"command": "ls -la /tmp", "description": "list the temp directory"}}).encode()


def sample(argv, env, n):
    times = []
    for _ in range(n):
        t = time.perf_counter()
        subprocess.run(argv, input=REQUEST, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=30)
        times.append((time.perf_counter() - t) * 1000)
    return times


def row(name, times):
    s = sorted(times)
    return {"case": name, "n": len(s), "min_ms": round(s[0], 1), "median_ms": round(statistics.median(s), 1),
            "p95_ms": round(s[max(0, int(len(s) * 0.95) - 1)], 1), "max_ms": round(s[-1], 1)}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--samples", type=int, default=20)
    n = ap.parse_args().samples
    with tempfile.TemporaryDirectory() as scratch:
        env = {k: v for k, v in os.environ.items() if not k.startswith("HERDR_")}
        env.update({"HERDR_ENV": "1", "HERDR_PANE_ID": "w9:p9", "HERDR_SOCKET_PATH": os.path.join(scratch, "no-such.sock"),
                    "XDG_RUNTIME_DIR": scratch, "XDG_CONFIG_HOME": os.path.join(scratch, "cfg")})
        zero_cfg = os.path.join(scratch, "cfg", "paddock")
        rows = []
        # warm the page cache so the first sample is not a cold read
        sample([sys.executable, HOOK], env, 3)
        rows.append(row("python3 -c pass (interpreter alone)", sample([sys.executable, "-c", "pass"], env, n)))
        rows.append(row("hook, no configuration file", sample([sys.executable, HOOK], env, n)))
        os.makedirs(zero_cfg)
        path = os.path.join(zero_cfg, "hook.toml")
        with open(path, "w") as f:
            f.write("window_seconds = 0\n")
        os.chmod(path, 0o600)
        rows.append(row("hook, window_seconds = 0", sample([sys.executable, HOOK], env, n)))
        rows.append(row("hook, window_seconds = 0, again", sample([sys.executable, HOOK], env, n)))
    print("%-42s %5s %8s %8s %8s %8s" % ("case", "n", "min", "median", "p95", "max"))
    for r in rows:
        print("%-42s %5d %7.1f %8.1f %8.1f %8.1f   ms" % (r["case"], r["n"], r["min_ms"], r["median_ms"], r["p95_ms"], r["max_ms"]))
    base = rows[0]["median_ms"]
    over = {r["case"]: round(r["median_ms"] - base, 1) for r in rows[1:]}
    print("median above the bare interpreter:", json.dumps(over))
    print(json.dumps({"rows": rows, "median_over_interpreter_ms": over, "python": sys.version.split()[0], "limit_ms": 50}))
    worst = max(r["p95_ms"] for r in rows[2:])
    print("AC-08.4 hook with window zero: p95 %.1f ms (limit 50 ms; includes interpreter start) -> %s" % (worst, "within" if worst < 50 else "OVER"))
    return 0 if worst < 50 else 1


if __name__ == "__main__":
    sys.exit(main())
