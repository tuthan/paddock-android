package io.github.tuthan.paddock.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.terminal.Mods
import io.github.tuthan.paddock.terminal.SoftInput

/**
 * The Android keyboard for the Terminal tab while this phone controls the terminal. A terminal has no text field, so the
 * keyboard needs one to talk to: this is a field nobody sees (one dp, transparent, hidden from TalkBack, which reads the
 * terminal itself) that always holds [SoftInput.SENTINEL]. Each edit the keyboard makes is read against the sentinel, sent
 * as keys through [onBytes], and undone, so nothing typed is kept. The keyboard is asked not to correct or suggest (a
 * visible-password field), because a terminal is not prose.
 *
 * The undoing happens inside the edit ([InputTransformation]), not in a state update after it: a keyboard commits several
 * edits in one frame (a swipe, a fast typist, a word and its space), and an update that waits for the next composition leaves
 * the following edit reading text it already sent, which resends everything before it. Found on a device ("echo soft-e2e"
 * arrived as "eecho sofftft-ft-e2e"); [io.github.tuthan.paddock.ui.SoftKeyboardInputTest] drives the field's InputConnection
 * the way a keyboard does.
 *
 * Not supported: a keyboard that composes text (replaces the word it is typing with each letter) despite the request for a
 * password-type field with no suggestions. Compose does not let a transformation see the composition, so each step would be
 * read as new text and the letters sent again. Gboard commits each character directly in such a field.
 *
 * [armed] is the sticky Ctrl, Alt and Shift from the key strip: they are spent on the first key (a character, Enter or
 * Backspace) and [onArmedSpent] says so.
 * Keys that arrive as key events rather than text (Enter and Backspace on some keyboards, a Bluetooth keyboard's arrows,
 * Esc, Ctrl chords) are encoded here the way the terminal itself encodes a hardware key; a plain printable key is left to the
 * field, which turns it into an edit, so nothing is sent twice.
 */
@Composable
fun SoftKeyboardInput(
    focusRequester: FocusRequester,
    armed: Mods,
    onArmedSpent: () -> Unit,
    onFocus: (Boolean) -> Unit,
    onBytes: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberTextFieldState(SoftInput.SENTINEL, TextRange(SoftInput.SENTINEL.length))
    // The armed modifiers as the field itself last knew them: the parameter only changes on the next composition, and a burst of
    // edits arrives before that, so the first character would spend them and the second would still find them armed.
    val armedNow = remember { arrayOf(Mods.None) }
    SideEffect { armedNow[0] = armed }
    val send by rememberUpdatedState(onBytes)
    val spent by rememberUpdatedState(onArmedSpent)
    val reader = remember {
        InputTransformation {
            val keys = SoftInput.keys(asCharSequence().toString())
            revertAllChanges()
            for (k in keys) {
                val e = SoftInput.encode(k, armedNow[0])
                e.bytes?.let(send)
                if (e.spent) { armedNow[0] = Mods.None; spent() }
            }
        }
    }
    BasicTextField(
        state = state,
        modifier = modifier.size(1.dp).alpha(0f).semantics { hideFromAccessibility() }
            .focusRequester(focusRequester).onFocusChanged { onFocus(it.isFocused) }
            .onPreviewKeyEvent { e ->
                // A key event is a key too: the armed modifiers join the ones the event carries, and the first key spends them.
                val a = armedNow[0]
                val alt = e.isAltPressed || a.alt; val ctrl = e.isCtrlPressed || a.ctrl; val shift = e.isShiftPressed || a.shift
                if (!HardwareKeys.isRaw(e.key.nativeKeyCode, alt, ctrl)) return@onPreviewKeyEvent false
                val bytes = HardwareKeys.encode(e.key.nativeKeyCode, e.utf16CodePoint, shift, alt, ctrl) ?: return@onPreviewKeyEvent false
                if (e.type == KeyEventType.KeyDown) {
                    send(bytes)
                    if (!a.none) { armedNow[0] = Mods.None; spent() }
                }
                true
            },
        inputTransformation = reader,
        lineLimits = TextFieldLineLimits.MultiLine(),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.None,
        ),
    )
}
