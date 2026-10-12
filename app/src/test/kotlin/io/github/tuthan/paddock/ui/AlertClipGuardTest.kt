package io.github.tuthan.paddock.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** C7, as far as it is logic: what the guard clears and what it leaves alone. The real clipboard is `CopyClipTest`'s. */
class AlertClipGuardTest {
    private var onClipboard: ClipStamp? = null
    private var cleared = 0
    private val guard = AlertClipGuard(peek = { onClipboard }, clear = { cleared++; onClipboard = null })

    @Test fun anAlertCopyThatIsStillThereIsClearedOnTheNextReturn() {
        onClipboard = ClipStamp("Paddock alert relay setup", 1_000)
        guard.arm()
        guard.onFront()
        assertEquals(1, cleared)
        assertEquals(null, onClipboard)
    }

    @Test fun anotherAppsClipIsLeftAlone() {
        onClipboard = ClipStamp("Paddock alert relay setup", 1_000)
        guard.arm()
        onClipboard = ClipStamp("a password", 2_000)
        guard.onFront()
        assertEquals(0, cleared)
        assertEquals(ClipStamp("a password", 2_000), onClipboard)
    }

    @Test fun theSameLabelCopiedAgainLaterIsNotThisCopy() {
        onClipboard = ClipStamp("ntfy subscribe link", 1_000)
        guard.arm()
        onClipboard = ClipStamp("ntfy subscribe link", 5_000) // the user copied it from somewhere else, an hour on
        guard.onFront()
        assertEquals(0, cleared)
    }

    @Test fun nothingIsClearedWhenNothingWasArmed() {
        onClipboard = ClipStamp("Paddock output", 1_000) // an output copy is never armed, so never cleared
        guard.onFront()
        assertEquals(0, cleared)
    }

    @Test fun itIsOneShotAndTheLaterCopyOfTheSameLabelSurvivesTheNextReturn() {
        onClipboard = ClipStamp("Paddock alert relay setup", 1_000)
        guard.arm()
        guard.onFront()
        onClipboard = ClipStamp("Paddock alert relay setup", 1_000)
        guard.onFront()
        assertEquals(1, cleared)
    }

    @Test fun aClipboardThatCannotBeReadArmsNothingAndClearsNothing() {
        onClipboard = null
        guard.arm()
        onClipboard = ClipStamp("anything", 1)
        guard.onFront()
        assertEquals(0, cleared)
    }
}
