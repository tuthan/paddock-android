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


    // ---- no progress observed, and what a re-read shows ---------------------------------------------------------------

    private fun acked(seq: Long? = 7, sentAt: Long? = sent, kind: OperationKind = OperationKind.Prompt, outcome: OperationOutcome = OperationOutcome.Acknowledged) =
        record(kind = kind, outcome = outcome).copy(seqAtSend = seq, sentAt = sentAt)

    @Test fun fiveSecondsWithoutAStateObservationLabelsAnAcceptedPromptAndNothingElse() {
        assertFalse(p.noProgress(acked(), currentSeq = 7, nowMillis = sent + 4_999), "not yet")
        assertTrue(p.noProgress(acked(), currentSeq = 7, nowMillis = sent + 5_000))
        assertTrue(p.noProgress(acked(), currentSeq = 7, nowMillis = sent + 60_000), "and it stays")
        assertFalse(p.noProgress(acked(), currentSeq = 8, nowMillis = sent + 60_000), "a state observation after the send means there is progress to see")
        assertFalse(p.noProgress(acked(), currentSeq = null, nowMillis = sent + 60_000), "an agent that is not in the read: nothing is claimed")
        assertFalse(p.noProgress(acked(seq = null), currentSeq = 7, nowMillis = sent + 60_000), "without the seq the send began with there is nothing to compare")
        assertFalse(p.noProgress(acked(sentAt = null), currentSeq = 7, nowMillis = sent + 60_000))
        assertFalse(p.noProgress(acked(kind = OperationKind.Esc), currentSeq = 7, nowMillis = sent + 60_000), "the rule is for prompts")
        assertFalse(p.noProgress(acked(outcome = OperationOutcome.Unknown), currentSeq = 7, nowMillis = sent + 60_000), "an unknown outcome has its own line")
        assertFalse(p.noProgress(acked(outcome = OperationOutcome.Rejected), currentSeq = 7, nowMillis = sent + 60_000))
    }

    @Test fun theLabelIsAddedToAnAcceptedPromptsLineOnlyWhenAsked() {
        val ack = OperationResult.Acknowledged(acked(), Unit)
        assertEquals("Prompt sent 14:03:12 · accepted by herdr, which is not a receipt for any turn", p.line(OperationKind.Prompt, ack).text)
        assertEquals("Prompt sent 14:03:12 · accepted by herdr, which is not a receipt for any turn · no progress observed", p.line(OperationKind.Prompt, ack, noProgress = true).text)
        assertEquals("Esc sent 14:03:12 · accepted by herdr", p.line(OperationKind.Esc, OperationResult.Acknowledged(acked(kind = OperationKind.Esc), Unit), noProgress = true).text)
        assertEquals("no progress observed", NO_PROGRESS)
    }

    private fun report(freed: OperationKind?, status: io.github.tuthan.paddock.herdr.AgentStatus = io.github.tuthan.paddock.herdr.AgentStatus.Working) =
        ReReadReport("term_1", reread, status, listOfNotNull(freed?.let { record(kind = it, outcome = OperationOutcome.Unknown, resolvedAt = reread) }))

    @Test fun aRereadStatesWhatHerdrReportsNowAndWhatTheCheckDoesAndDoesNotTell() {
        val status = "Re-read 14:05:40 · herdr reports the agent as working"
        assertEquals(listOf(status), p.rereadLines(report(null), TextCheck.Found), "nothing was waiting: only the state")
        assertEquals(listOf(status, "Paddock kept only a hash of this prompt, so it cannot look for its text. Check the terminal."), p.rereadLines(report(OperationKind.Prompt), TextCheck.NotKept))
        assertEquals("The output has not been read yet, so the prompt's text cannot be looked for.", p.rereadLines(report(OperationKind.Prompt), TextCheck.NoOutput)[1])
        assertEquals("The prompt's text appears in the last 200 lines of output. That does not say the agent took it as a prompt.", p.rereadLines(report(OperationKind.Prompt), TextCheck.Found)[1])
        assertEquals("The prompt's text does not appear in the last 200 lines of output. That does not say it was not received.", p.rereadLines(report(OperationKind.Prompt), TextCheck.NotFound)[1])
        assertEquals("Paddock cannot tell whether the key reached the agent. Look at the terminal.", p.rereadLines(report(OperationKind.Esc), TextCheck.NotKept)[1])
        assertEquals("Paddock cannot tell whether the key reached the agent. Look at the terminal.", p.rereadLines(report(OperationKind.CtrlC), TextCheck.Found)[1], "a key has no text to look for")
        assertEquals("Paddock cannot tell whether the desktop focused the agent. Look at the desktop.", p.rereadLines(report(OperationKind.Focus), TextCheck.Found)[1])
    }

    @Test fun aRereadNeverJudgesWhetherAPromptWasReceived() {
        val all = TextCheck.entries.flatMap { c -> OperationKind.entries.flatMap { k -> p.rereadLines(report(k), c) } }.joinToString(" ").lowercase()
        for (word in listOf("was received", "was delivered", "succeeded", "failed", "lost", "resend", "retry", "try again")) {
            assertFalse(word in all.replace("it was not received", "").replace("not received", ""), word)
        }
    }

    @Test fun aRereadThatDidNothingSaysSoAndWhy() {
        assertEquals("This agent is no longer in the session, or its pane now holds another terminal. Nothing was re-read.", p.rereadFailure(RereadOutcome.Failed(gone = true, detail = "x")))
        assertEquals("Could not read the agent, so nothing was re-read (relay exited).", p.rereadFailure(RereadOutcome.Failed(gone = false, detail = "relay exited")))
    }


    // ---- Phase 08: the phone's Yes and No

    @Test fun anAnswerWrittenIsNeverCalledAReceiptThatClaudeCodeAppliedIt() {
        val yes = p.line(OperationKind.Allow, OperationResult.Acknowledged(record(OperationKind.Allow, OperationOutcome.Acknowledged), Unit))
        assertEquals("Yes written 14:03:12 · for the hook to hand to the agent, which this does not prove", yes.text)
        val no = p.line(OperationKind.Deny, OperationResult.Acknowledged(record(OperationKind.Deny, OperationOutcome.Acknowledged), Unit))
        assertTrue(no.text.startsWith("No written 14:03:12"))
        assertEquals(ResultTone.Ok, yes.tone)
    }

    @Test fun theWritersRefusalsAreSaidInTheirOwnWordsAndOfferTheTerminal() {
        for (code in listOf("request_gone", "request_expired", "request_too_large", "request_mismatch", "request_host_io")) {
            val line = p.line(OperationKind.Allow, OperationResult.Rejected(record(OperationKind.Allow, OperationOutcome.Rejected, code), code, "x"))
            assertEquals(io.github.tuthan.paddock.answers.AnswerCodes.sentence(code), line.text, code)
            assertTrue(line.opensTerminal)
            assertEquals(ResultTone.Refused, line.tone)
        }
        assertTrue("Lost" in p.line(OperationKind.Deny, OperationResult.Rejected(record(OperationKind.Deny, OperationOutcome.Rejected, "request_gone"), "request_gone", "x")).text)
    }

    @Test fun anAnswerRefusedBeforeAnythingWasSentSaysWhy() {
        for (why in io.github.tuthan.paddock.answers.NotAnswerable.entries) {
            val line = p.line(OperationKind.Allow, OperationResult.NotSent(record(OperationKind.Allow, OperationOutcome.NotSent, why.code), why.code, "x"))
            assertEquals("${why.sentence} Nothing was sent.", line.text, why.code)
            assertTrue(line.opensTerminal)
        }
    }

    @Test fun anUnknownAnswerAndItsJournalLineSpeakOfTheRequestNotOfHerdr() {
        val u = record(OperationKind.Allow, OperationOutcome.Unknown)
        assertEquals("Yes sent 14:03:12 · outcome unknown · re-read before sending again", p.unknownText(u))
        assertEquals("Yes sent 14:03:12, waiting for the host's answer", p.describe(record(OperationKind.Allow, OperationOutcome.Sent)))
        assertEquals("No refused by the host (request_gone)", p.describe(record(OperationKind.Deny, OperationOutcome.Rejected, "request_gone")))
        val freed = ReReadReport("term_1", reread, io.github.tuthan.paddock.herdr.AgentStatus.Working, listOf(u.copy(resolvedAt = reread)))
        assertTrue("the agent applied the answer" in p.rereadLines(freed, TextCheck.NotKept).last())
    }
}
