package io.github.tuthan.paddock.hostkey

import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A host key as the server presented it: the SSH wire blob and its algorithm name. */
class PresentedHostKey(val algorithm: String, val blob: ByteArray) {
    /** `SHA256:` plus unpadded base64, the form `ssh-keygen -l` prints. */
    val fingerprint: String =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
}

data class PinnedHostKey(
    val profileId: String,
    val endpoint: String,
    val algorithm: String,
    val blob: ByteArray,
    val fingerprint: String,
    val firstSeenMillis: Long,
    var lastSeenMillis: Long,
)

/** Persistence port for pins. The Room implementation arrives with its dependency review. */
interface HostKeyStore {
    suspend fun find(profileId: String): PinnedHostKey?
    suspend fun save(pin: PinnedHostKey)
    suspend fun touch(profileId: String, nowMillis: Long)
}

/** Which server host-key algorithms the phone offers, and which presented keys count as the same key as a pin. */
object HostKeyAlgorithms {
    /** First contact: modern signatures only. No `ssh-rsa` (RSA with SHA-1) and no `ssh-dss`. */
    val MODERN = listOf("ssh-ed25519", "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521", "rsa-sha2-512", "rsa-sha2-256")
    private val RSA = listOf("rsa-sha2-512", "rsa-sha2-256")

    /**
     * The key type behind an algorithm name. sshlib reports the negotiated signature algorithm, so one RSA key shows up as
     * `rsa-sha2-512` or `rsa-sha2-256` (or `ssh-rsa` in a pin made by an older build); all three are the type `ssh-rsa`, the
     * name inside the key blob. Every other host-key algorithm names its key type directly.
     */
    fun keyType(algorithm: String): String = if (algorithm == "ssh-rsa" || algorithm.startsWith("rsa-sha2-")) "ssh-rsa" else algorithm

    /**
     * What to offer the server. With a pin, only the pinned key's type, so a server that has added another key (say Ed25519
     * next to a pinned ECDSA key) presents the pinned one instead of reading as Changed. Without a pin, [MODERN]. A pin of a
     * type no longer offered (`ssh-dss`, an unknown name) gets [MODERN]: the server's modern key then shows as Changed, with
     * both fingerprints, rather than the connection quietly using a retired algorithm.
     */
    fun offered(pin: PinnedHostKey?): List<String> {
        val type = pin?.let { keyType(it.algorithm) } ?: return MODERN
        return when (type) {
            "ssh-rsa" -> RSA
            in MODERN -> listOf(type)
            else -> MODERN
        }
    }
}

sealed interface HostKeyState {
    /** Never seen: show algorithm and fingerprint, wait for the user. Nothing is authenticated meanwhile. */
    data class Unknown(val presented: PresentedHostKey) : HostKeyState
    data class Pinned(val pin: PinnedHostKey) : HostKeyState
    /** The key differs from the pin: blocked, both fingerprints shown, no automatic replacement. */
    data class Changed(val pin: PinnedHostKey, val presented: PresentedHostKey) : HostKeyState
}

/**
 * The only decision point for host keys. [evaluate] is what the SSH library's verifier calls before any
 * authentication; it never replaces a pin. A pin is created only by [pinAccepted] (or [acceptUnknown]), after the user
 * agrees, and replaced only by [replaceChanged], which exists for an explicit user action and nothing else.
 */
class HostKeyPolicy(private val store: HostKeyStore, private val clock: () -> Long) {
    private val writeLock = Mutex()

    /** The pin for [profileId], or null on first contact. Throws [HostKeyStoreCorrupt] when the store cannot be read. */
    suspend fun pinFor(profileId: String): PinnedHostKey? = store.find(profileId)

    /**
     * Pins a first-contact key the user accepted. The connector calls it only after the key exchange completed, so the
     * server has proved it holds the key. Returns the state that holds afterwards: [HostKeyState.Pinned], also when a
     * concurrent attempt pinned the same key first, or [HostKeyState.Changed] when a different key was pinned meanwhile.
     * Never replaces a pin.
     */
    suspend fun pinAccepted(profileId: String, endpoint: String, presented: PresentedHostKey): HostKeyState = writeLock.withLock {
        when (val state = evaluate(profileId, endpoint, presented)) {
            is HostKeyState.Unknown -> HostKeyState.Pinned(pinOf(profileId, endpoint, presented).also { store.save(it) })
            else -> state
        }
    }

    suspend fun evaluate(profileId: String, endpoint: String, presented: PresentedHostKey): HostKeyState {
        val pin = store.find(profileId) ?: return HostKeyState.Unknown(presented)
        // Same key, whichever RSA signature algorithm this connection negotiated.
        val same = HostKeyAlgorithms.keyType(pin.algorithm) == HostKeyAlgorithms.keyType(presented.algorithm) && MessageDigest.isEqual(pin.blob, presented.blob)
        return if (same) {
            store.touch(profileId, clock())
            HostKeyState.Pinned(pin)
        } else {
            HostKeyState.Changed(pin, presented)
        }
    }

    suspend fun acceptUnknown(profileId: String, endpoint: String, presented: PresentedHostKey): PinnedHostKey = writeLock.withLock {
        check(store.find(profileId) == null) { "profile $profileId already has a pin; use replaceChanged" }
        pinOf(profileId, endpoint, presented).also { store.save(it) }
    }

    /**
     * Replaces the pin the user was shown ([expected]) with the key they approved. Fails when the stored pin is no longer
     * [expected], so an approval cannot overwrite a pin that changed after the dialog was drawn.
     */
    suspend fun replaceChanged(profileId: String, endpoint: String, expected: PinnedHostKey, presented: PresentedHostKey): PinnedHostKey = writeLock.withLock {
        val current = checkNotNull(store.find(profileId)) { "profile $profileId has no pin to replace" }
        check(HostKeyAlgorithms.keyType(current.algorithm) == HostKeyAlgorithms.keyType(expected.algorithm) && MessageDigest.isEqual(current.blob, expected.blob)) {
            "the pin for $profileId is not the one that was shown"
        }
        pinOf(profileId, endpoint, presented).also { store.save(it) }
    }

    private fun pinOf(profileId: String, endpoint: String, p: PresentedHostKey): PinnedHostKey {
        val now = clock()
        return PinnedHostKey(profileId, endpoint, p.algorithm, p.blob, p.fingerprint, now, now)
    }
}
