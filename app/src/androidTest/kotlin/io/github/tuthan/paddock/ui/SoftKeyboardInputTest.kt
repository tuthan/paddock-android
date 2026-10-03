package io.github.tuthan.paddock.ui

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.github.tuthan.paddock.terminal.Mods
import io.github.tuthan.paddock.ui.components.SoftKeyboardInput
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/**
 * The hidden keyboard field against what an input method really does: it talks to the field's InputConnection, several
 * edits in a row without waiting for a frame. Compose's own test input (performTextInput) waits for the field between
 * edits and so cannot show the failure this guards: an edit read against text the field had not been put back from yet,
 * which resent the characters before it (found on a device with the real keyboard: "echo soft-e2e" arrived as
 * "eecho sofftft-ft-e2e").
 */
class SoftKeyboardInputTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val sent = CopyOnWriteArrayList<String>()
    private var armed by mutableStateOf(Mods.None)
    private var spent = 0

    private fun start(mods: Mods = Mods.None): InputConnection {
        armed = mods
        rule.setContent {
            val focus = remember { FocusRequester() }
            SoftKeyboardInput(focus, armed = armed, onArmedSpent = { spent++; armed = Mods.None }, onFocus = {}, onBytes = { sent += String(it, Charsets.UTF_8) })
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
        rule.waitForIdle()
        var ic: InputConnection? = null
        rule.runOnUiThread { ic = rule.activity.currentFocus?.onCreateInputConnection(EditorInfo()) }
        return assertNotNull("the focused field gives the keyboard a connection", ic).let { ic!! }
    }

    private fun <T> onUi(block: () -> T): T { var r: T? = null; rule.runOnUiThread { r = block() }; rule.waitForIdle(); @Suppress("UNCHECKED_CAST") return r as T }

    @Test fun editsCommittedBackToBackAreEachSentOnce() {
        val ic = start()
        onUi { for (c in "echo soft-e2e") ic.commitText(c.toString(), 1) }   // one UI-thread turn: no frame between the edits
        assertEquals("echo soft-e2e", sent.joinToString(""))
    }

    @Test fun aWordCommittedAtOnceAndThenAnotherAreSentOnce() {
        val ic = start()
        onUi { ic.commitText("ls", 1); ic.commitText(" -la", 1) }
        assertEquals("ls -la", sent.joinToString(""))
    }

    @Test fun backspaceFromTheKeyboardIsOneDeleteEvenAfterTyping() {
        val ic = start()
        onUi { ic.commitText("ab", 1); ic.deleteSurroundingText(1, 0); ic.commitText("c", 1) }
        assertEquals(listOf("a", "b", "\u007F", "c"), sent.toList())
    }

    @Test fun backspaceOnAnEmptyLineStillReachesTheTerminal() {
        val ic = start()
        onUi { ic.deleteSurroundingText(1, 0); ic.deleteSurroundingText(1, 0); ic.deleteSurroundingText(1, 0) }
        assertEquals(listOf("\u007F", "\u007F", "\u007F"), sent.toList())
    }

    @Test fun enterAndBackspaceKeyEventsFromTheKeyboardAreSentOnce() {
        val ic = start()
        onUi {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
        assertEquals(listOf("\r", "\u007F"), sent.toList())
    }

    @Test fun aLineBreakCommittedAsTextIsEnter() {
        val ic = start()
        onUi { ic.commitText("ls", 1); ic.commitText("\n", 1) }
        assertEquals(listOf("l", "s", "\r"), sent.toList())
    }

    @Test fun stickyCtrlIsSpentOnTheFirstCharacterOfABurstOnly() {
        val ic = start(Mods(ctrl = true))
        onUi { ic.commitText("c", 1); ic.commitText("c", 1) }
        assertEquals(listOf("\u0003", "c"), sent.toList())
        assertEquals(1, spent)
    }

    @Test fun stickyAltIsSpentOnTheFirstCharacterOfABurstOnly() {
        val ic = start(Mods(alt = true))
        onUi { ic.commitText("b", 1); ic.commitText("b", 1) }
        assertEquals(listOf("\u001Bb", "b"), sent.toList())
        assertEquals(1, spent)
    }

    @Test fun stickyShiftIsSpentOnTheFirstCharacterOfABurstOnly() {
        val ic = start(Mods(shift = true))
        onUi { ic.commitText("a", 1); ic.commitText("a", 1) }
        assertEquals(listOf("A", "a"), sent.toList())
        assertEquals(1, spent)
    }

    @Test fun anArmedModifierAppliesToEnterAndBackspaceKeyEventsAndIsSpentOnce() {
        val ic = start(Mods(alt = true))
        onUi {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
        assertEquals(listOf("\u001B\u007F", "\u007F"), sent.toList())
        assertEquals(1, spent)
    }

    @Test fun anArmedCtrlMakesAKeyEventForALetterItsControlCodeOnceNotAlsoText() {
        val ic = start(Mods(ctrl = true))
        onUi { ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C)); ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_C)) }
        assertEquals(listOf("\u0003"), sent.toList())
        assertEquals(1, spent)
    }
}
