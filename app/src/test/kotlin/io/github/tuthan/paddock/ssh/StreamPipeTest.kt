package io.github.tuthan.paddock.ssh

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The reader of one SSH stream: bounded, released by its owner's teardown, and still ending normally at end of stream. */
class StreamPipeTest {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "pipe-test").apply { isDaemon = true } }
    @After fun stop() { pool.shutdownNow() }

    /** Never ends; counts reads. */
    private class Endless : InputStream() {
        val reads = AtomicInteger()
        override fun read(): Int = 0
        override fun read(b: ByteArray, off: Int, len: Int): Int { reads.incrementAndGet(); return len }
    }

    private fun pipe(input: InputStream, finished: CountDownLatch = CountDownLatch(1)) =
        StreamPipe(input, { r -> pool.execute { try { r.run() } finally { finished.countDown() } } }, { IOException("mapped: ${it.message}") }, { IOException("not started") })

    private fun waitFor(what: String, millis: Long = 5_000, cond: () -> Boolean) {
        val end = System.nanoTime() + millis * 1_000_000
        while (System.nanoTime() < end) { if (cond()) return; Thread.sleep(10) }
        fail("timed out waiting for $what")
    }

    @Test fun closingWhileTheReaderIsBlockedOnAFullBufferReleasesItsWorker() {
        val input = Endless(); val finished = CountDownLatch(1)
        val p = pipe(input, finished)
        runBlocking { p.flow.first() } // a consumer that takes one chunk and goes away
        // One taken, the buffer full, and the reader parked in its blocking send with one more chunk in hand.
        waitFor("the reader to fill the buffer") { input.reads.get() >= StreamPipe.CHUNKS + 2 }
        Thread.sleep(200)
        assertFalse("the reader should be parked on the full buffer, not finished", finished.await(0, TimeUnit.MILLISECONDS))
        val before = input.reads.get()
        p.close()
        assertTrue("closing the pipe must release the blocked reader", finished.await(5, TimeUnit.SECONDS))
        assertTrue("a released reader reads no further", input.reads.get() <= before + 1)
    }

    @Test fun closingEndsTheFlowOfACollectorStillAttachedWithoutAnError() = runBlocking<Unit> {
        val input = Endless()
        val p = pipe(input)
        val seen = AtomicInteger()
        val collector = async(Dispatchers.Default) { p.flow.collect { seen.incrementAndGet(); Thread.sleep(5) } }
        waitFor("some output") { seen.get() > 0 }
        p.close()
        collector.await() // completes normally: our own teardown is not the collector's failure
        assertTrue(seen.get() > 0)
    }

    @Test fun aCollectorsOwnCancellationStillPropagates() = runBlocking<Unit> {
        val p = pipe(Endless())
        val seen = AtomicInteger()
        val job = launch(Dispatchers.Default) { p.flow.collect { seen.incrementAndGet(); Thread.sleep(5) } }
        waitFor("the collector to receive output") { seen.get() > 0 }
        job.cancel()
        job.join()
        assertTrue("a cancelled collector is cancelled, not completed", job.isCancelled)
        p.close()
    }

    @Test fun everythingBeforeEndOfStreamIsDeliveredInOrderThenTheFlowEnds() = runBlocking<Unit> {
        val data = ByteArray(20_000) { (it % 251).toByte() }
        val p = pipe(data.inputStream())
        val got = p.flow.toList().fold(ByteArray(0)) { a, b -> a + b }
        assertEquals(data.size, got.size)
        assertTrue(data.contentEquals(got))
    }

    @Test fun aFailedReadIsReportedAsTheMappedError() = runBlocking<Unit> {
        val broken = object : InputStream() {
            override fun read(): Int = throw IOException("link gone")
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw IOException("link gone")
        }
        try { pipe(broken).flow.toList(); fail("expected the mapped error") } catch (e: IOException) { assertEquals("mapped: link gone", e.message) }
    }

    @Test fun aWorkerThatCannotStartEndsTheFlowWithItsReason() = runBlocking<Unit> {
        val p = StreamPipe(Endless(), { throw java.util.concurrent.RejectedExecutionException() }, { it }, { IOException("session down") })
        try { p.flow.toList(); fail("expected the not-started error") } catch (e: IOException) { assertEquals("session down", e.message) }
    }
}
