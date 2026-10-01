package io.github.tuthan.paddock.hostkey

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class HostKeyPolicyTest {
    private class MemoryStore : HostKeyStore {
        val pins = mutableMapOf<String, PinnedHostKey>()
        var saves = 0
        override suspend fun find(profileId: String) = pins[profileId]
        override suspend fun save(pin: PinnedHostKey) { pins[pin.profileId] = pin; saves++ }
        override suspend fun touch(profileId: String, nowMillis: Long) { pins[profileId]?.lastSeenMillis = nowMillis }
    }

    private var now = 100L
    private val store = MemoryStore()
    private val policy = HostKeyPolicy(store) { now }
    private val keyA = PresentedHostKey("ssh-ed25519", byteArrayOf(1, 2, 3, 4))
    private val keyB = PresentedHostKey("ssh-ed25519", byteArrayOf(9, 9, 9, 9))

    @Test
    fun firstContactIsUnknownAndPinsNothing() = runBlocking<Unit> {
        assertIs<HostKeyState.Unknown>(policy.evaluate("p", "10.0.0.1:22", keyA))
        assertNull(store.find("p"))
        assertEquals(0, store.saves)
    }

    @Test
    fun acceptingPinsTheKeyAndLaterContactIsPinned() = runBlocking<Unit> {
        val pin = policy.acceptUnknown("p", "10.0.0.1:22", keyA)
        assertEquals(keyA.fingerprint, pin.fingerprint)
        now = 500
        assertIs<HostKeyState.Pinned>(policy.evaluate("p", "10.0.0.1:22", keyA))
        assertEquals(500L, store.find("p")!!.lastSeenMillis)
        assertEquals(100L, store.find("p")!!.firstSeenMillis)
    }

    @Test
    fun aDifferentKeyIsChangedAndTheOldPinSurvives() = runBlocking<Unit> {
        policy.acceptUnknown("p", "10.0.0.1:22", keyA)
        val state = policy.evaluate("p", "10.0.0.1:22", keyB)
        assertIs<HostKeyState.Changed>(state)
        assertEquals(keyA.fingerprint, state.pin.fingerprint)
        assertEquals(keyB.fingerprint, state.presented.fingerprint)
        assertEquals(keyA.fingerprint, store.find("p")!!.fingerprint)
        assertEquals(1, store.saves)
    }

    @Test
    fun theSameBytesUnderADifferentAlgorithmAreChanged() = runBlocking<Unit> {
        policy.acceptUnknown("p", "e", keyA)
        assertIs<HostKeyState.Changed>(policy.evaluate("p", "e", PresentedHostKey("ssh-rsa", keyA.blob)))
    }

    @Test
    fun evaluationNeverReplacesAPinAndAcceptCannotOverwriteOne() = runBlocking<Unit> {
        policy.acceptUnknown("p", "e", keyA)
        repeat(3) { policy.evaluate("p", "e", keyB) }
        assertEquals(keyA.fingerprint, store.find("p")!!.fingerprint)
        assertFailsWith<IllegalStateException> { policy.acceptUnknown("p", "e", keyB) }
    }

    @Test
    fun replaceIsExplicitAndNeedsAnExistingPin() = runBlocking<Unit> {
        assertFailsWith<IllegalStateException> { policy.replaceChanged("p", "e", keyB) }
        policy.acceptUnknown("p", "e", keyA)
        policy.replaceChanged("p", "e", keyB)
        assertEquals(keyB.fingerprint, store.find("p")!!.fingerprint)
    }

    @Test
    fun pinAcceptedPinsOnceAndNeverReplacesAPinMadeMeanwhile() = runBlocking<Unit> {
        assertNull(policy.pinFor("p"))
        assertIs<HostKeyState.Pinned>(policy.pinAccepted("p", "e", keyA))
        assertEquals(keyA.fingerprint, policy.pinFor("p")!!.fingerprint)
        // A second attempt that was answered for the same key finds it pinned; one for another key finds it changed.
        assertIs<HostKeyState.Pinned>(policy.pinAccepted("p", "e", keyA))
        val changed = policy.pinAccepted("p", "e", keyB)
        assertIs<HostKeyState.Changed>(changed)
        assertEquals(keyA.fingerprint, store.find("p")!!.fingerprint)
        assertEquals(1, store.saves)
    }

    @Test
    fun profilesAreIndependent() = runBlocking<Unit> {
        policy.acceptUnknown("p1", "e", keyA)
        assertIs<HostKeyState.Unknown>(policy.evaluate("p2", "e", keyA))
    }

    @Test
    fun fingerprintMatchesSshKeygenFormat() {
        val k = PresentedHostKey("ssh-ed25519", ByteArray(0))
        assertEquals("SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU", k.fingerprint) // SHA-256 of empty input
    }
}
