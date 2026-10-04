# Accessibility (Phase 10)

What was checked, how, what it found, and what only a person with a phone can check. The acceptance criterion is AC-10.3: accessibility checks pass on every screen, TalkBack script recorded, reduce motion respected.

## The Accessibility Test Framework is not used

`androidx.compose.ui:ui-test-junit4-accessibility` (`enableAccessibilityChecks()`, the vault note's `AccessibilityChecks`) was declined on 2026-10-02 (`dependency-reviews.md`). So the automatic pass is Paddock's own, `app/src/androidTest/.../ui/SemanticsAudit.kt`, over the semantics tree and the rendered pixels. It is **not** the Accessibility Test Framework and makes no claim to its coverage. Google's Accessibility Scanner on a phone stays in `talkback-script.md` as the stand-in for the checks this audit cannot make.

## What `SemanticsAudit` checks

On the merged tree, the one TalkBack walks:

| Rule | Fails when |
| --- | --- |
| `label` | an actionable node (click, long click, button, switch, checkbox, radio, tab) has no content description and no text |
| `target` | an actionable node's touch rectangle, as the test API's `getUnclippedBoundsInRoot` reports it (padding and minimum-size expansion included), is under 48 × 48 dp. The one exemption is named: the Terminal tab with the keyboard up (36 dp buttons, 40 dp strip keys, `buttons.md`) |
| `duplicate` | two actionable nodes sit on one rectangle |
| `state` | a switch or checkbox does not announce on or off, or a radio button or tab does not announce selected |
| `heading` | a screen has no node marked as a heading (dialogs and the Manual input panel are exempt) |
| `collapsed` | a region that must keep a height (the request on the decision sheet, the output) shows less than its minimum **to the user**: its clipped bounds, so a region squeezed to nothing by its siblings counts as nothing even when its content is tall |

On the unmerged tree and a screenshot of the same window:

| Rule | Fails when |
| --- | --- |
| `contrast` | a text run, as drawn, is under 4.5:1 (3:1 for 18 sp, or 14 sp bold, and larger). Background = the most common pixel in the text's box; foreground = the mean of the pixels furthest from it. 0.35 of slack covers anti-aliasing: a thin glyph never reaches its full colour. Text in a disabled control is exempt (WCAG 1.4.3 exempts inactive components); the reason a control is off is a separate, enabled text and is measured |

With large-text mode (`-e a11yLarge 1`, below), also:

| Rule | Fails when |
| --- | --- |
| `overflow` | any text is ellipsized or taller than its box. The one exemption: the example inside an empty address field (`192.168.1.20 or box.example.ts.net`), whose label above it is whole |
| `offscreen` | text or an actionable node reaches outside the window's width, unless it sits in a horizontal scroller (the key strip) |

`SemanticsAuditSelfTest` feeds the audit a screen that breaks each rule and one that breaks none, so a clean result means something. Every audit test also saves the screen as `a11y-<screen>.png` and what it measured as `a11y-<screen>.txt` next to the other screenshots, in both themes (the light and dark screenshots of AC-10.3).

## Where it ran

An `audit*` test in the screen's own test class, so it uses the same fixtures as that screen's other tests. Screens and states covered, dark and light unless noted: Home (live; degraded), Agent output (live, stale), Composer, Manual input, Decision sheet (open; expired, dark), Activity (all; phone actions), Add machine (new, with a key, permission denied, connecting), the fingerprint dialogs (first trust, with a pairing link, changed key, pairing mismatch; dark), Import key, Relay install, Alert relay (not installed, failed check), Guarded answers (installed, not installed), Settings (and alerts on with a recovery row), Snippet editor, Spaces (and the row-actions and rename dialogs; dark), Terminal (controlling, observing, keyboard up). Run with the whole `ui` package (`tools/run-ui-tests.sh <serial> io.github.tuthan.paddock.ui`).

**At a large font on a 360 × 640 dp screen:** `tools/run-a11y-large.sh [serial] [out-dir] [font scale, default 2.0]` resizes the emulator to 720 × 1280 px at 320 dpi (360 × 640 dp), sets `font_scale`, runs every `audit*` test with the overflow and off-screen rules on (`-e a11yLarge <scale>`), and restores the display and the font whatever happens. Run at 1.3, 1.5 and 2.0.

## What it found, and what changed

| Finding | Where | Fix |
| --- | --- | --- |
| Rows of a host that is not live were faded with `alpha(0.55)`: the context line was **2.6:1** dark and **2.57:1** light, the title 4.05:1 light. The palette's own pair table could not see it, because the fade is applied after the colours are chosen | Home, degraded | The row's text is never faded. A stale row has no wash, its title is drawn in the `dim` colour (4.5:1 or more on both grounds), its dot is faded (not text), and what it *was* is in words ("was blocked") |
| At 200 % on a small phone the **request on the decision sheet collapsed to zero height**: Yes and No were on screen and what they approve was not. The safety note under the buttons was cut off | Decision sheet | `FlexColumn` (new, `ui/components/FlexColumn.kt`): the request is the flexible child, as it was with `weight`, but never gets less than 120 dp; when the rest plus that minimum is taller than the screen, the sheet scrolls. On a screen with room it lays out as before |
| At 200 % the **output collapsed to zero height** (header, chips, tabs and a banner left it nothing), and its footnote was cut | Output tab | Same `FlexColumn`: the output keeps 140 dp, its banner and footnote are separate children of the column, and the body scrolls only when they do not fit |
| The state chip's age was cut ("Blocked · observed 40 s") | Output, Composer | A chip wraps to three lines at a large font; one line otherwise |
| A row's detail line ("docs · observed 40 s ago · tap marks seen") was cut | Home | Four lines at a large font; two otherwise |
| The Activity filter's **All** chip was 43 dp wide (48 high) | Activity | The chip's touch target is at least 48 dp both ways (`FilterChips`) |
| Widgets cut their text or their time at 130 % and 200 % | Widgets | `widgets.md`, "Font size and small cells" |

Wrongly reported at first and corrected in the audit, not in the app: 28 dp targets that were 48 dp (the audit read the content box, not the padded one), low contrast in disabled controls (exempt), and `hasVisualOverflow`, which is true for any wrap-content text laid out under a wide constraint. A first version of the layout fix scrolled the whole body at any large font; two existing 200 % tests (`ManualInputPanelTest`) showed that on a tall screen that pushed the panel below the fold, which is why the fix is a flexible column and not a rule about font size.

## Reduce motion

The app has **no animation that runs on its own**. The state dot is a static canvas; nothing breathes, pulses or loops. `MotionTest` (JVM) reads the sources and fails on any infinite animation, any framework animator, or any finite animation that is not on its list. The list has two entries, both finite and started by a user action or a change in the list: `animateItem` on the herd's rows (a row moves or fades when the list changes; the order never changes under a finger), and the switch thumb (`animateDpAsState`, 150 ms). Compose runs both on the platform's animator clock, which Settings > Accessibility > Remove animations sets to zero, so they finish at once with it on. That last step is the platform's documented behaviour and was not re-measured on a phone.

## Heading and state structure

Every screen above has a heading node (`heading` rule); every switch, checkbox, radio and tab announces its state (`state` rule). Row, widget and chip descriptions put the state word first and never rely on colour (`talkback-script.md`, first lines).

## Not checked here

- **TalkBack itself.** How the screen sounds, focus order and what is announced on a live update need a person with a phone: `talkback-script.md`. The semantics the script listens for are asserted by the tests.
- **The Accessibility Scanner** on a phone (`talkback-script.md`, A1 to A5): it reads rendered pixels of the real device, and sees touch-target and contrast suggestions the emulator audit may not.
- **The terminal grid.** It is drawn on a canvas in the ANSI palette (`contrast.md`); the audit measures only text nodes.
- **Switch Access and keyboard traversal order**, other than the focus the dialogs set (the dialog's safe choice is focused first, tested per dialog).
- **Dynamic colour, high-contrast text, colour inversion and colour-correction settings.** Nothing relies on colour alone, but none of these was exercised.
- **Languages other than English.** All strings are English.
