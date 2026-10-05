package io.github.tuthan.paddock.pairing

/**
 * The one-line exchange with the desktop's `pair` popup (the plugin's PROTOCOL.md). The phone sends one line and reads one line:
 *
 * ```
 * paddock-pair/1 key <sid> <public key line>
 * paddock-pair/1 status <sid>
 * ```
 *
 * and the desktop answers `paddock-pair/1 <word>`. Nothing secret travels either way: the key line is public, `sid` is a session
 * handle taken from the QR, and the answer is a result word.
 */
object PairingWire {
    const val VERSION = "paddock-pair/1"
    const val MAX_REQUEST_BYTES = 4096
    private val SID = Regex("[A-Za-z0-9_-]{22}")

    fun keyRequest(sid: String, keyLine: String): ByteArray {
        require(SID.matches(sid)) { "invalid session handle" }
        require(keyLine.isNotEmpty() && keyLine.none { it == '\n' || it == '\r' || it.code < 0x20 || it.code > 0x7e }) { "invalid key line" }
        val bytes = "$VERSION key $sid $keyLine\n".toByteArray(Charsets.US_ASCII)
        require(bytes.size <= MAX_REQUEST_BYTES) { "request too long" }
        return bytes
    }

    fun statusRequest(sid: String): ByteArray {
        require(SID.matches(sid)) { "invalid session handle" }
        return "$VERSION status $sid\n".toByteArray(Charsets.US_ASCII)
    }

    fun decodeReply(line: String?): PairingReply {
        if (line == null) return PairingReply.Malformed
        val parts = line.trim().split(' ')
        if (parts.size != 2 || parts[0] != VERSION) return PairingReply.Malformed
        return when (parts[1]) {
            "pending" -> PairingReply.Pending
            "ok" -> PairingReply.Ok
            "rejected" -> PairingReply.Rejected
            "expired" -> PairingReply.Expired
            "refused" -> PairingReply.Refused
            "busy" -> PairingReply.Busy
            "none" -> PairingReply.None
            else -> PairingReply.Malformed
        }
    }
}

enum class PairingReply { Pending, Ok, Rejected, Expired, Refused, Busy, None, Malformed }
