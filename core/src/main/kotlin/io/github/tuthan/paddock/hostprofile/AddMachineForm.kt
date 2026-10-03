package io.github.tuthan.paddock.hostprofile

import io.github.tuthan.paddock.net.EndpointClass
import io.github.tuthan.paddock.net.GateDecision
import io.github.tuthan.paddock.net.classifyEndpoint

/** What the Add machine fields hold, as typed. Nothing is trimmed or parsed until [AddMachineForm] looks at it. */
data class AddMachineInput(
    val host: String = "",
    val port: String = "22",
    val user: String = "",
    val key: KeyKind = KeyKind.Phone,
    val importedKeyId: String? = null,
    /** Optional herdr session name; blank means the running default. */
    val session: String = "",
    /**
     * The host-key fingerprints a pairing link named for this host and port, while the fields still say what the link said;
     * null for a machine typed in. [AddMachineForm.profile] ignores it: the caller hands it to the host-key broker, which
     * compares the key the machine presents with it before the trust dialog.
     */
    val pairedFingerprints: List<String>? = null,
)

/** One message per field, null when the field is fine. */
data class FieldErrors(val host: String? = null, val port: String? = null, val user: String? = null, val session: String? = null) {
    val any: Boolean get() = host != null || port != null || user != null || session != null
}

/** What the route note under the host field says. Local addresses are the only ones the Android 17 grant concerns. */
enum class RouteNote {
    /** Nothing typed yet: the note recommends a VPN route for a phone that leaves the house. */
    Empty,
    /** Not a local-network address (a name, a tailnet or public address): no grant involved. */
    NotLocal,
    /** A LAN address and the grant is not needed or is already given. */
    LocalReady,
    /** A LAN address, the grant is required and missing: the screen asks for it in context before Connect. */
    LocalNeedsGrant,
}

/** Validation and derivation for the Add machine screen. No state, no I/O. */
object AddMachineForm {
    fun normalizeHost(raw: String): String = raw.trim().removeSurrounding("[", "]")

    fun errors(input: AddMachineInput): FieldErrors {
        val host = input.host.trim()
        val hostError = when {
            host.isEmpty() -> "Enter a hostname or IP address."
            "://" in host || '@' in host || '/' in host ->
                "Enter just the host, like 192.168.1.20 or box.example.ts.net. The user has its own field."
            host.any { it.isWhitespace() } -> "A host cannot contain spaces."
            else -> try { HostProfile("probe", "probe", normalizeHost(host), 22, "probe"); null } catch (_: IllegalArgumentException) {
                "That does not look like a hostname or an IP address."
            }
        }
        val port = input.port.trim().toIntOrNull()
        val portError = if (port == null || port !in 1..65535) "Enter a port from 1 to 65535." else null
        val user = input.user.trim()
        val userError = when {
            user.isEmpty() -> "Enter the user name to sign in as."
            else -> try { HostProfile("probe", "probe", "probe", 22, user); null } catch (_: IllegalArgumentException) {
                "Use letters, digits, dots, dashes or underscores."
            }
        }
        val name = input.session.trim()
        val sessionError = if (name.isNotEmpty() && !HostProfile.SESSION.matches(name)) "Start with a letter or digit; then letters, digits, dots, dashes or underscores, as `herdr session list` shows it." else null
        return FieldErrors(hostError, portError, userError, sessionError)
    }

    /** A profile for valid input; null otherwise. The id is derived from the host and made unique against [existingIds]. */
    fun profile(input: AddMachineInput, existingIds: Set<String>): HostProfile? {
        if (errors(input).any) return null
        val host = normalizeHost(input.host)
        return try {
            HostProfile(
                id = idFor(host, existingIds), name = host.take(60), host = host, port = input.port.trim().toInt(), user = input.user.trim(),
                key = input.key, importedKeyId = if (input.key == KeyKind.Imported) input.importedKeyId else null,
                session = input.session.trim().ifEmpty { null },
            )
        } catch (_: IllegalArgumentException) { null }
    }

    fun idFor(host: String, existingIds: Set<String>): String {
        val base = host.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("")
            .replace(Regex("-+"), "-").trim('-').take(36).trim('-').ifEmpty { "machine" }
        if (base !in existingIds) return base
        var n = 2
        while ("$base-$n" in existingIds) n++
        return "$base-$n"
    }

    /**
     * [grant] is the gate's decision for this host on this device and build. [endpoint] defaults to the text alone; once
     * typing settles the caller passes the resolved class, so a LAN name such as `nas.lan` is described as local.
     */
    fun route(host: String, grant: GateDecision, endpoint: EndpointClass = classifyEndpoint(normalizeHost(host))): RouteNote = when {
        host.isBlank() -> RouteNote.Empty
        endpoint == EndpointClass.NotLocal -> RouteNote.NotLocal
        grant == GateDecision.NeedsGrant -> RouteNote.LocalNeedsGrant
        else -> RouteNote.LocalReady
    }
}
