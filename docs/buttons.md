# Buttons

One component, `PaddockButton`, four kinds. The design is the vault's `assets/src/_style.part` (`.btn`) and its System sheet; this is what the app does with it.

## What a button looks like

| | Regular | Small (`small = true`) | Dense (`dense = true`) |
| --- | --- | --- | --- |
| Height | at least 48 dp | at least 48 dp | 36 dp |
| Text | 15 sp, weight 600 | 14 sp, weight 600 | 14 sp, weight 600 |
| Padding | 16 dp across, 10 dp down | 12 dp across, 10 dp down | 12 dp across, 4 dp down |
| Radius | 10 dp (`PaddockRadii.button`) | same | same |
| Icon | 20 dp, 8 dp before the text | same | same |

The four kinds differ in colour only (`ButtonStyleTest`: every kind is the same height at a size):

| Kind | Fill | Text | Border |
| --- | --- | --- | --- |
| Primary | accent | ground | none |
| Secondary | field | title | control line |
| Ghost | none | accent | accent at 35% |
| Danger | none | needs-you | needs-you at 40% |

Dense is the one deliberate departure from the 48 dp target: the Terminal tab's controls while the Android keyboard shares the screen (see terminal-control.md). The key strip's keys are the same: `field` fill, control line, radius 8 (`PaddockRadii.key`), 48 dp, or 40 dp with the keyboard up.

## Which kind

By what the button means, never by where it sits or how many share a row (design System sheet: "Primary: review the current prompt. Secondary. Ghost: open, inspect, fall back. Danger: interrupt, stop, forget"):

| Kind | Means | Examples |
| --- | --- | --- |
| Primary | the one thing this screen is for | Connect, Send prompt, Import key, Install the relay, Yes, Review prompt, Start an agent…, Start agent, Start with this name, Rename |
| Secondary | a neutral action, and the safe choice in a dialog | Cancel, Back, Back to the herd, Close, Not now, Done, Dismiss, Copy, Share, Copy key only, Show as QR, Keyboard, Hide keyboard, Release, Resize to fit, Request control, Edit, Move up, Check again, Keep the old key, Restart (disabled, Spaces), Leave it |
| Ghost | open, inspect, retry, or take another way | Open terminal, Re-read, Try again, Check the setup, Manual input, Focus on desktop, Trust and connect, Answer in the terminal instead, Rename agent…, Show its workspace/tab on the desktop, Open the pane, Choose another name, Trust this repository…, Start anyway, Open it, Clear the name |
| Danger | interrupt, stop, forget or replace | Esc, Ctrl+C, Esc · Interrupt, Remove, Reset the record, Unregister, Replace with the new key, Stop…, Delete…, and a recovery card's "Close the new …" |

A row of controls that mean the same kind of thing uses one kind. The Terminal tab's four controls (Request control, Keyboard, Release, Resize to fit) are all Secondary, which is also what the design draws for Request control. They were two Ghost and two Secondary before, so a row looked like three styles.

## What the audit changed (2026-10-03)

The component already matched the design's `.btn` (radius, heights, type, border alphas). The differences were kinds at call sites:

- Terminal tab: Request control, Keyboard / Hide keyboard and Resize to fit Ghost to Secondary (Release already was); the controls are one scrolling line in both layouts instead of wrapping Resize to fit onto a row of its own.
- Back, Back to the herd (the screens shown when an agent or machine is gone): Ghost to Secondary, as the design draws Back to the herd.
- Cancel, Edit and Move up in the snippet editor, Done in Manual input, Dismiss in Manual input, the composer and the decision sheet, Copy key only and Show as QR on Add machine: Ghost to Secondary.
- Unregister (alert relay): Ghost to Danger, because it forgets a registration.

Left as they are: Ghost for "Trust and connect", "Check the setup", "Add a machine", "Paste a pairing link" and a banner's action (tinted by the banner); "No" on the decision sheet stays Secondary (the design draws a longer "No, and tell Claude what to do differently" as Ghost; that sheet was reviewed as built).

## Kept honest

- `ButtonRolesTest` reads the source and fails when a button named in the tables above has another kind, and when the four terminal controls differ (it fails on a Ghost "Release").
- `ButtonStyleTest` renders the four kinds at each size and fails when their heights differ or a regular or small one is under 48 dp.
