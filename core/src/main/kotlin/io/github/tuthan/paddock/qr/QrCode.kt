package io.github.tuthan.paddock.qr

/** Error-correction level. Only the two levels a short on-screen code needs; `Low` keeps the code small. */
enum class QrEcc(internal val formatBits: Int, internal val row: Int) { Low(1, 0), Medium(0, 1) }

/**
 * A QR Code symbol (ISO/IEC 18004) for UTF-8 text in byte mode, versions 1 to 40, levels L and M. It exists so the
 * phone's public key can be shown as a code without a dependency; it draws nothing and touches nothing else.
 * `QrCodeTest` checks it against an independent decoder and encoder when they are installed.
 */
class QrCode private constructor(val version: Int, val ecc: QrEcc, val mask: Int, private val modules: Array<BooleanArray>, private val functionModules: Array<BooleanArray>) {
    val size: Int = version * 4 + 17

    fun isDark(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size && modules[y][x]

    /** True for finder, timing, alignment, format and version modules: the ones no mask touches. */
    internal fun isFunction(x: Int, y: Int): Boolean = functionModules[y][x]

    companion object {
        const val MAX_BYTES_LOW = 2953

        /** Whether mask pattern [mask] flips the module at ([x], [y]). */
        internal fun maskInverts(mask: Int, x: Int, y: Int): Boolean = when (mask) {
            0 -> (x + y) % 2 == 0
            1 -> y % 2 == 0
            2 -> x % 3 == 0
            3 -> (x + y) % 3 == 0
            4 -> (x / 3 + y / 2) % 2 == 0
            5 -> x * y % 2 + x * y % 3 == 0
            6 -> (x * y % 2 + x * y % 3) % 2 == 0
            else -> ((x + y) % 2 + x * y % 3) % 2 == 0
        }

        fun encodeText(text: String, ecc: QrEcc = QrEcc.Low): QrCode {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val version = (1..40).firstOrNull { 4 + countBits(it) + 8 * bytes.size <= dataCodewords(it, ecc) * 8 }
                ?: throw IllegalArgumentException("text is too long for a QR code (${bytes.size} bytes)")
            val bits = BitBuffer()
            bits.append(0b0100, 4)
            bits.append(bytes.size, countBits(version))
            for (b in bytes) bits.append(b.toInt() and 0xFF, 8)
            val capacityBits = dataCodewords(version, ecc) * 8
            bits.append(0, minOf(4, capacityBits - bits.size))
            bits.append(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacityBits) { bits.append(pad, 8); pad = pad xor (0xEC xor 0x11) }
            val data = ByteArray(bits.size / 8)
            for (i in 0 until bits.size) if (bits.get(i)) data[i ushr 3] = (data[i ushr 3].toInt() or (1 shl (7 - (i and 7)))).toByte()
            return Builder(version, ecc, addEccAndInterleave(data, version, ecc)).build()
        }

        private fun countBits(version: Int) = if (version <= 9) 8 else 16

        private fun rawModules(ver: Int): Int {
            var result = (16 * ver + 128) * ver + 64
            if (ver >= 2) {
                val numAlign = ver / 7 + 2
                result -= (25 * numAlign - 10) * numAlign - 55
                if (ver >= 7) result -= 36
            }
            return result
        }

        private fun dataCodewords(ver: Int, ecc: QrEcc) = rawModules(ver) / 8 - ECC_PER_BLOCK[ecc.row][ver] * NUM_BLOCKS[ecc.row][ver]

        private fun addEccAndInterleave(data: ByteArray, ver: Int, ecc: QrEcc): ByteArray {
            val numBlocks = NUM_BLOCKS[ecc.row][ver]
            val blockEccLen = ECC_PER_BLOCK[ecc.row][ver]
            val rawCodewords = rawModules(ver) / 8
            val numShortBlocks = numBlocks - rawCodewords % numBlocks
            val shortBlockLen = rawCodewords / numBlocks
            val divisor = reedSolomonDivisor(blockEccLen)
            val blocks = ArrayList<ByteArray>()
            var k = 0
            for (i in 0 until numBlocks) {
                val dat = data.copyOfRange(k, k + shortBlockLen - blockEccLen + if (i < numShortBlocks) 0 else 1)
                k += dat.size
                val eccBytes = reedSolomonRemainder(dat, divisor)
                // Short blocks get a placeholder so every block has the same length; the placeholder is skipped below.
                blocks += (if (i < numShortBlocks) dat + 0.toByte() else dat) + eccBytes
            }
            val result = ByteArray(rawCodewords)
            var r = 0
            for (i in 0 until blocks[0].size) for (j in blocks.indices) {
                if (i != shortBlockLen - blockEccLen || j >= numShortBlocks) result[r++] = blocks[j][i]
            }
            check(r == rawCodewords)
            return result
        }

        private fun reedSolomonDivisor(degree: Int): ByteArray {
            val result = ByteArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in result.indices) {
                    result[j] = multiply(result[j].toInt() and 0xFF, root).toByte()
                    if (j + 1 < result.size) result[j] = (result[j].toInt() xor result[j + 1].toInt()).toByte()
                }
                root = multiply(root, 0x02)
            }
            return result
        }

        private fun reedSolomonRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
            val result = ByteArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() xor result[0].toInt()) and 0xFF
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = (result[i].toInt() xor multiply(divisor[i].toInt() and 0xFF, factor)).toByte()
            }
            return result
        }

        /** Multiplication in GF(2^8) modulo x^8 + x^4 + x^3 + x^2 + 1. */
        private fun multiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor (((y ushr i) and 1) * x)
            }
            return z and 0xFF
        }

        // Error-correction codewords per block and number of blocks, by level row (L, M) and version (index 0 unused).
        private val ECC_PER_BLOCK = arrayOf(
            intArrayOf(-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30),
            intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28),
        )
        private val NUM_BLOCKS = arrayOf(
            intArrayOf(-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25),
            intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49),
        )
    }

    private class BitBuffer {
        private val bits = ArrayList<Boolean>()
        val size get() = bits.size
        fun get(i: Int) = bits[i]
        fun append(value: Int, count: Int) { for (i in count - 1 downTo 0) bits += ((value ushr i) and 1) != 0 }
    }

    private class Builder(val version: Int, val ecc: QrEcc, val codewords: ByteArray) {
        val size = version * 4 + 17
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        fun build(): QrCode {
            drawFunctionPatterns()
            drawCodewords()
            var bestMask = 0
            var bestPenalty = Int.MAX_VALUE
            for (m in 0 until 8) {
                applyMask(m); drawFormatBits(m)
                val p = penalty()
                if (p < bestPenalty) { bestPenalty = p; bestMask = m }
                applyMask(m) // undo
            }
            applyMask(bestMask); drawFormatBits(bestMask)
            return QrCode(version, ecc, bestMask, modules, isFunction)
        }

        private fun set(x: Int, y: Int, dark: Boolean) { modules[y][x] = dark; isFunction[y][x] = true }

        private fun drawFunctionPatterns() {
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            drawFinder(3, 3); drawFinder(size - 4, 3); drawFinder(3, size - 4)
            val pos = alignmentPositions()
            for (i in pos.indices) for (j in pos.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == pos.size - 1) || (i == pos.size - 1 && j == 0)) continue
                drawAlignment(pos[i], pos[j])
            }
            drawFormatBits(0)
            drawVersion()
        }

        private fun drawFinder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val dist = maxOf(Math.abs(dx), Math.abs(dy))
                val x = cx + dx; val y = cy + dy
                if (x in 0 until size && y in 0 until size) set(x, y, dist != 2 && dist != 4)
            }
        }

        private fun drawAlignment(cx: Int, cy: Int) {
            for (dy in -2..2) for (dx in -2..2) set(cx + dx, cy + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
        }

        private fun alignmentPositions(): IntArray {
            if (version == 1) return IntArray(0)
            val numAlign = version / 7 + 2
            val step = (version * 8 + numAlign * 3 + 5) / (numAlign * 4 - 4) * 2
            val result = IntArray(numAlign)
            result[0] = 6
            var pos = size - 7
            for (i in numAlign - 1 downTo 1) { result[i] = pos; pos -= step }
            return result
        }

        private fun bit(x: Int, i: Int) = ((x ushr i) and 1) != 0

        private fun drawFormatBits(mask: Int) {
            val data = (ecc.formatBits shl 3) or mask
            var rem = data
            repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
            val bits = ((data shl 10) or rem) xor 0x5412
            for (i in 0..5) set(8, i, bit(bits, i))
            set(8, 7, bit(bits, 6)); set(8, 8, bit(bits, 7)); set(7, 8, bit(bits, 8))
            for (i in 9..14) set(14 - i, 8, bit(bits, i))
            for (i in 0..7) set(size - 1 - i, 8, bit(bits, i))
            for (i in 8..14) set(8, size - 15 + i, bit(bits, i))
            set(8, size - 8, true)
        }

        private fun drawVersion() {
            if (version < 7) return
            var rem = version
            repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val b = bit(bits, i)
                val a = size - 11 + i % 3
                val c = i / 3
                set(a, c, b); set(c, a, b)
            }
        }

        private fun drawCodewords() {
            var i = 0
            var right = size - 1
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y][x] && i < codewords.size * 8) {
                        modules[y][x] = bit(codewords[i ushr 3].toInt(), 7 - (i and 7))
                        i++
                    }
                }
                right -= 2
            }
            check(i == codewords.size * 8)
        }

        private fun applyMask(mask: Int) {
            for (y in 0 until size) for (x in 0 until size) {
                if (isFunction[y][x]) continue
                val invert = maskInverts(mask, x, y)
                if (invert) modules[y][x] = !modules[y][x]
            }
        }

        private fun penalty(): Int {
            var result = 0
            for (y in 0 until size) {
                var runColor = false; var runX = 0
                val history = IntArray(7)
                for (x in 0 until size) {
                    if (modules[y][x] == runColor) {
                        runX++
                        if (runX == 5) result += 3 else if (runX > 5) result++
                    } else {
                        addHistory(runX, history)
                        if (!runColor) result += countPatterns(history) * 40
                        runColor = modules[y][x]; runX = 1
                    }
                }
                result += terminateAndCount(runColor, runX, history) * 40
            }
            for (x in 0 until size) {
                var runColor = false; var runY = 0
                val history = IntArray(7)
                for (y in 0 until size) {
                    if (modules[y][x] == runColor) {
                        runY++
                        if (runY == 5) result += 3 else if (runY > 5) result++
                    } else {
                        addHistory(runY, history)
                        if (!runColor) result += countPatterns(history) * 40
                        runColor = modules[y][x]; runY = 1
                    }
                }
                result += terminateAndCount(runColor, runY, history) * 40
            }
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val c = modules[y][x]
                if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3
            }
            var dark = 0
            for (row in modules) for (m in row) if (m) dark++
            val total = size * size
            val k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1
            return result + k * 10
        }

        private fun countPatterns(h: IntArray): Int {
            val n = h[1]
            val core = n > 0 && h[2] == n && h[3] == n * 3 && h[4] == n && h[5] == n
            return (if (core && h[0] >= n * 4 && h[6] >= n) 1 else 0) + (if (core && h[6] >= n * 4 && h[0] >= n) 1 else 0)
        }

        private fun terminateAndCount(runColor: Boolean, runLength: Int, h: IntArray): Int {
            var len = runLength
            if (runColor) { addHistory(len, h); len = 0 }
            len += size
            addHistory(len, h)
            return countPatterns(h)
        }

        private fun addHistory(runLength: Int, h: IntArray) {
            var len = runLength
            if (h[0] == 0) len += size
            System.arraycopy(h, 0, h, 1, h.size - 1)
            h[0] = len
        }
    }
}
