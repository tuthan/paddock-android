package io.github.tuthan.paddock.files

/**
 * What the intake made of a picked, captured or shared file (SEC-2): one private copy, sniffed, stripped of image metadata where it applies and
 * hashed, so what is sent is what was checked. [id] names the copy for the draft to keep, [name] is the sanitized inbox name, [mime] is the sniff's
 * and never the sender's claim, [size] and [sha256] are the copy's. No Android type: `:app` builds it and hands the bytes over as a [ByteSource].
 */
data class Snapshot(val id: String, val name: String, val mime: String, val size: Long, val sha256: String)
