package io.github.tuthan.paddock.ssh

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyGenerator

/** Where the private key actually lives, as the platform reports it; never assumed from what was requested. */
enum class KeyBacking { StrongBox, Tee, Software, Unknown }

class PhoneKeyInfo(val publicKey: ECPublicKey, val backing: KeyBacking)

/**
 * The phone's SSH identity: an EC P-256 key generated in AndroidKeyStore. The private half is a non-exportable
 * handle that can only sign. It requires no user authentication, so a locked phone can still answer a connection
 * (the reason is recorded in docs/ssh-library-decision.md, "Phone key"); the trade is that anyone who can run this app
 * unlocked can sign. StrongBox is requested first; any failure of that request falls back to the default (TEE, or software
 * where the device has no TEE).
 *
 * [sdk] and [keyGen] exist for tests: the SDK level the code paths follow, and the generator for one attempt
 * (`strongBox` true is the StrongBox request).
 */
class PhoneKey internal constructor(
    private val alias: String,
    private val sdk: Int,
    private val keyGen: (alias: String, strongBox: Boolean) -> Unit,
) {
    constructor(alias: String = DEFAULT_ALIAS) : this(alias, Build.VERSION.SDK_INT, { a, strongBox -> generateEc(a, strongBox) })

    private val keyStore: KeyStore get() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    /** Before API 31 KeyInfo cannot tell StrongBox from TEE, so a successful StrongBox request is remembered as this entry. */
    private val strongBoxMarker = "$alias.strongbox"

    fun exists(): Boolean = keyStore.containsAlias(alias)

    /** Returns the existing key, or creates it. The backing level is read back from the platform each time. */
    fun getOrCreate(): PhoneKeyInfo {
        if (!exists()) generate()
        return info()
    }

    /** Throws [ConnectFailure.KeyUnavailable] when the key is gone (app data lost) or the Keystore cannot read it. */
    fun info(): PhoneKeyInfo {
        val entry = entry()
        return PhoneKeyInfo(entry.certificate.publicKey as ECPublicKey, backingOf(entry.privateKey))
    }

    /**
     * The signing handle for the SSH library. Calls `Signature.getInstance("SHA256withECDSA")` without a provider name.
     * Throws [ConnectFailure.KeyUnavailable], which the connection owner shows as a key problem the user must fix, never retried.
     */
    fun privateKey(): PrivateKey = entry().privateKey

    private fun entry(): KeyStore.PrivateKeyEntry {
        // Never generate here: a silent new identity would orphan every authorization on the hosts.
        val entry = try { keyStore.getEntry(alias, null) } catch (e: Exception) { throw ConnectFailure.KeyUnavailable("the phone key cannot be read", e) }
        return entry as? KeyStore.PrivateKeyEntry ?: throw ConnectFailure.KeyUnavailable("the phone key does not exist; create it first")
    }

    fun publicLine(comment: String): String = OpenSshKeys.publicLine(getOrCreate().publicKey, comment)

    fun delete() {
        keyStore.deleteEntry(alias)
        runCatching { keyStore.deleteEntry(strongBoxMarker) }
    }

    internal fun hasStrongBoxMarker(): Boolean = runCatching { keyStore.containsAlias(strongBoxMarker) }.getOrDefault(false)

    private fun generate() {
        runCatching { keyStore.deleteEntry(strongBoxMarker) }
        if (sdk >= Build.VERSION_CODES.P) {
            try {
                keyGen(alias, true)
                markStrongBox()
                return
            } catch (e: Exception) {
                // StrongBoxUnavailableException, but also ProviderException or KeyStoreException from StrongBox chips that
                // refuse a supported spec: none of that may escape a click handler. Clear any half-made entry, then fall back.
                runCatching { keyStore.deleteEntry(alias) }
            }
        }
        keyGen(alias, false)
    }

    private fun markStrongBox() {
        if (sdk >= Build.VERSION_CODES.S && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return // KeyInfo.securityLevel says it directly
        runCatching {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER)
                .apply { init(KeyGenParameterSpec.Builder(strongBoxMarker, KeyProperties.PURPOSE_SIGN).build()) }
                .generateKey()
        }
    }

    private fun backingOf(key: PrivateKey): KeyBacking = try {
        val info = KeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java)
        // [sdk] picks the path under test; the platform check keeps a test's higher [sdk] from calling a missing method.
        if (sdk >= Build.VERSION_CODES.S && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyBacking.StrongBox
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyBacking.Tee
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyBacking.Software
                else -> KeyBacking.Unknown
            }
        } else {
            // API 28 to 30 report StrongBox and TEE alike as secure hardware; the marker says which one was asked for and given.
            @Suppress("DEPRECATION")
            when {
                !info.isInsideSecureHardware -> KeyBacking.Software
                hasStrongBoxMarker() -> KeyBacking.StrongBox
                else -> KeyBacking.Tee
            }
        }
    } catch (_: Exception) {
        KeyBacking.Unknown
    }

    companion object {
        const val DEFAULT_ALIAS = "paddock-phone-key"
        private const val PROVIDER = "AndroidKeyStore"

        /** One generation attempt; [strongBox] is only honoured on API 28 and later. */
        internal fun generateEc(alias: String, strongBox: Boolean) {
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(spec) }.generateKeyPair()
        }
    }
}
