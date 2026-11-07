package app.tvplay.ui.theme

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Pure-Kotlin ISO/IEC 18004 QR Code encoder and Bitmap renderer (Versions 1..6, ECC Level L, Byte mode).
 * Used to render Wi-Fi Direct (`WIFI:T:WPA;S:<ssid>;P:<psk>;;`) and pairing QR codes on TV Stage.
 */
object TvQrCodeRenderer {

    private data class VersionSpec(
        val version: Int,
        val totalCodewords: Int,
        val dataCodewords: Int,
        val ecCodewordsPerBlock: Int,
        val numBlocks: Int,
        val alignmentCenters: IntArray,
    ) {
        val size: Int get() = 17 + version * 4
        val byteCapacity: Int get() = dataCodewords - 2
    }

    private val VERSION_SPECS = listOf(
        VersionSpec(1, totalCodewords = 26, dataCodewords = 19, ecCodewordsPerBlock = 7, numBlocks = 1, alignmentCenters = intArrayOf()),
        VersionSpec(2, totalCodewords = 44, dataCodewords = 34, ecCodewordsPerBlock = 10, numBlocks = 1, alignmentCenters = intArrayOf(6, 18)),
        VersionSpec(3, totalCodewords = 70, dataCodewords = 55, ecCodewordsPerBlock = 15, numBlocks = 1, alignmentCenters = intArrayOf(6, 22)),
        VersionSpec(4, totalCodewords = 100, dataCodewords = 80, ecCodewordsPerBlock = 20, numBlocks = 1, alignmentCenters = intArrayOf(6, 26)),
        VersionSpec(5, totalCodewords = 134, dataCodewords = 108, ecCodewordsPerBlock = 26, numBlocks = 1, alignmentCenters = intArrayOf(6, 30)),
        VersionSpec(6, totalCodewords = 172, dataCodewords = 136, ecCodewordsPerBlock = 18, numBlocks = 2, alignmentCenters = intArrayOf(6, 34)),
    )

    fun buildWifiQrPayload(ssid: String, passphrase: String): String {
        fun escapeWifiField(raw: String): String = buildString(raw.length + 4) {
            for (ch in raw) {
                if (ch == '\\' || ch == ';' || ch == ',' || ch == ':' || ch == '"') {
                    append('\\')
                }
                append(ch)
            }
        }
        val cleanSsid = escapeWifiField(ssid.trim())
        val cleanPass = escapeWifiField(passphrase.trim())
        return if (cleanPass.isEmpty()) {
            "WIFI:T:nopass;S:$cleanSsid;;"
        } else {
            "WIFI:T:WPA;S:$cleanSsid;P:$cleanPass;;"
        }
    }

    fun renderBitmap(
        content: String,
        targetSizePx: Int = 360,
        quietZoneModules: Int = 3,
        darkColor: Int = Color.BLACK,
        lightColor: Int = Color.WHITE,
    ): Bitmap? {
        val matrix = encodeMatrix(content) ?: return null
        val qrSize = matrix.size
        val totalModules = qrSize + quietZoneModules * 2
        val scale = (targetSizePx / totalModules).coerceAtLeast(4)
        val bitmapSize = totalModules * scale
        val pixels = IntArray(bitmapSize * bitmapSize) { lightColor }

        for (y in 0 until qrSize) {
            val row = matrix[y]
            val startPy = (y + quietZoneModules) * scale
            for (x in 0 until qrSize) {
                if (!row[x]) continue
                val startPx = (x + quietZoneModules) * scale
                for (dy in 0 until scale) {
                    val rowOffset = (startPy + dy) * bitmapSize + startPx
                    for (dx in 0 until scale) {
                        pixels[rowOffset + dx] = darkColor
                    }
                }
            }
        }
        return Bitmap.createBitmap(pixels, bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888)
    }

    internal fun encodeMatrix(content: String): Array<BooleanArray>? {
        val payload = content.toByteArray(Charsets.UTF_8)
        val spec = VERSION_SPECS.firstOrNull { payload.size <= it.byteCapacity } ?: return null
        val dataCodewords = encodeDataCodewords(payload, spec.dataCodewords)
        val allCodewords = appendErrorCorrection(dataCodewords, spec)

        val size = spec.size
        val modules = Array(size) { BooleanArray(size) }
        val isFunction = Array(size) { BooleanArray(size) }

        fun setFunctionModule(x: Int, y: Int, isDark: Boolean) {
            if (x in 0 until size && y in 0 until size) {
                modules[y][x] = isDark
                isFunction[y][x] = true
            }
        }

        fun drawFinderPattern(centerX: Int, centerY: Int) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    val xx = centerX + dx
                    val yy = centerY + dy
                    if (xx in 0 until size && yy in 0 until size) {
                        setFunctionModule(xx, yy, dist != 2 && dist != 4)
                    }
                }
            }
        }

        fun drawAlignmentPattern(centerX: Int, centerY: Int) {
            for (dy in -2..2) {
                for (dx in -2..2) {
                    val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    setFunctionModule(centerX + dx, centerY + dy, dist != 1)
                }
            }
        }

        // 1. Timing patterns
        for (i in 0 until size) {
            setFunctionModule(6, i, i % 2 == 0)
            setFunctionModule(i, 6, i % 2 == 0)
        }

        // 2. Finder patterns
        drawFinderPattern(3, 3)
        drawFinderPattern(size - 4, 3)
        drawFinderPattern(3, size - 4)

        // 3. Alignment patterns
        val align = spec.alignmentCenters
        for (i in align.indices) {
            for (j in align.indices) {
                if ((i == 0 && j == 0) || (i == 0 && j == align.lastIndex) || (i == align.lastIndex && j == 0)) {
                    continue
                }
                drawAlignmentPattern(align[i], align[j])
            }
        }

        // 4. Reserve format information areas (mask = 0 initially)
        fun drawFormatBits(mask: Int) {
            val data = (0b01 shl 3) or mask // ECC Level L = 01
            var rem = data
            for (i in 0 until 10) {
                rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
            }
            val bits = ((data shl 10) or rem) xor 0x5412
            fun bit(index: Int): Boolean = ((bits ushr index) and 1) != 0

            for (i in 0..5) setFunctionModule(8, i, bit(i))
            setFunctionModule(8, 7, bit(6))
            setFunctionModule(8, 8, bit(7))
            setFunctionModule(7, 8, bit(8))
            for (i in 9 until 15) setFunctionModule(14 - i, 8, bit(i))

            for (i in 0 until 8) setFunctionModule(size - 1 - i, 8, bit(i))
            for (i in 8 until 15) setFunctionModule(8, size - 15 + i, bit(i))
            setFunctionModule(8, size - 8, true)
        }

        drawFormatBits(mask = 0)

        // 5. Place data and EC codewords in zigzag order and apply Mask 0: (x + y) % 2 == 0
        var bitIndex = 0
        val totalBits = allCodewords.size * 8
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y][x]) {
                        val dataBit = if (bitIndex < totalBits) {
                            val byteVal = allCodewords[bitIndex ushr 3].toInt() and 0xFF
                            ((byteVal ushr (7 - (bitIndex and 7))) and 1) != 0
                        } else {
                            false
                        }
                        bitIndex++
                        val invert = (x + y) % 2 == 0
                        modules[y][x] = dataBit xor invert
                    }
                }
            }
            right -= 2
        }

        return modules
    }

    private fun encodeDataCodewords(payload: ByteArray, dataCapacityCodewords: Int): ByteArray {
        val totalBitsCapacity = dataCapacityCodewords * 8
        val out = ByteArray(dataCapacityCodewords)
        var bitPos = 0

        fun appendBits(value: Int, numBits: Int) {
            for (i in numBits - 1 downTo 0) {
                if (bitPos >= totalBitsCapacity) return
                val bit = (value ushr i) and 1
                if (bit != 0) {
                    out[bitPos ushr 3] = (out[bitPos ushr 3].toInt() or (1 shl (7 - (bitPos and 7)))).toByte()
                }
                bitPos++
            }
        }

        // Byte mode indicator (0100) + 8-bit character count (Versions 1..9)
        appendBits(0b0100, 4)
        appendBits(payload.size, 8)
        for (b in payload) {
            appendBits(b.toInt() and 0xFF, 8)
        }
        // Terminator (up to 4 zero bits)
        val terminatorBits = minOf(4, totalBitsCapacity - bitPos)
        appendBits(0, terminatorBits)
        // Pad to byte boundary
        while (bitPos and 7 != 0) {
            appendBits(0, 1)
        }
        // Pad bytes 0xEC, 0x11 alternately
        var padIndex = 0
        while (bitPos < totalBitsCapacity) {
            appendBits(if (padIndex % 2 == 0) 0xEC else 0x11, 8)
            padIndex++
        }
        return out
    }

    private fun appendErrorCorrection(data: ByteArray, spec: VersionSpec): ByteArray {
        if (spec.numBlocks == 1) {
            val ec = reedSolomonComputeRemainder(data, spec.ecCodewordsPerBlock)
            return data + ec
        }
        val blockSize = spec.dataCodewords / spec.numBlocks
        val dataBlocks = Array(spec.numBlocks) { i ->
            data.copyOfRange(i * blockSize, (i + 1) * blockSize)
        }
        val ecBlocks = Array(spec.numBlocks) { i ->
            reedSolomonComputeRemainder(dataBlocks[i], spec.ecCodewordsPerBlock)
        }
        val result = ByteArray(spec.totalCodewords)
        var pos = 0
        for (i in 0 until blockSize) {
            for (b in 0 until spec.numBlocks) {
                result[pos++] = dataBlocks[b][i]
            }
        }
        for (i in 0 until spec.ecCodewordsPerBlock) {
            for (b in 0 until spec.numBlocks) {
                result[pos++] = ecBlocks[b][i]
            }
        }
        return result
    }

    private fun reedSolomonComputeRemainder(data: ByteArray, degree: Int): ByteArray {
        val generator = reedSolomonGenerator(degree)
        val result = ByteArray(degree)
        for (b in data) {
            val factor = (b.toInt() xor result[0].toInt()) and 0xFF
            System.arraycopy(result, 1, result, 0, degree - 1)
            result[degree - 1] = 0
            for (i in 0 until degree) {
                result[i] = (result[i].toInt() xor gfMultiply(generator[i].toInt() and 0xFF, factor)).toByte()
            }
        }
        return result
    }

    private fun reedSolomonGenerator(degree: Int): ByteArray {
        val result = ByteArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                result[j] = gfMultiply(result[j].toInt() and 0xFF, root).toByte()
                if (j + 1 < degree) {
                    result[j] = (result[j].toInt() xor (result[j + 1].toInt() and 0xFF)).toByte()
                }
            }
            root = gfMultiply(root, 0x02)
        }
        return result
    }

    private fun gfMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z ushr 7) * 0x11D)
            z = z xor (((y ushr i) and 1) * x)
        }
        return z
    }
}
