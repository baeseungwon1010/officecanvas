package io.github.baeseungwon1010.officecanvas.docx

import io.github.baeseungwon1010.officecanvas.hwpx.Border
import io.github.baeseungwon1010.officecanvas.hwpx.BorderFill
import io.github.baeseungwon1010.officecanvas.hwpx.CharStyle
import io.github.baeseungwon1010.officecanvas.hwpx.DrawingKind
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxDoc
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxElement
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxPage
import io.github.baeseungwon1010.officecanvas.hwpx.ImageCrop
import io.github.baeseungwon1010.officecanvas.hwpx.PAGE_NUMBER_MARK
import io.github.baeseungwon1010.officecanvas.hwpx.PageFlow
import io.github.baeseungwon1010.officecanvas.hwpx.ParaAlign
import io.github.baeseungwon1010.officecanvas.hwpx.TableCell
import io.github.baeseungwon1010.officecanvas.hwpx.TextFlow
import io.github.baeseungwon1010.officecanvas.hwpx.at
import io.github.baeseungwon1010.officecanvas.hwpx.extentHu
import io.github.baeseungwon1010.officecanvas.hwpx.withPageNumber
import io.github.baeseungwon1010.officecanvas.ppt.fixSymbolText
import io.github.baeseungwon1010.officecanvas.pptx.NS_A
import io.github.baeseungwon1010.officecanvas.pptx.NS_R
import io.github.baeseungwon1010.officecanvas.pptx.child
import io.github.baeseungwon1010.officecanvas.pptx.children
import io.github.baeseungwon1010.officecanvas.pptx.first
import io.github.baeseungwon1010.officecanvas.pptx.rels
import io.github.baeseungwon1010.officecanvas.pptx.xml
import io.github.baeseungwon1010.officecanvas.util.Limits
import io.github.baeseungwon1010.officecanvas.util.RecentBytes
import io.github.baeseungwon1010.officecanvas.util.SafeBitmaps
import io.github.baeseungwon1010.officecanvas.util.readCapped
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile

/**
 * Word documents (.docx, WordprocessingML). Unlike Hangul's formats, Word saves no
 * layout: paragraphs are laid out here ([TextFlow]) with their styles (document
 * defaults, the paragraph and character style chains, direct formatting), list
 * numbering, tab stops, borders and shading, and paged with the section's page size
 * and margins; tables take their grid, merged cells, borders, shading and row
 * heights; inline pictures are read from the package when drawn; headers and
 * footers repeat on each page with their page number fields.
 */
internal object DocxParser {

    class ParseException(message: String) : Exception(message)

    internal const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"
    private const val M = "http://schemas.openxmlformats.org/officeDocument/2006/math"

    /** Stands for the document's page count in headers and footers until it is known. */
    private const val PAGE_COUNT_MARK = ''

    fun parse(file: File): HwpxDoc {
        val zip = try { ZipFile(file) } catch (e: java.io.IOException) { throw ParseException("not a .docx: ${e.message}") }
        var keep = false
        try {
            val doc = Reader(zip).read()
            keep = true
            return doc.copy(resources = zip)
        } finally {
            if (!keep) zip.close()
        }
    }

    // ---- properties ---------------------------------------------------------

    /** Run properties; null means "not set here". */
    private data class RunProps(
        val size: Float? = null, val bold: Boolean? = null, val italic: Boolean? = null,
        val underline: Boolean? = null, val strike: Boolean? = null, val color: Int? = null,
        val vert: Int? = null, val ascii: String? = null, val eastAsia: String? = null,
        val asciiTheme: String? = null, val eastAsiaTheme: String? = null, val caps: Boolean? = null,
        val vanish: Boolean? = null, val scale: Int? = null, val spacing: Int? = null, val style: String? = null
    ) {
        /** These properties over [base]'s. */
        fun over(base: RunProps) = RunProps(
            size ?: base.size, bold ?: base.bold, italic ?: base.italic, underline ?: base.underline,
            strike ?: base.strike, color ?: base.color, vert ?: base.vert,
            // A font set directly replaces a theme font set lower down, and the other way round.
            if (ascii != null || asciiTheme != null) ascii else base.ascii,
            if (eastAsia != null || eastAsiaTheme != null) eastAsia else base.eastAsia,
            if (ascii != null || asciiTheme != null) asciiTheme else base.asciiTheme,
            if (eastAsia != null || eastAsiaTheme != null) eastAsiaTheme else base.eastAsiaTheme,
            caps ?: base.caps, vanish ?: base.vanish, scale ?: base.scale, spacing ?: base.spacing, style ?: base.style
        )
    }

    private data class Line(val argb: Int, val widthHu: Long, val space: Long = 0)

    /** Paragraph properties; null means "not set here". Lengths in HWPUNIT. */
    private data class ParaProps(
        val jc: String? = null, val left: Long? = null, val right: Long? = null,
        val firstLine: Long? = null, val hanging: Long? = null,
        val before: Long? = null, val after: Long? = null, val line: Long? = null, val lineRule: String? = null,
        val contextual: Boolean? = null, val pageBreakBefore: Boolean? = null, val keepNext: Boolean? = null,
        val numId: String? = null, val ilvl: Int? = null, val tabs: List<Pair<Long, String>>? = null,
        val top: Line? = null, val bottom: Line? = null, val shade: Int? = null, val wordWrap: Boolean? = null,
        val style: String? = null, val leftBorder: Line? = null, val rightBorder: Line? = null
    ) {
        /** Paragraphs with the same borders and indents share one box. */
        fun sameBox(o: ParaProps) = top == o.top && bottom == o.bottom && leftBorder == o.leftBorder && rightBorder == o.rightBorder &&
            left == o.left && right == o.right && (top != null || bottom != null || leftBorder != null || rightBorder != null)

        fun over(base: ParaProps) = ParaProps(
            jc ?: base.jc,
            left ?: base.left, right ?: base.right,
            // A first-line indent and a hanging one replace each other.
            if (firstLine != null || hanging != null) firstLine else base.firstLine,
            if (firstLine != null || hanging != null) hanging else base.hanging,
            before ?: base.before, after ?: base.after,
            line ?: base.line, if (line != null) lineRule else base.lineRule,
            contextual ?: base.contextual, pageBreakBefore ?: base.pageBreakBefore, keepNext ?: base.keepNext,
            numId ?: base.numId, ilvl ?: base.ilvl,
            mergeTabs(base.tabs, tabs),
            top ?: base.top, bottom ?: base.bottom, shade ?: base.shade, wordWrap ?: base.wordWrap, style ?: base.style,
            leftBorder ?: base.leftBorder, rightBorder ?: base.rightBorder
        )

        private fun mergeTabs(base: List<Pair<Long, String>>?, own: List<Pair<Long, String>>?): List<Pair<Long, String>>? {
            if (own == null) return base
            val cleared = own.filter { it.second == "clear" }.map { it.first }.toSet()
            return (base.orEmpty().filter { (p, _) -> cleared.none { kotlin.math.abs(it - p) < 50 } } + own.filter { it.second != "clear" })
                .sortedBy { it.first }
        }
    }

    private class Style(val type: String, val basedOn: String?, val pPr: ParaProps, val rPr: RunProps, val tblPr: Element?)

    private class Level(val start: Int, val format: String, val text: String, val suffix: String, val pPr: ParaProps, val rPr: RunProps)

    // ---- reading ------------------------------------------------------------

    private class Reader(val zip: ZipFile) {
        private val text = TextFlow()
        private val documentPath = "word/document.xml"
        private val docRels = zip.rels(documentPath)
        private val styles = HashMap<String, Style>()
        private var defaultRun = RunProps()
        private var defaultPara = ParaProps()
        private var defaultParaStyle: String? = null
        private var defaultTableStyle: String? = null
        private var defaultTab = 720L * 5
        private val themeFonts = HashMap<String, String>()
        private val nums = HashMap<String, Pair<String, Map<Int, Int>>>() // numId -> (abstractNumId, start overrides)
        private val abstracts = HashMap<String, Map<Int, Level>>()
        private val counters = HashMap<String, IntArray>()
        private val pictures = RecentBytes<String>(32L shl 20)

        fun read(): HwpxDoc {
            val document = zip.xml(documentPath)?.documentElement ?: throw ParseException("not a .docx: word/document.xml is missing")
            val body = document.child(W, "body") ?: throw ParseException("not a .docx: no body")
            readTheme()
            readStyles()
            readNumbering()
            zip.xml("word/settings.xml")?.documentElement?.child(W, "defaultTabStop")?.twips("val")?.takeIf { it > 0 }?.let { defaultTab = it }

            // Sections end at a paragraph holding a <w:sectPr>, and the body's own
            // <w:sectPr> describes the last one.
            val blocks = ArrayList<Element>()
            flattenBlocks(body, blocks, 0)
            val pages = ArrayList<HwpxPage>()
            var current = ArrayList<Element>()
            for (b in blocks) {
                if (pages.size >= Limits.MAX_PAGES) break
                current += b
                val sect = if (b.localName == "p") b.child(W, "pPr")?.child(W, "sectPr") else null
                if (sect != null) { pages += section(current, sect); current = ArrayList() }
            }
            if ((current.isNotEmpty() || pages.isEmpty()) && pages.size < Limits.MAX_PAGES) {
                pages += section(current, body.child(W, "sectPr"))
            }
            if (pages.isEmpty()) throw ParseException("the document has no pages")
            val total = pages.size.coerceAtMost(Limits.MAX_PAGES)
            return HwpxDoc(pages.take(Limits.MAX_PAGES).map { page ->
                page.copy(elements = page.elements.map { it.replacingText(PAGE_COUNT_MARK, total.toString()) })
            })
        }

        /** Body content: paragraphs and tables, with content controls and
         *  custom XML unwrapped. */
        private fun flattenBlocks(parent: Element, out: MutableList<Element>, depth: Int) {
            if (depth > Limits.MAX_NESTING) return
            for (c in parent.children()) {
                if (c.namespaceURI != W) continue
                when (c.localName) {
                    "p", "tbl" -> out += c
                    "sdt" -> c.child(W, "sdtContent")?.let { flattenBlocks(it, out, depth + 1) }
                    "customXml", "ins", "moveTo" -> flattenBlocks(c, out, depth + 1)
                }
            }
        }

        // ---- definitions ----

        private fun readTheme() {
            val themePath = docRels.values.firstOrNull { it.contains("theme/") } ?: "word/theme/theme1.xml"
            val fonts = zip.xml(themePath)?.documentElement?.first(NS_A, "fontScheme") ?: return
            for ((which, el) in listOf("minor" to fonts.child(NS_A, "minorFont"), "major" to fonts.child(NS_A, "majorFont"))) {
                el ?: continue
                el.child(NS_A, "latin")?.getAttribute("typeface")?.ifBlank { null }?.let { themeFonts["${which}HAnsi"] = it; themeFonts["${which}Ascii"] = it }
                val ea = el.child(NS_A, "ea")?.getAttribute("typeface")?.ifBlank { null }
                    ?: el.children().firstOrNull { it.localName == "font" && it.getAttribute("script") == "Hang" }?.getAttribute("typeface")?.ifBlank { null }
                ea?.let { themeFonts["${which}EastAsia"] = it }
            }
        }

        private fun readStyles() {
            val root = zip.xml("word/styles.xml")?.documentElement ?: return
            root.child(W, "docDefaults")?.let { d ->
                defaultRun = runProps(d.child(W, "rPrDefault")?.child(W, "rPr"))
                defaultPara = paraProps(d.child(W, "pPrDefault")?.child(W, "pPr"))
            }
            for (s in root.children().filter { it.localName == "style" && it.namespaceURI == W }) {
                val id = s.wAttr("styleId") ?: continue
                val type = s.wAttr("type") ?: "paragraph"
                styles[id] = Style(type, s.child(W, "basedOn")?.wAttr("val"), paraProps(s.child(W, "pPr")), runProps(s.child(W, "rPr")), s.child(W, "tblPr"))
                if (s.wAttr("default").let { it == "1" || it == "true" || it == "on" }) {
                    if (type == "paragraph") defaultParaStyle = id
                    if (type == "table") defaultTableStyle = id
                }
            }
        }

        private fun readNumbering() {
            val root = zip.xml("word/numbering.xml")?.documentElement ?: return
            for (a in root.children().filter { it.localName == "abstractNum" }) {
                val id = a.wAttr("abstractNumId") ?: continue
                abstracts[id] = a.children().filter { it.localName == "lvl" }.mapNotNull { lvl ->
                    val ilvl = lvl.wAttr("ilvl")?.toIntOrNull()?.takeIf { it in 0..8 } ?: return@mapNotNull null
                    ilvl to Level(
                        start = lvl.child(W, "start")?.wAttr("val")?.toIntOrNull() ?: 1,
                        format = lvl.child(W, "numFmt")?.wAttr("val") ?: "decimal",
                        text = lvl.child(W, "lvlText")?.wAttr("val") ?: "",
                        suffix = lvl.child(W, "suff")?.wAttr("val") ?: "tab",
                        pPr = paraProps(lvl.child(W, "pPr")),
                        rPr = runProps(lvl.child(W, "rPr"))
                    )
                }.toMap()
            }
            for (n in root.children().filter { it.localName == "num" }) {
                val id = n.wAttr("numId") ?: continue
                val abs = n.child(W, "abstractNumId")?.wAttr("val") ?: continue
                val starts = n.children().filter { it.localName == "lvlOverride" }.mapNotNull { o ->
                    val ilvl = o.wAttr("ilvl")?.toIntOrNull() ?: return@mapNotNull null
                    o.child(W, "startOverride")?.wAttr("val")?.toIntOrNull()?.let { ilvl to it }
                }.toMap()
                nums[id] = abs to starts
            }
        }

        /** A style and the ones it is based on, the most basic first. */
        private fun chain(id: String?): List<Style> {
            val out = ArrayList<Style>()
            var cur = id
            val seen = HashSet<String>()
            while (cur != null && seen.add(cur) && out.size < 32) {
                val s = styles[cur] ?: break
                out += s
                cur = s.basedOn
            }
            return out.asReversed()
        }

        /** The table style of the table being laid out: its paragraph and run
         *  properties apply to the paragraphs in it, over the document defaults. */
        private var tableStyle: List<Style> = emptyList()

        private fun paraPropsOf(p: Element): ParaProps {
            val own = paraProps(p.child(W, "pPr"))
            var props = defaultPara
            for (s in tableStyle) props = s.pPr.over(props)
            for (s in chain(own.style ?: defaultParaStyle)) props = s.pPr.over(props)
            level(props.over(ParaProps()).let { own.over(it) })?.let { props = it.pPr.over(props) }
            return own.over(props)
        }

        /** Run properties of a paragraph's text from [p]'s style, a character style
         *  and direct formatting [own]. */
        private fun runPropsOf(paraStyle: String?, own: RunProps): RunProps {
            var props = defaultRun
            for (s in tableStyle) props = s.rPr.over(props)
            for (s in chain(paraStyle ?: defaultParaStyle)) props = s.rPr.over(props)
            own.style?.let { cs -> for (s in chain(cs)) props = s.rPr.over(props) }
            return own.over(props)
        }

        private fun level(props: ParaProps): Level? {
            val numId = props.numId?.takeIf { it != "0" } ?: return null
            val abs = nums[numId]?.first ?: return null
            return abstracts[abs]?.get(props.ilvl ?: 0)
        }

        private fun charStyle(r: RunProps): CharStyle {
            val latin = r.ascii ?: r.asciiTheme?.let { themeFonts[it] }
            val ea = r.eastAsia ?: r.eastAsiaTheme?.let { themeFonts[it] }
            val size = (r.size ?: 10f).coerceIn(1f, 400f)
            return CharStyle(
                sizePt = size,
                colorArgb = r.color ?: 0xFF000000.toInt(),
                bold = r.bold == true, italic = r.italic == true, underline = r.underline == true, strike = r.strike == true,
                hangulFace = ea ?: latin, latinFace = latin ?: ea,
                widthPercent = r.scale?.coerceIn(50, 200) ?: 100,
                spacingPercent = r.spacing?.let { (it / 20f / size * 100).toInt().coerceIn(-50, 50) } ?: 0,
                script = r.vert ?: 0
            )
        }

        /** The font's single line (ascent, descent and gap) as Word sets it, per point of size. */
        private fun lineFactor(face: String?): Float {
            val f = face?.lowercase().orEmpty()
            return when {
                "맑은" in f || "malgun" in f -> 1.33f
                "calibri" in f -> 1.22f
                "cambria" in f -> 1.17f
                "consolas" in f -> 1.17f
                "courier" in f -> 1.13f
                "arial" in f || "helvetica" in f || "liberation" in f || "times" in f -> 1.15f
                else -> 1.17f
            }
        }

        private fun singleLine(style: CharStyle, hasHangul: Boolean): Long {
            val face = if (hasHangul) style.hangulFace else style.latinFace
            return (style.sizePt * 100 * maxOf(lineFactor(face), lineFactor(style.latinFace))).toLong()
        }

        // ---- sections and pages ----

        private class Geometry(
            val width: Long, val height: Long, val top: Long, val bottom: Long, val left: Long, val right: Long,
            val header: Long, val footer: Long
        ) {
            val bodyWidth get() = (width - left - right).coerceAtLeast(2000)
        }

        private fun geometry(sect: Element?): Geometry {
            val size = sect?.child(W, "pgSz")
            var w = size?.twips("w") ?: (11906L * 5)
            var h = size?.twips("h") ?: (16838L * 5)
            if (w <= 0 || h <= 0 || w > 2_000_000 || h > 2_000_000) { w = 11906L * 5; h = 16838L * 5 }
            if (size?.wAttr("orient") == "landscape" && w < h) { val t = w; w = h; h = t }
            val m = sect?.child(W, "pgMar")
            fun margin(name: String, default: Long) = (m?.twips(name) ?: default).let { kotlin.math.abs(it) }.coerceAtMost(h / 3)
            return Geometry(w, h, margin("top", 7200), margin("bottom", 7200), margin("left", 9000), margin("right", 9000),
                margin("header", 3540), margin("footer", 3540))
        }

        private fun section(blocks: List<Element>, sect: Element?): List<HwpxPage> {
            val g = geometry(sect)
            val header = headerFooter(sect, "headerReference", g)
            val footer = headerFooter(sect, "footerReference", g)
            // The body starts below the top margin, or below a header taller than it.
            val headerBottom = g.header + (header?.second ?: 0L)
            val bodyY = maxOf(g.top, headerBottom)
            val footerTop = g.height - g.footer - (footer?.second ?: 0L)
            val bodyBottom = minOf(g.height - g.bottom, footerTop)
            val bodyHeight = (bodyBottom - bodyY).coerceAtLeast(2000)
            val flow = PageFlow(bodyHeight)
            var y = 0L
            val laid = layBlocks(blocks, g.bodyWidth, 0)
            for ((index, item) in laid.withIndex()) {
                if (flow.full) break
                if (item.breakBefore && (y > 0 || flow.page > 0)) { flow.breakPage(); y = 0 }
                // A paragraph kept with the next (a heading, mostly) goes on to the
                // next page with it when the next one's first line wouldn't fit.
                if (item.keepNext && y > 0) {
                    var need = item.laid.height
                    var k = index + 1
                    while (k < laid.size && laid[k - 1].keepNext && k - index < 8) {
                        val next = laid[k].laid
                        val firstLine = next.lines.firstOrNull { it !is HwpxElement.Drawing }
                        need += if (laid[k].keepNext) next.height else (firstLine?.let { it.yHu + it.extentHu } ?: next.bottom)
                        k++
                    }
                    if (y + need > bodyHeight && need <= bodyHeight) { flow.breakPage(); y = 0 }
                }
                // Space before a paragraph is dropped at the top of a page the text ran
                // onto (not after a page break the document asks for).
                val top = if (y == 0L && flow.page > 0 && !item.breakBefore && !laid.getOrNull(index - 1).let { it?.breakAfter == true }) -item.laid.lead else y
                y = text.place(flow, item.laid, top, bodyHeight).second
                if (item.breakAfter) { flow.breakPage(); y = 0 }
            }
            val pages = flow.finish().map { it.value }.ifEmpty { listOf(emptyList()) }
            return pages.mapIndexed { i, elements ->
                val decor = ArrayList<HwpxElement>()
                header?.first?.let { els -> els.forEach { decor += it.at(it.xHu, it.yHu + g.header - bodyY).withPageNumber(i + 1) } }
                footer?.first?.let { els -> els.forEach { decor += it.at(it.xHu, it.yHu + footerTop - bodyY).withPageNumber(i + 1) } }
                HwpxPage(g.width, g.height, decor + elements, g.left, bodyY)
            }
        }

        /** The default header or footer of a section, laid out, and its height. */
        private fun headerFooter(sect: Element?, kind: String, g: Geometry): Pair<List<HwpxElement>, Long>? {
            val ref = sect?.children()?.filter { it.localName == kind }?.let { refs ->
                refs.firstOrNull { it.wAttr("type") == "default" } ?: refs.firstOrNull()
            } ?: return null
            val path = docRels[ref.getAttributeNS(NS_R, "id")] ?: return null
            val root = zip.xml(path)?.documentElement ?: return null
            val blocks = ArrayList<Element>()
            flattenBlocks(root, blocks, 0)
            val out = ArrayList<HwpxElement>()
            var y = 0L
            for (item in layBlocks(blocks, g.bodyWidth, 0, partRels = zip.rels(path))) {
                item.laid.lines.forEach { out += it.at(it.xHu, it.yHu + y) }
                y += item.laid.height
            }
            return out to y
        }

        // ---- blocks ----

        private class Item(val laid: TextFlow.Laid, val breakBefore: Boolean = false, val breakAfter: Boolean = false, val keepNext: Boolean = false)

        private fun layBlocks(blocks: List<Element>, width: Long, depth: Int, partRels: Map<String, String> = docRels): List<Item> {
            val out = ArrayList<Item>()
            var prevStyle: String? = null
            var prevContextual = false
            var prevProps: ParaProps? = null
            for ((index, b) in blocks.withIndex()) {
                if (b.localName == "tbl") {
                    table(b, width, depth + 1, partRels)?.let { out += Item(TextFlow.Laid(listOf(it), it.heightHu, it.heightHu, false)) }
                    prevStyle = null; prevContextual = false; prevProps = null
                    continue
                }
                val props = paraPropsOf(b)
                val style = props.style ?: defaultParaStyle
                // Spacing between paragraphs of the same style is dropped when they
                // say so (lists, mostly).
                val nextProps = blocks.getOrNull(index + 1)?.takeIf { it.localName == "p" }?.let { paraPropsOf(it) }
                val contextual = props.contextual == true
                val dropBefore = contextual && prevContextual && prevStyle == style
                val dropAfter = contextual && nextProps?.contextual == true && (nextProps.style ?: defaultParaStyle) == style
                // A run of paragraphs with the same borders is one box: its top line
                // above the first, its bottom line below the last.
                val joinsAbove = prevProps?.let { props.sameBox(it) } == true
                val joinsBelow = nextProps?.let { props.sameBox(it) } == true
                out += paragraph(b, props, width, depth, partRels, dropBefore, dropAfter, joinsAbove, joinsBelow)
                prevStyle = style; prevContextual = contextual; prevProps = props
            }
            return out
        }

        /** A paragraph, split where it has page breaks. */
        private fun paragraph(
            p: Element, props: ParaProps, width: Long, depth: Int, partRels: Map<String, String>,
            dropBefore: Boolean, dropAfter: Boolean, joinsAbove: Boolean = false, joinsBelow: Boolean = false
        ): List<Item> {
            val markProps = runPropsOf(props.style, runProps(p.child(W, "pPr")?.child(W, "rPr")))
            val markStyle = charStyle(markProps)
            val breakNonLatin = props.wordWrap == false
            val parts = ArrayList<MutableList<TextFlow.Atom>>()
            var atoms = ArrayList<TextFlow.Atom>()
            parts += atoms
            val tabs = props.tabs.orEmpty().map { (pos, align) ->
                TextFlow.TabStop(pos - (props.left ?: 0), when (align) { "right", "end" -> ParaAlign.RIGHT; "center" -> ParaAlign.CENTER; else -> ParaAlign.LEFT })
            }
            // The list number (or bullet) and what follows it.
            level(props)?.let { lvl -> numberAtoms(props, lvl, markProps, tabs, breakNonLatin, atoms) }
            val field = FieldState()
            runsOf(p, 0) { r, kind ->
                when (kind) {
                    RunItem.PAGE_BREAK -> { atoms = ArrayList(); parts += atoms }
                    else -> runAtoms(r, props, tabs, breakNonLatin, atoms, field, partRels, depth)
                }
            }
            val shape = shape(props, width)
            return parts.mapIndexed { k, partAtoms ->
                val first = k == 0
                val last = k == parts.size - 1
                val s = TextFlow.ParaShape(
                    shape.align, shape.indent, shape.left, shape.right,
                    if (first && !dropBefore) shape.prev else 0, if (last && !dropAfter) shape.next else 0,
                    shape.spacing, shape.breakNonLatin
                )
                val laid = text.lay(partAtoms, s, width, markStyle, singleLine(markStyle, false))
                Item(decorate(laid, props, width, joinsAbove || !first, joinsBelow || !last), breakBefore = first && props.pageBreakBefore == true,
                    breakAfter = !last, keepNext = last && props.keepNext == true)
            }
        }

        /** Paragraph shading behind the text and its border lines; a paragraph
         *  [joinsAbove] / [joinsBelow] one in the same box leaves that edge open,
         *  and its shading and side lines run through the spacing between them. */
        private fun decorate(laid: TextFlow.Laid, props: ParaProps, width: Long, joinsAbove: Boolean, joinsBelow: Boolean): TextFlow.Laid {
            if (props.shade == null && props.top == null && props.bottom == null && props.leftBorder == null && props.rightBorder == null) return laid
            val left = props.left ?: 0
            val w = (width - left - (props.right ?: 0)).coerceAtLeast(100)
            val lines = laid.lines
            val first = lines.minOfOrNull { it.yHu } ?: 0L
            val top = if (joinsAbove) 0L else first
            val bottom = if (joinsBelow) laid.height else laid.bottom - (props.after ?: 0).coerceAtMost(laid.bottom - first)
            // Shading and side lines in strips, one before each line (from the line
            // to the next, the first from the box's top, the last to its bottom), so
            // that a paragraph running onto the next page takes its strips along.
            val out = ArrayList<HwpxElement>()
            if (!joinsAbove) props.top?.let { b -> out += hline(left, top - b.space, w, b) }
            for ((i, line) in lines.withIndex()) {
                val from = if (i == 0) top else line.yHu
                val to = if (i == lines.size - 1) bottom else lines[i + 1].yHu
                if (to > from) {
                    props.shade?.let { out += HwpxElement.Drawing(left, from, w, to - from, DrawingKind.RECTANGLE, lineArgb = null, fillArgb = it) }
                    props.leftBorder?.let { b -> out += vline(left - b.space - b.widthHu / 2, from, to - from, b) }
                    props.rightBorder?.let { b -> out += vline(left + w + b.space, from, to - from, b) }
                }
                out += line
            }
            if (lines.isEmpty()) props.shade?.let { out += HwpxElement.Drawing(left, top, w, (bottom - top).coerceAtLeast(1), DrawingKind.RECTANGLE, lineArgb = null, fillArgb = it) }
            if (!joinsBelow) props.bottom?.let { b -> out += hline(left, bottom + b.space, w, b) }
            return TextFlow.Laid(out, laid.height, laid.bottom, laid.empty, laid.lead)
        }

        private fun vline(x: Long, y: Long, h: Long, b: Line) =
            HwpxElement.Drawing(x, y, 0, h.coerceAtLeast(1), DrawingKind.LINE, points = listOf(0L to 0L, 0L to h.coerceAtLeast(1)), lineArgb = b.argb, lineWidthHu = b.widthHu)

        private fun hline(x: Long, y: Long, w: Long, b: Line) =
            HwpxElement.Drawing(x, y, w, 0, DrawingKind.LINE, points = listOf(0L to 0L, w to 0L), lineArgb = b.argb, lineWidthHu = b.widthHu)

        private fun shape(props: ParaProps, width: Long): TextFlow.ParaShape {
            val line = props.line
            val spacing = when (props.lineRule) {
                "exact" -> TextFlow.LineSpacing.Exactly((line ?: 1200).coerceIn(100, 200_000))
                "atLeast" -> TextFlow.LineSpacing.AtLeast((line ?: 0).coerceIn(0, 200_000))
                // "auto": 240ths of a single line (stored here x5 like other lengths).
                else -> TextFlow.LineSpacing.Multiple(((line ?: 1200) / 1200f).coerceIn(0.1f, 20f))
            }
            return TextFlow.ParaShape(
                align = when (props.jc) {
                    "center" -> ParaAlign.CENTER
                    "right", "end" -> ParaAlign.RIGHT
                    "both", "justify", "lowKashida", "mediumKashida", "highKashida" -> ParaAlign.JUSTIFY
                    "distribute", "thaiDistribute" -> ParaAlign.DISTRIBUTE
                    else -> ParaAlign.LEFT
                },
                indent = props.firstLine ?: props.hanging?.let { -it } ?: 0,
                left = (props.left ?: 0).coerceIn(-width, width), right = (props.right ?: 0).coerceIn(-width, width),
                prev = (props.before ?: 0).coerceIn(0, 200_000), next = (props.after ?: 0).coerceIn(0, 200_000),
                spacing = spacing, breakNonLatin = props.wordWrap == false
            )
        }

        // ---- runs ----

        private enum class RunItem { RUN, PAGE_BREAK }

        /** Complex field state: inside an instruction, and whether its shown result is replaced. */
        private class FieldState {
            var depth = 0
            var instr = StringBuilder()
            var inResult = false
            var replaced = false
        }

        /** The runs of a paragraph in order, through hyperlinks, content controls,
         *  insertions and simple fields; deleted text is left out. Page breaks are
         *  reported on their own. */
        private fun runsOf(parent: Element, depth: Int, visit: (Element, RunItem) -> Unit) {
            if (depth > Limits.MAX_NESTING) return
            for (c in parent.children()) {
                when {
                    c.namespaceURI == W && c.localName == "r" -> {
                        val hasPageBreak = c.children().any { it.localName == "br" && it.wAttr("type") == "page" }
                        if (!hasPageBreak) { visit(c, RunItem.RUN); continue }
                        // Split the run at its page breaks.
                        visit(c, RunItem.RUN)
                        visit(c, RunItem.PAGE_BREAK)
                    }
                    c.namespaceURI == W && c.localName in setOf("hyperlink", "ins", "moveTo", "smartTag", "customXml", "bdo", "dir") -> runsOf(c, depth + 1, visit)
                    c.namespaceURI == W && c.localName == "sdt" -> c.child(W, "sdtContent")?.let { runsOf(it, depth + 1, visit) }
                    c.namespaceURI == W && c.localName == "fldSimple" -> {
                        val instr = c.wAttr("instr")?.trim()?.uppercase().orEmpty()
                        if (instr.startsWith("PAGE") || instr.startsWith("NUMPAGES")) visit(c, RunItem.RUN) else runsOf(c, depth + 1, visit)
                    }
                    c.namespaceURI == M && (c.localName == "oMath" || c.localName == "oMathPara") -> mathRuns(c, depth + 1, visit)
                }
            }
        }

        /** An equation's text, in order, as plain runs (its layout isn't drawn). */
        private fun mathRuns(parent: Element, depth: Int, visit: (Element, RunItem) -> Unit) {
            if (depth > Limits.MAX_NESTING) return
            for (c in parent.children()) {
                if (c.namespaceURI == M && c.localName == "r") visit(c, RunItem.RUN) else mathRuns(c, depth + 1, visit)
            }
        }

        private fun runAtoms(
            r: Element, props: ParaProps, tabs: List<TextFlow.TabStop>, breakNonLatin: Boolean,
            out: MutableList<TextFlow.Atom>, field: FieldState, partRels: Map<String, String>, depth: Int
        ) {
            if (r.localName == "fldSimple") {
                val instr = r.wAttr("instr")?.trim()?.uppercase().orEmpty()
                val rp = runPropsOf(props.style, runProps(r.child(W, "r")?.child(W, "rPr")))
                val style = charStyle(rp)
                text.addText(if (instr.startsWith("NUMPAGES")) "$PAGE_COUNT_MARK" else "$PAGE_NUMBER_MARK", style, breakNonLatin, out, singleLine(style, false))
                return
            }
            val rp = runPropsOf(props.style, runProps(r.child(W, "rPr")))
            if (rp.vanish == true) return
            val style = charStyle(rp)
            val face = rp.ascii ?: rp.asciiTheme?.let { themeFonts[it] }
            for (c in r.children()) {
                // Inside a field instruction, or a page number field's shown result.
                val skip = field.depth > 0 && (!field.inResult || field.replaced)
                when (c.localName) {
                    "fldChar" -> when (c.wAttr("fldCharType")) {
                        "begin" -> { field.depth++; field.instr = StringBuilder(); field.inResult = false; field.replaced = false }
                        "separate" -> {
                            field.inResult = true
                            val instr = field.instr.toString().trim().uppercase()
                            if (instr.startsWith("PAGE") || instr.startsWith("NUMPAGES")) {
                                field.replaced = true
                                text.addText(if (instr.startsWith("NUMPAGES")) "$PAGE_COUNT_MARK" else "$PAGE_NUMBER_MARK", style, breakNonLatin, out, singleLine(style, false))
                            }
                        }
                        "end" -> { if (field.depth > 0) field.depth--; field.inResult = false; field.replaced = false }
                    }
                    "instrText" -> if (field.depth > 0 && !field.inResult) field.instr.append(c.textContent)
                    "t" -> if (!skip) {
                        var t = c.textContent.replace('\n', ' ').replace('\r', ' ')
                        if (rp.caps == true) t = t.uppercase()
                        t = fixSymbolText(t, face)
                        val hangul = t.any { TextFlow.isNonLatin(it) }
                        text.addText(t, style, breakNonLatin, out, singleLine(style, hangul))
                    }
                    "tab" -> if (!skip) out += text.tab(style, tabs, defaultTab)
                    "br", "cr" -> if (!skip && c.wAttr("type") != "page") out += text.lineBreak(style)
                    "noBreakHyphen" -> if (!skip) text.addText("-", style, breakNonLatin, out)
                    "sym" -> if (!skip) {
                        val code = c.wAttr("char")?.toIntOrNull(16) ?: continue
                        text.addText(fixSymbolText(code.toChar().toString(), c.wAttr("font")), style, breakNonLatin, out)
                    }
                    "drawing" -> if (!skip) drawing(c, partRels)?.let { out += text.inline(it, style) }
                }
            }
        }

        private fun numberAtoms(props: ParaProps, lvl: Level, markProps: RunProps, tabs: List<TextFlow.TabStop>, breakNonLatin: Boolean, out: MutableList<TextFlow.Atom>) {
            val numId = props.numId ?: return
            val ilvl = (props.ilvl ?: 0).coerceIn(0, 8)
            val levels = abstracts[nums[numId]?.first] ?: return
            val count = counters.getOrPut(numId) { IntArray(9) { -1 } }
            val starts = nums[numId]?.second.orEmpty()
            count[ilvl] = if (count[ilvl] < 0) (starts[ilvl] ?: lvl.start) else count[ilvl] + 1
            for (k in ilvl + 1..8) count[k] = -1
            if (lvl.format == "none") return
            val label = Regex("%([1-9])").replace(lvl.text) { m ->
                val k = m.groupValues[1].toInt() - 1
                val n = count.getOrElse(k) { 1 }.let { if (it < 0) (starts[k] ?: levels[k]?.start ?: 1) else it }
                numberText(n, if (lvl.format == "bullet") "decimal" else levels[k]?.format ?: "decimal")
            }
            val rp = lvl.rPr.over(markProps)
            val style = charStyle(rp)
            val face = rp.ascii ?: rp.asciiTheme?.let { themeFonts[it] }
            val shown = fixSymbolText(label, face)
            text.addText(shown, style, breakNonLatin, out, singleLine(style, false))
            when (lvl.suffix) {
                "space" -> text.addText(" ", style, breakNonLatin, out)
                "nothing" -> {}
                else -> out += text.tab(style, tabs, defaultTab)
            }
        }

        // ---- pictures ----

        private fun drawing(d: Element, partRels: Map<String, String>): HwpxElement? {
            val holder = d.child(WP, "inline") ?: d.child(WP, "anchor") ?: return null
            val ext = holder.child(WP, "extent") ?: return null
            val w = (ext.getAttribute("cx").toLongOrNull() ?: 0) / 127
            val h = (ext.getAttribute("cy").toLongOrNull() ?: 0) / 127
            if (w <= 0 || h <= 0 || w > 2_000_000 || h > 2_000_000) return null
            val blip = holder.first(NS_A, "blip") ?: return null
            val path = partRels[blip.getAttributeNS(NS_R, "embed")] ?: return null
            val entry = zip.getEntry(path) ?: return null
            val crop = holder.first(PIC, "blipFill")?.child(NS_A, "srcRect")?.let { r ->
                fun f(a: String) = (r.getAttribute(a).toLongOrNull() ?: 0L) / 100_000.0
                ImageCrop.of(f("l"), f("t"), f("r"), f("b"), 1.0, 1.0)
            }
            return HwpxElement.ImageEl(0, 0, w, h, zipEntry = null, crop = crop, bytes = {
                pictures.get(path) {
                    try { zip.readCapped(entry, SafeBitmaps.MAX_IMAGE_BYTES) } catch (e: Exception) { null }
                }
            })
        }

        // ---- tables ----

        private class CellBorders(val top: Line?, val bottom: Line?, val left: Line?, val right: Line?, val insideH: Line?, val insideV: Line?)

        private fun borders(el: Element?): CellBorders? {
            el ?: return null
            fun side(vararg names: String) = names.firstNotNullOfOrNull { el.child(W, it) }?.let { line(it) }
            return CellBorders(side("top"), side("bottom"), side("left", "start"), side("right", "end"), side("insideH"), side("insideV"))
        }

        /** A border line; a "nil"/"none" one is no line (Line with zero width). */
        private fun line(b: Element): Line? {
            val v = b.wAttr("val") ?: return null
            if (v == "nil" || v == "none") return Line(0, 0)
            val color = b.wAttr("color")?.let { if (it == "auto") 0xFF000000.toInt() else hex(it) } ?: 0xFF000000.toInt()
            val sz = (b.wAttr("sz")?.toDoubleOrNull()?.toLong() ?: 4).coerceIn(1, 96)
            return Line(color, (sz * 25 / 2).coerceAtLeast(10), (b.wAttr("space")?.toDoubleOrNull()?.toLong() ?: 0).coerceIn(0, 31) * 100)
        }

        private fun table(t: Element, width: Long, depth: Int, partRels: Map<String, String>): HwpxElement.Table? {
            if (depth > Limits.MAX_NESTING) return null
            val tblPr = t.child(W, "tblPr")
            val styleId = tblPr?.child(W, "tblStyle")?.wAttr("val") ?: defaultTableStyle
            val styleChain = chain(styleId)
            // Table borders and cell margins: the style's, then the table's own.
            var tb: CellBorders? = null
            var cellMar: Element? = null
            for (s in styleChain) {
                s.tblPr?.child(W, "tblBorders")?.let { tb = mergeBorders(tb, borders(it)) }
                s.tblPr?.child(W, "tblCellMar")?.let { cellMar = it }
            }
            tblPr?.child(W, "tblBorders")?.let { tb = mergeBorders(tb, borders(it)) }
            tblPr?.child(W, "tblCellMar")?.let { cellMar = it }
            fun mar(name: String, alt: String, default: Long) = (cellMar?.child(W, name) ?: cellMar?.child(W, alt))?.twips("w") ?: default
            val marL = mar("left", "start", 540); val marR = mar("right", "end", 540)
            val marT = mar("top", "top", 0); val marB = mar("bottom", "bottom", 0)

            var cols = t.child(W, "tblGrid")?.children()?.filter { it.localName == "gridCol" }?.map { (it.twips("w") ?: 0).coerceIn(0, 2_000_000) }.orEmpty()
            val rowsXml = t.children().filter { it.localName == "tr" }.take(10_000)
            if (rowsXml.isEmpty()) return null
            if (cols.isEmpty() || cols.all { it == 0L }) {
                val first = rowsXml.first().children().filter { it.localName == "tc" }
                cols = first.map { (it.child(W, "tcPr")?.child(W, "tcW")?.twips("w") ?: (width / maxOf(first.size, 1))).coerceAtLeast(500) }
            }
            if (cols.isEmpty()) return null
            val colCount = cols.size
            val grid = MutableList(rowsXml.size) { MutableList<TableCell?>(colCount) { null } }
            val rowHeights = ArrayList<Long>()
            val exact = BooleanArray(rowsXml.size)
            // A merged run's content height counts on its last row.
            val content = HashMap<Pair<Int, Int>, Long>()

            for ((r, tr) in rowsXml.withIndex()) {
                val trPr = tr.child(W, "trPr")
                val trH = trPr?.child(W, "trHeight")
                rowHeights += (trH?.twips("val") ?: 0).coerceIn(0, 2_000_000)
                exact[r] = trH?.wAttr("hRule") == "exact"
                var col = trPr?.child(W, "gridBefore")?.wAttr("val")?.toIntOrNull()?.coerceIn(0, colCount) ?: 0
                for (tc in tr.children().filter { it.localName == "tc" }) {
                    if (col >= colCount) break
                    val tcPr = tc.child(W, "tcPr")
                    val span = (tcPr?.child(W, "gridSpan")?.wAttr("val")?.toIntOrNull() ?: 1).coerceIn(1, colCount - col)
                    val vMerge = tcPr?.child(W, "vMerge")
                    if (vMerge != null && vMerge.wAttr("val") != "restart") {
                        // Continues the merged cell above, which then spans this row too.
                        val top = (r - 1 downTo 0).firstOrNull { grid[it][col] != null }
                        val above = top?.let { grid[it][col] }
                        if (top != null && above != null && top + above.rowSpan == r) grid[top][col] = above.copy(rowSpan = r - top + 1)
                        col += span
                        continue
                    }
                    val cellWidth = cols.subList(col, col + span).sum()
                    val m = tcPr?.child(W, "tcMar")
                    fun cm(name: String, alt: String, d: Long) = (m?.child(W, name) ?: m?.child(W, alt))?.twips("w") ?: d
                    val l = cm("left", "start", marL); val rr = cm("right", "end", marR)
                    val tp = cm("top", "top", marT); val bt = cm("bottom", "bottom", marB)
                    val inner = (cellWidth - l - rr).coerceAtLeast(500)
                    val blocks = ArrayList<Element>()
                    flattenBlocks(tc, blocks, 0)
                    val elements = ArrayList<HwpxElement>()
                    var y = tp
                    var bottom = tp
                    val outerStyle = tableStyle
                    tableStyle = styleChain
                    val laidBlocks = try { layBlocks(blocks, inner, depth, partRels) } finally { tableStyle = outerStyle }
                    for (item in laidBlocks) {
                        item.laid.lines.forEach { elements += it.at(it.xHu + l, it.yHu + y) }
                        bottom = maxOf(bottom, y + item.laid.bottom)
                        y += item.laid.height
                        if (elements.size > TextFlow.MAX_LINES) break
                    }
                    content[r to col] = bottom + bt
                    val cb = borders(tcPr?.child(W, "tcBorders"))
                    val lastCol = col + span >= colCount
                    fun pick(own: Line?, outer: Line?, inside: Line?, edge: Boolean) = (own ?: if (edge) outer else inside)
                    val fill = BorderFill(
                        left = border(pick(cb?.left, tb?.left, tb?.insideV, col == 0)),
                        right = border(pick(cb?.right, tb?.right, tb?.insideV, lastCol)),
                        top = border(pick(cb?.top, tb?.top, tb?.insideH, r == 0)),
                        bottom = border(pick(cb?.bottom, tb?.bottom, tb?.insideH, r == rowsXml.size - 1)),
                        fillArgb = tcPr?.child(W, "shd")?.let { shade(it) }
                    )
                    grid[r][col] = TableCell(span, 1, elements, borderFill = fill, vAlign = when (tcPr?.child(W, "vAlign")?.wAttr("val")) {
                        "center" -> 1; "bottom" -> 2; else -> 0
                    })
                    col += span
                }
            }
            // Bottom borders of merged cells come from their last row.
            val heights = rowHeights.toMutableList()
            for (r in grid.indices) {
                if (exact[r]) continue
                var need = heights[r]
                for (c in 0 until colCount) {
                    val cell = grid[r][c] ?: continue
                    if (cell.rowSpan == 1) need = maxOf(need, content[r to c] ?: 0)
                }
                heights[r] = need.coerceAtLeast(200)
            }
            for (r in grid.indices) for (c in 0 until colCount) {
                val cell = grid[r][c] ?: continue
                if (cell.rowSpan <= 1) continue
                val last = (r + cell.rowSpan - 1).coerceAtMost(grid.size - 1)
                val have = (r..last).sumOf { heights[it] }
                val need = content[r to c] ?: 0
                if (need > have && !exact[last]) heights[last] += need - have
            }
            val ind = tblPr?.child(W, "tblInd")?.twips("w") ?: 0
            val total = cols.sum()
            val x = when (tblPr?.child(W, "jc")?.wAttr("val")) {
                "center" -> (width - total) / 2
                "right", "end" -> width - total
                else -> ind
            }
            return HwpxElement.Table(x, 0, total, heights.sum(), cols, heights, grid)
        }

        private fun mergeBorders(base: CellBorders?, own: CellBorders?): CellBorders? {
            own ?: return base
            base ?: return own
            return CellBorders(own.top ?: base.top, own.bottom ?: base.bottom, own.left ?: base.left, own.right ?: base.right,
                own.insideH ?: base.insideH, own.insideV ?: base.insideV)
        }

        private fun border(l: Line?): Border? = l?.takeIf { it.widthHu > 0 }?.let { Border(it.argb, it.widthHu) }

        // ---- property elements ----

        private fun runProps(rPr: Element?): RunProps {
            rPr ?: return RunProps()
            fun flag(name: String) = rPr.child(W, name)?.let { it.wAttr("val").let { v -> v == null || v.isTrue() } }
            val fonts = rPr.child(W, "rFonts")
            return RunProps(
                size = rPr.child(W, "sz")?.wAttr("val")?.toFloatOrNull()?.let { it / 2f },
                bold = flag("b"), italic = flag("i"),
                underline = rPr.child(W, "u")?.let { it.wAttr("val") != "none" },
                strike = flag("strike") ?: flag("dstrike"),
                color = rPr.child(W, "color")?.wAttr("val")?.let { if (it == "auto") 0xFF000000.toInt() else hex(it) },
                vert = when (rPr.child(W, "vertAlign")?.wAttr("val")) { "superscript" -> 1; "subscript" -> -1; "baseline" -> 0; else -> null },
                ascii = fonts?.wAttr("ascii") ?: fonts?.wAttr("hAnsi"),
                eastAsia = fonts?.wAttr("eastAsia"),
                asciiTheme = fonts?.wAttr("asciiTheme") ?: fonts?.wAttr("hAnsiTheme"),
                eastAsiaTheme = fonts?.wAttr("eastAsiaTheme"),
                caps = flag("caps"), vanish = flag("vanish"),
                scale = rPr.child(W, "w")?.wAttr("val")?.toIntOrNull(),
                spacing = rPr.child(W, "spacing")?.wAttr("val")?.toIntOrNull(),
                style = rPr.child(W, "rStyle")?.wAttr("val")
            )
        }

        private fun paraProps(pPr: Element?): ParaProps {
            pPr ?: return ParaProps()
            val ind = pPr.child(W, "ind")
            val sp = pPr.child(W, "spacing")
            val num = pPr.child(W, "numPr")
            val bdr = pPr.child(W, "pBdr")
            fun flag(name: String) = pPr.child(W, name)?.let { it.wAttr("val").let { v -> v == null || v.isTrue() } }
            return ParaProps(
                jc = pPr.child(W, "jc")?.wAttr("val"),
                left = ind?.let { it.twips("left") ?: it.twips("start") },
                right = ind?.let { it.twips("right") ?: it.twips("end") },
                firstLine = ind?.twips("firstLine"), hanging = ind?.twips("hanging"),
                before = sp?.twips("before"), after = sp?.twips("after"),
                line = sp?.twips("line"), lineRule = sp?.wAttr("lineRule") ?: sp?.wAttr("line")?.let { "auto" },
                contextual = flag("contextualSpacing"), pageBreakBefore = flag("pageBreakBefore"), keepNext = flag("keepNext"),
                numId = num?.child(W, "numId")?.wAttr("val"), ilvl = num?.child(W, "ilvl")?.wAttr("val")?.toIntOrNull(),
                tabs = pPr.child(W, "tabs")?.children()?.filter { it.localName == "tab" }?.mapNotNull { tab ->
                    tab.twips("pos")?.let { it to (tab.wAttr("val") ?: "left") }
                },
                top = bdr?.child(W, "top")?.let { line(it) }?.takeIf { it.widthHu > 0 },
                bottom = bdr?.child(W, "bottom")?.let { line(it) }?.takeIf { it.widthHu > 0 },
                shade = pPr.child(W, "shd")?.let { shade(it) },
                wordWrap = flag("wordWrap"),
                style = pPr.child(W, "pStyle")?.wAttr("val"),
                leftBorder = bdr?.let { it.child(W, "left") ?: it.child(W, "start") }?.let { line(it) }?.takeIf { it.widthHu > 0 },
                rightBorder = bdr?.let { it.child(W, "right") ?: it.child(W, "end") }?.let { line(it) }?.takeIf { it.widthHu > 0 }
            )
        }

        private fun shade(shd: Element): Int? {
            val fill = shd.wAttr("fill") ?: return null
            if (fill == "auto") return null
            return hex(fill)?.takeIf { it != 0xFFFFFFFF.toInt() }
        }
    }

    // ---- helpers ------------------------------------------------------------

    /** A list number in Word's number format [fmt]. */
    internal fun numberText(n: Int, fmt: String): String {
        fun letters(base: Char) = if (n <= 0) "" else {
            // a..z, then aa..zz, as Word counts.
            val k = (n - 1) % 26
            (base + k).toString().repeat((n - 1) / 26 + 1)
        }
        fun roman(): String {
            if (n !in 1..3999) return n.toString()
            val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val sy = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            var x = n
            return buildString { for (i in v.indices) while (x >= v[i]) { append(sy[i]); x -= v[i] } }
        }
        fun cycle(chars: String) = if (n <= 0) n.toString() else chars[(n - 1) % chars.length].toString()
        return when (fmt) {
            "decimalZero" -> if (n in 0..9) "0$n" else n.toString()
            "upperRoman" -> roman()
            "lowerRoman" -> roman().lowercase()
            "upperLetter" -> letters('A')
            "lowerLetter" -> letters('a')
            "ganada" -> cycle("가나다라마바사아자차카타파하")
            "chosung" -> cycle("ㄱㄴㄷㄹㅁㅂㅅㅇㅈㅊㅋㅌㅍㅎ")
            "decimalEnclosedCircle", "decimalEnclosedCircleChinese" -> if (n in 1..20) (0x2460 + n - 1).toChar().toString() else n.toString()
            "decimalFullWidth", "decimalFullWidth2" -> n.toString().map { (it.code - '0'.code + 0xFF10).toChar() }.joinToString("")
            "koreanDigital", "koreanDigital2", "koreanLegal" -> koreanDigits(n)
            "koreanCounting" -> if (n in 1..10) listOf("하나", "둘", "셋", "넷", "다섯", "여섯", "일곱", "여덟", "아홉", "열")[n - 1] else n.toString()
            "none" -> ""
            else -> n.toString()
        }
    }

    private fun koreanDigits(n: Int): String {
        if (n !in 1..9999) return n.toString()
        val d = "영일이삼사오육칠팔구"
        val units = listOf("천", "백", "십", "")
        val s = n.toString().padStart(4, '0')
        return buildString {
            for (i in 0 until 4) {
                val v = s[i] - '0'
                if (v == 0) continue
                if (v != 1 || units[i].isEmpty()) append(d[v])
                append(units[i])
            }
        }
    }

    private fun Element.wAttr(name: String): String? = getAttributeNS(W, name).ifEmpty { null }

    /** A length in twentieths of a point, as HWPUNIT (100 per point). */
    private fun Element.twips(name: String): Long? = wAttr(name)?.let { v ->
        // Google Docs writes lengths with decimals ("609.09").
        v.toLongOrNull()?.times(5) ?: v.toDoubleOrNull()?.takeIf { it.isFinite() && kotlin.math.abs(it) < 1e7 }?.let { (it * 5).toLong() }
    }

    private fun String?.isTrue() = this == null || this == "1" || this == "true" || this == "on"

    private fun hex(v: String): Int? = v.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }

    /** [this] with [mark] in its text replaced by [value]. */
    private fun HwpxElement.replacingText(mark: Char, value: String): HwpxElement = when (this) {
        is HwpxElement.TextRun ->
            if (spans.none { mark in it.text }) this
            else copy(spans = spans.map { it.copy(text = it.text.replace(mark.toString(), value)) })
        is HwpxElement.Table -> copy(cells = cells.map { row -> row.map { c -> c?.copy(elements = c.elements.map { it.replacingText(mark, value) }) } })
        else -> this
    }
}
