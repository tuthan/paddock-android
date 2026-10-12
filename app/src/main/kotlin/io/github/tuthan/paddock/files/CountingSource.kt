package io.github.tuthan.paddock.files

import java.io.FilterInputStream
import java.io.InputStream

/** A [ByteSource] that tells [onBytes] how many bytes have been read in all, for the transfer sheet's progress. It changes nothing about the bytes. */
class CountingSource(private val inner: ByteSource, private val onBytes: (Long) -> Unit) : ByteSource {
    override fun open(): InputStream = object : FilterInputStream(inner.open()) {
        private var total = 0L
        override fun read(): Int = super.read().also { if (it >= 0) { total++; onBytes(total) } }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) { total += it; onBytes(total) } }
    }
}
