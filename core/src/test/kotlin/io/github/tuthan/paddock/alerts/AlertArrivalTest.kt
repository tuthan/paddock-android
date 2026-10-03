package io.github.tuthan.paddock.alerts

import io.github.tuthan.paddock.herdr.Snapshot
import io.github.tuthan.paddock.reconcile.Freshness
import io.github.tuthan.paddock.reconcile.Installed
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test

class AlertArrivalTest {
    private fun installed(at: Long, epoch: Long = 1) = Installed(Snapshot("0.9.1", 22), at, epoch)

    @Test fun anOlderReadIsNeverUsedEvenWhenLive() = runBlocking {
        val fresh = MutableStateFlow(Freshness.Live)
        val reads = MutableStateFlow<Installed?>(installed(at = 900))
        assertNull(AlertArrival.freshRead(AlertReads("main", fresh, reads), arrivedAtMillis = 1000, timeoutMillis = 150))
    }

    @Test fun aStaleConnectionIsNeverUsedEvenWithANewRead() = runBlocking {
        val fresh = MutableStateFlow(Freshness.Stale)
        val reads = MutableStateFlow<Installed?>(installed(at = 2000))
        assertNull(AlertArrival.freshRead(AlertReads("main", fresh, reads), arrivedAtMillis = 1000, timeoutMillis = 150))
    }

    @Test fun aReadStartedAtTheArrivalCountsWhenTheConnectionIsLive() = runBlocking {
        val reads = AlertReads("main", MutableStateFlow(Freshness.Live), MutableStateFlow<Installed?>(installed(at = 1000, epoch = 7)))
        assertEquals(7L, AlertArrival.freshRead(reads, arrivedAtMillis = 1000, timeoutMillis = 500)?.epoch)
    }

    @Test fun itWaitsForTheConnectionAndTheRead() = runBlocking {
        val fresh = MutableStateFlow(Freshness.Starting)
        val installedFlow = MutableStateFlow<Installed?>(null)
        val waiting = async(Dispatchers.Default) { AlertArrival.freshRead(AlertReads("main", fresh, installedFlow), arrivedAtMillis = 1000, timeoutMillis = 5000) }
        delay(100)
        installedFlow.value = installed(at = 500)       // a read from before the alert
        fresh.value = Freshness.Live
        delay(100)
        installedFlow.value = installed(at = 1500, epoch = 3)
        assertEquals(3L, waiting.await()?.epoch)
    }
}
