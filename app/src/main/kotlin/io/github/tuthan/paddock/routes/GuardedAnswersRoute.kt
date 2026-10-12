package io.github.tuthan.paddock.routes

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.tuthan.paddock.answers.AnswerSetupText
import io.github.tuthan.paddock.host.HostUiModel
import io.github.tuthan.paddock.hostprofile.MachineBook
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.relay.PinnedHostScripts
import io.github.tuthan.paddock.ui.CopyKind
import io.github.tuthan.paddock.ui.copyText
import io.github.tuthan.paddock.ui.screens.GuardedAgent
import io.github.tuthan.paddock.ui.screens.GuardedAnswers
import io.github.tuthan.paddock.ui.screens.GuardedAnswersUi
import io.github.tuthan.paddock.ui.screens.GuardedCopied
import io.github.tuthan.paddock.ui.screens.GuardedHostState
import kotlinx.coroutines.launch

/**
 * Guarded answers on the watched machine: the pinned scripts' state on the host (read through the live connection), the consented
 * install of exactly those files, and the texts to paste. Nothing here registers the hook or edits any agent's settings.
 */
@Composable
internal fun GuardedAnswersRoute(hostUi: HostUiModel, machineBook: MachineBook, hostScripts: PinnedHostScripts, onBack: () -> Unit) {
    val view by hostUi.view.collectAsState()
    val profile by machineBook.profile.collectAsState()
    val host = (view.phase as? HostPhase.Monitoring)?.host
    val answerHost = host?.answerHost
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var hostState by remember(answerHost) { mutableStateOf<GuardedHostState>(if (answerHost == null) GuardedHostState.NotConnected else GuardedHostState.Reading) }
    var installing by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(GuardedCopied.None) }
    var window by rememberSaveable { mutableIntStateOf(AnswerSetupText.DEFAULT_WINDOW_SECONDS) }
    var codexWindow by rememberSaveable { mutableIntStateOf(AnswerSetupText.CODEX_WINDOW_SECONDS) }
    LaunchedEffect(answerHost, tick) {
        if (answerHost == null) { hostState = GuardedHostState.NotConnected; return@LaunchedEffect }
        hostState = GuardedHostState.Reading
        hostState = try { GuardedHostState.Known(answerHost.inspect()) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Throwable) { GuardedHostState.Failed(e.message?.lineSequence()?.firstOrNull()?.take(160) ?: "the read failed") }
    }
    val known = (hostState as? GuardedHostState.Known)?.setup
    val config = runCatching { AnswerSetupText.configCommand(window, codexWindow) }.getOrNull()
    val snippet = known?.let { runCatching { AnswerSetupText.settingsSnippet(it.hookDestination, window) }.getOrNull() }
    val codexSnippet = known?.let { runCatching { AnswerSetupText.codexSnippet(it.hookDestination, codexWindow) }.getOrNull() }
    val opencodeCommand = known?.opencodeDestination?.let { runCatching { AnswerSetupText.opencodeCommand(it) }.getOrNull() }
    val copy = { label: String, text: String?, which: GuardedCopied ->
        text?.let {
            if (copyText(ctx, CopyKind.GuardedSetup, it, label = label)) copied = which
        }
    }
    GuardedAnswers(
        GuardedAnswersUi(
            profile?.name ?: "this machine", answerHost?.decideSha256 ?: hostScripts.decideSha256, answerHost?.hookSha256 ?: hostScripts.hookSha256,
            hostState, installing, installError, window, config, snippet, copied,
            opencodeSha256 = answerHost?.opencodeSha256 ?: hostScripts.opencodeSha256, codexWindowSeconds = codexWindow,
            codexSnippet = codexSnippet, opencodeCommand = opencodeCommand,
        ),
        onBack = onBack,
        onInstall = {
            val h = answerHost ?: return@GuardedAnswers
            installing = true; installError = null
            scope.launch {
                try { h.install() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Throwable) { installError = "The install did not finish: " + (e.message?.lineSequence()?.firstOrNull()?.take(160) ?: "unknown error") }
                finally { installing = false; tick++ }
            }
        },
        onCheck = { tick++ },
        onWindow = { window = it; copied = GuardedCopied.None },
        onCodexWindow = { codexWindow = it; copied = GuardedCopied.None },
        onCopyConfig = { copy("Paddock guarded answers configuration", config, GuardedCopied.Config) },
        onCopyRegistration = { agent ->
            val text = when (agent) { GuardedAgent.Claude -> snippet; GuardedAgent.Codex -> codexSnippet; GuardedAgent.Opencode -> opencodeCommand }
            copy("Paddock guarded answers registration (${agent.label})", text, GuardedCopied.Registration)
        },
    )
}
