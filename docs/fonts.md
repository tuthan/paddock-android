# Bundled fonts

The design (vault `01-ux-brainstorm`, type table) uses IBM Plex Sans for text and JetBrains Mono for facts (ids, paths, times,
fingerprints). Both are bundled in the APK, unmodified, so the app makes no font request of any kind. Approved 2026-10-02,
Hung Vo ("ok approve let finish them"), on the recommendation in the session. They are assets, not package-manager
dependencies, so Socket does not apply; the review is provenance, licence and a pinned hash, checked by `tools/check-fonts.sh`
(called from `tools/check.sh`).

| File in `app/src/main/res/font/` | Upstream file | Used for |
| --- | --- | --- |
| `ibm_plex_sans_regular.ttf` | `IBMPlexSans-Regular.ttf` (Version 3.005) | body, secondary, notes, chips (400) |
| `ibm_plex_sans_medium.ttf` | `IBMPlexSans-Medium.ttf` (Version 3.005) | row titles, navigation (500) |
| `ibm_plex_sans_semibold.ttf` | `IBMPlexSans-SemiBold.ttf` (Version 3.005) | titles, kickers, state words, buttons (600) |
| `jetbrains_mono_regular.ttf` | `JetBrainsMono-Regular.ttf` (Version 2.304) | mono facts (400) |
| `jetbrains_mono_medium.ttf` | `JetBrainsMono-Medium.ttf` (Version 2.304) | the agent monogram and the "offered now" fact (500) |

## Provenance

- IBM Plex Sans: `ibm-plex-sans.zip` from the GitHub release `@ibm/plex-sans@1.1.0` of `IBM/plex` (published 2024-11-13), files
  under `fonts/complete/ttf/`. Zip SHA-256 `fb365d910566e6d199cc2c15579a7dd9a267128e18431a394ed81f1970c69200`.
- JetBrains Mono: `JetBrainsMono-2.304.zip` from the GitHub release `v2.304` of `JetBrains/JetBrainsMono` (published
  2023-01-14), files under `fonts/ttf/`. Zip SHA-256 `6f6376c6ed2960ea8a963cd7387ec9d76e3f629125bc33d1fdcd7eb7012f7bbf`.
- GitHub reports no digest for either release asset. Each of the five TTFs was therefore compared with the file of the same
  name in the repository's git tree at the release tag (`@ibm/plex-sans@1.1.0`, `v2.304`) and the hashes were identical. That
  is a second path from the same organisation, not an independent publisher.
- The per-file SHA-256 values are in `tools/check-fonts.sh`.

## Licence

Both are under the SIL Open Font License 1.1. IBM Plex carries the Reserved Font Name "Plex". The licence requires the
copyright notice and licence text to travel with the fonts, which `app/src/main/assets/licenses/fonts.txt` does (both texts,
the Plex one with its line endings normalised) and **Settings > About > Fonts** shows. Two consequences for any later change:

- **Do not modify the files** (no subsetting, no renaming of the font inside the file, no variable-font conversion). A modified
  version may not use the Reserved Font Name. Only the file names in `res/font/` differ from upstream, because Android resource
  names are lower case; the bytes are the upstream bytes.
- The fonts must not be sold on their own. They ship only as part of the app.

## Use in the app

`PaddockFonts` (in `ui/theme/PaddockTokens.kt`) builds the two families; every text token takes its family from there. Plex
Sans digits are tabular by default (all ten advance 600/1000 em), which is what the Home ages and the Activity times need;
JetBrains Mono is monospaced. The terminal canvas (Terminal tab) still draws with the system monospace and measures its own
cell: a font change there would move cell sizes and belongs with its own check. Plex Sans has no U+25CF; the only occurrence
in the sources is in comments.

## Updating

Replace a file only with the upstream bytes of a release you have looked at: download it, compare it with the git tree at the
tag, update the hash in `tools/check-fonts.sh` and the version and hash here, and re-run the Compose suite and the 200% font
screenshots (Plex is wider than the system sans, so titles ellipsize and wrap sooner).
