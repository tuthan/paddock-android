package io.github.tuthan.paddock.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.tuthan.paddock.ssh.ImportCheck
import io.github.tuthan.paddock.ui.components.Banner
import io.github.tuthan.paddock.ui.components.ButtonKind
import io.github.tuthan.paddock.ui.components.Fact
import io.github.tuthan.paddock.ui.components.Field
import io.github.tuthan.paddock.ui.components.Kicker
import io.github.tuthan.paddock.ui.components.Note
import io.github.tuthan.paddock.ui.components.PaddockButton
import io.github.tuthan.paddock.ui.components.ScreenHeader
import io.github.tuthan.paddock.ui.theme.PaddockIcons
import io.github.tuthan.paddock.ui.theme.PaddockTokens

/** A key file the user chose. The text is held in memory by the caller and never written anywhere by the screen, nor printed. */
data class PickedKeyFile(val displayName: String, val text: String) {
    override fun toString(): String = "PickedKeyFile(displayName=$displayName, text=<${text.length} chars>)"
}

/** The words for an import that did not complete. `null` for a ready key: the caller leaves the screen. */
fun importMessage(check: ImportCheck): String? = when (check) {
    is ImportCheck.Ready -> null
    ImportCheck.NeedsPassphrase -> "This key is encrypted. Enter its passphrase."
    ImportCheck.WrongPassphrase -> "That passphrase does not open this key."
    ImportCheck.NotAKey -> "That is not a private key Paddock can read. Choose an OpenSSH or PEM private key, not the .pub file."
    is ImportCheck.Unsupported -> "Paddock cannot use this key: ${check.reason}"
}

/**
 * Import a private key: choose a file or paste it, add its passphrase when it has one. The key text and passphrase are
 * held in plain `remember` (not saveable) state, so they never reach saved instance state or a rotation bundle, and the
 * pasted text is masked on screen. An encrypted key's passphrase is stored (encrypted) because Paddock reconnects
 * without asking for it, and the screen says so. The caller does the import and supplies the outcome in [result].
 */
@Composable
fun ImportKey(
    picked: PickedKeyFile?,
    pickError: String?,
    busy: Boolean,
    result: ImportCheck?,
    onChooseFile: () -> Unit,
    onClearFile: () -> Unit,
    onImport: (pem: String, passphrase: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = PaddockTokens.colors
    var pasted by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    val pem = picked?.text ?: pasted
    val message = result?.let(::importMessage)

    Column(modifier.fillMaxSize().imePadding()) {
        ScreenHeader("Import a private key", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = PaddockTokens.spacing.gutter).padding(top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "Your key is checked on this phone, then stored encrypted by the phone's Keystore. It is never sent anywhere except to sign in to your machine.",
                style = PaddockTokens.type.body, color = c.dim,
            )
            Kicker("The key")
            if (picked != null) {
                Fact("Chosen file", picked.displayName)
                PaddockButton("Choose a different file", onClearFile, kind = ButtonKind.Secondary, icon = PaddockIcons.File)
            } else {
                PaddockButton("Choose a key file", onChooseFile, kind = ButtonKind.Secondary, icon = PaddockIcons.File)
                Field(
                    "Or paste the key", pasted, { pasted = it }, singleLine = false, secret = true, imeAction = ImeAction.Default,
                    placeholder = "-----BEGIN OPENSSH PRIVATE KEY-----",
                )
            }
            if (pickError != null) Banner(pickError)
            Field("Passphrase, if the key has one", passphrase, { passphrase = it }, secret = true, imeAction = ImeAction.Done, onDone = { if (pem.isNotBlank() && !busy) onImport(pem, passphrase) })
            Note("An encrypted key's passphrase is stored encrypted on this phone, because Paddock reconnects without asking for it. Leave it empty for a key that has none.")
            if (message != null) Banner(message)
        }
        Column(Modifier.padding(horizontal = PaddockTokens.spacing.gutter, vertical = 10.dp)) {
            PaddockButton(if (busy) "Checking…" else "Import key", { onImport(pem, passphrase) }, enabled = pem.isNotBlank() && !busy)
        }
    }
}
