package io.github.tuthan.paddock.ops

import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import io.github.tuthan.paddock.identity.TerminalKey
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationPresenterTest {
    private val p = OperationPresenter(ZoneOffset.UTC, Locale.ROOT)
    private val sent = java.time.LocalDateTime.of(2026, 10, 2, 14, 3, 12).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val reread = java.time.LocalDateTime.of(2026, 10, 2, 14, 5, 40).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun record(kind: OperationKind = OperationKind.Prompt, outcome: OperationOutcome, code: String? = null, resolvedAt: Long? = null) =
        OperationRecord(1, "h1", "paddock-test", "term_1", 1, kind, requestedAt = sent - 50, outcome = outcome, sentAt = sent, code = code, resolvedAt = resolvedAt)

    @Test fun anUnknownSendReadsExactlyAsTheNoteSaysIt() {
        val line = p.line(OperationKind.Prompt, OperationResult.Unknown(record(outcome = OperationOutcome.Unknown), "link lost"))
        assertEquals("prompt sent 14:03:12 · outcome unknown · re-read before sending again", line.text)
        assertEquals(ResultTone.Unknown, line.tone)
        assertTrue(line.unknown)
    }

    @Test fun onceReReadTheRowSaysSoAndStaysUnknown() {
        assertEquals("prompt sent 14:03:12 · outcome unknown · re-read 14:05", p.unknownText(record(outcome = OperationOutcome.Unknown, resolvedAt = reread)))
    }

    @Test fun anAcknowledgedPromptIsNotCalledAReceipt() {
        val line = p.line(OperationKind.Prompt, OperationResult.Acknowledged(record(outcome = OperationOutcome.Acknowledged), Unit))
        assertEquals("Prompt sent 14:03:12 · accepted by herdr, which is not a receipt for any turn", line.text)
        assertEquals(ResultTone.Ok, line.tone)
        assertFalse(line.unknown)
    }

    @Test fun keysAndFocusSayWhatHappened() {
        assertEquals("Esc sent 14:03:12 · accepted by herdr", p.line(OperationKind.Esc, OperationResult.Acknowledged(record(OperationKind.Esc, OperationOutcome.Acknowledged), Unit)).text)
        assertEquals("Ctrl+C sent 14:03:12 · accepted by herdr", p.line(OperationKind.CtrlC, OperationResult.Acknowledged(record(OperationKind.CtrlC, OperationOutcome.Acknowledged), Unit)).text)
        assertEquals("The desktop now has this agent focused · 14:03:12", p.line(OperationKind.Focus, OperationResult.Acknowledged(record(OperationKind.Focus, OperationOutcome.Acknowledged), Unit)).text)
    }

    @Test fun herdrsBlockedRefusalIsTheGuardWorkingAndOffersTheTerminal() {
        val line = p.line(OperationKind.Prompt, OperationResult.Rejected(record(outcome = OperationOutcome.Rejected, code = "agent_blocked"), "agent_blocked", "agent w1:p1 is blocked and requires interactive input"))
        assertTrue("blocked" in line.text && "Nothing was typed" in line.text)
        assertTrue(line.opensTerminal)
        assertEquals(ResultTone.Refused, line.tone)
    }

    @Test fun otherHerdrRefusalsKeepHerdrsWords() {
        fun rej(code: String, msg: String) = p.line(OperationKind.Esc, OperationResult.Rejected(record(OperationKind.Esc, OperationOutcome.Rejected, code), code, msg))
        assertTrue("unsupported key x" in rej("invalid_key", "unsupported key x").text)
        assertTrue("not ready" in rej("agent_not_ready", "agent w1:p1 is not an active named agent").text)
        assertTrue("herdr refused the Esc: something odd" in rej("weird", "something odd").text)
    }

    @Test fun ourOwnNotReadyRefusalsNameTheConditionAndSayNothingWasSent() {
        for (reason in NotReadyReason.entries) {
            val line = p.line(OperationKind.Prompt, OperationResult.NotSent(record(outcome = OperationOutcome.NotSent, code = reason.code), reason.code, reason.sentence))
            assertTrue(line.text.startsWith(reason.sentence) && line.text.endsWith("Nothing was sent."), reason.name)
            assertEquals(reason == NotReadyReason.Blocked, line.opensTerminal, reason.name)
        }
    }

    @Test fun transportAndJournalProblemsSayNothingWasSent() {
        fun ns(reason: String, msg: String = "") = p.line(OperationKind.Prompt, OperationResult.NotSent(record(outcome = OperationOutcome.NotSent, code = reason), reason, msg)).text
        assertTrue("nothing was sent" in ns("transport_failed", "no route").lowercase() && "no route" in ns("transport_failed", "no route"))
        assertTrue("nothing was sent" in ns("preflight_failed", "timed out").lowercase())
        assertTrue("operation record" in ns("journal_unwritable"))
        assertTrue("moved" in ns("pane_moved").lowercase() || "another terminal" in ns("pane_moved"))
    }

    @Test fun refusalsBeforeAnyRowReadAsPlainSentences() {
        val key = TerminalKey(TargetRef(HostProfileId("h1"), "s", "t"), 1)
        assertTrue("out of date" in p.line(OperationKind.Prompt, OperationResult.Stale(key)).text)
        assertTrue("Re-read before sending again" in p.line(OperationKind.Prompt, OperationResult.NeedsReread(record(outcome = OperationOutcome.Unknown))).text)
        assertTrue("still running" in p.line(OperationKind.Prompt, OperationResult.Busy(record(outcome = OperationOutcome.Sent))).text)
        assertTrue("could not be written" in p.line(OperationKind.Prompt, OperationResult.JournalFailed("disk full")).text)
    }

    @Test fun aJournalRowIsOneLineForActivity() {
        assertEquals("Prompt sent 14:03:12 · accepted by herdr, which is not a receipt for any turn", p.describe(record(outcome = OperationOutcome.Acknowledged)))
        assertEquals("prompt refused by herdr (agent_blocked)", p.describe(record(outcome = OperationOutcome.Rejected, code = "agent_blocked")))
        assertEquals("prompt not sent (working)", p.describe(record(outcome = OperationOutcome.NotSent, code = NotReadyReason.Working.code)))
        assertEquals("prompt sent 14:03:12 · outcome unknown · re-read before sending again", p.describe(record(outcome = OperationOutcome.Unknown)))
        assertEquals("prompt sent 14:03:12, waiting for herdr's answer", p.describe(record(outcome = OperationOutcome.Sent)))
        assertTrue(p.describe(record(outcome = OperationOutcome.Requested)).startsWith("prompt requested"))
    }

    @Test fun neverShowsPromptText() {
        val withText = record(outcome = OperationOutcome.Acknowledged).copy(promptText = "secret words", payloadSha256 = "abc")
        for (o in OperationOutcome.entries) assertFalse("secret words" in p.describe(withText.copy(outcome = o)))
    }
}
