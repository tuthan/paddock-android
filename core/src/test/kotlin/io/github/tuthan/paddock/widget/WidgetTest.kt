package io.github.tuthan.paddock.widget

import io.github.tuthan.paddock.alerts.AlertState
import io.github.tuthan.paddock.alerts.DeepLink
import io.github.tuthan.paddock.alerts.DeepLinkResult
import io.github.tuthan.paddock.attention.AttentionModel
import io.github.tuthan.paddock.attention.SeenLookup
import io.github.tuthan.paddock.herdr.Agent
import io.github.tuthan.paddock.herdr.AgentStatus
import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.relay.FakeSession
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * The widget cache, what each widget says about it, and the background read (Phase 10, AC-10.1). The rule under test is that a count is never
 * shown without the time of the read it comes from, and that nothing here can hold a connection.
 */
class WidgetTest {
    private val host = HostProfileId("laptop")
    private fun ms(day: Int, h: Int, m: Int) = ZonedDateTime.of(2026, 10, day, h, m, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    private val presenter = WidgetPresenter(ZoneOffset.UTC, Locale.ENGLISH)

    private fun agent(pane: String, status: AgentStatus, seq: Long? = null, title: String? = null, kind: String = "claude") =
        Agent(paneId = pane, terminalId = "term_${pane.replace(':', '_')}", workspaceId = "w1", tabId = "w1:t1", agent = kind, agentStatus = status, stateChangeSeq = seq, terminalTitleStripped = title)
    private fun home(vararg a: Agent, seen: SeenLookup = SeenLookup { null }) = AttentionModel.home(Snapshot("0.9.1", 22, agents = a.toList()), ms(3, 14, 2), host, "main", 1, seen = seen)
    private fun cache(vararg a: Agent, readAt: Long = ms(3, 14, 2), seen: SeenLookup = SeenLookup { null }) =
        WidgetCacheBuilder.from(home(*a, seen = seen), "laptop", "Laptop", "main", readAt)

    // ---- a locked widget (vault M8: widgets are Pro) ----------------------------------------------------------------------------

    @Test fun aLockedWidgetDrawsNoCountNoMachineNoRowsAndNoTimeWhateverTheCacheHolds() {
        val c = cache(agent("w1:p1", AgentStatus.Blocked, seq = 5, title = "approve edit"), agent("w1:p2", AgentStatus.Done, seq = 2))
        val locked = presenter.present(c, ms(3, 14, 3), locked = true)
        assertEquals(WidgetPresenter.LOCKED, locked)
        assertFalse(locked.hasData)
        assertNull(locked.count); assertNull(locked.asOf); assertNull(locked.asOfLabel)
        assertTrue(locked.rows.isEmpty() && locked.machine.isEmpty() && locked.detail.isEmpty())
        assertTrue("Pro" in locked.headline && "Pro" in locked.description && "No count" in locked.description, locked.description)
        // Not locked: the same cache is read as before.
        assertTrue(presenter.present(c, ms(3, 14, 3)).hasData)
        assertTrue(presenter.present(c, ms(3, 14, 3), locked = false).hasData)
    }

    @Test fun aLockedWidgetIsLockedEvenWithNoCache() {
        assertEquals(WidgetPresenter.LOCKED, presenter.present(null, ms(3, 14, 3), locked = true))
        assertEquals(WidgetPresenter.NO_DATA, presenter.present(null, ms(3, 14, 3)))
    }

    // ---- the cache --------------------------------------------------------------------------------------------------------

    @Test fun theCacheCountsEachStateAsTheHerdDoesAndKeepsTheTwoMostUrgentBlockedRowsInHerdOrder() {
        val c = cache(
            agent("w1:p1", AgentStatus.Blocked, seq = 5, title = "approve edit to build.gradle"), agent("w1:p2", AgentStatus.Blocked, seq = 9, title = "run migrations"),
            agent("w1:p3", AgentStatus.Blocked, seq = 7, title = "third"), agent("w1:p4", AgentStatus.Done, seq = 2), agent("w1:p5", AgentStatus.Working), agent("w1:p6", AgentStatus.Working),
            agent("w1:p7", AgentStatus.Idle), agent("w1:p8", AgentStatus.Unknown),
        )
        assertEquals(WidgetCounts(needsYou = 3, done = 1, working = 2, ready = 1, unknown = 1), c.counts)
        assertEquals(listOf("run migrations", "third"), c.urgent.map { it.title }, "the most recently changed first, and no more than two")
        assertEquals(listOf(9L, 7L), c.urgent.map { it.seq })
        assertEquals("main", c.session); assertEquals("laptop", c.hostId)
    }

    @Test fun aDoneTheUserAlreadySawIsReadyNotDone() {
        val c = cache(agent("w1:p1", AgentStatus.Done, seq = 4), seen = SeenLookup { 4L })
        assertEquals(WidgetCounts(ready = 1), c.counts)
    }

    @Test fun onlyBlockedAgentsGetARowAndTheTitleIsBounded() {
        val c = cache(agent("w1:p1", AgentStatus.Done, seq = 4, title = "finished"), agent("w1:p2", AgentStatus.Blocked, seq = 1, title = "x".repeat(300)))
        assertEquals(1, c.urgent.size)
        assertEquals(WidgetCache.MAX_TITLE, c.urgent.single().title.length)
    }

    @Test fun theFileStoreRoundTripsAtomicallyAndAnUnreadableFileIsSetAsideAndReadsAsNothing() {
        val dir = createTempDirectory("widget").toFile()
        try {
            val store = FileWidgetCacheStore(File(dir, "widget-cache.json"))
            assertNull(store.load(), "no file, no cache")
            val c = cache(agent("w1:p1", AgentStatus.Blocked, seq = 1, title = "t"))
            store.save(c)
            assertEquals(c, store.load())
            assertFalse(File(dir, "widget-cache.json.tmp").exists(), "the temp file is gone after the rename")
            File(dir, "widget-cache.json").writeText("{ not json")
            assertNull(store.load()); assertTrue(File(dir, "widget-cache.json.corrupt").exists(), "kept aside, not wiped")
            File(dir, "widget-cache.json").writeText("""{"version":99,"hostId":"laptop","machine":"L","session":"main","readAtMillis":1,"counts":{}}""")
            assertNull(store.load(), "a version this build does not know is no cache")
        } finally { dir.deleteRecursively() }
    }

    @Test fun theCacheHoldsNoPromptOutputOrPathBeyondTheCleanedTitle() {
        val dir = createTempDirectory("widget").toFile()
        try {
            val file = File(dir, "widget-cache.json")
            FileWidgetCacheStore(file).save(cache(agent("w1:p1", AgentStatus.Blocked, seq = 1, title = "fix\u001b[31m the build\u202E")))
            val text = file.readText()
            assertFalse("\u001b" in text || "\u202E" in text, "controls and direction marks never reach the file")
            assertEquals(setOf("version", "hostId", "machine", "session", "readAtMillis", "counts", "urgent"), (kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject).keys)
        } finally { dir.deleteRecursively() }
    }

    // ---- what the widgets say ---------------------------------------------------------------------------------------------

    @Test fun withDataEveryWidgetCarriesItsTimeAndWithoutDataThereIsNoNumberAtAll() {
        val now = ms(3, 14, 10)
        val some = presenter.present(cache(agent("w1:p1", AgentStatus.Blocked, seq = 1, title = "approve")), now)
        assertTrue(some.hasData); assertEquals("as of 14:02", some.asOf); assertEquals("1", some.count)
        assertTrue("as of 14:02" in some.description, "TalkBack hears the time with the count")
        val none = presenter.present(null, now)
        assertFalse(none.hasData); assertNull(none.asOf); assertNull(none.count); assertEquals("", none.detail); assertTrue(none.rows.isEmpty())
        assertFalse(none.headline.any { it.isDigit() } || none.description.any { it.isDigit() }, "no digit without a read to put it on")
    }

    @Test fun aReadFromAnotherDayNamesTheDayAndAnHourOldReadIsMarkedStaleWithItsTimeStillShown() {
        val c = cache(agent("w1:p1", AgentStatus.Blocked, seq = 1, title = "approve"), readAt = ms(3, 14, 2))
        assertEquals("as of Sat 14:02", presenter.present(c, ms(4, 9, 0)).asOf, "2026-10-03 is a Saturday")
        val fresh = presenter.present(c, ms(3, 14, 20)); assertFalse(fresh.stale)
        val old = presenter.present(c, ms(3, 15, 5))
        assertTrue(old.stale); assertEquals("as of 14:02", old.asOf); assertEquals("as of 14:02 · old", old.asOfLabel); assertTrue("This read is old." in old.description)
        assertEquals("as of 14:02", fresh.asOfLabel)
        assertTrue(presenter.present(c, ms(3, 14, 2) - 10 * 60_000).stale, "a read from the future (a clock that moved) is not trusted either")
    }

    @Test fun theHeadlineAndDetailSayWhatNeedsYouAndWhatElseThereIs() {
        val now = ms(3, 14, 5)
        val quiet = presenter.present(cache(agent("w1:p1", AgentStatus.Idle), agent("w1:p2", AgentStatus.Idle), agent("w1:p3", AgentStatus.Working)), now)
        assertEquals("Nothing needs you", quiet.headline); assertEquals("1 working · 2 ready", quiet.detail); assertEquals(WidgetTone.Quiet, quiet.tone); assertEquals("0", quiet.count)
        val one = presenter.present(cache(agent("w1:p1", AgentStatus.Blocked, seq = 1)), now)
        assertEquals("1 needs you", one.headline); assertEquals("needs you", one.countLabel); assertEquals(WidgetTone.NeedsYou, one.tone)
        val two = presenter.present(cache(agent("w1:p1", AgentStatus.Blocked, seq = 1), agent("w1:p2", AgentStatus.Blocked, seq = 2), agent("w1:p3", AgentStatus.Done, seq = 3)), now)
        assertEquals("2 need you", two.headline); assertEquals("need you", two.countLabel); assertEquals("1 done", two.detail)
        assertEquals("No agents running", presenter.present(cache(), now).detail)
    }

    @Test fun aRowsLinkIsTheAlertsOwnLinkForThatAgentAndParsesBackToIt() {
        val c = cache(agent("w1:p2", AgentStatus.Blocked, seq = 9, title = "run migrations", kind = "codex"))
        val row = presenter.present(c, ms(3, 14, 5)).rows.single()
        assertEquals("cx", row.code, "widgets keep the two-letter code; the glyph stays in the app")
        assertEquals("Blocked · seen 14:02", row.detail)
        assertEquals("Review run migrations, blocked, seen 14:02", row.description)
        val hint = assertIs<DeepLinkResult.Valid>(DeepLink.parse(row.link)).hint
        assertEquals("laptop", hint.target.host.value); assertEquals("main", hint.target.session); assertEquals("term_w1_p2", hint.target.terminalId)
        assertEquals("w1:p2", hint.paneId); assertEquals(AlertState.Blocked, hint.state); assertEquals(9L, hint.sequence)
    }

    @Test fun aCacheWhoseIdsMakeNoValidLinkDrawsTheRowWithoutOne() {
        val c = cache(agent("w1:p1", AgentStatus.Blocked, seq = 1, title = "t")).copy(session = "bad session!")
        assertNull(presenter.present(c, ms(3, 14, 5)).rows.single().link)
    }

    // ---- the background read ----------------------------------------------------------------------------------------------

    private val profile = HostProfile(id = "laptop", name = "Laptop", host = "10.0.2.2", user = "u")
    private val fixtures = File(System.getProperty("paddock.repoRoot"), "fixtures/herdr-0.9.1")
    private val clock = Clock { ms(3, 14, 2) }

    private fun herdrAnswering(out: String, exit: Int = 0, err: String = "") = FakeSession(onExec = { argv, _ ->
        when {
            argv.firstOrNull() == "sh" -> FakeSession.result(0, "/usr/bin/herdr")
            else -> FakeSession.result(exit, out, err)
        }
    })

    @Test fun oneSnapshotReadBecomesTheCacheAndNothingElseIsRun() = runBlocking<Unit> {
        val store = InMemoryWidgetCacheStore()
        val session = herdrAnswering(File(fixtures, "snapshot-rich.json").readText())
        val out = WidgetRefresher(store, clock).refresh(profile, session, "paddock-test")
        val cache = assertIs<WidgetRefresher.Outcome.Updated>(out).cache
        assertEquals(cache, store.load())
        assertEquals(WidgetCounts(needsYou = 1), cache.counts); assertEquals(ms(3, 14, 2), cache.readAtMillis)
        assertEquals("paddock-test", cache.session); assertEquals("Laptop", cache.machine)
        assertEquals(listOf("sh", "/usr/bin/herdr"), session.execs.map { it.first.first() }, "locate herdr, then one snapshot: nothing is installed or started")
        assertEquals(listOf("/usr/bin/herdr", "--session", "paddock-test", "api", "snapshot"), session.execs.last().first)
    }

    @Test fun aReadThatFailsLeavesTheCacheExactlyAsItWasSoTheWidgetsKeepTheirOldTime() = runBlocking<Unit> {
        val old = cache(agent("w1:p1", AgentStatus.Blocked, seq = 1, title = "old"), readAt = ms(3, 9, 0))
        val store = InMemoryWidgetCacheStore(old)
        for (session in listOf(
            herdrAnswering("", exit = 1, err = "boom"), herdrAnswering("not json"), herdrAnswering("""{"id":"x","result":{"type":"error"}}"""),
            FakeSession(onExec = { _, _ -> FakeSession.result(1) }),                                       // herdr not found
        )) {
            assertIs<WidgetRefresher.Outcome.Unchanged>(WidgetRefresher(store, clock).refresh(profile, session, "paddock-test"))
            assertEquals(old, store.load())
        }
        assertIs<WidgetRefresher.Outcome.Unchanged>(WidgetRefresher(store, clock).refresh(profile, herdrAnswering(""), "bad name"), "a session name herdr cannot have is refused before any command")
    }

    @Test fun theDoneTheUserSawCountsAsReadWhenThePhoneKnowsTheEpoch() = runBlocking<Unit> {
        val store = InMemoryWidgetCacheStore()
        val json = File(fixtures, "snapshot-rich.json").readText().replace("\"agent_status\":\"blocked\"", "\"agent_status\":\"done\"")
        val unseen = assertIs<WidgetRefresher.Outcome.Updated>(WidgetRefresher(store, clock).refresh(profile, herdrAnswering(json), "paddock-test")).cache
        assertEquals(1, unseen.counts.done)
        val seen = assertIs<WidgetRefresher.Outcome.Updated>(WidgetRefresher(store, clock, seen = { _, _ -> SeenLookup { Long.MAX_VALUE } }).refresh(profile, herdrAnswering(json), "paddock-test")).cache
        assertEquals(WidgetCounts(ready = 1), seen.counts)
    }
}
