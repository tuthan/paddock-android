package io.github.tuthan.paddock.ssh

import io.github.tuthan.paddock.ports.SecretCorrupt
import io.github.tuthan.paddock.ports.SecretStore

/** What the UI may show about a stored imported key. The key material itself is never exposed here. */
data class ImportedKeyInfo(val id: String, val keyType: String, val fingerprint: String, val encrypted: Boolean, val passphraseRemembered: Boolean)

/**
 * Imported keys at rest: the key text exactly as the user supplied it (still passphrase-encrypted when it was)
 * goes through the [SecretStore], which wraps it with the Keystore key. A passphrase is stored when the caller passes
 * `rememberPassphrase`, and the app always does (`AppGraph.importKey`), because connecting never asks for one. It is
 * wrapped under the same Keystore key as the key text, so on this phone it adds no protection at rest beyond that
 * wrapping: whoever can unwrap one can unwrap the other. Nothing is written in clear.
 */
class ImportedKeyStore(private val secrets: SecretStore) {
    private fun keyName(id: String) = "imported-$id"
    private fun passName(id: String) = "imported-$id.pass"
    private fun metaName(id: String) = "imported-$id.meta"

    /**
     * Validates [pem] in memory and stores it only when [ImportCheck.Ready]; any other result is returned and
     * nothing is stored. [id] is a profile or key id matching the secret-name rules.
     */
    suspend fun import(id: String, pem: CharArray, passphrase: String?, rememberPassphrase: Boolean): ImportCheck {
        require(SECRET_ID.matches(id)) { "invalid key id" }
        val check = ImportedKey.check(pem, passphrase)
        if (check !is ImportCheck.Ready) return check
        val keyBytes = String(pem).toByteArray(Charsets.UTF_8)
        try {
            secrets.put(keyName(id), keyBytes)
            val remember = rememberPassphrase && check.encrypted && !passphrase.isNullOrEmpty()
            if (remember) secrets.put(passName(id), passphrase!!.toByteArray(Charsets.UTF_8)) else secrets.delete(passName(id))
            secrets.put(metaName(id), "${check.keyType}\n${check.fingerprint}\n${check.encrypted}\n$remember".toByteArray(Charsets.UTF_8))
        } finally { keyBytes.fill(0) }
        return check
    }

    suspend fun info(id: String): ImportedKeyInfo? {
        val meta = secrets.get(metaName(id)) ?: return null
        val f = String(meta, Charsets.UTF_8).split('\n')
        if (f.size != 4) return null
        return ImportedKeyInfo(id, f[0], f[1], f[2].toBooleanStrict(), f[3].toBooleanStrict())
    }

    /**
     * Auth material for one connection (the connector wipes the returned key text, so call this per attempt). An encrypted
     * key uses the remembered passphrase unless [passphrase] is given; returns null when no key is stored under [id]. A key
     * that cannot be decoded is reported by the connector as `ConnectFailure.BadKey`. Throws [ConnectFailure.KeyUnavailable]
     * when the stored copy cannot be unwrapped (corrupt file, or the Keystore wrapping key is gone).
     */
    suspend fun load(id: String, passphrase: String? = null): SshAuth.Imported? {
        val keyBytes = unwrap(keyName(id)) ?: return null
        val remembered = try { unwrap(passName(id)) } catch (e: ConnectFailure) { keyBytes.fill(0); throw e }
        try {
            val pass = passphrase ?: remembered?.toString(Charsets.UTF_8)
            return SshAuth.Imported(String(keyBytes, Charsets.UTF_8).toCharArray(), pass)
        } finally { keyBytes.fill(0); remembered?.fill(0) }
    }

    suspend fun delete(id: String) { secrets.delete(keyName(id)); secrets.delete(passName(id)); secrets.delete(metaName(id)) }

    private suspend fun unwrap(name: String): ByteArray? =
        try { secrets.get(name) } catch (e: SecretCorrupt) { throw ConnectFailure.KeyUnavailable("the imported key stored on this phone cannot be read", e) }

    private companion object { val SECRET_ID = Regex("[a-z0-9][a-z0-9-]{0,40}") }
}
