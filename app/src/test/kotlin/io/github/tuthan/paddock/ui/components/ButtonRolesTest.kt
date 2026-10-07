package io.github.tuthan.paddock.ui.components

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The button roles of docs/buttons.md, read from the source: a button's kind follows what it means (Secondary for a neutral or safe
 * action, Danger for stop or forget, and so on), so the same label never looks different on two screens. A source scan is crude, and
 * it is meant to be: it fails the moment someone makes Cancel or Release a Ghost again, which a screenshot would not.
 */
class ButtonRolesTest {
    private val main = File("src/main/kotlin")

    private data class Call(val file: String, val labels: Set<String>, val kind: String, val args: String = "")

    /** Every PaddockButton( call: the string literals in it and its kind (Primary when none is given; null when the kind is chosen at run time). */
    private fun calls(): List<Call> {
        val out = mutableListOf<Call>()
        main.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            var from = 0
            while (true) {
                val at = text.indexOf("PaddockButton(", from); if (at < 0) break
                from = at + 1
                if (text.substring((at - 4).coerceAtLeast(0), at) == "fun ") continue
                var depth = 0; var i = at + "PaddockButton".length; var inString = false; val literals = mutableListOf<String>(); var cur = StringBuilder()
                val start = i
                while (i < text.length) {
                    val ch = text[i]
                    if (inString) {
                        if (ch == '\\') { cur.append(text[i + 1]); i += 2; continue }
                        if (ch == '"') { inString = false; literals += cur.toString(); cur = StringBuilder() } else cur.append(ch)
                    } else when (ch) {
                        '"' -> inString = true
                        '(' -> depth++
                        ')' -> { depth--; if (depth == 0) break }
                    }
                    i++
                }
                val args = text.substring(start, i)
                val kind = Regex("""kind\s*=\s*(if\s*\(|ButtonKind\.(\w+))""").find(args)?.let { m -> if (m.groupValues[2].isEmpty()) null else m.groupValues[2] } ?: if ("kind =" in args) "dynamic" else "Primary"
                out += Call(f.name, literals.toSet(), kind, args)
            }
        }
        return out
    }

    private val secondary = listOf(
        "Cancel", "Back", "Back to the herd", "Close", "Not now", "Done", "Dismiss", "Keyboard", "Hide keyboard", "Release", "Resize to fit",
        "Request control", "Edit", "Move up", "Copy key only", "Show as QR", "Check again", "Keep the old key", "Copy", "Share", "Restart", "Leave it",
        "Restore purchase", "Tip \${tip.price}", "Watch", "Save name", "Send a test alert", "Copy the subscribe link", "Copy the commands",
    )
    private val danger = listOf("Esc", "Ctrl+C", "Esc · Interrupt", "Remove", "Reset the record…", "Unregister", "Replace with the new key", "Stop…", "Delete…", "Remove…", "Turn off alerts…")
    private val primary = listOf("Connect", "Send prompt", "Import key", "Yes", "Review prompt", "Install the relay", "Start an agent…", "Start agent", "Start with this name", "Rename", "Buy Pro",
        "Enter the address", "Allow local-network access", "Allow the camera", "Search again", "Turn on alerts…", "Update alerts…", "Open in the ntfy app")
    private val ghost = listOf("Re-read", "Try again", "Check the setup", "Open terminal", "Manual input", "Focus on desktop", "Trust and connect", "Answer in the terminal instead", "Rename agent…", "Show its workspace on the desktop",
        "Show its tab on the desktop", "Open the pane", "Choose another name", "Trust this repository…", "Start anyway", "Open it", "Clear the name",
        "Find on this network", "Scan the code on the desktop", "Paste a pairing link", "Wake the machine", "Add another machine")

    private fun check(role: String, labels: List<String>, found: List<Call>) {
        val wrong = found.filter { c -> c.labels.any { it in labels } && c.kind != role }
        assertTrue("these buttons are not $role: ${wrong.map { "${it.file} ${it.labels} is ${it.kind}" }}", wrong.isEmpty())
    }

    @Test fun theScanSeesTheButtonsItChecks() {
        val found = calls()
        assertTrue("found only ${found.size} PaddockButton calls; the scan is broken", found.size > 50)
        val seen = found.flatMap { it.labels }.toSet()
        for (label in listOf("Release", "Keyboard", "Cancel", "Esc", "Connect", "Re-read", "Stop…", "Delete…", "Restart", "Start agent")) assertTrue("no button labelled $label found", label in seen)
    }

    @Test fun neutralAndSafeActionsAreSecondary() = check("Secondary", secondary, calls())
    @Test fun interruptStopAndForgetAreDanger() = check("Danger", danger, calls())
    @Test fun theOneThingAScreenIsForIsPrimary() = check("Primary", primary, calls())
    @Test fun openInspectAndFallBackAreGhost() = check("Ghost", ghost, calls())

    /**
     * Decision D6: a locked button says Pro with `pro =` (the lock glyph, the tag, and "<label> · Pro" for TalkBack), never with " · Pro" in its
     * label, and keeps its kind. Spaces' three Pro buttons are pinned by label; the kind checks above still find them by the plain literal.
     */
    @Test fun aLockedButtonSaysProWithItsTagAndNeverInItsLabel() {
        val found = calls()
        val suffixed = found.filter { c -> c.labels.any { "· Pro" in it } }
        assertTrue("a button label carries \" · Pro\"; pass pro = instead: ${suffixed.map { "${it.file} ${it.labels}" }}", suffixed.isEmpty())
        for (label in listOf("Start an agent…", "Stop…", "Delete…")) {
            val spaces = found.filter { it.file == "Spaces.kt" && label in it.labels }
            assertTrue("no Spaces button labelled $label found", spaces.isNotEmpty())
            for (c in spaces) {
                assertTrue("Spaces' $label does not say Pro with pro =: ${c.args}", Regex("""\bpro\s*=""").containsMatchIn(c.args))
                assertTrue("Spaces' $label still builds its label with proLabel: ${c.args}", "proLabel(" !in c.args)
            }
        }
    }

    /**
     * The Machines screen and a machine's page (decision D3): Watch is Secondary and says Pro with `pro =` (the list's card and the page each have
     * one), Remove… is Danger and only on the page, Add another machine is Ghost. A card is a clickable row, never a PaddockButton.
     */
    @Test fun theMachinesButtonsHaveTheirKindsAndWatchSaysProWithItsTag() {
        val machines = calls().filter { it.file == "Machines.kt" }
        val watch = machines.filter { "Watch" in it.labels }
        assertTrue("expected Watch on the card and on the page, found ${watch.size}", watch.size == 2)
        for (c in watch) {
            assertTrue("Machines' Watch is not Secondary: ${c.kind}", c.kind == "Secondary")
            assertTrue("Machines' Watch does not say Pro with pro =: ${c.args}", Regex("""\bpro\s*=""").containsMatchIn(c.args))
            assertTrue("Machines' Watch builds its label with proLabel: ${c.args}", "proLabel(" !in c.args)
        }
        val remove = machines.filter { "Remove…" in it.labels }
        assertTrue("expected one Remove… (on the page), found ${remove.size}", remove.size == 1 && remove.single().kind == "Danger")
        assertTrue("Add another machine is not Ghost", machines.filter { "Add another machine" in it.labels }.map { it.kind } == listOf("Ghost"))
        assertTrue("a Machines button has no label: the cards are rows, not buttons", machines.none { it.labels.isEmpty() })
    }

    @Test fun theTerminalControlsAreOneKindSoTheRowReadsAsOne() {
        val terminal = calls().filter { it.file == "TerminalTab.kt" && it.labels.any { l -> l in listOf("Keyboard", "Release", "Resize to fit", "Request control") } }
        assertTrue("expected the four terminal controls, found ${terminal.size}", terminal.size == 4)
        assertTrue("the terminal controls differ: ${terminal.map { it.labels to it.kind }}", terminal.map { it.kind }.toSet() == setOf("Secondary"))
    }
}
