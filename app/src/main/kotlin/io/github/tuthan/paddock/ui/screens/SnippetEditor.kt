package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.ops.Snippets
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.Field
import io.github.tuthan.paddock.ui.components.Note
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.components.card
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/**
 * The user's snippets: add, edit, move up, remove. Every change is checked by [Snippets] (empty, too long, a repeat,
 * past 50) and the reason is shown under the field in words; a valid one goes to [onChange] as the whole new list.
 */
@Composable
fun SnippetEditor(snippets: List<String>, onChange: (List<String>) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val c = PaddockTokens.colors
    var draft by rememberSaveable { mutableStateOf("") }
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }

    fun apply(change: Snippets.Change) {
        when (change) {
            is Snippets.Change.Done -> { onChange(change.items); draft = ""; editing = null; error = null }
            is Snippets.Change.Refused -> error = change.reason
        }
    }

    Column(modifier.fillMaxSize()) {
        ScreenHeader("Snippets", onBack = onBack)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PaddockTokens.spacing.gutter).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Note("Your own phrases for the composer. They stay on this phone, are never synced, and go nowhere until you tap one and send the prompt. Up to ${Snippets.MAX_COUNT}, ${Snippets.MAX_LENGTH} characters each.")
            Field(
                if (editing == null) "New snippet" else "Edit snippet", draft, { draft = it; error = null },
                error = error, singleLine = false, imeAction = ImeAction.Default,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaddockButton(
                    if (editing == null) "Add snippet" else "Save snippet",
                    { apply(editing?.let { Snippets.edit(snippets, it, draft) } ?: Snippets.add(snippets, draft)) },
                    Modifier.weight(1f), enabled = draft.isNotBlank(),
                )
                if (editing != null) PaddockButton("Cancel", { editing = null; draft = ""; error = null }, kind = ButtonKind.Secondary, fillWidth = false)
            }
            if (snippets.isEmpty()) Text("No snippets yet.", style = PaddockTokens.type.body, color = c.dim)
            snippets.forEachIndexed { i, s ->
                Column(Modifier.card(c), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s, style = PaddockTokens.type.body, color = c.title, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaddockButton("Edit", { editing = i; draft = s; error = null }, kind = ButtonKind.Secondary, small = true, fillWidth = false)
                        if (i > 0) PaddockButton("Move up", { onChange(Snippets.move(snippets, i, i - 1)) }, kind = ButtonKind.Secondary, small = true, fillWidth = false)
                        PaddockButton("Remove", {
                            onChange(Snippets.remove(snippets, i))
                            if (editing == i) { editing = null; draft = "" } else if ((editing ?: -1) > i) editing = editing!! - 1
                            error = null
                        }, kind = ButtonKind.Danger, small = true, fillWidth = false)
                    }
                }
            }
            Note("${snippets.size} of ${Snippets.MAX_COUNT} saved.")
        }
    }
}
