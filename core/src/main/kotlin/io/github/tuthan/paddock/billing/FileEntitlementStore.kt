package io.github.tuthan.paddock.billing

import io.github.tuthan.paddock.storage.DurableFile
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The last verified entitlement in one plain JSON file, written through [DurableFile] as the host-key pin store is. It is
 * not Keystore-wrapped: with the source open and an unlocked build, wrapping protects nothing, and a lost Keystore key
 * would add a way into "unknown". The store account stays the authority, so an unreadable file is kept aside as
 * `<name>.corrupt` (one copy) and the state is unknown until the next verification, which restores a real purchase.
 * Keep [file] in app-private, backup-excluded storage.
 */
class FileEntitlementStore(private val file: File) : EntitlementStore {
    companion object {
        /** In the app's files directory; the home-screen widgets read the same file to know whether they are locked. */
        const val FILE_NAME = "entitlement.json"
    }

    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    override suspend fun load(): EntitlementState = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext EntitlementState()
            try {
                json.decodeFromString(EntitlementState.serializer(), file.readText())
            } catch (_: RuntimeException) { // SerializationException, IllegalArgumentException and odd shapes
                runCatching { Files.move(file.toPath(), File(file.absolutePath + ".corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING) }
                EntitlementState()
            } catch (_: IOException) {
                EntitlementState()
            }
        }
    }

    override suspend fun save(state: EntitlementState) {
        lock.withLock {
            withContext(Dispatchers.IO) {
                DurableFile.replace(file, json.encodeToString(EntitlementState.serializer(), state).toByteArray(Charsets.UTF_8))
            }
        }
    }
}
