package io.github.tuthan.paddock.ssh

import com.trilead.ssh2.ChannelCondition
import com.trilead.ssh2.Connection
import com.trilead.ssh2.ConnectionMonitor
import com.trilead.ssh2.ServerHostKeyVerifier
import com.trilead.ssh2.Session
import com.trilead.ssh2.auth.SignatureProxy
import io.github.tuthan.paddock.hostkey.HostKeyPolicy
import io.github.tuthan.paddock.hostkey.HostKeyState
import io.github.tuthan.paddock.hostkey.PresentedHostKey
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
import java.security.PrivateKey
import java.security.PublicKey
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/** How the phone proves who it is. */
sealed interface SshAuth {
    /** The Keystore handle plus its public half; the private key is never read, only asked to sign. */
    class Phone(val privateKey: PrivateKey, val publicKey: PublicKey) : SshAuth
    /** An imported OpenSSH private key held in memory only for the connect call. */
    class Imported(val pem: CharArray, val passphrase: String?) : SshAuth
}

/**
 * Builds sshlib connections behind the [SshSession] port. Order of events for one attempt: the gate, then the
 * socket, then host-key verification (before any authentication), then authentication. See
 * docs/ssh-library-decision.md for why each sshlib workaround exists.
 */
class SshlibConnector(
    private val hostKeys: HostKeyPolicy,
    private val clock: Clock,
    private val gate: ConnectGate = ConnectGate.Open,
    private val keepalive: Duration = 15.seconds,
    private val keepaliveReplyTimeout: Duration = 10.seconds,
    private val connectTimeout: Duration = 10.seconds,
) {
    /**
     * [onUnknownHostKey] is asked once, on first contact, with the algorithm and fingerprint; returning false
     * declines. It runs before authentication and its answer is the only way a pin is created.
     */
    suspend fun connect(
        target: SshTarget,
        auth: SshAuth,
        onUnknownHostKey: suspend (PresentedHostKey) -> Boolean,
    ): SshSession {
        gate.check(target)?.let { throw ConnectFailure.Refused(it) }
        // A bad imported key fails here, before any socket opens.
        val importedPair = (auth as? SshAuth.Imported)?.let {
            try { ImportedKey.keyPair(it.pem, it.passphrase) } catch (e: InvalidImportedKey) { throw ConnectFailure.BadKey(e.check.toString()) }
        }
        val tracker = LinkTracker(clock).also { it.connecting() }
        val connection = Connection(target.host, target.port)
        // API 26 to 27 have no ChaCha20 provider and sshlib offers chacha20-poly1305 whenever the server does.
        connection.setClient2ServerCiphers(CIPHERS)
        connection.setServer2ClientCiphers(CIPHERS)

        var failure: ConnectFailure? = null
        val verifier = ServerHostKeyVerifier { _, _, algorithm, blob ->
            val presented = PresentedHostKey(algorithm, blob)
            // Called on the connecting thread, so blocking here is what holds authentication back.
            runBlocking {
                when (val state = hostKeys.evaluate(target.profileId, target.endpoint, presented)) {
                    is HostKeyState.Pinned -> true
                    is HostKeyState.Changed -> { failure = ConnectFailure.HostKeyChanged(state.pin, state.presented); false }
                    is HostKeyState.Unknown ->
                        if (onUnknownHostKey(presented)) { hostKeys.acceptUnknown(target.profileId, target.endpoint, presented); true }
                        else { failure = ConnectFailure.HostKeyDeclined(presented); false }
                }
            }
        }
        val ms = connectTimeout.inWholeMilliseconds.toInt()
        try {
            withContext(Dispatchers.IO) { connection.connect(verifier, ms, ms) }
        } catch (e: IOException) {
            runCatching { connection.close() }
            val f = failure ?: classify(e)
            tracker.down(f.reason)
            throw f
        }
        try {
            val ok = withContext(Dispatchers.IO) { authenticate(connection, target.user, auth, importedPair) }
            if (!ok) throw ConnectFailure.AuthFailed()
        } catch (e: ConnectFailure) {
            runCatching { connection.close() }; tracker.down(e.reason); throw e
        } catch (e: IOException) {
            runCatching { connection.close() }
            val f = ConnectFailure.AuthFailed().takeIf { e.message?.contains("Authentication", ignoreCase = true) == true } ?: classify(e)
            tracker.down(f.reason); throw f
        }
        tracker.up()
        return SshlibSession(connection, tracker, keepalive, keepaliveReplyTimeout)
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

    private fun classify(e: IOException): ConnectFailure {
        val chain = generateSequence<Throwable>(e) { it.cause }.toList()
        return when {
            chain.any { it is java.net.SocketTimeoutException } -> ConnectFailure.TimedOut(e)
            else -> ConnectFailure.Unreachable(chain.last())
        }
    }

    companion object {
        private val CIPHERS = arrayOf("aes256-gcm@openssh.com", "aes128-gcm@openssh.com", "aes256-ctr", "aes128-ctr")
        private const val SHA256 = "SHA-256"
    }
}

/** One established sshlib connection. Channel use is bounded; a dead link is reported through [link]. */
internal class SshlibSession(
    private val connection: Connection,
    private val tracker: LinkTracker,
    keepalive: Duration,
    private val replyTimeout: Duration,
) : SshSession {
    override val link: StateFlow<LinkState> get() = tracker.link

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
        val command = io.github.tuthan.paddock.cli.argvToCommand(argv)
        requireUp()
        return slots.withPermit {
            val started = System.nanoTime()
            val out = BoundedBytes(limits.stdoutMax); val err = BoundedBytes(limits.stderrMax)
            val opened = java.util.concurrent.atomic.AtomicReference<Session?>(null)
            val done = CompletableDeferred<Int>()
            workers.execute {
                try {
                    val session = connection.openSession().also { opened.set(it) }
                    session.execCommand(command)
                    stdin?.let { session.stdin.write(it) }
                    session.stdin.close()
                    val errReader = Thread { drain(session.stderr, err) }.apply { isDaemon = true; start() }
                    drain(session.stdout, out)
                    errReader.join()
                    session.waitForCondition(ChannelCondition.EXIT_STATUS or ChannelCondition.CLOSED, 5_000)
                    done.complete(session.exitStatus ?: -1)
                } catch (e: Throwable) { done.completeExceptionally(e) }
            }
            try {
                val exit = try { withTimeout(limits.deadline) { done.await() } }
                catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw ExecTimedOut(argv) }
                catch (e: IOException) { throw if (tracker.link.value is LinkState.Down) SessionDown((tracker.link.value as LinkState.Down).reason) else e }
                ExecResult(exit, out.toByteArray(), err.toByteArray(), out.truncated, err.truncated, ((System.nanoTime() - started) / 1_000_000).milliseconds)
            } finally {
                // Closing unblocks any reader still waiting on a timed-out command.
                opened.get()?.let { closeOffThread(it) }
            }
        }
    }

    private fun requireUp() {
        (tracker.link.value as? LinkState.Down)?.let { throw SessionDown(it.reason) }
    }

    override suspend fun openStream(argv: List<String>): StreamChannel {
        val command = io.github.tuthan.paddock.cli.argvToCommand(argv)
        requireUp()
        slots.acquire()
        return try {
            val session = withContext(Dispatchers.IO) { connection.openSession().also { it.execCommand(command) } }
            Stream(session) { slots.release() }
        } catch (e: Throwable) { slots.release(); throw e }
    }

    override suspend fun close() {
        tracker.down(DownReason.Closed)
        closeOffThread()
    }

    private inner class Stream(private val session: Session, private val onClose: () -> Unit) : StreamChannel {
        private var closed = false
        override val stdout: Flow<ByteArray> = chunks(session.stdout)
        override val stderr: Flow<ByteArray> = chunks(session.stderr)
        override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) { session.stdin.write(bytes); session.stdin.flush() }
        override suspend fun closeStdin() = withContext(Dispatchers.IO) { session.stdin.close() }
        override suspend fun awaitExit(): Int = withContext(Dispatchers.IO) {
            session.waitForCondition(ChannelCondition.EXIT_STATUS or ChannelCondition.CLOSED, 0)
            session.exitStatus ?: -1
        }
        override suspend fun close() { synchronized(this) { if (closed) return; closed = true }; closeOffThread(session); onClose() }
        private fun chunks(input: java.io.InputStream): Flow<ByteArray> = flow {
            val buf = ByteArray(8192)
            while (true) { val n = input.read(buf); if (n < 0) break; emit(buf.copyOf(n)) }
        }.flowOn(Dispatchers.IO)
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

    companion object { const val MAX_CHANNELS = 8 }
}
