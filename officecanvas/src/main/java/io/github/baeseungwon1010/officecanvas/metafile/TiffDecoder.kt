package io.github.baeseungwon1010.officecanvas.metafile

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Baseline TIFF → 24-bit BMP, so BitmapFactory (which has no TIFF decoder) can
 * show TIFF pictures embedded in old decks. Covers the first image of the file:
 * uncompressed, LZW (with or without the horizontal predictor) and PackBits
 * strips; 8-bit gray/RGB(A)/palette and 1-bit bilevel. Fax (CCITT), JPEG-in-TIFF,
 * tiles and planar layouts return null.
 */
internal object TiffDecoder {

    fun toBmp(tiff: ByteArray): ByteArray? = try {
        decode(tiff)
    } catch (e: RuntimeException) {
        null
    }

    private fun decode(tiff: ByteArray): ByteArray? {
        if (tiff.size < 8) return null
        val order = when {
            tiff[0] == 'I'.code.toByte() && tiff[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            tiff[0] == 'M'.code.toByte() && tiff[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> return null
        }
        val b = ByteBuffer.wrap(tiff).order(order)
        if (b.getShort(2).toInt() != 42) return null
        val ifd = b.getInt(4)
        val n = b.getShort(ifd).toInt() and 0xFFFF
        val tags = HashMap<Int, IntArray>()
        for (i in 0 until n) {
            val e = ifd + 2 + i * 12
            val tag = b.getShort(e).toInt() and 0xFFFF
            val type = b.getShort(e + 2).toInt() and 0xFFFF
            val count = b.getInt(e + 4)
            val size = when (type) { 3 -> 2; 4 -> 4; 1, 7 -> 1; else -> 0 }
            if (size == 0 || count <= 0) continue
            val at = if (size * count <= 4) e + 8 else b.getInt(e + 8)
            tags[tag] = IntArray(count) { k ->
                when (size) {
                    1 -> b.get(at + k).toInt() and 0xFF
                    2 -> b.getShort(at + 2 * k).toInt() and 0xFFFF
                    else -> b.getInt(at + 4 * k)
                }
            }
        }
        fun one(tag: Int, default: Int) = tags[tag]?.firstOrNull() ?: default
        val width = one(256, 0)
        val height = one(257, 0)
        if (width <= 0 || height <= 0 || width.toLong() * height > 40_000_000L) return null
        val bits = one(258, 1)
        val compression = one(259, 1)
        val photometric = one(262, 1)
        val spp = one(277, 1)
        val rowsPerStrip = one(278, height).coerceAtLeast(1)
        val predictor = one(317, 1)
        if (one(284, 1) != 1 || compression !in listOf(1, 5, 32773) || (bits != 8 && bits != 1)) return null
        val offsets = tags[273] ?: return null
        val counts = tags[279] ?: return null

        val rowBytes = (width * spp * bits + 7) / 8
        val pixels = ByteArray(rowBytes * height)
        var row = 0
        for (s in offsets.indices) {
            if (row >= height) break
            val rows = minOf(rowsPerStrip, height - row)
            val raw = tiff.copyOfRange(offsets[s], minOf(tiff.size, offsets[s] + counts.getOrElse(s) { 0 }))
            val strip = when (compression) {
                5 -> lzw(raw, rows * rowBytes)
                32773 -> packBits(raw, rows * rowBytes)
                else -> raw
            }
            System.arraycopy(strip, 0, pixels, row * rowBytes, minOf(strip.size, rows * rowBytes))
            row += rows
        }
        if (predictor == 2 && bits == 8) {
            for (y in 0 until height) for (x in spp until width * spp) {
                val i = y * rowBytes + x
                pixels[i] = (pixels[i] + pixels[i - spp]).toByte()
            }
        }

        val palette = tags[320]
        fun rgb(x: Int, y: Int): Int {
            val o = y * rowBytes
            if (bits == 1) {
                val bit = (pixels[o + x / 8].toInt() ushr (7 - x % 8)) and 1
                val white = if (photometric == 0) bit == 0 else bit == 1
                return if (white) 0xFFFFFF else 0
            }
            val p = o + x * spp
            fun c(i: Int) = pixels[p + i].toInt() and 0xFF
            return when {
                photometric == 3 && palette != null -> {
                    val idx = c(0); val m = palette.size / 3
                    ((palette[idx] ushr 8) shl 16) or ((palette[m + idx] ushr 8) shl 8) or (palette[2 * m + idx] ushr 8)
                }
                photometric == 2 && spp >= 3 -> (c(0) shl 16) or (c(1) shl 8) or c(2)
                photometric == 0 -> (255 - c(0)).let { (it shl 16) or (it shl 8) or it }
                else -> c(0).let { (it shl 16) or (it shl 8) or it }
            }
        }
        return bmp24(width, height, ::rgb)
    }

    private fun bmp24(w: Int, h: Int, rgb: (Int, Int) -> Int): ByteArray {
        val stride = (w * 3 + 3) and 3.inv()
        val out = ByteBuffer.allocate(54 + stride * h).order(ByteOrder.LITTLE_ENDIAN)
        out.put('B'.code.toByte()).put('M'.code.toByte()).putInt(54 + stride * h).putInt(0).putInt(54)
        out.putInt(40).putInt(w).putInt(h).putShort(1).putShort(24).putInt(0).putInt(stride * h)
            .putInt(2835).putInt(2835).putInt(0).putInt(0)
        for (y in h - 1 downTo 0) {
            for (x in 0 until w) {
                val c = rgb(x, y)
                out.put((c and 0xFF).toByte()).put(((c ushr 8) and 0xFF).toByte()).put(((c ushr 16) and 0xFF).toByte())
            }
            repeat(stride - w * 3) { out.put(0) }
        }
        return out.array()
    }

    private fun packBits(src: ByteArray, expected: Int): ByteArray {
        val out = ByteArrayOutputStream(expected)
        var i = 0
        while (i < src.size && out.size() < expected) {
            val n = src[i++].toInt()
            when {
                n >= 0 -> { val len = minOf(n + 1, src.size - i); out.write(src, i, len); i += len }
                n != -128 -> { if (i < src.size) { val v = src[i++].toInt(); repeat(1 - n) { out.write(v) } } }
            }
        }
        return out.toByteArray()
    }

    /** TIFF LZW: MSB-first codes, 9..12 bits, 256 = clear, 257 = end, with the
     *  TIFF "early change" (width grows one code before the table fills). */
    private fun lzw(src: ByteArray, expected: Int): ByteArray {
        val out = ByteArrayOutputStream(expected)
        val table = arrayOfNulls<ByteArray>(4096)
        for (k in 0 until 256) table[k] = byteArrayOf(k.toByte())
        var next = 258
        var width = 9
        var bitPos = 0L
        var prev: ByteArray? = null
        fun read(): Int {
            var code = 0
            for (k in 0 until width) {
                val byteIdx = (bitPos ushr 3).toInt()
                if (byteIdx >= src.size) return 257
                val bit = (src[byteIdx].toInt() ushr (7 - (bitPos and 7).toInt())) and 1
                code = (code shl 1) or bit
                bitPos++
            }
            return code
        }
        while (out.size() < expected) {
            val code = read()
            if (code == 257) break
            if (code == 256) {
                next = 258; width = 9; prev = null
                continue
            }
            val entry = when {
                code < next && table[code] != null -> table[code]!!
                code == next && prev != null -> prev + prev[0]
                else -> break
            }
            out.write(entry)
            if (prev != null && next < 4096) table[next++] = prev + entry[0]
            prev = entry
            width = when { next + 1 >= 2048 -> 12; next + 1 >= 1024 -> 11; next + 1 >= 512 -> 10; else -> 9 }
        }
        return out.toByteArray()
    }
}
