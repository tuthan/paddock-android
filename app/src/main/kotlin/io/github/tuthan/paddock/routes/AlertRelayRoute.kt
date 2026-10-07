package io.github.tuthan.paddock.routes

import android.content.ClipData
import android.content.ClipboardManager
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
import io.github.tuthan.paddock.AppGraph
import io.github.tuthan.paddock.alerts.AlertSetup
import io.github.tuthan.paddock.alerts.AlertSetupCopy
import io.github.tuthan.paddock.alerts.AlertSetupRunner
import io.github.tuthan.paddock.alerts.AlertSetupScript
import io.github.tuthan.paddock.alerts.DeliveryMode
import io.github.tuthan.paddock.alerts.NtfyServer
import io.github.tuthan.paddock.alerts.ServicePlatform
import io.github.tuthan.paddock.alerts.SetupOutcome
import io.github.tuthan.paddock.alerts.SetupRun
import io.github.tuthan.paddock.alerts.SetupStep
import io.github.tuthan.paddock.alerts.StepState
import io.github.tuthan.paddock.hostprofile.HostProfile
import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.ui.screens.AlertForm
import io.github.tuthan.paddock.ui.screens.AlertRelay
import io.github.tuthan.paddock.ui.screens.AlertRelayHostState
import io.github.tuthan.paddock.ui.screens.AlertRelayUi
import io.github.tuthan.paddock.ui.screens.PushDistributorUi
import io.github.tuthan.paddock.ui.screens.PushRegisteredUi
import io.github.tuthan.paddock.ui.screens.PushUi
import io.github.tuthan.paddock.ui.screens.SetupRunUi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Locked-phone alerts on the watched machine, in one step: the form's two choices, one confirmation, then `AlertSetupRunner` does the rest over the live
 * connection (registers with the ntfy app when Paddock shows the alerts, installs the pinned relay, writes the configuration, checks it, enables the unit).
 * What was chosen is remembered per machine (`AppGraph.alertSetups`) so the subscribe link can be shown again and a second run keeps the topic.
 */
@Composable
internal fun AlertRelayRoute(graph: AppGraph, onBack: () -> Unit) {
    val view by graph.hostUi.view.collectAsState()
    val profile by graph.machineBook.profile.collectAsState()
    val host = (view.phase as? HostPhase.Monitoring)?.host
    val relay = host?.alertRelay
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var hostState by remember(relay) { mutableStateOf<AlertRelayHostState>(if (relay == null) AlertRelayHostState.NotConnected else AlertRelayHostState.Reading) }
    var installing by remember { mutableStateOf(false) }
    var installError by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }
    var run by remember { mutableStateOf(SetupRunUi()) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var resultIsProblem by remember { mutableStateOf(false) }
    // How the machine keeps the relay running (systemd on Linux, a LaunchAgent on a Mac): what it said it is when it last connected, then what it says now. The script
    // shown under "Details and manual setup" and the script that runs are built from this one value.
    var platform by remember(profile?.id) { mutableStateOf(ServicePlatform.of(profile?.detectedOs ?: profile?.os)) }
    LaunchedEffect(relay) { if (relay != null) platform = try { relay.platform() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { platform } }
    // The registrations and the distributors on the phone, read again on every return (an app may have been installed meanwhile).
    val registrations by graph.push.registrations.collectAsState()
    val pushNotice by graph.push.notice.collectAsState()
    val resumes = rememberResumes()
    LaunchedEffect(Unit) { graph.push.refresh() }
    // The ntfy app first: it is the distributor most people have, so it is the one picked unless the user picks another.
    val distributors = remember(resumes, registrations) { graph.push.distributors().sortedByDescending { it.packageName == NTFY_PACKAGE } }
    var onHost by remember(relay, tick) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(relay, tick) { onHost = if (relay == null) null else try { relay.hasPushEndpoint() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { null } }
    val reg = registrations.firstOrNull { it.profile == profile?.id }
    LaunchedEffect(relay, tick) {
        if (relay == null) { hostState = AlertRelayHostState.NotConnected; return@LaunchedEffect }
        hostState = AlertRelayHostState.Reading
        hostState = try { AlertRelayHostState.Known(relay.inspect()) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Throwable) { AlertRelayHostState.Failed(e.message?.take(160) ?: "the read failed") }
    }

    // What this phone last set up for the machine, and the form. The form starts from it (or from what is installed on the phone) and then belongs to the user.
    val machineId = profile?.id
    var saved by remember(machineId, tick) { mutableStateOf<io.github.tuthan.paddock.alerts.SavedAlertSetup?>(null) }
    var savedRead by remember(machineId) { mutableStateOf(false) }
    LaunchedEffect(machineId, tick) { saved = machineId?.let { runCatching { graph.alertSetups.get(it) }.getOrNull() }; savedRead = true }
    var modeName by rememberSaveable(machineId) { mutableStateOf<String?>(null) }
    var ownServer by rememberSaveable(machineId) { mutableStateOf(false) }
    var ownUrl by rememberSaveable(machineId) { mutableStateOf("") }
    var token by rememberSaveable(machineId) { mutableStateOf("") }
    var distributor by rememberSaveable(machineId) { mutableStateOf<String?>(null) }
    var topic by rememberSaveable(machineId) { mutableStateOf<String?>(null) }
    LaunchedEffect(savedRead, saved, distributors.isEmpty()) {
        if (!savedRead || modeName != null) return@LaunchedEffect
        val s = saved
        if (s != null) {
            modeName = s.mode.name
            if (s.mode == DeliveryMode.NtfyApp) { ownServer = !NtfyServer.isPublic(s.ntfyUrl); ownUrl = if (ownServer) s.ntfyUrl else ""; token = s.token }
        } else modeName = (if (distributors.isNotEmpty()) DeliveryMode.Push else DeliveryMode.NtfyApp).name
    }
    val form = AlertForm(modeName?.let { DeliveryMode.valueOf(it) } ?: DeliveryMode.NtfyApp, ownServer, ownUrl, token, distributor)
    val checkedForm = io.github.tuthan.paddock.alerts.AlertSetupForm.check(form.mode, form.ownServer, form.ownUrl, form.token, distributors.size)

    /** The topic is made once per machine, kept across runs (a new one would orphan the ntfy app's subscription) and never typed. */
    fun topicForSetup(): String = topic ?: saved?.topic?.takeIf { it.isNotEmpty() } ?: NtfyServer.newTopic(ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }).also { topic = it }
    fun setupOrNull(): AlertSetup? {
        val p = profile ?: return null; val h = host ?: return null
        if (!checkedForm.canStart) return null
        return AlertSetup(
            p.id, p.name, form.mode, h.socketPath,
            ntfyUrl = checkedForm.serverUrl ?: NtfyServer.PUBLIC, ntfyTopic = if (form.mode == DeliveryMode.NtfyApp) topicForSetup() else "", ntfyToken = if (form.mode == DeliveryMode.NtfyApp && form.ownServer) form.token else "",
            platform = platform,
        )
    }
    val script = setupOrNull()?.let { s -> runCatching { AlertSetupScript.build(graph.hostScripts.alertUnit, s, graph.hostScripts.alertSha256) }.getOrNull() }

    suspend fun obtainEndpoint(p: HostProfile): String? {
        val pkg = form.distributor?.takeIf { d -> distributors.any { it.packageName == d } } ?: distributors.firstOrNull()?.packageName ?: return null
        val existing = graph.push.registrations.value.firstOrNull { it.profile == p.id }
        if (existing != null && existing.distributor == pkg && existing.endpoint != null) return existing.endpoint
        graph.push.register(p.id, p.name, pkg)
        return kotlinx.coroutines.withTimeoutOrNull(20_000) {
            graph.push.registrations.first { all -> all.firstOrNull { it.profile == p.id }?.endpoint != null }.first { it.profile == p.id }.endpoint
        }
    }

    AlertRelay(
        AlertRelayUi(
            profile?.name ?: "this machine", profile?.id ?: "", graph.hostScripts.alertSha256, hostState,
            saved = saved?.takeIf { it.enabled },
            push = PushUi(
                distributors = distributors.map { PushDistributorUi(it.packageName, it.label) },
                registered = reg?.let { r ->
                    PushRegisteredUi(
                        distributorLabel = distributors.firstOrNull { it.packageName == r.distributor }?.label ?: r.distributor,
                        distributorInstalled = distributors.any { it.packageName == r.distributor },
                        endpointHost = r.endpoint?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() },
                        hasEndpoint = r.endpoint != null, shared = r.sharedAtMillis != null, failure = r.failure?.label,
                    )
                },
                notice = pushNotice, onHost = onHost,
            ),
            run = run, script = script, copied = copied, installing = installing, installError = installError, busy = busy, result = result, resultIsProblem = resultIsProblem,
            platform = platform,
        ),
        form = form,
        onForm = { f ->
            modeName = f.mode.name; ownServer = f.ownServer; ownUrl = f.ownUrl; token = f.token; distributor = f.distributor
            copied = false; result = null
        },
        onBack = onBack,
        onTurnOn = {
            val r = relay ?: return@AlertRelay
            val p = profile ?: return@AlertRelay
            val setup = setupOrNull() ?: return@AlertRelay
            val steps = (if (setup.mode == DeliveryMode.Push) SetupStep.entries else SetupStep.entries.filter { it != SetupStep.Register && it != SetupStep.SendAddress })
            run = SetupRunUi(steps = steps.map { it to StepState.Pending }, running = true); result = null; copied = false
            scope.launch {
                val outcome = AlertSetupRunner(r, graph.hostScripts.alertUnit, graph.hostScripts.alertSha256).run(setup, { obtainEndpoint(p) }) { step, state ->
                    run = run.copy(steps = run.steps.map { if (it.first == step) step to state else it })
                }
                when (outcome) {
                    is SetupRun.Done -> {
                        runCatching {
                            graph.alertSetups.put(p.id, io.github.tuthan.paddock.alerts.SavedAlertSetup(setup.mode, setup.ntfyUrl, setup.ntfyTopic, setup.ntfyToken, graph.clock.nowMillis()))
                            if (setup.mode == DeliveryMode.Push) graph.push.markShared(p.id)
                            else if (reg != null) { graph.push.unregister(p.id); runCatching { r.removePushEndpoint() } } // the earlier way is gone: no stale address left behind
                        }
                        run = run.copy(running = false, finished = true, note = outcome.lingerNote)
                    }
                    is SetupRun.Stopped -> run = run.copy(running = false, message = outcome.message)
                }
                tick++
            }
        },
        onTurnOff = {
            val r = relay ?: return@AlertRelay
            val p = profile ?: return@AlertRelay
            busy = true; result = null; run = SetupRunUi()
            scope.launch {
                try {
                    when (val o = r.turnOff()) {
                        is SetupOutcome.Done -> {
                            runCatching { graph.push.unregister(p.id) }
                            // Kept with its topic, but off: turning alerts on again changes nothing the ntfy app subscribed to.
                            saved?.let { s -> runCatching { graph.alertSetups.put(p.id, s.copy(enabled = false)) } }
                            result = "Alerts are off for ${p.name}."; resultIsProblem = false
                        }
                        SetupOutcome.NoSystemd -> { result = AlertSetupCopy.NO_SYSTEMD; resultIsProblem = true }
                        else -> { result = "Could not turn the relay off: ${(o as? SetupOutcome.Failed)?.message ?: "unknown error"}"; resultIsProblem = true }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Throwable) { result = "Could not turn the relay off: " + (e.message?.take(160) ?: "unknown error"); resultIsProblem = true }
                finally { busy = false; tick++ }
            }
        },
        onTest = {
            val r = relay ?: return@AlertRelay
            busy = true; result = null
            scope.launch {
                try {
                    when (val o = r.sendTest()) {
                        AlertSetupScript.TestOutcome.Sent -> { result = "Test sent. It should arrive on this phone within a few seconds. If it does not, check the server address, and on the ntfy app that it is subscribed."; resultIsProblem = false }
                        is AlertSetupScript.TestOutcome.Failed -> { result = "The test did not go out (${o.reason}). Check that ${profile?.name ?: "the machine"} can reach the server."; resultIsProblem = true }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Throwable) { result = "The test did not go out: " + (e.message?.take(160) ?: "unknown error"); resultIsProblem = true }
                finally { busy = false }
            }
        },
        onInstall = {
            val r = relay ?: return@AlertRelay
            installing = true; installError = null
            scope.launch {
                try { r.install() }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Throwable) { installError = "The install did not finish: " + (e.message?.take(160) ?: "unknown error") }
                finally { installing = false; tick++ }
            }
        },
        onCheck = { tick++ },
        onCopy = {
            script?.let {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Paddock alert relay setup", it))
                copied = true
            }
        },
        // Explicitly to the ntfy app: the link carries the topic, which is the secret of this mode, so no other app that handles ntfy:// gets it.
        onOpenNtfy = { link ->
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(link)).setPackage(NTFY_PACKAGE)
            try { ctx.startActivity(intent) }
            catch (_: android.content.ActivityNotFoundException) { result = "The ntfy app is not installed on this phone. Install it, then try again."; resultIsProblem = true }
        },
        onCopyLink = { link ->
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ntfy subscribe link", link))
            result = "Copied. Open it on a phone with the ntfy app, or enter the server and topic in the app by hand."; resultIsProblem = false
        },
    )
}

private const val NTFY_PACKAGE = "io.heckel.ntfy"
