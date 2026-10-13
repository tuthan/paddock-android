#!/usr/bin/env python3
"""Synthesises the demo video's soundtrack and puts it on the recording.

    tools/make-demo-soundtrack.py --video demo-silent.mp4 --out demo.mp4 [--events tools/demo-soundtrack.json] [--wav soundtrack.wav]
    tools/make-demo-soundtrack.py --probe demo.mp4         list the screen changes of a recording (to write the events file for it)

The sound is in the voice of the product page's 45-second reel (site/assets/audio.js of the plugin repository): detuned pads through a low-pass shape,
soft plucks, a chirp and a tick for a tap, key clicks, a long reverb, and the same chords (F, G, A, E). Everything is made here from numbers: no sample, no
recording and no third-party sound, so there is nothing to license, and the same events file gives the same sound (the random parts are seeded). The harmony
follows the story: calm while the herd works, an E minor colour and a chime when an agent blocks, G while its question is read, a lift when the prompt is
sent, and F again with an ascending chime when the answer arrives.

Needs Python 3 and ffmpeg and nothing else (no package): the short sounds and the reverb are computed here with the standard library, the pad is made by
ffmpeg from a sum of sines, and ffmpeg also filters, measures the loudness (about -18 LUFS, ceiling -2 dBFS) and joins the sound to the picture. It takes about
a minute. The events (when each tap, the alert, the typing, the send and the answer happen in the video) are in tools/demo-soundtrack.json: they were
measured from the published take, and a new take needs new times (--probe prints its screen changes to read them from).
"""
import argparse, array, json, math, os, random, re, subprocess, sys, tempfile, wave

SR = 44100
TAU = 2 * math.pi
TARGET_LUFS = -18.0
CEILING = 0.79                                       # -2 dBFS: the true peak (between samples) and the AAC encode stay under 0 dBFS
CHORDS = {"F": [53, 57, 60, 64], "G": [55, 59, 62, 64], "A": [57, 60, 64, 67], "E": [52, 55, 59, 62]}
PAN_CENTRE = math.cos(math.pi / 4)                   # a centred mono sound is this loud in each channel


def mtof(m): return 440.0 * 2.0 ** ((m - 69) / 12.0)


class Mix:
    """Four buses of floats: the dry sound and the reverb's send, left and right. Every sound is mono and placed with a pan."""

    def __init__(self, seconds):
        self.n = int(seconds * SR)
        self.dl, self.dr, self.sl, self.sr = ([0.0] * self.n for _ in range(4))

    def add(self, sig, t, gain=1.0, pan=0.0, rev=0.3):
        i = int(t * SR)
        if i < 0:
            sig, i = sig[-i:], 0
        n = min(len(sig), self.n - i)
        if n <= 0:
            return
        lg, rg = math.cos((pan + 1) * math.pi / 4) * gain, math.sin((pan + 1) * math.pi / 4) * gain
        dl, dr, sl, sr = self.dl, self.dr, self.sl, self.sr
        rl, rr = lg * rev, rg * rev
        for j in range(n):
            x, k = sig[j], i + j
            dl[k] += x * lg
            dr[k] += x * rg
            sl[k] += x * rl
            sr[k] += x * rr


# --- the short sounds: lists of floats, mono -----------------------------------------------------------------------------------

def pluck(note, dec=0.6, v=1.0, kind="triangle"):
    """A triangle (or sine) with a quiet octave over it: a 4 ms attack and an exponential decay to -60 dB over [dec] seconds."""
    f = mtof(note)
    n, att = int((dec + 0.05) * SR), max(1, int(0.004 * SR))
    r1, r2 = math.exp(-6.9 / (dec * SR)), math.exp(-6.9 / (dec * 0.5 * SR))
    e1 = e2 = 1.0
    w = f / SR
    out = [0.0] * n
    for j in range(n):
        a = j / att if j < att else 1.0
        if j >= att:
            e1 *= r1
            e2 *= r2
        ph = (w * j) % 1.0
        body = 1.0 - 4.0 * abs(((ph + 0.25) % 1.0) - 0.5) if kind == "triangle" else math.sin(TAU * ph)
        out[j] = (body * e1 + 0.3 * math.sin(TAU * 2 * ph) * e2) * a * v
    return out


def tick(rnd, dec=0.016, v=1.0, double=False):
    """A burst of differenced noise, which is bright: the click of a hat or a fingertip (differenced twice, brighter still)."""
    n = int((dec + 0.02) * SR)
    r = math.exp(-6.9 / (dec * SR))
    p1 = p2 = 0.0
    e = 1.0
    out = [0.0] * n
    for j in range(n):
        g = rnd.gauss(0, 1)
        d = g - p1
        p1 = g
        if double:
            d, p2 = d - p2, d
        out[j] = d * e * (j / 44.0 if j < 44 else 1.0) * v * 0.6
        e *= r
    return out


def tap_sound(rnd, v=1.0):
    """A sine chirp from 2.1 kHz down to 1.2 kHz in 30 ms with a tick on it: the reel's tap."""
    n = int(0.07 * SR)
    r = math.exp(-6.9 / (0.045 * SR))
    ph = 0.0
    e = 1.0
    out = [0.0] * n
    for j in range(n):
        f = 2100.0 * (1200.0 / 2100.0) ** min(j / SR / 0.03, 1.0)
        ph += f / SR
        out[j] = math.sin(TAU * ph) * e * 0.9 * (j / 44.0 if j < 44 else 1.0) * v
        e *= r
    for j, x in enumerate(tick(rnd, 0.02, 0.5)[:n]):
        out[j] += x * v
    return out


def key_click(rnd, v=1.0):
    """Noise narrowed to a band (a short moving average minus a long one) plus a short thump: a key."""
    n = 2646
    pre = [0.0]
    for _ in range(n + 24):
        pre.append(pre[-1] + rnd.gauss(0, 1))
    f = 640 + rnd.uniform(-60, 60)
    r1, r2 = math.exp(-6.9 / (0.03 * SR)), math.exp(-6.9 / (0.02 * SR))
    e1 = e2 = 1.0
    out = [0.0] * n
    for j in range(n):
        band = (pre[j + 8] - pre[j]) / 8 - (pre[j + 24] - pre[j]) / 24
        a = j / 44.0 if j < 44 else 1.0
        out[j] = (band * 3.2 * e1 + math.sin(TAU * f * j / SR) * 0.45 * e2) * a * v
        e1 *= r1
        e2 *= r2
    return out


def whoosh(rnd, dur, f0, f1, v=1.0):
    """Noise through a resonant band-pass whose centre sweeps from f0 to f1: a state-variable filter, one sample at a time."""
    n = int(dur * SR)
    low = band = 0.0
    out = [0.0] * n
    q = 0.55
    for j in range(n):
        f = 2 * math.sin(math.pi * f0 * (f1 / f0) ** (j / n) / SR)
        low += f * band
        high = rnd.gauss(0, 1) - low - q * band
        band += f * high
        out[j] = band * math.sin(math.pi * j / n) ** 1.6 * v * 0.35
    return out


def blip(dur, v=1.0):
    n = int(dur * SR)
    ph = 0.0
    out = [0.0] * n
    for j in range(n):
        t = j / SR
        ph += 520.0 * (1900.0 / 520.0) ** (t / dur) / SR
        out[j] = math.sin(TAU * ph) * min(t / 0.05, 1.0) * max(1 - t / dur, 0.0) * v
    return out


# --- the pad, made by ffmpeg ---------------------------------------------------------------------------------------------------

def pad_expr(notes, seed):
    """Detuned saws (each note twice, 8 cents apart, at random phases) with a slow swell: one ffmpeg expression of t.
    The low-pass is a filter after it, as in a synth; a sawtooth is pi/2 of the sum of its harmonics at 1/k."""
    rnd = random.Random(seed)
    voices = []
    for note in notes:
        for cents in (-8, 8):
            voices.append("(2*mod(%.4f*t+%.4f,1)-1)" % (mtof(note) * 2 ** (cents / 1200), rnd.uniform(0, 1)))
    scale = 0.10 * 2.2 / (len(notes) * 2) * math.pi / 2
    return "%.5f*(1+0.12*sin(%.5f*t+%d))*(%s)" % (scale, TAU * 0.17, seed, "+".join(voices))


def render_pad(ev, path):
    """One input per chord (swelling in over 1.4 s, out over 2.4 s, each starting 0.4 s before its time), mixed into one mono wav."""
    chords = ev["chords"]
    cmd = ["ffmpeg", "-v", "error", "-y"]
    chains = []
    for i, (t0, name) in enumerate(chords):
        t1 = chords[i + 1][0] if i + 1 < len(chords) else ev["fade_out_from"]
        hold = t1 - t0 + 0.4
        cmd += ["-f", "lavfi", "-i", "aevalsrc=exprs='%s':s=%d:d=%.3f" % (pad_expr(CHORDS[name], i), SR, hold + 2.4)]
        start = max(t0 - 0.4, 0.0) if i else 0.0
        # two one-pole low-passes at 1.5 kHz in series: the weight 1 / (1 + (f / 1500)^2) of each harmonic
        chains.append("[%d:a]lowpass=f=1500:p=1,lowpass=f=1500:p=1,afade=t=in:st=0:d=1.4,afade=t=out:st=%.3f:d=2.4,adelay=%d:all=1[p%d]" % (i, hold, int(start * 1000), i))
    graph = (";".join(chains) + ";" + "".join("[p%d]" % i for i in range(len(chords))) +
             "amix=inputs=%d:normalize=0:duration=longest[pad]" % len(chords))
    subprocess.run(cmd + ["-filter_complex", graph, "-map", "[pad]", "-ar", str(SR), "-ac", "1", path], check=True)


def read_wav_mono(path):
    with wave.open(path, "rb") as w:
        assert w.getsampwidth() == 2 and w.getnchannels() == 1 and w.getframerate() == SR, "unexpected pad format"
        data = array.array("h")
        data.frombytes(w.readframes(w.getnframes()))
    if sys.byteorder == "big":
        data.byteswap()
    return [x / 32768.0 for x in data]


# --- the reverb: a Freeverb (eight damped combs, then four all-passes), one per channel -----------------------------------------

COMBS = (1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
ALLPASSES = (556, 441, 341, 225)


def comb(x, delay, feedback, damp):
    buf = [0.0] * delay
    out = [0.0] * len(x)
    store, i, keep = 0.0, 0, 1.0 - damp
    for j, v in enumerate(x):
        y = buf[i]
        store = y * keep + store * damp
        buf[i] = v + store * feedback
        out[j] = y
        i += 1
        if i == delay:
            i = 0
    return out


def allpass(x, delay, g=0.5):
    buf = [0.0] * delay
    out = [0.0] * len(x)
    i = 0
    for j, v in enumerate(x):
        b = buf[i]
        out[j] = b - v
        buf[i] = v + b * g
        i += 1
        if i == delay:
            i = 0
    return out


def reverb(x, spread):
    """About three seconds of tail, darker as it fades; [spread] makes the two channels' delays differ, so the room is wide."""
    acc = [0.0] * len(x)
    for d in COMBS:
        acc = [a + b for a, b in zip(acc, comb(x, d + spread, 0.93, 0.25))]
    for d in ALLPASSES:
        acc = allpass(acc, d + spread)
    return acc


def energy(x): return sum(v * v for v in x)


# --- the score -------------------------------------------------------------------------------------------------------------

def build_buses(ev, pad):
    """Every sound placed in time on the dry bus and on the reverb's send."""
    dur = ev["duration"]
    m = Mix(dur)
    rnd = random.Random(5)
    chords = ev["chords"]

    # the bed: the pad, centred, and half of it into the reverb
    for i, x in enumerate(pad[:m.n]):
        m.dl[i] += x * PAN_CENTRE
        m.dr[i] += x * PAN_CENTRE
        m.sl[i] += x * PAN_CENTRE * 0.5
        m.sr[i] += x * PAN_CENTRE * 0.5

    # a sparse shimmer of plucks on the current chord's own notes: it keeps the bed moving without a beat
    t = 1.2
    while t < ev["fade_out_from"] - 0.5:
        name = [c for c in chords if c[0] <= t][-1][1]
        note = rnd.choice([n + 12 * o for n in CHORDS[name] for o in (1, 2)])
        if rnd.random() < 0.7:
            m.add(pluck(note, 0.9, 0.5), t, gain=0.085, pan=rnd.uniform(-0.6, 0.6), rev=0.55)
        t += rnd.choice([0.5, 0.75, 1.0])

    # the first screen: a rising arpeggio, as the reel opens
    for i, n in enumerate([69, 72, 76, 81]):
        m.add(pluck(n, 0.5, 0.6), 0.2 + i * 0.12, gain=0.08, pan=-0.3 + i * 0.2, rev=0.5)

    for t in ev["swipes"]:
        m.add(whoosh(rnd, 0.28, 700, 2200), t - 0.05, gain=0.7, rev=0.35)
    for t in ev["taps"]:
        m.add(tap_sound(rnd), t, gain=1.0, rev=0.12)
    ty = ev["typing"]
    for i in range(ty["keys"]):
        k = ty["from"] + (ty["to"] - ty["from"]) * i / max(ty["keys"] - 1, 1) + rnd.uniform(-0.04, 0.04)
        m.add(key_click(rnd), k, gain=0.85, pan=rnd.uniform(-0.15, 0.15), rev=0.05)

    # the alert: two bright plucks that rise (the bed turns to E minor at the same moment)
    a = ev["alert"]
    m.add(pluck(83, 1.2, 0.9, "sine"), a, gain=0.34, pan=-0.1, rev=0.6)
    m.add(pluck(90, 1.6, 0.9, "sine"), a + 0.16, gain=0.30, pan=0.1, rev=0.65)
    m.add(pluck(88, 1.2, 0.5, "sine"), a + 0.34, gain=0.16, rev=0.65)

    # the send: a sweep up and a blip
    s = ev["send"]
    m.add(whoosh(rnd, 0.5, 500, 3800), s + 0.05, gain=0.6, rev=0.4)
    m.add(blip(0.35), s + 0.1, gain=0.12, rev=0.4)

    # the agent at work: a quiet tick that stops when it is done
    w = ev["working"]
    x = w["from"]
    while x < w["to"]:
        m.add(tick(rnd, 0.02, 1.0, double=True), x, gain=0.22, pan=0.2, rev=0.1)
        x += 0.25

    # the answer: an ascending chime in F, over a low F
    d = ev["done"]
    for i, n in enumerate([72, 76, 79, 84, 88]):
        m.add(pluck(n, 1.6, 0.9, "sine"), d + i * 0.11, gain=0.30 - i * 0.02, pan=-0.4 + i * 0.2, rev=0.6)
    m.add(pluck(41, 2.4, 0.9, "sine"), d, gain=0.28, rev=0.2)
    return m


def write_stereo(path, left, right):
    with wave.open(path, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(SR)
        buf = array.array("h", [0]) * (2 * len(left))
        for i in range(len(left)):
            buf[2 * i] = max(-32768, min(32767, int(left[i] * 32767)))
            buf[2 * i + 1] = max(-32768, min(32767, int(right[i] * 32767)))
        if sys.byteorder == "big":
            buf.byteswap()
        w.writeframes(buf.tobytes())


def measure(path):
    """Integrated loudness in LUFS, from ffmpeg's ebur128."""
    r = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", path, "-af", "ebur128", "-f", "null", "-"], capture_output=True, text=True).stderr
    return float(re.findall(r"^\s*I:\s*(-?[0-9.]+) LUFS", r, re.M)[-1])


def build(ev, wav, work):
    dur = ev["duration"]
    pad_path = os.path.join(work, "pad.wav")
    print("pad (ffmpeg)...", file=sys.stderr)
    render_pad(ev, pad_path)
    print("sounds...", file=sys.stderr)
    m = build_buses(ev, read_wav_mono(pad_path))
    print("reverb...", file=sys.stderr)
    wl, wr = reverb(m.sl, 0), reverb(m.sr, 23)
    # the reverb comes out at 0.7 of the energy's square root that went in, whatever the network's own gain is
    g = 0.7 * math.sqrt((energy(m.sl) + energy(m.sr)) / max(energy(wl) + energy(wr), 1e-12))
    n = m.n
    fade_in, fade_from = int(0.3 * SR), ev["fade_out_from"]
    left, right = [0.0] * n, [0.0] * n
    for i in range(n):
        t = i / SR
        f = min(i / fade_in, 1.0) * min(max((dur - t) / (dur - fade_from), 0.0), 1.0) ** 1.5
        left[i] = (m.dl[i] + wl[i] * g) * f
        right[i] = (m.dr[i] + wr[i] * g) * f
    peak = max(max(map(abs, left)), max(map(abs, right)), 1e-9)
    k = 0.7 / peak                                   # headroom for the 16-bit file; the loudness is set below
    write_stereo(os.path.join(work, "premix.wav"), [x * k for x in left], [x * k for x in right])
    first = os.path.join(work, "first.wav")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", os.path.join(work, "premix.wav"), "-af", "highpass=f=70", "-ar", str(SR), "-ac", "2", first], check=True)
    gain = TARGET_LUFS - measure(first)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", first, "-af", "volume=%.3fdB,alimiter=limit=%.3f:attack=5:release=60:level=disabled" % (gain, CEILING),
                    "-ar", str(SR), "-ac", "2", wav], check=True)


def probe(video):
    """The screen changes of a recording: the mean change between frames at 15 per second, above a threshold, one per 0.4 s."""
    w, h, fps = 54, 108, 15
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", video, "-vf", "fps=%d,scale=%d:%d,format=gray" % (fps, w, h), "-f", "rawvideo", "-"],
                         capture_output=True, check=True).stdout
    size = w * h
    frames = len(raw) // size
    last = -1.0
    for i in range(1, frames):
        a, b = raw[(i - 1) * size:i * size], raw[i * size:(i + 1) * size]
        d = sum(abs(x - y) for x, y in zip(a, b)) / size
        t = i / fps
        if d > 1.6 and t - last > 0.4:
            print("%6.2f s   change %5.1f" % (t, d))
            last = t
    print("%.2f s long" % (frames / fps))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--events", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "demo-soundtrack.json"))
    ap.add_argument("--video"); ap.add_argument("--out"); ap.add_argument("--wav")
    ap.add_argument("--probe", metavar="VIDEO")
    a = ap.parse_args()
    if a.probe:
        return probe(a.probe)
    if not (a.video and a.out) and not a.wav:
        ap.error("--video and --out, or --wav, or --probe")
    ev = json.load(open(a.events))
    with tempfile.TemporaryDirectory() as work:
        wav = a.wav or os.path.join(work, "soundtrack.wav")
        build(ev, wav, work)
        if a.video and a.out:
            subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", a.video, "-i", wav, "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac", "-b:a", "112k",
                            "-ar", str(SR), "-shortest", "-movflags", "+faststart", a.out], check=True)
    print("ok", a.out or a.wav)


if __name__ == "__main__":
    main()
