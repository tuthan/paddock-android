package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tuthan.paddock.terminal.EndReason
import io.github.tuthan.paddock.terminal.Mods
import io.github.tuthan.paddock.terminal.TerminalMode
import io.github.tuthan.paddock.terminal.TerminalNotice
import io.github.tuthan.paddock.terminal.TerminalView
import io.github.tuthan.paddock.ui.components.Banner
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.ButtonPair
import io.github.tuthan.paddock.ui.components.Chip
import io.github.tuthan.paddock.ui.components.Fact
import io.github.tuthan.paddock.ui.components.HardwareKeys
import io.github.tuthan.paddock.ui.components.KeyStrip
import io.github.tuthan.paddock.ui.components.Note
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.SoftKeyboardInput
import io.github.tuthan.paddock.ui.components.TerminalCanvas
import io.github.tuthan.paddock.ui.components.TerminalText
import io.github.tuthan.paddock.ui.components.terminalDescription
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens
import kotlinx.coroutines.delay

/** The hidden field the Android keyboard types into; tests find it by this tag. */
const val KEYBOARD_FIELD_TAG = "terminal-keyboard-input"

/** What the Terminal tab asks of the session. The tab never talks to the host itself. */
class TerminalActions(
    val onViewport: (cols: Int, rows: Int) -> Unit = { _, _ -> },
    val onRequestControl: () -> Unit = {},
    val onTakeOver: () -> Unit = {},
    val onInstallHelper: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
    val onRelease: () -> Unit = {},
    val onResizeToFit: (cols: Int, rows: Int) -> Unit = { _, _ -> },
    /** Bytes for the terminal. Called only while this phone controls it. */
    val onKey: (ByteArray) -> Unit = {},
    /** Opens a fresh session after one that ended with an error. */
    val onRetry: () -> Unit = {},
)

/** What the pill says, and whether the phone may type. */
internal fun pillText(view: TerminalView): String {
    val size = if (view.cols > 0) " · ${view.cols}×${view.rows}" else ""
    return when (val m = view.mode) {
        TerminalMode.Idle -> "not connected"
        is TerminalMode.Connecting -> if (m.control) "asking for control…" else "connecting…"
        TerminalMode.Observing -> "read-only$size"
        TerminalMode.Controlling -> "in control$size"
        TerminalMode.Releasing -> "releasing…"
        is TerminalMode.Ended -> "ended"
    }
}

internal fun endedText(reason: EndReason): Pair<String, String> = when (reason) {
    EndReason.PaneGone -> "This terminal is no longer in the session." to "It was closed, or moved to another session."
    EndReason.LinkLost -> "The connection to the machine dropped." to "If you had control, the machine gives the terminal back to the desktop shortly after. Open Terminal again once the machine is connected."
    is EndReason.Closed -> "herdr closed the terminal stream." to "It said: ${reason.reason}"
    is EndReason.Failed -> "The terminal stream failed." to reason.message
}

/**
 * The Terminal tab. Read-only until the user asks for control: the pill says which, Request control is the only way in
 * (never with takeover), a refusal is explained in herdr's words with Take over as its own action, and the key strip and the
 * hardware keyboard send nothing unless this phone controls the terminal. Resize to fit and a control request that would
 * resize the desktop's terminal both ask first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TerminalTab(
    view: TerminalView, actions: TerminalActions, modifier: Modifier = Modifier,
    /**
     * The Android keyboard shares the screen: the controls stay on one scrolling line, the keys are 40 dp bars with no note under
     * them, and the grid follows the cursor. Follows the keyboard itself; a test sets it.
     */
    compact: Boolean = WindowInsets.isImeVisible,
) {
    val c = PaddockTokens.colors
    val density = LocalDensity.current
    val controlling = view.mode == TerminalMode.Controlling
    val ended = view.mode as? TerminalMode.Ended
    var userSp by rememberSaveable { mutableFloatStateOf(0f) }
    var viewWidth by remember { mutableStateOf(0f) }
    var fitCells by remember { mutableStateOf(1 to 1) }
    var asking by remember { mutableStateOf<Ask?>(null) }
    val pxPerSp = with(density) { 1.sp.toPx() }
    val autoSp = if (viewWidth > 0f && view.cols > 0) TerminalText.fitSp(viewWidth, view.cols, pxPerSp) else TerminalText.FIT_FLOOR_SP
    val sp = if (userSp > 0f) userSp else autoSp
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(controlling) { if (controlling) runCatching { focus.requestFocus() } }
    // The Android keyboard talks to a hidden field (SoftKeyboardInput), which holds focus while the keyboard is up.
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardFocus = remember { FocusRequester() }
    var keyboardFocused by remember { mutableStateOf(false) }
    val keyboardShown = WindowInsets.isImeVisible && keyboardFocused
    var armed by remember { mutableStateOf(Mods.None) }
    // Every key sent asks the grid to show the cursor again, even after the person moved the grid by hand.
    var typed by remember { mutableIntStateOf(0) }
    val send: (ByteArray) -> Unit = { bytes -> typed++; actions.onKey(bytes) }
    LaunchedEffect(controlling) { if (!controlling) { keyboard?.hide(); armed = Mods.None } }
    val notice = view.notice
    if (notice is TerminalNotice.Resynced) LaunchedEffect(notice, view.frames) { delay(4_000); actions.onDismissNotice() }

    val controls: @Composable () -> Unit = {
        Chip(
            if (compact) pillText(view).removePrefix("in ") else pillText(view), description = "Terminal: ${pillText(view)}", tone = if (controlling) c.attention else null,
            icon = if (controlling) PaddockIcons.Keyboard else PaddockIcons.Eye,
        )
        when (view.mode) {
            TerminalMode.Observing -> if (notice !is TerminalNotice.HelperNeeded) PaddockButton(
                "Request control", { if (view.controlWouldResizeDesktop) asking = Ask.TakeControlResizes(fitCells.first, fitCells.second) else actions.onRequestControl() },
                kind = ButtonKind.Secondary, small = true, fillWidth = false, icon = PaddockIcons.Keyboard, dense = compact,
            )
            TerminalMode.Controlling -> {
                PaddockButton(
                    if (keyboardShown) "Hide keyboard" else "Keyboard",
                    {
                        if (keyboardShown) { keyboard?.hide(); runCatching { focus.requestFocus() } }
                        else { runCatching { keyboardFocus.requestFocus() }; keyboard?.show() }
                    },
                    kind = ButtonKind.Secondary, small = true, fillWidth = false, dense = compact,
                )
                PaddockButton("Release", actions.onRelease, kind = ButtonKind.Secondary, small = true, fillWidth = false, dense = compact)
                PaddockButton("Resize to fit", { asking = Ask.Resize(fitCells.first, fitCells.second) }, kind = ButtonKind.Secondary, small = true, fillWidth = false, dense = compact)
            }
            else -> Unit
        }
    }

    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
        // One line, like the key strip under the grid: it scrolls sideways when the pill and the three buttons do not fit, instead of
        // wrapping Resize to fit onto a row of its own (56 dp of the grid's height).
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp), verticalAlignment = Alignment.CenterVertically,
        ) { controls() }
        NoticeBanner(notice, actions)
        if (ended != null) {
            val (what, why) = endedText(ended.reason)
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(what, style = PaddockTokens.type.rowTitle, color = c.title)
                Text(why, style = PaddockTokens.type.secondary, color = c.dim)
                if (ended.reason is EndReason.Failed || ended.reason is EndReason.Closed) PaddockButton("Try again", actions.onRetry, kind = ButtonKind.Ghost, small = true, fillWidth = false)
            }
        }
        val shape = RoundedCornerShape(PaddockTokens.radii.slab)
        Box(
            Modifier.weight(1f).fillMaxWidth().clip(shape).background(c.slab)
                .border(if ((focused || keyboardFocused) && controlling) 2.dp else 1.dp, if ((focused || keyboardFocused) && controlling) c.accent else c.line(), shape),
        ) {
            if (view.grid == null && ended == null) {
                Text(
                    if (view.mode is TerminalMode.Connecting || view.mode == TerminalMode.Idle) "Connecting to the terminal…" else "Waiting for the first screen…",
                    style = PaddockTokens.type.body, color = c.dim, modifier = Modifier.padding(12.dp),
                )
            }
            if (controlling) {
                SoftKeyboardInput(
                    focusRequester = keyboardFocus, armed = armed, onArmedSpent = { armed = Mods.None },
                    onFocus = { keyboardFocused = it }, onBytes = send,
                    modifier = Modifier.align(Alignment.BottomStart).testTag(KEYBOARD_FIELD_TAG),
                )
            }
            TerminalCanvas(
                grid = view.grid, cursor = view.cursor, textSizeSp = sp,
                onTextSizeSp = { userSp = it }, onResetTextSize = { userSp = 0f },
                onViewportCells = { cols, rows -> fitCells = cols to rows; actions.onViewport(cols, rows) },
                onViewSize = { viewWidth = it },
                description = terminalDescription(view.grid, pillText(view).replace(" · ", ", ")),
                dimmed = ended != null, frame = view.frames, arrivedNanos = view.lastFrameArrivedNanos,
                followCursor = controlling, refollow = typed,
                // Focus and keys sit on the node that TalkBack reads, so the terminal is one stop with one description.
                modifier = Modifier.focusRequester(focus).onFocusChanged { focused = it.isFocused }.focusable()
                    .onPreviewKeyEvent { e ->
                        // The hardware keyboard reaches the terminal only under control; everything else is left to the system.
                        if (!controlling) return@onPreviewKeyEvent false
                        val bytes = HardwareKeys.encode(e.key.nativeKeyCode, e.utf16CodePoint, e.isShiftPressed, e.isAltPressed, e.isCtrlPressed)
                            ?: return@onPreviewKeyEvent false
                        if (e.type == KeyEventType.KeyDown) send(bytes)
                        true
                    },
            )
        }
        KeyStrip(
            onKey = { bytes -> if (controlling) send(bytes) }, enabled = controlling, dense = compact,
            note = if (controlling) "Keys and the keyboard go to the terminal. Release to stop." else "Request control to use the keys. Observing never sends a key.",
            armed = if (controlling) armed else null, onToggleMod = { armed = armed.toggled(it) }, onModsSpent = { armed = Mods.None },
        )
        if (!controlling && ended == null) Note("Pinch to change the text size, drag to move, double tap to fit. None of it changes the terminal.")
    }

    when (val a = asking) {
        is Ask.Resize -> ConfirmDialog(
            title = "Resize the terminal to ${a.cols}×${a.rows}?",
            body = "This changes the terminal on the desktop too: everything attached to it is redrawn at ${a.cols}×${a.rows}, the size that fits this screen at the current text size.",
            confirm = "Resize to ${a.cols}×${a.rows}", onConfirm = { asking = null; actions.onResizeToFit(a.cols, a.rows) }, onCancel = { asking = null },
        )
        is Ask.TakeControlResizes -> ConfirmDialog(
            title = "Take control at ${a.cols}×${a.rows}?",
            body = "Paddock could not read this terminal's current size, so taking control sets it to ${a.cols}×${a.rows}, the size of this screen. The terminal on the desktop changes with it.",
            confirm = "Take control", onConfirm = { asking = null; actions.onRequestControl() }, onCancel = { asking = null },
        )
        null -> Unit
    }
    if (notice is TerminalNotice.HelperNeeded) {
        ConfirmDialog(
            title = "Install the control helper?",
            body = "Control goes through one small Python script on the machine. It runs herdr's own control command and gives the terminal back to the desktop if this phone stops answering. It is written with owner-only permissions and checked against the hash below before every use." +
                if (notice.replacing) " A different file is already there. Installing replaces it." else "",
            facts = listOf("File on the host" to notice.destination, "SHA-256 of the script" to notice.sha256),
            confirm = "Install and request control", onConfirm = actions.onInstallHelper, onCancel = actions.onDismissNotice,
        )
    }
}

private sealed interface Ask {
    data class Resize(val cols: Int, val rows: Int) : Ask
    data class TakeControlResizes(val cols: Int, val rows: Int) : Ask
}

@Composable
private fun NoticeBanner(notice: TerminalNotice?, actions: TerminalActions) {
    when (notice) {
        null, is TerminalNotice.HelperNeeded -> Unit
        TerminalNotice.Resynced -> Note("Resynced: a screen update was missed, so the whole screen was read again.", icon = PaddockIcons.Eye)
        is TerminalNotice.Conflict -> Banner(
            "Another client is attached to this terminal and owns its input. herdr says: ${notice.herdrSays}. Taking over ends the other client's input.",
            actionLabel = "Take over", onAction = actions.onTakeOver,
        )
        is TerminalNotice.ControlRefused -> Banner("herdr did not give control: ${notice.herdrSays}", actionLabel = "Dismiss", onAction = actions.onDismissNotice)
        is TerminalNotice.ControlEnded -> Banner("Control ended: ${notice.herdrSays}. The terminal is read-only again.", actionLabel = "Dismiss", onAction = actions.onDismissNotice)
        TerminalNotice.HelperUnavailable -> Banner("This version of Paddock has no control helper to install, so the terminal stays read-only.", actionLabel = "Dismiss", onAction = actions.onDismissNotice)
    }
}

/** A choice the user must make before anything changes. Cancel is the safe choice and has the initial focus; dismissing is Cancel. */
@Composable
fun ConfirmDialog(
    title: String, body: String, confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit,
    facts: List<Pair<String, String>> = emptyList(), danger: Boolean = false,
) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.dialog)
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onCancel,
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.fieldLine(), shape)
                .verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = PaddockTokens.type.headerTitle, color = c.title)
            Text(body, style = PaddockTokens.type.body.copy(fontSize = PaddockTokens.type.summary.fontSize), color = c.text)
            facts.forEach { (k, v) -> Fact(k, v) }
            ButtonPair(
                { m -> PaddockButton("Cancel", onCancel, m, kind = ButtonKind.Secondary, autoFocus = true) },
                { m -> PaddockButton(confirm, onConfirm, m, kind = if (danger) ButtonKind.Danger else ButtonKind.Ghost) },
            )
        }
    }
}
