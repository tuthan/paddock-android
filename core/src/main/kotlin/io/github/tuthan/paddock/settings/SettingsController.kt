package io.github.tuthan.paddock.settings

import io.github.tuthan.paddock.applock.AppLock
import io.github.tuthan.paddock.ops.SnippetStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What Settings changes: the saved [AppSettings] and the user's snippets, held in memory and written through. Which writes wait for the file and which
 * are saved in [scope] (so leaving a screen straight after an edit cannot drop them) is each setter's own, as it was when these lived in the app graph.
 */
class SettingsController(
    private val scope: CoroutineScope,
    private val store: AppSettingsStore,
    private val snippetStore: SnippetStore,
    private val appLock: AppLock,
) {
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _snippets = MutableStateFlow<List<String>>(emptyList())
    /** The user's own prompt snippets: on this phone only, never synced. */
    val snippets: StateFlow<List<String>> = _snippets.asStateFlow()

    /** The settings as they are in memory now. */
    val value: AppSettings get() = _settings.value

    /**
     * Reads the settings and the snippets (start-up), in that order, and tells [appLock] what the saved choice is: covered from the first frame until
     * this, so a cold start with the lock on stays covered and one with it off opens now.
     */
    suspend fun load(): AppSettings {
        _settings.value = store.load()
        appLock.start(_settings.value.appLock)
        _snippets.value = runCatching { snippetStore.load() }.getOrDefault(emptyList())
        return _settings.value
    }

    /** The settings as the file holds them, for a caller that runs before [load] has (a widget job that starts a cold process) or that wants the disk's word. */
    suspend fun stored(): AppSettings = store.load()

    /** Changes the in-memory settings; the caller saves ([save] or [saveQuietly]). */
    fun update(transform: (AppSettings) -> AppSettings) { _settings.update(transform) }

    /** Writes the settings; a write that fails throws. */
    suspend fun save() = store.save(_settings.value)

    /** Writes the settings and says whether that worked. */
    suspend fun saveQuietly(): Boolean = runCatching { store.save(_settings.value) }.isSuccess

    private fun saveInScope() { scope.launch { runCatching { store.save(_settings.value) } } }

    /** Saved in the graph's scope: the herd redraws at once and leaving Settings straight after cannot drop the write. */
    fun setAgentGlyphs(on: Boolean) {
        update { it.copy(agentGlyphs = on) }
        saveInScope()
    }

    /**
     * Turns the lock on or off, after the user proved themselves to the phone. Saved before it is believed: when the write fails the setting stays as it was and
     * this says so (false), so Settings never shows a lock that a restart would not have. Turning it on leaves the app open; turning it off opens it.
     */
    suspend fun setAppLock(on: Boolean): Boolean {
        val before = _settings.value
        update { it.copy(appLock = on) }
        val saved = saveQuietly()
        if (!saved) { _settings.value = before; return false }
        appLock.enabledChanged(on)
        return true
    }

    /** How long Paddock may be out of sight before the lock asks again. Saved in the graph's scope. */
    fun setAppLockAfter(seconds: Int) {
        update { it.copy(appLockAfterSeconds = seconds) }
        saveInScope()
    }

    suspend fun setProtectSensitive(on: Boolean) {
        update { it.copy(protectSensitiveScreens = on) }
        save()
    }

    /** Saved in the graph's scope, so leaving the screen straight after an edit cannot drop the write. */
    fun setSnippets(items: List<String>) {
        _snippets.value = items
        scope.launch { runCatching { snippetStore.save(items) } }
    }

    fun setLocalAlerts(on: Boolean) {
        update { it.copy(localAlerts = on) }
        saveInScope()
    }

    fun setHidePromptOnLockScreen(on: Boolean) {
        update { it.copy(hidePromptOnLockScreen = on) }
        saveInScope()
    }

    /** The permission dialog was shown once: from now on a refusal without a rationale means the system will not show it again. */
    fun notePermissionAsked() {
        if (_settings.value.notificationPermissionAsked) return
        update { it.copy(notificationPermissionAsked = true) }
        saveInScope()
    }

    fun setKeepPromptText(on: Boolean) {
        update { it.copy(keepPromptText = on) }
        saveInScope()
    }

    /** The user agreed once to what desktop focus does; the first tap asks until this is set. */
    fun setDesktopFocusConfirmed() {
        update { it.copy(desktopFocusConfirmed = true) }
        saveInScope()
    }
}
