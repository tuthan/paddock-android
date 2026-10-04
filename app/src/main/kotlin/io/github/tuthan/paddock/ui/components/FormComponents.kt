package io.github.tuthan.paddock.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.tuthan.paddock.hostkey.HostKeyPrompt
import io.github.tuthan.paddock.qr.QrCode
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * A text field as the design draws it: its label above, a 48 dp bordered box, an error under it in words. The label
 * and error are part of the field (its decoration), so TalkBack reads them with it and tapping the label focuses it.
 * [mono] is for facts the user types exactly: hosts, users, ports, session names.
 */
@Composable
fun Field(
    label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    error: String? = null, keyboardType: KeyboardType = KeyboardType.Text, imeAction: ImeAction = ImeAction.Next,
    placeholder: String? = null, onDone: (() -> Unit)? = null,
    secret: Boolean = false, singleLine: Boolean = true, mono: Boolean = false,
) {
    val c = PaddockTokens.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(PaddockTokens.radii.field)
    val textStyle = (if (mono) PaddockTokens.type.monoFact.copy(fontSize = 14.sp, lineHeight = 20.sp) else PaddockTokens.type.body).copy(color = c.title)
    BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = singleLine, minLines = if (singleLine) 1 else 4, maxLines = if (singleLine) 1 else 8,
        textStyle = textStyle, cursorBrush = SolidColor(c.accent), interactionSource = interaction,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = if (secret) KeyboardType.Password else keyboardType, imeAction = imeAction,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.fillMaxWidth().semantics { if (error != null) error(error) },
        decorationBox = { inner ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Sentence case, not small caps: a label is read with its field, and TalkBack reads it as words.
                Text(label, style = PaddockTokens.type.note.copy(fontWeight = FontWeight.SemiBold), color = if (error != null) c.needsYou else c.dim)
                Box(
                    Modifier.fillMaxWidth().heightIn(min = if (singleLine) PaddockTokens.spacing.touchTarget else 96.dp)
                        .clip(shape).background(c.surface)
                        .border(if (focused || error != null) 1.5.dp else 1.dp, when { error != null -> c.needsYou; focused -> c.accent; else -> c.fieldLine() }, shape)
                        .padding(horizontal = 14.dp, vertical = if (singleLine) 0.dp else 12.dp),
                    contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart,
                ) {
                    if (value.isEmpty() && placeholder != null) Text(placeholder, style = textStyle.copy(color = c.dim), maxLines = if (singleLine) 1 else 3)
                    inner()
                }
                if (error != null) Text(error, style = PaddockTokens.type.secondary, color = c.needsYou)
            }
        },
    )
}

/** A switch as the design draws it: a 44 x 26 track and a 20 dp thumb that slides; on is the accent. Decorative only: the row is the control. */
@Composable
private fun SwitchTrack(checked: Boolean) {
    val c = PaddockTokens.colors
    val x by animateDpAsState(if (checked) 21.dp else 3.dp, tween(150), label = "thumb")
    Box(Modifier.size(width = 44.dp, height = 26.dp).clip(RoundedCornerShape(13.dp)).background(if (checked) c.accent else c.track)) {
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(x.roundToPx(), 3.dp.roundToPx()) }.size(20.dp).clip(RoundedCornerShape(10.dp)).background(if (checked) c.ground else c.text))
    }
}

/**
 * An on/off row: label and detail, the switch at the end. The whole row is the 48 dp target, announced as a switch
 * with its state; the thumb's position says the state without relying on the track colour.
 */
@Composable
fun Toggle(
    label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, detail: String? = null, enabled: Boolean = true,
    /** Padding inside the touch target, so a card's ripple covers the whole card. */
    inset: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
) {
    val c = PaddockTokens.colors
    Row(
        modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.55f)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = PaddockTokens.spacing.touchTarget).padding(inset)
            .semantics { stateDescription = if (checked) "On" else "Off" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = PaddockTokens.type.rowTitle, color = c.title)
            if (detail != null) Text(detail, style = PaddockTokens.type.secondary, color = c.dim)
        }
        SwitchTrack(checked)
    }
}

/** One of several mutually exclusive choices: a card with a radio mark, announced as a radio button with its state. */
@Composable
fun ChoiceCard(title: String, detail: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.row)
    Row(
        modifier.fillMaxWidth().clip(shape).background(if (selected) c.accent.copy(alpha = 0.10f) else c.surface)
            .border(1.dp, if (selected) c.accent.copy(alpha = 0.6f) else c.control(), shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .heightIn(min = PaddockTokens.spacing.touchTarget).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top,
    ) {
        Canvas(Modifier.padding(top = 2.dp).size(18.dp)) {
            val w = 2.dp.toPx()
            drawCircle(if (selected) c.accent else c.faint, radius = size.minDimension / 2 - w / 2, style = Stroke(w))
            if (selected) drawCircle(c.accent, radius = size.minDimension * 0.45f / 2)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = PaddockTokens.type.rowTitle, color = c.title)
            Text(detail, style = PaddockTokens.type.secondary, color = c.dim)
        }
    }
}

/** Monospace text on the slab: fingerprints, key lines, commands, paths. Selectable. */
@Composable
fun Slab(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.slab)
    SelectionContainer {
        Column(
            modifier.fillMaxWidth().clip(shape).background(c.slab).border(1.dp, c.line(), shape).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) { content() }
    }
}

/**
 * A labelled fact: the label is Paddock's own words in small caps, the value sits on the slab exactly as written (a path
 * or an algorithm is never upper-cased). Read as "label: value".
 */
@Composable
fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = "$label: $value" }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Kicker(label)
        Slab { Text(value, style = PaddockTokens.type.monoFact, color = c.text) }
    }
}

/**
 * The design's four actions (design note, System sheet), by what the button means and never by where it sits or how many share a row:
 *  - Primary: the one thing this screen is for (Connect, Send prompt, Install the relay, Yes).
 *  - Secondary: a neutral action and the safe choice (Cancel, Back, Close, Done, Dismiss, Copy, Share, Keyboard, Release, Request control).
 *  - Ghost: open, inspect, retry or fall back to another way (Open terminal, Re-read, Try again, Check the setup, Trust and connect).
 *  - Danger: interrupt, stop, forget or replace (Esc, Ctrl+C, Remove, Reset the record, Unregister).
 * A row of controls uses one kind unless its buttons mean different things. `ButtonRolesTest` pins the labels named in docs/buttons.md.
 */
enum class ButtonKind { Primary, Secondary, Ghost, Danger }

/**
 * A button at least 48 dp tall. [autoFocus] puts initial focus on it once it is on screen: a dialog's safe choice.
 * Focus is drawn as a 2 dp ring in the text colour just outside the button, whatever its kind. [tint] recolours a
 * Ghost button (a banner's action takes the banner's colour).
 */
@Composable
fun PaddockButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: ButtonKind = ButtonKind.Primary, enabled: Boolean = true,
    autoFocus: Boolean = false, small: Boolean = false, icon: ImageVector? = null, fillWidth: Boolean = true, tint: Color? = null,
    /** A 36 dp bar for a row that has to stay one line (the Terminal tab's controls while the Android keyboard is up). */
    dense: Boolean = false,
) {
    val c = PaddockTokens.colors
    val radius = PaddockTokens.radii.button
    val shape = RoundedCornerShape(radius)
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    // `clickable` is not focusable by request in touch mode, so a dialog's safe choice carries its own always-focusable
    // target and handles taps itself: a second (clickable) focus stop inside it would also overwrite its Focused semantics.
    if (autoFocus) LaunchedEffect(Unit) { focus.requestFocus() }
    val ghost = tint ?: c.accent
    val (bg, fg, border) = when (kind) {
        ButtonKind.Primary -> Triple(c.accent, c.ground, Color.Transparent)
        ButtonKind.Secondary -> Triple(c.field, c.title, c.control())
        ButtonKind.Ghost -> Triple(Color.Transparent, ghost, ghost.copy(alpha = if (tint != null) 0.5f else 0.35f))
        ButtonKind.Danger -> Triple(Color.Transparent, c.needsYou, c.needsYou.copy(alpha = 0.4f))
    }
    val ring = c.text
    Row(
        modifier.then(if (fillWidth) Modifier.fillMaxWidth() else Modifier).alpha(if (enabled) 1f else 0.5f)
            .onFocusChanged { focused = it.hasFocus }
            .drawWithContent {
                drawContent()
                if (focused) {
                    val gap = 3.dp.toPx(); val w = 2.dp.toPx()
                    drawRoundRect(
                        ring, topLeft = Offset(-gap, -gap), size = Size(size.width + 2 * gap, size.height + 2 * gap),
                        cornerRadius = CornerRadius(radius.toPx() + gap), style = Stroke(w),
                    )
                }
            }
            .then(
                if (autoFocus) Modifier.focusRequester(focus).focusTarget()
                    .onKeyEvent { e ->
                        val activate = e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.DirectionCenter || e.key == Key.Spacebar
                        if (enabled && activate && e.type == KeyEventType.KeyUp) { onClick(); true } else activate
                    }
                else Modifier,
            )
            .heightIn(min = if (dense) 36.dp else PaddockTokens.spacing.touchTarget)
            .clip(shape).background(bg).border(1.dp, border, shape)
            .then(
                if (autoFocus) Modifier
                    .pointerInput(enabled, onClick) { detectTapGestures { if (enabled) onClick() } }
                    .semantics(mergeDescendants = true) { role = Role.Button; this.focused = focused; onClick { if (enabled) onClick(); true } }
                else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            )
            .padding(horizontal = if (small) 12.dp else 16.dp, vertical = if (dense) 4.dp else 10.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
            Box(Modifier.size(8.dp))
        }
        Text(text, style = if (small) PaddockTokens.type.buttonSmall else PaddockTokens.type.button, color = fg, textAlign = TextAlign.Center)
    }
}

/** Two buttons side by side, or stacked when the font is large enough that side by side would cramp them. */
@Composable
fun ButtonPair(first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit, modifier: Modifier = Modifier) {
    if (LocalDensity.current.fontScale >= 1.5f) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) { first(Modifier); second(Modifier) }
    } else {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { first(Modifier.weight(1f)); second(Modifier.weight(1f)) }
    }
}

/**
 * The host-key dialog. Both forms put initial focus on the safe choice (Cancel, or Keep the old key) and make the
 * other a separate, deliberate tap; dismissing is the same as the safe choice. A changed key is drawn in the attention
 * colour with both fingerprints, so the difference is visible before any choice.
 */
@Composable
fun FingerprintDialog(prompt: HostKeyPrompt, onTrust: () -> Unit, onCancel: () -> Unit) {
    val c = PaddockTokens.colors
    val first = prompt as? HostKeyPrompt.FirstTrust
    val changed = prompt as? HostKeyPrompt.Changed
    val mismatch = prompt as? HostKeyPrompt.PairingMismatch
    val shape = RoundedCornerShape(PaddockTokens.radii.dialog)
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape).background(c.surface)
                .border(1.dp, if (changed != null || mismatch != null) c.attention.copy(alpha = 0.45f) else c.fieldLine(), shape)
                .verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (first != null) {
                Text("New host: ${prompt.endpoint}", style = PaddockTokens.type.headerTitle, color = c.title)
                Text(
                    "Its key is not known to this phone yet, and nothing has signed in. Compare this fingerprint with the one on the machine before you trust it.",
                    style = PaddockTokens.type.body.copy(fontSize = PaddockTokens.type.summary.fontSize), color = c.text,
                )
                Slab {
                    Text(first.algorithm, style = PaddockTokens.type.monoFact, color = c.dim)
                    Text(first.fingerprint, style = PaddockTokens.type.monoFact, color = c.title)
                    // The pairing link named this fingerprint. It is one more thing to compare, not a decision: Trust is still the user's tap.
                    if (first.matchesPairingLink) Text("Same as the fingerprint in the pairing link.", style = PaddockTokens.type.monoFact, color = c.dim)
                }
                Kicker("Run on the machine to compare")
                Slab { Text(first.compareCommand, style = PaddockTokens.type.monoFact, color = c.text) }
                ButtonPair(
                    { m -> PaddockButton("Cancel", onCancel, m, kind = ButtonKind.Secondary, autoFocus = true) },
                    { m -> PaddockButton("Trust and connect", onTrust, m, kind = ButtonKind.Ghost) },
                )
            } else if (mismatch != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(PaddockIcons.Warning, contentDescription = null, tint = c.attention, modifier = Modifier.size(20.dp))
                    Text("${prompt.endpoint} is not the machine in the pairing link", style = PaddockTokens.type.headerTitle, color = c.attention)
                }
                Text(
                    "Paddock did not sign in and trusted nothing. The key this address offered is not one the pairing link names. Either the link is for another machine, or something else is answering at this address.",
                    style = PaddockTokens.type.body.copy(fontSize = PaddockTokens.type.summary.fontSize), color = c.text,
                )
                Slab {
                    Text(if (mismatch.linkFingerprints.size == 1) "in the pairing link" else "in the pairing link · any of", style = PaddockTokens.type.monoFact, color = c.dim)
                    mismatch.linkFingerprints.forEach { Text(it, style = PaddockTokens.type.monoFact, color = c.text) }
                    Box(Modifier.size(6.dp))
                    Text("offered now · ${mismatch.algorithm}", style = PaddockTokens.type.monoFact.copy(fontWeight = FontWeight.Medium), color = c.attention)
                    Text(mismatch.presentedFingerprint, style = PaddockTokens.type.monoFact, color = c.title)
                }
                Kicker("Run on the machine to compare")
                Slab { Text(mismatch.compareCommand, style = PaddockTokens.type.monoFact, color = c.text) }
                PaddockButton("Close", onCancel, Modifier.fillMaxWidth(), kind = ButtonKind.Secondary, autoFocus = true)
            } else if (changed != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(PaddockIcons.Warning, contentDescription = null, tint = c.attention, modifier = Modifier.size(20.dp))
                    Text("${prompt.endpoint} has a new host key", style = PaddockTokens.type.headerTitle, color = c.attention)
                }
                Text(
                    "Paddock did not sign in. Either the machine was reinstalled, or something else is answering at this address. Keep the old key unless you changed the machine yourself.",
                    style = PaddockTokens.type.body.copy(fontSize = PaddockTokens.type.summary.fontSize), color = c.text,
                )
                Slab {
                    Text("known · ${changed.oldAlgorithm} · first trusted ${DATE.format(Instant.ofEpochMilli(changed.oldFirstSeenMillis))}", style = PaddockTokens.type.monoFact, color = c.dim)
                    Text(changed.oldFingerprint, style = PaddockTokens.type.monoFact, color = c.text)
                    Box(Modifier.size(6.dp))
                    Text("offered now · ${changed.newAlgorithm}", style = PaddockTokens.type.monoFact.copy(fontWeight = FontWeight.Medium), color = c.attention)
                    Text(changed.newFingerprint, style = PaddockTokens.type.monoFact, color = c.title)
                }
                Kicker("Run on the machine to compare")
                Slab { Text(changed.compareCommand, style = PaddockTokens.type.monoFact, color = c.text) }
                ButtonPair(
                    { m -> PaddockButton("Keep the old key", onCancel, m, kind = ButtonKind.Secondary, autoFocus = true) },
                    { m -> PaddockButton("Replace with the new key", onTrust, m, kind = ButtonKind.Danger) },
                )
            }
        }
    }
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

/** A settings or list card: the design's `.row` surface with its hairline. A tappable card passes padded = false, adds its click, then pads, so the ripple fills the card. */
fun Modifier.card(colors: io.github.tuthan.paddock.ui.theme.PaddockColors, padded: Boolean = true): Modifier {
    val shape = RoundedCornerShape(12.dp)
    return this.fillMaxWidth().clip(shape).background(colors.surface).border(1.dp, colors.line(), shape)
        .then(if (padded) Modifier.padding(horizontal = 14.dp, vertical = 10.dp) else Modifier)
}
