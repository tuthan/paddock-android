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
| Primary | the one thing this screen is for | Connect, Send prompt, Import key, Install the relay, Yes, Review prompt, Start an agent…, Start agent, Start with this name, Rename, Buy Pro (the Pro gate's one purpose), Enter the address (Welcome), Start / Search again (Find on this network), Allow local-network access, Allow the camera, Turn on alerts… / Update alerts… (the alert setup's one purpose), Open in the ntfy app (its subscribe link), Unlock (the lock screen's one button) |
| Secondary | a neutral action, and the safe choice in a dialog | Restore purchase, Tip <price> (Settings), Watch (a saved machine, on its card on Machines and on its page; locked, the lock glyph and the "Pro" tag), Cancel, Back, Back to the herd, Close, Not now, Done, Dismiss, Copy, Share, Copy key only, Show as QR, Save name (a machine's page), Send a test alert, Copy the subscribe link, Copy the commands, Keyboard, Hide keyboard, Release, Resize to fit, Request control, Edit, Move up, Check again, Keep the old key, Restart (disabled, Spaces), Leave it |
| Ghost | open, inspect, retry, or take another way | Open terminal, Re-read, Try again, Check the setup, Install / Reinstall / Replace the script… (alert setup, "the commands" section), Manual input, Focus on desktop, Trust and connect, Answer in the terminal instead, Rename agent…, Show its workspace/tab on the desktop, Open the pane, Choose another name, Find on this network, Scan the code on the desktop, Paste a pairing link, Wake the machine, Trust this repository…, Start anyway, Open it, Clear the name, Add another machine |
| Danger | interrupt, stop, forget or replace | Esc, Ctrl+C, Esc · Interrupt, Remove, Remove… (on a saved machine's page), Reset the record, Turn off alerts…, Replace with the new key, Stop…, Delete…, and a recovery card's "Close the new …" |

A row of controls that mean the same kind of thing uses one kind. The Terminal tab's four controls (Request control, Keyboard, Release, Resize to fit) are all Secondary, which is also what the design draws for Request control. They were two Ghost and two Secondary before, so a row looked like three styles.

## What the audit changed (2026-10-03)

The component already matched the design's `.btn` (radius, heights, type, border alphas). The differences were kinds at call sites:

- Terminal tab: Request control, Keyboard / Hide keyboard and Resize to fit Ghost to Secondary (Release already was); the controls are one scrolling line in both layouts instead of wrapping Resize to fit onto a row of its own.
- Back, Back to the herd (the screens shown when an agent or machine is gone): Ghost to Secondary, as the design draws Back to the herd.
- Cancel, Edit and Move up in the snippet editor, Done in Manual input, Dismiss in Manual input, the composer and the decision sheet, Copy key only and Show as QR on Add machine: Ghost to Secondary.
- Unregister (alert relay): Ghost to Danger, because it forgets a registration. (The 2026-10-07 setup screen replaced it with **Turn off alerts…**, Danger, which unregisters and takes the relay down after a confirmation.)

Left as they are: Ghost for "Trust and connect", "Check the setup", "Add a machine", "Paste a pairing link" and a banner's action (tinted by the banner); "No" on the decision sheet stays Secondary (the design draws a longer "No, and tell Claude what to do differently" as Ghost; that sheet was reviewed as built).

## Locked (Pro)

A button for a Pro capability the phone does not hold (vault M8, decision D6) passes `pro = true` and keeps its plain label:

- **Glyph and tag.** A 16 dp lock (`PaddockIcons.Lock`, in the button's text colour; test tag `PRO_LOCK_TAG`, so `SpacesTest`, `ProGateTest` and `MachinesTest` assert the glyph wherever they assert the tag) before the label, in place of the button's own icon, and a small "Pro" pill after it: 11 sp (`stateWord`), a 14% accent wash with a 50% accent edge, the word in `title`. On Primary the word and the edge take the button's text colour (an accent wash vanishes on the accent fill), and on Danger the word does, so a locked Stop still reads as Danger. `ProTagTest` holds the word at 4.5:1 on every kind in both palettes, on the ground and on a surface, and for a Ghost also on a banner's wash (no other kind sits on a banner; a Danger tag there would fall to about 3.9:1).
- **Spoken name.** "<label> · Pro" ("Stop… · Pro", "Start an agent… · Pro"), so TalkBack reads the lock and nothing depends on the glyph or a colour. A caller that sets its own `contentDescription` in the modifier (a label that names the machine) keeps it.
- **No change of kind or size.** A locked Danger button stays Danger, a Primary stays Primary; the glyph and the tag are shorter than the label's line at every font scale, so the height does not change (`ButtonStyleTest`, at 1x and 2x). When a narrow button has no room for the tag beside the label (a half-width Delete… at a large font), the tag moves, whole, to a line under the label, as a wrapped label would; it is never clipped. Whether it fits is decided from the tag's own full width (`proTagBeside` in `LockedLabel`, `ProTagTest`), never from the room the label leaves (the first version was a `FlowRow`, which measures the tag into whatever the label leaves on its line). `ButtonStyleTest` checks the half-width Delete… at 130 %: label on one line, the tag whole on a line under it and inside the button. It does not read `hasVisualOverflow` off the semantics result: for a plain Text that result is rebuilt with the node's whole room as the paragraph width, so it says "overflow" for any word narrower than its room (the test failed that way twice on the emulator, 2026-10-06, with the tag drawn whole).
- **Still tappable.** The tap asks the gate, which opens the sheet from an idle app and, from a busy one, opens nothing and shows one sentence in the notice bar saying when Pro is offered (`ProGate.deferNotice`; docs/billing.md, "What the gate does").

A button's label never carries " · Pro" (`ButtonRolesTest` fails on it, and pins `pro =` on Spaces' Start an agent…, Stop… and Delete…, and on the two Watch buttons of Machines, the card's and the page's). The suffix is for text rows (`proLabel`: "Guarded answers · Pro" in Settings). Home's machine chips (`MachineChipRow`, `docs/machines.md`) are chips, not buttons: a locked one draws a 14 dp lock after the name, and TalkBack reads "<name>, not watched, Pro". The one exception to all of this is "Set up" on the decision sheet: the gate may not open over a pending request, so without Pro it is withheld, not marked, and the sheet's notice says guarded answers are Pro and set up from Settings (`decisionSetUp`, `decisionNotice`).

## Kept honest

- `ButtonRolesTest` reads the source and fails when a button named in the tables above has another kind, and when the four terminal controls differ (it fails on a Ghost "Release").
- `ButtonStyleTest` renders the four kinds at each size and fails when their heights differ or a regular or small one is under 48 dp, when a locked button is taller than a plain one at 1x or 2x, and when a half-width locked Delete… at 130 % breaks its label, clips its tag, or leaves the tag anywhere but under the label and inside the button.
- `ProTagTest` (JVM) fails when a locked button's spoken name changes or the "Pro" tag's word falls under 4.5:1 on a kind.
