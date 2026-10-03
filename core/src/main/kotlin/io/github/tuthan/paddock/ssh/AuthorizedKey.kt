package io.github.tuthan.paddock.ssh

import java.security.MessageDigest
import java.util.Base64

/**
 * One `authorized_keys` line for this phone's key, as the enrollment flow accepts it: `ecdsa-sha2-nistp256 <base64> [comment]`
 * and nothing else. Exactly one line of printable ASCII with single spaces; no option prefix (`command=`, `from=`), no other
 * key type, a body that really is an OpenSSH P-256 public-key blob, and a comment of letters, digits, `@ . _ -` only. What
 * [parse] returns is therefore safe to put inside single quotes in a shell command, and is the only thing the copy command
 * and the host plugin's `authorize-phone` ever write. The host plugin implements the same rule in Python; keep them equal.
 */
class AuthorizedKey private constructor(val line: String, val comment: String?, private val blob: ByteArray) {
    /** `SHA256:` and unpadded base64 of the key blob, the form `ssh-keygen -l` prints, so the phone and the host can be compared. */
    val fingerprint: String =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

    override fun toString() = "AuthorizedKey($fingerprint)"

    companion object {
        const val MAX_BYTES = 1024
        private const val TYPE = OpenSshKeys.ECDSA_P256
        private const val CURVE = "nistp256"
        private val COMMENT = Regex("[A-Za-z0-9@._-]{1,64}")
        private val BASE64 = Regex("[A-Za-z0-9+/]+={0,2}")

        /** Null for anything that is not exactly one acceptable key line. Never throws and never echoes the input. */
        fun parse(text: String?): AuthorizedKey? {
            if (text == null || text.length > MAX_BYTES) return null
            if (text.any { it.code < 0x20 || it.code > 0x7e }) return null
            val parts = text.split(' ')
            if (parts.size !in 2..3 || parts[0] != TYPE) return null
            val body = parts[1]
            if (!BASE64.matches(body)) return null
            val comment = parts.getOrNull(2)
            if (comment != null && !COMMENT.matches(comment)) return null
            val blob = try { Base64.getDecoder().decode(body) } catch (_: IllegalArgumentException) { return null }
            // Canonical only: the body must be exactly what encoding the blob gives back (no stray padding or bit tricks).
            if (Base64.getEncoder().encodeToString(blob) != body || !isP256Blob(blob)) return null
            return AuthorizedKey(text, comment, blob)
        }

        /** `uint32 19 "ecdsa-sha2-nistp256" uint32 8 "nistp256" uint32 65 0x04 X Y`: 104 bytes, nothing after. */
        private fun isP256Blob(b: ByteArray): Boolean {
            var i = 0
            fun string(): ByteArray? {
                if (i + 4 > b.size) return null
                val n = ((b[i].toInt() and 0xff) shl 24) or ((b[i + 1].toInt() and 0xff) shl 16) or ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)
                i += 4
                if (n < 0 || n > b.size - i) return null
                return b.copyOfRange(i, i + n).also { i += n }
            }
            val type = string() ?: return false
            val curve = string() ?: return false
            val point = string() ?: return false
            return type.contentEquals(TYPE.toByteArray()) && curve.contentEquals(CURVE.toByteArray()) &&
                point.size == 65 && point[0] == 4.toByte() && i == b.size
        }
    }
}
