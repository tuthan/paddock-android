package io.github.tuthan.paddock.terminal

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * AC-05.2: the five Phase 00 frame fixtures render to the grids written by hand in `resources/terminal/expected/`. The
 * engine is fed one frame at a time exactly as the bridge feeds it; after the named frame its grid, cursor and screen
 * must equal the expected file, line for line.
 */
class FixtureGridsTest {
    private val dir = File(System.getProperty("paddock.repoRoot"), "fixtures/herdr-0.9.1")

    private fun frames(name: String): List<TerminalRecord.Frame> =
        File(dir, "frames-$name.jsonl").readLines().filter { it.isNotBlank() }.map { FrameDecoder.decode(it) as TerminalRecord.Frame }

    private fun expected(file: String): List<String> =
        GridDump.parseExpected(javaClass.getResource("/terminal/expected/$file")!!.readText())

    /** Applies frames the way the bridge does: a full frame clears and sizes the engine first, then every frame is fed. */
    private fun engineAfter(name: String, seq: Int): VtEngine {
        val engine = VtEngine()
        val sequencer = FrameSequencer()
        for (f in frames(name).take(seq)) {
            assertEquals(FrameSequencer.Verdict.Apply, sequencer.accept(f))
            if (f.full) { engine.resize(f.width, f.height); engine.clear() }
            engine.feed(f.bytes)
        }
        return engine
    }

    private fun check(name: String, seq: Int, file: String) {
        val engine = engineAfter(name, seq)
        val actual = GridDump.of(engine)
        val want = expected(file)
        assertEquals(want.joinToString("\n"), actual.joinToString("\n"), "$name after seq $seq against $file")
        assertEquals(0, engine.unhandled, "$name: nothing in a herdr frame is unknown to the engine (${engine.unhandledSamples})")
    }

    @Test fun everyFixtureOpensOnTheSameFullFrame() {
        for (name in listOf("colours", "wide", "cursor", "scroll", "altscreen")) check(name, 1, "initial.grid")
    }

    @Test fun coloursRenderSixteenAndTwoFiftySixColoursBoldAndDim() {
        check("colours", 2, "colours.seq2.grid"); check("colours", 3, "colours.seq3.grid"); check("colours", 4, "colours.seq4.grid")
    }

    @Test fun wideCharactersTakeTwoCellsAtTheColumnsHerdrNames() {
        check("wide", 3, "wide.seq3.grid"); check("wide", 4, "wide.seq4.grid")
    }

    @Test fun cursorMovesAndEraseLandOnTheExpectedCells() {
        check("cursor", 3, "cursor.seq3.grid"); check("cursor", 4, "cursor.seq4.grid")
    }

    @Test fun aScrollIsRepaintedRowByRow() {
        check("scroll", 2, "scroll.seq2.grid"); check("scroll", 3, "scroll.seq3.grid"); check("scroll", 4, "scroll.seq4.grid")
    }

    @Test fun anAlternateScreenArrivesAsARepaintAndTheShellComesBack() {
        check("altscreen", 2, "altscreen.seq2.grid"); check("altscreen", 3, "altscreen.seq3.grid")
        check("altscreen", 4, "altscreen.seq4.grid"); check("altscreen", 5, "altscreen.seq5.grid")
    }

    @Test fun everyExpectedFileIsUsed() {
        val used = setOf("initial.grid") + listOf(
            "colours.seq2", "colours.seq3", "colours.seq4", "wide.seq3", "wide.seq4", "cursor.seq3", "cursor.seq4",
            "scroll.seq2", "scroll.seq3", "scroll.seq4", "altscreen.seq2", "altscreen.seq3", "altscreen.seq4", "altscreen.seq5",
        ).map { "$it.grid" }
        val present = File(System.getProperty("paddock.repoRoot"), "core/src/test/resources/terminal/expected").listFiles()!!.map { it.name }.filter { it.endsWith(".grid") }.toSet()
        assertEquals(used, present, "an expected grid no test reads is a grid nobody checks")
    }

    @Test fun theEngineIsOnlyAsGoodAsTheCheck() {
        // A mutated grid must fail: guard against a comparison that always passes.
        val engine = engineAfter("colours", 4)
        val actual = GridDump.of(engine)
        val broken = expected("colours.seq4.grid").toMutableList()
        broken[broken.indexOfFirst { it.startsWith("style 3 1-6") }] = "style 3 1-6 fg=1"
        assertTrue(actual != broken)
    }
}
