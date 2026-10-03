package io.github.tuthan.paddock.hostkey

/**
 * What a host-key dialog shows. Built only from keys the server presented and the pin the user already trusted; the
 * dialog layer adds nothing and decides nothing. Fingerprints are `SHA256:` strings, the form `ssh-keygen -l` prints.
 */
sealed interface HostKeyPrompt {
    val endpoint: String

    /** First contact: nothing is trusted yet and nothing has been authenticated. */
    data class FirstTrust(
        override val endpoint: String, val algorithm: String, val fingerprint: String, val compareCommand: String,
        /** True when the machine was added from a pairing link and this fingerprint is one the link named. The user still decides. */
        val matchesPairingLink: Boolean = false,
    ) : HostKeyPrompt

    /**
     * The machine was added from a pairing link and presented a key the link does not name. Nothing was trusted and the connect
     * has already failed; the dialog only shows both and offers no way to trust.
     */
    data class PairingMismatch(
        override val endpoint: String, val algorithm: String, val presentedFingerprint: String, val linkFingerprints: List<String>, val compareCommand: String,
    ) : HostKeyPrompt

    /** The host presented a key different from the pin. Nothing was authenticated; the pin stays unless the user replaces it. */
    data class Changed(
        override val endpoint: String,
        val oldAlgorithm: String, val oldFingerprint: String, val oldFirstSeenMillis: Long,
        val newAlgorithm: String, val newFingerprint: String, val compareCommand: String,
    ) : HostKeyPrompt
}

object HostKeyPrompts {
    fun firstTrust(endpoint: String, presented: PresentedHostKey, linkFingerprints: List<String>? = null) = HostKeyPrompt.FirstTrust(
        endpoint, displayAlgorithm(presented.algorithm), presented.fingerprint, compareCommand(presented.algorithm),
        matchesPairingLink = linkFingerprints != null && presented.fingerprint in linkFingerprints,
    )

    fun pairingMismatch(refusal: PairingRefusal) = HostKeyPrompt.PairingMismatch(
        refusal.endpoint, displayAlgorithm(refusal.presented.algorithm), refusal.presented.fingerprint, refusal.expected, compareCommand(refusal.presented.algorithm),
    )

    fun changed(endpoint: String, state: HostKeyState.Changed) = HostKeyPrompt.Changed(
        endpoint, displayAlgorithm(state.pin.algorithm), state.pin.fingerprint, state.pin.firstSeenMillis,
        displayAlgorithm(state.presented.algorithm), state.presented.fingerprint, compareCommand(state.presented.algorithm),
    )

    fun displayAlgorithm(wire: String): String = when {
        wire == "ssh-ed25519" -> "ED25519"
        wire == "ecdsa-sha2-nistp256" -> "ECDSA P-256"
        wire == "ecdsa-sha2-nistp384" -> "ECDSA P-384"
        wire == "ecdsa-sha2-nistp521" -> "ECDSA P-521"
        wire == "ssh-rsa" || wire.startsWith("rsa-sha2-") -> "RSA"
        else -> wire
    }

    /** Run on the host, it prints the fingerprint of the key sshd would present, to compare with what the phone shows. */
    fun compareCommand(wire: String): String {
        val file = when {
            wire == "ssh-ed25519" -> "ed25519"
            wire.startsWith("ecdsa-sha2-") -> "ecdsa"
            wire == "ssh-rsa" || wire.startsWith("rsa-sha2-") -> "rsa"
            else -> null
        }
        return if (file != null) "ssh-keygen -lf /etc/ssh/ssh_host_${file}_key.pub" else "ssh-keygen -lf /etc/ssh/ssh_host_*_key.pub"
    }
}
