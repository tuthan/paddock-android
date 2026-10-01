package io.github.tuthan.paddock.ledger

import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActivityPresenterTest {
    private fun ms(day: Int, h: Int, m: Int, s: Int = 0) = ZonedDateTime.of(2026, 10, day, h, m, s, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    private val now = ms(1, 15, 0)
    private var id = 0L

    private fun p(titles: Map<String, String> = emptyMap()) =
        ActivityPresenter(ZoneOffset.UTC, Locale.ENGLISH, hostName = { if (it == "laptop") "Laptop" else it }, titleOf = { _, _, t -> titles[t] })

    private fun obs(kind: ObservationKind, at: Long, terminal: String? = null, detail: String = "") =
        ActivityItem.Observed(Observation(++id, "laptop", "main", terminal, 1, kind, at, detail))

    @Test fun groupsByDayWithTodayAndYesterdayNamedAndOlderDatesSpelledOut() {
        val items = listOf(
            obs(ObservationKind.Connected, ms(1, 14, 5)),
            obs(ObservationKind.Connected, ms(1, 9, 0)),
            obs(ObservationKind.Connected, ms(9 - 8, 1, 0).minus(86_400_000)),   // 30 Sep 01:00
            obs(ObservationKind.Connected, ms(1, 0, 0).minus(3 * 86_400_000)),    // 28 Sep
        )
        val sections = p().present(items, now)
        assertEquals(listOf("Today", "Yesterday", "Mon 28 Sep"), sections.map { it.heading })
        assertEquals(listOf("14:05", "09:00"), sections[0].rows.map { it.timeLabel })
    }

    @Test fun stateChangesAreSentencesAboutWhatWasSeenWithTheTransitionAsDetail() {
        val t = mapOf("term_a" to "approve edit to build.gradle")
        fun change(detail: String) = p(t).present(listOf(obs(ObservationKind.StateChanged, ms(1, 14, 0), "term_a", detail)), now).single().rows.single()
        val moved = change("blocked -> working")
        assertEquals("approve edit to build.gradle: blocker no longer observed", moved.text)
        assertEquals("now working", moved.detail)
        assertFalse(moved.text.contains("answered", ignoreCase = true))
        assertEquals("14:00, approve edit to build.gradle: blocker no longer observed, now working", moved.description)
        assertEquals(ActivityTone.Quiet, moved.tone)
        val blocked = change("working -> blocked")
        assertEquals("approve edit to build.gradle needed you" to ActivityTone.NeedsYou, blocked.text to blocked.tone)
        assertEquals("working → blocked", blocked.detail)
        assertEquals("approve edit to build.gradle finished" to ActivityTone.Done, change("working -> done").let { it.text to it.tone })
        assertEquals("approve edit to build.gradle started working" to ActivityTone.Working, change("idle -> working").let { it.text to it.tone })
        assertEquals("approve edit to build.gradle is ready", change("working -> idle").text)
        assertEquals("approve edit to build.gradle changed state", change("odd").text)
    }

    @Test fun anAgentWithNoKnownTitleFallsBackToAShortIdNeverBlank() {
        val row = p().present(listOf(obs(ObservationKind.AgentAppeared, ms(1, 14, 0), "term_65cbe353cc3172")), now).single().rows.single()
        assertEquals("agent cc3172 appeared", row.text)
    }

    @Test fun connectionRowsNameTheHostByItsDisplayName() {
        val rows = p().present(listOf(obs(ObservationKind.Disconnected, ms(1, 14, 0)), obs(ObservationKind.Connected, ms(1, 13, 0))), now).single().rows
        assertEquals(listOf("Lost the connection to Laptop", "Connected to Laptop"), rows.map { it.text })
    }

    @Test fun phoneActionsSayWhatTheUserDidAndHowItEnded() {
        fun act(outcome: ActionOutcome) = ActivityItem.Acted(PhoneAction(++id, "laptop", "main", "term_a", 1, ActionKind.MarkSeen, ms(1, 14, 0), outcome))
        val t = mapOf("term_a" to "write release notes")
        assertEquals("You marked write release notes as seen", p(t).present(listOf(act(ActionOutcome.Ok)), now).single().rows.single().text)
        assertTrue(p(t).present(listOf(act(ActionOutcome.Failed)), now).single().rows.single().text.endsWith("(failed)"))
        assertTrue(p(t).present(listOf(act(ActionOutcome.Unknown)), now).single().rows.single().text.endsWith("(outcome unknown)"))
    }

    @Test fun gapsSayHowLongThePhoneHadNoLinkOrSinceWhenItStillHasNone() {
        val closed = ActivityItem.Gap(ms(1, 14, 0), ms(1, 14, 4), "laptop", "main")
        val open = ActivityItem.Gap(ms(1, 14, 30), null, "laptop", "main")
        val short = ActivityItem.Gap(ms(1, 14, 40), ms(1, 14, 40, 2), "laptop", "main")
        val rows = p().present(listOf(open, short, closed), now).single().rows
        assertEquals("No connection to Laptop since 14:30", rows[0].text)
        assertEquals("No connection to Laptop for a few seconds", rows[1].text)
        assertEquals("No connection to Laptop for 4 min", rows[2].text)
        assertTrue(rows.all { it.kind == ActivityRowKind.Gap && it.tone == ActivityTone.Host })
    }

    @Test fun keysAreStableAndDistinctAcrossKindsAndHosts() {
        val items = listOf(
            obs(ObservationKind.Connected, ms(1, 14, 0)),
            ActivityItem.Acted(PhoneAction(++id, "laptop", "main", "term_a", 1, ActionKind.MarkSeen, ms(1, 13, 0), ActionOutcome.Ok)),
            ActivityItem.Gap(ms(1, 12, 0), null, "laptop", "main"),
            ActivityItem.Gap(ms(1, 12, 0), null, "desk", "main"),
        )
        val keys = p().present(items, now).flatMap { it.rows }.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun noItemsMeansNoSections() = assertTrue(p().present(emptyList(), now).isEmpty())
}
