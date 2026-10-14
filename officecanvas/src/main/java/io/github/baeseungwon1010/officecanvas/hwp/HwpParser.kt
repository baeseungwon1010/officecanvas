package io.github.baeseungwon1010.officecanvas.hwp

import io.github.baeseungwon1010.officecanvas.hwpx.Border
import io.github.baeseungwon1010.officecanvas.hwpx.BorderFill
import io.github.baeseungwon1010.officecanvas.hwpx.CharStyle
import io.github.baeseungwon1010.officecanvas.hwpx.HWPUNIT_PER_INCH
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxDoc
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxElement
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxPage
import io.github.baeseungwon1010.officecanvas.hwpx.PageFlow
import io.github.baeseungwon1010.officecanvas.hwpx.ParaAlign
import io.github.baeseungwon1010.officecanvas.hwpx.Span
import io.github.baeseungwon1010.officecanvas.hwpx.TableCell
import io.github.baeseungwon1010.officecanvas.ole.Ole2Reader
import io.github.baeseungwon1010.officecanvas.util.Limits
import io.github.baeseungwon1010.officecanvas.util.SafeBitmaps
import java.io.File
import java.util.zip.Inflater

/**
 * Reads a Hangul 5.0 binary document (.hwp) into the same page model as .hwpx.
 *
 * The container is OLE2: a FileHeader stream (signature, version, flags), a
 * DocInfo stream (fonts, character and paragraph shapes, the picture list) and one
 * BodyText/SectionN stream per section, all usually raw-deflated, plus pictures in
 * BinData/BINxxxx.ext. The streams are sequences of tagged records whose level
 * field forms a tree (a paragraph's text, character runs, line segments and
 * controls are its children; a table's cells and their paragraphs hang off the
 * table's control header).
 *
 * As in HWPX, every line's position is saved (PARA_LINE_SEG: start offset,
 * vertical position, horizontal start and width), so text is placed where Hangul
 * put it rather than laid out again, and a vertical position going back up marks a
 * new page.
 *
 * Spec: Hancom's "한글 문서 파일 형식 5.0" (HWP 5.0 file format, revision 1.3).
 */
internal object HwpParser {

    class ParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private const val TAG_BEGIN = 16
    private const val ID_MAPPINGS = TAG_BEGIN + 1
    private const val BIN_DATA = TAG_BEGIN + 2
    private const val FACE_NAME = TAG_BEGIN + 3
    private const val BORDER_FILL = TAG_BEGIN + 4
    private const val CHAR_SHAPE = TAG_BEGIN + 5
    private const val PARA_SHAPE = TAG_BEGIN + 9
    private const val PARA_HEADER = TAG_BEGIN + 50
    private const val PARA_TEXT = TAG_BEGIN + 51
    private const val PARA_CHAR_SHAPE = TAG_BEGIN + 52
    private const val PARA_LINE_SEG = TAG_BEGIN + 53
    private const val CTRL_HEADER = TAG_BEGIN + 55
    private const val LIST_HEADER = TAG_BEGIN + 56
    private const val PAGE_DEF = TAG_BEGIN + 57
    private const val SHAPE_COMPONENT = TAG_BEGIN + 60
    private const val TABLE = TAG_BEGIN + 61
    private const val SHAPE_PICTURE = TAG_BEGIN + 69

    /** A stream larger than this once inflated is not read. */
    private const val MAX_STREAM_BYTES = 64 shl 20

    private val SIGNATURE = "HWP Document File".toByteArray(Charsets.US_ASCII)

    /** True when [ole] holds a Hangul 5.0 document. */
    fun isHwp(ole: Ole2Reader): Boolean {
        val header = ole.readStream("FileHeader") ?: return false
        return header.size >= 40 && SIGNATURE.indices.all { header[it] == SIGNATURE[it] }
    }

    fun parse(file: File): HwpxDoc {
        val ole = try {
            Ole2Reader.open(file)
        } catch (e: Ole2Reader.FormatException) {
            throw ParseException("not a .hwp: ${e.message}", e)
        }
        if (!isHwp(ole)) throw ParseException("not a .hwp: missing HWP FileHeader")
        val header = Bytes(ole.readStream("FileHeader")!!)
        val flags = header.u32(36)
        if (flags and 0x2 != 0) throw ParseException("the document is password-protected")
        if (flags and 0x4 != 0) throw ParseException("distribution documents are encrypted and not supported")
        val compressed = flags and 0x1 != 0

        fun stream(path: String, deflated: Boolean = compressed): ByteArray? {
            val raw = ole.readStream(path) ?: return null
            return if (deflated) inflate(raw) else raw
        }
        val docInfo = stream("DocInfo") ?: throw ParseException("missing DocInfo")
        val sections = ole.streamPaths.mapNotNull { p ->
            Regex("BodyText/Section(\\d+)").matchEntire(p)?.groupValues?.get(1)?.toIntOrNull()?.let { it to p }
        }.sortedBy { it.first }.map { it.second }
        if (sections.isEmpty()) throw ParseException("no BodyText sections")
        return fromStreams(docInfo, sections.asSequence().map { stream(it) }) { id, ext, compression ->
            val deflate = when (compression) { 1 -> true; 2 -> false; else -> compressed }
            listOf(ext, ext.lowercase(), ext.uppercase()).distinct()
                .firstNotNullOfOrNull { ole.readStream("BinData/BIN%04X.%s".format(id, it)) }
                ?.let { if (deflate) inflate(it) ?: it else it }
        }
    }

    /**
     * The document from its decompressed DocInfo and section streams; [binData]
     * finds a picture by BIN_DATA id, extension and compression setting. Separate
     * from the container so tests can feed crafted streams directly.
     */
    internal fun fromStreams(
        docInfo: ByteArray, sections: Sequence<ByteArray?>, binData: (Int, String, Int) -> ByteArray?
    ): HwpxDoc {
        val info = DocInfo.read(Bytes(docInfo))
        val pictures = HashMap<Int, ByteArray?>()
        fun picture(binItem: Int): ByteArray? = pictures.getOrPut(binItem) {
            val bin = info.binData.getOrNull(binItem - 1)?.takeIf { it.id >= 0 } ?: return@getOrPut null
            binData(bin.id, bin.ext, bin.compression)?.takeIf { it.size <= SafeBitmaps.MAX_IMAGE_BYTES }
        }

        val pages = mutableListOf<HwpxPage>()
        var geometry = PageGeometry(59528, 84186, 8504, 8504)
        for (data in sections) {
            if (pages.size >= Limits.MAX_PAGES) break
            data ?: continue
            try {
                val section = Section(info, ::picture, pages.size)
                val roots = Record.tree(Bytes(data))
                section.read(roots, geometry)
                geometry = section.geometry
                for (page in section.pages) if (pages.size < Limits.MAX_PAGES) pages += page
            } catch (e: Exception) {
                // One unreadable section costs its own pages, not the whole document.
            }
        }
        return HwpxDoc(pages)
    }

    /** Raw deflate (no zlib header), capped. Null if the data isn't deflated. */
    private fun inflate(data: ByteArray): ByteArray? {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(data)
            val out = java.io.ByteArrayOutputStream(data.size * 4)
            val buf = ByteArray(64 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > MAX_STREAM_BYTES) return null
            }
            out.toByteArray()
        } catch (e: java.util.zip.DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    // ---- records --------------------------------------------------------------

    /** Little-endian reads that return 0 past the end instead of throwing. */
    private class Bytes(val data: ByteArray, val start: Int = 0, val end: Int = data.size) {
        val size get() = end - start
        fun u8(o: Int): Int = if (start + o in start until end) data[start + o].toInt() and 0xFF else 0
        fun u16(o: Int): Int = u8(o) or (u8(o + 1) shl 8)
        fun i16(o: Int): Int = u16(o).toShort().toInt()
        fun u32(o: Int): Int = u16(o) or (u16(o + 2) shl 16)
        fun utf16(o: Int, chars: Int): String {
            val n = chars.coerceIn(0, ((size - o) / 2).coerceAtLeast(0))
            return String(CharArray(n) { u16(o + 2 * it).toChar() })
        }
    }

    /** A record and the records nested under it (those with a deeper level
     *  directly following it). */
    private class Record(val tag: Int, val level: Int, val body: Bytes) {
        val children = mutableListOf<Record>()

        fun child(tag: Int) = children.firstOrNull { it.tag == tag }
        fun all(tag: Int) = children.filter { it.tag == tag }

        /** The control id of a CTRL_HEADER, as the four characters it spells ("tbl "). */
        val ctrlId: String get() = String(CharArray(4) { body.u8(3 - it).toChar() })

        companion object {
            fun tree(b: Bytes): List<Record> {
                val roots = mutableListOf<Record>()
                val stack = ArrayDeque<Record>()
                var off = 0
                while (off + 4 <= b.size) {
                    val h = b.u32(off)
                    val tag = h and 0x3FF
                    val level = (h ushr 10) and 0x3FF
                    var size = h ushr 20
                    off += 4
                    if (size == 0xFFF) { size = b.u32(off); off += 4 }
                    if (size < 0 || size > b.size - off) break
                    val rec = Record(tag, level, Bytes(b.data, b.start + off, b.start + off + size))
                    off += size
                    while (stack.isNotEmpty() && stack.last().level >= level) stack.removeLast()
                    // A level deeper than the nesting limit is attached at the limit.
                    if (stack.isEmpty()) roots += rec else stack.last().children += rec
                    if (stack.size < Limits.MAX_NESTING * 4) stack.addLast(rec)
                }
                return roots
            }
        }
    }

    // ---- DocInfo --------------------------------------------------------------

    private class BinItem(val id: Int, val ext: String, val compression: Int)

    private class DocInfo(
        val charShapes: List<CharStyle>,
        val paraAligns: List<ParaAlign>,
        val borderFills: List<BorderFill>,
        val binData: List<BinItem>
    ) {
        fun charShape(id: Int) = charShapes.getOrNull(id) ?: CharStyle()
        fun paraAlign(id: Int) = paraAligns.getOrNull(id) ?: ParaAlign.LEFT
        /** Border/fill ids are 1-based. */
        fun borderFill(id: Int) = borderFills.getOrNull(id - 1)

        companion object {
            /** Border line widths by index, in millimetres. */
            private val LINE_MM = floatArrayOf(0.1f, 0.12f, 0.15f, 0.2f, 0.25f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 1f, 1.5f, 2f, 3f, 4f, 5f)

            /** COLORREF is 0x00BBGGRR; anything in the top byte means "no color". */
            private fun colorRef(v: Int): Int? = if (v ushr 24 != 0) null
                else (0xFF shl 24) or ((v and 0xFF) shl 16) or (v and 0xFF00) or ((v ushr 16) and 0xFF)

            fun read(b: Bytes): DocInfo {
                val records = Record.tree(b).flatMap { listOf(it) + it.children.flatMap { c -> listOf(c) + c.children } }
                // Fonts are listed per script (Hangul, Latin, Hanja, ...), each list
                // as long as ID_MAPPINGS says; character shapes index into them.
                val mappings = records.firstOrNull { it.tag == ID_MAPPINGS }?.body
                val faceCounts = (1..7).map { mappings?.u32(it * 4)?.coerceAtLeast(0) ?: 0 }
                val faces = records.filter { it.tag == FACE_NAME }.map { r -> r.body.utf16(3, r.body.u16(1)) }
                fun face(script: Int, index: Int): String? {
                    val first = faceCounts.take(script).sum()
                    return if (index < faceCounts.getOrElse(script) { 0 }) faces.getOrNull(first + index) else null
                }
                // CHAR_SHAPE: face ids (7 x u16), width ratios (7 x u8), spacings (7 x
                // s8), relative sizes, offsets, then size (1/100 pt), attributes, ...,
                // text color. Index 0 of each per-script array is Hangul's.
                val charShapes = records.filter { it.tag == CHAR_SHAPE }.map { r ->
                    val c = r.body
                    val attr = c.u32(46)
                    CharStyle(
                        sizePt = c.u32(42) / 100f,
                        colorArgb = colorRef(c.u32(52)) ?: 0xFF000000.toInt(),
                        italic = attr and 0x1 != 0,
                        bold = attr and 0x2 != 0,
                        underline = (attr ushr 2) and 0x3 == 1,
                        strike = (attr ushr 18) and 0x7 != 0,
                        hangulFace = face(0, c.u16(0)),
                        latinFace = face(1, c.u16(2)),
                        widthPercent = c.u8(14).takeIf { it in 50..200 } ?: 100,
                        spacingPercent = c.u8(21).toByte().toInt(),
                        script = when {
                            attr and (1 shl 15) != 0 -> 1
                            attr and (1 shl 16) != 0 -> -1
                            else -> 0
                        }
                    )
                }
                // PARA_SHAPE attribute bits 2-4: justify, left, right, center, distribute, divide.
                val paraAligns = records.filter { it.tag == PARA_SHAPE }.map { r ->
                    when ((r.body.u32(0) ushr 2) and 0x7) {
                        0 -> ParaAlign.JUSTIFY
                        2 -> ParaAlign.RIGHT
                        3 -> ParaAlign.CENTER
                        4, 5 -> ParaAlign.DISTRIBUTE
                        else -> ParaAlign.LEFT
                    }
                }
                // BORDER_FILL: attributes, then left/right/top/bottom/diagonal lines (type,
                // width index, color), then the fill (bit 0 of its type: a solid color).
                val borderFills = records.filter { it.tag == BORDER_FILL }.map { r ->
                    val f = r.body
                    fun side(o: Int): Border? {
                        if (f.u8(o) == 0) return null // type 0: no line
                        val mm = LINE_MM.getOrElse(f.u8(o + 1)) { 0.12f }
                        return Border(colorRef(f.u32(o + 2)) ?: return null, (mm * HWPUNIT_PER_INCH / 25.4).toLong())
                    }
                    // Fill type bits: 0 solid color, 1 picture, 2 gradient. A gradient
                    // (type u8, angle, center x/y, blur, color count, then the
                    // colors) is drawn as the average of its colors.
                    val fillType = f.u32(32)
                    val fill = when {
                        fillType and 0x1 != 0 -> colorRef(f.u32(36))
                        fillType and 0x4 != 0 -> {
                            val n = f.u32(32 + 21).coerceIn(0, 16)
                            val at = 32 + 25 + if (n > 2) 4 * n else 0
                            (0 until n).mapNotNull { colorRef(f.u32(at + 4 * it)) }.takeIf { it.isNotEmpty() }?.let { cs ->
                                fun avg(shift: Int) = cs.sumOf { (it ushr shift) and 0xFF } / cs.size
                                (0xFF shl 24) or (avg(16) shl 16) or (avg(8) shl 8) or avg(0)
                            }
                        }
                        else -> null
                    }
                    BorderFill(side(2), side(8), side(14), side(20), fill)
                }
                val binData = records.filter { it.tag == BIN_DATA }.map { r ->
                    val attr = r.body.u16(0)
                    // Linked pictures (type 0) point at files outside the document; they are not followed.
                    if (attr and 0xF == 0) BinItem(-1, "", 0)
                    else BinItem(r.body.u16(2), r.body.utf16(6, r.body.u16(4)), (attr ushr 4) and 0x3)
                }
                return DocInfo(charShapes, paraAligns, borderFills, binData)
            }
        }
    }

    // ---- BodyText ---------------------------------------------------------------

    private data class PageGeometry(
        val widthHu: Long, val heightHu: Long, val bodyXHu: Long, val bodyYHu: Long,
        val rightHu: Long = 8504, val bottomHu: Long = 4252, val headerHu: Long = 4252, val footerHu: Long = 4252
    )

    private fun HwpxElement.shifted(dx: Long, dy: Long): HwpxElement = when (this) {
        is HwpxElement.TextRun -> copy(xHu = xHu + dx, yHu = yHu + dy)
        is HwpxElement.Table -> copy(xHu = xHu + dx, yHu = yHu + dy)
        is HwpxElement.ImageEl -> copy(xHu = xHu + dx, yHu = yHu + dy)
    }

    private class Line(val start: Int, val y: Long, val height: Long, val textHeight: Long, val baseline: Long, val spacing: Long, val x: Long, val width: Long)

    /** PARA_LINE_SEG: 36 bytes per line (start, y, height, text height, baseline,
     *  spacing, x, width, flags). */
    private fun lineSegs(para: Record, stack: Boolean = false): List<Line> {
        val lines = para.child(PARA_LINE_SEG)?.body?.let { b ->
            (0 until b.size / 36).map { k ->
                val o = k * 36
                Line(b.u32(o), b.u32(o + 4).toLong(), b.u32(o + 8).toLong(), b.u32(o + 12).toLong(),
                    b.u32(o + 16).toLong(), b.u32(o + 20).toLong(), b.u32(o + 24).toLong(), b.u32(o + 28).toLong())
            }
        }.orEmpty()
        if (!stack) return lines
        // In a cell the lines only go down; a writer that saved every line at the
        // same height left them to be stacked.
        val out = ArrayList<Line>(lines.size)
        for (l in lines) {
            val prev = out.lastOrNull()
            out += if (prev != null && l.y <= prev.y) Line(l.start, prev.y + (prev.height.takeIf { it > 0 } ?: prev.textHeight) + prev.spacing, l.height, l.textHeight, l.baseline, l.spacing, l.x, l.width) else l
        }
        return out
    }

    private class Section(val info: DocInfo, val picture: (Int) -> ByteArray?, val firstPageIndex: Int) {
        var geometry = PageGeometry(59528, 84186, 8504, 8504)
        val pages = mutableListOf<HwpxPage>()
        private var header: List<HwpxElement> = emptyList()
        private var footer: List<HwpxElement> = emptyList()
        /** Page number position (1-3 top, 4-6 bottom: left, center, right), if any. */
        private var pageNumber: Int? = null

        private fun listParagraphs(ctrl: Record): List<HwpxElement> =
            ctrl.children.filter { it.tag == PARA_HEADER }.flatMap { paragraph(it, 1) }

        private fun pageNumberRun(position: Int, number: Int): HwpxElement.TextRun? {
            if (position !in 1..6) return null
            val g = geometry
            val width = g.widthHu - g.bodyXHu - g.rightHu
            val y = if (position <= 3) -g.headerHu / 2 - 500 else g.heightHu - g.bottomHu - g.footerHu / 2 - 500 - g.bodyYHu
            val align = when ((position - 1) % 3) { 0 -> ParaAlign.LEFT; 1 -> ParaAlign.CENTER; else -> ParaAlign.RIGHT }
            return HwpxElement.TextRun(0, y, width, 1000, listOf(Span(number.toString(), CharStyle())), 850, align)
        }

        fun read(roots: List<Record>, fallback: PageGeometry) {
            geometry = fallback
            var flow: PageFlow? = null
            for (para in roots.filter { it.tag == PARA_HEADER }) {
                // The section definition rides on the first paragraph's controls.
                for (ctrl in para.all(CTRL_HEADER)) when (ctrl.ctrlId) {
                    "secd" -> ctrl.child(PAGE_DEF)?.let { geometry = pageGeometry(it.body) }
                    // Header and footer: a list of paragraphs placed in the header
                    // (footer) area, repeated on every page of the section.
                    "head" -> if (header.isEmpty()) header = listParagraphs(ctrl)
                    "foot" -> if (footer.isEmpty()) footer = listParagraphs(ctrl)
                    "pgnp" -> pageNumber = (ctrl.body.u32(4) ushr 8) and 0xF
                }
                val pageFlow = flow ?: PageFlow(geometry.run { heightHu - bodyYHu - bottomHu - footerHu }).also { flow = it }
                if (pageFlow.full) break
                // Break type (PARA_HEADER byte 11): 0x01 section, 0x04 page. A page
                // break's first line is at the top like the previous page's, so the
                // flag is what tells them apart.
                val forcedBreak = para.body.u8(11) and 0x05 != 0
                for ((k, el) in paragraph(para, 0).withIndex()) {
                    pageFlow.add(el, startsPage = k == 0 && forcedBreak, startsLine = el in lineStarts, floating = el in placedFloating)
                }
            }
            val g = geometry
            for (elements in flow?.finish().orEmpty()) {
                if (elements.isEmpty()) continue
                val decorations = header.map { it.shifted(0, -g.headerHu) } +
                    footer.map { it.shifted(0, g.heightHu - g.bottomHu - g.footerHu - g.bodyYHu) } +
                    listOfNotNull(pageNumber?.let { pageNumberRun(it, firstPageIndex + pages.size + 1) })
                pages += HwpxPage(g.widthHu, g.heightHu, decorations + elements, g.bodyXHu, g.bodyYHu)
            }
        }

        private fun pageGeometry(p: Bytes): PageGeometry {
            var w = p.u32(0).toLong() and 0xFFFFFFFFL
            var h = p.u32(4).toLong() and 0xFFFFFFFFL
            if (p.u32(36) and 0x1 != 0) { val t = w; w = h; h = t } // landscape
            if (w <= 0 || h <= 0 || w > 1_000_000 || h > 1_000_000) return geometry
            val left = p.u32(8).toLong(); val right = p.u32(12).toLong(); val top = p.u32(16).toLong()
            val bottom = p.u32(20).toLong(); val header = p.u32(24).toLong(); val footer = p.u32(28).toLong()
            val gutter = p.u32(32).toLong()
            return PageGeometry(w, h, left + gutter, top + header, right, bottom, header, footer)
        }

        /**
         * A paragraph's text with its controls: characters below 32 are controls,
         * most of them eight code units long (the code, six of data, the code
         * again) and counted that way in line start offsets. Extended controls
         * (tables, pictures, section definitions, ...) take their CTRL_HEADER
         * children in order.
         */
        fun paragraph(para: Record, depth: Int, inCell: Boolean = false): List<HwpxElement> {
            val raw = para.child(PARA_TEXT)?.body
            val units = (raw?.size ?: 0) / 2
            val chars = CharArray(units) { raw!!.u16(2 * it).toChar() }
            val text = StringBuilder(units)
            val inlineAt = HashMap<Int, HwpxElement>()
            val controls = para.all(CTRL_HEADER).iterator()
            var i = 0
            while (i < units) {
                val c = chars[i].code
                when {
                    c >= 32 -> { text.append(chars[i]); i++ }
                    c in CHAR_CONTROLS -> {
                        text.append(when (c) { 24 -> '-'; 30, 31 -> ' '; else -> FILLER })
                        i++
                    }
                    else -> {
                        val span = minOf(8, units - i)
                        val ctrl = if (c in EXTENDED_CONTROLS) controls.takeIf { it.hasNext() }?.next() else null
                        val obj = ctrl?.let { control(it, depth) }
                        if (obj != null) { inlineAt[text.length] = obj; text.append(OBJECT) }
                        else text.append(if (c == 9) '\t' else FILLER)
                        repeat(span - 1) { text.append(FILLER) }
                        i += span
                    }
                }
            }
            val lines = lineSegs(para, stack = inCell)
            if (lines.isEmpty()) return emptyList()
            val runs = para.child(PARA_CHAR_SHAPE)?.body?.let { b -> (0 until b.size / 8).map { b.u32(it * 8) to b.u32(it * 8 + 4) } }.orEmpty()

            val align = info.paraAlign(para.body.u16(8))
            val out = mutableListOf<HwpxElement>()
            for ((k, line) in lines.withIndex()) {
                val start = line.start.coerceIn(0, text.length)
                val end = (if (k + 1 < lines.size) lines[k + 1].start else text.length).coerceIn(start, text.length)
                val last = k == lines.size - 1
                var runStart = start
                val lineFirst = out.size
                fun emit(el: HwpxElement) {
                    if (out.size == lineFirst || out.drop(lineFirst).all { it in placedFloating }) {
                        if (el !in placedFloating) lineStarts += el
                    }
                    out += el
                }
                for (pos in start until end) {
                    val obj = inlineAt[pos] ?: continue
                    textRun(text, runStart, pos, runs, line, align, last)?.let { emit(it) }
                    emit(place(obj, line))
                    runStart = pos + 1
                }
                textRun(text, runStart, end, runs, line, align, last)?.let { emit(it) }
            }
            return out
        }

        /** The line's text from [start] to [end], split where the character shape changes. */
        private fun textRun(
            text: StringBuilder, start: Int, end: Int, runs: List<Pair<Int, Int>>, line: Line, align: ParaAlign, last: Boolean
        ): HwpxElement.TextRun? {
            if (end <= start) return null
            val spans = mutableListOf<Span>()
            var pos = start
            while (pos < end) {
                val idx = runs.indexOfLast { it.first <= pos }.coerceAtLeast(0)
                val next = (runs.getOrNull(idx + 1)?.first ?: end).coerceIn(pos + 1, end)
                val piece = text.substring(pos, next).filter { it != FILLER && it != OBJECT }.replace("\t", "    ")
                if (piece.isNotEmpty()) spans += Span(piece, info.charShape(runs.getOrNull(idx)?.second ?: 0))
                pos = next
            }
            while (spans.isNotEmpty() && spans.last().text.isBlank()) spans.removeAt(spans.size - 1)
            if (spans.isEmpty()) return null
            spans[spans.size - 1] = spans.last().copy(text = spans.last().text.trimEnd())
            return HwpxElement.TextRun(
                xHu = line.x, yHu = line.y, widthHu = line.width, heightHu = line.textHeight, spans = spans,
                baselineHu = line.baseline.takeIf { it > 0 } ?: (line.textHeight * 85 / 100), align = align, lastLine = last
            )
        }

        /** Inline objects sit on their line; floating ones are offset from it (or from the page). */
        private fun place(obj: HwpxElement, line: Line): HwpxElement {
            val floating = floatingOf[obj]
            var x = line.x
            var y = line.y
            if (floating != null) {
                x = when (floating.horzRel) {
                    0 -> floating.horzOffset - geometry.bodyXHu
                    1 -> floating.horzOffset
                    else -> line.x + floating.horzOffset
                }
                y = when (floating.vertRel) {
                    0 -> floating.vertOffset - geometry.bodyYHu
                    1 -> floating.vertOffset
                    // An object that takes its own lines and moves with the text
                    // can't sit above the paragraph it belongs to; a negative offset
                    // saved for it is left from before the text moved (the next
                    // paragraph starts right below the object at the anchor line).
                    else -> if (floating.keepsBelowAnchor) maxOf(line.y, line.y + floating.vertOffset) else line.y + floating.vertOffset
                }
            }
            val placed = when (obj) {
                is HwpxElement.Table -> obj.copy(xHu = x, yHu = y)
                is HwpxElement.ImageEl -> obj.copy(xHu = x, yHu = y)
                is HwpxElement.TextRun -> obj.copy(xHu = x, yHu = y)
            }
            if (floating != null) placedFloating += placed
            return placed
        }

        private class Floating(val vertRel: Int, val horzRel: Int, val vertOffset: Long, val horzOffset: Long, val keepsBelowAnchor: Boolean)
        private val floatingOf = java.util.IdentityHashMap<HwpxElement, Floating>()
        /** Elements that begin a line (see PageFlow.add). */
        private val lineStarts = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<HwpxElement, Boolean>())
        /** Floating objects once placed. */
        private val placedFloating = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<HwpxElement, Boolean>())

        /** A table or picture control; null for every other kind. */
        private fun control(ctrl: Record, depth: Int): HwpxElement? {
            if (depth > Limits.MAX_NESTING) return null
            val obj = when (ctrl.ctrlId) {
                "tbl " -> table(ctrl, depth)
                "gso " -> picture(ctrl)
                else -> null
            } ?: return null
            // Common object properties: attribute bit 0 = treat as a character.
            val attr = ctrl.body.u32(4)
            if (attr and 0x1 == 0) {
                // Bits 3-4 vertical reference (paper, page, paragraph), 8-9 horizontal,
                // 13 moves with text, 21-23 text wrapping (1 = takes its own lines).
                val keepsBelow = (attr ushr 13) and 0x1 != 0 && (attr ushr 21) and 0x7 == 1
                floatingOf[obj] = Floating((attr ushr 3) and 0x3, (attr ushr 8) and 0x3, ctrl.body.u32(8).toLong(), ctrl.body.u32(12).toLong(), keepsBelow)
            }
            return obj
        }

        private fun picture(ctrl: Record): HwpxElement.ImageEl? {
            val pic = findDeep(ctrl, SHAPE_PICTURE, 0) ?: return null
            val bytes = picture(pic.body.u16(71)) ?: return null
            val w = ctrl.body.u32(16).toLong(); val h = ctrl.body.u32(20).toLong()
            if (w <= 0 || h <= 0) return null
            return HwpxElement.ImageEl(0, 0, w, h, zipEntry = null, bytes = bytes)
        }

        private fun findDeep(r: Record, tag: Int, depth: Int): Record? {
            if (depth > Limits.MAX_NESTING) return null
            for (c in r.children) {
                if (c.tag == tag) return c
                findDeep(c, tag, depth + 1)?.let { return it }
            }
            return null
        }

        /**
         * A table control: a TABLE record (attributes, row and column counts), then
         * for each cell a LIST_HEADER (paragraph count, then column, row, spans,
         * size, margins) followed by that many paragraphs.
         */
        private fun table(ctrl: Record, depth: Int): HwpxElement.Table? {
            val t = ctrl.child(TABLE)?.body ?: return null
            class Cell(val col: Int, val row: Int, val colSpan: Int, val rowSpan: Int, val w: Long, val h: Long,
                       val marginLeft: Long, val marginRight: Long, val marginTop: Long, val borderFill: Int, val vAlign: Int,
                       val paras: MutableList<Record> = mutableListOf())
            val cells = mutableListOf<Cell>()
            for (c in ctrl.children) {
                when (c.tag) {
                    LIST_HEADER -> {
                        val b = c.body
                        // List header: paragraph count, flags (bits 5-6: vertical alignment),
                        // then column, row, spans, size, margins, border/fill id.
                        cells += Cell(b.u16(8), b.u16(10), b.u16(12).coerceAtLeast(1), b.u16(14).coerceAtLeast(1),
                            b.u32(16).toLong(), b.u32(20).toLong(), b.u16(24).toLong(), b.u16(26).toLong(), b.u16(28).toLong(),
                            b.u16(32), (b.u32(4) ushr 5) and 0x3)
                    }
                    PARA_HEADER -> cells.lastOrNull()?.paras?.add(c)
                }
            }
            if (cells.isEmpty()) return null
            // The declared grid is trusted only as far as the cells present (see HwpxParser).
            val rowCnt = t.u16(4).coerceIn(1, cells.maxOf { it.row } + 1)
            val colCnt = t.u16(6).coerceIn(1, cells.maxOf { it.col } + 1)
            val grid = MutableList(rowCnt) { MutableList<TableCell?>(colCnt) { null } }
            val colWidths = HashMap<Int, Long>()
            val rowHeights = HashMap<Int, Long>()
            for (cell in cells) {
                if (cell.row !in 0 until rowCnt || cell.col !in 0 until colCnt) continue
                // Some writers start every paragraph of a cell at 0 rather than below
                // the previous one; those are stacked.
                var cursor = 0L
                val runs = cell.paras.flatMap { para ->
                    val lines = lineSegs(para, stack = true)
                    val shift = lines.firstOrNull()?.let { if (it.y < cursor) cursor - it.y else 0L } ?: 0L
                    lines.lastOrNull()?.let { cursor = it.y + it.height + it.spacing + shift }
                    paragraph(para, depth + 1, inCell = true).map { it.shifted(0, shift) }
                }.map { it.shifted(cell.marginLeft, cell.marginTop) }.map { el ->
                    // A line can't be wider than its cell; some writers save the
                    // page's width for lines in cells.
                    val inner = cell.w - cell.marginLeft - cell.marginRight
                    if (el is HwpxElement.TextRun && inner > 0 && el.xHu + el.widthHu > cell.marginLeft + inner)
                        el.copy(widthHu = (cell.marginLeft + inner - el.xHu).coerceAtLeast(inner / 4)) else el
                }
                if (cell.colSpan == 1) colWidths[cell.col] = maxOf(colWidths[cell.col] ?: 0L, cell.w)
                if (cell.rowSpan == 1) rowHeights[cell.row] = maxOf(rowHeights[cell.row] ?: 0L, cell.h)
                grid[cell.row][cell.col] = TableCell(cell.colSpan, cell.rowSpan, runs,
                    borderFill = info.borderFill(cell.borderFill), vAlign = cell.vAlign)
            }
            val width = ctrl.body.u32(16).toLong()
            val height = ctrl.body.u32(20).toLong()
            return HwpxElement.Table(
                xHu = 0, yHu = 0, widthHu = width, heightHu = height,
                columnWidthsHu = (0 until colCnt).map { colWidths[it] ?: (width / colCnt) },
                rowHeightsHu = (0 until rowCnt).map { rowHeights[it] ?: (height / rowCnt) },
                cells = grid
            )
        }
    }

    /** Placeholder for the code units a control occupies after its first. */
    private const val FILLER = '\u0000'
    /** Where an inline table or picture sits in the text. */
    private const val OBJECT = '￼'

    /** Controls one code unit long: 0 (unused), line break, paragraph break, hyphen, spaces. */
    private val CHAR_CONTROLS = setOf(0, 10, 13, 24, 25, 26, 27, 28, 29, 30, 31)

    /** Controls with a CTRL_HEADER record of their own. */
    private val EXTENDED_CONTROLS = setOf(1, 2, 3, 11, 12, 14, 15, 16, 17, 18, 21, 22, 23)
}
