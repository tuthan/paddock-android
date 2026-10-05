package io.github.tuthan.paddock.scanner

import android.view.Surface

/**
 * The camera of the scanner page for as long as it is meant to run: one scanner per visible preview surface. A [QrScanner] starts once and
 * is closed for good, so a surface that goes away (Home, the lock screen, another app) closes it, and the surface coming back (or the activity
 * starting again with the surface still there) makes a new one. While the page is stopped no camera is open, whatever the surface does.
 *
 * Every call comes from the main thread (the surface callbacks and the lifecycle observer both do), so nothing here is locked.
 */
internal class ScannerSession(
    private val create: () -> QrScanner,
    /** A scanner that could not be started; the session has already closed it. */
    private val onFailure: (String) -> Unit,
) {
    private var scanner: QrScanner? = null
    private var surface: Surface? = null
    private var stopped = false

    fun surfaceCreated(created: Surface) { surface = created; open() }

    fun surfaceDestroyed() { surface = null; close() }

    /** ON_START. The surface is usually new by now, but when it survived the stop there is no `surfaceCreated` to follow. */
    fun start() { stopped = false; open() }

    /** ON_STOP: the camera is not for a page nobody can see. */
    fun stop() { stopped = true; close() }

    /** The page leaves the composition. */
    fun dispose() { surface = null; stopped = true; close() }

    private fun open() {
        if (scanner != null || stopped) return
        val target = surface?.takeIf { it.isValid } ?: return
        val made = create()
        scanner = made
        try {
            made.start(target)
        } catch (e: Exception) {
            close()
            onFailure(e.message ?: "The camera is unavailable")
        }
    }

    private fun close() {
        val s = scanner ?: return
        scanner = null
        try { s.close() } catch (_: Exception) { }
    }
}
