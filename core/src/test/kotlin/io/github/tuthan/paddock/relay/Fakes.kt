package io.github.tuthan.paddock.relay

import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.ExecResult
import io.github.tuthan.paddock.ports.LinkState
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.ports.StreamChannel
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow

/** A stream the test feeds by hand: [feed] puts bytes on stdout, [end] closes it. */
class FakeStream(val argv: List<String>) : StreamChannel {
    private val out = Channel<ByteArray>(Channel.UNLIMITED)
    val written = CopyOnWriteArrayList<ByteArray>()
    @Volatile var closed = false
    @Volatile var stdinClosed = false
    var exit = 0
    /** How many chunks the reader has taken from this stream, to show whether it was held back. */
    @Volatile var taken = 0; private set
    override val stdout: Flow<ByteArray> = out.receiveAsFlow().onEach { taken++ }
    override val stderr: Flow<ByteArray> = emptyFlow()
    /** Called with each request line written, so a scripted host can answer. */
    @Volatile var onRequest: (String) -> Unit = {}
    override suspend fun write(bytes: ByteArray) { written += bytes; bytes.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.forEach(onRequest) }
    override suspend fun closeStdin() { stdinClosed = true }
    override suspend fun awaitExit(): Int = exit
    override suspend fun close() { closed = true; out.close() }
    fun feed(text: String) { out.trySend(text.toByteArray()) }
    fun end() { out.close() }
    fun writtenText() = written.joinToString("") { it.toString(Charsets.UTF_8) }
}

class FakeSession(
    private val onExec: (List<String>, ByteArray?) -> ExecResult = { _, _ -> result(0) },
    private val onStream: (FakeStream) -> Unit = {},
) : SshSession {
    val streams = CopyOnWriteArrayList<FakeStream>()
    val execs = CopyOnWriteArrayList<Pair<List<String>, ByteArray?>>()
    override val link: StateFlow<LinkState> = MutableStateFlow(LinkState.Up(0))
    override suspend fun exec(argv: List<String>, stdin: ByteArray?, limits: ExecLimits): ExecResult { execs += argv to stdin; return onExec(argv, stdin) }
    override suspend fun openStream(argv: List<String>): StreamChannel = FakeStream(argv).also { streams += it; onStream(it) }
    override suspend fun close() = Unit

    companion object {
        fun result(exit: Int, out: String = "", err: String = "") = ExecResult(exit, out.toByteArray(), err.toByteArray(), false, false, Duration.ZERO)
    }
}
