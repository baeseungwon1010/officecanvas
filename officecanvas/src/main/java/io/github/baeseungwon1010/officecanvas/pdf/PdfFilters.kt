package io.github.baeseungwon1010.officecanvas.pdf

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Stream filters (7.4). Decoding stops at the first image codec (DCT, JPX, CCITT,
 * JBIG2), whose data is handed on as it is. Output is capped, and data cut short or
 * damaged decodes as far as it goes, as viewers do.
 */
internal object PdfFilters {

    /** Decoded bytes, and the image codec still to apply, if any. */
    class Decoded(val bytes: ByteArray, val imageCodec: String?, val codecParms: PdfDict?)

    const val MAX_OUTPUT = 64 shl 20

    private val IMAGE_CODECS = setOf("DCTDecode", "DCT", "JPXDecode", "CCITTFaxDecode", "CCF", "JBIG2Decode")

    fun decode(data: ByteArray, filters: List<String>, parms: List<PdfDict?>, limit: Int = MAX_OUTPUT): Decoded {
        var bytes = data
        for ((i, f) in filters.withIndex()) {
            if (f in IMAGE_CODECS) return Decoded(bytes, normalize(f), parms.getOrNull(i))
            val p = parms.getOrNull(i)
            bytes = when (f) {
                "FlateDecode", "Fl" -> predict(inflate(bytes, limit), p)
                "LZWDecode", "LZW" -> predict(lzw(bytes, (p?.int("EarlyChange") ?: 1) != 0, limit), p)
                "ASCIIHexDecode", "AHx" -> asciiHex(bytes)
                "ASCII85Decode", "A85" -> ascii85(bytes)
                "RunLengthDecode", "RL" -> runLength(bytes, limit)
                "Crypt" -> bytes
                else -> bytes // unknown: hand on what we have
            }
        }
        return Decoded(bytes, null, null)
    }

    private fun normalize(f: String) = when (f) { "DCT" -> "DCTDecode"; "CCF" -> "CCITTFaxDecode"; else -> f }

    fun inflate(data: ByteArray, limit: Int = MAX_OUTPUT): ByteArray {
        fun run(nowrap: Boolean): ByteArray? {
            val inf = Inflater(nowrap)
            val out = ByteArrayOutputStream(minOf(maxOf(data.size * 4, 1024), 64 shl 20))
            try {
                inf.setInput(data)
                val buf = ByteArray(64 * 1024)
                while (!inf.finished()) {
                    val n = try { inf.inflate(buf) } catch (e: java.util.zip.DataFormatException) { if (out.size() > 0) break else return null }
                    if (n == 0) {
                        if (inf.needsInput() || inf.needsDictionary()) break
                    }
                    out.write(buf, 0, n)
                    if (out.size() > limit) break
                }
                return out.toByteArray()
            } finally {
                inf.end()
            }
        }
        // Some writers leave out the zlib header.
        return run(false)?.takeIf { it.isNotEmpty() || data.isEmpty() } ?: run(true) ?: ByteArray(0)
    }

    /** PNG (10..15) and TIFF (2) predictors (7.4.4.4). */
    fun predict(data: ByteArray, p: PdfDict?): ByteArray {
        val predictor = p?.int("Predictor") ?: 1
        if (predictor < 2) return data
        val colors = (p?.int("Colors") ?: 1).coerceIn(1, 32)
        val bpc = (p?.int("BitsPerComponent") ?: 8).coerceIn(1, 16)
        val columns = (p?.int("Columns") ?: 1).coerceIn(1, 1 shl 20)
        val bpp = maxOf(1, (colors * bpc + 7) / 8)
        val rowLen = (columns.toLong() * colors * bpc + 7).div(8).toInt()
        if (predictor == 2) {
            if (bpc != 8) return data
            val out = data.copyOf()
            var row = 0
            while (row + rowLen <= out.size) {
                for (i in bpp until rowLen) out[row + i] = (out[row + i] + out[row + i - bpp]).toByte()
                row += rowLen
            }
            return out
        }
        val out = ByteArrayOutputStream(data.size)
        var prev = ByteArray(rowLen)
        var i = 0
        while (i < data.size) {
            val type = data[i].toInt() and 0xFF
            i++
            val cur = ByteArray(rowLen)
            val n = minOf(rowLen, data.size - i)
            System.arraycopy(data, i, cur, 0, n)
            i += rowLen
            for (k in 0 until rowLen) {
                val a = if (k >= bpp) cur[k - bpp].toInt() and 0xFF else 0
                val b = prev[k].toInt() and 0xFF
                val c = if (k >= bpp) prev[k - bpp].toInt() and 0xFF else 0
                val x = cur[k].toInt() and 0xFF
                cur[k] = when (type) {
                    1 -> x + a
                    2 -> x + b
                    3 -> x + (a + b) / 2
                    4 -> {
                        val pp = a + b - c
                        val pa = Math.abs(pp - a); val pb = Math.abs(pp - b); val pc = Math.abs(pp - c)
                        x + if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
                    }
                    else -> x
                }.toByte()
            }
            out.write(cur, 0, n)
            prev = cur
        }
        return out.toByteArray()
    }

    private fun lzw(data: ByteArray, earlyChange: Boolean, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(data.size * 2)
        val table = ArrayList<ByteArray>(4096)
        fun reset() { table.clear(); for (i in 0 until 256) table += byteArrayOf(i.toByte()); table += ByteArray(0); table += ByteArray(0) }
        reset()
        var bits = 9
        var bitBuf = 0L
        var bitCount = 0
        var prev: ByteArray? = null
        var i = 0
        while (true) {
            while (bitCount < bits && i < data.size) { bitBuf = (bitBuf shl 8) or (data[i++].toLong() and 0xFF); bitCount += 8 }
            if (bitCount < bits) break
            val code = ((bitBuf shr (bitCount - bits)) and ((1L shl bits) - 1)).toInt()
            bitCount -= bits
            if (code == 256) { reset(); bits = 9; prev = null; continue }
            if (code == 257) break
            val entry = when {
                code < table.size -> table[code]
                prev != null -> prev + prev[0]
                else -> break
            }
            out.write(entry)
            if (out.size() > limit) break
            if (prev != null && table.size < 4096) table += prev + entry[0]
            prev = entry
            val next = table.size + if (earlyChange) 1 else 0
            bits = when { next >= 2048 -> 12; next >= 1024 -> 11; next >= 512 -> 10; else -> 9 }
        }
        return out.toByteArray()
    }

    private fun asciiHex(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 2)
        var hi = -1
        for (b in data) {
            val c = b.toInt() and 0xFF
            if (c == '>'.code) break
            val v = PdfLexer.hex(c)
            if (v < 0) continue
            if (hi < 0) hi = v else { out.write((hi shl 4) or v); hi = -1 }
        }
        if (hi >= 0) out.write(hi shl 4)
        return out.toByteArray()
    }

    private fun ascii85(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        var i = 0
        // A leading "<~" is allowed.
        if (data.size >= 2 && data[0] == '<'.code.toByte() && data[1] == '~'.code.toByte()) i = 2
        val group = IntArray(5)
        var n = 0
        while (i < data.size) {
            val c = data[i++].toInt() and 0xFF
            if (c == '~'.code) break
            if (PdfLexer.isSpace(c)) continue
            if (c == 'z'.code && n == 0) { repeat(4) { out.write(0) }; continue }
            if (c < '!'.code || c > 'u'.code) continue
            group[n++] = c - '!'.code
            if (n == 5) {
                var v = 0L
                for (k in 0 until 5) v = v * 85 + group[k]
                for (k in 3 downTo 0) out.write(((v shr (8 * k)) and 0xFF).toInt())
                n = 0
            }
        }
        if (n > 1) {
            for (k in n until 5) group[k] = 84
            var v = 0L
            for (k in 0 until 5) v = v * 85 + group[k]
            for (k in 3 downTo 4 - (n - 1) + 0) out.write(((v shr (8 * k)) and 0xFF).toInt())
        }
        return out.toByteArray()
    }

    private fun runLength(data: ByteArray, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(data.size * 2)
        var i = 0
        while (i < data.size && out.size() <= limit) {
            val len = data[i++].toInt() and 0xFF
            when {
                len == 128 -> break
                len < 128 -> { val n = minOf(len + 1, data.size - i); out.write(data, i, n); i += n }
                else -> { if (i >= data.size) break; val b = data[i++].toInt(); repeat(257 - len) { out.write(b) } }
            }
        }
        return out.toByteArray()
    }
}
