package io.github.tuthan.paddock.spike

import com.hierynomus.sshj.key.KeyAlgorithm
import com.hierynomus.sshj.key.KeyAlgorithms
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Security
import java.util.concurrent.TimeUnit
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.Factory
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.signature.Signature
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider

/** sshj candidate. BouncyCastle is forced to 1.86 by the build; see docs/dependency-reviews.md. */
class SshjClient : SpikeClient {
    private var ssh: SSHClient? = null

    override fun connect(
        host: String, port: Int, user: String, auth: SpikeAuth,
        onHostKey: (String, String) -> Boolean, keepaliveSeconds: Int, connectTimeoutMs: Int,
    ) {
        // Android ships a stripped "BC"; sshj needs the full provider ahead of it.
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)

        val config = DefaultConfig()
        config.keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
        if (auth is SpikeAuth.Keystore) {
            // sshj's own ECDSA signer asks BouncyCastle to sign, which cannot use a Keystore handle.
            val factory = KeyAlgorithms.Factory(
                SpikeKeys.SSH_NAME,
                object : Factory.Named<Signature> {
                    override fun getName() = SpikeKeys.SSH_NAME
                    override fun create(): Signature = KeystoreEcdsaSignature()
                },
                KeyType.ECDSA256,
            )
            config.keyAlgorithms = listOf<Factory.Named<KeyAlgorithm>>(factory) + config.keyAlgorithms.filter { it.name != SpikeKeys.SSH_NAME }
        }
        val client = SSHClient(config)
        client.connectTimeout = connectTimeoutMs
        var rejected: HostKeyRejected? = null
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val type = KeyType.fromKey(key)
                val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
                val algorithm = type.toString()
                val fp = SpikeKeys.sha256Fingerprint(blob)
                val ok = onHostKey(algorithm, fp)
                if (!ok) rejected = HostKeyRejected(algorithm, fp)
                return ok
            }
            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        try {
            client.connect(host, port)
        } catch (e: IOException) {
            client.close()
            throw rejected ?: e
        }
        client.connection.keepAlive.keepAliveInterval = keepaliveSeconds
        ssh = client
        when (auth) {
            is SpikeAuth.Keystore -> client.authPublickey(user, object : KeyProvider {
                override fun getPrivate(): PrivateKey = auth.privateKey
                override fun getPublic(): PublicKey = auth.publicKey
                override fun getType(): KeyType = KeyType.ECDSA256
            })
            is SpikeAuth.Pem -> client.authPublickey(
                user,
                client.loadKeys(auth.pem, null, auth.passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }),
            )
        }
    }

    override fun exec(command: String): ExecOutcome {
        val client = checkNotNull(ssh)
        client.startSession().use { session ->
            val cmd = session.exec(command)
            val err = ByteArrayOutputStream()
            val errThread = Thread { cmd.errorStream.copyTo(err) }.apply { start() }
            val out = cmd.inputStream.readBytes()
            cmd.join(30, TimeUnit.SECONDS)
            errThread.join(5_000)
            return ExecOutcome(cmd.exitStatus ?: -1, out, err.toByteArray())
        }
    }

    override fun awaitDead(timeoutMs: Long): Long {
        val client = checkNotNull(ssh)
        val t0 = System.nanoTime()
        while ((System.nanoTime() - t0) / 1_000_000 < timeoutMs) {
            if (!client.isConnected) return (System.nanoTime() - t0) / 1_000_000
            Thread.sleep(200)
        }
        return -1
    }

    override fun close() { runCatching { ssh?.close() } }
}

/** SHA256withECDSA through the AndroidKeyStore provider, wrapped as an sshj signature. Sign only. */
private class KeystoreEcdsaSignature : Signature {
    private var sig: java.security.Signature? = null
    override fun getSignatureName() = SpikeKeys.SSH_NAME
    override fun initSign(prvkey: PrivateKey) {
        sig = java.security.Signature.getInstance("SHA256withECDSA", "AndroidKeyStore").apply { initSign(prvkey) }
    }
    override fun initVerify(pubkey: PublicKey) = throw UnsupportedOperationException("spike signs only")
    override fun update(H: ByteArray) { sig!!.update(H) }
    override fun update(H: ByteArray, off: Int, len: Int) { sig!!.update(H, off, len) }
    override fun sign(): ByteArray = sig!!.sign()
    override fun encode(signature: ByteArray): ByteArray = SpikeKeys.sshEcdsaBlob(signature)
    override fun verify(sig: ByteArray): Boolean = throw UnsupportedOperationException("spike signs only")
}
