package io.github.tuthan.paddock.hostprofile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AC-11.5's parser half: a pairing link fills in the machine, and anything hostile, missing or ill-formed is refused. */
class PairingLinkTest {
    private val fpA = "SHA256:" + "A".repeat(43)
    private val fpB = "SHA256:" + "b+/".repeat(14) + "b"           // 43 characters from the whole alphabet
    private val good = "paddock://pair?v=1&host=box.example.ts.net&port=2222&user=jdoe&fp=$fpA"

    private fun valid(text: String?) = assertIs<PairingResult.Valid>(PairingLinks.parse(text), "should accept: $text").link
    private fun rejected(text: String?) = assertIs<PairingResult.Rejected>(PairingLinks.parse(text), "should refuse: ${text?.replace("\n", "\\n")}")

    @Test fun aLinkTheHostWritesFillsInTheMachine() {
        assertEquals(PairingLink("box.example.ts.net", 2222, "jdoe", listOf(fpA), null), valid(good))
        val full = valid("paddock://pair?v=1&host=192.168.1.20&port=22&user=jdoe&fp=$fpA,$fpB&session=paddock-dev")
        assertEquals(listOf(fpA, fpB), full.fingerprints)
        assertEquals("paddock-dev", full.session)
        assertEquals(AddMachineInput("192.168.1.20", "22", "jdoe", session = "paddock-dev", pairedFingerprints = listOf(fpA, fpB)), full.toInput())
        assertEquals(KeyKind.Phone, full.toInput().key, "a pairing link never chooses the key")
    }

    @Test fun buildAndParseAgree() {
        val link = PairingLink("nas.lan", 22, "root", listOf(fpA, fpB), "main")
        assertEquals(link, valid(PairingLinks.build(link)))
        assertEquals(PairingLink("[fe80::1]".removeSurrounding("[", "]"), 22, "u", listOf(fpA), null), valid("paddock://pair?v=1&host=[fe80::1]&port=22&user=u&fp=$fpA"))
    }

    @Test fun percentEscapesAreDecodedStrictly() {
        assertEquals(listOf(fpB), valid("paddock://pair?v=1&host=h&port=22&user=u&fp=SHA256%3A${fpB.removePrefix("SHA256:").replace("+", "%2B").replace("/", "%2F")}").fingerprints)
        for (bad in listOf("%", "%4", "%zz", "%C3", "%FF%FF")) assertEquals(PairingRejection.Malformed, rejected("paddock://pair?v=1&host=h&port=22&user=u&fp=$fpA&session=$bad").reason, bad)
    }

    @Test fun aSchemeAndHostOtherThanThePairingLinkIsNotOne() {
        for (t in listOf(null, "", "https://pair?v=1", "paddock://open?v=1&host=h&port=22&user=u&fp=$fpA", "paddock://pair", "paddock:pair?v=1", " $good", "xpaddock://pair?v=1"))
            assertEquals(PairingRejection.NotAPairingLink, rejected(t).reason, "$t")
        assertEquals(good.replace("paddock", "PADDOCK"), good.replace("paddock", "PADDOCK")); valid(good.replace("paddock://", "PADDOCK://"))
    }

    @Test fun everyRequiredFieldIsRequired() {
        for (key in listOf("v", "host", "port", "user", "fp")) {
            val without = good.substringAfter("pair?").split('&').filterNot { it.startsWith("$key=") }.joinToString("&")
            val r = rejected("paddock://pair?$without")
            assertEquals(PairingRejection.MissingField, r.reason, key); assertEquals(key, r.field)
        }
    }

    @Test fun aFieldWithoutAValueOrRepeatedIsRefusedNotResolvedByPosition() {
        assertEquals("host", rejected("paddock://pair?v=1&host&port=22&user=u&fp=$fpA").field)
        for (key in listOf("v=1", "host=h", "port=22", "user=u", "fp=$fpA", "session=s")) {
            val r = rejected("$good&session=s&$key".replace("&session=s&session=s", "&session=s&session=s"))
            if (key == "session=s") assertEquals(PairingRejection.DuplicateField, r.reason) else assertEquals(PairingRejection.DuplicateField, r.reason, key)
        }
    }

    @Test fun anotherVersionNeedsANewerPaddock() {
        assertEquals(PairingRejection.UnsupportedVersion, rejected(good.replace("v=1", "v=2")).reason)
        assertEquals(PairingRejection.UnsupportedVersion, rejected(good.replace("v=1", "v=01")).reason)
        assertEquals(PairingRejection.UnsupportedVersion, rejected(good.replace("v=1", "v=")).reason)
    }

    @Test fun hostileOrIllFormedFieldsAreRefusedByTheirKeyAndNeverEchoed() {
        val cases = mapOf(
            "host" to listOf("a b", "a/b", "a@b", "http://x", "x?y", "exämple.com", "a%20b", "-", "", "x".repeat(254), "..", "a;b", "a'b", "a\$b", "[::1", "%2F", "h%00"),
            "port" to listOf("0", "65536", "-1", "abc", "22.5", "", "1".repeat(6), "0x16", "%31%32x"),
            "user" to listOf("a b", "-rf", "a/b", "a@b", "a;b", "", "x".repeat(65), "aé", "root%0Ahost"),
            "fp" to listOf("", "SHA256:short", "sha256:" + "A".repeat(43), "SHA256:" + "A".repeat(44), "SHA256:" + "A".repeat(42) + "=", "MD5:" + "A".repeat(43), fpA + ",", ",$fpA", "$fpA,$fpA", "$fpA,$fpB,$fpA", "$fpA,x",
                (1..5).joinToString(",") { "SHA256:" + "$it".repeat(43).take(43) }, "SHA256:" + "A".repeat(42) + "!", "$fpA;$fpB"),
            "session" to listOf("", "-x", "a b", "a/b", ".hidden", "x".repeat(65), "a;b", "a%0Ab"),
        )
        for ((field, values) in cases) for (v in values) {
            val link = good.substringAfter("pair?").split('&').filterNot { it.startsWith("$field=") }.plus("$field=$v").joinToString("&")
            val r = rejected("paddock://pair?$link")
            assertTrue(r.reason == PairingRejection.BadField && r.field == field || r.reason == PairingRejection.Malformed, "$field=$v -> $r")
            assertTrue(!r.toString().contains(v) || v.isEmpty() || v.length < 2, "the refusal repeats the value for $field")
        }
    }

    @Test fun anOversizedOrSmuggledLinkIsMalformed() {
        assertEquals(PairingRejection.TooLong, rejected(good + "&x=" + "a".repeat(PairingLinks.MAX_LENGTH)).reason)
        assertEquals(PairingRejection.Malformed, rejected("$good&x=${"a&".repeat(20)}").reason, "more parameters than any link has")
        for (t in listOf("$good#frag", "$good\n", "$good\r&x=1", "$good x", "${good}\u0000", "$good\t", "$good&host=‮evil", "paddock://pair?v=1&host=h&port=22&user=u&fp=$fpA&é=1"))
            assertEquals(PairingRejection.Malformed, rejected(t).reason, t.replace("\n", "\\n"))
    }

    @Test fun unknownKeysAreIgnoredAndNeverDecodedOrTrusted() {
        assertEquals(valid(good), valid("$good&key=ssh-ed25519%20AAAA&password=hunter2&extra=%zz"))
        assertNull(PairingLinks.parse("$good&password=x").let { (it as PairingResult.Valid).link.session }, "no extra field leaks into the machine")
    }

    @Test fun findPicksTheLinkOutOfPastedText() {
        assertEquals(good, PairingLinks.find("Pair Paddock with my box:\n$good\nthanks"))
        assertEquals(good, PairingLinks.find("  $good  "))
        assertEquals(null, PairingLinks.find("nothing here")); assertEquals(null, PairingLinks.find(null)); assertEquals(null, PairingLinks.find(""))
        assertEquals(good, PairingLinks.find("paddock://open?x=1 $good"), "the first token that is a pairing link")
    }
}
