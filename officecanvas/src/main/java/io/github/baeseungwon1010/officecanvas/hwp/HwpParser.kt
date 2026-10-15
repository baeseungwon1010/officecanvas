package io.github.baeseungwon1010.officecanvas.hwp

import io.github.baeseungwon1010.officecanvas.hwpx.Border
import io.github.baeseungwon1010.officecanvas.hwpx.BorderFill
import io.github.baeseungwon1010.officecanvas.hwpx.CharStyle
import io.github.baeseungwon1010.officecanvas.hwpx.GridSpan
import io.github.baeseungwon1010.officecanvas.hwpx.clearOfTables
import io.github.baeseungwon1010.officecanvas.hwpx.gridSizes
import io.github.baeseungwon1010.officecanvas.hwpx.DecorArea
import io.github.baeseungwon1010.officecanvas.hwpx.DecorTimeline
import io.github.baeseungwon1010.officecanvas.hwpx.BulletDef
import io.github.baeseungwon1010.officecanvas.hwpx.NumberLevel
import io.github.baeseungwon1010.officecanvas.hwpx.NumberingDef
import io.github.baeseungwon1010.officecanvas.hwpx.ParaHead
import io.github.baeseungwon1010.officecanvas.hwpx.ParaHeading
import io.github.baeseungwon1010.officecanvas.hwpx.withHead
import io.github.baeseungwon1010.officecanvas.ppt.bulletGlyph
import io.github.baeseungwon1010.officecanvas.hwpx.DrawingKind
import io.github.baeseungwon1010.officecanvas.hwpx.HWPUNIT_PER_INCH
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxDoc
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxElement
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxPage
import io.github.baeseungwon1010.officecanvas.hwpx.ImageCrop
import io.github.baeseungwon1010.officecanvas.hwpx.PAGE_NUMBER_MARK
import io.github.baeseungwon1010.officecanvas.hwpx.PageDecor
import io.github.baeseungwon1010.officecanvas.hwpx.PageFlow
import io.github.baeseungwon1010.officecanvas.hwpx.PageHiding
import io.github.baeseungwon1010.officecanvas.hwpx.withPageNumber
import io.github.baeseungwon1010.officecanvas.hwpx.ParaAlign
import io.github.baeseungwon1010.officecanvas.hwpx.Span
import io.github.baeseungwon1010.officecanvas.hwpx.at
import io.github.baeseungwon1010.officecanvas.hwpx.extentHu
import io.github.baeseungwon1010.officecanvas.hwpx.paragraphRelativeY
import io.github.baeseungwon1010.officecanvas.hwpx.shiftedBy
import io.github.baeseungwon1010.officecanvas.hwpx.spansOf
import io.github.baeseungwon1010.officecanvas.hwpx.inlineSpan
import io.github.baeseungwon1010.officecanvas.hwpx.TableCell
import io.github.baeseungwon1010.officecanvas.ole.Ole2Reader
import io.github.baeseungwon1010.officecanvas.util.RecentBytes
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
    private const val NUMBERING = TAG_BEGIN + 7
    private const val BULLET = TAG_BEGIN + 8
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
    private const val SHAPE_LINE = TAG_BEGIN + 62
    private const val SHAPE_RECTANGLE = TAG_BEGIN + 63
    private const val SHAPE_POLYGON = TAG_BEGIN + 66
    private const val SHAPE_PICTURE = TAG_BEGIN + 69
    private const val EQEDIT = TAG_BEGIN + 72


    /** 2x3 affine matrices [a, b, c, d, e, f]: x' = a x + b y + c, y' = d x + e y + f. */
    private val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)

    private fun multiply(m: DoubleArray, n: DoubleArray) = doubleArrayOf(
        m[0] * n[0] + m[1] * n[3], m[0] * n[1] + m[1] * n[4], m[0] * n[2] + m[1] * n[5] + m[2],
        m[3] * n[0] + m[4] * n[3], m[3] * n[1] + m[4] * n[4], m[3] * n[2] + m[4] * n[5] + m[5]
    )

    /** Six doubles; anything absurd counts as no transform. */
    private fun matrix(b: Bytes, at: Int): DoubleArray =
        DoubleArray(6) { b.f64(at + 8 * it) }.takeIf { m -> m.all { it.isFinite() && kotlin.math.abs(it) < 1e7 } } ?: IDENTITY

    private fun apply(m: DoubleArray, x: Double, y: Double) = (m[0] * x + m[1] * y + m[2]) to (m[3] * x + m[4] * y + m[5])

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
        // The file stays open while pictures may be drawn (they are read from it
        // then); OfficeCanvas closes it through HwpxDoc.resources.
        var keep = false
        try {
            return parse(ole).also { keep = it.resources != null }
        } finally {
            if (!keep) ole.close()
        }
    }

    private fun parse(ole: Ole2Reader): HwpxDoc {
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
        var pictures = false
        val recent = RecentBytes<String>(32L shl 20)
        val doc = fromStreams(docInfo, sections.asSequence().map { stream(it) }) { id, ext, compression ->
            val deflate = when (compression) { 1 -> true; 2 -> false; else -> compressed }
            val path = listOf(ext, ext.lowercase(), ext.uppercase()).distinct()
                .map { "BinData/BIN%04X.%s".format(id, it) }.firstOrNull { ole.hasStream(it) } ?: return@fromStreams null
            pictures = true
            // Read (and inflated) when drawn; a logo on every page only once.
            return@fromStreams {
                recent.get(path) {
                    try {
                        ole.readStream(path)?.let { if (deflate) inflate(it) ?: it else it }
                    } catch (e: java.io.IOException) {
                        null // the file was closed or can't be read any more
                    }
                }
            }
        }
        return if (pictures) doc.copy(resources = ole) else doc
    }

    /**
     * The document from its decompressed DocInfo and section streams; [binData]
     * finds a picture by BIN_DATA id, extension and compression setting, giving
     * back how to read it when drawn (null when there is none). Separate
     * from the container so tests can feed crafted streams directly.
     */
    internal fun fromStreams(
        docInfo: ByteArray, sections: Sequence<ByteArray?>, binData: (Int, String, Int) -> (() -> ByteArray?)?
    ): HwpxDoc {
        val info = DocInfo.read(Bytes(docInfo))
        val pictures = HashMap<Int, (() -> ByteArray?)?>()
        fun picture(binItem: Int): (() -> ByteArray?)? = pictures.getOrPut(binItem) {
            val bin = info.binData.getOrNull(binItem - 1)?.takeIf { it.id >= 0 } ?: return@getOrPut null
            val load = binData(bin.id, bin.ext, bin.compression) ?: return@getOrPut null
            { load()?.takeIf { it.size <= SafeBitmaps.MAX_IMAGE_BYTES } }
        }

        val pages = mutableListOf<HwpxPage>()
        var geometry = PageGeometry(59528, 84186, 8504, 8504)
        // Paragraph numbers count on from one section to the next.
        val heads = ParaHead(info.numberings, info.bullets)
        var pageNumber = 0
        for (data in sections) {
            if (pages.size >= Limits.MAX_PAGES) break
            data ?: continue
            try {
                val section = Section(info, ::picture, pageNumber, heads)
                val roots = Record.tree(Bytes(data))
                section.read(roots, geometry)
                geometry = section.geometry
                pageNumber = section.pageNumber
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
        fun f64(o: Int): Double = java.lang.Double.longBitsToDouble((u32(o).toLong() and 0xFFFFFFFFL) or (u32(o + 4).toLong() shl 32))
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
        val binData: List<BinItem>,
        val headings: List<ParaHeading?> = emptyList(),
        val numberings: List<NumberingDef> = emptyList(),
        val bullets: List<BulletDef> = emptyList()
    ) {
        fun charShape(id: Int) = charShapes.getOrNull(id) ?: CharStyle()
        fun paraAlign(id: Int) = paraAligns.getOrNull(id) ?: ParaAlign.LEFT
        fun heading(paraShape: Int) = headings.getOrNull(paraShape)
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
                // PARA_SHAPE attribute bits 23-24: heading kind (outline, number,
                // bullet), 25-27 its level; the numbering or bullet id at byte 30.
                val headings = records.filter { it.tag == PARA_SHAPE }.map { r ->
                    val attr = r.body.u32(0)
                    val kind = (attr ushr 23) and 0x3
                    if (kind == 0) null else ParaHeading(kind, r.body.u16(30), (attr ushr 25) and 0x7)
                }
                // A paragraph head (numbering level or bullet): attributes (bits 4:
                // distance in HWPUNIT rather than percent, 5-8: number shape), width
                // adjustment, distance from the text, character shape.
                fun gap(b: Bytes, o: Int) = b.u16(o + 6).let { if ((b.u32(o) ushr 4) and 0x1 != 0) it / 10 else it }
                // NUMBERING: seven heads, each followed by its format string, then a
                // start number and each level's start.
                val numberings = records.filter { it.tag == NUMBERING }.map { r ->
                    val b = r.body
                    var o = 0
                    val heads = (0 until 7).map {
                        val at = o
                        val len = b.u16(o + 12).coerceAtMost(64)
                        val format = b.utf16(o + 14, len)
                        o += 14 + 2 * len
                        Triple(at, format, (b.u32(at) ushr 5) and 0xF)
                    }
                    val starts = (0 until 7).map { lv -> b.u32(o + 2 + 4 * lv).takeIf { b.size >= o + 6 + 4 * lv && it in 0..10_000 } ?: 1 }
                    NumberingDef(heads.mapIndexed { lv, (at, format, shape) -> NumberLevel(format, shape, starts[lv], gap(b, at)) })
                }
                // BULLET: a paragraph head, then the character (a symbol font's
                // private-use code for most of Hangul's own bullets).
                val bullets = records.filter { it.tag == BULLET }.map { r ->
                    val code = r.body.u16(12)
                    val glyph = if (code in 0xF000..0xF0FF) bulletGlyph(code, "wingdings") else if (code >= 32) code.toChar().toString() else ""
                    BulletDef(glyph, gap(r.body, 0))
                }
                return DocInfo(charShapes, paraAligns, borderFills, binData, headings, numberings, bullets)
            }
        }
    }

    // ---- BodyText ---------------------------------------------------------------

    private data class PageGeometry(
        val widthHu: Long, val heightHu: Long, val bodyXHu: Long, val bodyYHu: Long,
        val rightHu: Long = 8504, val bottomHu: Long = 4252, val headerHu: Long = 4252, val footerHu: Long = 4252
    )

    private fun HwpxElement.shifted(dx: Long, dy: Long): HwpxElement = shiftedBy(dx, dy)

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

    /** [pageNumber]: the number of the page before the section's first (numbering
     *  runs on through sections); after [read], that of its last page. */
    private class Section(val info: DocInfo, val picture: (Int) -> (() -> ByteArray?)?, var pageNumber: Int, val heads: ParaHead) {
        /** The numbering outline paragraphs use (the section definition's, 1-based). */
        private var outlineNumbering = 0
        var geometry = PageGeometry(59528, 84186, 8504, 8504)
        val pages = mutableListOf<HwpxPage>()

        private fun listParagraphs(ctrl: Record): List<HwpxElement> =
            ctrl.children.filter { it.tag == PARA_HEADER }.withIndex().flatMap { (k, p) -> paragraph(p, 1, inCell = true, firstInList = k == 0) }

        /** A header's or footer's paragraphs and their vertical alignment (LIST_HEADER flags bits 5-6). */
        private fun area(ctrl: Record) =
            DecorArea(listParagraphs(ctrl), ctrl.child(LIST_HEADER)?.body?.let { (it.u32(4) ushr 5) and 0x3 } ?: 0)

        fun read(roots: List<Record>, fallback: PageGeometry) {
            geometry = fallback
            var flow: PageFlow? = null
            val decor = DecorTimeline()
            for (para in roots.filter { it.tag == PARA_HEADER }) {
                val changes = mutableListOf<(PageDecor) -> PageDecor>()
                val hidings = mutableListOf<PageHiding>()
                val seen = HashSet<Pair<String, Int>>()
                var restart: Int? = null
                // The section definition rides on the first paragraph's controls.
                for (ctrl in para.all(CTRL_HEADER)) when (ctrl.ctrlId) {
                    "secd" -> {
                        ctrl.child(PAGE_DEF)?.let { geometry = pageGeometry(it.body) }
                        outlineNumbering = ctrl.body.u16(18)
                    }
                    // Header and footer: a list of paragraphs placed in the header
                    // (footer) area; attribute bits 0-1 say which pages (both, even, odd).
                    // Within one paragraph the first header (footer) for the same pages
                    // counts; a template had a second, empty one after its table.
                    "head" -> if (seen.add("head" to (ctrl.body.u32(4) and 0x3)))
                        area(ctrl).let { a -> changes += { it.withHeader(a, ctrl.body.u32(4) and 0x3) } }
                    "foot" -> if (seen.add("foot" to (ctrl.body.u32(4) and 0x3)))
                        area(ctrl).let { a -> changes += { it.withFooter(a, ctrl.body.u32(4) and 0x3) } }
                    // Page number position: attributes (bits 8-11: position), then the
                    // user, prefix, suffix and side characters.
                    // Hiding on this page (bits 0 header, 1 footer, 5 page number), and
                    // page numbers starting over (number type 0, then the number).
                    "pghd" -> ctrl.body.u32(4).let { a -> hidings += PageHiding(a and 0x1 != 0, a and 0x2 != 0, a and 0x20 != 0) }
                    "nwno" -> if (ctrl.body.u32(4) and 0xF == 0) restart = ctrl.body.u16(8)
                    "pgnp" -> {
                        val pos = (ctrl.body.u32(4) ushr 8) and 0xF
                        val side = ctrl.body.u16(14).takeIf { it in 33..0xFFFF }?.toChar()?.toString().orEmpty()
                        changes += { it.copy(pageNumberPosition = pos, pageNumberSide = side) }
                    }
                }
                val pageFlow = flow ?: PageFlow(geometry.run { heightHu - bodyYHu - bottomHu - footerHu }).also { flow = it }
                if (pageFlow.full) break
                // Break type (PARA_HEADER byte 11): 0x01 section, 0x04 page. A page
                // break's first line is at the top like the previous page's, so the
                // flag is what tells them apart.
                val forcedBreak = para.body.u8(11) and 0x05 != 0
                val firstLineY = lineSegs(para).firstOrNull()?.y
                var startPage = pageFlow.pageFor(firstLineY, forcedBreak)
                val elements = paragraph(para, 0)
                // A paragraph with no line to place still takes its line (see PageFlow.skipLine).
                val noLine = elements.all { it in placedFloating }
                if (noLine && firstLineY != null) {
                    pageFlow.skipLine(firstLineY, forcedBreak)
                    startPage = pageFlow.page
                }
                for ((k, el) in elements.withIndex()) {
                    pageFlow.add(el, startsPage = k == 0 && forcedBreak && !noLine, startsLine = el in lineStarts, floating = el in placedFloating, takesLines = el in takingLines,
                        movesWithText = el in fromParagraph)
                    if (k == 0 && !noLine) startPage = pageFlow.page
                }
                // These controls apply from the page their paragraph starts on
                // (hiding to that page only).
                changes.forEach { decor.change(startPage, it) }
                hidings.forEach { decor.hide(startPage, it) }
                restart?.let { decor.restart(startPage, it) }
            }
            val g = geometry
            val pg = PageDecor.Geometry(g.widthHu, g.heightHu, g.bodyXHu, g.bodyYHu, g.rightHu, g.bottomHu, g.headerHu, g.footerHu)
            for ((index, elements) in flow?.finish().orEmpty()) {
                val number = decor.number(index, pageNumber).also { pageNumber = it }
                pages += HwpxPage(g.widthHu, g.heightHu, decor.at(index).elements(number, pg, decor.hidingAt(index)) + elements.map { it.withPageNumber(number) }, g.bodyXHu, g.bodyYHu)
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
        /** [firstInList]: [para] starts a header, footer or text box (its top is the list's top). */
        fun paragraph(para: Record, depth: Int, inCell: Boolean = false, firstInList: Boolean = false): List<HwpxElement> {
            // Its number or bullet, counted even when the paragraph turns out empty.
            var head = heads.next(info.heading(para.body.u16(8)), outlineNumbering)
            val raw = para.child(PARA_TEXT)?.body
            val units = (raw?.size ?: 0) / 2
            val chars = CharArray(units) { raw!!.u16(2 * it).toChar() }
            val text = StringBuilder(units)
            val inlineAt = HashMap<Int, HwpxElement>()
            val tabWidths = HashMap<Int, Long>()
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
                        // An automatic number of type 0 (attribute bits 0-3) is the page number.
                        val pageNumber = ctrl?.ctrlId == "atno" && ctrl.body.u32(4) and 0xF == 0
                        // A tab's data starts with the width it took (u32, HWPUNIT).
                        if (c == 9 && span >= 3) tabWidths[text.length] = (chars[i + 1].code.toLong() or (chars[i + 2].code.toLong() shl 16))
                        if (obj != null) { inlineAt[text.length] = obj; text.append(OBJECT) }
                        else text.append(if (c == 9) '\t' else if (pageNumber) PAGE_NUMBER_MARK else FILLER)
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
            // The number goes in front of the paragraph's first piece of text.
            fun withHead(run: HwpxElement.TextRun): HwpxElement.TextRun =
                head?.let { (mark, gap) -> head = null; run.withHead(mark, gap) } ?: run
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
                val hasText = text.substring(start, end).any { it != FILLER && it != OBJECT && !it.isWhitespace() }
                // Objects set like characters in a line of text go in the line;
                // the rest (floating, or alone on their line) are placed on their own.
                val inLine = if (hasText) (start until end).mapNotNull { p -> inlineAt[p]?.takeIf { floatingOf[it] == null }?.let { p to it } }.toMap() else emptyMap()
                for (pos in start until end) {
                    val obj = inlineAt[pos] ?: continue
                    if (pos in inLine) continue
                    textRun(text, runStart, pos, runs, line, align, last, tabWidths, inLine)?.let { emit(withHead(it)) }
                    emit(place(obj, line, firstInList && k == 0 && hasText))
                    runStart = pos + 1
                }
                textRun(text, runStart, end, runs, line, align, last, tabWidths, inLine)?.let { emit(withHead(it)) }
            }
            return out
        }

        /** The line's text from [start] to [end], split where the character shape changes. */
        private fun textRun(
            text: StringBuilder, start: Int, end: Int, runs: List<Pair<Int, Int>>, line: Line, align: ParaAlign, last: Boolean,
            tabWidths: Map<Int, Long>, inLine: Map<Int, HwpxElement>
        ): HwpxElement.TextRun? {
            if (end <= start) return null
            val spans = mutableListOf<Span>()
            var pos = start
            while (pos < end) {
                val idx = runs.indexOfLast { it.first <= pos }.coerceAtLeast(0)
                val next = (runs.getOrNull(idx + 1)?.first ?: end).coerceIn(pos + 1, end)
                val style = info.charShape(runs.getOrNull(idx)?.second ?: 0)
                // Fillers and objects take no room, except objects set in the line;
                // keep tab offsets by dropping them in place.
                var from = pos
                for (k in pos..next) {
                    if (k < next && text[k] != FILLER && text[k] != OBJECT) continue
                    if (k > from) spans += spansOf(text.substring(from, k), from, style, tabWidths)
                    if (k < next) inLine[k]?.let { spans += inlineSpan(it, style) }
                    from = k + 1
                }
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
        /** [textFirstLine]: [line] is its paragraph's first and holds text. */
        private fun place(obj: HwpxElement, line: Line, textFirstLine: Boolean): HwpxElement {
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
                    else -> paragraphRelativeY(floating.vertOffset, floating.takesLines, floating.keepsBelowAnchor,
                        obj.extentHu, line.y, textFirstLine, line.height)
                }
            }
            val placed = obj.at(x, y)
            if (floating != null) placedFloating += placed
            if (floating?.takesLines == true) takingLines += placed
            if (floating != null && floating.vertRel != 0 && floating.vertRel != 1) fromParagraph += placed
            return placed
        }

        private class Floating(
            val vertRel: Int, val horzRel: Int, val vertOffset: Long, val horzOffset: Long,
            val takesLines: Boolean, val keepsBelowAnchor: Boolean
        )
        private val floatingOf = java.util.IdentityHashMap<HwpxElement, Floating>()
        /** Elements that begin a line (see PageFlow.add). */
        private val lineStarts = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<HwpxElement, Boolean>())
        /** Floating objects once placed. */
        private val placedFloating = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<HwpxElement, Boolean>())
        /** Placed floating objects that text wraps above and below. */
        private val takingLines = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<HwpxElement, Boolean>())
        /** Placed floating objects positioned from their paragraph. */
        private val fromParagraph = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<HwpxElement, Boolean>())

        /** A table or picture control; null for every other kind. */
        private fun control(ctrl: Record, depth: Int): HwpxElement? {
            if (depth > Limits.MAX_NESTING) return null
            val obj = when (ctrl.ctrlId) {
                "tbl " -> table(ctrl, depth)
                "gso " -> drawingObject(ctrl, depth)
                "eqed" -> equation(ctrl)
                else -> null
            } ?: return null
            // Common object properties: attribute bit 0 = treat as a character.
            val attr = ctrl.body.u32(4)
            if (attr and 0x1 == 0) {
                // Bits 3-4 vertical reference (paper, page, paragraph), 8-9 horizontal,
                // 13 moves with text, 21-23 text wrapping (1 = takes its own lines).
                val takesLines = (attr ushr 21) and 0x7 == 1
                val keepsBelow = takesLines && (attr ushr 13) and 0x1 != 0
                floatingOf[obj] = Floating((attr ushr 3) and 0x3, (attr ushr 8) and 0x3, ctrl.body.u32(8).toLong(), ctrl.body.u32(12).toLong(),
                    takesLines, keepsBelow)
            }
            return obj
        }

        /**
         * A drawing object (gso): a picture, or a line, rectangle, ellipse, polygon
         * or group of them, possibly holding a text box.
         */
        private fun drawingObject(ctrl: Record, depth: Int): HwpxElement? {
            val sc = ctrl.child(SHAPE_COMPONENT) ?: return null
            if (sc.ctrlId == "\$pic") return picture(ctrl)
            // A straight line has no height (or width).
            val w = ctrl.body.u32(16).toLong(); val h = ctrl.body.u32(20).toLong()
            if (w < 0 || h < 0 || w + h == 0L) return null
            val members = shapes(sc, IDENTITY, depth)
            if (members.isEmpty()) return null
            return if (members.size == 1 && members[0].xHu == 0L && members[0].yHu == 0L) members[0]
            else HwpxElement.Drawing(0, 0, w, h, DrawingKind.RECTANGLE, lineArgb = null, content = members)
        }

        /**
         * One shape component and, for a group, its members, in the coordinates of
         * the object's box. Each component maps its own original-size coordinates
         * into its parent's through its rendering matrices (a translation, then a
         * scale and a rotation per group level).
         */
        private fun shapes(sc: Record, parent: DoubleArray, depth: Int): List<HwpxElement> {
            if (depth > Limits.MAX_NESTING) return emptyList()
            val b = sc.body
            val id = sc.ctrlId
            // A top-level component repeats its control id.
            val o = if (b.u32(4) == b.u32(0)) 8 else 4
            val origW = b.u32(o + 12).toLong().coerceAtLeast(1)
            val origH = b.u32(o + 16).toLong().coerceAtLeast(1)
            var at = o + 42
            val levels = b.u16(at).coerceAtMost(16)
            var m = matrix(b, at + 2)
            at += 2 + 48
            repeat(levels) { m = multiply(multiply(m, matrix(b, at)), matrix(b, at + 48)); at += 96 }
            val total = multiply(parent, m)
            if (id == "\$con") {
                return sc.children.filter { it.tag == SHAPE_COMPONENT }.flatMap { shapes(it, total, depth + 1) }
            }
            // Line: color, width, attributes (bits 0-5 type, 0 = none), outline style.
            val lineType = b.u32(at + 8) and 0x3F
            val lineArgb = if (lineType == 0) null else colorRefOf(b.u32(at))
            val lineWidth = b.u32(at + 4).toLong().coerceIn(0, 10_000)
            val fillType = b.u32(at + 13)
            val fill = if (fillType and 0x1 != 0) colorRefOf(b.u32(at + 17)) else null

            fun mapped(points: List<Pair<Double, Double>>) = points.map { (x, y) -> apply(total, x, y) }
            val corners = listOf(0.0 to 0.0, origW.toDouble() to 0.0, origW.toDouble() to origH.toDouble(), 0.0 to origH.toDouble())
            val (kind, points, corner) = when (id) {
                "\$rec" -> Triple(DrawingKind.RECTANGLE, mapped(corners), sc.child(SHAPE_RECTANGLE)?.body?.u8(0) ?: 0)
                "\$ell" -> Triple(DrawingKind.ELLIPSE, mapped(corners), 0)
                "\$lin" -> {
                    val l = sc.child(SHAPE_LINE)?.body ?: return emptyList()
                    Triple(DrawingKind.LINE, mapped(listOf(l.u32(0).toDouble() to l.u32(4).toDouble(), l.u32(8).toDouble() to l.u32(12).toDouble())), 0)
                }
                "\$pol" -> {
                    val p = sc.child(SHAPE_POLYGON)?.body ?: return emptyList()
                    val n = p.u32(0).coerceIn(0, (p.size - 4) / 8)
                    Triple(DrawingKind.POLYGON, mapped((0 until n).map { p.u32(4 + 8 * it).toDouble() to p.u32(8 + 8 * it).toDouble() }), 0)
                }
                else -> return emptyList()
            }
            if (points.isEmpty()) return emptyList()
            val minX = points.minOf { it.first }; val minY = points.minOf { it.second }
            val boxW = (points.maxOf { it.first } - minX).toLong().coerceAtLeast(1)
            val boxH = (points.maxOf { it.second } - minY).toLong().coerceAtLeast(1)
            // A text box: a list header (paragraph count, flags with the vertical
            // alignment in bits 5-6, then left/right/top/bottom margins) and its paragraphs.
            val list = sc.child(LIST_HEADER)
            val content = if (list == null) emptyList() else {
                val lb = list.body
                sc.children.filter { it.tag == PARA_HEADER }.withIndex().flatMap { (k, p) -> paragraph(p, depth + 1, inCell = true, firstInList = k == 0) }
                    .map { it.shifted(lb.u16(8).toLong(), lb.u16(12).toLong()) }
            }
            val rel = points.map { (x, y) -> (x - minX).toLong() to (y - minY).toLong() }
            // An axis-aligned rectangle stays a rectangle; a rotated one becomes its outline.
            val axisAligned = kind != DrawingKind.RECTANGLE || rel.all { (x, y) -> (x == 0L || x == boxW) && (y == 0L || y == boxH) }
            return listOf(HwpxElement.Drawing(
                minX.toLong(), minY.toLong(), boxW, boxH,
                if (axisAligned) kind else DrawingKind.POLYGON, rel, corner,
                lineArgb, lineWidth, fill, content,
                list?.body?.let { (it.u32(4) ushr 5) and 0x3 } ?: 0
            ))
        }

        /**
         * An equation control: the object's size, and an EQEDIT record with
         * attributes, the script (u16 length, UTF-16), the base font size
         * (HWPUNIT, 1000 = 10 pt), the color and the baseline (percent of the
         * height, from the top).
         */
        private fun equation(ctrl: Record): HwpxElement.Equation? {
            val e = ctrl.child(EQEDIT)?.body ?: return null
            val w = ctrl.body.u32(16).toLong(); val h = ctrl.body.u32(20).toLong()
            if (w <= 0 || h <= 0) return null
            val len = e.u16(4)
            val script = e.utf16(6, len).takeIf { it.isNotBlank() } ?: return null
            val o = 6 + 2 * len
            return HwpxElement.Equation(
                0, 0, w, h, script,
                e.u32(o).toLong().takeIf { it in 100..100_000 } ?: 1000,
                colorRefOf(e.u32(o + 4)) ?: 0xFF000000.toInt(),
                e.i16(o + 8).takeIf { it in 1..100 } ?: 85
            )
        }

        private fun colorRefOf(v: Int): Int? = if (v ushr 24 != 0) null
            else (0xFF shl 24) or ((v and 0xFF) shl 16) or (v and 0xFF00) or ((v ushr 16) and 0xFF)

        private fun picture(ctrl: Record): HwpxElement.ImageEl? {
            val pic = findDeep(ctrl, SHAPE_PICTURE, 0) ?: return null
            val bytes = picture(pic.body.u16(71)) ?: return null
            val w = ctrl.body.u32(16).toLong(); val h = ctrl.body.u32(20).toLong()
            if (w <= 0 || h <= 0) return null
            // The visible part (left/top/right/bottom, bytes 44-59) is given within the
            // image's own size, which newer files save near the end of the record (its
            // last 9 bytes: width, height, a flag); older ones in the same units as the
            // corner points (bytes 12-43).
            val b = pic.body
            val dimW = if (b.size >= 91) b.u32(b.size - 9) else 0
            val dimH = if (b.size >= 91) b.u32(b.size - 5) else 0
            val (fullW, fullH) = if (dimW > 0 && dimH > 0) dimW to dimH else (b.u32(20) - b.u32(12)) to (b.u32(32) - b.u32(16))
            val crop = ImageCrop.of(b.u32(44).toDouble(), b.u32(48).toDouble(), b.u32(52).toDouble(), b.u32(56).toDouble(),
                fullW.toDouble(), fullH.toDouble())
            return HwpxElement.ImageEl(0, 0, w, h, zipEntry = null, bytes = bytes, crop = crop)
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
                        // then column, row, spans, size, margins, border/fill id. A margin
                        // of 0xFFFF means the table's own (TABLE bytes 10-17: left,
                        // right, top, bottom).
                        fun margin(o: Int, tableOffset: Int) = b.u16(o).let { if (it == 0xFFFF) t.u16(tableOffset) else it }.toLong()
                        cells += Cell(b.u16(8), b.u16(10), b.u16(12).coerceAtLeast(1), b.u16(14).coerceAtLeast(1),
                            b.u32(16).toLong(), b.u32(20).toLong(), margin(24, 10), margin(26, 12), margin(28, 14),
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
            val spans = ArrayList<GridSpan>()
            val rowHeights = HashMap<Int, Long>()
            val contentHeights = HashMap<Int, Long>()
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
                }.let { clearOfTables(it) { el -> el in placedFloating } }.map { it.shifted(cell.marginLeft, cell.marginTop) }.map { el ->
                    // A line can't be wider than its cell; some writers save the
                    // page's width for lines in cells.
                    val inner = cell.w - cell.marginLeft - cell.marginRight
                    if (el is HwpxElement.TextRun && inner > 0 && el.xHu + el.widthHu > cell.marginLeft + inner)
                        el.copy(widthHu = (cell.marginLeft + inner - el.xHu).coerceAtLeast(inner / 4)) else el
                }
                if (cell.colSpan == 1) colWidths[cell.col] = maxOf(colWidths[cell.col] ?: 0L, cell.w)
                spans += GridSpan(cell.col, cell.colSpan, cell.w)
                // A cell's declared size is what it had when its row last fit on a
                // page; content running over pages reaches further than that. Only
                // lines that follow on from each other count: a stray line saved far
                // below the rest isn't content to make room for.
                var content = cell.marginTop
                for (e in runs.sortedBy { it.yHu }) {
                    if (e.yHu > content + 10_000) break
                    content = maxOf(content, e.yHu + e.extentHu + cell.marginTop)
                }
                if (cell.rowSpan == 1) {
                    rowHeights[cell.row] = maxOf(rowHeights[cell.row] ?: 0L, cell.h)
                    // A cell declaring no height at all is a writer's leftover, not a size.
                    if (cell.h > 0) contentHeights[cell.row] = maxOf(contentHeights[cell.row] ?: 0L, content)
                }
                grid[cell.row][cell.col] = TableCell(cell.colSpan, cell.rowSpan, runs,
                    borderFill = info.borderFill(cell.borderFill), vAlign = cell.vAlign)
            }
            val width = ctrl.body.u32(16).toLong()
            val declared = ctrl.body.u32(20).toLong()
            // A table running over several pages declares only part of its height;
            // its rows say how tall it really is.
            val known = rowHeights.values.takeIf { it.isNotEmpty() }?.average()?.toLong() ?: (declared / rowCnt)
            val declaredRows = (0 until rowCnt).map { rowHeights[it] ?: known }
            // Rows short of the declared height are stretched to it (as drawn); a
            // row whose content runs well past that (over pages, or a cell declared
            // smaller than its own margins) takes the content's.
            val stretch = (declared.toDouble() / declaredRows.sum().coerceAtLeast(1)).coerceAtLeast(1.0)
            val shown = declaredRows.map { (it * stretch).toLong() }
            // "Well past": by a couple of lines, or to over twice the height (a nested
            // table declared 282 for each row of a 1282-tall line).
            val grown = shown.indices.filter { (contentHeights[it] ?: 0L).let { c -> c > shown[it] + 2000 || c > shown[it] * 2 } }.toSet()
            var rowsHu = shown.mapIndexed { r, h -> if (r in grown) contentHeights[r]!! else h }
            // When everything still fits the table's own height (the height Hangul
            // laid the text after it out with), the other rows give up their
            // stretch, down to their content, to keep it.
            val need = shown.indices.map { r -> if (r in grown) rowsHu[r] else maxOf(declaredRows[r], contentHeights[r] ?: 0L) }
            if (grown.isNotEmpty() && rowsHu.sum() > declared && need.sum() <= declared) {
                val room = declared - grown.sumOf { rowsHu[it] }
                fun fitted(f: Double) = rowsHu.indices.map { r -> if (r in grown) rowsHu[r] else maxOf(need[r], (shown[r] * f).toLong()) }
                var lo = 0.0; var hi = 1.0
                repeat(30) { val mid = (lo + hi) / 2; if (fitted(mid).sum() - grown.sumOf { rowsHu[it] } > room) hi = mid else lo = mid }
                rowsHu = fitted(lo)
            }
            return HwpxElement.Table(
                xHu = 0, yHu = 0, widthHu = width, heightHu = maxOf(declared, rowsHu.sum()),
                // Columns only spanned cells cover get their width from where those cells end.
                columnWidthsHu = gridSizes(spans, colCnt, width) ?: (0 until colCnt).map { colWidths[it] ?: (width / colCnt) },
                rowHeightsHu = rowsHu,
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
