package io.github.tuthan.paddock.ports

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecretNameTest {
    @Test
    fun acceptsOrdinaryNames() {
        listOf("a", "imported-key-1", "host.profile_2", "0", "x".repeat(64), "a.b.c", "key.pem").forEach { assertTrue(SECRET_NAME.matches(it), it) }
    }

    @Test
    fun rejectsTraversalHiddenTempAndOddNames() {
        listOf("", ".", "..", "../x", "a/b", "a\\b", ".hidden", "-x", "_x", "A", "a b", "x".repeat(65), "a.tmp", "tmp.tmp", "a\u0000b", "a\nb", "naïve").forEach {
            assertFalse(SECRET_NAME.matches(it), it)
        }
    }

    @Test
    fun aTmpSuffixOnlyAtTheEndIsRejected() {
        assertTrue(SECRET_NAME.matches("a.tmpx")); assertTrue(SECRET_NAME.matches("a.tmp.pem"))
    }
}
