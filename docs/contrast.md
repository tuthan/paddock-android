# Contrast (AC-10.2)

Every colour pair the app draws, measured for both palettes. `PaletteContrastTest` computes this table from the tokens in `PaddockTokens.kt`, fails the build when a text pair is under 4.5:1 or a non-text pair (a state's dot or icon, the switch thumb, the focus ring) under 3:1, and fails when this file and the tokens disagree.

- **Ratio:** WCAG 2, `(L1 + 0.05) / (L2 + 0.05)`, with Compose's own sRGB luminance. A wash (a row's 9% tint, a banner's 12%) is composited over the ground and over a card's surface and the lower ratio is the one shown.
- **No 3:1 exception is used.** The note allows 3:1 for text at 24 sp and above; no text pair here relies on it, every text pair clears 4.5:1.
- **`faint` is decorative and graded nowhere** (chevrons and ornamental rules, about 2.8:1 on a card in both themes); `PaletteContrastTest` fails if any file draws text with it.
- **Hairlines** (a card's 8% edge, a field's 14%) are decoration by design. A field is identified by its fill, its label and its focus ring (the accent at 1.5 dp), not by its edge, and a switch by its thumb and its On or Off state, which is spoken.
- **What changed on measuring.** The light palette started as placeholders. Two tokens failed and were darkened, not excused: `dim` 5A607F to 525776 (4.20:1 on a banner wash, 4.24:1 on a field) and `attention` 8F5E15 to 7A5112 (3.79:1 on a field, 3.81:1 on a banner wash). The dark palette passed unchanged.
- **Not covered:** rendered pixels on a real display (a scanner reads those), the terminal's 16 colours (`AnsiPaletteTest`, 4.5:1 on the slab in both themes), and anything a launcher or the system draws (the widgets' backgrounds are the palette's `surface`, see `docs/widgets.md`).

<!-- contrast-table:start -->
### Dark (the reviewed default)

Text, WCAG ratio (4.5 or more is a pass); the last column is the lowest in the row.

| Text colour | ground | surface | field | slab | needs-you row | done row | banner, any hue | Lowest | Used for |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| title | 14.51 | 13.79 | 10.84 | 15.42 | 12.12 | 11.57 | 10.74 | **10.74** | headings, row titles |
| text | 11.14 | 10.59 | 8.32 | 11.84 | 9.31 | 8.88 | 8.25 | **8.25** | body, terminal and facts |
| dim | 6.13 | 5.83 | 4.58 | 6.51 | 5.12 | 4.89 | 4.54 | **4.54** | summaries, notes, placeholders |
| accent | 7.14 | 6.79 | 5.33 | 7.59 | 5.97 | 5.69 | 5.29 | **5.29** | Ghost buttons, links, selected labels |
| needs-you | 6.80 | 6.46 | 5.08 | 7.22 | 5.68 | 5.42 | 5.03 | **5.03** | Danger buttons, errors, blocked words |
| done | 9.84 | 9.35 | 7.35 | 10.46 | 8.22 | 7.85 | 7.29 | **7.29** | done words |
| attention | 8.99 | 8.55 | 6.72 | 9.56 | 7.51 | 7.17 | 6.66 | **6.66** | warnings and the Replaced banner |

Primary button label (ground on the accent fill): **7.14**.

Not text (3 or more is a pass):

| Pair | Lowest |
| --- | --- |
| accent dot or icon, on ground and surface | 6.79 |
| needs-you dot or icon, on ground and surface | 6.46 |
| done dot or icon, on ground and surface | 9.35 |
| attention dot or icon, on ground and surface | 8.55 |
| switch thumb (off) on its track | 5.53 |
| switch thumb (on) on the accent track | 7.14 |
| focus ring (text colour) on ground and surface | 10.59 |

### Light

Text, WCAG ratio (4.5 or more is a pass); the last column is the lowest in the row.

| Text colour | ground | surface | field | slab | needs-you row | done row | banner, any hue | Lowest | Used for |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| title | 13.85 | 17.09 | 11.78 | 15.28 | 12.20 | 12.28 | 11.63 | **11.63** | headings, row titles |
| text | 8.90 | 10.98 | 7.57 | 9.82 | 7.84 | 7.89 | 7.47 | **7.47** | body, terminal and facts |
| dim | 5.70 | 7.04 | 4.85 | 6.29 | 5.03 | 5.06 | 4.79 | **4.79** | summaries, notes, placeholders |
| accent | 6.12 | 7.55 | 5.20 | 6.75 | 5.39 | 5.42 | 5.14 | **5.14** | Ghost buttons, links, selected labels |
| needs-you | 5.60 | 6.92 | 4.77 | 6.18 | 4.94 | 4.97 | 4.70 | **4.70** | Danger buttons, errors, blocked words |
| done | 5.83 | 7.19 | 4.96 | 6.43 | 5.14 | 5.17 | 4.89 | **4.89** | done words |
| attention | 5.65 | 6.97 | 4.80 | 6.23 | 4.98 | 5.01 | 4.74 | **4.74** | warnings and the Replaced banner |

Primary button label (ground on the accent fill): **6.12**.

Not text (3 or more is a pass):

| Pair | Lowest |
| --- | --- |
| accent dot or icon, on ground and surface | 6.12 |
| needs-you dot or icon, on ground and surface | 5.60 |
| done dot or icon, on ground and surface | 5.83 |
| attention dot or icon, on ground and surface | 5.65 |
| switch thumb (off) on its track | 5.58 |
| switch thumb (on) on the accent track | 6.12 |
| focus ring (text colour) on ground and surface | 8.90 |
<!-- contrast-table:end -->
