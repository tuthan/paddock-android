package io.github.tuthan.paddock.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.hostkey.HostKeyPrompt
import io.github.tuthan.paddock.qr.QrCode
import io.github.tuthan.paddock.ui.theme.PaddockTokens
import androidx.compose.runtime.remember
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A labelled text field. An error is shown under the field in words and announced with it; colour never carries it alone. */
@Composable
fun Field(
    label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    error: String? = null, keyboardType: KeyboardType = KeyboardType.Text, imeAction: ImeAction = ImeAction.Next,
    placeholder: String? = null, onDone: (() -> Unit)? = null,
    secret: Boolean = false, singleLine: Boolean = true,
) {
    val c = PaddockTokens.colors
    val colors = TextFieldDefaults.colors(
        focusedContainerColor = c.field, unfocusedContainerColor = c.field, errorContainerColor = c.field,
        focusedTextColor = c.title, unfocusedTextColor = c.title, errorTextColor = c.title,
        focusedLabelColor = c.dim, unfocusedLabelColor = c.dim, errorLabelColor = c.needsYou,
        focusedIndicatorColor = c.accent, unfocusedIndicatorColor = c.line(focused = true), errorIndicatorColor = c.needsYou,
        cursorColor = c.accent, errorCursorColor = c.accent,
        focusedPlaceholderColor = c.dim, unfocusedPlaceholderColor = c.dim,
        focusedSupportingTextColor = c.dim, unfocusedSupportingTextColor = c.dim, errorSupportingTextColor = c.needsYou,
    )
    TextField(
        value = value, onValueChange = onValueChange, singleLine = singleLine, minLines = if (singleLine) 1 else 3, maxLines = if (singleLine) 1 else 6, isError = error != null, colors = colors,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        supportingText = error?.let { { Text(it) } },
        shape = RoundedCornerShape(PaddockTokens.radii.field),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = if (secret) KeyboardType.Password else keyboardType, imeAction = imeAction,
        ),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone?.invoke() }),
        visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = modifier.fillMaxWidth().semantics { if (error != null) error(error) },
    )
}

/** An on/off row. The whole row is the 48 dp target and TalkBack hears the label and the state. */
@Composable
fun Toggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, detail: String? = null, enabled: Boolean = true) {
    val c = PaddockTokens.colors
    Row(
        modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.55f)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = PaddockTokens.spacing.touchTarget).padding(vertical = 6.dp)
            .semantics { stateDescription = if (checked) "On" else "Off" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = PaddockTokens.type.rowTitle, color = c.title)
            if (detail != null) Text(detail, style = PaddockTokens.type.secondary, color = c.dim)
        }
        // The state is a word as well as a shape, so it never depends on the track colour.
        Text(if (checked) "On" else "Off", style = PaddockTokens.type.secondary, color = if (checked) c.accent else c.dim)
    }
}

/** One of several mutually exclusive choices, as a card. Announced as a radio button with its selected state. */
@Composable
fun ChoiceCard(title: String, detail: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.card)
    Column(
        modifier.fillMaxWidth().background(if (selected) c.wash(c.accent) else c.surface, shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.line(), shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = PaddockTokens.spacing.touchTarget).padding(PaddockTokens.spacing.cardPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = PaddockTokens.type.rowTitle, color = c.title)
        Text(detail, style = PaddockTokens.type.secondary, color = c.dim)
    }
}

/** A labelled value in monospace on the slab: fingerprints, key lines, commands. Selectable, and read as "label, value". */
@Composable
fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$label: $value" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Kicker(label)
        SelectionContainer {
            Text(
                value, style = PaddockTokens.type.monoFact, color = c.text,
                modifier = Modifier.fillMaxWidth().background(c.slab, RoundedCornerShape(PaddockTokens.radii.key)).padding(10.dp),
            )
        }
    }
}

enum class ButtonKind { Primary, Quiet, Danger }

/** A full-width button at least 48 dp tall. [autoFocus] puts initial focus on it once it is on screen: a dialog's safe choice. */
@Composable
fun PaddockButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: ButtonKind = ButtonKind.Primary, enabled: Boolean = true, autoFocus: Boolean = false) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.button)
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // `clickable` is not focusable by request in touch mode, so a dialog's safe choice carries its own always-focusable
    // target and handles taps itself: a second (clickable) focus stop inside it would also overwrite its Focused semantics.
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    val (bg, fg, border) = when (kind) {
        ButtonKind.Primary -> Triple(c.accent, c.ground, c.accent)
        ButtonKind.Quiet -> Triple(Color.Transparent, c.title, c.line(focused = true))
        ButtonKind.Danger -> Triple(c.wash(c.needsYou), c.needsYou, c.washBorder(c.needsYou))
    }
    androidx.compose.foundation.layout.Box(
        modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f)
            .onFocusChanged { focused = it.hasFocus }
            .then(
                if (autoFocus) Modifier.focusRequester(focus).focusTarget()
                    .onKeyEvent { e ->
                        val activate = e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.DirectionCenter || e.key == Key.Spacebar
                        if (enabled && activate && e.type == KeyEventType.KeyUp) { onClick(); true } else activate
                    }
                else Modifier,
            )
            .heightIn(min = PaddockTokens.spacing.touchTarget)
            .background(bg, shape).border(if (focused) 3.dp else 1.dp, if (focused) c.accent else border, shape)
            .then(
                if (autoFocus) Modifier
                    .pointerInput(enabled, onClick) { detectTapGestures { if (enabled) onClick() } }
                    .semantics(mergeDescendants = true) { role = Role.Button; this.focused = focused; onClick { if (enabled) onClick(); true } }
                else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = PaddockTokens.type.rowTitle, color = fg) }
}

/**
 * The host-key dialog. Both forms put initial focus on the safe choice (Cancel, or Keep the old key) and make the
 * unsafe one a deliberate tap on a separate, full-width button; neither can be confirmed by dismissing the dialog.
 */
@Composable
fun FingerprintDialog(prompt: HostKeyPrompt, onTrust: () -> Unit, onCancel: () -> Unit) {
    val c = PaddockTokens.colors
    val first = prompt as? HostKeyPrompt.FirstTrust
    val changed = prompt as? HostKeyPrompt.Changed
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = c.surface,
        title = { Text(if (first != null) "Trust ${prompt.endpoint}?" else "The key for ${prompt.endpoint} changed", style = PaddockTokens.type.screenTitle, color = c.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (first != null) {
                    Text("Nothing is trusted yet and nothing has signed in. Check that this fingerprint matches the one on the machine before you trust it.", style = PaddockTokens.type.body, color = c.text)
                    Fact("Key type", first.algorithm)
                    Fact("Fingerprint", first.fingerprint)
                    Fact("Run on the machine to compare", first.compareCommand)
                } else if (changed != null) {
                    Text("Paddock did not sign in. Either the machine was reinstalled, or something else is answering at this address. Keep the old key unless you changed the machine yourself.", style = PaddockTokens.type.body, color = c.text)
                    Fact("Old key, ${changed.oldAlgorithm}, first trusted ${DATE.format(Instant.ofEpochMilli(changed.oldFirstSeenMillis))}", changed.oldFingerprint)
                    Fact("New key, ${changed.newAlgorithm}", changed.newFingerprint)
                    Fact("Run on the machine to compare", changed.compareCommand)
                }
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (first != null) {
                    PaddockButton("Cancel", onCancel, kind = ButtonKind.Quiet, autoFocus = true)
                    PaddockButton("Trust and connect", onTrust)
                } else {
                    PaddockButton("Keep the old key", onCancel, kind = ButtonKind.Quiet, autoFocus = true)
                    PaddockButton("Replace with the new key", onTrust, kind = ButtonKind.Danger)
                }
            }
        },
    )
}

private val DATE: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

/** A QR code, dark modules on a light ground with the four-module quiet zone scanners need, whatever the theme. */
@Composable
fun QrView(qr: QrCode, description: String, modifier: Modifier = Modifier) {
    Canvas(modifier.size(224.dp).semantics { contentDescription = description }) {
        val quiet = 4
        val modules = qr.size + 2 * quiet
        val cell = size.minDimension / modules
        drawRect(Color.White)
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (qr.isDark(x, y)) drawRect(Color.Black, Offset((x + quiet) * cell, (y + quiet) * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
