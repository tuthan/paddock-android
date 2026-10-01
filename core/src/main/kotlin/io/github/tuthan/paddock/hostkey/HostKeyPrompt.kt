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
    ) : HostKeyPrompt

    /** The host presented a key different from the pin. Nothing was authenticated; the pin stays unless the user replaces it. */
    data class Changed(
        override val endpoint: String,
        val oldAlgorithm: String, val oldFingerprint: String, val oldFirstSeenMillis: Long,
        val newAlgorithm: String, val newFingerprint: String, val compareCommand: String,
    ) : HostKeyPrompt
}

object HostKeyPrompts {
    fun firstTrust(endpoint: String, presented: PresentedHostKey) =
        HostKeyPrompt.FirstTrust(endpoint, displayAlgorithm(presented.algorithm), presented.fingerprint, compareCommand(presented.algorithm))

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
