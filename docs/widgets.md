# Widgets (Phase 10)

Three home-screen widgets on a dated cache. They are `AppWidgetProvider`s drawing `RemoteViews`; no Glance and no WorkManager (both reviewed 2026-10-03 and not added, `dependency-reviews.md`).

| Widget | Size (min) | Shows |
| --- | --- | --- |
| Summary | 4 × 2 (250 × 110 dp) | `2 need you` and `as of 14:02`; `Laptop · 1 done · 2 working · 3 ready`; up to two blocked agents, each a code chip, the title and a **Review** button |
| Count | 2 × 2 (110 × 110 dp) | the number that need you, its label, `as of 14:02` |
| Strip | 4 × 1 (250 × 40 dp) | `1 needs you` or `Nothing needs you`, `as of 14:02` |

## The rule: a count never appears without its time

`WidgetPresenter` turns the cache into text and is the only place that rule lives. Content with data always has `asOf`. Content without data (no cache yet, an unreadable one, another version) has no digit at all, only `Open Paddock to read your herd` (`Open Paddock` on the 2 × 2). A read older than 30 minutes (two missed refreshes) or dated in the future (a clock that moved) is *stale*: the time prints as `as of 14:02 · old` and the widget draws in the dim colour, so an old number says it is old in words and not only in a colour. A read from another day prints the weekday (`as of Fri 14:02`).

## Where the data comes from

Only `files/widget-cache.json`: the last successful read of the watched machine (host id, machine name, session, read time, five counts, and at most two blocked agents with title, kind, ids, sequence and the time the phone saw them blocked). Titles are the herd's own cleaned titles, at most 60 characters. No prompt text, terminal output or path is in it. The file is replaced atomically (fsynced temp file, then rename); a file that does not parse is set aside as `.corrupt` and reads as "no cache".

A widget redraw reads that file and nothing else. No widget holds a socket, a coroutine scope or a credential.

Who writes it:

- **The open app**, whenever the herd's counts or blocked rows change, and at most once a minute when they do not (`AppGraph.trackWidgetCache`). Writing redraws the widgets.
- **The refresh job**, `WidgetRefreshJobService`, a `JobScheduler` periodic job at the 15-minute floor, network any, not persisted. It runs `AppGraph.refreshWidgetCache`: if the app is in the foreground it does nothing; if the cache has no read of a machine yet it does nothing (the first read is the app's); otherwise it opens one SSH connection to that machine, runs `herdr --session S api snapshot`, builds the same counts the herd shows, writes the cache and releases the connection. A read that fails leaves the cache as it was, so the widgets go on saying when their numbers are from. It installs nothing on the host.
- The job is scheduled when a widget is added and on every `onUpdate` (the system sends one after a reboot); it is cancelled when the last widget of the three kinds is removed. It is not persisted, because surviving a reboot would need a boot receiver and its permission; the reboot's `onUpdate` does the same.

## Taps

A tap anywhere on a widget opens the herd (the launcher entry). A **Review** button, and the row it sits in, fires that agent's alert link, `paddock://open?...`, the same one a notification's Review fires: the app resolves it against a fresh read of that exact terminal before it says anything about the agent (`alerts.md`). The link is built from the cache's ids and checked with `DeepLink.parse` when the content is made; a row whose ids do not make a valid link is drawn without the button and its tap opens the herd. Every `PendingIntent` is explicit (this app's `MainActivity`), immutable and carries nothing the user typed.

## Colours and the theme

Widget colours are resources equal to the app's tokens (`WidgetColorsTest` pins each one to `PaddockTokens`, light and dark). A colour that changes with the state is never set from code: each tone is its own view in the layout and the renderer shows one, so the launcher's own night mode picks the palette when it draws. Each pair is in `contrast.md`'s table; text on the widget ground is at least 4.5:1 in both themes.

## Font size and small cells

A widget cannot grow with the user's font: the launcher's grid fixes its cell. Every text in a widget is sized in dp at the user's font scale held between 1 and 1.3 (`WidgetRenderer.scale`), so the result is the same on the platform's linear and its non-linear (Android 14+) scaling. Past 1.3 the widget draws less before it would cut anything, in this order:

1. no second row, no detail line (`secondRowFromDp`, `detailFromDp`);
2. no rows at all when not even one fits (`firstRowFromDp`); the headline and the time stay and a tap opens the herd;
3. the 2 × 2 puts the number and its label on one line (`compactCount`);
4. the strip is the headline and the time; its detail counts are only in the content description.

An old read at a larger font adds a line to the summary's header (`headerExtraDp`), because `as of 14:02 · old` pushes the headline onto two lines. On the 250 dp strip at 130 % an old read's headline can end in an ellipsis (`1 needs y…`): the count and the time stay whole, and the content description carries the full text.

`WidgetRenderTest.nothingIsClippedAtAnyFontScaleOnAnyGridAndEveryCountKeepsItsTime` draws five contents (two, one, none needing, old, unread) × three widgets × a roomy and a tight grid × font scales 1, 1.3 and 2 × light and dark, and fails when any text has less height than its layout needs, lies outside the widget, or when a time wraps (the 2 × 2 may wrap it to two lines) or ends in an ellipsis. Heights of 94 dp and more get a row at scale 1; the tight 110 dp grid is the one that was found clipping.

One limit: the renderer reads the font scale when it draws. A launcher keeps the last drawn `RemoteViews` after the user changes the font size, so the layout choice follows on the next redraw (a cache write, the job's run, or the launcher's own `onUpdate`).

## Accessibility

Each widget has one content description for the whole card (`Paddock, Laptop. 2 need you. 1 done, 2 working, 3 ready. as of 14:02.`, plus `This read is old.` when stale); each row and its button describe the agent (`Review approve edit to build.gradle, blocked, seen 13:58`). Every tap target is at least 48 dp tall (rows) or 40 dp (the button inside a 48 dp row). Widgets are `home_screen` category only: never on the lock screen.

## Glyph decision (Phase 12)

The widgets' rows and the notifications' large icon keep the **two-letter code** (`cl`, `cx`), not the Phase 12 glyph. Reasons: a `RemoteViews` row can carry a text chip but not a vector the app draws with its theme (the glyphs are Compose paths), the code is readable at 12 sp on both palettes at ≥ 4.5:1 and a glyph at that size is not, and the code is what TalkBack already reads. Revisit if the glyphs ship as drawable resources.

## Tests

| Test | Covers |
| --- | --- |
| `WidgetTest` (core, 13) | cache round trip and corrupt/other-version files, presenter text and stale rule, deep links, refresher outcomes |
| `WidgetColorsTest` (app, 4) | every widget colour equals its token, both palettes |
| `WidgetHostTest` (androidTest, API 29+) | each provider bound to a real `AppWidgetHost` with the shell's bind permission; the system's own `onUpdate` draws the cache's time, the count and (summary) the first row and its Review button. API 26 has no way to hold that permission in a test and skips it |
| `WidgetRenderTest` (androidTest, 11) | every size × theme × grid carries its time; quiet, old and unread content; no digit before a read; descriptions; tap wiring; 200 % font; no clipping at 1, 1.3, 2; providers are home-screen only with no framework timer; the job is periodic, 15 minutes, not persisted; the service is system-bound |
