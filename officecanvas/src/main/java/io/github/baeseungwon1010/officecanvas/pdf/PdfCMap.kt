package io.github.baeseungwon1010.officecanvas.pdf

import java.nio.ByteBuffer

/**
 * A CMap (9.7.5, 9.10.3): how a font's string bytes split into codes (codespace
 * ranges), and what each code means: a CID (an encoding CMap's cidrange/cidchar)
 * or text (a ToUnicode CMap's bfrange/bfchar).
 */
internal class PdfCMap {
    /** Codespace ranges by byte length: (low, high) for each length. */
    private val spaces = ArrayList<Triple<Int, Long, Long>>()
    private val unicode = HashMap<Long, String>()
    private val unicodeRanges = ArrayList<Triple<Long, Long, String>>()
    private val cids = HashMap<Long, Int>()
    private val cidRanges = ArrayList<Triple<Long, Long, Int>>()

    val hasUnicode get() = unicode.isNotEmpty() || unicodeRanges.isNotEmpty()
    val hasCodespace get() = spaces.isNotEmpty()

    /** Splits [bytes] into (at most [limit]) codes with their byte lengths. Without codespace ranges, [defaultLength] bytes each. */
    fun codes(bytes: ByteArray, defaultLength: Int, limit: Int = Int.MAX_VALUE, out: (code: Long, length: Int) -> Unit) {
        var i = 0
        var n = 0
        while (i < bytes.size && n++ < limit) {
            var len = 0
            if (spaces.isNotEmpty()) {
                var v = 0L
                for (n in 1..4) {
                    if (i + n > bytes.size) break
                    v = (v shl 8) or (bytes[i + n - 1].toLong() and 0xFF)
                    if (spaces.any { it.first == n && v in it.second..it.third }) { len = n; break }
                }
                // No range matched: the shortest length in use, as viewers do.
                if (len == 0) len = spaces.minOf { it.first }
            } else len = defaultLength
            len = minOf(len, bytes.size - i)
            var code = 0L
            for (k in 0 until len) code = (code shl 8) or (bytes[i + k].toLong() and 0xFF)
            out(code, len)
            i += len
        }
    }

    /** Codes no range has, so a string of them doesn't look through every range again (capped). */
    private val noText = HashSet<Long>()
    private val noCid = HashSet<Long>()
    private var unicodeIndex: Ranges<String>? = null
    private var cidIndex: Ranges<Int>? = null

    fun text(code: Long): String? {
        unicode[code]?.let { return it }
        if (code in noText) return null
        val index = unicodeIndex?.takeIf { it.size == unicodeRanges.size } ?: Ranges(unicodeRanges).also { unicodeIndex = it }
        val hit = index.find(code)
        if (hit == null) { if (noText.size < MAX_MISSES) noText += code; return null }
        return offset(hit.third, (code - hit.first).toInt()).also { if (unicode.size < MAX_ENTRIES * 2) unicode[code] = it }
    }

    fun cid(code: Long): Int? {
        cids[code]?.let { return it }
        if (code in noCid) return null
        val index = cidIndex?.takeIf { it.size == cidRanges.size } ?: Ranges(cidRanges).also { cidIndex = it }
        val hit = index.find(code)
        if (hit == null) { if (noCid.size < MAX_MISSES) noCid += code; return null }
        return hit.third + (code - hit.first).toInt()
    }

    /**
     * Ranges sorted by their low code, for a binary search. Where ranges overlap (they
     * shouldn't), the first one defined among the few nearest wins, as in a straight scan.
     */
    private class Ranges<T>(list: List<Triple<Long, Long, T>>) {
        val size = list.size
        private val sorted = list.withIndex().sortedBy { it.value.first }
        private val lows = LongArray(sorted.size) { sorted[it].value.first }

        fun find(code: Long): Triple<Long, Long, T>? {
            // The last range starting at or below the code, and a few before it.
            var lo = 0; var hi = lows.size - 1; var at = -1
            while (lo <= hi) { val mid = (lo + hi) ushr 1; if (lows[mid] <= code) { at = mid; lo = mid + 1 } else hi = mid - 1 }
            var best: IndexedValue<Triple<Long, Long, T>>? = null
            var k = at
            while (k >= 0 && k > at - 16) {
                val r = sorted[k]
                if (code <= r.value.second && (best == null || r.index < best.index)) best = r
                k--
            }
            return best?.value
        }
    }

    /** [start] with its last character advanced by [k] (a bfrange's later codes). */
    private fun offset(start: String, k: Int): String {
        if (k == 0 || start.isEmpty()) return start
        val last = start.codePointBefore(start.length)
        val head = start.substring(0, start.length - Character.charCount(last))
        val cp = last + k
        return if (cp in 0..0x10FFFF) head + String(Character.toChars(cp)) else start
    }

    companion object {
        private const val MAX_ENTRIES = 200_000
        /** Codespace ranges a CMap may have (real ones have a handful). */
        private const val MAX_SPACES = 256
        private const val MAX_MISSES = 65_536

        fun parse(data: ByteArray, into: PdfCMap = PdfCMap(), resolveUse: ((String) -> PdfCMap?)? = null): PdfCMap {
            val lex = PdfLexer(ByteBuffer.wrap(data))
            val stack = ArrayList<PdfObj>()
            var count = 0
            fun hexBytes(o: PdfObj?): ByteArray? = (o as? PdfString)?.bytes
            fun code(b: ByteArray): Long { var v = 0L; for (x in b.take(4)) v = (v shl 8) or (x.toLong() and 0xFF); return v }
            fun utf16(b: ByteArray): String = if (b.size == 1) (b[0].toInt() and 0xFF).toChar().toString() else String(b, Charsets.UTF_16BE)
            while (count < MAX_ENTRIES) {
                val o = lex.next() ?: if (lex.pos >= lex.size) break else continue
                if (o !is PdfKeyword) { stack += o; if (stack.size > 1000) stack.removeAt(0); continue }
                when (o.word) {
                    "usecmap" -> {
                        (stack.lastOrNull() as? PdfName)?.name?.let { name -> resolveUse?.invoke(name)?.let { into.inherit(it) } }
                        stack.clear()
                    }
                    "begincodespacerange", "beginbfchar", "beginbfrange", "begincidchar", "begincidrange", "beginnotdefrange" -> stack.clear()
                    "endcodespacerange" -> {
                        var k = 0
                        while (k + 1 < stack.size) {
                            val lo = hexBytes(stack[k]); val hi = hexBytes(stack[k + 1])
                            if (lo != null && hi != null && lo.size in 1..4 && into.spaces.size < MAX_SPACES) into.spaces += Triple(lo.size, code(lo), code(hi))
                            k += 2; count++
                        }
                        stack.clear()
                    }
                    "endbfchar" -> {
                        var k = 0
                        while (k + 1 < stack.size) {
                            val src = hexBytes(stack[k])
                            val dst = stack[k + 1]
                            if (src != null) when (dst) {
                                is PdfString -> into.unicode[code(src)] = utf16(dst.bytes)
                                is PdfName -> PdfEncodings.glyph(dst.name)?.let { into.unicode[code(src)] = it }
                                else -> {}
                            }
                            k += 2; count++
                        }
                        stack.clear()
                    }
                    "endbfrange" -> {
                        var k = 0
                        while (k + 2 < stack.size) {
                            val lo = hexBytes(stack[k]); val hi = hexBytes(stack[k + 1]); val dst = stack[k + 2]
                            if (lo != null && hi != null) {
                                val l = code(lo); val h = code(hi)
                                when {
                                    h < l || h - l > 65535 -> {}
                                    dst is PdfString -> into.unicodeRanges += Triple(l, h, utf16(dst.bytes))
                                    dst is PdfArray -> dst.items.forEachIndexed { j, d ->
                                        if (l + j <= h) (d as? PdfString)?.let { into.unicode[l + j] = utf16(it.bytes) }
                                    }
                                }
                            }
                            k += 3; count++
                        }
                        stack.clear()
                    }
                    "endcidchar" -> {
                        var k = 0
                        while (k + 1 < stack.size) {
                            val src = hexBytes(stack[k]); val cid = (stack[k + 1] as? PdfNum)?.int
                            if (src != null && cid != null) into.cids[code(src)] = cid
                            k += 2; count++
                        }
                        stack.clear()
                    }
                    "endcidrange" -> {
                        var k = 0
                        while (k + 2 < stack.size) {
                            val lo = hexBytes(stack[k]); val hi = hexBytes(stack[k + 1]); val cid = (stack[k + 2] as? PdfNum)?.int
                            if (lo != null && hi != null && cid != null) into.cidRanges += Triple(code(lo), code(hi), cid)
                            k += 3; count++
                        }
                        stack.clear()
                    }
                    else -> stack.clear()
                }
            }
            return into
        }

        /** Identity-H/V: two-byte codes that are their own CIDs. */
        fun identity() = PdfCMap().apply { spaces += Triple(2, 0L, 0xFFFFL); cidRanges += Triple(0L, 0xFFFFL, 0) }

        /** The codespace of a predefined CJK CMap by its name (its CIDs aren't known here). */
        fun predefined(name: String): PdfCMap? {
            val m = PdfCMap()
            when {
                name.startsWith("Identity") -> return identity()
                name.contains("UCS2") || name.contains("UTF16") -> { m.spaces += Triple(2, 0L, 0xD7FFL); m.spaces += Triple(4, 0xD800DC00L, 0xDBFFDFFFL); m.spaces += Triple(2, 0xE000L, 0xFFFFL) }
                name.contains("UTF8") -> { m.spaces += Triple(1, 0L, 0x7FL); m.spaces += Triple(2, 0xC080L, 0xDFBFL); m.spaces += Triple(3, 0xE08080L, 0xEFBFBFL); m.spaces += Triple(4, 0xF0808080L, 0xF7BFBFBFL) }
                name.contains("UHC") || name.contains("GBK") || name.contains("RKSJ") || name.contains("B5") || name.contains("EUC") || name.contains("ETen") || name.contains("HKscs") -> {
                    m.spaces += Triple(1, 0L, 0x80L); m.spaces += Triple(2, 0x8140L, 0xFEFEL); m.spaces += Triple(1, 0xA0L, 0xDFL)
                }
                name == "KSC-H" || name == "KSC-V" -> { m.spaces += Triple(1, 0L, 0x80L); m.spaces += Triple(2, 0x2121L, 0x7E7EL) }
                else -> return null
            }
            return m
        }
    }

    private fun inherit(o: PdfCMap) {
        spaces += o.spaces.take((MAX_SPACES - spaces.size).coerceAtLeast(0)); unicodeRanges += o.unicodeRanges; cidRanges += o.cidRanges
        for ((k, v) in o.unicode) unicode.putIfAbsent(k, v)
        for ((k, v) in o.cids) cids.putIfAbsent(k, v)
    }
}
