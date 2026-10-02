package io.github.tuthan.paddock.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.tuthan.paddock.ops.OperationKind
import io.github.tuthan.paddock.ops.OperationOutcome
import io.github.tuthan.paddock.ops.OperationRecord

class ActivityTest {
    private var next = 1L
    private fun obs(kind: ObservationKind, at: Long, session: String = "s", terminal: String? = null) =
        Observation(next++, "h", session, terminal, 1, kind, at)

    private val action = PhoneAction(100, "h", "s", "t1", 1, ActionKind.MarkSeen, at = 50, outcome = ActionOutcome.Ok)

    @Test fun aGapRunsFromDisconnectedToTheNextConnected() {
        val gaps = Activity.gaps(listOf(obs(ObservationKind.Disconnected, 10), obs(ObservationKind.Connected, 25)))
        assertEquals(listOf(10L to 25L), gaps.map { it.from to it.to })
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
        assertEquals(listOf(10L to 30L), gaps.map { it.from to it.to })
    }

    @Test fun aRepeatedDisconnectDoesNotRestartTheGap() {
        val gaps = Activity.gaps(
            listOf(obs(ObservationKind.Disconnected, 10), obs(ObservationKind.Disconnected, 15), obs(ObservationKind.Connected, 30)),
        )
        assertEquals(listOf(10L to 30L), gaps.map { it.from to it.to })
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

    @Test fun aConnectedAfterAConnectedIsAGapFromTheLastRecordedThingBecauseTheAppStoppedWithoutSayingSo() {
        val gaps = Activity.gaps(listOf(
            obs(ObservationKind.Connected, 10), obs(ObservationKind.StateChanged, 15, terminal = "t1"),
            obs(ObservationKind.Connected, 40),
        ))
        assertEquals(listOf(15L to 40L), gaps.map { it.from to it.to })
    }

    @Test fun theFirstConnectedEverIsNotAGap() {
        assertTrue(Activity.gaps(listOf(obs(ObservationKind.Connected, 10), obs(ObservationKind.StateChanged, 15, terminal = "t1"))).isEmpty())
    }


    private fun op(id: Long, at: Long, kind: OperationKind = OperationKind.Focus, outcome: OperationOutcome = OperationOutcome.Acknowledged) =
        OperationRecord(id, "h", "s", "t1", 1, kind, at, outcome)

    @Test fun journalRowsAppearAsPhoneActionsAndInAllButNotInTheOtherFilters() {
        val ops = listOf(op(1, 60), op(2, 70, OperationKind.Prompt, OperationOutcome.Unknown))
        val observations = listOf(obs(ObservationKind.StateChanged, 55, terminal = "t1"), obs(ObservationKind.Connected, 10))
        fun rows(f: ActivityFilter) = Activity.build(observations, listOf(action), f, ops)
        assertEquals(listOf(ops[1], ops[0]), rows(ActivityFilter.PhoneActions).filterIsInstance<ActivityItem.Operation>().map { it.record })
        assertEquals("the ledger's mark-as-seen is still there beside them", 3, rows(ActivityFilter.PhoneActions).size)
        assertEquals(2, rows(ActivityFilter.All).filterIsInstance<ActivityItem.Operation>().size)
        assertTrue(rows(ActivityFilter.StateChanges).none { it is ActivityItem.Operation })
        assertTrue(rows(ActivityFilter.Connection).none { it is ActivityItem.Operation })
        assertEquals("without a journal there is nothing to add", emptyList<ActivityItem>(), Activity.build(emptyList(), emptyList(), ActivityFilter.PhoneActions))
    }

    @Test fun journalRowsSortAmongTheOthersByWhenTheUserAsked() {
        val items = Activity.build(listOf(obs(ObservationKind.StateChanged, 55, terminal = "t1")), listOf(action), ActivityFilter.All, listOf(op(1, 60), op(2, 40)))
        assertEquals(listOf(60L, 55L, 50L, 40L), items.map { it.at })
    }
}
