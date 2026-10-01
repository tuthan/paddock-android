package io.github.tuthan.paddock.hostkey

import io.github.tuthan.paddock.ssh.ConnectFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostKeyBrokerTest {
    private val key = PresentedHostKey("ssh-ed25519", ByteArray(32) { it.toByte() })
    private val other = PresentedHostKey("ssh-ed25519", ByteArray(32) { (it + 1).toByte() })
    private val scope = CoroutineScope(Dispatchers.Default)

    private suspend fun until(cond: () -> Boolean) = withTimeout(3_000) { while (!cond()) delay(5) }

    @Test fun aQuestionSuspendsUntilTheUserAnswersAndThenClears() = runBlocking<Unit> {
        val b = HostKeyBroker()
        val result = scope.async { b.askFirstTrust("laptop", "10.0.0.2:22", key) }
        until { b.firstTrust.value != null }
        assertEquals("laptop", b.firstTrust.value!!.profileId)
        assertFalse(result.isCompleted)
        b.answerFirstTrust(true)
        assertTrue(result.await())
        assertNull(b.firstTrust.value)
    }

    @Test fun decliningAnswersFalse() = runBlocking<Unit> {
        val b = HostKeyBroker()
        val result = scope.async { b.askFirstTrust("laptop", "e", key) }
        until { b.firstTrust.value != null }
        b.answerFirstTrust(false)
        assertFalse(result.await())
    }

    @Test fun aCancelledConnectWithdrawsItsDialog() = runBlocking<Unit> {
        val b = HostKeyBroker()
        val result = scope.async { b.askFirstTrust("laptop", "e", key) }
        until { b.firstTrust.value != null }
        result.cancel()
        until { b.firstTrust.value == null }
    }

    @Test fun aLateAnswerWithNothingAskedDoesNothing() = runBlocking<Unit> {
        val b = HostKeyBroker()
        b.answerFirstTrust(true)                                  // no question: must not pre-answer the next one
        val result = scope.async { b.askFirstTrust("laptop", "e", key) }
        until { b.firstTrust.value != null }
        assertFalse(result.isCompleted)
        b.answerFirstTrust(false)
        assertFalse(result.await())
    }

    @Test fun twoConnectsAskOneAtATimeInOrder() = runBlocking<Unit> {
        val b = HostKeyBroker()
        val first = scope.async { b.askFirstTrust("a", "ea", key) }
        until { b.firstTrust.value?.profileId == "a" }
        val second = scope.async { b.askFirstTrust("b", "eb", other) }
        delay(100)
        assertEquals("a", b.firstTrust.value!!.profileId, "the second waits behind the first")
        b.answerFirstTrust(true); assertTrue(first.await())
        until { b.firstTrust.value?.profileId == "b" }
        b.answerFirstTrust(false); assertFalse(second.await())
    }

    @Test fun aChangedKeyIsRecordedPerProfileUntilCleared() {
        val b = HostKeyBroker()
        val pin = PinnedHostKey("laptop", "10.0.0.2:22", key.algorithm, key.blob, key.fingerprint, 1_000L, 2_000L)
        b.recordChanged("laptop", "10.0.0.2:22", ConnectFailure.HostKeyChanged(pin, other))
        val c = assertNotNull(b.changed.value["laptop"])
        assertEquals(other.fingerprint, c.presented.fingerprint)
        assertEquals(key.fingerprint, c.pin.fingerprint)
        b.clearChanged("laptop")
        assertTrue(b.changed.value.isEmpty())
    }
}
