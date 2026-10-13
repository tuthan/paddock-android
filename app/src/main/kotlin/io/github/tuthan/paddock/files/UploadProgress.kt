package io.github.tuthan.paddock.files

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * How far the composer's upload of a file has got, per agent (Phase 15). The upload runs in the host's scope and outlives the composer's screen, so its
 * progress is the graph's too: a rotation, or the screen coming back after the app was away, draws the same progress and its Cancel. An entry is set when
 * a send with a file starts and goes when that agent's send is over.
 */
class UploadProgress {
    private val _sent = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** The bytes sent so far, by terminal id, for each upload under way. */
    val sent: StateFlow<Map<String, Long>> = _sent.asStateFlow()

    fun started(terminalId: String) = _sent.update { it + (terminalId to 0L) }

    /** Ignored once [ended]: a count that arrives late does not bring an upload back. */
    fun sent(terminalId: String, bytes: Long) = _sent.update { if (terminalId in it) it + (terminalId to bytes) else it }

    fun ended(terminalId: String) = _sent.update { it - terminalId }
}

/**
 * The snapshots the cache sweep keeps whatever their age within the day: the one the draft names, and the one a share waiting in the chooser (or behind the
 * lock) holds. The sweep runs at every activity creation, a rotation included, so a waiting share's file must not look abandoned.
 */
fun filesInUse(draftAttachment: String?, waiting: SharedItem?): Set<String> =
    setOfNotNull(draftAttachment, (waiting as? SharedItem.File)?.snapshot?.id)
