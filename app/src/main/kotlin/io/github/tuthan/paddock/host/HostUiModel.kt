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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One machine's view, kept across leaving and returning to the app: each [attach] follows a new controller, and the
 * last home it saw stays as the dimmed fallback while the next connection comes up.
 */
class HostUiModel(private val scope: CoroutineScope) {
    private val _view = MutableStateFlow(HostView())
    val view: StateFlow<HostView> = _view.asStateFlow()
    private var job: Job? = null
    private var spacesJob: Job? = null
    private var profileId: String? = null

    /** The phase on screen when the latest Connect was pressed: a failure that is this very object is the previous attempt's, not the new one's. */
    @Volatile var phaseBeforeAttempt: HostPhase? = null
        private set

    fun beginAttempt() { phaseBeforeAttempt = _view.value.phase }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun attach(controller: HostSessionController) {
        job?.cancel(); spacesJob?.cancel()
        // Another machine's last home is not this one's fallback.
        if (controller.profile.id != profileId) { profileId = controller.profile.id; _view.value = HostView() }
        spacesJob = scope.launch { controller.spaces.collect { spaces -> _view.update { it.copy(spaces = spaces) } } }
        job = scope.launch {
            controller.phase.collectLatest { phase ->
                if (phase is HostPhase.Monitoring) {
                    val host = phase.host
                    combine(host.home, host.freshness, host.reconciler.installed, host.blockedPreview, host.lastLoss) { home, fresh, installed, preview, loss -> Snapshot5(home, fresh, installed, preview, loss) }
                        .collect { (home, fresh, installed, preview, loss) ->
                            _view.update { v ->
                                v.copy(
                                    phase = phase, home = home, freshness = fresh, blockedPreview = preview, lastLoss = loss,
                                    lastHome = home ?: v.lastHome, lastReadAtMillis = installed?.readAtMillis ?: v.lastReadAtMillis,
                                    herdrVersion = installed?.snapshot?.version ?: v.herdrVersion,
                                )
                            }
                        }
                } else {
                    _view.update { it.copy(phase = phase, home = null, freshness = null, blockedPreview = null, lastLoss = null) }
                }
            }
        }
    }

    fun detach() { job?.cancel(); job = null; spacesJob?.cancel(); spacesJob = null }
}

private data class Snapshot5(
    val home: io.github.tuthan.paddock.attention.HomeModel?,
    val freshness: io.github.tuthan.paddock.reconcile.Freshness,
    val installed: io.github.tuthan.paddock.reconcile.Installed?,
    val preview: io.github.tuthan.paddock.live.BlockedPreview?,
    val loss: Throwable?,
)
