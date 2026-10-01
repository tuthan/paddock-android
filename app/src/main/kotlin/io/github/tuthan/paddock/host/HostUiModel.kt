package io.github.tuthan.paddock.host

import io.github.tuthan.paddock.live.HostPhase
import io.github.tuthan.paddock.live.HostSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * One machine's view, kept across leaving and returning to the app: each [attach] follows a new controller, and the
 * last home it saw stays as the dimmed fallback while the next connection comes up.
 */
class HostUiModel(private val scope: CoroutineScope) {
    private val _view = MutableStateFlow(HostView())
    val view: StateFlow<HostView> = _view.asStateFlow()
    private var job: Job? = null
    private var profileId: String? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    fun attach(controller: HostSessionController) {
        job?.cancel()
        // Another machine's last home is not this one's fallback.
        if (controller.profile.id != profileId) { profileId = controller.profile.id; _view.value = HostView() }
        job = scope.launch {
            controller.phase.collectLatest { phase ->
                if (phase is HostPhase.Monitoring) {
                    val host = phase.host
                    combine(host.home, host.freshness, host.reconciler.installed, host.blockedPreview, host.lastLoss) { home, fresh, installed, preview, loss -> Snapshot5(home, fresh, installed, preview, loss) }
                        .collect { (home, fresh, installed, preview, loss) ->
                            _view.value = _view.value.copy(
                                phase = phase, home = home, freshness = fresh, blockedPreview = preview, lastLoss = loss,
                                lastHome = home ?: _view.value.lastHome, lastReadAtMillis = installed?.readAtMillis ?: _view.value.lastReadAtMillis,
                                herdrVersion = installed?.snapshot?.version ?: _view.value.herdrVersion,
                            )
                        }
                } else {
                    _view.value = _view.value.copy(phase = phase, home = null, freshness = null, blockedPreview = null, lastLoss = null)
                }
            }
        }
    }

    fun detach() { job?.cancel(); job = null }
}

private data class Snapshot5(
    val home: io.github.tuthan.paddock.attention.HomeModel?,
    val freshness: io.github.tuthan.paddock.reconcile.Freshness,
    val installed: io.github.tuthan.paddock.reconcile.Installed?,
    val preview: io.github.tuthan.paddock.live.BlockedPreview?,
    val loss: Throwable?,
)
