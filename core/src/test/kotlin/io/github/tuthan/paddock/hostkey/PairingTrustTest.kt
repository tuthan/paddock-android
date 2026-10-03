package io.github.tuthan.paddock.hostkey

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** AC-11.5: a pairing link's fingerprints are compared with the key the machine presents. A match informs; a mismatch refuses; the user still decides. */
class PairingTrustTest {
    private val key = PresentedHostKey("ssh-ed25519", ByteArray(32) { it.toByte() })
    private val other = PresentedHostKey("ssh-ed25519", ByteArray(32) { (it + 1).toByte() })
    private val scope = CoroutineScope(Dispatchers.Default)
    private suspend fun until(cond: () -> Boolean) = withTimeout(3_000) { while (!cond()) delay(5) }

    @Test fun aMatchingKeyStillAsksAndTheDialogCarriesTheLinksFingerprints() = runBlocking<Unit> {
        val b = HostKeyBroker()
        b.expectPairing("box", listOf("SHA256:other", key.fingerprint))
        val result = scope.async { b.askFirstTrust("box", "box:22", key) }
        until { b.firstTrust.value != null }
        val request = assertNotNull(b.firstTrust.value)
        assertEquals(listOf("SHA256:other", key.fingerprint), request.linkFingerprints)
        assertTrue(HostKeyPrompts.firstTrust(request.endpoint, request.presented, request.linkFingerprints).matchesPairingLink)
        assertFalse(result.isCompleted, "a pairing link never answers for the user")
        assertTrue(b.answerFirstTrust(request.id, true))
        assertTrue(result.await())
        assertNull(b.pairingRefused.value)
    }

    @Test fun aKeyTheLinkDoesNotNameIsRefusedWithoutAnyDialogAndNothingIsTrusted() = runBlocking<Unit> {
        val b = HostKeyBroker()
        b.expectPairing("box", listOf(key.fingerprint))
        assertFalse(b.askFirstTrust("box", "box:22", other), "refused")
        assertNull(b.firstTrust.value, "no question was ever put to the user")
        val refusal = assertNotNull(b.pairingRefused.value)
        assertEquals(listOf(key.fingerprint), refusal.expected); assertEquals(other.fingerprint, refusal.presented.fingerprint); assertEquals("box", refusal.profileId)
        val prompt = HostKeyPrompts.pairingMismatch(refusal)
        assertEquals(other.fingerprint, prompt.presentedFingerprint); assertEquals(listOf(key.fingerprint), prompt.linkFingerprints)
        b.clearPairingRefusal(); assertNull(b.pairingRefused.value)
    }

    @Test fun theLinkIsRememberedPerMachineAndForgottenOnceAKeyIsTrustedOrTheFormClearsIt() = runBlocking<Unit> {
        val b = HostKeyBroker()
        b.expectPairing("box", listOf(key.fingerprint))
        // Another machine is not bound by it.
        val elsewhere = scope.async { b.askFirstTrust("nas", "nas:22", other) }
        until { b.firstTrust.value != null }
        assertNull(b.firstTrust.value!!.linkFingerprints); b.answerFirstTrust(b.firstTrust.value!!.id, false); assertFalse(elsewhere.await())
        // Trusting the matching key uses the link up.
        val first = scope.async { b.askFirstTrust("box", "box:22", key) }
        until { b.firstTrust.value != null }; b.answerFirstTrust(b.firstTrust.value!!.id, true); assertTrue(first.await())
        val again = scope.async { b.askFirstTrust("box", "box:22", other) }
        until { b.firstTrust.value != null }
        assertNull(b.firstTrust.value!!.linkFingerprints, "the link applied to the first key only"); b.answerFirstTrust(b.firstTrust.value!!.id, false); assertFalse(again.await())
        // A Connect that carries no link clears an earlier one.
        b.expectPairing("box", listOf(key.fingerprint)); b.expectPairing("box", null)
        val typed = scope.async { b.askFirstTrust("box", "box:22", other) }
        until { b.firstTrust.value != null }; b.answerFirstTrust(b.firstTrust.value!!.id, true); assertTrue(typed.await())
        assertNull(b.pairingRefused.value)
    }

    @Test fun decliningAMatchingKeyIsStillDecliningAndTheLinkStaysForTheNextConnect() = runBlocking<Unit> {
        val b = HostKeyBroker()
        b.expectPairing("box", listOf(key.fingerprint))
        val no = scope.async { b.askFirstTrust("box", "box:22", key) }
        until { b.firstTrust.value != null }; b.answerFirstTrust(b.firstTrust.value!!.id, false); assertFalse(no.await())
        assertFalse(b.askFirstTrust("box", "box:22", other), "a decline does not lift the comparison")
        assertNotNull(b.pairingRefused.value)
    }
}
