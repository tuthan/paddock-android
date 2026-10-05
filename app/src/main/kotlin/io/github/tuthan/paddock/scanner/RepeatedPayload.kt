package io.github.tuthan.paddock.scanner

/**
 * Says whether a code the camera has just read is news. A code left in front of the camera is read again every few frames, and each reading
 * must not announce itself again (nor, for a code that is not a pairing link, start the camera over): the same text is reported once, and
 * again only after it has been out of sight for [windowNanos]. Used by the one thread that decodes.
 */
class RepeatedPayload(private val windowNanos: Long) {
    private var last: String? = null
    private var lastSeenNanos = 0L

    /** True for a payload that is new, or has not been seen for [windowNanos]; every sighting, new or not, keeps the window open. */
    fun isNews(payload: String, nowNanos: Long): Boolean {
        val repeat = payload == last && nowNanos - lastSeenNanos < windowNanos
        last = payload
        lastSeenNanos = nowNanos
        return !repeat
    }
}
