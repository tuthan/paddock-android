package io.github.tuthan.paddock

import org.junit.Assert.assertEquals
import org.junit.Test

/** X11: the transfer hold keeps the watch while a picker is in front, and never lifts the app lock. */
class WatchActiveTest {
    @Test fun visibleAndUncoveredWatches() = assertEquals(true, watchActive(visible = true, locked = false, held = false))
    @Test fun notVisibleAndNotHeldDoesNotWatch() = assertEquals(false, watchActive(visible = false, locked = false, held = false))
    @Test fun aPickerInFrontKeepsTheWatch() = assertEquals(true, watchActive(visible = false, locked = false, held = true))
    @Test fun theLockCoversEvenAHeldWatch() {
        assertEquals("a hold must never connect behind the lock", false, watchActive(visible = false, locked = true, held = true))
        assertEquals(false, watchActive(visible = true, locked = true, held = true))
        assertEquals(false, watchActive(visible = true, locked = true, held = false))
    }
    @Test fun everyCombinationIsTheRule() {
        for (v in listOf(false, true)) for (l in listOf(false, true)) for (h in listOf(false, true)) {
            assertEquals("visible=$v locked=$l held=$h", (v || h) && !l, watchActive(v, l, h))
        }
    }
}

/** The hold feeds the watch and nothing else: a source scan, in the way `CopyKindTest` pins the clipboard writes. */
class TransferHoldFeedsOnlyTheWatchTest {
    private val main = java.io.File("src/main/kotlin")

    @Test fun inTheGraphTheHoldIsDeclaredAndReadByTheWatchAndNothingElse() {
        val lines = java.io.File(main, "io/github/tuthan/paddock/AppGraph.kt").readLines().filter { "transferHold" in it }
        val other = lines.filterNot { it.trimStart().startsWith("val transferHold") || "::watchActive" in it }
        assertEquals("the app lock's stopwatch, the heartbeat and the rest keep the foreground: $other", emptyList<String>(), other)
        assertEquals(2, lines.size)
    }

    @Test fun onlyTheGraphTheRootAndTheOutputRouteTouchTheHold() {
        // The graph makes it, the root hands it to the composer route (an attach picker) and the output route (Get's Save chooser): nothing else names it.
        val files = main.walkTopDown().filter { it.isFile && it.extension == "kt" && "transferHold" in it.readText() }.map { it.name }.toSet()
        assertEquals(setOf("AppGraph.kt", "PaddockRoot.kt", "OutputRoute.kt"), files)
    }
}

/** The activity-result seam is for tests only: nothing in `src/main` may set it. */
class ActivityResultSeamTest {
    @Test fun nothingInMainSetsTheOverride() {
        val writers = java.io.File("src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .flatMap { f -> f.readLines().withIndex().filter { (_, l) -> Regex("""activityResultsOverride\s*=[^=]""").containsMatchIn(l) && !l.trimStart().startsWith("*") }.map { "${f.name}:${it.index + 1}" } }.toList()
        assertEquals("only an instrumented test sets it: $writers", emptyList<String>(), writers)
    }
}
