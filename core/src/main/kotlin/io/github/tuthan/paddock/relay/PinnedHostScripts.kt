package io.github.tuthan.paddock.relay

/**
 * The scripts, unit file and example configuration Paddock installs on a machine, each checked against the pin bundled beside it (host/SOURCE.json) when
 * this is built: a mismatch throws [IllegalStateException] and the app does not start. [read] opens a bundled asset by name (Android assets in the app,
 * files in a test).
 */
class PinnedHostScripts(private val read: (String) -> ByteArray) {
    private fun text(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8).trim()

    /** The relay and the control helper are pinned by `<base>.sha256`; every other file by `<file>.sha256`. */
    private fun pinned(name: String, pinFile: String, what: String = "bundled $name"): Pair<ByteArray, String> {
        val bytes = read(name)
        val pin = text(read(pinFile))
        check(sha256Hex(bytes) == pin) { "the $what does not match its pin" }
        return bytes to pin
    }

    val relay: ByteArray
    val relaySha256: String
    val control: ByteArray
    val controlSha256: String

    init {
        pinned("paddock-relay.py", "paddock-relay.sha256", "bundled relay").let { relay = it.first; relaySha256 = it.second }
        pinned("paddock-control.py", "paddock-control.sha256", "bundled control helper").let { control = it.first; controlSha256 = it.second }
    }

    private fun script(name: String): ByteArray = pinned(name, "$name.sha256").first

    /** The alert relay's script, unit and example configuration. */
    val alert: ByteArray = script("paddock-alert-relay.py")
    val alertSha256: String = sha256Hex(alert)
    val alertUnit: String = script("paddock-alert-relay.service").toString(Charsets.UTF_8)
    val alertConfigExample: String = script("alert-relay.example.toml").toString(Charsets.UTF_8)

    /** The permission-request writer and the Claude Code hook (Phase 08), and the opencode plugin. */
    val decide: ByteArray = script("paddock-decide.py")
    val decideSha256: String = sha256Hex(decide)
    val hook: ByteArray = script("paddock-claude-permission-hook.py")
    val hookSha256: String = sha256Hex(hook)
    val opencode: ByteArray = script("paddock-opencode-permission.js")
    val opencodeSha256: String = sha256Hex(opencode)
}
