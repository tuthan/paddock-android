package io.github.tuthan.paddock.settings

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything the user can change in Settings. Monitoring while the app is open is not here: it is not optional in this
 * phase. [protectSensitiveScreens] defaults on, because agent output and the screens that follow it can show secrets;
 * turning it off is a deliberate act.
 */
@Serializable
data class AppSettings(
    val protectSensitiveScreens: Boolean = true,
    /** The machine to watch on start; null (or a removed profile) falls back to the first one. */
    val watchedProfileId: String? = null,
    /**
     * Off by default: the operation journal records a prompt as a SHA-256 and nothing else. On, it also keeps the text,
     * which then shows in the unknown-outcome check ("does this text appear in the pane?") after a restart.
     */
    val keepPromptText: Boolean = false,
    /**
     * Not a Settings toggle: the user has been told what desktop focus does (it moves the desktop's cursor and marks a
     * completion seen on the machine) and agreed once. Until then the first tap asks.
     */
    val desktopFocusConfirmed: Boolean = false,
)

interface AppSettingsStore {
    suspend fun load(): AppSettings
    suspend fun save(settings: AppSettings)
}

class InMemoryAppSettingsStore(initial: AppSettings = AppSettings()) : AppSettingsStore {
    @Volatile private var current = initial
    override suspend fun load() = current
    override suspend fun save(settings: AppSettings) { current = settings }
}

/**
 * One small JSON file. A missing file is the defaults. An unreadable file is also the defaults rather than an error:
 * these are preferences, nothing is lost, and the safe default (protection on) is what a damaged file falls back to.
 */
class FileAppSettingsStore(private val file: File) : AppSettingsStore {
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    override suspend fun load(): AppSettings = lock.withLock {
        withContext(Dispatchers.IO) {
            if (!file.exists()) AppSettings()
            else try { json.decodeFromString(AppSettings.serializer(), file.readText()) } catch (_: RuntimeException) { AppSettings() } catch (_: IOException) { AppSettings() }
        }
    }

    override suspend fun save(settings: AppSettings) {
        lock.withLock {
            withContext(Dispatchers.IO) {
                file.absoluteFile.parentFile?.let { check(it.isDirectory || it.mkdirs()) { "cannot create ${it.path}" } }
                val tmp = File(file.absolutePath + ".tmp")
                tmp.writeText(json.encodeToString(AppSettings.serializer(), settings))
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
