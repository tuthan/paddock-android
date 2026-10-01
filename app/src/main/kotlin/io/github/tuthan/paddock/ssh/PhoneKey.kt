package io.github.tuthan.paddock.ssh

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/** Where the private key actually lives, as the platform reports it; never assumed from what was requested. */
enum class KeyBacking { StrongBox, Tee, Software, Unknown }

class PhoneKeyInfo(val publicKey: ECPublicKey, val backing: KeyBacking)

/**
 * The phone's SSH identity: an EC P-256 key generated in AndroidKeyStore. The private half is a non-exportable
 * handle that can only sign. It requires no user authentication, so a locked phone can still answer a connection
 * (the reason is recorded in docs/ssh-library-decision.md and the Phase 02 note); the trade is that anyone who
 * can run this app unlocked can sign. StrongBox is requested first, then the default TEE, then software.
 */
class PhoneKey(private val alias: String = DEFAULT_ALIAS) {
    private val keyStore: KeyStore get() = KeyStore.getInstance(PROVIDER).apply { load(null) }

    fun exists(): Boolean = keyStore.containsAlias(alias)

    /** Returns the existing key, or creates it. The backing level is read back from the platform each time. */
    fun getOrCreate(): PhoneKeyInfo {
        if (!exists()) generate()
        return info()
    }

    fun info(): PhoneKeyInfo {
        val entry = keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry
        return PhoneKeyInfo(entry.certificate.publicKey as ECPublicKey, backingOf(entry.privateKey))
    }

    /** The signing handle for the SSH library. Calls `Signature.getInstance("SHA256withECDSA")` without a provider name. */
    fun privateKey(): PrivateKey = (keyStore.getEntry(alias, null) as KeyStore.PrivateKeyEntry).privateKey

    fun publicLine(comment: String): String = OpenSshKeys.publicLine(getOrCreate().publicKey, comment)

    fun delete() { keyStore.deleteEntry(alias) }

    private fun generate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { generate(strongBox = true); return } catch (_: StrongBoxUnavailableException) { /* fall back below */ }
        }
        generate(strongBox = false)
    }

    private fun generate(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) setIsStrongBoxBacked(true) }
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).apply { initialize(spec) }.generateKeyPair()
    }

    private fun backingOf(key: PrivateKey): KeyBacking = try {
        val info = KeyFactory.getInstance(key.algorithm, PROVIDER).getKeySpec(key, KeyInfo::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (info.securityLevel) {
                KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyBacking.StrongBox
                KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyBacking.Tee
                KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyBacking.Software
                else -> KeyBacking.Unknown
            }
        } else {
            @Suppress("DEPRECATION")
            if (info.isInsideSecureHardware) KeyBacking.Tee else KeyBacking.Software
        }
    } catch (_: Exception) {
        KeyBacking.Unknown
    }

    companion object {
        const val DEFAULT_ALIAS = "paddock-phone-key"
        private const val PROVIDER = "AndroidKeyStore"
    }
}
