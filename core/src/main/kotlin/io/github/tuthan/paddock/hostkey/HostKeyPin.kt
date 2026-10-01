package io.github.tuthan.paddock.hostkey

import java.security.MessageDigest
import java.util.Base64

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

sealed interface HostKeyState {
    /** Never seen: show algorithm and fingerprint, wait for the user. Nothing is authenticated meanwhile. */
    data class Unknown(val presented: PresentedHostKey) : HostKeyState
    data class Pinned(val pin: PinnedHostKey) : HostKeyState
    /** The key differs from the pin: blocked, both fingerprints shown, no automatic replacement. */
    data class Changed(val pin: PinnedHostKey, val presented: PresentedHostKey) : HostKeyState
}

/**
 * The only decision point for host keys. [evaluate] is what the SSH library's verifier calls before any
 * authentication; it never replaces a pin. A pin is created only by [acceptUnknown], after the user agrees,
 * and replaced only by [replaceChanged], which exists for an explicit user action and nothing else.
 */
class HostKeyPolicy(private val store: HostKeyStore, private val clock: () -> Long) {
    suspend fun evaluate(profileId: String, endpoint: String, presented: PresentedHostKey): HostKeyState {
        val pin = store.find(profileId) ?: return HostKeyState.Unknown(presented)
        val same = pin.algorithm == presented.algorithm && MessageDigest.isEqual(pin.blob, presented.blob)
        return if (same) {
            store.touch(profileId, clock())
            HostKeyState.Pinned(pin)
        } else {
            HostKeyState.Changed(pin, presented)
        }
    }

    suspend fun acceptUnknown(profileId: String, endpoint: String, presented: PresentedHostKey): PinnedHostKey {
        check(store.find(profileId) == null) { "profile $profileId already has a pin; use replaceChanged" }
        return pinOf(profileId, endpoint, presented).also { store.save(it) }
    }

    suspend fun replaceChanged(profileId: String, endpoint: String, presented: PresentedHostKey): PinnedHostKey {
        check(store.find(profileId) != null) { "profile $profileId has no pin to replace" }
        return pinOf(profileId, endpoint, presented).also { store.save(it) }
    }

    private fun pinOf(profileId: String, endpoint: String, p: PresentedHostKey): PinnedHostKey {
        val now = clock()
        return PinnedHostKey(profileId, endpoint, p.algorithm, p.blob, p.fingerprint, now, now)
    }
}
