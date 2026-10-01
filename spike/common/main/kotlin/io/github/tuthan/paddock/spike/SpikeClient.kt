package io.github.tuthan.paddock.spike

import java.io.IOException
import java.security.PrivateKey
import java.security.PublicKey

/** Throwaway Phase 02 spike surface: just enough to run S1 to S6 against both candidate libraries. */
class ExecOutcome(val exit: Int, val stdout: ByteArray, val stderr: ByteArray)

/** The host-key callback said no; no authentication was attempted. */
class HostKeyRejected(val algorithm: String, val fingerprint: String) : IOException("host key rejected: $algorithm $fingerprint")

sealed interface SpikeAuth {
    /** A non-exportable AndroidKeyStore handle; the adapter may only ask it to sign. */
    class Keystore(val privateKey: PrivateKey, val publicKey: PublicKey) : SpikeAuth
    class Pem(val pem: String, val passphrase: String?) : SpikeAuth
}

interface SpikeClient : AutoCloseable {
    /** [onHostKey] runs before any authentication; returning false must abort with [HostKeyRejected]. */
    fun connect(
        host: String,
        port: Int,
        user: String,
        auth: SpikeAuth,
        onHostKey: (algorithm: String, sha256: String) -> Boolean,
        keepaliveSeconds: Int = 15,
        connectTimeoutMs: Int = 10_000,
    )

    fun exec(command: String): ExecOutcome

    /** Blocks until the library itself reports the link dead; returns elapsed ms, or -1 if [timeoutMs] passes first. */
    fun awaitDead(timeoutMs: Long): Long
}
