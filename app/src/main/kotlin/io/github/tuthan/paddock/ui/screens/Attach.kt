package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.clickable
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.Note
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.card
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/** Where an attachment can come from (Phase 15, slice 10). */
enum class AttachSource(val label: String) {
    Photo("Photo"),
    Document("Document"),
    Camera("Take a photo"),
}

/** Whether the composer can attach a file, and when it cannot, why: the sentence sits beside the button, not behind a dead tap. */
sealed interface AttachUi {
    /** No button: a composer with no file support (the tests that predate it). */
    data object Hidden : AttachUi
    data object On : AttachUi
    data class Off(val reason: String) : AttachUi
}

/**
 * The file waiting in the draft. [name] is the sanitized inbox name, the one the agent will be told, never the sender's. [thumbnail] is decoded at a
 * bounded size, or null (not an image, too large to decode, or not made yet). [gone] says the snapshot was swept (a day passed, or the process died
 * and the cache was cleared): the chip says so, and Send is off until it is removed.
 */
data class AttachmentUi(val name: String, val sizeText: String, val kindText: String, val thumbnail: ImageBitmap? = null, val gone: Boolean = false)

/** A transfer under way: [sent] of [total] bytes of [name]. */
data class TransferUi(val name: String, val sent: Long, val total: Long, val direction: TransferWay = TransferWay.ToMachine)

enum class TransferWay { ToMachine, ToPhone }

const val ATTACH_NOTE_OFF_NO_FOLDER = "Attach is off: herdr does not report this agent's folder yet."
const val KEEP_OPEN_NOTE = "Keep Paddock open until this finishes. Leaving the app stops a transfer."

/** The Attach button and the sentence that says why it is off. */
@Composable
internal fun AttachRow(attach: AttachUi, onAttach: () -> Unit) {
    val c = PaddockTokens.colors
    when (attach) {
        AttachUi.Hidden -> Unit
        AttachUi.On -> PaddockButton("Attach", onAttach, kind = ButtonKind.Secondary, small = true, icon = PaddockIcons.Attach, fillWidth = false)
        is AttachUi.Off -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PaddockButton("Attach", {}, kind = ButtonKind.Secondary, small = true, icon = PaddockIcons.Attach, fillWidth = false, enabled = false)
            Text(attach.reason, style = PaddockTokens.type.secondary, color = c.dim, modifier = Modifier.semantics { contentDescription = "Attach is off. ${attach.reason}" })
        }
    }
}

/** The chip for the attached file: a thumbnail or the file glyph, the name, the size and kind, and Remove. */
@Composable
internal fun AttachmentChip(ui: AttachmentUi, onRemove: () -> Unit) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().card(c, padded = false).testTag("attachment-chip").padding(start = 10.dp).semantics(mergeDescendants = false) {},
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(44.dp).clip(shape).background(c.surface).border(1.dp, c.fieldLine(), shape), contentAlignment = Alignment.Center) {
            if (ui.thumbnail != null) Image(ui.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxHeight().fillMaxWidth())
            else Icon(PaddockIcons.File, contentDescription = null, tint = c.dim, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp).semantics(mergeDescendants = true) {
            contentDescription = if (ui.gone) "Attached file ${ui.name} is no longer on this phone" else "Attached ${ui.kindText} ${ui.name}, ${ui.sizeText}"
        }) {
            Text(ui.name, style = PaddockTokens.type.body, color = c.title, maxLines = 2)
            Text(if (ui.gone) "No longer on this phone. Remove it and attach it again." else "${ui.kindText} · ${ui.sizeText}", style = PaddockTokens.type.secondary, color = c.dim)
        }
        Box(
            Modifier.size(PaddockTokens.spacing.touchTarget).clickable(role = Role.Button, onClickLabel = "Remove attachment", onClick = onRemove)
                .semantics { contentDescription = "Remove attachment" },
            contentAlignment = Alignment.Center,
        ) { Icon(PaddockIcons.Close, contentDescription = null, tint = c.dim, modifier = Modifier.size(20.dp)) }
    }
}

/** What Attach offers: three sources. Cancel is the focused choice. */
@Composable
fun AttachMenuDialog(onChoose: (AttachSource) -> Unit, onDismiss: () -> Unit, cameraAvailable: Boolean = true) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.dialog)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.fieldLine(), shape).verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Attach to this prompt", style = PaddockTokens.type.headerTitle, color = c.title, modifier = Modifier.semantics { heading() })
            Text("The file is copied to this phone first, its location and camera details are removed from an image, and it goes to the agent's .paddock/inbox folder over SSH.", style = PaddockTokens.type.secondary, color = c.dim)
            PaddockButton(AttachSource.Photo.label, { onChoose(AttachSource.Photo) }, kind = ButtonKind.Ghost)
            PaddockButton(AttachSource.Document.label, { onChoose(AttachSource.Document) }, kind = ButtonKind.Ghost)
            if (cameraAvailable) PaddockButton(AttachSource.Camera.label, { onChoose(AttachSource.Camera) }, kind = ButtonKind.Ghost)
            PaddockButton("Cancel", onDismiss, kind = ButtonKind.Secondary, autoFocus = true)
        }
    }
}

/**
 * The transfer sheet: what is being sent, how far it is, and Cancel. The screen stays on while it shows (a transfer that outlives the screen timing out
 * is the commonest way one is cut), and it says what a transfer needs: the app in front.
 */
@Composable
fun TransferSheet(ui: TransferUi, onCancel: () -> Unit) {
    val c = PaddockTokens.colors
    val shape = RoundedCornerShape(PaddockTokens.radii.dialog)
    // The screen stays on while the sheet shows: a transfer that outlives the screen timing out is the commonest way one is cut. In a Dialog this view is
    // the host window's, which stays visible behind the sheet, so its flag is what the system reads.
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
    val fraction = if (ui.total > 0) (ui.sent.toFloat() / ui.total).coerceIn(0f, 1f) else 0f
    val title = if (ui.direction == TransferWay.ToMachine) "Sending ${ui.name}" else "Getting ${ui.name}"
    val progress = "${Sizes.text(ui.sent)} of ${Sizes.text(ui.total)}"
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(shape).background(c.surface).border(1.dp, c.fieldLine(), shape).padding(18.dp).testTag("transfer-sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = PaddockTokens.type.headerTitle, color = c.title, maxLines = 2, modifier = Modifier.semantics { heading() })
            Box(
                Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.fieldLine())
                    .semantics { progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f); contentDescription = "$title, $progress" }.testTag("transfer-progress"),
            ) { Box(Modifier.fillMaxWidth(fraction).height(8.dp).background(c.accent)) }
            Text(progress, style = PaddockTokens.type.secondary, color = c.dim, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Note(KEEP_OPEN_NOTE)
            PaddockButton("Cancel", onCancel, kind = ButtonKind.Secondary, autoFocus = true)
        }
    }
}

/** Sizes as a person reads them: 1.2 MB, not 1258291 bytes. */
object Sizes {
    fun text(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.0f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
        else -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024.0))
    }
}
