package io.github.tuthan.paddock.ssh

import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** AC-11.3's parser half: only one acceptable key line gets through, and OpenSSH agrees on what it is. */
class AuthorizedKeyTest {
    companion object {
        fun p256Line(comment: String? = "paddock@phone"): String {
            val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public as ECPublicKey
            val line = OpenSshKeys.publicLine(key, comment ?: "x")
            return if (comment == null) line.substringBeforeLast(' ') else line
        }
    }

    private val good = p256Line()
    private val body = good.split(' ')[1]

    @Test fun theLineThePhoneMakesIsAccepted() {
        val key = assertNotNull(AuthorizedKey.parse(good))
        assertEquals(good, key.line)
        assertEquals("paddock@phone", key.comment)
        assertNotNull(AuthorizedKey.parse(p256Line(null))).also { assertNull(it.comment) }
        assertNotNull(AuthorizedKey.parse(p256Line("a.b_c-d@e")))
        assertNotNull(AuthorizedKey.parse(p256Line("x".repeat(64))))
    }

    @Test fun theFingerprintIsWhatOpenSshPrints() {
        val key = assertNotNull(AuthorizedKey.parse(good))
        assertEquals(Regex("SHA256:[A-Za-z0-9+/]{43}").matches(key.fingerprint), true)
        if (!File("/usr/bin/ssh-keygen").canExecute()) return
        val f = Files.createTempFile("pub", ".pub").toFile().apply { writeText(good + "\n") }
        val p = ProcessBuilder("/usr/bin/ssh-keygen", "-lf", f.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor(10, TimeUnit.SECONDS)
        assertEquals(true, out.contains(key.fingerprint), "ssh-keygen: $out")
    }

    @Test fun hostileOrWrongLinesAreRefused() {
        val cases = mapOf(
            "empty" to "", "blank" to "   ", "null-ish" to "ecdsa-sha2-nistp256",
            "trailing newline" to "$good\n", "leading newline" to "\n$good", "two lines" to "$good\n$good", "CR" to good.replace(" paddock", "\r paddock"),
            "NUL" to "$good\u0000", "tab separator" to good.replaceFirst(' ', '\t'), "double space" to good.replace(" paddock", "  paddock"), "leading space" to " $good", "trailing space" to "$good ",
            "option prefix" to "command=\"/bin/sh\" $good", "from option" to "from=\"1.2.3.4\" $good", "no-pty prefix" to "restrict,$good",
            "other type" to "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl x",
            "rsa" to "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAAAgQC x", "p384" to good.replace("nistp256", "nistp384"),
            "comment quote" to "$good x'y", "comment semicolon" to "$good x;y", "comment subshell" to "$good \$(id)", "comment backtick" to "$good `id`",
            "comment space" to "$good a b", "comment slash" to "$good a/b", "comment unicode" to "$good café", "comment RTL" to "$good a‮b", "comment too long" to "$good ${"x".repeat(65)}",
            "private key" to "-----BEGIN OPENSSH PRIVATE KEY-----", "PEM body" to "-----BEGIN EC PRIVATE KEY----- MHcCAQEE",
            "body not base64" to "ecdsa-sha2-nistp256 !!!! x", "body empty" to "ecdsa-sha2-nistp256  x", "body truncated" to "ecdsa-sha2-nistp256 ${body.take(body.length - 8)} x",
            "body padding" to "ecdsa-sha2-nistp256 $body= x", "body not a blob" to "ecdsa-sha2-nistp256 ${Base64.getEncoder().encodeToString(ByteArray(104))} x",
            "blob with tail" to "ecdsa-sha2-nistp256 ${Base64.getEncoder().encodeToString(Base64.getDecoder().decode(body) + byteArrayOf(0))} x",
            "huge" to "ecdsa-sha2-nistp256 ${"A".repeat(5000)} x", "type case" to good.replaceFirst("ecdsa", "ECDSA"),
        )
        for ((name, text) in cases) assertNull(AuthorizedKey.parse(text), "should refuse: $name")
        assertNull(AuthorizedKey.parse(null))
    }

    @Test fun aRefusedLineIsNeverEchoedByTheErrorPath() {
        // parse returns null and has no message to leak; toString of a good key names only the fingerprint.
        val key = assertNotNull(AuthorizedKey.parse(good))
        assertEquals(false, key.toString().contains(body))
    }
}
