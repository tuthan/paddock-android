package io.github.tuthan.paddock.spike

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

object SpikeKeys {
    const val ALIAS = "paddock-spike-key"
    const val SSH_NAME = "ecdsa-sha2-nistp256"

    /** Creates the P-256 signing key on first use; the private half never leaves the Keystore. */
    fun keystoreKeyPair(): Pair<PrivateKey, ECPublicKey> {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build(),
                )
            }.generateKeyPair()
        }
        val entry = ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        return entry.privateKey to (entry.certificate.publicKey as ECPublicKey)
    }

    /** `ecdsa-sha2-nistp256 AAAA… comment`, the line sshd wants in authorized_keys. */
    fun openSshPublicLine(pub: ECPublicKey, comment: String): String {
        val x = unsigned(pub.w.affineX, 32)
        val y = unsigned(pub.w.affineY, 32)
        val point = byteArrayOf(4) + x + y
        val blob = ByteArrayOutputStream().apply {
            putString(SSH_NAME.toByteArray()); putString("nistp256".toByteArray()); putString(point)
        }.toByteArray()
        return "$SSH_NAME ${Base64.getEncoder().encodeToString(blob)} $comment"
    }

    fun sha256Fingerprint(blob: ByteArray): String =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

    /** `mpint r || mpint s` from a DER ECDSA signature: the blob inside an SSH ecdsa signature. */
    fun sshEcdsaBlob(der: ByteArray): ByteArray {
        var i = 0
        fun len(): Int { var l = der[i++].toInt() and 0xff; if (l >= 0x80) { val n = l and 0x7f; l = 0; repeat(n) { l = (l shl 8) or (der[i++].toInt() and 0xff) } }; return l }
        require(der[i++].toInt() == 0x30) { "not a DER sequence" }; len()
        fun int(): BigInteger { require(der[i++].toInt() == 0x02) { "not a DER integer" }; val l = len(); val v = BigInteger(der.copyOfRange(i, i + l)); i += l; return v }
        val r = int(); val s = int()
        return ByteArrayOutputStream().apply { putString(r.toByteArray()); putString(s.toByteArray()) }.toByteArray()
    }

    /** The whole SSH signature: `string(format) || string(blob)`. */
    fun sshEcdsaSignature(der: ByteArray): ByteArray =
        ByteArrayOutputStream().apply { putString(SSH_NAME.toByteArray()); putString(sshEcdsaBlob(der)) }.toByteArray()

    private fun unsigned(v: BigInteger, size: Int): ByteArray {
        val b = v.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(size - b.size) + b
    }

    private fun ByteArrayOutputStream.putString(b: ByteArray) {
        write(byteArrayOf((b.size ushr 24).toByte(), (b.size ushr 16).toByte(), (b.size ushr 8).toByte(), b.size.toByte())); write(b)
    }
}
