package io.github.tuthan.paddock.integration

import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.ExecResult
import io.github.tuthan.paddock.ports.LinkState
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.ports.StreamChannel
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.withContext

/**
 * An [SshSession] whose "remote" is this machine: every argv runs as a local process. It exercises the real herdr and
 * the real relay script without SSH, which the device suite covers separately. Commands are run without a shell.
 */
class LocalProcessSession(private val env: Map<String, String> = emptyMap()) : SshSession {
    private fun builder(argv: List<String>) = ProcessBuilder(argv).also { it.environment().putAll(env) }

    val commands = java.util.concurrent.CopyOnWriteArrayList<List<String>>()
    /** Everything written to any process's stdin (exec input and relay request lines), so a test can assert what was never sent. */
    val stdinLog = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val streams = java.util.concurrent.CopyOnWriteArrayList<Pair<List<String>, Process>>()

    /** Streams still running whose argv mentions [needle]; the test kills them to simulate a dropped relay. */
    fun liveProcessesMatching(needle: String): List<Process> = streams.filter { (argv, p) -> p.isAlive && argv.any { needle in it } }.map { it.second }
    override val link: StateFlow<LinkState> = MutableStateFlow(LinkState.Up(0))

    override suspend fun exec(argv: List<String>, stdin: ByteArray?, limits: ExecLimits): ExecResult = withContext(Dispatchers.IO) {
        commands += argv
        val started = System.nanoTime()
        val p = builder(argv).start()
        if (stdin != null) { stdinLog += stdin.toString(Charsets.UTF_8); p.outputStream.use { it.write(stdin) } } else p.outputStream.close()
        val errBytes = java.util.concurrent.atomic.AtomicReference(ByteArray(0))
        val errThread = Thread { errBytes.set(p.errorStream.readBytes()) }.also { it.isDaemon = true; it.start() }
        val out = p.inputStream.readBytes()
        p.waitFor(limits.deadline.inWholeMilliseconds, TimeUnit.MILLISECONDS); errThread.join(1000)
        ExecResult(p.exitValue(), out.take(limits.stdoutMax).toByteArray(), errBytes.get().take(limits.stderrMax).toByteArray(), out.size > limits.stdoutMax, errBytes.get().size > limits.stderrMax, Duration.ZERO)
    }

    override suspend fun openStream(argv: List<String>): StreamChannel {
        commands += argv
        val p = builder(argv).start()
        streams += argv to p
        // The SshSession contract: a collector cancelled while the process is silent returns at once. A reader thread
        // feeds a channel, so the blocking read never holds up the collector; close() ends the process and the thread.
        val out = Channel<ByteArray>(16)
        Thread({
            val buf = ByteArray(8192)
            try { while (true) { val n = p.inputStream.read(buf); if (n < 0) break; out.trySendBlocking(buf.copyOf(n)) } } catch (_: java.io.IOException) { } finally { out.close() }
        }, "local-stream").apply { isDaemon = true; start() }
        return object : StreamChannel {
            override val stdout: Flow<ByteArray> = out.receiveAsFlow()
            override val stderr: Flow<ByteArray> = emptyFlow()
            override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) { stdinLog += bytes.toString(Charsets.UTF_8); p.outputStream.write(bytes); p.outputStream.flush() }
            override suspend fun closeStdin() = withContext(Dispatchers.IO) { p.outputStream.close() }
            override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) { p.waitFor() }
            override suspend fun close() { p.destroy(); runCatching { p.waitFor(2, TimeUnit.SECONDS) }; p.destroyForcibly() }
        }
    }

    override suspend fun close() = Unit
}
