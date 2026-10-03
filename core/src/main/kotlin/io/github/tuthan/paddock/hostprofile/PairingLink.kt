package io.github.tuthan.paddock.hostprofile

import io.github.tuthan.paddock.cli.HerdrCli
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * What a pairing link says about a machine: where it is, who to sign in as, optionally which herdr session, and the
 * fingerprint or fingerprints of its SSH host keys. It carries no key and no secret, and nothing in it is trusted: it
 * pre-fills Add machine, and the first connection compares the key the machine presents with [fingerprints]. A match only
 * adds a line to the trust dialog; the user still taps Trust, and a key the link does not name is refused.
 */
data class PairingLink(val host: String, val port: Int, val user: String, val fingerprints: List<String>, val session: String?) {
    /** The input Add machine starts from: the machine's fields, this phone's key, and the link's fingerprints to compare. */
    fun toInput() = AddMachineInput(host = host, port = port.toString(), user = user, session = session.orEmpty(), pairedFingerprints = fingerprints)
}

enum class PairingRejection {
    /** Not a string, or not `paddock://pair?`. */
    NotAPairingLink,
    TooLong,
    /** Characters outside printable ASCII, a fragment, a broken percent escape, or too many parameters. */
    Malformed,
    /** A version this build does not know; the link needs a newer Paddock. */
    UnsupportedVersion,
    MissingField,
    DuplicateField,
    /** A field outside its alphabet or range. Carries the field's key, never its value. */
    BadField,
}

sealed interface PairingResult {
    data class Valid(val link: PairingLink) : PairingResult
    data class Rejected(val reason: PairingRejection, val field: String? = null) : PairingResult
}

/**
 * `paddock://pair?v=1&host=<host>&port=<port>&user=<user>&fp=<SHA256:…[,SHA256:…]>[&session=<name>]`, produced on the machine by the
 * herdr plugin's `show-pairing` action and opened on the phone (a tap, or pasted text). Any app can fire the intent, so this
 * parser is the boundary: printable ASCII only, a length and a parameter bound, strict percent-decoding, every value checked
 * against the alphabet its owner uses (a host and user as a [HostProfile] holds them, a session as herdr names it, a
 * fingerprint as `ssh-keygen -l` prints it), a repeated known key refused rather than resolved by position, and unknown keys
 * ignored. `fp` lists one to four fingerprints (a machine can hold several host keys and the phone offers them in its own order).
 */
object PairingLinks {
    const val SCHEME = "paddock"
    const val HOST = "pair"
    const val PREFIX = "$SCHEME://$HOST?"
    const val MAX_LENGTH = 1024
    const val MAX_FINGERPRINTS = 4
    private const val MAX_PARAMS = 16
    private val KEYS = setOf("v", "host", "port", "user", "fp", "session")
    private val REQUIRED = listOf("v", "host", "port", "user", "fp")
    private val HOSTNAME = Regex("[A-Za-z0-9._:\\[\\]-]{1,253}")
    private val USER = Regex("[A-Za-z0-9._][A-Za-z0-9._-]{0,63}")
    private val PORT = Regex("[0-9]{1,5}")
    private val FINGERPRINT = Regex("SHA256:[A-Za-z0-9+/]{43}")

    /** The first token of pasted [text] that starts with `paddock://pair?`, for a link copied together with other words. Null when there is none. */
    fun find(text: String?): String? = text?.split(Regex("\\s+"))?.firstOrNull { it.startsWith(PREFIX, ignoreCase = true) }

    fun parse(text: String?): PairingResult {
        if (text == null) return PairingResult.Rejected(PairingRejection.NotAPairingLink)
        if (text.length > MAX_LENGTH) return PairingResult.Rejected(PairingRejection.TooLong)
        if (!text.startsWith(PREFIX, ignoreCase = true)) return PairingResult.Rejected(PairingRejection.NotAPairingLink)
        if (text.any { it.code < 0x21 || it.code > 0x7e } || '#' in text) return PairingResult.Rejected(PairingRejection.Malformed)
        val parts = text.substring(PREFIX.length).split('&')
        if (parts.size > MAX_PARAMS) return PairingResult.Rejected(PairingRejection.Malformed)
        val values = HashMap<String, String>()
        for (part in parts) {
            val eq = part.indexOf('=')
            val key = if (eq < 0) part else part.substring(0, eq)
            if (key !in KEYS) continue
            if (eq < 0) return PairingResult.Rejected(PairingRejection.BadField, key)
            if (key in values) return PairingResult.Rejected(PairingRejection.DuplicateField, key)
            values[key] = decode(part.substring(eq + 1)) ?: return PairingResult.Rejected(PairingRejection.Malformed)
        }
        for (key in REQUIRED) if (key !in values) return PairingResult.Rejected(PairingRejection.MissingField, key)

        fun bad(key: String) = PairingResult.Rejected(PairingRejection.BadField, key)
        if (values.getValue("v") != "1") return PairingResult.Rejected(PairingRejection.UnsupportedVersion, "v")
        val rawHost = values.getValue("host").takeIf { HOSTNAME.matches(it) } ?: return bad("host")
        val host = AddMachineForm.normalizeHost(rawHost)
        // Brackets only wrap an IPv6 literal, and a colon only appears in one: `[::1`, `a:b` and `[name]` are not hosts.
        val ipv6 = host.count { it == ':' } >= 2 && host.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        if ('[' in host || ']' in host || (':' in host && !ipv6) || (rawHost != host && !ipv6)) return bad("host")
        // The profile's own rule decides what a host is, so a link can never hold one the app would refuse to save.
        if (AddMachineForm.errors(AddMachineInput(host = host, port = "22", user = "probe")).host != null || host.none { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == ':' }) return bad("host")
        val port = values.getValue("port").takeIf { PORT.matches(it) }?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return bad("port")
        val user = values.getValue("user").takeIf { USER.matches(it) } ?: return bad("user")
        val fingerprints = values.getValue("fp").split(',')
        if (fingerprints.size !in 1..MAX_FINGERPRINTS || fingerprints.any { !FINGERPRINT.matches(it) } || fingerprints.toSet().size != fingerprints.size) return bad("fp")
        val session = values["session"]?.let { s -> s.takeIf { HerdrCli.SESSION_NAME.matches(it) } ?: return bad("session") }
        return PairingResult.Valid(PairingLink(host, port, user, fingerprints, session))
    }

    /** The link for [link], as the plugin writes it (raw fingerprints). Used by tests and by the docs; the app only reads links. */
    fun build(link: PairingLink): String =
        PREFIX + listOfNotNull(
            "v=1", "host=${link.host}", "port=${link.port}", "user=${link.user}", "fp=${link.fingerprints.joinToString(",")}", link.session?.let { "session=$it" },
        ).joinToString("&")

    /** Percent-decodes [v] strictly: a `%` not followed by two hex digits, or bytes that are not UTF-8, make the link malformed. */
    private fun decode(v: String): String? {
        if ('%' !in v) return v
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '%') {
                if (i + 2 >= v.length) return null
                val hi = Character.digit(v[i + 1], 16)
                val lo = Character.digit(v[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                out.write(hi * 16 + lo)
                i += 3
            } else { out.write(c.code); i++ }
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(out.toByteArray())).toString()
        } catch (_: CharacterCodingException) { null }
    }
}
