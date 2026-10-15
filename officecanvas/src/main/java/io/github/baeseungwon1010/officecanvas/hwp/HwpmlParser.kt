package io.github.baeseungwon1010.officecanvas.hwp

import io.github.baeseungwon1010.officecanvas.hwpx.Border
import io.github.baeseungwon1010.officecanvas.hwpx.BorderFill
import io.github.baeseungwon1010.officecanvas.hwpx.CharStyle
import io.github.baeseungwon1010.officecanvas.hwpx.DEFAULT_TAB_HU
import io.github.baeseungwon1010.officecanvas.hwpx.GridSpan
import io.github.baeseungwon1010.officecanvas.hwpx.HWPUNIT_PER_INCH
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxDoc
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxElement
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxPage
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxRenderer
import io.github.baeseungwon1010.officecanvas.hwpx.PageFlow
import io.github.baeseungwon1010.officecanvas.hwpx.ParaAlign
import io.github.baeseungwon1010.officecanvas.hwpx.Span
import io.github.baeseungwon1010.officecanvas.hwpx.TableCell
import io.github.baeseungwon1010.officecanvas.hwpx.at
import io.github.baeseungwon1010.officecanvas.hwpx.extentHu
import io.github.baeseungwon1010.officecanvas.hwpx.gridSizes
import io.github.baeseungwon1010.officecanvas.hwpx.inlineSpan
import io.github.baeseungwon1010.officecanvas.hwpx.widthHu
import io.github.baeseungwon1010.officecanvas.util.Limits
import io.github.baeseungwon1010.officecanvas.util.SafeXml
import io.github.baeseungwon1010.officecanvas.util.readCapped
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File

/**
 * HWPML, the XML format older Hangul versions (and some document systems) save
 * `.hwp` files in: `<HWPML><HEAD>` with the font, character, paragraph and border
 * definitions and `<BODY><SECTION>` with paragraphs, tables and other objects.
 *
 * Unlike HWPX and HWP 5.0 it saves no layout: no line breaks and no positions for
 * lines. So lines are broken here, measured with the same faces the renderer draws
 * with, and set at the paragraph's line spacing; pages break where the body is
 * full or a paragraph asks for it. Objects placed by offsets (forms are often
 * nothing but tables placed this way) go where their offsets say.
 */
internal object HwpmlParser {

    class ParseException(message: String) : Exception(message)

    /** True when [file] is HWPML: XML whose root is `<HWPML>`. */
    fun isHwpml(file: File): Boolean {
        val head = ByteArray(1024)
        val n = file.inputStream().use { it.read(head) }
        if (n < 8) return false
        val text = when {
            head[0] == 0xFF.toByte() && head[1] == 0xFE.toByte() -> String(head, 2, n - 2, Charsets.UTF_16LE)
            head[0] == 0xFE.toByte() && head[1] == 0xFF.toByte() -> String(head, 2, n - 2, Charsets.UTF_16BE)
            else -> String(head, 0, n, Charsets.UTF_8)
        }.trimStart('﻿', ' ', '\r', '\n', '\t')
        return text.startsWith("<") && "<HWPML" in text
    }

    fun parse(file: File): HwpxDoc {
        val bytes = file.inputStream().use { it.readCapped(SafeXml.MAX_PART_BYTES) }
            ?: throw ParseException("the HWPML document is larger than ${SafeXml.MAX_PART_BYTES shr 20} MB")
        val root = SafeXml.parse(bytes, namespaceAware = false)?.documentElement
            ?.takeIf { it.tagName == "HWPML" } ?: throw ParseException("not an HWPML document")
        val head = Head(root.child("HEAD"))
        val pages = ArrayList<HwpxPage>()
        for (section in root.child("BODY")?.all("SECTION").orEmpty()) {
            if (pages.size >= Limits.MAX_PAGES) break
            pages += Section(head, section).pages()
        }
        if (pages.isEmpty()) throw ParseException("the HWPML document has no pages")
        return HwpxDoc(pages.take(Limits.MAX_PAGES))
    }

    // ---- definitions ---------------------------------------------------------

    private class ParaShape(
        val align: ParaAlign = ParaAlign.JUSTIFY,
        val indent: Long = 0, val left: Long = 0, val right: Long = 0,
        val prev: Long = 0, val next: Long = 0,
        val spacingType: String = "Percent", val spacing: Long = 160,
        /** Hangul (and other non-Latin) words may break between any two characters. */
        val breakNonLatin: Boolean = true
    )

    private class Head(head: Element?) {
        val styles = HashMap<String, CharStyle>()
        val paras = HashMap<String, ParaShape>()
        val borderFills = HashMap<String, BorderFill>()

        init {
            val faces = head?.child("MAPPINGTABLE")?.child("FACENAMELIST")?.all("FONTFACE").orEmpty()
            fun fonts(lang: String) = faces.firstOrNull { it.getAttribute("Lang") == lang }
                ?.all("FONT")?.associate { it.getAttribute("Id") to it.getAttribute("Name") }.orEmpty()
            val hangulFonts = fonts("Hangul")
            val latinFonts = fonts("Latin")
            val table = head?.child("MAPPINGTABLE")
            for (cs in table?.child("CHARSHAPELIST")?.all("CHARSHAPE").orEmpty()) {
                val ids = cs.child("FONTID")
                // Spacing outside Hangul's -50..50 % is a writer's placeholder, not a spacing.
                val spacing = cs.child("CHARSPACING")?.int("Hangul")?.takeIf { it in -50..50 } ?: 0
                styles[cs.getAttribute("Id")] = CharStyle(
                    sizePt = (cs.int("Height") ?: 1000).coerceIn(100, 400_000) / 100f,
                    colorArgb = colorRef(cs.getAttribute("TextColor")) ?: 0xFF000000.toInt(),
                    bold = cs.child("BOLD") != null,
                    italic = cs.child("ITALIC") != null,
                    underline = cs.child("UNDERLINE") != null,
                    strike = cs.child("STRIKEOUT") != null,
                    hangulFace = hangulFonts[ids?.getAttribute("Hangul")],
                    latinFace = latinFonts[ids?.getAttribute("Latin")],
                    widthPercent = cs.child("RATIO")?.int("Hangul")?.takeIf { it in 50..200 } ?: 100,
                    spacingPercent = spacing,
                    script = when {
                        cs.child("SUPERSCRIPT") != null -> 1
                        cs.child("SUBSCRIPT") != null -> -1
                        else -> 0
                    }
                )
            }
            for (ps in table?.child("PARASHAPELIST")?.all("PARASHAPE").orEmpty()) {
                val m = ps.child("PARAMARGIN")
                fun margin(name: String) = (m?.int(name) ?: 0).toLong().coerceIn(-100_000, 100_000)
                paras[ps.getAttribute("Id")] = ParaShape(
                    align = when (ps.getAttribute("Align")) {
                        "Left" -> ParaAlign.LEFT
                        "Right" -> ParaAlign.RIGHT
                        "Center" -> ParaAlign.CENTER
                        "Distribute", "DistributeSpace" -> ParaAlign.DISTRIBUTE
                        else -> ParaAlign.JUSTIFY
                    },
                    indent = margin("Indent"), left = margin("Left"), right = margin("Right"),
                    prev = margin("Prev").coerceAtLeast(0), next = margin("Next").coerceAtLeast(0),
                    spacingType = m?.getAttribute("LineSpacingType").orEmpty().ifEmpty { "Percent" },
                    spacing = (m?.int("LineSpacing") ?: 160).toLong().coerceIn(0, 100_000),
                    breakNonLatin = ps.getAttribute("BreakNonLatinWord") != "false"
                )
            }
            for (bf in table?.child("BORDERFILLLIST")?.all("BORDERFILL").orEmpty()) {
                fun side(name: String): Border? {
                    val b = bf.child(name) ?: return null
                    if (b.getAttribute("Type").let { it.isEmpty() || it == "None" }) return null
                    val mm = b.getAttribute("Width").removeSuffix("mm").trim().toDoubleOrNull() ?: 0.12
                    return Border(colorRef(b.getAttribute("Color")) ?: return null, (mm.coerceIn(0.0, 10.0) * HWPUNIT_PER_INCH / 25.4).toLong())
                }
                borderFills[bf.getAttribute("Id")] = BorderFill(
                    side("LEFTBORDER"), side("RIGHTBORDER"), side("TOPBORDER"), side("BOTTOMBORDER"),
                    bf.child("FILLBRUSH")?.child("WINDOWBRUSH")?.getAttribute("FaceColor")?.let { colorRef(it) }
                )
            }
        }

        fun style(id: String) = styles[id] ?: styles["0"] ?: CharStyle()
        fun para(id: String) = paras[id] ?: paras["0"] ?: ParaShape()
    }

    // ---- layout --------------------------------------------------------------

    /** A paragraph laid out from its own top: its lines (and objects set in them),
     *  its floating objects, where the next paragraph starts ([height]) and where
     *  its last line ends ([bottom]: a cell is sized to that, not to the line
     *  spacing after it). [empty]: no text or object at all. */
    private class Laid(val lines: List<HwpxElement>, val floats: List<Floating>, val height: Long, val bottom: Long, val empty: Boolean)

    /** An object placed by offsets, anchored at its paragraph's first line. */
    private class Floating(val obj: HwpxElement, val pos: Element, val takesLines: Boolean)

    /** One unit of text that isn't broken: a word (with the spaces after it), a
     *  single Hangul character, a tab, an object or a forced line break. */
    private class Atom(val spans: List<Span>, val width: Float, val trailing: Float, val height: Long, val lineBreak: Boolean = false)

    private class Section(val head: Head, val section: Element) {
        private var pageW = 59528L
        private var pageH = 84188L
        private var bodyX = 8504L
        private var bodyY = 5668L
        private var bodyW = 42520L
        private var bodyH = 70000L

        init {
            section.all("P").firstOrNull()?.all("TEXT")?.firstNotNullOfOrNull { it.child("SECDEF") }?.child("PAGEDEF")?.let { pd ->
                var w = pd.long("Width") ?: pageW
                var h = pd.long("Height") ?: pageH
                if (w <= 0 || h <= 0) { w = 59528; h = 84188 }
                // Landscape="1" is a sideways page whose saved size is the upright one.
                if (pd.getAttribute("Landscape").let { it == "1" || it.equals("true", true) } && w < h) { val t = w; w = h; h = t }
                val m = pd.child("PAGEMARGIN")
                fun margin(name: String) = (m?.long(name) ?: 0L).coerceIn(0, 200_000)
                pageW = w; pageH = h
                bodyX = margin("Left") + margin("Gutter")
                bodyY = margin("Top") + margin("Header")
                bodyW = (w - bodyX - margin("Right")).coerceAtLeast(1000)
                bodyH = (h - bodyY - margin("Bottom") - margin("Footer")).coerceAtLeast(1000)
            }
        }

        fun pages(): List<HwpxPage> {
            val flow = PageFlow(bodyH)
            var y = 0L
            for (p in section.all("P")) {
                if (flow.full) break
                if (p.getAttribute("PageBreak") == "true") { flow.breakPage(); y = 0 }
                val laid = paragraph(p, bodyW, 0)
                // Lines go down the page and on to the next one when it is full;
                // [base] is where the paragraph's top falls on the current page.
                var base = y
                var anchorY: Long? = null
                for (line in laid.lines) {
                    var top = base + line.yHu
                    if (top + line.extentHu > bodyH && top > 0 && line !is HwpxElement.Table) {
                        flow.breakPage(); base = -line.yHu; top = 0
                    }
                    if (anchorY == null) anchorY = top
                    val pageBefore = flow.page
                    flow.add(line.at(line.xHu, top))
                    // A table that ran over pages: the paragraph goes on below its last piece.
                    if (line is HwpxElement.Table && flow.page != pageBefore) base = flow.lastTableBottom - line.yHu - line.heightHu
                }
                val start = anchorY ?: base
                for (f in laid.floats) {
                    flow.add(placeFloat(f, start), floating = true, takesLines = f.takesLines)
                }
                y = base + laid.height
            }
            return flow.finish().map { (_, elements) -> HwpxPage(pageW, pageH, elements, bodyX, bodyY) }
        }

        /** A floating object at its offsets: from the paper, the page (both counted
         *  here from the body's corner), or its paragraph ([anchorY]) and column. */
        private fun placeFloat(f: Floating, anchorY: Long): HwpxElement {
            val pos = f.pos
            val w = f.obj.widthHu
            val h = f.obj.extentHu
            val hOff = pos.long("HorzOffset") ?: 0L
            val vOff = pos.long("VertOffset") ?: 0L
            val (refX, refW) = when (pos.getAttribute("HorzRelTo")) {
                "Paper", "Page" -> -bodyX to pageW
                else -> 0L to bodyW
            }
            val (refY, refH) = when (pos.getAttribute("VertRelTo")) {
                "Paper", "Page" -> -bodyY to pageH
                else -> anchorY to bodyH
            }
            val x = refX + when (pos.getAttribute("HorzAlign")) {
                "Center" -> (refW - w) / 2 + hOff
                "Right", "Outside" -> refW - w - hOff
                else -> hOff
            }
            val y = refY + when (pos.getAttribute("VertAlign")) {
                "Center" -> (refH - h) / 2 + vOff
                "Bottom" -> refH - h - vOff
                else -> vOff
            }
            return f.obj.at(x, y)
        }

        /**
         * Lays out paragraph [p] in a column [width] wide: its text broken into
         * lines, objects set like characters in them, floating objects aside.
         */
        fun paragraph(p: Element, width: Long, depth: Int): Laid {
            val shape = head.para(p.getAttribute("ParaShape"))
            val atoms = ArrayList<Atom>()
            val floats = ArrayList<Floating>()
            var firstStyle: CharStyle? = null
            for (text in p.all("TEXT")) {
                val style = head.style(text.getAttribute("CharShape"))
                if (firstStyle == null) firstStyle = style
                for (child in text.children()) {
                    when (child.tagName) {
                        "CHAR" -> textAtoms(child, style, shape.breakNonLatin, atoms)
                        "TABLE" -> table(child, depth + 1)?.let { obj ->
                            val so = child.child("SHAPEOBJECT")
                            val pos = so?.child("POSITION")
                            if (pos == null || pos.getAttribute("TreatAsChar") == "true") {
                                atoms += Atom(listOf(inlineSpan(obj, style)), obj.widthHu.toFloat(), 0f, obj.extentHu)
                            } else {
                                floats += Floating(obj, pos, so.getAttribute("TextWrap") == "TopAndBottom")
                            }
                        }
                    }
                }
            }
            val baseStyle = firstStyle ?: CharStyle()
            val baseHeight = (baseStyle.sizePt * 100).toLong()
            val lines = ArrayList<HwpxElement>()
            var y = shape.prev
            var bottom = y
            var i = 0
            var first = true
            do {
                val x = shape.left + if (first) shape.indent.coerceAtLeast(0) else (-shape.indent).coerceAtLeast(0)
                val avail = (width - x - shape.right).coerceAtLeast(500)
                // Fill the line: whole atoms while they fit (trailing spaces may hang
                // past the edge); a lone atom too wide for any line goes in anyway.
                val start = i
                var used = 0f
                while (i < atoms.size && !atoms[i].lineBreak) {
                    val a = atoms[i]
                    if (i > start && used + a.width - a.trailing > avail) break
                    used += a.width
                    i++
                }
                val forced = i < atoms.size && atoms[i].lineBreak
                val lineAtoms = atoms.subList(start, i)
                if (forced) i++
                val textHeight = lineAtoms.filter { a -> a.spans.none { it.inline != null } }.maxOfOrNull { it.height } ?: baseHeight
                val objectHeight = lineAtoms.filter { a -> a.spans.any { it.inline != null } }.maxOfOrNull { it.height } ?: 0L
                val height = maxOf(textHeight, objectHeight)
                val last = i >= atoms.size
                val spans = merge(lineAtoms.flatMap { it.spans })
                if (lineAtoms.size == 1 && objectHeight > 0 && lineAtoms[0].spans.size == 1) {
                    // An object alone on its line is placed on its own.
                    val obj = lineAtoms[0].spans[0].inline!!
                    val ox = x + when (shape.align) {
                        ParaAlign.CENTER -> ((avail - obj.widthHu) / 2).coerceAtLeast(0)
                        ParaAlign.RIGHT -> (avail - obj.widthHu).coerceAtLeast(0)
                        else -> 0L
                    }
                    lines += obj.at(ox, y)
                } else if (spans.isNotEmpty()) {
                    lines += HwpxElement.TextRun(
                        xHu = x, yHu = y, widthHu = avail, heightHu = height, spans = spans,
                        baselineHu = height - textHeight * 15 / 100, align = shape.align, lastLine = last || forced
                    )
                }
                bottom = y + height
                y += pitch(shape, textHeight, height)
                first = false
            } while (i < atoms.size && lines.size < MAX_LINES)
            val empty = atoms.none { a -> a.spans.any { it.inline != null || it.text.isNotBlank() } } && floats.isEmpty()
            return Laid(lines, floats, y + shape.next, bottom + shape.next, empty)
        }

        /** Line pitch: Hangul's line spacing is a percent of the text height, a
         *  fixed pitch, extra space between lines, or a minimum. */
        private fun pitch(shape: ParaShape, textHeight: Long, height: Long): Long = when (shape.spacingType) {
            "Fixed" -> maxOf(shape.spacing, 1)
            "BetweenLines" -> height + shape.spacing
            "AtLeast" -> maxOf(height, shape.spacing)
            else -> height + textHeight * (shape.spacing - 100).coerceAtLeast(0) / 100
        }

        private val widths = HashMap<Pair<CharStyle, String>, Float>()

        /** Measured as drawn; single characters (most Hangul atoms) are remembered. */
        private fun measure(text: String, style: CharStyle): Float {
            if (text.length > 2) return HwpxRenderer.widthHu(Span(text, style))
            return widths.getOrPut(style to text) { HwpxRenderer.widthHu(Span(text, style)) }
        }

        /** Breaks a <CHAR>'s text into atoms: words with their trailing spaces, and
         *  Hangul or Han characters one by one when those may break anywhere. */
        private fun textAtoms(char: Element, style: CharStyle, breakNonLatin: Boolean, out: MutableList<Atom>) {
            val height = (style.sizePt * 100).toLong()
            fun add(text: String) {
                if (text.isEmpty() || out.size >= MAX_ATOMS) return
                val trimmed = text.trimEnd(' ')
                val width = measure(text, style)
                val trailing = if (trimmed.length < text.length) width - measure(trimmed, style) else 0f
                out += Atom(listOf(Span(text, style)), width, trailing, height)
            }
            for (node in char.childNodes.toList()) {
                when {
                    node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE -> {
                        val text = node.nodeValue.replace('\n', ' ').replace('\r', ' ')
                        var from = 0
                        var k = 0
                        while (k < text.length) {
                            val c = text[k]
                            if (breakNonLatin && isNonLatin(c)) {
                                add(text.substring(from, k))
                                var end = k + 1
                                while (end < text.length && text[end] == ' ') end++
                                add(text.substring(k, end))
                                from = end; k = end
                                continue
                            }
                            if (c == ' ') {
                                var end = k + 1
                                while (end < text.length && text[end] == ' ') end++
                                add(text.substring(from, end))
                                from = end; k = end
                                continue
                            }
                            k++
                        }
                        add(text.substring(from))
                    }
                    node is Element && node.tagName == "TAB" ->
                        out += Atom(listOf(Span("\t", style, DEFAULT_TAB_HU)), DEFAULT_TAB_HU.toFloat(), 0f, height)
                    node is Element && node.tagName == "LINEBREAK" ->
                        out += Atom(emptyList(), 0f, 0f, height, lineBreak = true)
                    node is Element && (node.tagName == "NBSPACE" || node.tagName == "FWSPACE") -> add(" ")
                    node is Element && node.tagName == "HYPEN" -> add("-")
                }
            }
        }

        /** A <TABLE>: its grid from where its cells end, rows as tall as their cells
         *  declare or their content needs. */
        private fun table(t: Element, depth: Int): HwpxElement.Table? {
            if (depth > Limits.MAX_NESTING) return null
            val cellsXml = t.all("ROW").flatMap { it.all("CELL") }
            if (cellsXml.isEmpty()) return null
            val rowCnt = (t.int("RowCount") ?: 0).coerceIn(1, cellsXml.maxOf { it.int("RowAddr") ?: 0 } + 1)
            val colCnt = (t.int("ColCount") ?: 0).coerceIn(1, cellsXml.maxOf { it.int("ColAddr") ?: 0 } + 1)
            val inside = t.child("INSIDEMARGIN")
            val grid = MutableList(rowCnt) { MutableList<TableCell?>(colCnt) { null } }
            val colSpans = ArrayList<GridSpan>()
            val rowSpans = ArrayList<GridSpan>()
            val content = HashMap<Int, Long>()
            for (c in cellsXml) {
                val row = c.int("RowAddr") ?: continue
                val col = c.int("ColAddr") ?: continue
                if (row !in 0 until rowCnt || col !in 0 until colCnt) continue
                val colSpan = (c.int("ColSpan") ?: 1).coerceAtLeast(1)
                val rowSpan = (c.int("RowSpan") ?: 1).coerceAtLeast(1)
                val w = (c.long("Width") ?: 0L).coerceIn(0, 1_000_000)
                val h = (c.long("Height") ?: 0L).coerceIn(0, 1_000_000)
                val m = if (c.getAttribute("HasMargin") == "true") c.child("CELLMARGIN") ?: inside else inside
                fun margin(name: String) = (m?.long(name) ?: 141L).coerceIn(0, 100_000)
                val list = c.child("PARALIST")
                val inner = (w - margin("Left") - margin("Right")).coerceAtLeast(500)
                var y = 0L
                var bottom = 0L
                var empty = true
                val elements = ArrayList<HwpxElement>()
                for (p in list?.all("P").orEmpty()) {
                    val laid = paragraph(p, inner, depth)
                    laid.lines.forEach { elements += it.at(it.xHu + margin("Left"), it.yHu + y + margin("Top")) }
                    // Objects floating in a cell are placed from its paragraph, and the
                    // cell is made tall enough for them.
                    var reach = 0L
                    laid.floats.forEach { f ->
                        val placed = f.obj.at(margin("Left") + (f.pos.long("HorzOffset") ?: 0), y + margin("Top") + (f.pos.long("VertOffset") ?: 0))
                        elements += placed
                        reach = maxOf(reach, placed.yHu + placed.extentHu - margin("Top"))
                    }
                    bottom = maxOf(y + laid.bottom, reach)
                    y = maxOf(y + laid.height, reach)
                    if (!laid.empty) empty = false
                    if (elements.size > MAX_LINES) break
                }
                // A cell with nothing in it keeps its declared height (forms use thin
                // empty rows as spacers); one with content is at least as tall as it.
                val needed = bottom + margin("Top") + margin("Bottom")
                if (rowSpan == 1 && !empty) content[row] = maxOf(content[row] ?: 0L, needed)
                colSpans += GridSpan(col, colSpan, w)
                rowSpans += GridSpan(row, rowSpan, h)
                grid[row][col] = TableCell(
                    colSpan, rowSpan, elements,
                    borderFill = head.borderFills[c.getAttribute("BorderFill")],
                    vAlign = when (list?.getAttribute("VertAlign")) { "Center" -> 1; "Bottom" -> 2; else -> 0 }
                )
            }
            val size = t.child("SHAPEOBJECT")?.child("SIZE")
            val declaredW = size?.long("Width") ?: colSpans.filter { it.start == 0 }.sumOf { it.sizeHu }
            val declaredH = size?.long("Height") ?: 0L
            val cols = gridSizes(colSpans, colCnt, declaredW) ?: List(colCnt) { (declaredW / colCnt).coerceAtLeast(1) }
            val declaredRows = gridSizes(rowSpans.sortedBy { it.start }, rowCnt, declaredH)
            val rows = (0 until rowCnt).map { r -> maxOf(declaredRows?.get(r) ?: 0L, content[r] ?: 0L).takeIf { it > 0 } ?: 1000L }
            return HwpxElement.Table(0, 0, cols.sum(), rows.sum(), cols, rows, grid)
        }
    }

    /** Consecutive text spans in one style, joined. */
    private fun merge(spans: List<Span>): List<Span> {
        val out = ArrayList<Span>(spans.size)
        for (s in spans) {
            val prev = out.lastOrNull()
            if (prev != null && prev.style == s.style && prev.tabHu == null && prev.inline == null && s.tabHu == null && s.inline == null) {
                out[out.size - 1] = prev.copy(text = prev.text + s.text)
            } else out += s
        }
        return out
    }

    private fun isNonLatin(c: Char) = c.code in 0x1100..0x11FF || c.code in 0x3000..0x9FFF || c.code in 0xAC00..0xD7A3 || c.code in 0xF900..0xFAFF || c.code in 0xFF00..0xFFEF

    /** COLORREF as a decimal number (0x00BBGGRR); a value with the top byte set means none. */
    private fun colorRef(value: String): Int? {
        val v = value.trim().toLongOrNull() ?: return null
        if (v < 0 || v > 0xFFFFFF) return null
        val r = (v and 0xFF).toInt(); val g = ((v shr 8) and 0xFF).toInt(); val b = ((v shr 16) and 0xFF).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** Lines in one paragraph or cell, whatever a file claims. */
    private const val MAX_LINES = 20_000

    /** Words and characters kept from one paragraph (far more than fit on the pages it may have). */
    private const val MAX_ATOMS = 200_000

    private fun org.w3c.dom.NodeList.toList(): List<Node> = (0 until length).map { item(it) }
    private fun Element.children(): List<Element> = childNodes.toList().filterIsInstance<Element>()
    private fun Element.child(name: String): Element? = children().firstOrNull { it.tagName == name }
    private fun Element.all(name: String): List<Element> = children().filter { it.tagName == name }
    private fun Element.int(attr: String): Int? = getAttribute(attr).trim().toIntOrNull()
    private fun Element.long(attr: String): Long? = getAttribute(attr).trim().toLongOrNull()
}
