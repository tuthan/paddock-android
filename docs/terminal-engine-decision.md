# Terminal engine decision

**Phase 05, AC-05.1.** Candidates, licences, Socket reviews and fixture results. **Owner of dependency dispositions:** Hung Vo. Reviews are in [dependency-reviews.md](dependency-reviews.md).

**Status: decided 2026-10-02 (slice 3).** An own engine in `:core`, no dependency. Candidates and reviews were recorded first (slice 1); the decision follows the fixture rendering below.

## What the engine has to render

The Phase 00 finding that decides the size of this work: a `terminal.frame` is not the PTY's byte stream. herdr renders its own screen state and sends it as a **cell patch**. Counting every escape sequence in the 21 records of the five fixtures, and checking the frames of live observers at 40x10, 80x24, 130x45 and 300x100 against a 120x40 and a 66x49 PTY, the only sequences present are:

| Sequence | Where | Count in the fixtures |
| --- | --- | --- |
| `CSI r;c H` (cursor position) | before every run of changed cells | 275 |
| `CSI ... m` (SGR: `0;39;49`, `1`, `2`, `38;5;n`, `49`) | before every run | 80 |
| `CSI ? 2026 h/l`, `CSI ? 25 h/l` (synchronized update, cursor visibility) | start and end of every frame | 105 |
| `OSC 8 ; ; ST` (an empty hyperlink, a reset) | start of every frame | 21 |
| `CSI 2 J` | first full frame | 5 |

Everything else is text. There is no scrolling, no erase-in-line, no insert or delete, no alternate-screen switch (`?1049` never appears: an application's alternate screen arrives as a repaint of the visible cells), and wide characters are placed by explicit column. The observer's own viewport is free-form: a 40x10 observer shows the top-left 40x10 cells of a larger PTY, and a 300x100 observer shows the PTY at the top-left of a larger blank frame, so the engine has to follow whatever size the frame says.

## Candidates

Each candidate needs a licence decision and a Socket review before it is tried (Phase 05 note).

| Candidate | Licence | Socket (2026-10-02) | Isolation and cost | Tried? |
| --- | --- | --- | --- | --- |
| ConnectBot `org.connectbot:termlib` 0.3.9 | Apache-2.0 (POM) | **404, no result** | A Compose component over libvterm: the AAR ships four native `libjni_cb_term.so` (2.6 to 3.6 MB each) that parse the host's bytes, plus runtime dependencies on `appcompat`, Material Components, `core-ktx` and the Compose artifacts. Android-only, so fixtures could only run as instrumentation tests | No. Needs a human disposition for a package Socket has no data on, and so do its Google Maven dependencies |
| Termux `terminal-emulator` | GPLv3-only repository; Apache-2.0 for the code taken from Terminal Emulator for Android, with Termux's changes under GPLv3 | Not reviewable (JitPack) | Native C through the NDK, published to JitPack only, which this repository's controls exclude (Google Maven and Maven Central). A GPLv3 decision for the whole app would be a prerequisite | No. Excluded by repository policy before the licence question |
| xterm.js (`@xterm/xterm` 6.0.0) bundled offline in a WebView | MIT | **Overall 91, no dependencies, low alerts only** (`minifiedFile`, `urlStrings`): allow_with_warning | A JavaScript engine, a WebView and a bridge for frame bytes and key events. A canvas or DOM view TalkBack cannot describe cell by cell, a minified asset that sits outside Gradle's verification metadata, and a frame path that crosses a process boundary on every update | No. Passes Socket; fails the isolation, accessibility and verification tests below |
| Own VT parser in `:core` | none | none (no dependency) | Pure Kotlin, JVM-testable, no native code and no third-party code reading the host's bytes. Sized by the table above | See the decision (slice 3) |

### Why the order in the note is not followed

The note lists an own parser last, "only if all three fail", and calls it a separate work stream. That sizing assumed raw PTY bytes. The frames are a cell patch of five sequence kinds (table above), so the own engine is a few hundred lines of Kotlin, and the three candidates are weighed against that, not against a full VT100/xterm emulator. Termux is excluded before it can be tried (repository policy), termlib cannot be tried without a disposition for a package Socket cannot see, and xterm.js can be tried but fails on isolation and accessibility, not on Socket. Recorded here so the deviation from the note's order is visible and reversible: the engine sits behind the `TerminalEngine` port, so a later phase can swap an adapter without touching the decoder, the bridge or the screen.

### xterm.js against the note's four tests

The note allows a bundled WebView "if isolation, controls, accessibility and performance pass".

- **Isolation.** A WebView runs JavaScript from a vendored file and needs a channel for frame bytes in and key events out. The note allows "no JavaScript bridge beyond frame bytes and key events", which is still a bridge that carries every byte from a remote host into a JavaScript context.
- **Controls.** Pinch, pan, the key strip and the hardware keyboard are native in the other designs; in a WebView each needs a bridge call and focus handoff.
- **Accessibility.** The terminal view's text alternative is the Output tab. A WebView adds a second, unlabelled accessibility tree under it that this app does not control.
- **Verification.** The asset is a minified file (`minifiedFile` alert) outside the Gradle dependency model: no lockfile, no verification-metadata entry. It would need its own pin and hash rule.

Performance was not measured. The three failures above are enough to rule it out for this release, and the measurement would need a physical phone the project does not have.

## Decision

**An own engine, `terminal/VtEngine` in `:core`, behind the `TerminalEngine` port. No dependency is added; no candidate is in the build** (AC-05.1: the other candidates are absent from `libs.versions.toml`, the lockfiles and the APK).

### Fixture results (AC-05.2)

`FixtureGridsTest` feeds each fixture to the engine one record at a time, the way the bridge does (a full frame sizes and clears the engine; every frame is fed), and compares the engine's cells, styles, wide-character columns, cursor and screen with the grid written by hand before the engine existed.

| Fixture | Frames checked | Grids | Result | Unhandled sequences |
| --- | --- | --- | --- | --- |
| colours (16, 256, bold, dim) | seq 1 to 4 | `colours.seq2` to `seq4`, `initial` | pass | 0 |
| wide (CJK, fullwidth) | seq 1, 3, 4 | `wide.seq3`, `seq4`, `initial` | pass | 0 |
| cursor and erase | seq 1, 3, 4 | `cursor.seq3`, `seq4`, `initial` | pass | 0 |
| scroll (a repaint, row by row) | seq 1 to 4 | `scroll.seq2` to `seq4`, `initial` | pass | 0 |
| alternate screen | seq 1 to 5 | `altscreen.seq2` to `seq5`, `initial` | pass | 0 |

A perturbed expected file fails the same test (checked by hand while writing it), so the comparison is not vacuous.

**An independent engine agrees with the expected grids.** xterm.js's engine, run headless in Node (`@xterm/headless` 6.0.0, Socket overall 79 / quality 79, low `minifiedFile`: allow_with_warning; fetched with `npm pack --ignore-scripts` into a scratch directory outside the repository, no dependencies, no scripts, deleted afterwards), renders all 19 frame states to the same text, cursor, bold, dim, 256-colour palette indexes and double-width columns as the hand-written files. That settles the engine half of the xterm.js candidate (it renders the fixtures correctly) and shows the expected grids are not an artefact of how this engine reads them; the WebView half is what fails (see above). The script is kept with the evidence, not in the build. Beyond the fixtures, `VtEngineTest` (49 tests) covers the rest of what the engine accepts: every SGR form (`;` and `:`, indexed, 24-bit, with and without a colour-space id, underline colour skipped with its arguments), cursor movement and clamping, erase, insert and delete, scroll regions, wide and combining characters, UTF-8 errors, the alternate-screen switch, resize, and 2,000 random chunks that must never throw, leave the cursor outside the grid, split a wide character, or put a control character in a cell.

### Why this one

| Test from the note | Result |
| --- | --- |
| Licence | None needed. Nothing is copied or linked. |
| Isolation | Runs on the JVM in `:core`, which cannot import Android. The engine only reads: it parses and drops every OSC (clipboard write, title, hyperlink), DCS, APC, PM and SOS string, and every query (`CSI 6 n`, `CSI c`, `CSI ? Ps $ p`) without answering. `VtEngineTest` asserts each of those leaves the grid and cursor unchanged. An unknown sequence is counted (`unhandled`) and never shown as text, as the note requires. |
| Grid match | All five fixture categories, above. |
| Cost | About 640 lines of Kotlin (the engine, the width table, the grid model), with 88 tests, against a native library (termlib), a licence and repository change (Termux), or a WebView (xterm.js). |
| Verification | Covered by the Gradle build and `:core:test`; no vendored binary. |

### What it does not do

- It keeps no scrollback. herdr owns the scrollback and repaints the viewport, so a scrolled view is another frame, not phone-side state.
- It does not track application cursor-key mode: herdr does not forward `?1h`, so the key strip sends the normal-mode arrow sequences.
- Wide-character widths come from a Unicode 15 table. herdr places every run by explicit column, so a width the two tables disagree on (a few ambiguous emoji) shifts only the rest of that run.
- It does not draw images (sixel, kitty graphics, `ServerMessage::Graphics`). The CLI drops them before they reach the phone.

### When to reopen this

A herdr that sends raw PTY bytes instead of repaints; a fixture where `unhandled` is not zero (the note's rule: the fixture set grows by that case); or a measurement on a physical phone that the Compose renderer misses the 300 ms scroll target (AC-05.8), where the engine is not the likely cause but would be the first thing checked. The port keeps the swap local to one adapter.
