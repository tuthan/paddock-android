package io.github.tuthan.paddock.e2e

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import io.github.tuthan.paddock.net.NsdBrowser
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 14 slice 4, first task: does `NsdManager.discoverServices` work with the manifest as it is (no CHANGE_WIFI_MULTICAST_STATE) on
 * the API levels the app supports? A SecurityException would mean that normal permission is needed after all, which changes the release
 * check and the privacy copy. Nothing advertises on an emulator, so no service is expected: the question is only whether the browse can
 * start and stop. The platform's own lines (started, failed with a code) go to logcat tag E2E for the report.
 */
class NsdBrowserTest {
    private val lines = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val browser by lazy { NsdBrowser(InstrumentationRegistry.getInstrumentation().targetContext) { lines += it; Log.i("E2E", "NSD $it") } }

    @Test fun aBrowseStartsAndStopsWithoutAnyPermissionBeyondTheManifestsAndNeverThrowsASecurityException() {
        val found = runBlocking { withTimeoutOrNull(4_000) { browser.browse().toList() } }
        // The flow only ends by the timeout (a browse runs until cancelled); a SecurityException would have been thrown out of toList().
        assertEquals("a browse runs until it is cancelled", null, found)
        Log.i("E2E", "NSD API ${android.os.Build.VERSION.SDK_INT}: ${lines.joinToString("; ")}")
        assertTrue("the platform reported nothing at all about starting", lines.any { it.startsWith("started") || it.startsWith("start failed") || it.startsWith("stopped") })
        assertTrue("a security exception: ${lines.filter { it.startsWith("security") }}", lines.none { it.startsWith("security") })
    }
}
