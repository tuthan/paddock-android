package io.github.tuthan.paddock.spike

import com.trilead.ssh2.ChannelCondition
import com.trilead.ssh2.Connection
import com.trilead.ssh2.ConnectionMonitor
import com.trilead.ssh2.ServerHostKeyVerifier
import com.trilead.ssh2.auth.SignatureProxy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.PublicKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** ConnectBot sshlib candidate. The library has no keepalive timer of its own, so the adapter pings. */
class SshlibClient : SpikeClient {
    private var conn: Connection? = null
    private val dead = CountDownLatch(1)
    private val pinger = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "spike-keepalive").apply { isDaemon = true } }
    private val pingWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "spike-ping").apply { isDaemon = true } }

    override fun connect(
        host: String, port: Int, user: String, auth: SpikeAuth,
        onHostKey: (String, String) -> Boolean, keepaliveSeconds: Int, connectTimeoutMs: Int,
    ) {
        val c = Connection(host, port)
        // API 26 to 27 have no ChaCha20 provider, and sshlib offers chacha20-poly1305 whenever the server does.
        val ciphers = arrayOf("aes256-gcm@openssh.com", "aes128-gcm@openssh.com", "aes256-ctr", "aes128-ctr")
        c.setClient2ServerCiphers(ciphers)
        c.setServer2ClientCiphers(ciphers)
        var rejected: HostKeyRejected? = null
        c.addConnectionMonitor(ConnectionMonitor { dead.countDown() })
        try {
            c.connect(
                ServerHostKeyVerifier { _, _, algorithm, key ->
                    val fp = SpikeKeys.sha256Fingerprint(key)
                    val ok = onHostKey(algorithm, fp)
                    if (!ok) rejected = HostKeyRejected(algorithm, fp)
                    ok
                },
                connectTimeoutMs, connectTimeoutMs,
            )
        } catch (e: IOException) {
            c.close()
            throw rejected ?: e
        }
        conn = c
        val authenticated = when (auth) {
            is SpikeAuth.Keystore -> c.authenticateWithPublicKey(user, object : SignatureProxy(auth.publicKey) {
                override fun sign(message: ByteArray, hashAlgorithm: String): ByteArray {
                    require(hashAlgorithm == SHA256) { "unexpected digest $hashAlgorithm" }
                    val s = java.security.Signature.getInstance("SHA256withECDSA")
                    s.initSign(auth.privateKey); s.update(message)
                    return SpikeKeys.sshEcdsaSignature(s.sign())
                }
            })
            is SpikeAuth.Pem -> c.authenticateWithPublicKey(user, auth.pem.toCharArray(), auth.passphrase)
        }
        if (!authenticated) { c.close(); throw IOException("authentication failed") }
        pinger.scheduleWithFixedDelay({
            val probe = pingWorker.submit { c.ping() }
            try { probe.get(10, TimeUnit.SECONDS) }
            catch (e: Exception) { if (e is TimeoutException || e.cause != null) dead.countDown() }
        }, keepaliveSeconds.toLong(), keepaliveSeconds.toLong(), TimeUnit.SECONDS)
    }

    override fun exec(command: String): ExecOutcome {
        val session = checkNotNull(conn).openSession()
        try {
            session.execCommand(command)
            val err = ByteArrayOutputStream()
            val errThread = Thread { session.stderr.copyTo(err) }.apply { start() }
            val out = session.stdout.readBytes()
            session.waitForCondition(ChannelCondition.EXIT_STATUS or ChannelCondition.CLOSED, 30_000)
            errThread.join(5_000)
            return ExecOutcome(session.exitStatus ?: -1, out, err.toByteArray())
        } finally {
            session.close()
        }
    }

    override fun awaitDead(timeoutMs: Long): Long {
        val t0 = System.nanoTime()
        return if (dead.await(timeoutMs, TimeUnit.MILLISECONDS)) (System.nanoTime() - t0) / 1_000_000 else -1
    }

    /**
     * Connection.ping() holds the connection monitor while it waits for a reply, and close() needs the same
     * monitor, so on a stalled link close() blocks. Close from a daemon thread and do not wait for it.
     */
    override fun close() {
        pinger.shutdownNow(); pingWorker.shutdownNow()
        conn?.let { c -> Thread({ runCatching { c.close() } }, "spike-close").apply { isDaemon = true; start() } }
    }
}
