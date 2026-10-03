package io.github.tuthan.paddock.attention

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** AC-12.1 (the captured kinds), AC-12.2 (unique codes) and AC-12.4's data half (which kinds draw a glyph). */
class AgentMarksTest {
    private val fixtures = File(System.getProperty("paddock.repoRoot"), "fixtures/herdr-0.9.1")

    /** `herdr agent start --kind` on 0.9.1, one per line, as `tools/capture-agent-kinds.sh` wrote it. */
    private val startKinds = File(fixtures, "agent-start-kinds.txt").readLines().filter { it.isNotBlank() }

    @Test fun everyKindHerdrCanStartHasItsOwnCode() {
        assertEquals(24, startKinds.size, "the pinned kind list is the 24 of herdr 0.9.1")
        val codes = startKinds.associateWith { AgentMarks.of(it).code }
        assertTrue(startKinds.all { it in AgentMarks.listed }, "a kind herdr starts is missing from the table: ${startKinds.filter { it !in AgentMarks.listed }}")
        assertEquals(codes.size, codes.values.toSet().size, "two startable kinds share a code: ${codes.entries.groupBy({ it.value }, { it.key }).filter { it.value.size > 1 }}")
    }

    @Test fun allTwentySixListedMarksAreDistinctAndTwoCharactersLong() {
        assertEquals(26, AgentMarks.listed.size, "the 24 startable kinds, crush and shell")
        assertTrue("crush" in AgentMarks.listed && "shell" in AgentMarks.listed)
        val byCode = AgentMarks.listed.entries.groupBy({ it.value.code }, { it.key })
        assertTrue(byCode.all { it.value.size == 1 }, "shared codes: ${byCode.filter { it.value.size > 1 }}")
        assertTrue(AgentMarks.listed.values.all { it.code.length == 2 && it.code.all { c -> c in 'a'..'z' } }, "codes are two lower-case letters")
    }

    @Test fun theLetterCollisionsTheOldFirstTwoLettersRuleHadAreGone() {
        assertNotEquals(AgentMarks.of("claude").code, AgentMarks.of("cline").code, "cline used to read as claude's cl")
        assertEquals(3, listOf("kimi", "kilo", "kiro").map { AgentMarks.of(it).code }.toSet().size, "kimi, kilo and kiro were all ki")
        assertNotEquals(AgentMarks.of("maki").code, AgentMarks.of("mastracode").code, "maki and mastracode were both ma")
        assertEquals(listOf("cl", "cn", "km", "kl", "kr", "mk", "mc"), listOf("claude", "cline", "kimi", "kilo", "kiro", "maki", "mastracode").map { AgentMarks.of(it).code })
    }

    @Test fun theCodesTheDesignSheetShows() {
        assertEquals(
            listOf("cl", "cx", "oc", "gm", "cp", "cu", "pi", "am", "hm", "sh"),
            listOf("claude", "codex", "opencode", "gemini", "copilot", "cursor", "pi", "amp", "hermes", "shell").map { AgentMarks.of(it).code },
        )
        assertEquals(
            listOf("om", "dr", "dv", "km", "kl", "kr", "qd", "qw", "lt", "mc", "mk", "ms", "gk", "ag", "cn", "cs"),
            listOf("omp", "droid", "devin", "kimi", "kilo", "kiro", "qodercli", "qwen", "letta", "mastracode", "maki", "muse", "grok", "agy", "cline", "crush").map { AgentMarks.of(it).code },
        )
    }

    @Test fun exactlyTheTenCommonKindsDrawAGlyph() {
        val glyphs = AgentMarks.listed.filterValues { it.glyph != null }
        assertEquals(setOf("claude", "codex", "opencode", "gemini", "copilot", "cursor", "pi", "amp", "hermes", "shell"), glyphs.keys)
        assertEquals(AgentGlyph.entries.toSet(), glyphs.values.map { it.glyph }.toSet(), "one glyph per common kind, none shared")
    }

    @Test fun spellingAndCaseDoNotMatter() {
        assertEquals(AgentMarks.of("agy"), AgentMarks.of("Antigravity-CLI"))
        assertEquals(AgentMarks.of("agy"), AgentMarks.of("antigravity_cli"))
        assertEquals(AgentMarks.of("claude"), AgentMarks.of("Claude Code"))
        assertEquals(AgentMarks.of("copilot"), AgentMarks.of("GitHub-Copilot"))
        assertEquals(AgentMarks.of("gemini"), AgentMarks.of("GEMINI"))
        assertEquals(AgentMarks.of("gemini"), AgentMarks.of(" Gemini CLI "))
        assertEquals(AgentMarks.of("kiro"), AgentMarks.of("kiro-cli"))
        assertEquals("cx", AgentMarks.of("Codex").code)
    }

    @Test fun noKindOrNothingReadableShowsTwoDots() {
        listOf(null, "", "   ", "—", "--", "\u0000\u0007", "‮​", "\n\t").forEach { assertEquals(AgentMark("··"), AgentMarks.of(it), "kind $it") }
    }

    @Test fun anUnlistedKindShowsItsFirstTwoLettersAndNeverCrashes() {
        assertEquals("fa", AgentMarks.of("fake").code)
        assertEquals(null, AgentMarks.of("fake").glyph)
        assertEquals("co", AgentMarks.of("commandcode").code, "Command Code's string is not captured, so it is not in the table")
        assertEquals("pr", AgentMarks.of("Prime Agent").code)
        assertEquals("x", AgentMarks.of("X").code, "a one-character kind is what it is, never empty")
        assertEquals("ab", AgentMarks.of("\u0000a‮b\u0007c").code, "control and reordering characters are dropped before the letters are taken")
        assertTrue(AgentMarks.of("a".repeat(100_000)).code.length == 2)
    }

    @Test fun theMarkDoesNotDependOnWhatWasCapturedBeingTheKindName() {
        // `tools/capture-agent-kinds.sh`: for 21 of the 24 kinds `agent.get` returned the kind itself; cline was detected but never ready.
        val captured = File(fixtures, "agent-kind-strings.tsv").readLines().filter { it.isNotBlank() }.map { it.split('\t') }
        assertEquals(startKinds, captured.map { it[0] }, "one row per startable kind, in herdr's order")
        val strings = captured.filter { it[1] != "not-captured" }
        assertEquals(22, strings.size, "21 detected and ready, cline detected but never ready; kiro and letta not captured")
        assertEquals(listOf("kiro", "letta"), captured.filter { it[1] == "not-captured" }.map { it[0] })
        strings.forEach { (kind, seen) -> assertEquals(AgentMarks.of(kind), AgentMarks.of(seen), "$kind is shown as $seen on the wire") }
        assertEquals(listOf("cline"), strings.filter { it.size > 2 && it[2] == "start-timeout" }.map { it[0] })
    }
}
