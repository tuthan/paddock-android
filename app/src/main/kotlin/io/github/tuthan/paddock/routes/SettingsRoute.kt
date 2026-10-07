package io.github.tuthan.paddock.routes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.tuthan.paddock.AppGraph
import io.github.tuthan.paddock.applock.AppLockCopy
import io.github.tuthan.paddock.applock.AuthResult
import io.github.tuthan.paddock.applock.rememberDeviceAuth
import io.github.tuthan.paddock.billing.ProView
import io.github.tuthan.paddock.ui.screens.AlertsState
import io.github.tuthan.paddock.ui.screens.LocalAccess
import io.github.tuthan.paddock.ui.screens.ProCardState
import io.github.tuthan.paddock.ui.screens.Settings
import io.github.tuthan.paddock.ui.screens.SettingsState
import io.github.tuthan.paddock.ui.screens.TipOption
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings' Pro card from the graph's view of Pro. The foss build says everything is unlocked; the play build shows the store's last answer and its age.
 * What Pro covers (D5): without Pro, the "What Pro covers" row with the short list (it opens the overview sheet); with Pro bought, the one sentence;
 * in a build that unlocks everything, neither, since nothing there is Pro. Internal so `SettingsTest` can hold a held Pro view to the same rule.
 */
internal fun proCard(pro: ProView, nowMillis: Long): ProCardState {
    val summary = io.github.tuthan.paddock.billing.EntitlementPresenter.summary(pro.state, nowMillis, pro.unlocked, pro.reachable, pro.sellsPro)
    // The one grant rule (Entitlements.hasPro): an unlocked build holds Pro, so "not held" is also "not unlocked".
    val held = io.github.tuthan.paddock.billing.Entitlements.hasPro(pro.state, pro.unlocked, pro.sellsPro)
    return ProCardState(
        headline = summary.headline, detail = summary.detail, stale = summary.stale, sellsPro = pro.sellsPro, busy = pro.busy, message = pro.message,
        coverage = if (held && !pro.unlocked) io.github.tuthan.paddock.billing.ProCopy.coverage(io.github.tuthan.paddock.billing.ProGate.GATED) else "",
        overview = if (held) "" else io.github.tuthan.paddock.billing.ProCopy.coverageShort(io.github.tuthan.paddock.billing.ProGate.GATED),
        refunds = if (pro.sellsPro) io.github.tuthan.paddock.billing.ProCopy.REFUNDS else null,
        tips = if (pro.sellsPro) io.github.tuthan.paddock.billing.Products.TIPS.mapNotNull { id -> pro.prices[id]?.let { TipOption(id, it) } } else emptyList(),
    )
}

/** Settings fed from the graph. Internal so `SettingsTest` can drive the Pro card and its overview through the app's real graph. */
@Composable
internal fun SettingsRoute(graph: AppGraph, onBack: () -> Unit, onMachines: () -> Unit, onEditSnippets: () -> Unit, onAlertRelay: () -> Unit) {
    val snippets by graph.snippets.collectAsState()
    val settings by graph.settings.collectAsState()
    val profile by graph.machineBook.profile.collectAsState()
    val machines by graph.machineBook.machines.collectAsState()
    val view by graph.hostUi.view.collectAsState()
    val pro by graph.proService.view.collectAsState()
    // The store's prices (the tip buttons) are asked for when the Pro card is first shown, not at start; a no-op after they are known.
    androidx.compose.runtime.LaunchedEffect(Unit) { graph.proService.loadPrices() }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // The grant can change in system settings while Paddock is in the background: read it again on every return.
    val resumes = rememberResumes()
    val access = remember(resumes) {
        when {
            !graph.gate.lanAccessApplies() -> LocalAccess.NotRequired
            graph.gate.lanAccessMissing() -> LocalAccess.Denied
            else -> LocalAccess.Granted
        }
    }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "unknown" }
    val journalUnreadable by graph.journal.unreadable.collectAsState()
    // Read again on every return from system settings and after the dialog answers: the user can change any of it out of our sight.
    var accessTick by remember { mutableIntStateOf(0) }
    val activity = remember(ctx) { generateSequence(ctx) { (it as? android.content.ContextWrapper)?.baseContext }.firstOrNull { it is android.app.Activity } as? android.app.Activity }
    val notificationAccess = remember(resumes, accessTick, settings.notificationPermissionAsked) { graph.notificationAccess.read(activity, settings.notificationPermissionAsked) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { accessTick++ }
    val recovery = io.github.tuthan.paddock.alerts.NotificationAccessRules.recovery(notificationAccess)
    // Whether the phone has a screen lock to ask: read again on every return, because the user may set one in Android's settings and come back.
    val auth = rememberDeviceAuth()
    val deviceLock = remember(resumes) { auth.lock() }
    fun askPermission() {
        graph.prefs.notePermissionAsked()
        permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    Settings(
        SettingsState(
            settings.protectSensitiveScreens, access, version, machineCount = machines.size, watchedMachine = profile?.name, herdrVersion = view.herdrVersion, keepPromptText = settings.keepPromptText, snippetCount = snippets.size, journalUnreadable = journalUnreadable,
            alerts = AlertsState(settings.localAlerts, settings.hidePromptOnLockScreen, recovery), agentGlyphs = settings.agentGlyphs,
            pro = proCard(pro, graph.clock.nowMillis()),
            appLock = settings.appLock, appLockAfterSeconds = settings.appLockAfterSeconds, deviceLock = deviceLock,
        ),
        onAppLock = { on ->
            // Turning it on proves the phone can be asked (so a lock nobody can open is never switched on); turning it off proves it is the user, not whoever holds an open phone.
            graph.appLock.authenticating()
            auth.authenticate(if (on) AppLockCopy.TURN_ON_TITLE else AppLockCopy.TURN_OFF_TITLE, AppLockCopy.CONFIRM_SUBTITLE) { r ->
                graph.appLock.cancelled()
                if (r == AuthResult.Success) scope.launch { if (!graph.prefs.setAppLock(on)) android.widget.Toast.makeText(ctx, "The setting could not be saved.", android.widget.Toast.LENGTH_LONG).show() }
                else if (r is AuthResult.Failed) android.widget.Toast.makeText(ctx, AppLockCopy.failed(r.message), android.widget.Toast.LENGTH_LONG).show()
            }
        },
        onAppLockAfter = { graph.prefs.setAppLockAfter(it) },
        onOpenSecuritySettings = { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) } },
        onRestorePurchase = { graph.proService.restore() },
        onBuyTip = { graph.proService.buyTip(it) },
        // The sheet is the one purchase surface: the row only asks for it (idle opens it, busy says why not now).
        onProOverview = { graph.proService.openProOverview() },
        onProtectSensitive = { scope.launch { graph.prefs.setProtectSensitive(it) } },
        onAgentGlyphs = { graph.prefs.setAgentGlyphs(it) },
        onOpenSystemSettings = { ctx.startActivity(graph.gate.settingsIntent()) },
        onBack = onBack,
        onMachines = onMachines,
        onKeepPromptText = { graph.prefs.setKeepPromptText(it) },
        onEditSnippets = onEditSnippets,
        onRetryJournal = { graph.journal.retryLoad() },
        // Off the main thread: setting the file aside is disk work. A failure leaves the journal unreadable, which the card keeps saying.
        onResetJournal = { scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { graph.journal.resetUnreadable() } } },
        // Turning alerts on is the moment the permission is asked for (Android 13 and later); a refusal leaves the recovery row.
        onLocalAlerts = { on ->
            graph.prefs.setLocalAlerts(on)
            if (on && notificationAccess is io.github.tuthan.paddock.alerts.NotificationAccess.NeedsPermission && notificationAccess.canAsk) askPermission()
        },
        onHideOnLockScreen = { graph.prefs.setHidePromptOnLockScreen(it) },
        onAlertRelay = onAlertRelay,
        onAlertRecovery = { action ->
            if (action == io.github.tuthan.paddock.alerts.AccessRecovery.Action.AskPermission) askPermission()
            else recovery?.let { r -> graph.notificationAccess.settingsIntent(r, notificationAccess)?.let { ctx.startActivity(it) } }
        },
    )
}
