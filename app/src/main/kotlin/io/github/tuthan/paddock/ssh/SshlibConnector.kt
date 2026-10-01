package io.github.tuthan.paddock.ssh

import com.trilead.ssh2.ChannelCondition
import com.trilead.ssh2.Connection
import com.trilead.ssh2.ConnectionMonitor
import com.trilead.ssh2.DHGexParameters
import com.trilead.ssh2.ServerHostKeyVerifier
import com.trilead.ssh2.Session
import com.trilead.ssh2.auth.SignatureProxy
import io.github.tuthan.paddock.hostkey.HostKeyAlgorithms
import io.github.tuthan.paddock.hostkey.HostKeyPolicy
import io.github.tuthan.paddock.hostkey.HostKeyState
import io.github.tuthan.paddock.hostkey.PresentedHostKey
import io.github.tuthan.paddock.hostkey.askFromCallbackThread
import io.github.tuthan.paddock.ports.BoundedBytes
import io.github.tuthan.paddock.ports.Clock
import io.github.tuthan.paddock.ports.DownReason
import io.github.tuthan.paddock.ports.ExecLimits
import io.github.tuthan.paddock.ports.ExecResult
import io.github.tuthan.paddock.ports.LinkState
import io.github.tuthan.paddock.ports.LinkTracker
import io.github.tuthan.paddock.ports.SshSession
import io.github.tuthan.paddock.ports.StreamChannel
import java.io.IOException
import java.net.SocketTimeoutException
import java.security.PrivateKey
import java.security.PublicKey
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** How the phone proves who it is. */
sealed interface SshAuth {
    /** The Keystore handle plus its public half; the private key is never read, only asked to sign. */
    class Phone(val privateKey: PrivateKey, val publicKey: PublicKey) : SshAuth
    /**
     * An imported OpenSSH private key for one connect call. The connector wipes [pem] as soon as the key pair is built, so
     * load a fresh one for every attempt (the app does: `ImportedKeyStore.load` per connect). [passphrase] is a String and
     * cannot be wiped.
     */
    class Imported(val pem: CharArray, val passphrase: String?) : SshAuth
}

/**
 * Builds sshlib connections behind the [SshSession] port. Order of events for one attempt: the gate, then the
 * socket, then host-key verification (before any authentication), then authentication. See
 * docs/ssh-library-decision.md for why each sshlib workaround exists.
 *
 * sshlib's connect and authenticate calls block; they run on their own thread so a cancelled connect returns at once, and
 * every failure after the socket may have opened closes the connection.
 */
class SshlibConnector(
    private val hostKeys: HostKeyPolicy,
    private val clock: Clock,
    private val gate: ConnectGate = ConnectGate.Open,
    private val keepalive: Duration = 15.seconds,
    private val keepaliveReplyTimeout: Duration = 10.seconds,
    private val connectTimeout: Duration = 10.seconds,
    /** How long a command or stream waits for one of the [SshlibSession.MAX_CHANNELS] slots before failing with [ChannelsBusy]. */
    private val channelWait: Duration = 30.seconds,
    /**
     * How long a first-contact question may stay open. sshlib's key-exchange timer keeps running while its callback waits, and
     * it cannot end a callback that is blocking its thread (the waiting connect is only woken by that thread), so the question
     * carries this deadline itself and is withdrawn when it passes; sshlib's timer is set [connectTimeout] beyond it so the
     * socket is still open for an answer given in time. sshd's own `LoginGraceTime` (120 s by default) can end it sooner.
     */
    private val firstContactTimeout: Duration = 180.seconds,
) {
    /**
     * [onUnknownHostKey] is asked once, on first contact, with the algorithm and fingerprint; returning false declines. It
     * runs before authentication, as a child of this call (cancelling the connect cancels the question), and its answer is
     * the only way a pin is created. The pin is written only after the key exchange completes, when the server has proved
     * it holds the key; it is kept even if authentication then fails.
     */
    suspend fun connect(
        target: SshTarget,
        auth: SshAuth,
        onUnknownHostKey: suspend (PresentedHostKey) -> Boolean,
    ): SshSession {
        gate.check(target)?.let { throw ConnectFailure.Refused(it) }
        // A bad imported key fails here, before any socket opens. The key text is wiped once parsed.
        val importedPair = (auth as? SshAuth.Imported)?.let {
            try { ImportedKey.keyPair(it.pem, it.passphrase) }
            catch (e: InvalidImportedKey) { throw ConnectFailure.BadKey(e.check.toString()) }
            // sshlib's decoder also throws unchecked exceptions on input it cannot handle.
            catch (e: Exception) { throw ConnectFailure.BadKey(e.message ?: e.javaClass.simpleName) }
            finally { it.pem.fill('\u0000') }
        }
        val pin = try { hostKeys.pinFor(target.profileId) } catch (e: IOException) { throw ConnectFailure.HostKeysUnreadable(e) }
        val tracker = LinkTracker(clock).also { it.connecting() }
        val connection = Connection(target.host, target.port)
        // API 26 to 27 have no ChaCha20 provider and sshlib offers chacha20-poly1305 whenever the server does.
        connection.setClient2ServerCiphers(CIPHERS)
        connection.setServer2ClientCiphers(CIPHERS)
        connection.setClient2ServerMACs(MACS)
        connection.setServer2ClientMACs(MACS)
        connection.setKeyExchangeAlgorithms(KEX)
        connection.setDHGexParameters(DHGexParameters(2048, 3072, 8192))
        // A pinned host is asked for the pinned key's type only; first contact offers modern algorithms only.
        connection.setServerHostKeyAlgorithms(HostKeyAlgorithms.offered(pin).toTypedArray())

        val verdict = Verdict()
        // The host-key question runs on sshlib's thread; this job makes it a child of the connect call.
        val questions = SupervisorJob(currentCoroutineContext()[Job])
        val started = TimeSource.Monotonic.markNow()
        val verifier = ServerHostKeyVerifier { _, _, algorithm, blob ->
            verify(target, PresentedHostKey(algorithm, blob), verdict, questions, firstContactTimeout - started.elapsedNow(), onUnknownHostKey)
        }
        val connectMs = connectTimeout.inWholeMilliseconds.toInt()
        val kexMs = (if (pin == null) firstContactTimeout + connectTimeout else connectTimeout).inWholeMilliseconds.toInt()
        try {
            blocking("paddock-connect") { connection.connect(verifier, connectMs, kexMs) }
        } catch (e: CancellationException) {
            closeOffThread(connection); tracker.down(DownReason.Closed); throw e
        } catch (e: Throwable) {
            closeOffThread(connection)
            val f = verdict.failure ?: when (val c = if (e is IOException) classify(e) else ConnectFailure.Unreachable(e)) {
                // The server never answered: on Android 17 without the grant the OS may have dropped the connect silently.
                is ConnectFailure.TimedOut -> gate.timeoutHint(target)?.let { ConnectFailure.TimedOut(e, it) } ?: c
                else -> c
            }
            tracker.down(f.reason)
            throw if (e is Error) e else f
        } finally {
            // A question still open (the key-exchange timer expired, or the connect failed) is withdrawn here.
            verdict.kexDone = true
            questions.cancel()
        }
        try {
            // The key exchange is complete, so the server holds the key it presented: only now does "trust" become a pin.
            verdict.accepted?.let { presented ->
                val after = try { hostKeys.pinAccepted(target.profileId, target.endpoint, presented) } catch (e: IOException) { throw ConnectFailure.HostKeysUnreadable(e) }
                if (after is HostKeyState.Changed) throw ConnectFailure.HostKeyChanged(after.pin, after.presented)
            }
            val ok = blocking("paddock-auth") { authenticate(connection, target.user, auth, importedPair) }
            if (!ok) throw ConnectFailure.AuthFailed()
        } catch (e: CancellationException) {
            closeOffThread(connection); tracker.down(DownReason.Closed); throw e
        } catch (e: Throwable) {
            // Every failure closes: a Keystore exception escaping sshlib must not leave the socket and its reader thread behind.
            closeOffThread(connection)
            val f = authFailure(e)
            tracker.down(f.reason)
            throw if (e is Error) e else f
        }
        tracker.up()
        return SshlibSession(connection, tracker, keepalive, keepaliveReplyTimeout, channelWait)
    }

    /** What the host-key callback decided, read by the connecting coroutine once sshlib returns. */
    private class Verdict {
        @Volatile var failure: ConnectFailure? = null
        /** A first-contact key the user accepted; pinned only after the key exchange completes. */
        @Volatile var accepted: PresentedHostKey? = null
        @Volatile var kexDone = false
    }

    /** Runs on sshlib's thread, before any authentication; blocking here is what holds authentication back. */
    private fun verify(
        target: SshTarget, presented: PresentedHostKey, verdict: Verdict, questions: Job, questionTime: Duration,
        onUnknownHostKey: suspend (PresentedHostKey) -> Boolean,
    ): Boolean {
        // A later re-key on a connected session: the pin is in place, so only that key passes and nobody is asked.
        if (verdict.kexDone) return runBlocking { runCatching { hostKeys.evaluate(target.profileId, target.endpoint, presented) }.getOrNull() is HostKeyState.Pinned }
        return askFromCallbackThread(questions) {
            try {
                when (val state = hostKeys.evaluate(target.profileId, target.endpoint, presented)) {
                    is HostKeyState.Pinned -> true
                    is HostKeyState.Changed -> { verdict.failure = ConnectFailure.HostKeyChanged(state.pin, state.presented); false }
                    // Unanswered in time: the question is cancelled (the broker withdraws the dialog) and the attempt times out.
                    is HostKeyState.Unknown -> when (withTimeoutOrNull(questionTime) { onUnknownHostKey(presented) }) {
                        true -> { verdict.accepted = presented; true }
                        false -> { verdict.failure = ConnectFailure.HostKeyDeclined(presented); false }
                        null -> { verdict.failure = ConnectFailure.TimedOut(); false }
                    }
                }
            } catch (e: IOException) {
                verdict.failure = ConnectFailure.HostKeysUnreadable(e); false
            }
        } ?: false // cancelled: the connect was cancelled or gave up, and the question was withdrawn
    }

    private fun authenticate(c: Connection, user: String, auth: SshAuth, imported: java.security.KeyPair?): Boolean = when (auth) {
        is SshAuth.Phone -> c.authenticateWithPublicKey(user, object : SignatureProxy(auth.publicKey) {
            override fun sign(message: ByteArray, hashAlgorithm: String): ByteArray {
                require(hashAlgorithm == SHA256) { "unexpected digest $hashAlgorithm" }
                // No provider name: naming AndroidKeyStore fails on API 26.
                val s = java.security.Signature.getInstance("SHA256withECDSA")
                s.initSign(auth.privateKey); s.update(message)
                return io.github.tuthan.paddock.ssh.OpenSshKeys.signatureFromDer(s.sign())
            }
        })
        is SshAuth.Imported -> c.authenticateWithPublicKey(user, checkNotNull(imported))
    }

    /**
     * A server that rejects the key makes sshlib return false (AuthFailed above). An exception is something else: a Keystore
     * or signing failure anywhere in the chain is [ConnectFailure.KeyUnavailable]; sshlib wraps every I/O error during
     * authentication as "Publickey authentication failed.", so an IOException is classified by its cause like a connect error.
     */
    private fun authFailure(e: Throwable): ConnectFailure {
        val chain = generateSequence(e) { it.cause }.toList()
        return when {
            e is ConnectFailure -> e
            chain.any { it is java.security.GeneralSecurityException || it is java.security.ProviderException || it.javaClass.name == "android.security.KeyStoreException" } ->
                ConnectFailure.KeyUnavailable("the key could not sign", e)
            e is IOException && chain.any { it.message?.contains("not supported by the server", ignoreCase = true) == true } -> ConnectFailure.AuthFailed()
            e is IOException -> classify(e)
            else -> ConnectFailure.Unreachable(e)
        }
    }

    private fun classify(e: IOException): ConnectFailure {
        val chain = generateSequence<Throwable>(e) { it.cause }.toList()
        return when {
            chain.any { it is java.net.SocketTimeoutException } -> ConnectFailure.TimedOut(e)
            else -> ConnectFailure.Unreachable(chain.last())
        }
    }

    /** A blocking sshlib call on its own daemon thread; a cancelled caller returns at once and closes the connection. */
    private suspend fun <T> blocking(name: String, block: () -> T): T {
        val result = CompletableDeferred<T>()
        Thread({ try { result.complete(block()) } catch (e: Throwable) { result.completeExceptionally(e) } }, name).apply { isDaemon = true }.start()
        return result.await()
    }

    /** Connection.close() waits for the connection's monitor, which a still-running connect or authenticate holds. */
    private fun closeOffThread(c: Connection) { Thread({ runCatching { c.close() } }, "paddock-close").apply { isDaemon = true; start() } }

    companion object {
        private val CIPHERS = arrayOf("aes256-gcm@openssh.com", "aes128-gcm@openssh.com", "aes256-ctr", "aes128-ctr")
        /** Only used with the CTR ciphers (GCM carries its own tag). No SHA-1 MACs; sshlib has no MD5 MAC at all. */
        private val MACS = arrayOf("hmac-sha2-256-etm@openssh.com", "hmac-sha2-512-etm@openssh.com", "hmac-sha2-256", "hmac-sha2-512")
        /**
         * No SHA-1 key exchange (`diffie-hellman-group1-sha1`, `-group14-sha1`, `-group-exchange-sha1`). sshlib appends
         * `ext-info-c` and the strict-KEX marker itself. If the kyber exclusion is ever reverted, `mlkem768x25519-sha256` goes first.
         */
        private val KEX = arrayOf(
            "curve25519-sha256", "curve25519-sha256@libssh.org", "ecdh-sha2-nistp256", "ecdh-sha2-nistp384", "ecdh-sha2-nistp521",
            "diffie-hellman-group18-sha512", "diffie-hellman-group16-sha512", "diffie-hellman-group-exchange-sha256", "diffie-hellman-group14-sha256",
        )
        private const val SHA256 = "SHA-256"
    }
}

/**
 * One established sshlib connection. Channel use is bounded; a dead link is reported through [link].
 *
 * sshlib's reads, writes and waits block their thread and ignore interrupts, so nothing here blocks a coroutine on them:
 * blocking work runs on [workers] and coroutines wait on a deferred or a channel, which cancellation interrupts at once.
 * The blocked thread is released when its channel or the connection closes.
 */
internal class SshlibSession(
    private val connection: Connection,
    private val tracker: LinkTracker,
    keepalive: Duration,
    private val replyTimeout: Duration,
    private val channelWait: Duration = 30.seconds,
    private val openTimeout: Duration = 20.seconds,
) : SshSession {
    override val link: StateFlow<LinkState> get() = tracker.link

    /** Negotiated key exchange, cipher and host-key algorithm, for the debug screen and tests. */
    fun negotiated(): String = connection.connectionInfo.let {
        "kex=${it.keyExchangeAlgorithm} cipher=${it.clientToServerCryptoAlgorithm}/${it.serverToClientCryptoAlgorithm} hostkey=${it.serverHostKeyAlgorithm}"
    }

    // OpenSSH accepts ten sessions by default; stay under it and queue the rest.
    private val slots = Semaphore(MAX_CHANNELS)
    private val pinger = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "paddock-keepalive").apply { isDaemon = true } }
    private val pingWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "paddock-ping").apply { isDaemon = true } }
    private val workers = Executors.newCachedThreadPool { r -> Thread(r, "paddock-ssh-io").apply { isDaemon = true } }

    init {
        connection.addConnectionMonitor(ConnectionMonitor { reason -> tracker.down(DownReason.Network(reason?.message ?: "connection lost")) })
        // sshlib has no keepalive of its own. ping() holds the connection monitor while it waits for the reply.
        pinger.scheduleWithFixedDelay({
            if (tracker.link.value !is LinkState.Up) return@scheduleWithFixedDelay
            val probe = pingWorker.submit { connection.ping() }
            try { probe.get(replyTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS) }
            catch (e: TimeoutException) { tracker.down(DownReason.Timeout); closeOffThread() }
            catch (e: ExecutionException) { tracker.down(DownReason.Network(e.cause?.message ?: "ping failed")); closeOffThread() }
        }, keepalive.inWholeMilliseconds, keepalive.inWholeMilliseconds, TimeUnit.MILLISECONDS)
    }

    override suspend fun exec(argv: List<String>, stdin: ByteArray?, limits: ExecLimits): ExecResult {
        val command = wire(io.github.tuthan.paddock.cli.argvToCommand(argv))
        requireUp()
        acquireSlot()
        val channel = Opened()
        try {
            requireUp()
            val started = System.nanoTime()
            val out = BoundedBytes(limits.stdoutMax); val err = BoundedBytes(limits.stderrMax)
            val done = CompletableDeferred<Int>()
            submit {
                try {
                    // The deadline below covers this open: a session that opens after it is closed at once by attach().
                    val session = channel.attach(connection.openSession()) ?: return@submit
                    session.execCommand(command)
                    stdin?.let { session.stdin.write(it) }
                    session.stdin.close()
                    val errReader = Thread({ runCatching { drain(session.stderr, err) } }, "paddock-ssh-stderr").apply { isDaemon = true; start() }
                    drain(session.stdout, out)
                    errReader.join()
                    session.waitForCondition(EXIT_OR_CLOSED, 5_000)
                    done.complete(session.exitStatus ?: -1)
                } catch (e: Throwable) { done.completeExceptionally(e) }
            }
            val exit = try { withTimeout(limits.deadline) { done.await() } }
            catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw ExecTimedOut(argv) }
            catch (e: IOException) { throw downOr(e) }
            return ExecResult(exit, out.toByteArray(), err.toByteArray(), out.truncated, err.truncated, ((System.nanoTime() - started) / 1_000_000).milliseconds)
        } finally {
            // Closing unblocks the worker still reading a timed-out or cancelled command; the remote process is not signalled.
            channel.close()
            slots.release()
        }
    }

    private fun requireUp() {
        (tracker.link.value as? LinkState.Down)?.let { throw SessionDown(it.reason) }
    }

    /**
     * sshlib writes the exec command with ISO-8859-1, which turns anything outside Latin-1 into `?` and sends Latin-1 letters
     * as single bytes. Handing it the UTF-8 bytes as Latin-1 characters makes those bytes reach the server unchanged.
     */
    private fun wire(command: String): String = String(command.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)

    private fun downOr(e: IOException): IOException = (tracker.link.value as? LinkState.Down)?.let { SessionDown(it.reason) } ?: e

    /** Bounded: a caller that leaks streams gets a clear failure instead of a hang. */
    private suspend fun acquireSlot() {
        withTimeoutOrNull(channelWait) { slots.acquire() } ?: throw ChannelsBusy(MAX_CHANNELS, channelWait.inWholeMilliseconds)
    }

    private fun submit(task: () -> Unit) {
        try { workers.execute(task) } catch (e: RejectedExecutionException) { throw SessionDown((tracker.link.value as? LinkState.Down)?.reason ?: DownReason.Closed) }
    }

    /** Runs blocking sshlib work on a worker; the caller waits on a deferred, so cancelling it returns at once. */
    private suspend fun <T> onWorker(block: () -> T): T {
        val result = CompletableDeferred<T>()
        submit { try { result.complete(block()) } catch (e: Throwable) { result.completeExceptionally(e) } }
        return result.await()
    }

    override suspend fun openStream(argv: List<String>): StreamChannel {
        val command = wire(io.github.tuthan.paddock.cli.argvToCommand(argv))
        requireUp()
        acquireSlot()
        val channel = Opened()
        try {
            requireUp()
            val session = withTimeoutOrNull(openTimeout) { onWorker { channel.attach(connection.openSession())?.also { it.execCommand(command) } } }
                ?: throw SocketTimeoutException("opening an SSH channel took longer than $openTimeout")
            return Stream(session, channel)
        } catch (e: Throwable) {
            channel.close(); slots.release(); throw e
        }
    }

    override suspend fun close() {
        tracker.down(DownReason.Closed)
        closeOffThread()
    }

    /** A channel's session as it opens on a worker. After [close], a session that opens late is closed the moment it opens. */
    private inner class Opened {
        private var session: Session? = null
        private var closed = false

        fun attach(s: Session): Session? {
            val keep = synchronized(this) { if (closed) false else { session = s; true } }
            if (!keep) closeOffThread(s)
            return s.takeIf { keep }
        }

        fun close() {
            val s = synchronized(this) { closed = true; session.also { session = null } }
            s?.let { closeOffThread(it) }
        }
    }

    private inner class Stream(private val session: Session, private val channel: Opened) : StreamChannel {
        private val closed = AtomicBoolean(false)
        private val out = Pipe(session.stdout)
        private val err = Pipe(session.stderr)
        override val stdout: Flow<ByteArray> get() = out.flow
        override val stderr: Flow<ByteArray> get() = err.flow
        override suspend fun write(bytes: ByteArray) = onWorker { session.stdin.write(bytes); session.stdin.flush() }
        override suspend fun closeStdin() = onWorker { session.stdin.close() }
        override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) {
            // Polled so a cancellation is seen within one step: sshlib's wait does not return on interrupt.
            while ((session.waitForCondition(EXIT_OR_CLOSED, EXIT_POLL_MS) and EXIT_OR_CLOSED) == 0) ensureActive()
            session.exitStatus ?: -1
        }
        override suspend fun close() {
            if (!closed.compareAndSet(false, true)) return
            out.close(); err.close()
            channel.close()
            slots.release()
        }
    }

    /**
     * One sshlib input stream, read on a worker into a bounded channel. A collector suspends on the channel, never on the read,
     * so cancelling it returns at once. The reader starts on first collection and ends when the stream closes or [close] runs.
     */
    private inner class Pipe(private val input: java.io.InputStream) {
        private val chunks = Channel<ByteArray>(PIPE_CHUNKS)
        private val started = AtomicBoolean(false)

        val flow: Flow<ByteArray> = flow {
            if (started.compareAndSet(false, true)) start()
            for (chunk in chunks) emit(chunk)
        }

        private fun start() {
            try { workers.execute(::pump) } catch (e: RejectedExecutionException) { chunks.close(SessionDown((tracker.link.value as? LinkState.Down)?.reason ?: DownReason.Closed)) }
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
                chunks.close(if (e is IOException) downOr(e) else e) // a no-op after close()
            }
        }

        fun close() { chunks.close() }
    }

    private fun drain(input: java.io.InputStream, into: BoundedBytes) {
        val buf = ByteArray(8192)
        while (true) { val n = input.read(buf); if (n < 0) break; into.append(buf, n) }
    }

    /** Connection.close() needs the monitor a stalled ping() holds, so never call it on a thread we need. */
    private fun closeOffThread() {
        pinger.shutdownNow(); pingWorker.shutdownNow()
        Thread({ runCatching { connection.close() }; workers.shutdown() }, "paddock-close").apply { isDaemon = true; start() }
    }

    private fun closeOffThread(session: Session) { Thread({ runCatching { session.close() } }, "paddock-session-close").apply { isDaemon = true; start() } }

    companion object {
        const val MAX_CHANNELS = 8
        private const val PIPE_CHUNKS = 16
        private const val EXIT_POLL_MS = 200L
        private const val EXIT_OR_CLOSED = ChannelCondition.EXIT_STATUS or ChannelCondition.CLOSED
    }
}
