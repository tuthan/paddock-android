package io.github.tuthan.paddock.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityTest {
    private var next = 1L
    private fun obs(kind: ObservationKind, at: Long, session: String = "s", terminal: String? = null) =
        Observation(next++, "h", session, terminal, 1, kind, at)

    private val action = PhoneAction(100, "h", "s", "t1", 1, ActionKind.MarkSeen, at = 50, outcome = ActionOutcome.Ok)

    @Test fun aGapRunsFromDisconnectedToTheNextConnected() {
        val gaps = Activity.gaps(listOf(obs(ObservationKind.Disconnected, 10), obs(ObservationKind.Connected, 25)))
        assertEquals(listOf(ActivityItem.Gap(10, 25)), gaps)
    }

    @Test fun anOpenDisconnectIsAnOngoingGap() {
        val gap = Activity.gaps(listOf(obs(ObservationKind.Disconnected, 10))).single()
        assertEquals(10L, gap.from)
        assertNull(gap.to)
    }

    @Test fun gapsAreTrackedPerSession() {
        val gaps = Activity.gaps(
            listOf(
                obs(ObservationKind.Disconnected, 10, "a"),
                obs(ObservationKind.Connected, 20, "b"), // b was never disconnected: closes nothing in a
                obs(ObservationKind.Connected, 30, "a"),
            ),
        )
        assertEquals(listOf(ActivityItem.Gap(10, 30)), gaps)
    }

    @Test fun aRepeatedDisconnectDoesNotRestartTheGap() {
        val gaps = Activity.gaps(
            listOf(obs(ObservationKind.Disconnected, 10), obs(ObservationKind.Disconnected, 15), obs(ObservationKind.Connected, 30)),
        )
        assertEquals(listOf(ActivityItem.Gap(10, 30)), gaps)
    }

    @Test fun allShowsEverythingNewestFirst() {
        val items = Activity.build(
            listOf(obs(ObservationKind.StateChanged, 20, terminal = "t1"), obs(ObservationKind.Disconnected, 60)),
            listOf(action),
            ActivityFilter.All,
        )
        assertEquals(listOf(60L, 60L, 50L, 20L), items.map { it.at })
        assertTrue(items.any { it is ActivityItem.Gap })
    }

    @Test fun filtersSelectTheirKindsOnly() {
        val o = listOf(
            obs(ObservationKind.StateChanged, 20, terminal = "t1"),
            obs(ObservationKind.Disconnected, 30),
            obs(ObservationKind.Connected, 40),
        )
        assertTrue(Activity.build(o, listOf(action), ActivityFilter.StateChanges).all { it is ActivityItem.Observed })
        assertEquals(1, Activity.build(o, listOf(action), ActivityFilter.StateChanges).size)
        // Connection: the two events and the gap between them; no state change, no action.
        assertEquals(3, Activity.build(o, listOf(action), ActivityFilter.Connection).size)
        assertEquals(listOf<ActivityItem>(ActivityItem.Acted(action)), Activity.build(o, listOf(action), ActivityFilter.PhoneActions))
    }
}
