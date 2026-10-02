#!/usr/bin/env bash
# Reproduce the herdr 0.9.1 subscriber-lag observations (docs/events-under-load.md). Only the disposable session
# paddock-test-evlost is created, used and stopped; `default` and `paddock-test` are never touched.
#   tools/probe-event-loss/reproduce.sh      (about 5 minutes; output under build/event-loss-<time>/)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
OUT="$REPO/build/event-loss-$(date +%Y%m%d-%H%M%S)"; mkdir -p "$OUT"; cd "$OUT"   # captures and logs land here, not in the repo
export PATH="$HERE:$PATH"
bash "$REPO/tools/setup-session.sh" paddock-test-evlost
# A1: silent gap. Stalled reader (10 s), 5000 status changes -> 285 of 5003 delivered, no marker, connection stays open.
python3 "$HERE/evlost.py" --subs status --stall 10 --burst-n 5000 --threads 1 --post-read 200 --idle 8 --out A1-status-5000-1thr-stall10
# A2b: EOF with no marker. Fully stalled reader, 800 pane split+close cycles -> server closes after ~114 events (~35 KB).
python3 "$HERE/evlost.py" --subs lifecycle --stall 30 --burst-kind split --burst-n 800 --threads 2 --post-read 60 --idle 5 --out A2b-lifecycle-rcvdefault
# A3: same EOF with the real CLI as publisher (herdr pane report-agent ... --seq N).
python3 "$HERE/evlost.py" --subs status --stall 45 --burst-via cli --burst-n 1500 --threads 4 --post-read 120 --idle 8 --out A3-status-cli1500-stall45
# A4: slow reader (128 B per 0.2 s) -> silent gaps, no close.
python3 "$HERE/evlost.py" --subs lifecycle --read slow --slow-bytes 128 --slow-sleep 0.2 --stall 60 --burst-kind split --burst-n 600 --threads 2 --out A4-lifecycle-slowreader-128B-per-0.2s
# A5: through host/paddock-relay.py with a stalled stdout consumer.
python3 "$HERE/relay_probe.py" --subs lifecycle --stall 40 --burst-kind split --burst-n 800 --out A5-relay-lifecycle-stall40
python3 "$HERE/analyze.py" A1-status-5000-1thr-stall10
herdr --session paddock-test-evlost server stop
