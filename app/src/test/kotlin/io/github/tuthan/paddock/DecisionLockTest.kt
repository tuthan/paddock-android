package io.github.tuthan.paddock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the decision sheet offers for "Set up" and what it says when guarded answers are locked (review finding F7). The gate may not open over a
 * request (AC-13.5), so a locked "Set up" cannot be a button that asks the gate: it is withheld, and the sheet says why. The Compose half is
 * `DecisionLockTest` in androidTest.
 */
class DecisionLockTest {
    private val failed = "No such file: ~/.config/paddock/paddock-hook.py"

    @Test fun lockedWithAFailedReadSetUpIsNotOfferedSoThereIsNoDeadButton() {
        // Fails on the old rule `if (readError != null) onSetUp else null`, which offered a Set up whose tap did nothing.
        assertNull(decisionSetUp(failed, locked = true) { error("never run") })
    }

    @Test fun unlockedWithAFailedReadSetUpIsTheRoutesOwnAction() {
        val action: () -> Unit = {}
        assertSame(action, decisionSetUp(failed, locked = false, action))
    }

    @Test fun withoutAFailedReadThereIsNoSetUpWhateverProSays() {
        assertNull(decisionSetUp(null, locked = false) {})
        assertNull(decisionSetUp(null, locked = true) {})
    }

    @Test fun lockedWithAFailedReadTheSheetSaysWhyThereIsNoSetUp() {
        val note = decisionNotice(null, failed, locked = true)
        assertEquals(GUARDED_SETUP_IS_PRO, note)
        assertTrue(note!!.contains("Pro") && note.contains("Settings") && note.contains("terminal"))
    }

    @Test fun theNoteIsNotSaidWhenSetUpIsOfferedOrThereIsNothingToSetUp() {
        assertNull(decisionNotice(null, failed, locked = false))
        // A healthy request on a locked phone: Yes and No work, and the Pro sentence would only be noise.
        assertNull(decisionNotice(null, null, locked = true))
        assertNull(decisionNotice(null, null, locked = false))
    }

    @Test fun aReReadsFindingAndTheLockNoteAreBothKept() {
        val note = decisionNotice("The host's files show the request was answered on the desktop.", failed, locked = true)
        assertNotNull(note)
        assertEquals("The host's files show the request was answered on the desktop.\n$GUARDED_SETUP_IS_PRO", note)
        // Unlocked, the re-read's finding stands alone and is the very string it was.
        assertEquals("found", decisionNotice("found", failed, locked = false))
    }
}
