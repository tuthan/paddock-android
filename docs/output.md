# The Output tab, and copying from it

What the Output tab reads and shows, and how a copy leaves it (Phase 15, Part A). Free, with no capability id; it adds no permission and no dependency.

## What the tab reads

- **The tail of the agent's screen:** the last 200 lines of `herdr agent read <pane> --source recent-unwrapped --lines 200 --format ansi`, read again every second while the screen is in front, the Output tab is shown and following is on (`OutputFeed`, `outputFeedVisible`).
- **What it shows.** `Ansi.parse` keeps text, the sixteen foreground colours, bold, dim and underline, and drops everything else: no OSC (so no clipboard write, no link, no title), no cursor movement, no control character, no bidirectional override, no zero-width or tag character, no unpaired surrogate. A line is cut at 2,000 characters, a surrogate pair never in half, and the line says it was cut.
- **Following and paused.** A drag that reveals older lines, or a long press on a line, pauses following; the text stays and nothing is read. **Paused · tap to follow** resumes and reads at once. An answer that lands after a pause is dropped (and read again when following resumes), so the lines under a finger or a menu never change.
- **When a read fails** the last text stays with a banner and its age; when the terminal or the agent is gone the screen says so and offers the way back.

## Copying

Two ways, both on the Output tab, neither changing what the tab reads:

1. **Select text** (a chip beside Following or Paused). Opens a frozen copy of the lines as they were read: one read-only text with the platform's own selection (handles, magnifier, the toolbar's Copy and Select all), and **Copy all**, **Share** and **Re-read**. The feed reads nothing while it is open, so what is copied is what was seen. It opens at the newest line, or at the top of the line it was opened from, with nothing selected. Its header says what it is a copy of ("Text as read at 14:03:12 · 200 lines") and, when they apply, that the last read failed, that lines are cut at 2,000 characters, that only the newest N of M lines fit one copy, and that the agent or terminal has gone since (Re-read is then off). A rotation keeps the view and its copy, and so does the app being away long enough for its connection to close (2026-10-12): the copy stays with the agent's name as it was, and Re-read waits for the connection. A restart of the process brings back the Output tab, not the view. The copy is never in saved state: the field's `TextFieldState` is a plain `remember`, because `rememberTextFieldState` would save the whole copy (measured: 66 KB for a usual read, 298 KB at the budget). The toolbar's Copy goes through `SensitiveClipboard`, which must be an `AndroidClipboard`: when a text field shows its toolbar, foundation 1.12 reads the platform clipboard through `getNativeClipboardManager`, which throws for any other `Clipboard`, and a plain wrapper crashed the app on the first long press (found on a phone 2026-10-12; `SelectTextTest#realLongPressesSelectWithoutCrashing`, which a semantics selection never caught).
2. **A long press on a line:** a haptic tick, following paused, and a menu at the line with **Copy line**, **Copy from here to newest** and **Select text…** (no Copy line on an empty line). The menu acts on the lines captured at the press. TalkBack gets the same three as custom actions on each line ("Copy line", "Copy from here to newest", "Select text from this line"); a line has no click action.

## Paths in the output are links (Phase 15, Get)

A path the agent printed (`out/report.pdf`, `/home/jdoe/proj/docs/a.md:42`, a bare name with a known extension) is underlined in the Output tab when the screen has a Get to offer (`PathTokens`, at most eight a line, none in a line that was cut). The guess is cheap on purpose: the machine judges the real path when it is tapped (`docs/files.md`), so a wrong token costs one refused tap. A tap on the link opens the Get sheet; a long press on it opens the line menu above and follows no link; a drag that starts on it scrolls. TalkBack gets the three line actions and then one **Get file <path>** per path, and a line has no click action. Paths in the Terminal tab and in Select text are plain text.

## What a copy holds

The text the screen shows, and nothing else (`OutputCopy`):

- trailing spaces removed, lines joined with `\n`, nothing reflowed or joined, an empty line empty, no trailing newline;
- no escape code, no control character, no hidden character: a paste into a terminal cannot carry the end of a bracketed paste (`ESC [ 201 ~`) or text the user did not see;
- the agent's own line breaks, markers and borders come along (the view says so);
- at most 150,000 characters per copy: the newest whole lines that fit, and the copy says how many older lines it left out. The number is half the smallest size at which the share sheet failed in the spike (300,000 characters on API 26; the binder buffer is 1 MB for the whole process). The clipboard itself took more.

## The sensitive mark, and what it does and does not promise

Every copy goes through one helper (`ui/Copy.kt`, `CopyKind`), and a source scan (`CopyKindTest`) fails if anything else writes the clipboard.

| Copy | Marked sensitive |
| --- | --- |
| Agent output (Copy all, Copy line, Copy from here to newest, the toolbar's Copy in Select text) | while **Protect sensitive screens** is on, which is the default; never with it off |
| The alert setup script and the ntfy subscribe link (they hold the topic and, for a self-hosted server, its token) | always |
| The phone's public key, the authorize command, the wake command, the guarded-answers setup | never (not secrets) |

The mark is `android.content.extra.IS_SENSITIVE` on the clip's description. It is set on every Android level (the constant exists from API 33, the extra is an ordinary key). It makes Android 13 and later hide the preview in the system's confirmation, and a keyboard or clipboard manager that honours it keep the clip out of its history. **It adds no protection of its own** (Android's own words), and a keyboard that ignores it will keep a copy: nothing relies on it.

- **Not cleared.** Paddock never clears a copy of output: the user pastes later, clearing could destroy a newer clip from another app, and Android 13 and later clear a clip after a while by themselves.
- **The two alert copies are cleared** when Paddock next comes to the front, if the clipboard still holds them, because Android 12L and lower never clear it. The match is the clip's label and timestamp, read from its description only: the text is never read, and another app's clip is left alone (`AlertClipGuard`). After a process death nothing is armed and the copy stays.
- **Never logged or journaled.** No copy writes a log line or a journal row; the live flow prints a random marker line, copies it, and fails if logcat ever holds it.
- **On Android 12L and lower** the app says "Copied N lines."; on 13 and later the system confirms, and the app says something only when a line was cut or older lines were left out.
- **The agent never writes the clipboard**: OSC 52 stays dropped, here and in the Terminal tab.

## Protection

The view is in the Output route's window: `FLAG_SECURE` while **Protect sensitive screens** is on (the shell capture is black) and the app lock above it. The setting changing while the view is open takes effect on the next copy, and `FLAG_SECURE` follows at once.

## Where it lives

`core/.../output/AnsiText.kt` (`AnsiLine.cut`, `SafeText`), `OutputCopy.kt`, `OutputFeed.kt` (`readOnce`); `app/.../ui/Copy.kt`, `ui/screens/SelectText.kt`, `ui/components/OutputComponents.kt` (`OutputRow`, `linesToText`), `routes/OutputRoute.kt`. Part B adds `routes/GetRoute.kt` and `ui/screens/GetSheet.kt`; its tests are `PathLinksTest`, `GetSheetTest` and `OutboxTest` (device) and `GetControllerTest` (JVM), and `FilesFlowTest#t5` through the real machine. Tests: `AnsiTextTest`, `OutputCopyTest`, `OutputFeedTest`, `CopyKindTest`, `CopyNoticeTest`, `AlertClipGuardTest`, `SelectTextCopyTest`, `RetainedTest` (JVM); `CopyClipTest`, `SelectTextTest`, `AgentOutputTest` (device); the Select text steps in `LiveFlowTest`, run by `paddock-harness/run-live-e2e.sh`.
