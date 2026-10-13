package io.github.tuthan.paddock.files

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * What another app shared to Paddock, after the intake's checks (SEC-1 to SEC-3): a text, cleaned, or a file as a private [Snapshot] with the text that
 * came with it, if any. [removed] is how many hidden characters the text lost, for the draft to say. Nothing here is the sender's claim about itself.
 */
sealed interface SharedItem {
    data class Text(val text: String, val removed: Int = 0) : SharedItem
    /** [senderName] is the sender's own name for the file, the only input the drop folder's name rule (and its refusal of instruction files) can read; never shown as it came. */
    data class File(val snapshot: Snapshot, val text: String? = null, val removed: Int = 0, val senderName: String? = null) : SharedItem
}

/**
 * The one place a share waits for the screen layer, in [io.github.tuthan.paddock.hostprofile.PairingInbox]'s pattern. One item waits at a time (SEC-8: a flood of shares
 * cannot pile up snapshots): [offer] replaces the one waiting and hands it to [onDiscard], so the app can delete its snapshot. [take] hands the
 * waiting item to the caller, who owns it from then on, and so does not call [onDiscard]; [discard] is for when the user dismissed it or it cannot be
 * used. No lock is held while [onDiscard] runs, and it runs once for each item that leaves without being taken, on the thread that made it leave.
 *
 * Offering the very item that waits is not a replacement ([onDiscard] would delete the snapshot the item still holds).
 */
class ShareInbox(private val onDiscard: (SharedItem) -> Unit = {}) {
    private val _pending = MutableStateFlow<SharedItem?>(null)
    val pending: StateFlow<SharedItem?> = _pending.asStateFlow()

    fun offer(item: SharedItem) {
        val old = _pending.getAndUpdate { item }
        if (old != null && old !== item) onDiscard(old)
    }

    /** Returns the waiting item and clears it. The caller owns it now. */
    fun take(): SharedItem? = _pending.getAndUpdate { null }

    /** Clears the waiting item, if any, and hands it to [onDiscard]. */
    fun discard() {
        _pending.getAndUpdate { null }?.let(onDiscard)
    }

    /** Takes [item] only while it is still the one waiting, as [discard] does; false when a newer item took its place, which the screen then shows instead. */
    fun take(item: SharedItem): Boolean = _pending.compareAndSet(item, null)

    /**
     * Clears [item] and hands it to [onDiscard] only while it is still the one waiting: what the person dismissed is the item they saw, never a newer one that
     * took its place a moment before (a file is told apart by its snapshot; two equal texts are one item). False, and nothing changes, when another item waits
     * or none does.
     */
    fun discard(item: SharedItem): Boolean {
        if (!_pending.compareAndSet(item, null)) return false
        onDiscard(item)
        return true
    }
}
