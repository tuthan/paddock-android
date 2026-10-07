package io.github.tuthan.paddock.probe

import io.github.tuthan.paddock.hostprofile.MachineCopy
import io.github.tuthan.paddock.hostprofile.MachineRoster
import io.github.tuthan.paddock.hostprofile.MachineRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProbeCopyTest {
    private val now = 1_000_000L
    private fun read(n: Int, secondsAgo: Long) = ProbeReading(n, now - secondsAgo * 1000)

    @Test fun nothingIsSaidBeforeTheFirstLookHasFoundAnything() {
        assertNull(ProbeCopy.glance(null, now))
        assertNull(ProbeCopy.glance(ProbeState(), now))
    }

    @Test fun aCountSaysHowManyNeedYouAndHowOldTheLookIs() {
        val two = ProbeCopy.glance(ProbeState(read(2, 20)), now)!!
        assertEquals("2 need you · 20 s ago", two.text)
        assertEquals("2 agents need you, read 20 s ago", two.spoken)
        assertTrue(two.attention)
        val one = ProbeCopy.glance(ProbeState(read(1, 3)), now)!!
        assertEquals("1 needs you · just now", one.text)
        assertEquals("1 agent needs you, read just now", one.spoken)
        assertEquals("12 need you · 3 min ago", ProbeCopy.glance(ProbeState(read(12, 190)), now)!!.text)
    }

    @Test fun nothingBlockedIsSaidInWordsNotLeftOut() {
        val none = ProbeCopy.glance(ProbeState(read(0, 8)), now)!!
        assertEquals("all clear · 8 s ago", none.text)
        assertEquals("no agent needs you, read 8 s ago", none.spoken)
        assertFalse(none.attention)
    }

    @Test fun aProblemReplacesTheCountAndTheSpokenNameKeepsTheLastReading() {
        val down = ProbeCopy.glance(ProbeState(read(2, 200), ProbeProblem.Unreachable, failures = 2), now)!!
        assertEquals("not reachable", down.text)
        assertEquals("not reachable; last read 3 min ago, 2 agents need you", down.spoken)
        assertFalse(down.attention, "a count that may be old is never drawn as a live warning")
        val never = ProbeCopy.glance(ProbeState(problem = ProbeProblem.NeedsLook, failures = 1), now)!!
        assertEquals("open to check", never.text)
        assertEquals("open to check", never.spoken)
    }

    @Test fun everyProblemHasWordsAndTheyAreDistinct() {
        val words = ProbeProblem.entries.map { it.word }
        assertEquals(words.size, words.toSet().size)
        assertTrue(words.all { it.isNotBlank() && it.length <= 24 }, "a chip is a pill, not a sentence: $words")
    }

    @Test fun theCadenceIsHalfAMinuteAndAFailingMachineIsLookedAtLessAndLessOften() {
        assertEquals(30_000L, ProbeSchedule.nextDelayMillis(null, 0))
        assertEquals(
            listOf(30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L),
            (1..6).map { ProbeSchedule.nextDelayMillis(ProbeProblem.Unreachable, it) },
        )
        for (p in listOf(ProbeProblem.NeedsLook, ProbeProblem.NoHerdr, ProbeProblem.NoSession)) assertEquals(ProbeSchedule.SLOWEST_MILLIS, ProbeSchedule.nextDelayMillis(p, 1), "$p")
        assertEquals(2, ProbeSchedule.PARALLEL)
        assertTrue(ProbeSchedule.LOOK_TIMEOUT_MILLIS < ProbeSchedule.INTERVAL_MILLIS, "a look ends before the next one is due")
    }

    // ---- which machines are looked at ------------------------------------------------------------------------------------------------

    private val saved = listOf(
        io.github.tuthan.paddock.hostprofile.HostProfile("a", "Alpha", "10.0.0.1", 22, "jdoe"),
        io.github.tuthan.paddock.hostprofile.HostProfile("b", "Beta", "10.0.0.2", 22, "jdoe"),
        io.github.tuthan.paddock.hostprofile.HostProfile("c", "Gamma", "10.0.0.3", 22, "jdoe"),
    )

    @Test fun everyMachineButTheWatchedOneIsLookedAtWhileInFrontUnlockedAndWithPro() {
        assertEquals(listOf("b", "c"), ProbeTargets.of(inFront = true, covered = false, locked = false, machines = saved, watchedId = "a").map { it.id })
        assertEquals(listOf("a", "b", "c"), ProbeTargets.of(true, false, false, saved, watchedId = null).map { it.id })
    }

    @Test fun nothingIsLookedAtInTheBackgroundUnderTheLockOrWithoutPro() {
        for ((front, covered, locked) in listOf(Triple(false, false, false), Triple(true, true, false), Triple(true, false, true), Triple(false, true, true))) {
            assertEquals(emptyList(), ProbeTargets.of(front, covered, locked, saved, "a"), "front=$front covered=$covered locked=$locked")
        }
        assertEquals(emptyList(), ProbeTargets.of(true, false, false, saved.take(1), "a"), "one saved machine has nothing else to look at")
    }

    // ---- the chip row's model --------------------------------------------------------------------------------------------------------

    private val rows = listOf(
        MachineRow("a", "Alpha", "jdoe@a:22", null, watched = true, returnFree = false),
        MachineRow("b", "Beta server", "jdoe@b:22", null, watched = false, returnFree = false),
        MachineRow("c", "Gamma", "jdoe@c:22", null, watched = false, returnFree = false),
    )

    @Test fun onlyMachinesThatAreNotWatchedCarryWhatTheProbeFound() {
        val glance = ProbeCopy.glance(ProbeState(read(2, 20)), now)!!
        val chips = MachineRoster.chips(rows, switchLocked = false, glances = mapOf("a" to glance, "b" to glance))
        assertNull(chips.first { it.id == "a" }.glance, "the watched chip is the live one")
        assertEquals(glance, chips.first { it.id == "b" }.glance)
        assertNull(chips.first { it.id == "c" }.glance)
        assertNull(MachineRoster.chips(rows, switchLocked = false).first { it.id == "b" }.glance)
    }

    @Test fun theSpokenNameOfAnotherMachineGivesWatchedStateThenTheCountThenPro() {
        val glance = ProbeCopy.glance(ProbeState(read(2, 20)), now)!!
        assertEquals("Beta server, not watched", MachineCopy.chipOther("Beta server", locked = false))
        assertEquals("Beta server, not watched, Pro", MachineCopy.chipOther("Beta server", locked = true))
        assertEquals("Beta server, not watched, 2 agents need you, read 20 s ago", MachineCopy.chipOther("Beta server", locked = false, glance = glance))
        assertEquals("Beta server, not watched, 2 agents need you, read 20 s ago, Pro", MachineCopy.chipOther("Beta server", locked = true, glance = glance))
        assertNotNull(glance)
    }
}
