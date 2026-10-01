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

    @OptIn(ExperimentalCoroutinesApi::class)
    fun attach(controller: HostSessionController) {
        job?.cancel()
        job = scope.launch {
            controller.phase.collectLatest { phase ->
                if (phase is HostPhase.Monitoring) {
                    val host = phase.host
                    combine(host.home, host.freshness, host.reconciler.installed) { home, fresh, installed -> Triple(home, fresh, installed) }
                        .collect { (home, fresh, installed) ->
                            _view.value = _view.value.copy(
                                phase = phase, home = home, freshness = fresh,
                                lastHome = home ?: _view.value.lastHome, lastReadAtMillis = installed?.readAtMillis ?: _view.value.lastReadAtMillis,
                            )
                        }
                } else {
                    _view.value = _view.value.copy(phase = phase, home = null, freshness = null)
                }
            }
        }
    }

    fun detach() { job?.cancel(); job = null }
}
