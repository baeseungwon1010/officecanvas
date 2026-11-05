package io.github.baeseungwon1010.officecanvas.pdf

import io.github.baeseungwon1010.officecanvas.util.Limits
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * A PDF file: its cross-reference table (or streams, with object streams), the
 * trailer, decryption, and its pages with inherited attributes. The file is mapped,
 * not read into memory. A damaged cross-reference table is rebuilt by scanning the
 * file for "n g obj", as viewers do.
 */
internal class PdfDocument private constructor(private val file: RandomAccessFile?, val buf: ByteBuffer) : Closeable {

    class ParseException(message: String) : Exception(message)

    private sealed class Entry {
        class At(val offset: Int, val gen: Int) : Entry()
        class InStream(val stream: Int, val index: Int) : Entry()
    }

    private val entries = HashMap<Int, Entry>()
    var trailer = PdfDict(emptyMap())
        private set
    private var crypt: PdfCrypt? = null
    private var encryptRef: PdfRef? = null
    private val cache = object : LinkedHashMap<Int, PdfObj>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PdfObj>?) = size > 4000
    }
    private val objectStreams = object : LinkedHashMap<Int, Map<Int, PdfObj>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Map<Int, PdfObj>>?) = size > 16
    }
    private var rebuilt = false

    override fun close() { file?.close() }

    companion object {
        fun open(f: File): PdfDocument {
            if (f.length() > Int.MAX_VALUE) throw ParseException("the PDF is too large")
            val raf = RandomAccessFile(f, "r")
            try {
                val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                return PdfDocument(raf, buf).also { it.load() }
            } catch (e: Throwable) {
                raf.close()
                throw e
            }
        }

        fun open(bytes: ByteArray): PdfDocument = PdfDocument(null, ByteBuffer.wrap(bytes)).also { it.load() }
    }

    private fun load() {
        val lex = PdfLexer(buf)
        if (!findHeader(lex)) throw ParseException("not a PDF")
        try { readXrefChain(lex) } catch (e: Exception) { entries.clear() }
        if (entries.isEmpty() || resolve(trailer["Root"]) !is PdfDict) rebuild()
        if (resolve(trailer["Root"]) !is PdfDict) throw ParseException("the PDF has no document catalog")
        (trailer["Encrypt"])?.let { enc ->
            encryptRef = enc as? PdfRef
            val dict = resolve(enc) as? PdfDict ?: return@let
            val id = ((resolve(trailer["ID"]) as? PdfArray)?.get(0) as? PdfString)?.bytes ?: ByteArray(0)
            crypt = PdfCrypt.open(dict, id) { resolve(it) }
            cache.clear()
        }
    }

    private fun findHeader(lex: PdfLexer): Boolean {
        val limit = minOf(lex.size, 1024)
        for (i in 0 until limit - 4) if (lex.byteAt(i) == '%'.code && lex.byteAt(i + 1) == 'P'.code && lex.byteAt(i + 2) == 'D'.code && lex.byteAt(i + 3) == 'F'.code) return true
        return false
    }

    // ---- cross-reference --------------------------------------------------------

    private fun readXrefChain(lex: PdfLexer) {
        var offset = startXref(lex) ?: throw ParseException("no startxref")
        val seen = HashSet<Int>()
        var first = true
        while (offset in 0 until lex.size && seen.add(offset) && seen.size < 1000) {
            val t = readXrefSection(lex, offset) ?: break
            if (first) { trailer = t; first = false }
            else trailer = PdfDict(t.map + trailer.map) // the newest trailer's keys win
            // A hybrid file's xref stream holds what its table leaves out.
            (t["XRefStm"] as? PdfNum)?.int?.let { if (seen.add(it)) readXrefSection(lex, it) }
            offset = (t["Prev"] as? PdfNum)?.int ?: break
        }
    }

    private fun startXref(lex: PdfLexer): Int? {
        val from = maxOf(0, lex.size - 4096)
        for (i in lex.size - 9 downTo from) {
            if (lex.matches(i, "startxref")) {
                lex.pos = i + 9
                return (lex.next() as? PdfNum)?.int
            }
        }
        return null
    }

    /** One xref table or stream at [offset]; returns its trailer dictionary. */
    private fun readXrefSection(lex: PdfLexer, offset: Int): PdfDict? {
        lex.pos = offset
        lex.skipSpace()
        if (lex.matches(lex.pos, "xref")) {
            lex.pos += 4
            while (true) {
                lex.skipSpace()
                if (lex.matches(lex.pos, "trailer")) { lex.pos += 7; return lex.next(refs = true) as? PdfDict }
                val start = (lex.next() as? PdfNum)?.int ?: return null
                val count = (lex.next() as? PdfNum)?.int ?: return null
                if (count < 0 || count > 10_000_000) return null
                for (k in 0 until count) {
                    val off = (lex.next() as? PdfNum) ?: return null
                    val gen = (lex.next() as? PdfNum) ?: return null
                    val kind = (lex.next() as? PdfKeyword)?.word ?: return null
                    val num = start + k
                    if (kind == "n" && num !in entries && off.int > 0) entries[num] = Entry.At(off.int, gen.int)
                    else if (kind == "f" && num !in entries) entries[num] = Entry.At(-1, 0)
                }
            }
        }
        // An xref stream: "n g obj << /Type /XRef ... >> stream".
        val obj = readIndirectAt(offset, null) as? PdfStream ?: return null
        if (obj.dict.name("Type") != "XRef") return null
        val data = streamBytes(obj, decrypt = false) ?: return null
        val w = (obj.dict["W"] as? PdfArray)?.items?.map { (it as? PdfNum)?.int ?: 0 } ?: return null
        if (w.size < 3 || w.any { it !in 0..8 }) return null
        val index = (obj.dict["Index"] as? PdfArray)?.items?.map { (it as? PdfNum)?.int ?: 0 }
            ?: listOf(0, obj.dict.int("Size") ?: 0)
        val rowLen = w.sum()
        var p = 0
        var k = 0
        while (k + 1 < index.size) {
            val start = index[k]; val count = index[k + 1]
            for (j in 0 until count) {
                if (p + rowLen > data.size) break
                fun field(f: Int, width: Int): Long { var v = 0L; for (b in 0 until width) v = (v shl 8) or (data[f + b].toLong() and 0xFF); return v }
                val type = if (w[0] == 0) 1L else field(p, w[0])
                val f2 = field(p + w[0], w[1])
                val f3 = field(p + w[0] + w[1], w[2])
                p += rowLen
                val num = start + j
                if (num in entries) continue
                when (type) {
                    0L -> entries[num] = Entry.At(-1, 0)
                    1L -> if (f2 in 1 until Int.MAX_VALUE) entries[num] = Entry.At(f2.toInt(), f3.toInt())
                    2L -> entries[num] = Entry.InStream(f2.toInt(), f3.toInt())
                }
            }
            k += 2
        }
        return obj.dict
    }

    /** Scans the whole file for objects when the cross-reference is unusable. */
    private fun rebuild() {
        if (rebuilt) return
        rebuilt = true
        entries.clear(); cache.clear(); objectStreams.clear()
        val lex = PdfLexer(buf)
        var root: PdfObj? = null
        var trailerDict: PdfDict? = null
        var i = 0
        val n = lex.size
        while (i < n - 4) {
            // "obj" preceded by "num gen ".
            if (lex.byteAt(i) == 'o'.code && lex.byteAt(i + 1) == 'b'.code && lex.byteAt(i + 2) == 'j'.code &&
                PdfLexer.isDelimiterOrSpace(lex.byteAt(i + 3)) && i > 0 && PdfLexer.isSpace(lex.byteAt(i - 1))) {
                var j = i - 1
                while (j > 0 && PdfLexer.isSpace(lex.byteAt(j))) j--
                var genEnd = j
                while (j >= 0 && lex.byteAt(j) in '0'.code..'9'.code) j--
                val genStart = j + 1
                while (j > 0 && PdfLexer.isSpace(lex.byteAt(j))) j--
                val numEnd = j
                while (j >= 0 && lex.byteAt(j) in '0'.code..'9'.code) j--
                val numStart = j + 1
                if (genStart <= genEnd && numStart <= numEnd && numEnd - numStart < 9) {
                    var num = 0; for (k in numStart..numEnd) num = num * 10 + (lex.byteAt(k) - '0'.code)
                    var gen = 0; for (k in genStart..minOf(genEnd, genStart + 5)) gen = gen * 10 + (lex.byteAt(k) - '0'.code)
                    entries[num] = Entry.At(numStart, gen) // later definitions win
                }
            } else if (lex.matches(i, "trailer")) {
                lex.pos = i + 7
                (lex.next(refs = true) as? PdfDict)?.let { if (it["Root"] != null) trailerDict = it }
            }
            i++
        }
        if (trailerDict != null) trailer = trailerDict!!
        // Xref streams found by the scan name the catalog too; else find it.
        if (resolve(trailer["Root"]) !is PdfDict) {
            for (num in entries.keys.sorted()) {
                val o = get(num)
                val d = (o as? PdfStream)?.dict ?: o as? PdfDict
                if (d?.name("Type") == "XRef" && d["Root"] != null) { trailer = d; break }
                if (d?.name("Type") == "Catalog") root = PdfRef(num, 0)
            }
            if (resolve(trailer["Root"]) !is PdfDict && root != null) trailer = PdfDict(trailer.map + ("Root" to root!!))
        }
    }

    // ---- objects ----------------------------------------------------------------

    fun resolve(o: PdfObj?, depth: Int = 0): PdfObj? {
        var cur = o
        var d = depth
        while (cur is PdfRef) {
            if (d++ > 32) return null
            cur = get(cur.num)
        }
        return if (cur is PdfNull) null else cur
    }

    fun dict(o: PdfObj?): PdfDict? = when (val r = resolve(o)) { is PdfDict -> r; is PdfStream -> r.dict; else -> null }
    fun array(o: PdfObj?): PdfArray? = resolve(o) as? PdfArray
    fun num(o: PdfObj?): Double? = (resolve(o) as? PdfNum)?.value

    private var resolving = 0

    fun get(num: Int): PdfObj? {
        cache[num]?.let { return it }
        val e = entries[num] ?: return null
        if (resolving > 64) return null
        resolving++
        try {
            val o = when (e) {
                is Entry.At -> if (e.offset < 0) null else readIndirectAt(e.offset, PdfRef(num, e.gen))
                is Entry.InStream -> objectFromStream(e.stream, num)
            } ?: return null
            cache[num] = o
            return o
        } finally {
            resolving--
        }
    }

    private fun readIndirectAt(offset: Int, ref: PdfRef?): PdfObj? {
        val lex = PdfLexer(buf, offset)
        val num = lex.next() as? PdfNum ?: return null
        val gen = lex.next() as? PdfNum ?: return null
        if ((lex.next() as? PdfKeyword)?.word != "obj") return null
        if (ref != null && num.int != ref.num) {
            // The table points at the wrong place: rebuild once and try again.
            if (!rebuilt) { rebuild(); return get(ref.num) }
            return null
        }
        val owner = PdfRef(num.int, gen.int)
        var o = lex.next(refs = true) ?: return null
        lex.skipSpace()
        if (o is PdfDict && lex.matches(lex.pos, "stream")) {
            lex.pos += 6
            if (lex.byteAt(lex.pos) == 13) lex.pos++
            if (lex.byteAt(lex.pos) == 10) lex.pos++
            val start = lex.pos
            var length = (resolveLength(o["Length"]) ?: -1)
            if (length < 0 || start + length > lex.size || !endstreamNear(lex, start + length)) length = findEndstream(lex, start) - start
            o = PdfStream(o, start, length.coerceAtLeast(0), owner)
        } else if (crypt != null && owner != encryptRef) {
            o = decryptStrings(o, owner, 0)
        }
        return o
    }

    private fun resolveLength(o: PdfObj?): Int? = when (o) {
        is PdfNum -> o.int
        is PdfRef -> (get(o.num) as? PdfNum)?.int
        else -> null
    }

    private fun endstreamNear(lex: PdfLexer, at: Int): Boolean {
        var p = at
        while (p < lex.size && p < at + 4 && PdfLexer.isSpace(lex.byteAt(p))) p++
        return lex.matches(p, "endstream") || (lex.byteAt(p) == 'e'.code && lex.byteAt(p + 1) == 'n'.code && lex.byteAt(p + 2) == 'd'.code)
    }

    private fun findEndstream(lex: PdfLexer, start: Int): Int {
        var p = start
        while (p < lex.size - 9) {
            if (lex.byteAt(p) == 'e'.code && lex.matches(p, "endstream")) {
                var e = p
                if (e > start && lex.byteAt(e - 1) == 10) e--
                if (e > start && lex.byteAt(e - 1) == 13) e--
                return e
            }
            p++
        }
        return lex.size
    }

    private fun decryptStrings(o: PdfObj, owner: PdfRef, depth: Int): PdfObj {
        val c = crypt ?: return o
        if (depth > 32) return o
        return when (o) {
            is PdfString -> PdfString(c.decryptString(o.bytes, owner))
            is PdfArray -> PdfArray(o.items.map { decryptStrings(it, owner, depth + 1) })
            is PdfDict -> PdfDict(o.map.mapValues { decryptStrings(it.value, owner, depth + 1) })
            else -> o
        }
    }

    private fun objectFromStream(streamNum: Int, num: Int): PdfObj? {
        val objects = objectStreams[streamNum] ?: run {
            val s = get(streamNum) as? PdfStream ?: return null
            val data = streamBytes(s) ?: return null
            val n = (s.dict.int("N") ?: 0).coerceIn(0, 1_000_000)
            val first = s.dict.int("First") ?: return null
            val lex = PdfLexer(ByteBuffer.wrap(data))
            val offsets = ArrayList<Pair<Int, Int>>()
            repeat(n) {
                val on = (lex.next() as? PdfNum)?.int ?: return@repeat
                val oo = (lex.next() as? PdfNum)?.int ?: return@repeat
                offsets += on to oo
            }
            val map = HashMap<Int, PdfObj>()
            for ((on, oo) in offsets) {
                lex.pos = first + oo
                lex.next(refs = true)?.let { if (it !is PdfKeyword) map[on] = it }
            }
            objectStreams[streamNum] = map
            map
        }
        return objects[num]
    }

    // ---- streams ----------------------------------------------------------------

    /** A stream's data, decrypted, before its filters. */
    fun rawBytes(s: PdfStream, decrypt: Boolean = true): ByteArray? {
        if (s.length < 0 || s.start + s.length > buf.limit()) return null
        val b = ByteArray(s.length)
        val dup = buf.duplicate()
        dup.position(s.start)
        dup.get(b)
        val c = crypt
        if (decrypt && c != null && s.owner != null && s.dict.name("Type") != "XRef") {
            // A stream with its own /Crypt filter set to Identity isn't encrypted.
            if (filters(s).firstOrNull() == "Crypt") return b
            return c.decryptStream(b, s.owner)
        }
        return b
    }

    fun filters(s: PdfStream): List<String> = when (val f = resolve(s.dict["Filter"])) {
        is PdfName -> listOf(f.name)
        is PdfArray -> f.items.mapNotNull { (resolve(it) as? PdfName)?.name }
        else -> emptyList()
    }

    private fun parms(s: PdfStream): List<PdfDict?> = when (val p = resolve(s.dict["DecodeParms"] ?: s.dict["DP"])) {
        is PdfDict -> listOf(p)
        is PdfArray -> p.items.map { resolve(it) as? PdfDict }
        else -> emptyList()
    }

    /** A stream decoded through its filters, up to any image codec. */
    fun decode(s: PdfStream, decrypt: Boolean = true, limit: Int = PdfFilters.MAX_OUTPUT): PdfFilters.Decoded? {
        val raw = rawBytes(s, decrypt) ?: return null
        return PdfFilters.decode(raw, filters(s), parms(s), limit)
    }

    fun streamBytes(s: PdfStream, decrypt: Boolean = true): ByteArray? = decode(s, decrypt)?.bytes

    // ---- pages ------------------------------------------------------------------

    class Page(val dict: PdfDict, val resources: PdfDict?, val mediaBox: DoubleArray, val cropBox: DoubleArray, val rotate: Int)

    val pages: List<Page> by lazy {
        val out = ArrayList<Page>()
        val root = dict(trailer["Root"])
        val seen = HashSet<PdfObj>()
        fun walk(node: PdfObj?, inherited: Map<String, PdfObj>, depth: Int) {
            if (depth > 64 || out.size >= Limits.MAX_PAGES) return
            if (node is PdfRef && !seen.add(node)) return
            val d = dict(node) ?: return
            val attrs = HashMap(inherited)
            for (k in listOf("Resources", "MediaBox", "CropBox", "Rotate")) d[k]?.let { attrs[k] = it }
            val kids = array(d["Kids"])
            if (kids != null && d.name("Type") != "Page") {
                for (k in kids.items) walk(k, attrs, depth + 1)
                return
            }
            fun box(o: PdfObj?): DoubleArray? = array(o)?.items?.mapNotNull { num(it) }?.takeIf { it.size == 4 }?.let { b ->
                doubleArrayOf(minOf(b[0], b[2]), minOf(b[1], b[3]), maxOf(b[0], b[2]), maxOf(b[1], b[3]))
            }?.takeIf { it[2] - it[0] >= 1 && it[3] - it[1] >= 1 && it.all { v -> v.isFinite() && Math.abs(v) < 1e6 } }
            val media = box(attrs["MediaBox"]) ?: doubleArrayOf(0.0, 0.0, 612.0, 792.0)
            val crop = box(attrs["CropBox"])?.let { c ->
                doubleArrayOf(maxOf(c[0], media[0]), maxOf(c[1], media[1]), minOf(c[2], media[2]), minOf(c[3], media[3]))
                    .takeIf { it[2] - it[0] >= 1 && it[3] - it[1] >= 1 }
            } ?: media
            val rot = (((num(attrs["Rotate"]) ?: 0.0).toInt() % 360) + 360) % 360 / 90 * 90
            out += Page(d, dict(attrs["Resources"]), media, crop, rot)
        }
        walk(root?.get("Pages"), emptyMap(), 0)
        out
    }
}
