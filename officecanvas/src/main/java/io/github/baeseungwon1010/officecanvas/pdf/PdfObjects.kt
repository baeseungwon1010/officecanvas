package io.github.baeseungwon1010.officecanvas.pdf

import java.nio.ByteBuffer

/** PDF's objects (ISO 32000-1, 7.3). */
internal sealed class PdfObj

internal object PdfNull : PdfObj()

internal class PdfBool(val value: Boolean) : PdfObj()

internal class PdfNum(val value: Double) : PdfObj() {
    val int: Int get() = if (value.isFinite()) value.toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt() else 0
}

/** A string's bytes; what they mean (text, a font's codes) depends on where it is used. */
internal class PdfString(val bytes: ByteArray) : PdfObj() {
    /** As a text string (7.9.2.2): UTF-16BE with a byte order mark, UTF-8 with one, else PDFDocEncoding (Latin-1 close enough). */
    fun text(): String = when {
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        else -> String(bytes, Charsets.ISO_8859_1)
    }
}

internal class PdfName(val name: String) : PdfObj() {
    override fun equals(other: Any?) = other is PdfName && other.name == name
    override fun hashCode() = name.hashCode()
    override fun toString() = "/$name"
}

internal class PdfArray(val items: List<PdfObj>) : PdfObj() {
    operator fun get(i: Int): PdfObj? = items.getOrNull(i)
    val size get() = items.size
}

internal class PdfDict(val map: Map<String, PdfObj>) : PdfObj() {
    operator fun get(key: String): PdfObj? = map[key]
    fun name(key: String): String? = (map[key] as? PdfName)?.name
    fun num(key: String): Double? = (map[key] as? PdfNum)?.value
    fun int(key: String): Int? = (map[key] as? PdfNum)?.int
    fun bool(key: String): Boolean? = (map[key] as? PdfBool)?.value
}

internal class PdfRef(val num: Int, val gen: Int) : PdfObj() {
    override fun equals(other: Any?) = other is PdfRef && other.num == num && other.gen == gen
    override fun hashCode() = num * 31 + gen
}

/** A stream: its dictionary, and where its (still encoded) data lies in the file. */
internal class PdfStream(val dict: PdfDict, val start: Int, val length: Int, val owner: PdfRef?) : PdfObj()

/** An operator in a content stream, or a keyword (obj, stream, R...) in the file. */
internal class PdfKeyword(val word: String) : PdfObj()

/**
 * Reads PDF tokens and objects from [buf] (a file or a decoded stream), tolerant of
 * what writers get wrong: missing whitespace, odd numbers ("--5", "1.2.3"),
 * unbalanced strings at the end of data. Nesting is capped.
 */
internal class PdfLexer(private val buf: ByteBuffer, var pos: Int = 0) {
    val size: Int = buf.limit()

    fun byteAt(i: Int): Int = if (i in 0 until size) buf.get(i).toInt() and 0xFF else -1

    fun peek(): Int = byteAt(pos)

    fun skipSpace() {
        while (pos < size) {
            val c = byteAt(pos)
            if (isSpace(c)) pos++
            else if (c == '%'.code) { while (pos < size && byteAt(pos) != 10 && byteAt(pos) != 13) pos++ }
            else break
        }
    }

    /**
     * The next object, or a keyword; null at the end of the data or at a closing
     * delimiter that doesn't belong here. With [refs], "n g R" reads as a reference.
     */
    fun next(refs: Boolean = false, depth: Int = 0): PdfObj? {
        skipSpace()
        if (pos >= size) return null
        val c = byteAt(pos)
        return when {
            c == '/'.code -> { pos++; PdfName(readName()) }
            c == '('.code -> { pos++; PdfString(readLiteral()) }
            c == '<'.code && byteAt(pos + 1) == '<'.code -> { pos += 2; readDict(refs, depth) }
            c == '<'.code -> { pos++; PdfString(readHex()) }
            c == '['.code -> { pos++; readArray(refs, depth) }
            c == ']'.code || c == '>'.code || c == ')'.code || c == '}'.code -> { pos++; null }
            c == '{'.code -> { pos++; next(refs, depth) }
            c == '+'.code || c == '-'.code || c == '.'.code || c in '0'.code..'9'.code -> {
                val n = readNumber()
                if (refs && n.value >= 0 && n.value == Math.floor(n.value) && n.value < Int.MAX_VALUE) {
                    val save = pos
                    skipSpace()
                    val p2 = pos
                    if (byteAt(pos) in '0'.code..'9'.code) {
                        val g = readNumber()
                        skipSpace()
                        if (byteAt(pos) == 'R'.code && isDelimiterOrSpace(byteAt(pos + 1))) {
                            pos++
                            return PdfRef(n.int, g.int)
                        }
                    }
                    if (p2 >= 0) pos = save
                }
                n
            }
            else -> {
                val word = readWord()
                when (word) {
                    "true" -> PdfBool(true)
                    "false" -> PdfBool(false)
                    "null" -> PdfNull
                    "" -> { pos++; next(refs, depth) }
                    else -> PdfKeyword(word)
                }
            }
        }
    }

    private fun readArray(refs: Boolean, depth: Int): PdfObj {
        val items = ArrayList<PdfObj>()
        while (true) {
            skipSpace()
            if (pos >= size) break
            if (byteAt(pos) == ']'.code) { pos++; break }
            if (depth > MAX_DEPTH) { skipNested(); break }
            val o = next(refs, depth + 1) ?: break
            if (o is PdfKeyword && (o.word == "endobj" || o.word == "stream")) { pos -= o.word.length; break }
            items += o
            if (items.size > MAX_ITEMS) { skipNested(); break }
        }
        return PdfArray(items)
    }

    private fun readDict(refs: Boolean, depth: Int): PdfObj {
        val map = LinkedHashMap<String, PdfObj>()
        while (true) {
            skipSpace()
            if (pos >= size) break
            if (byteAt(pos) == '>'.code) { pos++; if (byteAt(pos) == '>'.code) pos++; break }
            if (depth > MAX_DEPTH) { skipNested(); break }
            val key = next(refs, depth + 1) ?: break
            if (key is PdfKeyword && (key.word == "endobj" || key.word == "stream")) { pos -= key.word.length; break }
            if (key !is PdfName) continue
            skipSpace()
            if (byteAt(pos) == '>'.code && byteAt(pos + 1) == '>'.code) { pos += 2; break }
            val value = next(refs, depth + 1) ?: break
            if (value is PdfKeyword && (value.word == "endobj" || value.word == "stream")) { pos -= value.word.length; break }
            map[key.name] = value
            if (map.size > MAX_ITEMS) { skipNested(); break }
        }
        return PdfDict(map)
    }

    /** Skips to the end of the array or dictionary being read, past anything nested. */
    private fun skipNested() {
        var level = 1
        while (pos < size && level > 0) {
            when (byteAt(pos)) {
                '['.code -> level++
                ']'.code -> level--
                '<'.code -> if (byteAt(pos + 1) == '<'.code) { level++; pos++ }
                '>'.code -> if (byteAt(pos + 1) == '>'.code) { level--; pos++ }
                '('.code -> { pos++; readLiteral(); continue }
            }
            pos++
        }
    }

    private fun readName(): String {
        val sb = StringBuilder()
        while (pos < size) {
            val c = byteAt(pos)
            if (isDelimiterOrSpace(c)) break
            if (c == '#'.code && hex(byteAt(pos + 1)) >= 0 && hex(byteAt(pos + 2)) >= 0) {
                sb.append(((hex(byteAt(pos + 1)) shl 4) or hex(byteAt(pos + 2))).toChar())
                pos += 3
            } else { sb.append(c.toChar()); pos++ }
            if (sb.length > 4096) break
        }
        return sb.toString()
    }

    private fun readLiteral(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var level = 1
        while (pos < size) {
            var c = byteAt(pos++)
            when (c) {
                '('.code -> level++
                ')'.code -> { level--; if (level == 0) break }
                '\\'.code -> {
                    c = byteAt(pos++)
                    when (c) {
                        'n'.code -> c = 10; 'r'.code -> c = 13; 't'.code -> c = 9; 'b'.code -> c = 8; 'f'.code -> c = 12
                        13 -> { if (byteAt(pos) == 10) pos++; continue }
                        10 -> continue
                        in '0'.code..'7'.code -> {
                            var v = c - '0'.code
                            repeat(2) { if (byteAt(pos) in '0'.code..'7'.code) v = v * 8 + (byteAt(pos++) - '0'.code) }
                            c = v and 0xFF
                        }
                        -1 -> break
                    }
                }
                13 -> { if (byteAt(pos) == 10) pos++; c = 10 }
            }
            out.write(c)
            if (out.size() > MAX_STRING) break
        }
        return out.toByteArray()
    }

    private fun readHex(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var hi = -1
        while (pos < size) {
            val c = byteAt(pos++)
            if (c == '>'.code) break
            val v = hex(c)
            if (v < 0) continue
            if (hi < 0) hi = v else { out.write((hi shl 4) or v); hi = -1 }
            if (out.size() > MAX_STRING) break
        }
        if (hi >= 0) out.write(hi shl 4)
        return out.toByteArray()
    }

    private fun readNumber(): PdfNum {
        val start = pos
        var neg = false
        while (pos < size && (byteAt(pos) == '+'.code || byteAt(pos) == '-'.code)) { if (byteAt(pos) == '-'.code) neg = !neg; pos++ }
        var v = 0.0
        var digits = 0
        while (pos < size && byteAt(pos) in '0'.code..'9'.code) { v = v * 10 + (byteAt(pos) - '0'.code); pos++; digits++ }
        if (pos < size && byteAt(pos) == '.'.code) {
            pos++
            var scale = 0.1
            while (pos < size && byteAt(pos) in '0'.code..'9'.code) { v += (byteAt(pos) - '0'.code) * scale; scale /= 10; pos++; digits++ }
            // "1.2.3": the rest isn't part of the number.
            while (pos < size && (byteAt(pos) == '.'.code || byteAt(pos) in '0'.code..'9'.code)) pos++
        }
        if (digits == 0 && pos == start) pos++
        return PdfNum(if (neg) -v else v)
    }

    private fun readWord(): String {
        val start = pos
        while (pos < size && !isDelimiterOrSpace(byteAt(pos))) pos++
        if (pos - start > 256) return ""
        val b = ByteArray(pos - start)
        for (i in b.indices) b[i] = buf.get(start + i)
        return String(b, Charsets.ISO_8859_1)
    }

    /** Whether [word] starts at [at], followed by a delimiter. */
    fun matches(at: Int, word: String): Boolean {
        for (i in word.indices) if (byteAt(at + i) != word[i].code) return false
        return isDelimiterOrSpace(byteAt(at + word.length))
    }

    companion object {
        const val MAX_DEPTH = 64
        const val MAX_ITEMS = 1_000_000
        const val MAX_STRING = 16 shl 20

        fun isSpace(c: Int) = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32
        fun isDelimiterOrSpace(c: Int) = c < 0 || isSpace(c) || c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
            c == '['.code || c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code
        fun hex(c: Int) = when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> -1
        }
    }
}
