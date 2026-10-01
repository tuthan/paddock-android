package io.github.tuthan.paddock.hostkey

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class HostKeyAlgorithmsTest {
    private fun pin(algorithm: String) = PinnedHostKey("p", "e", algorithm, byteArrayOf(1, 2, 3), "SHA256:x", 1, 1)

    @Test
    fun firstContactOffersModernAlgorithmsOnly() {
        val offered = HostKeyAlgorithms.offered(null)
        assertEquals(listOf("ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", "rsa-sha2-512", "rsa-sha2-256"), offered)
        assertFalse("ssh-rsa" in offered, "RSA with SHA-1")
        assertFalse("ssh-dss" in offered)
    }

    @Test
    fun aPinRestrictsTheOfferToItsKeyType() {
        assertEquals(listOf("ssh-ed25519"), HostKeyAlgorithms.offered(pin("ssh-ed25519")))
        assertEquals(listOf("ecdsa-sha2-nistp384"), HostKeyAlgorithms.offered(pin("ecdsa-sha2-nistp384")))
    }

    /** sshlib pins the negotiated signature name; any RSA name means the RSA key, and only SHA-2 signatures are offered for it. */
    @Test
    fun anRsaPinOffersBothSha2SignaturesWhicheverNameItWasPinnedUnder() {
        for (name in listOf("rsa-sha2-512", "rsa-sha2-256", "ssh-rsa")) assertEquals(listOf("rsa-sha2-512", "rsa-sha2-256"), HostKeyAlgorithms.offered(pin(name)), name)
        assertEquals("ssh-rsa", HostKeyAlgorithms.keyType("rsa-sha2-256"))
        assertEquals("ssh-ed25519", HostKeyAlgorithms.keyType("ssh-ed25519"))
    }

    @Test
    fun aRetiredOrUnknownPinTypeFallsBackToTheModernOffer() {
        assertEquals(HostKeyAlgorithms.MODERN, HostKeyAlgorithms.offered(pin("ssh-dss")))
        assertEquals(HostKeyAlgorithms.MODERN, HostKeyAlgorithms.offered(pin("sk-ssh-ed25519@openssh.com")))
    }

    @Test
    fun anRsaPinStillMatchesWhenTheOtherSha2SignatureIsNegotiated() = runBlocking<Unit> {
        val store = object : HostKeyStore {
            val pins = mutableMapOf<String, PinnedHostKey>()
            override suspend fun find(profileId: String) = pins[profileId]
            override suspend fun save(pin: PinnedHostKey) { pins[pin.profileId] = pin }
            override suspend fun touch(profileId: String, nowMillis: Long) = Unit
        }
        val policy = HostKeyPolicy(store) { 1 }
        val blob = byteArrayOf(0, 0, 0, 7) + "ssh-rsa".toByteArray() + ByteArray(20) { 3 }
        policy.acceptUnknown("p", "e", PresentedHostKey("rsa-sha2-512", blob))
        assertIs<HostKeyState.Pinned>(policy.evaluate("p", "e", PresentedHostKey("rsa-sha2-256", blob)))
        assertIs<HostKeyState.Pinned>(policy.evaluate("p", "e", PresentedHostKey("ssh-rsa", blob)))
        assertIs<HostKeyState.Changed>(policy.evaluate("p", "e", PresentedHostKey("ssh-ed25519", blob)), "another key type is never the same key")
    }
}
