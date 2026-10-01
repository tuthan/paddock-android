package io.github.tuthan.paddock.ports

/** Accumulates at most [max] bytes and remembers whether more arrived. Adapters use one per stream. */
class BoundedBytes(private val max: Int) {
    private val buffer = java.io.ByteArrayOutputStream(minOf(max, 8192))
    var truncated: Boolean = false
        private set

    fun append(chunk: ByteArray, length: Int = chunk.size) {
        val room = max - buffer.size()
        if (length > room) truncated = true
        if (room > 0) buffer.write(chunk, 0, minOf(length, room))
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()
}
