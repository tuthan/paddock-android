package io.github.tuthan.paddock.routes

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.github.tuthan.paddock.files.GetController
import io.github.tuthan.paddock.files.GetState
import io.github.tuthan.paddock.files.Handoff
import io.github.tuthan.paddock.files.OpenableTypes
import io.github.tuthan.paddock.lifecycle.TransferHold
import io.github.tuthan.paddock.ui.screens.GetActions
import io.github.tuthan.paddock.ui.screens.GetCopy
import io.github.tuthan.paddock.ui.screens.GetSheet
import io.github.tuthan.paddock.ui.screens.GetUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Get sheet shows for [this] at [nowMillis]: the machine's name only through `SafeText` (done in [GetController]), the size, the age and the folder. */
internal fun GetState.toUi(nowMillis: Long): GetUi = when (this) {
    is GetState.Looking -> GetUi.Looking(shown)
    is GetState.Offer -> GetUi.Offer(
        look.shownName, listOf(GetCopy.sizeAndAge(look.stat.size, look.stat.mtimeSeconds, nowMillis), GetCopy.inFolder(look.shownFolder)),
        nameNote = if (look.nameChanged) GetCopy.savedAs(look.cachedName) else null,
    )
    is GetState.Reading -> GetUi.Reading(look.shownName, read, look.stat.size)
    is GetState.Ready -> GetUi.Ready(look.shownName, listOf(GetCopy.size(look.stat.size)), canOpen = look.kind is OpenableTypes.Kind.Openable, apk = apk, note = note)
    is GetState.Failed -> GetUi.Failed(sentence)
}

/** Save as: the system's own "create a document" screen, told the type and a name; the result is a URI to write, or null when the person backed out. */
private class SaveAs : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.first).putExtra(Intent.EXTRA_TITLE, input.second)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
}

/**
 * The Get sheet for [controller]: its state drawn, and what its buttons do (Phase 15, slice 12). Open, Share and Save hand the cached file on through
 * [Handoff] (a `content://` URI of Paddock's own provider, read-only, for the one file); Save holds the watch while the system's chooser is in front, as
 * the pickers do (X11).
 */
@Composable
internal fun GetSheetHost(controller: GetController, hold: TransferHold, nowMillis: Long) {
    val state by controller.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ready = state as? GetState.Ready
    val saver = rememberLauncherForActivityResult(remember { SaveAs() }) { uri ->
        hold.release()
        val file = (controller.state.value as? GetState.Ready)?.file
        if (uri == null || file == null) return@rememberLauncherForActivityResult
        scope.launch { controller.say(if (withContext(Dispatchers.IO) { Handoff.saveTo(ctx, file, uri) }) "Saved." else "Could not save the file there.") }
    }
    val shown = state ?: return
    GetSheet(
        shown.toUi(nowMillis),
        GetActions(
            onGet = controller::get,
            onCancel = controller::cancel,
            onOpen = {
                val r = ready ?: return@GetActions
                val kind = r.look.kind as? OpenableTypes.Kind.Openable ?: return@GetActions
                try { ctx.startActivity(Intent.createChooser(Handoff.openIntent(ctx, r.file, kind), "Open")) } catch (_: ActivityNotFoundException) { controller.say("No app on this phone opens that.") }
            },
            onShare = {
                val r = ready ?: return@GetActions
                try { ctx.startActivity(Intent.createChooser(Handoff.shareIntent(ctx, r.file, Handoff.mimeOf(r.look.kind)), "Share")) } catch (_: ActivityNotFoundException) { controller.say("No app on this phone can take it.") }
            },
            onSave = {
                val r = ready ?: return@GetActions
                hold.hold()
                try { saver.launch(Handoff.mimeOf(r.look.kind) to r.look.cachedName) } catch (_: ActivityNotFoundException) { hold.release(); controller.say("This phone has no screen to save a file with.") }
            },
            onDone = controller::dismiss,
        ),
    )
}
