package io.github.tuthan.paddock.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.tuthan.paddock.ports.SECRET_NAME
import io.github.tuthan.paddock.ports.SecretCorrupt
import io.github.tuthan.paddock.ports.SecretStore
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Secrets wrapped with a non-exportable AES-256-GCM key in AndroidKeyStore. One file per secret under [dir]:
 * `version(1) ivLength(1) iv ciphertext+tag`. The secret's name is the GCM associated data, so a file copied
 * under another name fails authentication. Writes go to a temp file and are renamed into place.
 * [dir] must live in app-private storage; the app excludes it from backup and device transfer.
 */
class KeystoreSecretStore(private val dir: File, private val keyAlias: String = DEFAULT_ALIAS) : SecretStore {

    override suspend fun put(name: String, secret: ByteArray) = withContext(Dispatchers.IO) {
        require(SECRET_NAME.matches(name)) { "invalid secret name" }
        check(dir.isDirectory || dir.mkdirs()) { "cannot create secret directory" }
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.ENCRYPT_MODE, key())
            updateAAD(name.toByteArray())
        }
        val iv = cipher.iv
        val body = cipher.doFinal(secret)
        val out = ByteArray(2 + iv.size + body.size)
        out[0] = VERSION; out[1] = iv.size.toByte()
        iv.copyInto(out, 2); body.copyInto(out, 2 + iv.size)
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(File(dir, name))) { tmp.delete(); throw IOException("could not store secret") }
    }

    override suspend fun get(name: String): ByteArray? = withContext(Dispatchers.IO) {
        require(SECRET_NAME.matches(name)) { "invalid secret name" }
        val file = File(dir, name)
        if (!file.isFile) return@withContext null
        val data = file.readBytes()
        try {
            if (data.size < 2 || data[0] != VERSION) throw SecretCorrupt(name)
            val ivLen = data[1].toInt() and 0xff
            if (ivLen != IV_BYTES || data.size < 2 + ivLen + TAG_BYTES) throw SecretCorrupt(name)
            Cipher.getInstance(TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, data, 2, ivLen))
                updateAAD(name.toByteArray())
            }.doFinal(data, 2 + ivLen, data.size - 2 - ivLen)
        } catch (e: GeneralSecurityException) {
            throw SecretCorrupt(name, e)
        }
    }

    override suspend fun delete(name: String) = withContext(Dispatchers.IO) {
        require(SECRET_NAME.matches(name)) { "invalid secret name" }
        File(dir, name).delete()
        Unit
    }

    override suspend fun names(): Set<String> = withContext(Dispatchers.IO) {
        dir.listFiles()?.filter { it.isFile && SECRET_NAME.matches(it.name) }?.map { it.name }?.toSet() ?: emptySet()
    }

    /** Removes the wrapping key; every stored secret becomes unreadable. For tests and "forget everything". */
    fun destroyKey() { KeyStore.getInstance(PROVIDER).apply { load(null) }.deleteEntry(keyAlias) }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).apply {
            init(
                KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    companion object {
        const val DEFAULT_ALIAS = "paddock-secret-wrap"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val VERSION: Byte = 1
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
    }
}
