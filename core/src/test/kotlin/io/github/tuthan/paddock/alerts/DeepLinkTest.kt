package io.github.tuthan.paddock.alerts

import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import org.junit.Test

class DeepLinkTest {
    private val good = "paddock://open?h=workstation&s=default&t=term_65cdbb19f1e6944d&p=w2%3Ap12C&st=blocked&at=1790948507&n=1790948508"
    private val hint = AlertHint(TargetRef(HostProfileId("workstation"), "default", "term_65cdbb19f1e6944d"), "w2:p12C", AlertState.Blocked, 1_790_948_507, 1_790_948_508)

    private fun valid(link: String) = assertIs<DeepLinkResult.Valid>(DeepLink.parse(link), link).hint
    private fun rejected(link: String?, reason: Rejection, field: String? = null) {
        val r = assertIs<DeepLinkResult.Rejected>(DeepLink.parse(link), "$link")
        assertEquals(reason, r.reason, "$link")
        if (field != null) assertEquals(field, r.field, "$link")
    }

    @Test fun theRelaysLinkParsesToTheHintItNames() {
        assertEquals(hint, valid(good))
        assertEquals(AlertState.Done, valid(good.replace("st=blocked", "st=done")).state)
    }

    @Test fun aLinkBuiltFromAHintParsesBackToIt() {
        assertEquals(hint, valid(DeepLink.build(hint)))
        val odd = hint.copy(paneId = "w9:p1.x_y-z", target = TargetRef(HostProfileId("a"), "main.2-x_y", "term_Z-9"))
        assertEquals(odd, valid(DeepLink.build(odd)))
    }

    @Test fun fieldOrderCaseOfSchemeAndUnknownKeysDoNotMatter() {
        val reordered = "PADDOCK://OPEN?n=1790948508&at=1790948507&st=blocked&p=w2%3Ap12C&t=term_65cdbb19f1e6944d&s=default&h=workstation&future=%ZZ&other"
        assertEquals(hint, valid(reordered))
    }

    @Test fun percentEscapesDecodeStrictly() {
        assertEquals("w2:p12C", valid(good).paneId)
        assertEquals("w2:p12C", valid(good.replace("%3A", "%3a")).paneId)
        rejected(good.replace("%3A", "%3"), Rejection.Malformed)
        rejected(good.replace("%3A", "%"), Rejection.Malformed)
        rejected(good.replace("%3A", "%G1"), Rejection.Malformed)
        rejected(good.replace("%3A", "%C3%28"), Rejection.Malformed)   // not UTF-8
        rejected(good.replace("%3A", "%00"), Rejection.BadField, "p")
        rejected(good.replace("%3A", "%0A"), Rejection.BadField, "p")
        rejected(good.replace("%3A", "%2F"), Rejection.BadField, "p")  // "/"
    }

    @Test fun notALinkOfOurs() {
        for (text in listOf(null, "", "paddock", "paddock://", "paddock://open", "paddock://openx?h=a", "paddock://other?h=a", "paddock:open?h=a", "https://open?h=a", "http://paddock://open?h=a", "paddock:///open?h=a", " paddock://open?h=a"))
            rejected(text, Rejection.NotAPaddockLink)
        rejected("paddock://open?", Rejection.MissingField, "h")
    }

    @Test fun userinfoPathsPortsAndFragmentsAreRefused() {
        rejected(good.replace("paddock://open", "paddock://evil@open"), Rejection.NotAPaddockLink)
        rejected(good.replace("paddock://open?", "paddock://open/x?"), Rejection.NotAPaddockLink)
        rejected(good.replace("paddock://open?", "paddock://open:80?"), Rejection.NotAPaddockLink)
        rejected("$good#frag", Rejection.Malformed)
    }

    @Test fun controlCharactersWhitespaceAndNonAsciiAreMalformed() {
        for (c in listOf("\n", "\r", "\t", " ", "\u0000", "\u007f", "é", "‮", "😀"))
            rejected(good + "&x=" + c, Rejection.Malformed)
        rejected(good.replace("workstation", "work station"), Rejection.Malformed)
    }

    @Test fun lengthAndParameterCountAreBounded() {
        rejected(good + "&x=" + "a".repeat(600), Rejection.TooLong)
        rejected(good + "&a=1".repeat(10), Rejection.Malformed)
        assertEquals(hint, valid(good + "&a=1".repeat(8)))
        rejected(good.replace("workstation", "a".repeat(500)), Rejection.TooLong)
    }

    @Test fun everyFieldIsRequiredAndNoneRepeats() {
        for (key in listOf("h", "s", "t", "p", "st", "at", "n")) {
            val without = good.substringAfter("?").split("&").filterNot { it.startsWith("$key=") }.joinToString("&")
            rejected("paddock://open?$without", Rejection.MissingField, key)
            rejected("$good&$key=x", Rejection.DuplicateField, key)
        }
        rejected(good.replace("h=workstation", "h"), Rejection.BadField, "h")
    }

    @Test fun theProfileIsAProfileId() {
        for (bad in listOf("Workstation", "-a", "a_b", "a.b", "../x", "a".repeat(42), "", "%C3%A9"))
            rejected(good.replace("h=workstation", "h=$bad"), Rejection.BadField, "h")
        assertEquals("a", valid(good.replace("workstation", "a")).target.host.value)
        assertEquals("a".repeat(41), valid(good.replace("workstation", "a".repeat(41))).target.host.value)
    }

    @Test fun theSessionIsAHerdrSessionName() {
        for (bad in listOf("-x", ".x", "a%20b", "a/b", "a:b", "a".repeat(65), "")) rejected(good.replace("s=default", "s=$bad"), Rejection.BadField, "s")
        assertEquals("paddock-test", valid(good.replace("default", "paddock-test")).target.session)
        assertEquals("my.work_2", valid(good.replace("default", "my.work_2")).target.session)
    }

    @Test fun theTerminalAndPaneAreBoundedIds() {
        for (bad in listOf("term%20a", "term/1", "t".repeat(65), "", "a:b")) rejected(good.replace("t=term_65cdbb19f1e6944d", "t=$bad"), Rejection.BadField, "t")
        for (bad in listOf("w2/p1", "p".repeat(33), "", "a%20b")) rejected(good.replace("p=w2%3Ap12C", "p=$bad"), Rejection.BadField, "p")
    }

    @Test fun onlyBlockedAndDoneAreStates() {
        for (bad in listOf("working", "idle", "unknown", "Blocked", "blocked%20", "", "blocked%00", "done,blocked")) rejected(good.replace("st=blocked", "st=$bad"), Rejection.BadField, "st")
    }

    @Test fun theTimeAndTheSequenceAreBoundedNumbers() {
        for (bad in listOf("0", "-1", "1e9", "1.5", "99999999999", "123456789012", "", "0x10")) rejected(good.replace("at=1790948507", "at=$bad"), Rejection.BadField, "at")
        for (bad in listOf("-1", "1e3", "99999999999999999", "9007199254740992", "", "1%20")) rejected(good.replace("n=1790948508", "n=$bad"), Rejection.BadField, "n")
        assertEquals(0L, valid(good.replace("n=1790948508", "n=0")).sequence)
        assertEquals(9_007_199_254_740_991L, valid(good.replace("n=1790948508", "n=9007199254740991")).sequence)
        assertEquals(4_102_444_800L, valid(good.replace("at=1790948507", "at=4102444800")).atSeconds)
        rejected(good.replace("at=1790948507", "at=4102444801"), Rejection.BadField, "at")
    }

    @Test fun aHostileLinkNeverEchoesItsValues() {
        val r = assertIs<DeepLinkResult.Rejected>(DeepLink.parse(good.replace("s=default", "s=EVIL%2Fvalue")))
        assertEquals(Rejection.BadField, r.reason)
        assertNotEquals(null, r.field)
        assertEquals(false, r.toString().contains("EVIL"))
    }

    @Test fun exactlyWhatTheHostRelayWritesParses() {
        // tools/test-alert-relay.py pins this same string as build_link's output for these inputs, so the two sides cannot drift apart.
        assertEquals(42L, valid("paddock://open?h=workstation&s=default&t=term_65cdbb19f1e6944d&p=w2%3Ap12C&st=blocked&at=1790948507&n=42").sequence)
    }

    private val machineLink = "paddock://machine?h=workstation&n=a1b2c3d4e5f60718"

    @Test fun aMachineLinkNamesAMachineAndANonceAndNothingElse() {
        val r = assertIs<DeepLinkResult.Machine>(DeepLink.parse(machineLink))
        assertEquals(MachineHint(HostProfileId("workstation"), "a1b2c3d4e5f60718"), r.hint)
        assertEquals(r.hint, assertIs<DeepLinkResult.Machine>(DeepLink.parse(DeepLink.buildMachine(r.hint))).hint)
        assertEquals(r.hint, assertIs<DeepLinkResult.Machine>(DeepLink.parse("PADDOCK://MACHINE?n=a1b2c3d4e5f60718&h=workstation&terminal=x&other")).hint)
    }

    @Test fun aMachineLinkIsHeldToTheSameBoundaryAsAnAlertLink() {
        rejected(machineLink.replace("h=workstation", "h=Work%20Station"), Rejection.BadField, "h")
        rejected(machineLink.replace("n=a1b2c3d4e5f60718", "n=bad%2Fnonce"), Rejection.BadField, "n")
        rejected(machineLink.replace("n=a1b2c3d4e5f60718", "n=" + "a".repeat(65)), Rejection.BadField, "n")
        rejected("paddock://machine?h=workstation", Rejection.MissingField, "n")
        rejected("paddock://machine?n=abc", Rejection.MissingField, "h")
        rejected("$machineLink&h=other", Rejection.DuplicateField, "h")
        rejected(machineLink + "&x=" + "a".repeat(600), Rejection.TooLong)
        rejected("$machineLink#frag", Rejection.Malformed)
        rejected(machineLink + "\n", Rejection.Malformed)
        rejected("paddock://machinex?h=a&n=b", Rejection.NotAPaddockLink)
    }

    @Test fun aMachineLinkDoesNotPassForAnAlertLinkAndTheOtherWayRound() {
        assertIs<DeepLinkResult.Valid>(DeepLink.parse(good))
        assertIs<DeepLinkResult.Machine>(DeepLink.parse(machineLink))
        rejected("paddock://machine?h=workstation&s=default&t=term_a&p=w1&st=blocked&at=1790948507&n=42!", Rejection.BadField, "n")
        rejected(machineLink.replace("machine", "open"), Rejection.MissingField, "s")
    }
}
