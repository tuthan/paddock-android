package io.github.tuthan.paddock.settings

import io.github.tuthan.paddock.applock.AppLock
import io.github.tuthan.paddock.ops.InMemorySnippetStore
import io.github.tuthan.paddock.ops.SnippetStore
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking

/** What Settings does between the screen and the file; the same controller the app graph builds, with the store in memory. */
class SettingsControllerTest {
    private class Store(initial: AppSettings = AppSettings()) : AppSettingsStore {
        val inner = InMemoryAppSettingsStore(initial)
        @Volatile var failing = false
        @Volatile var saves = 0
        override suspend fun load() = inner.load()
        override suspend fun save(settings: AppSettings) {
            if (failing) throw IOException("disk full")
            saves++
            inner.save(settings)
        }
    }

    private class Snippets(var items: List<String> = emptyList(), var failLoad: Boolean = false) : SnippetStore {
        val saved = mutableListOf<List<String>>()
        override suspend fun load(): List<String> = if (failLoad) throw IOException("unreadable") else items
        override suspend fun save(items: List<String>) { saved += items }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val lock = AppLock { 0L }

    private fun controller(store: Store = Store(), snippets: Snippets = Snippets()) = SettingsController(scope, store, snippets, lock)

    @Test fun loadReadsSettingsThenSnippetsAndTellsTheLockWhatWasSaved() = runBlocking {
        val c = controller(Store(AppSettings(appLock = true, agentGlyphs = false)), Snippets(listOf("run the tests")))
        assertTrue(lock.locked.value, "covered before the choice is known")
        c.load()
        assertEquals(false, c.settings.value.agentGlyphs)
        assertEquals(listOf("run the tests"), c.snippets.value)
        assertTrue(lock.locked.value, "a saved lock stays covered")
    }

    @Test fun loadWithTheLockOffOpensTheScreens() = runBlocking {
        controller(Store(AppSettings(appLock = false))).load()
        assertFalse(lock.locked.value)
    }

    @Test fun unreadableSnippetsAreNoSnippetsAndSettingsStillLoad() = runBlocking {
        val c = controller(Store(AppSettings(localAlerts = true)), Snippets(failLoad = true))
        c.load()
        assertEquals(emptyList(), c.snippets.value)
        assertTrue(c.settings.value.localAlerts)
    }

    @Test fun aLockThatCannotBeSavedIsNotBelieved() = runBlocking {
        val store = Store(AppSettings(appLock = false))
        val c = controller(store)
        c.load()
        store.failing = true
        assertFalse(c.setAppLock(true))
        assertFalse(c.settings.value.appLock, "the setting stays as it was")
        assertFalse(store.inner.load().appLock)
    }

    @Test fun aLockThatWasSavedIsOnAndTurningItOffOpensTheScreens() = runBlocking {
        val store = Store(AppSettings(appLock = true))
        val c = controller(store)
        c.load()
        assertTrue(lock.locked.value)
        assertTrue(c.setAppLock(false))
        assertFalse(lock.locked.value)
        assertFalse(store.inner.load().appLock)
        assertTrue(c.setAppLock(true))
        assertFalse(lock.locked.value, "turning it on leaves the app open")
        assertTrue(store.inner.load().appLock)
    }

    @Test fun protectSensitiveWaitsForTheFileAndThrowsWhenItFails() = runBlocking {
        val store = Store()
        val c = controller(store)
        c.setProtectSensitive(false)
        assertFalse(store.inner.load().protectSensitiveScreens)
        store.failing = true
        assertFailsWith<IOException> { c.setProtectSensitive(true) }
        Unit
    }

    @Test fun theScopeSavedSettersWriteAndAFailedWriteDoesNotEndTheScope() = runBlocking {
        val store = Store()
        val c = controller(store)
        c.setAgentGlyphs(false); c.setLocalAlerts(true); c.setHidePromptOnLockScreen(false); c.setKeepPromptText(true); c.setDesktopFocusConfirmed(); c.setAppLockAfter(300)
        val saved = store.inner.load()
        assertEquals(listOf(false, true, false, true, true, 300), listOf(saved.agentGlyphs, saved.localAlerts, saved.hidePromptOnLockScreen, saved.keepPromptText, saved.desktopFocusConfirmed, saved.appLockAfterSeconds))
        store.failing = true
        c.setAgentGlyphs(true)
        assertTrue(c.settings.value.agentGlyphs, "memory follows the tap even when the file did not")
        store.failing = false
        c.setAgentGlyphs(false)
        assertFalse(store.inner.load().agentGlyphs, "the scope still saves after a failed write")
    }

    @Test fun thePermissionDialogIsNotedOnceAndNotSavedAgain() = runBlocking {
        val store = Store()
        val c = controller(store)
        c.notePermissionAsked()
        val after = store.saves
        c.notePermissionAsked()
        assertEquals(after, store.saves)
        assertTrue(store.inner.load().notificationPermissionAsked)
    }

    @Test fun snippetsAreShownAtOnceAndSavedInTheScope() = runBlocking {
        val snippets = Snippets()
        val c = controller(snippets = snippets)
        c.setSnippets(listOf("a", "b"))
        assertEquals(listOf("a", "b"), c.snippets.value)
        assertEquals(listOf(listOf("a", "b")), snippets.saved)
    }

    @Test fun storedIsTheFilesWordNotMemorys() = runBlocking {
        val store = Store(AppSettings(watchedProfileId = "m1"))
        val c = controller(store)
        assertEquals(null, c.settings.value.watchedProfileId, "before load, memory has the defaults")
        assertEquals("m1", c.stored().watchedProfileId)
    }
}
