package io.github.tuthan.paddock.files

import java.time.LocalDateTime
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FileNamesTest {
    private val now = LocalDateTime.of(2026, 10, 11, 14, 32, 5)
    private val stamp = "2026-10-11-143205-"

    /** The host script's allowlist (SEC-4): a name from here must always pass it. */
    private val scriptRule = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")

    private fun accepted(shared: String) = assertIs<DropName.Accepted>(FileNames.dropName(shared))

    @Test fun anInboxNameIsTheTimeThenTheReducedNameWithItsExtension() {
        assertEquals("2026-10-11-143205-screenshot.png", FileNames.inboxName(now, "screenshot.png"))
        assertEquals("${stamp}Screenshot_2026-10-11_at_14.32.05.png", FileNames.inboxName(now, "Screenshot 2026-10-11 at 14.32.05.png"))
        assertEquals("${stamp}archive.tar.gz", FileNames.inboxName(now, "archive.tar.gz"))
    }

    @Test fun anInboxNameWithNothingLeftIsFile() {
        for (original in listOf("", "   ", "🙂🙂", ".", "..", "...", "-", "_", "‮​")) {
            assertEquals("${stamp}file", FileNames.inboxName(now, original), "for a name that reduces to nothing")
        }
    }

    @Test fun aNameThatIsAPathKeepsOnlyItsLastPart() {
        assertEquals("${stamp}passwd", FileNames.inboxName(now, "../../etc/passwd"))
        assertEquals("${stamp}c.png", FileNames.inboxName(now, "a/b\\c.png"))
        assertEquals("${stamp}file", FileNames.inboxName(now, "dir/"))
    }

    @Test fun aLeadingDotHyphenOrUnderscoreIsRemovedSoNothingIsHiddenAndNothingIsAnOption() {
        assertEquals("${stamp}hidden", FileNames.inboxName(now, ".hidden"))
        assertEquals("bashrc", accepted(".bashrc").name)
        assertEquals("rf", accepted("-rf").name)
        assertEquals("x.txt", accepted("_x.txt").name)
        assertEquals("1_.png", accepted("(1).png").name)
        assertEquals("file", accepted("..").name)
        assertEquals("a.b", accepted("a.b.").name)
    }

    @Test fun controlAndInvisibleCharactersBecomeOneUnderscoreEach() {
        assertEquals("${stamp}a_b.png", FileNames.inboxName(now, "a\nb.png"))
        assertEquals("${stamp}a_b.png", FileNames.inboxName(now, "a‮​\u0000b.png"))
        assertEquals("${stamp}caf_.png", FileNames.inboxName(now, "café.png"))
    }

    @Test fun anInboxNameIsAtMostAHundredCharactersAndKeepsItsExtension() {
        val name = FileNames.inboxName(now, "a".repeat(300) + ".png")
        assertEquals(100, name.length)
        assertTrue(name.startsWith(stamp))
        assertTrue(name.endsWith("aaa.png"))
        // An extension too long to be one is cut like the rest.
        assertEquals(100, FileNames.inboxName(now, "a." + "b".repeat(300)).length)
    }

    @Test fun retryNamesGoBeforeTheExtension() {
        assertEquals("a-2.png", FileNames.retryName("a.png", 2))
        assertEquals("a-3", FileNames.retryName("a", 3))
        assertEquals("archive.tar-4.gz", FileNames.retryName("archive.tar.gz", 4))
        assertEquals("${stamp}shot-2.png", FileNames.retryName("${stamp}shot.png", 2))
        assertFailsWith<IllegalArgumentException> { FileNames.retryName("a.png", 1) }
    }

    @Test fun aRetryNameStaysWithinTheScriptsLimit() {
        val longest = FileNames.inboxName(now, "a".repeat(300) + ".png")
        assertEquals(100, longest.length)
        for (n in listOf(2, 9, 10, 99, 12345)) {
            val retry = FileNames.retryName(longest, n)
            assertTrue(retry.length <= 100 && retry.endsWith("-$n.png"), "retry $n")
            assertTrue(scriptRule.matches(retry))
        }
    }

    @Test fun reduceSaysWhetherTheNameChanged() {
        assertEquals(Reduced("report.pdf", false), FileNames.reduce("report.pdf"))
        assertEquals(Reduced("my_report.pdf", true), FileNames.reduce("my report.pdf"))
        assertEquals(Reduced("passwd", true), FileNames.reduce("../passwd"))
        assertEquals(Reduced("file", true), FileNames.reduce(""))
        assertEquals(Reduced("a_b", true), FileNames.reduce("a  \t b"))
    }

    @Test fun aDropNameIsReducedAndSaysSo() {
        assertEquals(DropName.Accepted("report.pdf", false), FileNames.dropName("report.pdf"))
        assertEquals(DropName.Accepted("my_report_1_.pdf", true), FileNames.dropName("my report (1).pdf"))
        assertEquals(DropName.Accepted("file", true), FileNames.dropName("🙂"))
        assertEquals(DropName.Accepted("a".repeat(96) + ".txt", true), FileNames.dropName("a".repeat(150) + ".txt"))
        assertEquals(100, accepted("a".repeat(150) + ".txt").name.length)
    }

    @Test fun everyInstructionNameIsRefusedInAnyCase() {
        assertEquals(8, FileNames.INSTRUCTION_NAMES.size)
        for (listed in FileNames.INSTRUCTION_NAMES) {
            for (variant in listOf(listed, listed.uppercase(), listed.lowercase(), listed.replaceFirstChar { it.lowercase() })) {
                assertEquals(DropName.Refused(listed), FileNames.dropName(variant), "refusing $listed")
            }
        }
    }

    @Test fun anInstructionNameIsRefusedAsReceivedAndAsReduced() {
        // `.mcp.json` loses its dot in reduction, so only the comparison on the name as received catches it.
        assertEquals(DropName.Refused(".mcp.json"), FileNames.dropName(".mcp.json"))
        assertEquals(DropName.Refused(".mcp.json"), FileNames.dropName(".MCP.JSON"))
        assertEquals(DropName.Refused(".mcp.json"), FileNames.dropName("sub/dir/.mcp.json"))
        // These reduce to a listed name; they are not listed as received.
        assertEquals(DropName.Refused("CLAUDE.md"), FileNames.dropName("CLAUDE.md."))
        assertEquals(DropName.Refused("CLAUDE.md"), FileNames.dropName(" CLAUDE.md"))
        assertEquals(DropName.Refused("CLAUDE.md"), FileNames.dropName("__CLAUDE.md"))
        assertEquals(DropName.Refused("AGENTS.md"), FileNames.dropName("..\\AGENTS.md"))
        assertEquals(DropName.Refused("settings.json"), FileNames.dropName("../.claude/settings.json"))
        assertEquals(DropName.Refused("codex.md"), FileNames.dropName("‮CODEX.MD"))
    }

    @Test fun aNameNextToAnInstructionNameIsNotRefused() {
        for (name in listOf("CLAUDE.md.txt", "MYCLAUDE.md", "CLAUDE.mdx", "my-settings.json", "mcp.json.bak", "opencode.jsonc", "AGENTS.md_")) {
            assertIs<DropName.Accepted>(FileNames.dropName(name), name)
        }
    }

    @Test fun aCachedNameIsReducedAndNeverHidden() {
        assertEquals("report.pdf", FileNames.cachedName("report.pdf"))
        assertEquals("passwd", FileNames.cachedName("../../etc/passwd"))
        assertEquals("ssh", FileNames.cachedName(".ssh"))
        assertEquals("file", FileNames.cachedName(""))
        assertEquals("a_b.log", FileNames.cachedName("a‮\nb.log"))
        assertEquals(100, FileNames.cachedName("x".repeat(500)).length)
        assertTrue(FileNames.cachedName("x".repeat(500) + ".apk").endsWith(".apk"))
    }

    @Test fun aCachedFileStaysInsideItsFolder() {
        val folder = "/data/cache/transfers/m1/3f2a"
        assertTrue(FileNames.containedIn(folder, "report.pdf"))
        assertTrue(FileNames.containedIn(folder + "/", "report.pdf"))
        assertTrue(FileNames.containedIn("/", "report.pdf"))
        assertTrue(FileNames.containedIn(folder + "/./x/..", "report.pdf"))
        for (name in listOf("", ".", "..", "../x", "a/b", "/etc/passwd", "a/../../b", "a\u0000b", "../3f2a/x")) {
            assertFalse(FileNames.containedIn(folder, name), "name \"${name.replace("\u0000", "<NUL>")}\"")
        }
        assertFalse(FileNames.containedIn("relative/folder", "x"))
        assertFalse(FileNames.containedIn("/../x", "x"))
    }

    @Test fun noNameFromHereEverBreaksTheScriptsRule() {
        val rnd = Random(20261011)
        val alphabet = "abcXYZ019._-/\\ \t\n\u0000‮​é🙂󠁁..--__".toList()
        repeat(4_000) {
            val original = buildString { repeat(rnd.nextInt(0, 160)) { append(alphabet[rnd.nextInt(alphabet.size)]) } }
            val inbox = FileNames.inboxName(now, original)
            assertTrue(inbox.length <= 100 && scriptRule.matches(inbox), "inbox name for seed input $it")
            val cached = FileNames.cachedName(original)
            assertTrue(scriptRule.matches(cached), "cached name for seed input $it")
            val drop = FileNames.dropName(original)
            if (drop is DropName.Accepted) {
                assertTrue(scriptRule.matches(drop.name), "drop name for seed input $it")
                assertEquals(drop.name != original, drop.changed)
                assertTrue(FileNames.INSTRUCTION_NAMES.none { it.equals(drop.name, ignoreCase = true) })
                assertTrue(scriptRule.matches(FileNames.retryName(drop.name, 2)))
            }
        }
    }
}
