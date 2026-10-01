package io.github.tuthan.paddock.ssh

import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenSshKeysTest {
    private fun p256() = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun fingerprint(blob: ByteArray) =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))

    private fun sshKeygen(vararg args: String): Pair<Int, String>? {
        if (!File("/usr/bin/ssh-keygen").canExecute()) return null
        val p = ProcessBuilder("/usr/bin/ssh-keygen", *args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(10, TimeUnit.SECONDS)
        return p.exitValue() to out
    }

    /** The strongest check available offline: OpenSSH itself must accept the line and agree on the fingerprint. */
    @Test
    fun sshKeygenAcceptsTheLineAndAgreesOnTheFingerprint() {
        repeat(20) {
            val pub = p256().public as ECPublicKey
            val file = File.createTempFile("paddock-key", ".pub").also { it.deleteOnExit() }
            file.writeText(OpenSshKeys.publicLine(pub, "paddock-test") + "\n")
            val (code, out) = sshKeygen("-l", "-f", file.path) ?: return
            assertEquals(0, code, out)
            assertTrue(out.contains(fingerprint(OpenSshKeys.publicBlob(pub))), "ssh-keygen said: $out")
            assertTrue(out.contains("(ECDSA)"), out)
        }
    }

    @Test
    fun coordinatesWithLeadingZeroBytesStillEncodeToFixedWidth() {
        // Generate until one coordinate has a leading zero byte (about 1 in 128 keys); the blob must stay 32 bytes each.
        var checked = 0
        repeat(2000) {
            val pub = p256().public as ECPublicKey
            val x = pub.w.affineX.toByteArray().dropWhile { it == 0.toByte() }.size
            val y = pub.w.affineY.toByteArray().dropWhile { it == 0.toByte() }.size
            if (x < 32 || y < 32) {
                // 4+19 name, 4+8 curve, 4+65 point
                assertEquals(23 + 12 + 69, OpenSshKeys.publicBlob(pub).size)
                checked++
            }
        }
        assertTrue(checked > 0, "never saw a short coordinate in 2000 keys")
    }

    @Test
    fun rejectsKeysThatAreNotP256() {
        val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair().public as ECPublicKey
        assertFailsWith<IllegalArgumentException> { OpenSshKeys.publicLine(p384, "x") }
    }

    @Test
    fun derSignatureBecomesAnSshSignatureThatDecodesBackToTheSameRs() {
        val pair = p256()
        repeat(50) {
            val sig = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(byteArrayOf(it.toByte(), 1, 2)) }.sign()
            val ssh = OpenSshKeys.signatureFromDer(sig)
            // string("ecdsa-sha2-nistp256") then string(blob); blob is mpint r, mpint s
            fun readString(b: ByteArray, at: Int): Pair<ByteArray, Int> {
                val n = ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)
                return b.copyOfRange(at + 4, at + 4 + n) to at + 4 + n
            }
            val (name, next) = readString(ssh, 0)
            assertEquals("ecdsa-sha2-nistp256", String(name))
            val (blob, end) = readString(ssh, next)
            assertEquals(ssh.size, end)
            val (r, afterR) = readString(blob, 0)
            val (s, afterS) = readString(blob, afterR)
            assertEquals(blob.size, afterS)
            // Rebuild a DER signature from the decoded r and s and verify it: the SSH encoding lost nothing.
            fun der(v: java.math.BigInteger): ByteArray = v.toByteArray().let { byteArrayOf(0x02, it.size.toByte()) + it }
            val body = der(java.math.BigInteger(r)) + der(java.math.BigInteger(s))
            val rebuilt = byteArrayOf(0x30, body.size.toByte()) + body
            assertTrue(
                Signature.getInstance("SHA256withECDSA").apply { initVerify(pair.public); update(byteArrayOf(it.toByte(), 1, 2)) }.verify(rebuilt),
                "rebuilt signature did not verify",
            )
        }
    }

    @Test
    fun rejectsNonDerInput() {
        assertFailsWith<IllegalArgumentException> { OpenSshKeys.rsBlob(byteArrayOf(0x31, 0)) }
        assertFailsWith<IllegalArgumentException> { OpenSshKeys.rsBlob(ByteArray(0)) }
    }
}
