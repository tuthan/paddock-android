#!/usr/bin/env python3
"""Checks the store listing: the text lengths, the image sizes, and that no old hostname is in any listing text, image filename or image metadata.

  tools/check-listing.py [--strict] [repo-root]
  tools/check-listing.py --self-test

Two text folders, because the two stores read different text (docs/release.md, "Play listing"): fastlane/metadata/android/en-US/ is F-Droid's source
and is not edited for Play; play-listing/en-US/ is the Google Play text. Both hold title.txt, short_description.txt and full_description.txt.
  - title.txt at most 30 characters, short_description.txt at most 80 characters, full_description.txt at most 4000 bytes (UTF-8; a bullet is three);
  - the Play full description differs from F-Droid's and names none of the other channels (F-Droid, IzzyOnDroid, GitHub: decision P2).
Images, found under fastlane/.../images/ and, if it exists, play-listing/en-US/images/ (the plan keeps them under fastlane, where F-Droid also reads them):
  - icon.png exactly 512x512 with no alpha (colour type 4 or 6, or a tRNS chunk);
  - featureGraphic.png exactly 1024x500;
  - phoneScreenshots/*.png, 2 to 8 files, each side 320 to 3840 px, the long side at most twice the short one.
Only the standard library is used: the PNG header is read here. The feature graphic and the screenshots do not exist yet (they are made from the demo
host, Phase 16 slice 3, never from a real machine); an absent one is reported as "missing, to be made from the demo host" and fails only with --strict.
A file that is there and wrong always fails.
Old hostnames: set PADDOCK_OLD_NAMES to the names, comma separated (the two names are in the vault's Phase 16 note; they are never written in this
repository). Unset, the check is skipped and says so. Matching is case-insensitive.
Exit status 1 if anything FAILs, or with --strict if anything is missing.
"""
import os, struct, sys, tempfile, zlib

FDROID = "fastlane/metadata/android/en-US"
PLAY = "play-listing/en-US"
TEXTS = (("title.txt", "characters", 30), ("short_description.txt", "characters", 80), ("full_description.txt", "bytes", 4000))
CHANNELS = ("F-Droid", "IzzyOnDroid", "GitHub")
TO_MAKE = "missing, to be made from the demo host"

def png_info(path):
    """(width, height, colour type, has tRNS) from the PNG's own chunks; ValueError if the file is not a PNG."""
    with open(path, "rb") as f: data = f.read()
    if data[:8] != b"\x89PNG\r\n\x1a\n": raise ValueError("not a PNG (bad signature)")
    pos, info, trns, first = 8, None, False, True
    while pos + 8 <= len(data):
        length, kind = struct.unpack(">I4s", data[pos:pos + 8]); body = data[pos + 8:pos + 8 + length]
        if len(body) < length: raise ValueError("truncated")
        if first and kind != b"IHDR": raise ValueError("the first chunk is not IHDR")
        first = False
        if kind == b"IHDR":
            if length != 13: raise ValueError("bad IHDR")
            w, h, _depth, ctype = struct.unpack(">IIBB", body[:10]); info = (w, h, ctype)
        elif kind == b"tRNS": trns = True
        elif kind in (b"IDAT", b"IEND"): break
        pos += 12 + length
    if info is None: raise ValueError("no IHDR")
    return info + (trns,)

def check(root, old_names, strict=False):
    """(lines, exit status). Each line starts PASS, FAIL, MISSING or NOTE."""
    lines = []; failed = missing = 0
    def out(kind, msg):
        nonlocal failed, missing
        lines.append(f"{kind:<7} {msg}"); failed += kind == "FAIL"; missing += kind == "MISSING"
    def read_text(folder, name):
        path = os.path.join(root, folder, name)
        if not os.path.isfile(path): out("FAIL", f"{folder}/{name}: absent"); return None
        try: text = open(path, encoding="utf-8").read()
        except UnicodeDecodeError: out("FAIL", f"{folder}/{name}: not UTF-8"); return None
        return text[:-1] if text.endswith("\n") else text

    full = {}
    for label, folder in (("F-Droid", FDROID), ("Play", PLAY)):
        for name, unit, limit in TEXTS:
            text = read_text(folder, name)
            if text is None: continue
            n = len(text) if unit == "characters" else len(text.encode("utf-8"))
            out("PASS" if 0 < n <= limit else "FAIL", f"{label} {name}: {n} {unit} (limit {limit})" + ("" if n else ", empty"))
            if unit == "characters" and "\n" in text: out("FAIL", f"{label} {name}: more than one line")
            if name == "full_description.txt": full[label] = text
    if len(full) == 2:
        out("PASS" if full["Play"] != full["F-Droid"] else "FAIL", "the Play full description differs from F-Droid's (P2)" if full["Play"] != full["F-Droid"] else "the Play full description is F-Droid's, unchanged (P2: the Play text is its own)")
        named = [c for c in CHANNELS if c.lower() in full["Play"].lower()]
        out("FAIL" if named else "PASS", f"the Play full description names another channel: {', '.join(named)} (P2)" if named else "the Play full description names no other channel (P2)")

    dirs = [d for d in (os.path.join(FDROID, "images"), os.path.join(PLAY, "images")) if os.path.isdir(os.path.join(root, d))]
    def size_of(path):
        try: return png_info(os.path.join(root, path))
        except (ValueError, OSError, struct.error) as e: out("FAIL", f"{path}: {e}"); return None
    def find(name): return [os.path.join(d, name) for d in dirs if os.path.isfile(os.path.join(root, d, name))]
    icons = find("icon.png")
    if not icons: out("FAIL", "icon.png: absent (512x512, no alpha)")
    for p in icons:
        i = size_of(p)
        if i: w, h, ct, trns = i; ok = (w, h) == (512, 512) and ct not in (4, 6) and not trns; out("PASS" if ok else "FAIL", f"{p}: {w}x{h}{', with alpha' if ct in (4, 6) or trns else ''} (512x512, no alpha)")
    feats = find("featureGraphic.png")
    if not feats: out("MISSING", f"featureGraphic.png: {TO_MAKE} (1024x500)")
    for p in feats:
        i = size_of(p)
        if i: out("PASS" if i[:2] == (1024, 500) else "FAIL", f"{p}: {i[0]}x{i[1]} (exactly 1024x500)")
    shot_dirs = [d for d in (os.path.join(x, "phoneScreenshots") for x in dirs) if os.path.isdir(os.path.join(root, d))]
    files = {d: sorted(f for f in os.listdir(os.path.join(root, d)) if not f.startswith(".")) for d in shot_dirs}
    if not any(files.values()): out("MISSING", f"phoneScreenshots/: {TO_MAKE} (2 to 8 PNG files)")
    for d, names in files.items():
        if not names: continue
        out("PASS" if 2 <= len(names) <= 8 else "FAIL", f"{d}: {len(names)} files (2 to 8)")
        for f in names:
            i = size_of(os.path.join(d, f)) if f.lower().endswith(".png") else out("FAIL", f"{d}/{f}: not a .png file")
            if not i: continue
            w, h = i[:2]; ok = 320 <= min(w, h) and max(w, h) <= 3840 and max(w, h) <= 2 * min(w, h)
            out("PASS" if ok else "FAIL", f"{d}/{f}: {w}x{h} (each side 320 to 3840, long side at most twice the short)")

    names = [n.strip().lower() for n in (old_names or "").split(",") if n.strip()]
    if not old_names: out("NOTE", "hostname check skipped: PADDOCK_OLD_NAMES is not set (the two names are in the vault's Phase 16 note)")
    elif not names: out("FAIL", "PADDOCK_OLD_NAMES is set but holds no name")
    elif any(len(n) < 4 for n in names): out("FAIL", "PADDOCK_OLD_NAMES holds a name under four characters; it would match far too much")
    else:
        hits = []
        for folder in (FDROID, PLAY):
            for dirpath, _dirs, fnames in os.walk(os.path.join(root, folder)):
                for f in fnames:
                    path = os.path.join(dirpath, f); rel = os.path.relpath(path, root)
                    data = open(path, "rb").read().lower()   # text, or an image's raw bytes (its metadata chunks are not compressed)
                    for n in names:
                        if n.encode() in rel.lower().encode(): hits.append(f"{rel}: a name in the file's path")
                        if n.encode() in data: hits.append(f"{rel}: a name in the file's content")
        for h in sorted(set(hits)): out("FAIL", f"old hostname in {h}")
        if not hits: out("PASS", f"no old hostname ({len(names)} names) in any listing text, image name or image metadata")
    status = 1 if failed or (strict and missing) else 0
    lines.append(f"{failed} failed, {missing} missing" + ("" if not missing else (" (failing: --strict)" if strict else " (not failing; --strict makes them fail)")))
    return lines, status

def self_test():
    bad = []
    def t(name, ok, detail=""):
        print(("PASS " if ok else "FAIL ") + name + (f"\n{detail}" if detail and not ok else "")); ok or bad.append(name)
    def chunk(kind, body): return struct.pack(">I", len(body)) + kind + body + struct.pack(">I", zlib.crc32(kind + body))
    def png(w, h, ctype=2, trns=False):
        return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, ctype, 0, 0, 0)) + (chunk(b"tRNS", b"\0\0") if trns else b"") + chunk(b"IDAT", zlib.compress(b"\0")) + chunk(b"IEND", b"")
    def put(root, rel, data):
        p = os.path.join(root, rel); os.makedirs(os.path.dirname(p), exist_ok=True)
        open(p, "wb" if isinstance(data, bytes) else "w", **({} if isinstance(data, bytes) else {"encoding": "utf-8"})).write(data)
    def tree(root, **o):
        """A listing that passes, with images only when asked; o overrides a file's content by key (fd_title, play_full, ...)."""
        files = {"fd_title": ("title.txt", "Paddock\n"), "fd_short": ("short_description.txt", "Watch agents.\n"), "fd_full": ("full_description.txt", "Free and Pro. Also on F-Droid.\n")}
        for k, (name, text) in files.items(): put(root, f"{FDROID}/{name}", o.get(k, text))
        put(root, f"{PLAY}/title.txt", o.get("play_title", "Paddock\n")); put(root, f"{PLAY}/short_description.txt", o.get("play_short", "Watch agents.\n")); put(root, f"{PLAY}/full_description.txt", o.get("play_full", "Free and Pro. Try a demo.\n"))
        put(root, f"{FDROID}/images/icon.png", o.get("icon", png(512, 512)))
    def run(strict=False, names="", **o):
        with tempfile.TemporaryDirectory() as r:
            tree(r, **{k: v for k, v in o.items() if k not in ("extra",)})
            for rel, data in o.get("extra", {}).items(): put(r, rel, data)
            return check(r, names, strict)
    def kinds(lines, kind): return [l for l in lines if l.startswith(kind)]
    lines, st = run(); t("a good listing without the feature graphic and screenshots passes, and says they are to be made from the demo host",
        st == 0 and len(kinds(lines, "FAIL")) == 0 and sum(TO_MAKE in l for l in lines) == 2 and any("0 failed, 2 missing" in l for l in lines), "\n".join(lines))
    lines, st = run(strict=True); t("--strict fails on the missing images", st == 1)
    t("PADDOCK_OLD_NAMES unset is skipped with a note", any(l.startswith("NOTE") and "PADDOCK_OLD_NAMES" in l for l in lines))
    ok_img = {f"{FDROID}/images/featureGraphic.png": png(1024, 500), f"{FDROID}/images/phoneScreenshots/1.png": png(1080, 1920), f"{FDROID}/images/phoneScreenshots/2.png": png(1080, 1920)}
    lines, st = run(strict=True, extra=ok_img); t("with the feature graphic and two 9:16 screenshots --strict passes", st == 0 and not kinds(lines, "MISSING"), "\n".join(lines))
    for label, over in (("a 31 character title", dict(fd_title="x" * 31)), ("an 81 character short description", dict(play_short="x" * 81)), ("an empty Play title", dict(play_title="\n")),
                        ("a 4001 byte full description", dict(fd_full="x" * 4001)), ("2000 bullets, which are 6000 bytes", dict(play_full="•" * 2000)), ("a two line Play title", dict(play_title="a\nb\n"))):
        lines, st = run(**over); t(f"{label} fails", st == 1 and len(kinds(lines, "FAIL")) >= 1, "\n".join(lines))
    lines, st = run(fd_title="x" * 30, play_short="x" * 80, fd_full="x" * 4000); t("30, 80 and 4000 are inside the limits", st == 0, "\n".join(lines))
    lines, st = run(play_full="Free and Pro. Also on F-Droid.\n"); t("a Play description equal to F-Droid's fails (P2)", st == 1 and any("unchanged" in l for l in lines))
    lines, st = run(play_full="Get it on IzzyOnDroid.\n"); t("a Play description that names another channel fails (P2)", st == 1 and any("names another channel: IzzyOnDroid" in l for l in lines))
    lines, st = run(icon=png(512, 512, 6)); t("an icon with alpha fails", st == 1 and any("with alpha" in l for l in lines))
    lines, st = run(icon=png(512, 512, 2, trns=True)); t("an icon with a tRNS chunk fails", st == 1)
    lines, st = run(icon=png(511, 512)); t("an icon that is 511x512 fails", st == 1)
    lines, st = run(icon=png(512, 512, 0)); t("a grey icon without alpha passes", st == 0)
    lines, st = run(extra={f"{FDROID}/images/featureGraphic.png": png(1024, 501)}); t("a feature graphic of 1024x501 fails even without --strict", st == 1)
    shots = lambda *sizes: {f"{FDROID}/images/phoneScreenshots/{i}.png": png(*s) for i, s in enumerate(sizes)}
    lines, st = run(extra=shots((1080, 1920))); t("one screenshot fails (two are needed)", st == 1 and any("1 files (2 to 8)" in l for l in lines))
    lines, st = run(extra=shots(*[(1080, 1920)] * 9)); t("nine screenshots fail", st == 1)
    lines, st = run(extra=shots(*[(1080, 1920)] * 8)); t("eight screenshots pass", st == 0)
    lines, st = run(extra=shots((320, 640), (3840, 1920))); t("320x640 and 3840x1920 are on the limits and pass", st == 0, "\n".join(lines))
    for s in ((319, 640), (400, 3841), (3841, 1920), (1000, 2001)):
        lines, st = run(extra=shots((1080, 1920), s)); t(f"a screenshot of {s[0]}x{s[1]} fails", st == 1)
    lines, st = run(extra={f"{FDROID}/images/phoneScreenshots/1.png": b"not a png", f"{FDROID}/images/phoneScreenshots/2.png": png(1080, 1920)}); t("a file that is not a PNG fails", st == 1 and any("not a PNG" in l for l in lines))
    lines, st = run(extra={f"{FDROID}/images/phoneScreenshots/1.jpg": b"\xff\xd8", f"{FDROID}/images/phoneScreenshots/2.png": png(1080, 1920)}); t("a JPEG fails (the check reads PNG only)", st == 1)
    lines, st = run(names="devbox-old", extra={f"{PLAY}/images/phoneScreenshots/DEVBOX-OLD-1.png": png(1080, 1920)}); t("an old name in an image filename fails, whatever the case", st == 1 and any("path" in l for l in lines), "\n".join(lines))
    lines, st = run(names="ghost1,devbox-old", play_full="Connect to devbox-old over SSH.\n"); t("an old name in listing text fails", st == 1 and any("content" in l for l in lines))
    lines, st = run(names="ghost1,devbox-old", extra={f"{FDROID}/images/featureGraphic.png": png(1024, 500) + chunk(b"tEXt", b"Comment\0from DevBox-Old")}); t("an old name in an image's metadata fails", st == 1)
    lines, st = run(names="ghost1,devbox-old"); t("with names set and none present the check passes and counts them", st == 0 and any("no old hostname (2 names)" in l for l in lines), "\n".join(lines))
    lines, st = run(names=",,"); t("a names list of only commas is not a silent pass", st == 1 and any("holds no name" in l for l in lines))
    lines, st = run(names="ab"); t("a very short name is refused rather than matching everything", st == 1)
    with tempfile.TemporaryDirectory() as r:
        put(r, f"{FDROID}/title.txt", "Paddock\n"); lines, st = check(r, ""); t("a missing Play folder and missing texts fail", st == 1 and sum(l.startswith("FAIL") for l in lines) >= 5, "\n".join(lines))
    print(f"\n{'all passed' if not bad else str(len(bad)) + ' FAILED'}"); return 1 if bad else 0

if __name__ == "__main__":
    args = sys.argv[1:]
    if args == ["--self-test"]: sys.exit(self_test())
    strict = "--strict" in args; args = [a for a in args if a != "--strict"]
    if len(args) > 1 or any(a.startswith("--") for a in args): sys.exit("usage: check-listing.py [--strict] [repo-root] | --self-test")
    root = os.path.abspath(args[0]) if args else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    lines, status = check(root, os.environ.get("PADDOCK_OLD_NAMES", ""), strict)
    print("\n".join(lines)); sys.exit(status)
