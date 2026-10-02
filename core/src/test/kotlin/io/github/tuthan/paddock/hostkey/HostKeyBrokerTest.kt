package io.github.tuthan.paddock.hostkey

import io.github.tuthan.paddock.ssh.ConnectFailure
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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
    private fun HostKeyBroker.openId() = firstTrust.value!!.id

    @Test fun aQuestionSuspendsUntilTheUserAnswersAndThenClears() = runBlocking<Unit> {
        val b = HostKeyBroker()
        val result = scope.async { b.askFirstTrust("laptop", "10.0.0.2:22", key) }
        until { b.firstTrust.value != null }
        assertEquals("laptop", b.firstTrust.value!!.profileId)
        assertFalse(result.isCompleted)
        assertTrue(b.answerFirstTrust(b.openId(), true))
        assertTrue(result.await())
        assertNull(b.firstTrust.value)
    }

    @Test fun decliningAnswersFalse() = runBlocking<Unit> {
        val b = HostKeyBroker()
        val result = scope.async { b.askFirstTrust("laptop", "e", key) }
        until { b.firstTrust.value != null }
        b.answerFirstTrust(b.openId(), false)
        assertFalse(result.await())
    }

    @Test fun aCancelledAskWithdrawsItsDialog() = runBlocking<Unit> {
        val b = HostKeyBroker()
        val result = scope.async { b.askFirstTrust("laptop", "e", key) }
        until { b.firstTrust.value != null }
        result.cancel()
        until { b.firstTrust.value == null }
    }

    @Test fun anAnswerWithNothingAskedDoesNothing() = runBlocking<Unit> {
        val b = HostKeyBroker()
        assertFalse(b.answerFirstTrust(1, true))                 // no question: must not pre-answer the next one
        val result = scope.async { b.askFirstTrust("laptop", "e", key) }
        until { b.firstTrust.value != null }
        assertFalse(result.isCompleted)
        b.answerFirstTrust(b.openId(), false)
        assertFalse(result.await())
    }

    @Test fun anAnswerOnlyAnswersTheQuestionItNamesAndOnlyOnce() = runBlocking<Unit> {
        val b = HostKeyBroker()
        val result = scope.async { b.askFirstTrust("laptop", "e", key) }
        until { b.firstTrust.value != null }
        val id = b.openId()
        assertFalse(b.answerFirstTrust(id + 1, true), "an answer to another question")
        assertFalse(result.isCompleted)
        assertTrue(b.answerFirstTrust(id, false))
        assertFalse(b.answerFirstTrust(id, true), "a second tap on the same dialog")
        assertFalse(result.await())
    }

    @Test fun twoConnectsAskOneAtATimeInOrder() = runBlocking<Unit> {
        val b = HostKeyBroker()
        val first = scope.async { b.askFirstTrust("a", "ea", key) }
        until { b.firstTrust.value?.profileId == "a" }
        val second = scope.async { b.askFirstTrust("b", "eb", other) }
        delay(100)
        assertEquals("a", b.firstTrust.value!!.profileId, "the second waits behind the first")
        b.answerFirstTrust(b.openId(), true); assertTrue(first.await())
        until { b.firstTrust.value?.profileId == "b" }
        b.answerFirstTrust(b.openId(), false); assertFalse(second.await())
    }

    /**
     * The connector's shape: sshlib calls its host-key callback on a thread of its own and blocks that thread until the
     * callback returns, while the connect coroutine waits for sshlib. Cancelling the connect (the app went to the background)
     * must withdraw the dialog and release sshlib's thread; a tap on the stale dialog must then answer nothing, and the next
     * attempt asks a fresh question that its own answer settles.
     */
    @Test fun cancellingTheConnectWithdrawsAQuestionAskedFromTheLibrarysThread() = runBlocking<Unit> {
        val b = HostKeyBroker()
        val libraryResult = AtomicReference<Any?>("unset")
        val libraryReturned = CompletableDeferred<Unit>()
        val connect = scope.launch {
            val owner = coroutineContext.job
            thread(name = "fake-sshlib-kex") {
                libraryResult.set(askFromCallbackThread(owner) { b.askFirstTrust("laptop", "e", key) })
                libraryReturned.complete(Unit)
            }
            libraryReturned.await() // the connect waits for sshlib, cancellably
        }
        until { b.firstTrust.value != null }
        val stale = b.openId()
        connect.cancel()
        until { b.firstTrust.value == null }
        withTimeout(1_000) { libraryReturned.await() }
        assertNull(libraryResult.get(), "sshlib's thread was released with \"not accepted\"")
        assertFalse(b.answerFirstTrust(stale, true), "a late tap on the withdrawn dialog answers nothing")

        // The user comes back: the next attempt asks again and its own answer settles it.
        val retry = scope.async { b.askFirstTrust("laptop", "e", key) }
        until { b.firstTrust.value != null }
        assertTrue(b.openId() != stale)
        assertFalse(b.answerFirstTrust(stale, false), "the old id cannot answer the new question")
        assertTrue(b.answerFirstTrust(b.openId(), true))
        assertTrue(retry.await())
    }

    @Test fun aQuestionOnAnAlreadyCancelledConnectIsNeverShown() = runBlocking<Unit> {
        val b = HostKeyBroker()
        val owner = kotlinx.coroutines.Job().also { it.cancel() }
        assertNull(askFromCallbackThread(owner) { b.askFirstTrust("laptop", "e", key) })
        assertNull(b.firstTrust.value)
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

    private val pin get() = PinnedHostKey("laptop", "10.0.0.2:22", key.algorithm, key.blob, key.fingerprint, 1_000L, 2_000L)

    @Test fun anApprovalOfTheWarningThatWasShownTakesItOnce() {
        val b = HostKeyBroker()
        b.recordChanged("laptop", "10.0.0.2:22", ConnectFailure.HostKeyChanged(pin, other))
        val shown = b.changed.value.getValue("laptop")
        val taken = assertNotNull(b.takeChanged("laptop", shown.id))
        assertEquals(other.fingerprint, taken.presented.fingerprint)
        assertTrue(b.changed.value.isEmpty())
        assertNull(b.takeChanged("laptop", shown.id))
    }

    @Test fun aLaterFailedAttemptReplacesTheWarningAndAnApprovalOfTheEarlierOneTakesNothing() {
        val b = HostKeyBroker()
        val third = PresentedHostKey("ssh-ed25519", byteArrayOf(7, 7, 7))
        b.recordChanged("laptop", "10.0.0.2:22", ConnectFailure.HostKeyChanged(pin, other))
        val shown = b.changed.value.getValue("laptop")
        b.recordChanged("laptop", "10.0.0.2:22", ConnectFailure.HostKeyChanged(pin, third))
        assertNull(b.takeChanged("laptop", shown.id))
        // The newer warning, with the key the host presented last, is untouched.
        assertEquals(third.fingerprint, b.changed.value.getValue("laptop").presented.fingerprint)
    }

    @Test fun aWarningClearedByAGoodConnectCannotBeApproved() {
        val b = HostKeyBroker()
        b.recordChanged("laptop", "10.0.0.2:22", ConnectFailure.HostKeyChanged(pin, other))
        val shown = b.changed.value.getValue("laptop")
        b.clearChanged("laptop")
        assertNull(b.takeChanged("laptop", shown.id))
    }

    @Test fun aTakenWarningComesBackOnlyIfNothingNewerWasRecorded() {
        val b = HostKeyBroker()
        b.recordChanged("laptop", "10.0.0.2:22", ConnectFailure.HostKeyChanged(pin, other))
        val shown = b.changed.value.getValue("laptop")
        val taken = assertNotNull(b.takeChanged("laptop", shown.id))
        b.restoreChanged(taken)
        assertEquals(shown.id, b.changed.value.getValue("laptop").id)

        val again = assertNotNull(b.takeChanged("laptop", shown.id))
        b.recordChanged("laptop", "10.0.0.2:22", ConnectFailure.HostKeyChanged(pin, PresentedHostKey("ssh-ed25519", byteArrayOf(5))))
        val newer = b.changed.value.getValue("laptop").id
        b.restoreChanged(again)
        assertEquals(newer, b.changed.value.getValue("laptop").id)
    }
}
