package io.github.tuthan.paddock.alerts

import io.github.tuthan.paddock.reconcile.Freshness
import io.github.tuthan.paddock.reconcile.Installed
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withTimeoutOrNull

/** What the arrival rule needs from the watched host: the session it reads and its installed reads with their freshness. */
class AlertReads(val sessionName: String, val freshness: StateFlow<Freshness>, val installed: StateFlow<Installed?>)

/**
 * An alert is resolved only against a read the phone made after the alert arrived, on a connection that is [Freshness.Live]
 * (its stream acknowledged and a read made after that installed). Anything shown before that is the phone's older view, and
 * an alert never points at it.
 */
object AlertArrival {
    const val TIMEOUT_MILLIS = 20_000L

    /** The first installed read that started at or after [arrivedAtMillis] while live, or null when none came in [timeoutMillis]. */
    suspend fun freshRead(reads: AlertReads, arrivedAtMillis: Long, timeoutMillis: Long = TIMEOUT_MILLIS): Installed? =
        withTimeoutOrNull(timeoutMillis) {
            combine(reads.freshness, reads.installed) { f, i -> i?.takeIf { f == Freshness.Live && it.readAtMillis >= arrivedAtMillis } }
                .filterNotNull().first()
        }
}
