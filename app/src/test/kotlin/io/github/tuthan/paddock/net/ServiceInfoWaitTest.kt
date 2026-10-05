package io.github.tuthan.paddock.net

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The registration of a service-info callback (Android 14 and later) is stopped however the wait for it ends. */
class ServiceInfoWaitTest {
    /** A platform stand-in: remembers what was registered and how many times it was stopped. */
    private class Platform {
        var reports: ServiceReports<String>? = null
        var registered = 0
        var unregistered = 0
        fun register(r: ServiceReports<String>): () -> Unit { reports = r; registered++; return { unregistered++ } }
    }

    private fun ipv4Only(info: String): String? = info.takeIf { it.startsWith("4:") }

    @Test fun aReportThatAnswersEndsTheWaitAndStopsTheRegistration() = runBlocking {
        val p = Platform()
        val result = async { awaitServiceInfo(p::register, ::ipv4Only) }
        while (p.reports == null) delay(5)
        p.reports!!.onUpdated("4:10.0.0.5")
        assertEquals("4:10.0.0.5", result.await())
        assertEquals(1, p.registered)
        assertEquals("a normal completion leaves nothing registered", 1, p.unregistered)
    }

    @Test fun aServiceThatIsLostEndsTheWaitWithNothingAndStopsTheRegistration() = runBlocking {
        val p = Platform()
        val result = async { awaitServiceInfo(p::register, ::ipv4Only) }
        while (p.reports == null) delay(5)
        p.reports!!.onLost()
        assertNull(result.await())
        assertEquals(1, p.unregistered)
    }

    @Test fun aRegistrationThatFailsEndsTheWaitWithNothingAndStopsWhatItCan() = runBlocking {
        val p = Platform()
        val result = async { awaitServiceInfo(p::register, ::ipv4Only) }
        while (p.reports == null) delay(5)
        p.reports!!.onFailed(3)
        assertNull(result.await())
        assertEquals(1, p.unregistered)
    }

    @Test fun aPlatformThatRefusesAtOnceIsANullAndThereIsNothingToStop() = runBlocking {
        var stopped = 0
        val result = awaitServiceInfo<String, String>({ throw IllegalArgumentException("not registered") }) { stopped++; it }
        assertNull(result)
        assertEquals(0, stopped)
    }

    @Test fun cancellationAndTimeoutStopTheRegistrationToo() = runBlocking {
        val p = Platform()
        assertNull(withTimeoutOrNull(100) { awaitServiceInfo(p::register, ::ipv4Only) })
        assertEquals(1, p.unregistered)

        val q = Platform()
        val job = launch(Dispatchers.Default, CoroutineStart.UNDISPATCHED) { awaitServiceInfo(q::register, ::ipv4Only) }
        while (q.reports == null) delay(5)
        job.cancel()
        job.join()
        assertEquals(1, q.unregistered)
    }

    @Test fun aReportWithoutAnIpv4AddressIsNotTheAnswerAndTheLaterOneIs() = runBlocking {
        // A host whose AAAA record arrives before its A record: the first report has no IPv4 address and must not end the wait.
        val p = Platform()
        val result = async { awaitServiceInfo(p::register, ::ipv4Only) }
        while (p.reports == null) delay(5)
        p.reports!!.onUpdated("6:fe80::1")
        delay(50)
        assertEquals("still waiting, still registered", 0, p.unregistered)
        p.reports!!.onUpdated("4:10.0.0.5")
        assertEquals("4:10.0.0.5", result.await())
        assertEquals(1, p.unregistered)
    }

    @Test fun aServiceThatNeverGetsAnIpv4AddressRunsOutTheCallersTimeoutAndIsStopped() = runBlocking {
        val p = Platform()
        val result = async { withTimeoutOrNull(150) { awaitServiceInfo(p::register, ::ipv4Only) } }
        while (p.reports == null) delay(5)
        p.reports!!.onUpdated("6:fe80::1")
        assertNull(result.await())
        assertEquals(1, p.unregistered)
    }

    @Test fun reportsAfterTheEndAreIgnoredAndDoNotResumeTwice() = runBlocking {
        val p = Platform()
        val result = async { awaitServiceInfo(p::register, ::ipv4Only) }
        while (p.reports == null) delay(5)
        p.reports!!.onUpdated("4:10.0.0.5")
        p.reports!!.onUpdated("4:10.0.0.6")
        p.reports!!.onLost()
        assertEquals("4:10.0.0.5", result.await())
        assertEquals(1, p.unregistered)
    }
}
