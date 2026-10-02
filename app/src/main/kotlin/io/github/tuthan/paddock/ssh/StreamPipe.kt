package io.github.tuthan.paddock.ssh

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * One blocking input stream, read on a worker into a bounded channel. A collector suspends on the channel, never on the read,
 * so cancelling it returns at once. The reader starts on first collection and ends when the stream closes or [close] runs.
 *
 * [close] is the owner's teardown, not a graceful end: it discards what is buffered and releases a reader blocked on a full
 * buffer. A plain channel close would leave that reader parked in its blocking send for good (a closed channel still owes its
 * suspended senders' elements to a receiver that is never coming), one stranded worker thread and one retained buffer per
 * closed stream. The flow then ends normally for a collector still attached.
 */
internal class StreamPipe(
    private val input: InputStream,
    /** Runs the reader; may throw [RejectedExecutionException] when the workers are shut down. */
    private val execute: (Runnable) -> Unit,
    /** What a failed read means (the link's own reason when the connection is down). */
    private val readFailure: (IOException) -> Throwable,
    /** What a pipe that could not start means. */
    private val notStarted: () -> Throwable,
    chunkCount: Int = CHUNKS,
) {
    private val chunks = Channel<ByteArray>(chunkCount)
    private val started = AtomicBoolean(false)
    private val torn = AtomicBoolean(false)

    val flow: Flow<ByteArray> = flow {
        if (started.compareAndSet(false, true)) start()
        try {
            for (chunk in chunks) emit(chunk)
        } catch (e: CancellationException) {
            // Our own teardown ends the stream; any other cancellation is the collector's and must propagate.
            if (!torn.get()) throw e
        }
    }

    private fun start() {
        try { execute(::pump) } catch (e: RejectedExecutionException) { chunks.close(notStarted()) }
    }

    private fun pump() {
        val buf = ByteArray(8192)
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                // Fails once close() has run (also while blocked on a full buffer): stop reading.
                if (chunks.trySendBlocking(buf.copyOf(n)).isFailure) return
            }
            chunks.close()
        } catch (e: Throwable) {
            chunks.close(if (e is IOException) readFailure(e) else e) // a no-op after close()
        }
    }

    fun close() {
        torn.set(true)
        chunks.cancel()
    }

    companion object {
        const val CHUNKS = 16
    }
}
