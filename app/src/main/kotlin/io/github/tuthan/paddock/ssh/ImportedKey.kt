package io.github.tuthan.paddock.ssh

import com.trilead.ssh2.crypto.PEMDecoder
import com.trilead.ssh2.crypto.PublicKeyUtils
import java.io.IOException
import java.security.KeyPair
import java.security.MessageDigest
import java.util.Base64

/** What inspecting an imported OpenSSH or PEM private key found. Nothing here is ever logged or persisted in clear. */
sealed interface ImportCheck {
    /** Usable. [keyType] is the SSH name (`ssh-ed25519`, `ecdsa-sha2-nistp256`, `ssh-rsa`); [fingerprint] is `SHA256:…`. */
    data class Ready(val keyType: String, val fingerprint: String, val encrypted: Boolean) : ImportCheck
    data object NeedsPassphrase : ImportCheck
    data object WrongPassphrase : ImportCheck
    data object NotAKey : ImportCheck
    data class Unsupported(val reason: String) : ImportCheck
}

/** A key that cannot be used; carries the classification so the caller never string-matches. */
class InvalidImportedKey(val check: ImportCheck) : Exception("imported key unusable: $check")

object ImportedKey {
    /** Parses and, when needed, decrypts [pem] in memory only. A passphrase is required exactly when the key is encrypted. */
    fun check(pem: CharArray, passphrase: String?): ImportCheck = try {
        val structure = decoder { PEMDecoder.parsePEM(pem) }
        val encrypted = decoder { PEMDecoder.isPEMEncrypted(structure) }
        if (encrypted && passphrase.isNullOrEmpty()) ImportCheck.NeedsPassphrase
        else {
            val pair = decoder { PEMDecoder.decode(structure, passphrase) }
            val blob = decoder { PublicKeyUtils.extractPublicKeyBlob(pair.public) }
            ImportCheck.Ready(keyTypeOf(blob), fingerprint(blob), encrypted)
        }
    } catch (e: IOException) {
        classify(e)
    } catch (e: java.security.GeneralSecurityException) {
        ImportCheck.Unsupported(e.message ?: "unsupported key")
    }

    /** sshlib's decoder throws unchecked exceptions on truncated or hostile input; that is "not a key", not a crash. */
    private inline fun <T> decoder(block: () -> T): T = try { block() } catch (e: RuntimeException) { throw IOException("malformed key data", e) }

    /** The SSH name is the first length-prefixed string of the public-key blob. */
    private fun keyTypeOf(blob: ByteArray): String {
        require(blob.size > 4) { "short public-key blob" }
        val n = ((blob[0].toInt() and 0xff) shl 24) or ((blob[1].toInt() and 0xff) shl 16) or ((blob[2].toInt() and 0xff) shl 8) or (blob[3].toInt() and 0xff)
        require(n in 1..64 && blob.size >= 4 + n) { "bad public-key blob" }
        return String(blob, 4, n, Charsets.US_ASCII)
    }

    /** The decoded pair for authentication; throws [InvalidImportedKey] with the classification when unusable. */
    fun keyPair(pem: CharArray, passphrase: String?): KeyPair {
        val checked = check(pem, passphrase)
        if (checked !is ImportCheck.Ready) throw InvalidImportedKey(checked)
        return PEMDecoder.decode(pem, passphrase)
    }

    private fun classify(e: IOException): ImportCheck {
        val m = e.message.orEmpty()
        return when {
            m.contains("Decryption failed", ignoreCase = true) || m.contains("passphrase", ignoreCase = true) -> ImportCheck.WrongPassphrase
            m.startsWith("malformed key data") || m.contains("PEM", ignoreCase = true) || m.contains("not a", ignoreCase = true) -> ImportCheck.NotAKey
            else -> ImportCheck.Unsupported(m.ifEmpty { "unsupported key" })
        }
    }

    private fun fingerprint(blob: ByteArray) =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
}
