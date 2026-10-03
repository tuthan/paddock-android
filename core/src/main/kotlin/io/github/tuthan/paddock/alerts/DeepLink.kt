package io.github.tuthan.paddock.alerts

import io.github.tuthan.paddock.cli.HerdrCli
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.identity.HostProfileId
import io.github.tuthan.paddock.identity.TargetRef
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** The two states that ever alert. Others are never put in a link, so a link naming one is refused. */
enum class AlertState(val wire: String) {
    Blocked("blocked"), Done("done");

    companion object { fun fromWire(s: String): AlertState? = entries.firstOrNull { it.wire == s } }
}

/**
 * What an alert says: which terminal, what the sender saw and when. A hint, never authority: nothing is sent to an agent
 * because one arrived, and the phone never trusts [state] over a fresh read. [sequence] is the relay's own counter
 * (strictly increasing across its restarts), or, for a notification Paddock raised itself, the agent's `state_change_seq`;
 * it and [atSeconds] drive dedupe and the age line, nothing else. A link never carries anything phone-local (no epoch).
 */
data class AlertHint(
    val target: TargetRef,
    val paneId: String,
    val state: AlertState,
    val atSeconds: Long,
    val sequence: Long,
)

enum class Rejection {
    /** Not a string, or not this app's scheme and host. */
    NotAPaddockLink,
    /** Longer than any link the relay writes. */
    TooLong,
    /** Characters outside printable ASCII, a fragment, a broken percent escape, or too many parameters. */
    Malformed,
    MissingField,
    DuplicateField,
    /** A field outside its alphabet or range. Carries the field's key, never its value. */
    BadField,
}

/**
 * What a push that names no terminal says: which machine and a nonce for dedupe. The UnifiedPush payload carries nothing else, so the
 * phone's own notification links here and the app reads that machine's herd before saying anything about it.
 */
data class MachineHint(val host: HostProfileId, val nonce: String)

sealed interface DeepLinkResult {
    data class Valid(val hint: AlertHint) : DeepLinkResult
    data class Machine(val hint: MachineHint) : DeepLinkResult
    data class Rejected(val reason: Rejection, val field: String? = null) : DeepLinkResult
}

/**
 * `paddock://open?h=<profile>&s=<session>&t=<terminal_id>&p=<pane_id>&st=<state>&at=<unix seconds>&n=<sequence>`, and for a push that
 * names no terminal `paddock://machine?h=<profile>&n=<nonce>` (only this app's own notification builds it; no intent filter admits it).
 *
 * The intent filter admits any app's VIEW intent, so this parser is the boundary: it accepts printable ASCII only, bounds the
 * length and the parameter count, decodes percent escapes strictly, and checks every field against the alphabet its owner uses
 * (a profile id as a [HostProfile] id, a session as herdr's session name). Unknown keys are ignored (a later relay may add
 * fields) and never decoded; a repeated known key is refused rather than resolved by position. What a valid link may do is
 * open a screen: the target is then resolved against a fresh read.
 */
object DeepLink {
    const val SCHEME = "paddock"
    const val HOST = "open"
    const val MAX_LENGTH = 512
    private const val MAX_PARAMS = 16
    private const val PREFIX = "$SCHEME://$HOST?"
    private const val MACHINE_PREFIX = "$SCHEME://machine?"
    private const val MAX_SECONDS = 4_102_444_800L          // 2100-01-01
    private const val MAX_SEQUENCE = 9_007_199_254_740_991L // 2^53 - 1, what the relay's JSON numbers can hold

    private val TERMINAL = Regex("[A-Za-z0-9_-]{1,64}")
    private val PANE = Regex("[A-Za-z0-9:._-]{1,32}")
    private val SECONDS = Regex("[0-9]{1,11}")
    private val SEQUENCE = Regex("[0-9]{1,16}")
    private val KEYS = setOf("h", "s", "t", "p", "st", "at", "n")
    private val MACHINE_KEYS = setOf("h", "n")
    private val NONCE = Regex("[A-Za-z0-9]{1,64}")

    fun parse(text: String?): DeepLinkResult {
        if (text == null) return DeepLinkResult.Rejected(Rejection.NotAPaddockLink)
        if (text.length > MAX_LENGTH) return DeepLinkResult.Rejected(Rejection.TooLong)
        val machine = text.startsWith(MACHINE_PREFIX, ignoreCase = true)
        if (!machine && !text.startsWith(PREFIX, ignoreCase = true)) return DeepLinkResult.Rejected(Rejection.NotAPaddockLink)
        if (text.any { it.code < 0x21 || it.code > 0x7e } || '#' in text) return DeepLinkResult.Rejected(Rejection.Malformed)
        val parts = text.substring(if (machine) MACHINE_PREFIX.length else PREFIX.length).split('&')
        val keys = if (machine) MACHINE_KEYS else KEYS
        if (parts.size > MAX_PARAMS) return DeepLinkResult.Rejected(Rejection.Malformed)
        val values = HashMap<String, String>()
        for (part in parts) {
            val eq = part.indexOf('=')
            val key = if (eq < 0) part else part.substring(0, eq)
            if (key !in keys) continue
            if (eq < 0) return DeepLinkResult.Rejected(Rejection.BadField, key)
            if (key in values) return DeepLinkResult.Rejected(Rejection.DuplicateField, key)
            values[key] = decode(part.substring(eq + 1)) ?: return DeepLinkResult.Rejected(Rejection.Malformed)
        }
        for (key in if (machine) listOf("h", "n") else listOf("h", "s", "t", "p", "st", "at", "n")) if (key !in values) return DeepLinkResult.Rejected(Rejection.MissingField, key)

        fun bad(key: String) = DeepLinkResult.Rejected(Rejection.BadField, key)
        val h = values.getValue("h").takeIf { HostProfile.ID.matches(it) } ?: return bad("h")
        if (machine) {
            val nonce = values.getValue("n").takeIf { NONCE.matches(it) } ?: return bad("n")
            return DeepLinkResult.Machine(MachineHint(HostProfileId(h), nonce))
        }
        val s = values.getValue("s").takeIf { HerdrCli.SESSION_NAME.matches(it) } ?: return bad("s")
        val t = values.getValue("t").takeIf { TERMINAL.matches(it) } ?: return bad("t")
        val p = values.getValue("p").takeIf { PANE.matches(it) } ?: return bad("p")
        val st = AlertState.fromWire(values.getValue("st")) ?: return bad("st")
        val at = values.getValue("at").takeIf { SECONDS.matches(it) }?.toLongOrNull()?.takeIf { it in 1..MAX_SECONDS } ?: return bad("at")
        val n = values.getValue("n").takeIf { SEQUENCE.matches(it) }?.toLongOrNull()?.takeIf { it in 0..MAX_SEQUENCE } ?: return bad("n")
        return DeepLinkResult.Valid(AlertHint(TargetRef(HostProfileId(h), s, t), p, st, at, n))
    }

    /** The link for [hint], as the relay writes it. Used for the intents of notifications Paddock raises itself. */
    fun build(hint: AlertHint): String {
        fun e(v: String) = buildString { for (b in v.toByteArray(Charsets.UTF_8)) { val c = b.toInt() and 0xff; if (c.toChar() in UNRESERVED) append(c.toChar()) else append('%').append("%02X".format(c)) } }
        return PREFIX + listOf(
            "h" to hint.target.host.value, "s" to hint.target.session, "t" to hint.target.terminalId, "p" to hint.paneId,
            "st" to hint.state.wire, "at" to hint.atSeconds.toString(), "n" to hint.sequence.toString(),
        ).joinToString("&") { (k, v) -> "$k=${e(v)}" }
    }

    /** The link of a notification Paddock raised for a push that named no terminal. */
    fun buildMachine(hint: MachineHint): String = "${MACHINE_PREFIX}h=${hint.host.value}&n=${hint.nonce}"

    private val UNRESERVED = (('A'..'Z') + ('a'..'z') + ('0'..'9') + listOf('-', '.', '_', '~')).toSet()

    /** Percent-decodes [v] strictly: a `%` not followed by two hex digits, or bytes that are not UTF-8, make the link malformed. */
    private fun decode(v: String): String? {
        if ('%' !in v) return v
        val out = java.io.ByteArrayOutputStream()
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
