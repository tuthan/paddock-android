package io.github.tuthan.paddock.ports

/** Encrypted storage for small secrets (imported key material). Implementations never write plaintext to disk. */
interface SecretStore {
    /** [name] must match [SECRET_NAME]; anything else throws [IllegalArgumentException]. */
    suspend fun put(name: String, secret: ByteArray)

    /** Returns null only when [name] was never stored. A stored value that fails authentication throws [SecretCorrupt]. */
    suspend fun get(name: String): ByteArray?

    suspend fun delete(name: String)
    suspend fun names(): Set<String>
}

class SecretCorrupt(name: String, cause: Throwable? = null) : Exception("stored secret '$name' failed authentication", cause)

/**
 * Starts with a letter or digit, so `.` and `..` can never name the directory or its parent, and never ends in
 * `.tmp`, which implementations use for in-flight writes.
 */
val SECRET_NAME = Regex("(?!.*\\.tmp$)[a-z0-9][a-z0-9._-]{0,63}")
