package io.github.baeseungwon1010.officecanvas.hwp

import io.github.baeseungwon1010.officecanvas.hwpx.Border
import io.github.baeseungwon1010.officecanvas.hwpx.BorderFill
import io.github.baeseungwon1010.officecanvas.hwpx.CharStyle
import io.github.baeseungwon1010.officecanvas.hwpx.GridSpan
import io.github.baeseungwon1010.officecanvas.hwpx.HWPUNIT_PER_INCH
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxDoc
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxElement
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxPage
import io.github.baeseungwon1010.officecanvas.hwpx.PageFlow
import io.github.baeseungwon1010.officecanvas.hwpx.ParaAlign
import io.github.baeseungwon1010.officecanvas.hwpx.TableCell
import io.github.baeseungwon1010.officecanvas.hwpx.TextFlow
import io.github.baeseungwon1010.officecanvas.hwpx.at
import io.github.baeseungwon1010.officecanvas.hwpx.extentHu
import io.github.baeseungwon1010.officecanvas.hwpx.gridSizes
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

    private class Head(head: Element?) {
        val styles = HashMap<String, CharStyle>()
        val paras = HashMap<String, TextFlow.ParaShape>()
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
                val spacing = (m?.int("LineSpacing") ?: 160).toLong().coerceIn(0, 100_000)
                paras[ps.getAttribute("Id")] = TextFlow.ParaShape(
                    align = when (ps.getAttribute("Align")) {
                        "Left" -> ParaAlign.LEFT
                        "Right" -> ParaAlign.RIGHT
                        "Center" -> ParaAlign.CENTER
                        "Distribute", "DistributeSpace" -> ParaAlign.DISTRIBUTE
                        else -> ParaAlign.JUSTIFY
                    },
                    indent = margin("Indent"), left = margin("Left"), right = margin("Right"),
                    prev = margin("Prev").coerceAtLeast(0), next = margin("Next").coerceAtLeast(0),
                    spacing = when (m?.getAttribute("LineSpacingType")) {
                        "Fixed" -> TextFlow.LineSpacing.Exactly(spacing)
                        "BetweenLines" -> TextFlow.LineSpacing.Between(spacing)
                        "AtLeast" -> TextFlow.LineSpacing.AtLeast(spacing)
                        else -> TextFlow.LineSpacing.Percent(spacing)
                    },
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
        fun para(id: String) = paras[id] ?: paras["0"] ?: TextFlow.ParaShape()
    }

    // ---- layout --------------------------------------------------------------

    /** A paragraph laid out ([TextFlow.Laid]) with its floating objects. */
    private class Laid(val flow: TextFlow.Laid, val floats: List<Floating>) {
        val height get() = flow.height
        val bottom get() = flow.bottom
        val empty get() = flow.empty && floats.isEmpty()
    }

    /** An object placed by offsets, anchored at its paragraph's first line. */
    private class Floating(val obj: HwpxElement, val pos: Element, val takesLines: Boolean)

    private class Section(val head: Head, val section: Element) {
        private val text = TextFlow()
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
                val (anchorY, next) = text.place(flow, laid.flow, y, bodyH)
                for (f in laid.floats) {
                    flow.add(placeFloat(f, anchorY), floating = true, takesLines = f.takesLines)
                }
                y = next
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
            val atoms = ArrayList<TextFlow.Atom>()
            val floats = ArrayList<Floating>()
            var firstStyle: CharStyle? = null
            for (t in p.all("TEXT")) {
                val style = head.style(t.getAttribute("CharShape"))
                if (firstStyle == null) firstStyle = style
                for (child in t.children()) {
                    when (child.tagName) {
                        "CHAR" -> textAtoms(child, style, shape.breakNonLatin, atoms)
                        "TABLE" -> table(child, depth + 1)?.let { obj ->
                            val so = child.child("SHAPEOBJECT")
                            val pos = so?.child("POSITION")
                            if (pos == null || pos.getAttribute("TreatAsChar") == "true") {
                                atoms += text.inline(obj, style)
                            } else {
                                floats += Floating(obj, pos, so.getAttribute("TextWrap") == "TopAndBottom")
                            }
                        }
                    }
                }
            }
            return Laid(text.lay(atoms, shape, width, firstStyle ?: CharStyle()), floats)
        }

        /** A <CHAR>'s text and the controls in it (tabs, line breaks, special spaces). */
        private fun textAtoms(char: Element, style: CharStyle, breakNonLatin: Boolean, out: MutableList<TextFlow.Atom>) {
            for (node in char.childNodes.toList()) {
                when {
                    node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE ->
                        text.addText(node.nodeValue.replace('\n', ' ').replace('\r', ' '), style, breakNonLatin, out)
                    node is Element && node.tagName == "TAB" -> out += text.tab(style)
                    node is Element && node.tagName == "LINEBREAK" -> out += text.lineBreak(style)
                    node is Element && (node.tagName == "NBSPACE" || node.tagName == "FWSPACE") -> text.addText(" ", style, breakNonLatin, out)
                    node is Element && node.tagName == "HYPEN" -> text.addText("-", style, breakNonLatin, out)
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
                    laid.flow.lines.forEach { elements += it.at(it.xHu + margin("Left"), it.yHu + y + margin("Top")) }
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
                    if (elements.size > TextFlow.MAX_LINES) break
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

    /** COLORREF as a decimal number (0x00BBGGRR); a value with the top byte set means none. */
    private fun colorRef(value: String): Int? {
        val v = value.trim().toLongOrNull() ?: return null
        if (v < 0 || v > 0xFFFFFF) return null
        val r = (v and 0xFF).toInt(); val g = ((v shr 8) and 0xFF).toInt(); val b = ((v shr 16) and 0xFF).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun org.w3c.dom.NodeList.toList(): List<Node> = (0 until length).map { item(it) }
    private fun Element.children(): List<Element> = childNodes.toList().filterIsInstance<Element>()
    private fun Element.child(name: String): Element? = children().firstOrNull { it.tagName == name }
    private fun Element.all(name: String): List<Element> = children().filter { it.tagName == name }
    private fun Element.int(attr: String): Int? = getAttribute(attr).trim().toIntOrNull()
    private fun Element.long(attr: String): Long? = getAttribute(attr).trim().toLongOrNull()
}
