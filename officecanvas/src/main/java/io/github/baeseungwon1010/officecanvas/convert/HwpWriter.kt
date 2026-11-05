package io.github.baeseungwon1010.officecanvas.convert

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.Deflater
import kotlin.math.roundToLong

/**
 * Writes a [ConvDocument] as a Hangul 5.0 binary document (.hwp): an OLE2 compound
 * file ([CfbWriter]) with the FileHeader, DocInfo (fonts, border/fills, character,
 * tab and paragraph shapes, the style) and one BodyText section stream per page
 * setup, records compressed as Hangul writes them, pictures in BinData. Record
 * layouts follow version 5.0.3.0 as Hangul saves it: paragraphs (with one line
 * segment each; Hangul lays the text out again), tables, pictures, the header and
 * footer with a page number.
 */
internal class HwpWriter(private val out: OutputStream, private val defaultFont: String) : PageSink {

    private val cfb = CfbWriter()
    private val sections = ArrayList<Records>()
    private var body = Records()
    private var section: ConvPage? = null
    private var pendingSecd = false
    private var header: List<ConvParagraph>? = null
    private var footer: List<ConvParagraph>? = null
    private var headerTop = 0.0
    private var footerBottom = 0.0
    private var instance = 0x12340000
    private val pictures = ArrayList<Pair<String, ByteArray>>() // extension, data

    private val fonts = LinkedHashMap<String, Int>()
    private val charShapes = LinkedHashMap<String, Int>()
    private val paraShapes = LinkedHashMap<String, Int>()
    private val tabDefs = LinkedHashMap<String, Int>()
    private val borderFills = LinkedHashMap<String, Int>()

    init {
        font(defaultFont)
        borderFill(null, null, null, null, null) // 1: none
        tabDef(emptyList())
        charShape(ConvRun("", defaultFont, 10.0))
        paraShape(ConvParagraph(emptyList()))
    }

    override fun page(page: ConvPage) {
        if (header == null && page.header.isNotEmpty()) { header = page.header; headerTop = page.headerTop }
        if (footer == null && page.footer.isNotEmpty()) { footer = page.footer; footerBottom = page.footerBottom }
        val prev = section
        val newSection = prev == null || !sameSetup(prev, page)
        if (newSection) {
            if (prev != null) sections += body
            body = Records()
            section = page
            pendingSecd = true
        }
        var first = true
        for (b in page.blocks.ifEmpty { listOf(ConvParagraph(emptyList())) }) {
            val breakBefore = first && !newSection
            val floats = if (first) page.floating else emptyList()
            when (b) {
                is ConvParagraph -> paragraph(body, 0, b, breakBefore, floats)
                is ConvTable -> {
                    if (pendingSecd || floats.isNotEmpty() || breakBefore) paragraph(body, 0, ConvParagraph(emptyList()), breakBefore, floats)
                    tableParagraph(body, 0, b)
                }
            }
            first = false
        }
    }

    private fun sameSetup(a: ConvPage, b: ConvPage) = Math.abs(a.width - b.width) < 1 && Math.abs(a.height - b.height) < 1 &&
        Math.abs(a.marginLeft - b.marginLeft) < 1 && Math.abs(a.marginTop - b.marginTop) < 1 && Math.abs(a.marginRight - b.marginRight) < 1

    // ---- paragraphs -------------------------------------------------------------

    /** Paragraph text: characters, and controls taking 8 WCHARs each. */
    private class Text {
        val chars = ByteArrayOutputStream()
        var length = 0
        var mask = 0
        val shapes = ArrayList<Pair<Int, Int>>() // position, char shape
        fun char(c: Char) { chars.write(c.code and 0xFF); chars.write(c.code shr 8); length++ }
        /** An extended control (section, columns, table, picture, header...). */
        fun control(code: Int, id: String) {
            val b = Bytes()
            b.u16(code); b.u32(chid(id)); b.zeros(8); b.u16(code)
            chars.write(b.bytes()); length += 8; mask = mask or (1 shl code)
        }
        fun tab(width: Long) {
            val b = Bytes()
            b.u16(9); b.u32(width.toInt()); b.u8(0); b.u8(2); b.u16(0x20); b.u16(0x20); b.u16(0x20); b.u16(9)
            chars.write(b.bytes()); length += 8; mask = mask or (1 shl 9)
        }
        fun shape(id: Int) { if (shapes.lastOrNull()?.first == length) shapes[shapes.size - 1] = length to id else if (shapes.lastOrNull()?.second != id) shapes += length to id }
    }

    /**
     * A paragraph at [level]: its text with the section's controls first when one
     * starts here, the floating pictures of its page, and its picture if it has one.
     */
    private fun paragraph(into: Records, level: Int, p: ConvParagraph, breakBefore: Boolean = false, floats: List<ConvImage> = emptyList()) {
        val t = Text()
        val firstShape = charShape(p.runs.firstOrNull() ?: ConvRun("", defaultFont, 10.0))
        t.shape(firstShape)
        val children = ArrayList<(Records, Int) -> Unit>()
        val secd = pendingSecd && level == 0
        if (secd) {
            pendingSecd = false
            t.control(2, "secd"); children += { r, l -> sectionDef(r, l) }
            t.control(2, "cold"); children += { r, l -> columnsDef(r, l) }
            header?.let { h -> t.control(16, "head"); children += { r, l -> headerFooter(r, l, "head", h) } }
            footer?.let { f -> t.control(16, "foot"); children += { r, l -> headerFooter(r, l, "foot", f) } }
        }
        for (img in floats) { t.control(11, "gso "); children += { r, l -> picture(r, l, img, floating = true) } }
        p.image?.let { img -> t.control(11, "gso "); children += { r, l -> picture(r, l, img, floating = false) } }
        for (run in p.runs) {
            if (run.text.isEmpty()) continue
            t.shape(charShape(run))
            if (run.field == "PAGE") {
                t.control(18, "atno"); children += { r, l -> autoNumber(r, l) }
                continue
            }
            for (ch in run.text) when (ch) {
                '\t' -> t.tab(4000)
                '\n' -> t.char('\n')
                else -> if (ch.code >= 0x20) t.char(ch)
            }
        }
        t.char('\r')
        paraRecords(into, level, paraShape(p), t, breakBefore, secd, p.runs.maxOfOrNull { it.size } ?: 10.0, p.lineHeight)
        for (c in children) c(into, level + 1)
    }

    private fun paraRecords(into: Records, level: Int, shape: Int, t: Text, breakBefore: Boolean, sectionStart: Boolean, size: Double, pitch: Double?) {
        val h = Bytes()
        h.u32(t.length); h.u32(t.mask); h.u16(shape); h.u8(0)
        h.u8(if (sectionStart) 3 else if (breakBefore) 4 else 0)
        h.u16(t.shapes.size); h.u16(0); h.u16(1); h.u32(0)
        into.add(PARA_HEADER, level, h.bytes())
        // A paragraph with only its end mark saves no text.
        if (t.length > 1) into.add(PARA_TEXT, level + 1, t.chars.toByteArray())
        val cs = Bytes()
        for ((pos, id) in t.shapes) { cs.u32(pos); cs.u32(id) }
        into.add(PARA_CHAR_SHAPE, level + 1, cs.bytes())
        val line = hu(size)
        val pitchHu = pitch?.let { hu(it) } ?: (line * 13 / 10)
        val seg = Bytes()
        seg.u32(0); seg.u32(0); seg.u32(line.toInt()); seg.u32(line.toInt()); seg.u32((line * 85 / 100).toInt())
        seg.u32((pitchHu - line).coerceAtLeast(0).toInt()); seg.u32(0); seg.u32(bodyWidth().toInt()); seg.u32(0x60000)
        into.add(PARA_LINE_SEG, level + 1, seg.bytes())
    }

    private fun bodyWidth(): Long = section?.let { hu(it.width - it.marginLeft - it.marginRight) } ?: 42520

    // ---- section controls -----------------------------------------------------

    private fun sectionDef(r: Records, level: Int) {
        val p = section!!
        val b = Bytes()
        b.u32(chid("secd")); b.u32(0); b.u16(1134); b.u16(0); b.u16(0); b.u32(8000); b.u16(1)
        b.u16(0); b.u16(0); b.u16(0); b.u16(0); b.u32(0); b.u32(0); b.u16(0)
        r.add(CTRL_HEADER, level, b.bytes())
        // A sideways page keeps its upright size, marked landscape.
        val landscape = p.width > p.height
        val pd = Bytes()
        pd.u32(hu(if (landscape) p.height else p.width).toInt()); pd.u32(hu(if (landscape) p.width else p.height).toInt())
        pd.u32(hu(p.marginLeft).toInt()); pd.u32(hu(p.marginRight).toInt())
        // Hangul's top margin ends where the header area starts; the body starts below both.
        val top = hu(p.marginTop); val bottom = hu(p.marginBottom)
        val topMargin = if (header != null) minOf(hu(headerTop), top) else top
        val bottomMargin = if (footer != null) minOf(hu(footerBottom), bottom) else bottom
        pd.u32(topMargin.toInt()); pd.u32(bottomMargin.toInt())
        pd.u32((top - topMargin).toInt()); pd.u32((bottom - bottomMargin).toInt())
        pd.u32(0); pd.u32(if (landscape) 1 else 0)
        r.add(PAGE_DEF, level + 1, pd.bytes())
        val note = hex("00 00 00 00 00 00 00 00 29 00 01 00 ff ff ff ff 52 03 37 02 1b 01 01 01 00 00 00 00")
        r.add(FOOTNOTE_SHAPE, level + 1, note)
        r.add(FOOTNOTE_SHAPE, level + 1, note)
        repeat(3) { r.add(PAGE_BORDER_FILL, level + 1, hex("01 00 00 00 89 05 89 05 89 05 89 05 01 00")) }
    }

    private fun columnsDef(r: Records, level: Int) {
        val b = Bytes()
        b.u32(chid("cold")); b.u16(0x1004); b.u16(0); b.u16(0); b.u8(0); b.u8(0); b.u32(0)
        r.add(CTRL_HEADER, level, b.bytes())
    }

    private fun headerFooter(r: Records, level: Int, kind: String, paras: List<ConvParagraph>) {
        val b = Bytes()
        b.u32(chid(kind)); b.u32(0); b.u32(instance++)
        r.add(CTRL_HEADER, level, b.bytes())
        val list = Bytes()
        list.u16(paras.size); list.u16(0); list.u32(if (kind == "foot") 64 else 0)
        list.u32(bodyWidth().toInt()); list.u32(hu(maxOf(if (kind == "head") (section?.marginTop ?: 0.0) - headerTop else (section?.marginBottom ?: 0.0) - footerBottom, 10.0)).toInt())
        list.u8(0); list.u8(0); list.zeros(16)
        r.add(LIST_HEADER, level + 1, list.bytes())
        for (p in paras) paragraph(r, level + 1, ConvParagraph(p.runs, p.align, p.leftIndent, p.rightIndent, p.firstLine, 0.0, p.lineHeight, null, p.tabs, rightTabs = p.rightTabs))
    }

    private fun autoNumber(r: Records, level: Int) {
        val b = Bytes()
        b.u32(chid("atno")); b.u32(0); b.u16(1); b.u16(0); b.u16(0); b.u16(0)
        r.add(CTRL_HEADER, level, b.bytes())
    }

    // ---- tables -----------------------------------------------------------------

    private fun tableParagraph(into: Records, level: Int, table: ConvTable) {
        val t = Text()
        t.shape(0)
        t.control(11, "tbl ")
        t.char('\r')
        val shape = paraShape(ConvParagraph(emptyList(), spaceBefore = table.spaceBefore))
        paraRecords(into, level, shape, t, false, false, 10.0, null)
        tableControl(into, level + 1, table)
    }

    private fun tableControl(r: Records, level: Int, table: ConvTable) {
        val w = table.colWidths.sumOf { hu(it) }; val h = table.rowHeights.sumOf { hu(it) }
        r.add(CTRL_HEADER, level, common("tbl ", TABLE_FLAGS, 0, hu(table.x).coerceAtLeast(-200000), w, h))
        val rows = table.rowHeights.size
        val byRow = table.cells.groupBy { it.row }
        val tb = Bytes()
        tb.u32(0x04000006); tb.u16(rows); tb.u16(table.colWidths.size); tb.u16(0)
        tb.u16(141); tb.u16(141); tb.u16(0); tb.u16(0)
        for (row in 0 until rows) tb.u16(byRow[row]?.size ?: 0)
        tb.u16(1); tb.u16(0)
        r.add(TABLE, level + 1, tb.bytes())
        for (row in 0 until rows) for (c in byRow[row].orEmpty().sortedBy { it.col }) {
            val cw = table.colWidths.subList(c.col, minOf(c.col + c.colSpan, table.colWidths.size)).sumOf { hu(it) }
            val ch = table.rowHeights.subList(c.row, minOf(c.row + c.rowSpan, rows)).sumOf { hu(it) }
            val paras = c.blocks.filterIsInstance<ConvParagraph>().ifEmpty { listOf(ConvParagraph(emptyList())) }
            val lh = Bytes()
            lh.u16(paras.size); lh.u16(0); lh.u32(when (c.vAlign) { 1 -> 32; 2 -> 64; else -> 0 })
            lh.u16(c.col); lh.u16(c.row); lh.u16(c.colSpan); lh.u16(c.rowSpan)
            lh.u32(cw.toInt()); lh.u32(ch.toInt()); lh.u16(141); lh.u16(141); lh.u16(0); lh.u16(0)
            lh.u16(borderFill(c.top, c.bottom, c.left, c.right, c.fill)); lh.u32(cw.toInt()); lh.zeros(9)
            r.add(LIST_HEADER, level + 1, lh.bytes())
            for (p in paras) paragraph(r, level + 1, p)
        }
    }

    /** An object's common header (table or picture): placement, size and margins. */
    private fun common(id: String, flags: Int, y: Long, x: Long, w: Long, h: Long): ByteArray {
        val b = Bytes()
        b.u32(chid(id)); b.u32(flags); b.u32(y.toInt()); b.u32(x.toInt()); b.u32(w.toInt()); b.u32(h.toInt())
        b.u16(0); b.u16(0); b.zeros(8); b.u32(instance++); b.u16(0); b.u16(0); b.u16(0)
        return b.bytes()
    }

    // ---- pictures ---------------------------------------------------------------

    private fun picture(r: Records, level: Int, img: ConvImage, floating: Boolean) {
        pictures += (if (img.png) "png" else "jpg") to img.data
        val binId = pictures.size
        val w = hu(img.width); val h = hu(img.height)
        r.add(CTRL_HEADER, level, if (floating) common("gso ", FLOAT_PICTURE_FLAGS, hu(img.y), hu(img.x), w, h) else common("gso ", PICTURE_FLAGS, 0, 0, w, h))
        val sc = Bytes()
        sc.u32(chid("\$pic")); sc.u32(chid("\$pic")); sc.u32(0); sc.u32(0); sc.u16(0); sc.u16(1)
        sc.u32(w.toInt()); sc.u32(h.toInt()); sc.u32(w.toInt()); sc.u32(h.toInt()); sc.u32(0); sc.u16(0)
        sc.u32((w / 2).toInt()); sc.u32((h / 2).toInt())
        sc.u16(1)
        for (v in doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)) sc.f64(v) // translation
        for (v in doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)) sc.f64(v) // scale
        for (v in doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)) sc.f64(v) // rotation
        r.add(SHAPE_COMPONENT, level + 1, sc.bytes())
        val pic = Bytes()
        pic.u32(0); pic.u32(0); pic.u32(0)
        pic.u32(0); pic.u32(0); pic.u32(w.toInt()); pic.u32(0); pic.u32(w.toInt()); pic.u32(h.toInt()); pic.u32(0); pic.u32(h.toInt())
        pic.u32(0); pic.u32(0); pic.u32(w.toInt()); pic.u32(h.toInt())
        pic.zeros(8)
        pic.u8(0); pic.u8(0); pic.u8(0); pic.u16(binId)
        pic.u8(0); pic.u32(instance++); pic.u32(0)
        pic.u32(w.toInt()); pic.u32(h.toInt()); pic.u8(0)
        r.add(SHAPE_PICTURE, level + 2, pic.bytes())
    }

    // ---- DocInfo ----------------------------------------------------------------

    private fun font(name: String): Int = fonts.getOrPut(name.ifBlank { defaultFont }) { fonts.size }

    private fun charShape(r: ConvRun): Int {
        val key = "${font(r.font)}|${hu(r.size)}|${r.bold}|${r.italic}|${r.underline}|${r.color and 0xFFFFFF}|${r.script}"
        return charShapes.getOrPut(key) { charShapes.size }
    }

    private fun paraShape(p: ConvParagraph): Int {
        val align = when (p.align) { ConvAlign.JUSTIFY -> 0; ConvAlign.LEFT -> 1; ConvAlign.RIGHT -> 2; ConvAlign.CENTER -> 3 }
        val size = p.runs.maxOfOrNull { it.size } ?: 10.0
        val percent = p.lineHeight?.let { (it / size * 100).roundToLong().coerceIn(80, 400) } ?: 130
        val fill = p.shading?.let { borderFill(null, null, null, null, it) } ?: 1
        val key = "$align|${hu(p.leftIndent)}|${hu(p.rightIndent)}|${hu(p.firstLine)}|${hu(p.spaceBefore)}|$percent|${tabDef(p.tabs, p.rightTabs)}|$fill"
        return paraShapes.getOrPut(key) { paraShapes.size }
    }

    /** Stops as "pos" (left) or "pos:R" (right), in order. */
    private fun tabDef(stops: List<Double>, right: List<Double> = emptyList()): Int =
        tabDefs.getOrPut((stops.map { hu(it) to "" } + right.map { hu(it) to ":R" }).sortedBy { it.first }.joinToString(",") { "${it.first}${it.second}" }) { tabDefs.size }

    /** A border/fill's id (1-based, as Hangul refers to them). */
    private fun borderFill(top: ConvBorder?, bottom: ConvBorder?, left: ConvBorder?, right: ConvBorder?, fill: Int?): Int {
        fun b(x: ConvBorder?) = x?.let { "${Math.round(it.width * 100)}:${it.color and 0xFFFFFF}" } ?: "-"
        return borderFills.getOrPut("${b(left)}|${b(right)}|${b(top)}|${b(bottom)}|${fill?.and(0xFFFFFF) ?: "-"}") { borderFills.size + 1 }
    }

    private fun docInfo(): ByteArray {
        val r = Records()
        val props = Bytes()
        props.u16(sections.size); repeat(6) { props.u16(1) }; props.u32(0); props.u32(0); props.u32(0)
        r.add(DOCUMENT_PROPERTIES, 0, props.bytes())
        val ids = Bytes()
        ids.u32(pictures.size); repeat(7) { ids.u32(fonts.size) }
        ids.u32(borderFills.size); ids.u32(charShapes.size); ids.u32(tabDefs.size); ids.u32(0); ids.u32(0)
        ids.u32(paraShapes.size); ids.u32(1); ids.u32(0)
        r.add(ID_MAPPINGS, 0, ids.bytes())
        for ((i, pic) in pictures.withIndex()) {
            val b = Bytes()
            b.u16(1); b.u16(i + 1); b.bstr(pic.first)
            r.add(BIN_DATA, 1, b.bytes())
        }
        repeat(7) { for (name in fonts.keys) { val b = Bytes(); b.u8(1); b.bstr(name); r.add(FACE_NAME, 1, b.bytes()) } }
        for (key in borderFills.keys) {
            val parts = key.split('|')
            val b = Bytes()
            b.u16(0)
            for (side in parts.take(4)) {
                if (side == "-") { b.u8(0); b.u8(0); b.u32(0) }
                else { val (w, c) = side.split(':'); b.u8(1); b.u8(widthIndex(w.toDouble() / 100)); b.u32(bgr(c.toInt())) }
            }
            b.u8(1); b.u8(0); b.u32(0) // diagonal
            b.u32(1)
            val fill = parts[4].takeIf { it != "-" }?.toInt()
            b.u32(fill?.let { bgr(it) } ?: -1); b.u32(0xFF000000.toInt()); b.u32(-1)
            b.zeros(5)
            r.add(BORDER_FILL, 1, b.bytes())
        }
        for (key in charShapes.keys) {
            val (f, size, bold, italic, under, color, script) = key.split('|')
            val b = Bytes()
            repeat(7) { b.u16(f.toInt()) }; repeat(7) { b.u8(100) }; repeat(7) { b.u8(0) }; repeat(7) { b.u8(100) }; repeat(7) { b.u8(0) }
            b.u32(size.toInt())
            var flags = 0
            if (italic == "true") flags = flags or 1
            if (bold == "true") flags = flags or 2
            if (under == "true") flags = flags or (1 shl 2)
            if (script == "1") flags = flags or (1 shl 15)
            if (script == "-1") flags = flags or (1 shl 16)
            b.u32(flags); b.u8(10); b.u8(10)
            b.u32(bgr(color.toInt())); b.u32(bgr(color.toInt())); b.u32(-1); b.u32(0xB2B2B2)
            b.u16(1); b.u32(0)
            r.add(CHAR_SHAPE, 1, b.bytes())
        }
        for (key in tabDefs.keys) {
            val b = Bytes()
            val stops = if (key.isEmpty()) emptyList() else key.split(',')
            b.u32(0); b.u32(stops.size)
            // Position, kind (0 left, 1 right), fill, padding.
            for (s in stops) { b.u32(s.removeSuffix(":R").toInt()); b.u8(if (s.endsWith(":R")) 1 else 0); b.u8(0); b.u16(0) }
            r.add(TAB_DEF, 1, b.bytes())
        }
        for (key in paraShapes.keys) {
            val (align, left, right, indent, prev, percent, tab) = key.split('|')
            val fill = key.split('|')[7]
            val b = Bytes()
            b.u32(align.toInt() shl 2)
            // Margins and spacing are saved doubled; the indent isn't.
            b.u32((left.toLong() * 2).toInt()); b.u32((right.toLong() * 2).toInt()); b.u32(indent.toInt())
            b.u32((prev.toLong() * 2).toInt()); b.u32(0); b.u32(percent.toInt())
            b.u16(tab.toInt()); b.u16(0); b.u16(fill.toInt()); b.zeros(8)
            b.u32(0); b.u32(0); b.u32(percent.toInt())
            r.add(PARA_SHAPE, 1, b.bytes())
        }
        val style = Bytes()
        style.bstr("바탕글"); style.bstr("Normal"); style.u8(0); style.u8(0); style.u16(1042); style.u16(0); style.u16(0); style.u16(0)
        r.add(STYLE, 1, style.bytes())
        r.add(FORBIDDEN_CHAR, 1, ByteArray(16))
        r.add(COMPATIBLE_DOCUMENT, 0, ByteArray(4))
        r.add(LAYOUT_COMPATIBILITY, 1, ByteArray(20))
        return r.bytes()
    }

    private operator fun <T> List<T>.component6() = this[5]
    private operator fun <T> List<T>.component7() = this[6]

    override fun finish() {
        section?.let { sections += body }
        val head = ByteArray(256)
        "HWP Document File".toByteArray(Charsets.US_ASCII).copyInto(head)
        // Version 5.0.3.0; compressed; encryption version 4.
        byteArrayOf(0, 3, 0, 5, 1, 0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 0).copyInto(head, 32)
        cfb.add("FileHeader", head)
        cfb.add("DocInfo", deflate(docInfo()))
        for ((i, s) in sections.withIndex()) cfb.add("BodyText/Section$i", deflate(s.bytes()))
        for ((i, pic) in pictures.withIndex()) cfb.add("BinData/BIN%04X.%s".format(i + 1, pic.first), deflate(pic.second))
        cfb.add("\u0005HwpSummaryInformation", summary())
        cfb.add("PrvText", previewText())
        cfb.write(out)
    }

    /** The preview text stream (left empty). */
    private fun previewText(): ByteArray = ByteArray(0)

    /** An empty summary property set, under Hangul's format id. */
    private fun summary(): ByteArray {
        val fmtid = hex("60 b6 a2 9f 61 10 d4 11 b4 c6 00 60 97 c0 9d 8c")
        val b = Bytes()
        b.u16(0xFFFE); b.u16(0); b.u32(0x00020206); b.raw(fmtid); b.u32(1); b.raw(fmtid); b.u32(48)
        b.u32(8); b.u32(0)
        return b.bytes()
    }

    // ---- records and bytes ----------------------------------------------------

    private class Records {
        private val out = ByteArrayOutputStream()
        fun add(tag: Int, level: Int, data: ByteArray) {
            val size = data.size
            val h = tag or (level shl 10) or ((if (size >= 0xFFF) 0xFFF else size) shl 20)
            val b = Bytes(); b.u32(h); if (size >= 0xFFF) b.u32(size)
            out.write(b.bytes()); out.write(data)
        }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private class Bytes {
        private val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun u32(v: Int) { u16(v and 0xFFFF); u16((v ushr 16) and 0xFFFF) }
        fun f64(v: Double) { val l = java.lang.Double.doubleToLongBits(v); u32(l.toInt()); u32((l ushr 32).toInt()) }
        fun zeros(n: Int) = out.write(ByteArray(n))
        fun raw(b: ByteArray) = out.write(b)
        fun bstr(s: String) { u16(s.length); for (c in s) u16(c.code) }
        fun bytes(): ByteArray = out.toByteArray()
    }

    companion object {
        private const val DOCUMENT_PROPERTIES = 16
        private const val ID_MAPPINGS = 17
        private const val BIN_DATA = 18
        private const val FACE_NAME = 19
        private const val BORDER_FILL = 20
        private const val CHAR_SHAPE = 21
        private const val TAB_DEF = 22
        private const val PARA_SHAPE = 25
        private const val STYLE = 26
        private const val COMPATIBLE_DOCUMENT = 30
        private const val LAYOUT_COMPATIBILITY = 31
        private const val FORBIDDEN_CHAR = 94
        private const val PARA_HEADER = 66
        private const val PARA_TEXT = 67
        private const val PARA_CHAR_SHAPE = 68
        private const val PARA_LINE_SEG = 69
        private const val CTRL_HEADER = 71
        private const val LIST_HEADER = 72
        private const val PAGE_DEF = 73
        private const val FOOTNOTE_SHAPE = 74
        private const val PAGE_BORDER_FILL = 75
        private const val SHAPE_COMPONENT = 76
        private const val TABLE = 77
        private const val SHAPE_PICTURE = 85

        /** As Hangul sets them: a table placed from its paragraph and column, text above and below it. */
        private const val TABLE_FLAGS = 136978960
        /** A picture set in the line like a character. */
        private const val PICTURE_FLAGS = 69870097
        /** A picture placed from the page's corner, behind the text. */
        private const val FLOAT_PICTURE_FLAGS = (1 shl 26) or (2 shl 21) or (2 shl 18) or (4 shl 15)

        /** A control's id as Hangul stores it: its four letters, the first in the high byte. */
        fun chid(id: String): Int = (id[0].code shl 24) or (id[1].code shl 16) or (id[2].code shl 8) or id[3].code

        fun hu(pt: Double): Long = (pt * 100).roundToLong().coerceIn(-1_000_000, 10_000_000)

        private fun bgr(rgb: Int): Int = ((rgb and 0xFF) shl 16) or (rgb and 0xFF00) or ((rgb shr 16) and 0xFF)

        /** A border width in points as Hangul's width steps (0.1 mm ... 5 mm). */
        private fun widthIndex(pt: Double): Int {
            val steps = doubleArrayOf(0.1, 0.12, 0.15, 0.2, 0.25, 0.3, 0.4, 0.5, 0.6, 0.7, 1.0, 1.5, 2.0, 3.0, 4.0, 5.0)
            val want = pt * 25.4 / 72
            return steps.indices.minByOrNull { Math.abs(steps[it] - want) }!!
        }

        private fun hex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()

        private fun deflate(data: ByteArray): ByteArray {
            val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            try {
                d.setInput(data); d.finish()
                val out = ByteArrayOutputStream(data.size / 3 + 64)
                val buf = ByteArray(64 * 1024)
                while (!d.finished()) out.write(buf, 0, d.deflate(buf))
                return out.toByteArray()
            } finally { d.end() }
        }
    }
}
