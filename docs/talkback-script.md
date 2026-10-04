# TalkBack script (AC-04.6)

Run on a physical phone with TalkBack on, against a machine with at least one Blocked, one Done and one Working agent. Record pass or fail per line in the Phase 04 evidence report. The automated tests assert the semantics this script listens for (merged descriptions, roles, states, 48 dp targets), and `SemanticsAudit` runs over every screen in both themes and at 200 % font on 360 × 640 dp (`accessibility.md`); only a person with the real screen reader can confirm how they sound. Lines 14 to 21 cover what Phases 07 to 12 added.

Expected order for every agent row: **state word, title, context, observed age** ("Blocked, approve edit to build.gradle, api, observed 40 seconds ago"). Colour never carries state; the word does.

| # | Do | Expect TalkBack to say |
| --- | --- | --- |
| 1 | Open the app on Home. Swipe right from the top. | The summary as a heading ("1 needs you, 1 done, …"), then the host chip ("Laptop, live, 40 s"), then section headings in order: Needs you, Done, Working, Ready, Unknown. |
| 2 | Swipe to each agent row. | One announcement per row in the order above, then "double tap to activate". No separate announcement for the dot or the age. |
| 3 | Pull the host's network (airplane mode). | The banner reads as a message with its reason; rows are announced dimmed with their age and **no** "double tap to activate". The recovery action, if shown, is a button. |
| 4 | Restore the network, open a Blocked row. | The agent's title as a heading, the state and age as one item, "Output" selected and "Terminal" not selected, the output region as "Terminal output", then the Following chip ("Following the newest output"). |
| 5 | Scroll the output up with two fingers. | The chip becomes "Output is paused. Tap to follow the newest output" and is a button. Double tap resumes. |
| 6 | Swipe to the key strip. | One item, "Keys, unavailable. Keys arrive in Phase 06". Not actionable. |
| 7 | Go back. Open Activity. | "Activity" heading; filter chips each announced with selected state; rows read as time then text ("14:50, approve edit to build.gradle: working to blocked"). |
| 8 | Open Settings. | Rows read "label, value, detail". Notification rows end with "unavailable". "Protect sensitive screens" is a switch announced "On" or "Off". |
| 9 | Open Add machine. Swipe through the fields. | Each field announces its label; on an invalid Connect each error is announced with its field. The key cards announce as radio buttons with selected state. |
| 10 | Connect to a new machine. | The fingerprint dialog opens with focus on **Cancel**; the title, key type, fingerprint and compare command each read as "label: value". "Trust and connect" is a separate button, never the default. |
| 11 | Change the host key on the machine and reconnect. | Home shows the banner "The host's key changed. Nothing was signed in." with "Review the key". The dialog opens with focus on **Keep the old key**; "Replace with the new key" is a separate button. |
| 12 | On Android 17, add a LAN address. | The reason text is read before Connect; the system dialog follows; after a denial the recovery row reads as a message with "Open settings" as a button. |
| 12b | On Android 17, with the grant denied, tap the recovery row's **Open settings**; under Permissions > Nearby devices choose Allow; go back. | The row opens the app's own settings page; after Back the recovery row has gone without restarting the app, and Connect goes on. Then choose Don't allow on that page: the app closes, and on the next launch Home shows "Local-network access is off" with **Open settings** as a button. (The emulator run covers this on API 37; an OEM's settings pages differ.) |
| 13 | Terminal tab, in control. Swipe to the key strip: row 1, then row 2. Double tap "Shift, for the next key", then "Tab". | Row 1 reads Escape, Enter, the arrows, Tab, Control C. Row 2 reads three switches ("Control, for the next key", "Alt, …", "Shift, …", each "off") then Home, End, Page up, Page down, Insert, Delete forward, F1 to F12. After the double tap the switch reads "armed"; after Tab it reads "off" again, and the terminal received Shift+Tab (a shell menu goes back one entry). |
| 14 | Spaces tab (Phase 09). Swipe through the session rows. | Each row reads the session name, running or stopped, and for a stopped one the saved layout with its date ("Saved layout: repo, notes · file dated 14:02") or "saved contents unavailable". **Stop…** and **Delete…** are buttons. The default session's Delete is announced as unavailable, with its reason on screen beside it. |
| 15 | Activate **Stop…** on a running session. | A dialog that reads the title naming the session, then "On Laptop." and the facts "Session: main", "Machine: Laptop"; focus is on **Cancel**; the confirm is a separate button and is never focused first. Cancel closes it and nothing is sent. |
| 16 | Activate **Start an agent…**. Swipe through the form. | Each field announces its label; the workspaces and the agent kinds announce as radio buttons with selected state; "Start in a new worktree" announces as a switch, on or off, with its explanation; **Start agent** is unavailable, not hidden, until the name (and the branch, with a worktree) is valid, and a problem reads with its field. A recovery card reads "Recovery card" and its title, then what happened, the ids as facts, then its buttons (the ones offered for that failure: **Open the pane**, **Choose another name**, **Trust this repository…**, **Start anyway**, a Danger **Close …**, or a Secondary leave action). |
| 17 | On Home, long-press an agent row (or use TalkBack's actions menu on it). | The actions list offers "More actions for <title>"; the dialog reads the agent's title, a one-line explanation, then **Rename agent…**, **Show its workspace on the desktop**, **Show its tab on the desktop** and Cancel. Rename opens a field labelled Name with the current name filled in, and **Clear the name** as a separate button. |
| 18 | Blocked agent with a permission request: open the Decision sheet from Output. | "Permission request" as the heading, the agent's title, chips for the tool, mode, request number and time left (read as one phrase each), the request as a scrolling region read in full, then **Yes** and **No** (announced unavailable, with the one-sentence reason, when the request expired or was replaced), **Answer in the terminal instead**, and the fact that Yes or No answers this request and nothing else. At 200% font the whole sheet scrolls and the request is still on screen above Yes. |
| 19 | Home with the host's network cut (airplane mode), then Settings > Locked-phone alerts and Guarded answers. | Stale rows read "was blocked" or "was working" with their age and are not actionable. The two setup screens read the file's SHA-256 as a fact ("SHA-256 of the script: …"), and each command to copy has its own **Copy** button that says what it copies. |
| 20 | Add a Paddock widget to the home screen (4 × 2, 2 × 2, 4 × 1). Focus each. | One announcement per widget: "Paddock, Laptop. 2 need you. 1 done, 2 working, 3 ready. as of 14:02." plus "This read is old." when the read is over 30 minutes old. In the 4 × 2, each row reads "Review approve edit to build.gradle, blocked, seen 13:58" and is a button; double tap opens the app, which reads the machine again first and then shows that agent (the Terminal tab, observing, while it is still blocked) or says what changed; it never opens an answer surface. Before the first read: "Paddock. Open the app to read your herd. No count is shown until it has been read." |
| 21 | Turn on Settings > Accessibility > Remove animations (or set the animation scales to off in developer options). Use Home with a list that changes. | Nothing moves on its own; the herd's rows change place without sliding; the switch thumb jumps. There is no pulsing or breathing dot anywhere. |

Also at 200% font and with the display size at maximum: nothing is cut off, and every action in the list above is still reachable by swipe.

## Accessibility Scanner

Stands in for the automatic checks of `enableAccessibilityChecks()` (the `ui-test-junit4-accessibility` artifact was declined on 2026-10-02, see `docs/dependency-reviews.md`). It is Google's Accessibility Scanner app on the phone, not a project dependency. Turn it on in Settings > Accessibility, then, with the Paddock build running, capture each screen below and open the result. Record per screen: the number of suggestions, and each one by kind (touch target size, text contrast, missing or duplicate label, clickable item).

| # | Screen | Pass when |
| --- | --- | --- |
| A1 | Home with a Blocked, a Done and a Working agent; then degraded (airplane mode) | No suggestion, or each one explained in the report |
| A2 | Agent screen, Output tab and Terminal tab (observing, then in control with the keyboard up) | Same |
| A3 | Composer, Manual input open | Same |
| A4 | Activity, Settings, Add machine, Import key | Same |
| A5 | The fingerprint dialog and the changed-key dialog | Same |

Check at 200% font as well as the default size. The scanner's contrast check reads rendered pixels, which the unit-tested palette ratio (4.5:1) does not: a text colour that passes in the palette can still fail on a tinted row (blocked and done rows are washed 9% and 30%).

