package io.github.baeseungwon1010.officecanvas.docx

import io.github.baeseungwon1010.officecanvas.hwpx.Border
import io.github.baeseungwon1010.officecanvas.hwpx.BorderFill
import io.github.baeseungwon1010.officecanvas.hwpx.CharStyle
import io.github.baeseungwon1010.officecanvas.hwpx.DrawingKind
import io.github.baeseungwon1010.officecanvas.hwpx.EquationLayout
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
import io.github.baeseungwon1010.officecanvas.hwpx.widthHu
import io.github.baeseungwon1010.officecanvas.ppt.fixSymbolText
import io.github.baeseungwon1010.officecanvas.pptx.NS_A
import io.github.baeseungwon1010.officecanvas.pptx.NS_MC
import io.github.baeseungwon1010.officecanvas.pptx.NS_R
import io.github.baeseungwon1010.officecanvas.pptx.Theme
import io.github.baeseungwon1010.officecanvas.pptx.attr
import io.github.baeseungwon1010.officecanvas.pptx.colorIn
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
    private const val VML_OFFICE = "urn:schemas-microsoft-com:office:office"
    private const val VML = "urn:schemas-microsoft-com:vml"
    private const val WPS = "http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
    private const val WPG = "http://schemas.microsoft.com/office/word/2010/wordprocessingGroup"

    /** Prefixes an <mc:Choice> may require for it to be read rather than its fallback. */
    private val READ_CHOICES = setOf("wps", "wpg", "wp14", "w14", "w15", "a14")

    /** Room for the line between the text and the footnotes. */
    private const val NOTE_SEPARATOR_HU = 1200L

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
        val vanish: Boolean? = null, val scale: Int? = null, val spacing: Int? = null, val style: String? = null,
        val background: Int? = null
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
            caps ?: base.caps, vanish ?: base.vanish, scale ?: base.scale, spacing ?: base.spacing, style ?: base.style,
            background ?: base.background
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

    /**
     * An object placed by offsets ([hFrom]: page, margin, column...; [vFrom]: page,
     * margin, paragraph, line...) or aligned in that area, rather than set in a
     * line: a floating picture, shape or text box. Text it [wraps] doesn't run over
     * it: here, text it is anchored beside goes on below it.
     */
    private class Floating(
        val el: HwpxElement,
        val hFrom: String, val hAlign: String?, val hOffset: Long,
        val vFrom: String, val vAlign: String?, val vOffset: Long,
        val behind: Boolean, val wraps: Boolean, val distBottom: Long
    ) {
        val followsText get() = vFrom == "paragraph" || vFrom == "line"
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
        private var evenAndOddHeaders = false
        private val themeFonts = HashMap<String, String>()
        private val nums = HashMap<String, Pair<String, Map<Int, Int>>>() // numId -> (abstractNumId, start overrides)
        private val abstracts = HashMap<String, Map<Int, Level>>()
        private val counters = HashMap<String, IntArray>()
        private val pictures = RecentBytes<String>(32L shl 20)
        private val theme: Theme = Theme.load(zip, docRels.values.firstOrNull { it.contains("theme/") } ?: "word/theme/theme1.xml")

        /** The page of the section being laid out; null inside a table cell or a
         *  text box, where floating objects are placed from their paragraph. */
        private var pageGeometry: Geometry? = null

        /** The text color a shape's style gives the text in it. */
        private var boxTextColor: Int? = null

        /** Footnotes and endnotes by id, and their parts' relationships. */
        private val footnotes = HashMap<String, Element>()
        private val endnotes = HashMap<String, Element>()
        private var footnoteRels = emptyMap<String, String>()
        private var endnoteRels = emptyMap<String, String>()
        private var footnoteFormat = "decimal"
        private var endnoteFormat = "lowerRoman"
        private var footnoteCount = 0
        /** Endnotes in the order they are referenced, with the numbers they show. */
        private val endnoteOrder = ArrayList<Pair<String, String>>()
        /** While a note is laid out: the number its own reference mark shows. */
        private var noteMark: String? = null
        /** Footnotes referenced by what was laid out since they were last taken,
         *  each laid out in [noteWidth]. */
        private val pendingNotes = ArrayList<TextFlow.Laid>()
        private var noteWidth = 2000L

        fun read(): HwpxDoc {
            val document = zip.xml(documentPath)?.documentElement ?: throw ParseException("not a .docx: word/document.xml is missing")
            val body = document.child(W, "body") ?: throw ParseException("not a .docx: no body")
            readTheme()
            readStyles()
            readNumbering()
            zip.xml("word/settings.xml")?.documentElement?.let { settings ->
                settings.child(W, "defaultTabStop")?.twips("val")?.takeIf { it > 0 }?.let { defaultTab = it }
                evenAndOddHeaders = settings.child(W, "evenAndOddHeaders")?.let { it.wAttr("val").isTrue() } == true
                settings.child(W, "footnotePr")?.child(W, "numFmt")?.wAttr("val")?.let { footnoteFormat = it }
                settings.child(W, "endnotePr")?.child(W, "numFmt")?.wAttr("val")?.let { endnoteFormat = it }
            }
            readNotes()

            // Sections end at a paragraph holding a <w:sectPr>, and the body's own
            // <w:sectPr> describes the last one.
            val blocks = ArrayList<Element>()
            flattenBlocks(body, blocks, 0)
            val pages = ArrayList<HwpxPage>()
            var current = ArrayList<Element>()
            val lastSect = blocks.indexOfLast { b -> b.localName == "p" && b.child(W, "pPr")?.child(W, "sectPr") != null }
            val trailing = lastSect < blocks.size - 1
            for ((i, b) in blocks.withIndex()) {
                if (pages.size >= Limits.MAX_PAGES) break
                current += b
                val sect = if (b.localName == "p") b.child(W, "pPr")?.child(W, "sectPr") else null
                if (sect != null) { pages += section(current, sect, last = i == lastSect && !trailing); current = ArrayList() }
            }
            if ((current.isNotEmpty() || pages.isEmpty()) && pages.size < Limits.MAX_PAGES) {
                pages += section(current, body.child(W, "sectPr"), last = true)
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
                if (c.namespaceURI != W && c.namespaceURI != NS_MC) continue
                when (c.localName) {
                    "p", "tbl" -> out += c
                    "sdt" -> c.child(W, "sdtContent")?.let { flattenBlocks(it, out, depth + 1) }
                    "customXml", "ins", "moveTo" -> flattenBlocks(c, out, depth + 1)
                    "AlternateContent" -> alternate(c)?.let { flattenBlocks(it, out, depth + 1) }
                }
            }
        }

        // ---- definitions ----

        private fun readNotes() {
            fun load(kind: String, into: HashMap<String, Element>): Map<String, String> {
                val path = docRels.values.firstOrNull { it.substringAfterLast('/') == "$kind.xml" } ?: return emptyMap()
                val root = zip.xml(path)?.documentElement ?: return emptyMap()
                for (n in root.children()) {
                    // The separators between the text and the notes aren't notes.
                    if (n.localName == kind.dropLast(1) && n.wAttr("type").let { it == null || it == "normal" }) n.wAttr("id")?.let { into[it] = n }
                }
                return zip.rels(path)
            }
            footnoteRels = load("footnotes", footnotes)
            endnoteRels = load("endnotes", endnotes)
        }

        /** A note's paragraphs laid out [noteWidth] wide, its reference mark showing [mark]. */
        private fun layNote(note: Element, mark: String, rels: Map<String, String>): TextFlow.Laid {
            val blocks = ArrayList<Element>()
            flattenBlocks(note, blocks, 0)
            val outerPage = pageGeometry; val outerStyle = tableStyle; val outerMark = noteMark
            pageGeometry = null; tableStyle = emptyList(); noteMark = mark
            val items = try { layBlocks(blocks, noteWidth, 1, rels) } finally { pageGeometry = outerPage; tableStyle = outerStyle; noteMark = outerMark }
            val (elements, _) = stack(items, 0, 0)
            val height = items.sumOf { it.laid.height }
            return TextFlow.Laid(elements, height, height, false)
        }

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
            props = own.over(props)
            return if (props.color == null && boxTextColor != null) props.copy(color = boxTextColor) else props
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
                script = r.vert ?: 0,
                backgroundArgb = r.background
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

        private fun section(blocks: List<Element>, sect: Element?, last: Boolean): List<HwpxPage> {
            val g = geometry(sect)
            val headers = headerFooter(sect, "headerReference", g)
            val footers = headerFooter(sect, "footerReference", g)
            val titlePage = sect?.child(W, "titlePg")?.let { it.wAttr("val").isTrue() } == true
            val numbering = sect?.child(W, "pgNumType")
            numbering?.wAttr("start")?.toIntOrNull()?.let { firstPageNumber = it.coerceIn(0, 1_000_000) }
            val numberFormat = numbering?.wAttr("fmt") ?: "decimal"
            // The body starts below the top margin, or below a header taller than it.
            val headerBottom = g.header + (headers.values.maxOfOrNull { it.height } ?: 0L)
            val bodyY = maxOf(g.top, headerBottom)
            val footerTop = g.height - g.footer - (footers.values.maxOfOrNull { it.height } ?: 0L)
            val bodyBottom = minOf(g.height - g.bottom, footerTop)
            val bodyHeight = (bodyBottom - bodyY).coerceAtLeast(2000)
            val flow = PageFlow(bodyHeight)
            var y = 0L
            pageGeometry = g
            noteWidth = g.bodyWidth
            // Endnotes go after the last section's text.
            val laid = layBlocks(blocks, g.bodyWidth, 0) + if (last) endnoteItems() else emptyList()
            // Footnotes go at the bottom of the page their reference is on, the
            // text above them.
            val notesOn = HashMap<Int, MutableList<TextFlow.Laid>>()
            fun notesHeight(page: Int, more: List<TextFlow.Laid> = emptyList()): Long {
                val all = notesOn[page].orEmpty() + more
                return if (all.isEmpty()) 0L else all.sumOf { it.height } + NOTE_SEPARATOR_HU
            }
            for ((index, item) in laid.withIndex()) {
                if (flow.full) break
                if (item.breakBefore && (y > 0 || flow.page > 0)) { flow.breakPage(); y = 0 }
                val room = (bodyHeight - notesHeight(flow.page, item.notes)).coerceAtLeast(bodyHeight / 4)
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
                    if (y + need > room && need <= room) { flow.breakPage(); y = 0 }
                }
                // Space before a paragraph is dropped at the top of a page the text ran
                // onto (not after a page break the document asks for).
                val top = if (y == 0L && flow.page > 0 && !item.breakBefore && !laid.getOrNull(index - 1).let { it?.breakAfter == true }) -item.laid.lead else y
                val (anchorY, next) = text.place(flow, item.laid, top, room)
                y = next
                if (item.notes.isNotEmpty()) notesOn.getOrPut(text.anchorPage) { ArrayList() } += item.notes
                for (f in item.floats) flow.put(text.anchorPage, f.el.at(floatX(f, g.bodyWidth, g), floatY(f, g, bodyY, anchorY)), f.behind)
                if (item.breakAfter) { flow.breakPage(); y = 0 }
            }
            val pages = flow.finish().map { (index, elements) ->
                val notes = notesOn[index] ?: return@map elements
                // The notes stacked up from the body's bottom, under a short line.
                val out = ArrayList(elements)
                var ny = bodyHeight - notesHeight(index) + NOTE_SEPARATOR_HU
                out += hline(0, ny - NOTE_SEPARATOR_HU / 2, minOf(g.bodyWidth / 3, 14400), Line(0xFF000000.toInt(), 50))
                for (note in notes) {
                    note.lines.forEach { out += it.at(it.xHu, it.yHu + ny) }
                    ny += note.height
                }
                out
            }.ifEmpty { listOf(emptyList()) }
            val border = pageBorder(sect?.child(W, "pgBorders"), g, bodyY)
            return pages.mapIndexed { i, elements ->
                val number = firstPageNumber + i
                // A section with a title page has its own first header and footer
                // (none when it defines none); even pages may have theirs.
                val variant = when {
                    titlePage && i == 0 -> "first"
                    evenAndOddHeaders && number % 2 == 0 -> "even"
                    else -> "default"
                }
                val shown = numberText(number, numberFormat)
                val decor = ArrayList<HwpxElement>()
                headers[variant]?.let { decor += it.placed(g, bodyY, g.header - bodyY) }
                footers[variant]?.let { decor += it.placed(g, bodyY, footerTop - bodyY) }
                HwpxPage(g.width, g.height, (border + decor + elements).map { it.replacingText(PAGE_NUMBER_MARK, shown) }, g.left, bodyY)
            }.also { firstPageNumber += pages.size }
        }

        /** The endnotes referenced so far, under a short line, to follow the text. */
        private fun endnoteItems(): List<Item> {
            if (endnoteOrder.isEmpty()) return emptyList()
            val rule = TextFlow.Laid(listOf(hline(0, NOTE_SEPARATOR_HU / 2, minOf(noteWidth / 3, 14400), Line(0xFF000000.toInt(), 50))), NOTE_SEPARATOR_HU, NOTE_SEPARATOR_HU, false)
            return listOf(Item(rule)) + endnoteOrder.take(10_000).mapNotNull { (id, mark) -> endnotes[id]?.let { Item(layNote(it, mark, endnoteRels)) } }
        }

        /** The number the next page gets: sections go on counting unless they restart. */
        private var firstPageNumber = 1

        /** Header and footer parts by type; a section that doesn't name one of a
         *  type keeps the previous section's. */
        private val partOfType = HashMap<String, String>()

        /** The lines around the page ([pgBorders]): measured from the page's edge,
         *  or (by default) from the text's. */
        private fun pageBorder(b: Element?, g: Geometry, bodyY: Long): List<HwpxElement> {
            b ?: return emptyList()
            val fromPage = b.wAttr("offsetFrom") == "page"
            val out = ArrayList<HwpxElement>()
            // "space" is in points either way.
            fun side(name: String) = b.child(W, name)?.let { line(it) }?.takeIf { it.widthHu > 0 }
            val top = side("top"); val bottom = side("bottom"); val left = side("left") ?: side("start"); val right = side("right") ?: side("end")
            fun x(l: Line?, isLeft: Boolean) = when {
                fromPage -> if (isLeft) (l?.space ?: 0) else g.width - (l?.space ?: 0)
                isLeft -> g.left - (l?.space ?: 0)
                else -> g.width - g.right + (l?.space ?: 0)
            } - g.left
            fun y(l: Line?, isTop: Boolean) = when {
                fromPage -> if (isTop) (l?.space ?: 0) else g.height - (l?.space ?: 0)
                isTop -> g.top - (l?.space ?: 0)
                else -> g.height - g.bottom + (l?.space ?: 0)
            } - bodyY
            val x0 = x(left, true); val x1 = x(right, false); val y0 = y(top, true); val y1 = y(bottom, false)
            if (x1 <= x0 || y1 <= y0) return emptyList()
            top?.let { out += hline(x0, y0, x1 - x0, it) }
            bottom?.let { out += hline(x0, y1, x1 - x0, it) }
            left?.let { out += vline(x0, y0, y1 - y0, it) }
            right?.let { out += vline(x1, y0, y1 - y0, it) }
            return out
        }

        /** A section's headers or footers ([kind]: "headerReference" or
         *  "footerReference") by type (default, first, even), laid out, with their heights. */
        private fun headerFooter(sect: Element?, kind: String, g: Geometry): Map<String, Part> {
            sect?.children()?.filter { it.localName == kind }?.forEach { ref ->
                val path = docRels[ref.getAttributeNS(NS_R, "id")] ?: return@forEach
                partOfType["$kind/${ref.wAttr("type") ?: "default"}"] = path
            }
            return listOf("default", "first", "even").mapNotNull { type ->
                partOfType["$kind/$type"]?.let { path -> layPart(path, g)?.let { type to it } }
            }.toMap()
        }

        /** A header or footer laid out: its lines (from its top), its height, and
         *  its floating objects with the top of their paragraphs. */
        private inner class Part(val elements: List<HwpxElement>, val height: Long, val floats: List<Pair<Floating, Long>>) {
            /** On a page, its top [dy] below the body's. */
            fun placed(g: Geometry, bodyY: Long, dy: Long): List<HwpxElement> =
                floats.filter { it.first.behind }.map { (f, top) -> f.el.at(floatX(f, g.bodyWidth, g), floatY(f, g, bodyY, top + dy)) } +
                    elements.map { it.at(it.xHu, it.yHu + dy) } +
                    floats.filter { !it.first.behind }.map { (f, top) -> f.el.at(floatX(f, g.bodyWidth, g), floatY(f, g, bodyY, top + dy)) }
        }

        /** A header or footer part, laid out in the body's width. */
        private fun layPart(path: String, g: Geometry): Part? {
            val root = zip.xml(path)?.documentElement ?: return null
            val blocks = ArrayList<Element>()
            flattenBlocks(root, blocks, 0)
            val out = ArrayList<HwpxElement>()
            val floats = ArrayList<Pair<Floating, Long>>()
            var y = 0L
            pageGeometry = g
            for (item in layBlocks(blocks, g.bodyWidth, 0, partRels = zip.rels(path))) {
                item.laid.lines.forEach { out += it.at(it.xHu, it.yHu + y) }
                item.floats.forEach { floats += it to y + item.laid.lead }
                y += item.laid.height
            }
            // (Footnotes are only shown for the body's text.)
            pendingNotes.clear()
            return Part(out, y, floats)
        }

        /** Where floating object [f] goes across, from the column's left edge. */
        private fun floatX(f: Floating, colWidth: Long, g: Geometry?): Long {
            val (from, size) = when {
                g == null -> 0L to colWidth
                f.hFrom == "page" -> -g.left to g.width
                f.hFrom == "leftMargin" -> -g.left to g.left
                f.hFrom == "rightMargin" -> (g.width - g.right - g.left) to g.right
                else -> 0L to colWidth
            }
            return from + when (f.hAlign) {
                "left", "inside" -> 0L
                "center" -> (size - f.el.widthHu) / 2
                "right", "outside" -> size - f.el.widthHu
                else -> f.hOffset
            }
        }

        /** Where floating object [f] goes down, from the body's top; [paraTop]: its paragraph's. */
        private fun floatY(f: Floating, g: Geometry, bodyY: Long, paraTop: Long): Long {
            val (from, size) = when (f.vFrom) {
                "page" -> -bodyY to g.height
                "margin" -> (g.top - bodyY) to (g.height - g.top - g.bottom)
                "topMargin" -> -bodyY to g.top
                "bottomMargin" -> (g.height - g.bottom - bodyY) to g.bottom
                else -> return paraTop + f.vOffset
            }
            return from + when (f.vAlign) {
                "top", "inside" -> 0L
                "center" -> (size - f.el.extentHu) / 2
                "bottom", "outside" -> size - f.el.extentHu
                else -> f.vOffset
            }
        }

        // ---- blocks ----

        private class Item(
            val laid: TextFlow.Laid, val breakBefore: Boolean = false, val breakAfter: Boolean = false, val keepNext: Boolean = false,
            /** Floating objects placed on the page once the paragraph's place is known. */
            val floats: List<Floating> = emptyList()
        ) {
            /** Footnotes referenced in it, laid out. */
            var notes: List<TextFlow.Laid> = emptyList()
        }

        private fun layBlocks(blocks: List<Element>, width: Long, depth: Int, partRels: Map<String, String> = docRels): List<Item> {
            val out = ArrayList<Item>()
            var prevStyle: String? = null
            var prevContextual = false
            var prevProps: ParaProps? = null
            for ((index, b) in blocks.withIndex()) {
                // Footnotes referenced in a block go with it (in the body: a cell's or a
                // text box's go with the table or paragraph holding it).
                val from = out.size
                fun takeNotes() {
                    if (pageGeometry == null || pendingNotes.isEmpty() || out.size == from) return
                    out[from].notes = pendingNotes.toList()
                    pendingNotes.clear()
                }
                if (b.localName == "tbl") {
                    table(b, width, depth + 1, partRels)?.let { out += Item(TextFlow.Laid(listOf(it), it.heightHu, it.heightHu, false)) }
                    takeNotes()
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
                takeNotes()
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
            val parts = ArrayList<Pair<MutableList<TextFlow.Atom>, MutableList<Floating>>>()
            var atoms = ArrayList<TextFlow.Atom>()
            var floats = ArrayList<Floating>()
            parts += atoms to floats
            val tabs = props.tabs.orEmpty().map { (pos, align) ->
                TextFlow.TabStop(pos, when (align) { "right", "end" -> ParaAlign.RIGHT; "center" -> ParaAlign.CENTER; else -> ParaAlign.LEFT })
            }
            // The list number (or bullet) and what follows it.
            level(props)?.let { lvl -> numberAtoms(props, lvl, markProps, tabs, breakNonLatin, atoms) }
            val field = FieldState()
            runsOf(p, 0) { r, kind ->
                when (kind) {
                    RunItem.PAGE_BREAK -> { atoms = ArrayList(); floats = ArrayList(); parts += atoms to floats }
                    else -> runAtoms(r, props, tabs, breakNonLatin, atoms, floats, field, partRels, depth, width)
                }
            }
            // A displayed equation is centered unless it says otherwise.
            val mathAlign = p.children().firstOrNull { it.namespaceURI == M && it.localName == "oMathPara" }?.let { mp ->
                when (mp.getElementsByTagNameNS(M, "jc").let { if (it.length > 0) (it.item(0) as Element).getAttributeNS(M, "val") else "" }) {
                    "left" -> ParaAlign.LEFT; "right" -> ParaAlign.RIGHT; else -> ParaAlign.CENTER
                }
            }
            val shape = shape(props, width).let { if (mathAlign == null) it else TextFlow.ParaShape(mathAlign, it.indent, it.left, it.right, it.prev, it.next, it.spacing, it.breakNonLatin) }
            return parts.mapIndexed { k, (partAtoms, partFloats) ->
                val first = k == 0
                val last = k == parts.size - 1
                val s = TextFlow.ParaShape(
                    shape.align, shape.indent, shape.left, shape.right,
                    if (first && !dropBefore) shape.prev else 0, if (last && !dropAfter) shape.next else 0,
                    shape.spacing, shape.breakNonLatin
                )
                val laid = text.lay(partAtoms, s, width, markStyle, singleLine(markStyle, false))
                val decorated = decorate(laid, props, width, joinsAbove || !first, joinsBelow || !last)
                val (withFloats, later) = setFloats(decorated, partFloats, width)
                Item(withFloats, breakBefore = first && props.pageBreakBefore == true,
                    breakAfter = !last, keepNext = last && props.keepNext == true, floats = later)
            }
        }

        /**
         * Puts a paragraph's floating objects that go with its text among its lines:
         * one the text wraps goes at the paragraph's top (or its offset from it) and
         * the text goes on below it; in a cell or a text box every one is placed from
         * the paragraph. The others are returned, to be placed on the page.
         */
        private fun setFloats(laid: TextFlow.Laid, floats: List<Floating>, width: Long): Pair<TextFlow.Laid, List<Floating>> {
            if (floats.isEmpty()) return laid to emptyList()
            val g = pageGeometry
            val inFlow = floats.filter { g == null || it.followsText && it.wraps }
            if (inFlow.isEmpty()) return laid to floats
            val top = laid.lead
            val placed = inFlow.map { f -> f.el.at(floatX(f, width, g), top + if (f.followsText) f.vOffset else 0L) }
            val shift = inFlow.filter { it.wraps }.maxOfOrNull { f -> (if (f.followsText) f.vOffset else 0L) + f.el.extentHu + f.distBottom }?.coerceAtLeast(0) ?: 0L
            val behind = inFlow.zip(placed).filter { it.first.behind }.map { it.second }
            val front = inFlow.zip(placed).filter { !it.first.behind }.map { it.second }
            val lines = behind + front + laid.lines.map { if (shift > 0) it.at(it.xHu, it.yHu + shift) else it }
            val bottom = maxOf(laid.bottom + shift, placed.maxOf { it.yHu + it.extentHu })
            return TextFlow.Laid(lines, laid.height + shift, bottom, false, laid.lead) to floats.filter { it !in inFlow }
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
                "atLeast" -> TextFlow.LineSpacing.AtLeastSingle((line ?: 0).coerceIn(0, 200_000))
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
                // Word hangs the first line out to the left of the indent; TextFlow
                // (like Hangul) starts the first line at the left and moves the others in.
                indent = props.firstLine ?: props.hanging?.let { -it } ?: 0,
                left = ((props.left ?: 0) - (props.hanging?.takeIf { props.firstLine == null } ?: 0)).coerceIn(-width, width),
                right = (props.right ?: 0).coerceIn(-width, width),
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
                    c.namespaceURI == NS_MC && c.localName == "AlternateContent" -> alternate(c)?.let { runsOf(it, depth + 1, visit) }
                    c.namespaceURI == W && c.localName == "fldSimple" -> {
                        val instr = c.wAttr("instr")?.trim()?.uppercase().orEmpty()
                        if (instr.startsWith("PAGE") || instr.startsWith("NUMPAGES")) visit(c, RunItem.RUN) else runsOf(c, depth + 1, visit)
                    }
                    c.namespaceURI == M && (c.localName == "oMath" || c.localName == "oMathPara") -> visit(c, RunItem.RUN)
                }
            }
        }

        /** An equation (or, for [m] an <m:oMathPara>, each of its equations on a line
         *  of its own), typeset from its structure at its text's size and color. */
        private fun equation(m: Element, props: ParaProps, breakNonLatin: Boolean, out: MutableList<TextFlow.Atom>) {
            val maths = if (m.localName == "oMathPara") m.children().filter { it.namespaceURI == M && it.localName == "oMath" } else listOf(m)
            for ((i, math) in maths.withIndex()) {
                val firstRun = math.getElementsByTagNameNS(W, "rPr").let { if (it.length > 0) it.item(0) as Element else null }
                // Without a size of its own, an equation takes its paragraph's text size.
                var para: org.w3c.dom.Node? = m.parentNode
                while (para != null && !(para is Element && para.namespaceURI == W && para.localName == "p")) para = para.parentNode
                val mark = (para as? Element)?.child(W, "pPr")?.child(W, "rPr")
                val style = charStyle(runPropsOf(props.style, runProps(firstRun).over(runProps(mark))))
                val tree = Omml.parse(math)
                val sizeHu = (style.sizePt * 100).toLong()
                val box = try { EquationLayout(style.colorArgb).layout(tree, sizeHu.toFloat()) } catch (e: Exception) { null }
                if (i > 0) out += text.lineBreak(style)
                if (box == null || box.width <= 0f || box.height <= 0f) continue
                val h = box.height.toLong().coerceIn(1, 2_000_000)
                val eq = HwpxElement.Equation(0, 0, box.width.toLong().coerceIn(1, 2_000_000), h, script = "", sizeHu = sizeHu,
                    colorArgb = style.colorArgb, baselinePercent = (box.ascent * 100 / box.height).toInt().coerceIn(0, 100), tree = tree)
                out += text.inline(eq, style)
            }
            if (maths.isEmpty()) text.addText(m.textContent.take(10_000), charStyle(runPropsOf(props.style, RunProps())), breakNonLatin, out)
        }

        private fun runAtoms(
            r: Element, props: ParaProps, tabs: List<TextFlow.TabStop>, breakNonLatin: Boolean,
            out: MutableList<TextFlow.Atom>, floats: MutableList<Floating>, field: FieldState, partRels: Map<String, String>, depth: Int, width: Long
        ) {
            if (r.localName == "fldSimple") {
                val instr = r.wAttr("instr")?.trim()?.uppercase().orEmpty()
                val rp = runPropsOf(props.style, runProps(r.child(W, "r")?.child(W, "rPr")))
                val style = charStyle(rp)
                text.addText(if (instr.startsWith("NUMPAGES")) "$PAGE_COUNT_MARK" else "$PAGE_NUMBER_MARK", style, breakNonLatin, out, singleLine(style, false))
                return
            }
            if (r.namespaceURI == M) { equation(r, props, breakNonLatin, out); return }
            val rp = runPropsOf(props.style, runProps(r.child(W, "rPr")))
            if (rp.vanish == true) return
            val style = charStyle(rp)
            val face = rp.ascii ?: rp.asciiTheme?.let { themeFonts[it] }
            fun place(obj: Obj?) {
                obj ?: return
                if (obj.floating != null) floats += obj.floating else out += text.inline(obj.el, style)
            }
            fun item(c: Element, nest: Int) {
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
                        val code = c.wAttr("char")?.toIntOrNull(16) ?: return
                        text.addText(fixSymbolText(code.toChar().toString(), c.wAttr("font")), style, breakNonLatin, out)
                    }
                    "drawing" -> if (!skip) place(drawing(c, partRels, depth))
                    "footnoteReference", "endnoteReference" -> if (!skip && noteMark == null) {
                        val id = c.wAttr("id") ?: return
                        val foot = c.localName == "footnoteReference"
                        val shown = if (foot) numberText(++footnoteCount, footnoteFormat) else numberText(endnoteOrder.size + 1, endnoteFormat)
                        // A custom mark is the run's own text, after this.
                        if (c.wAttr("customMarkFollows")?.isTrue() != true) text.addText(shown, style, breakNonLatin, out)
                        if (foot) footnotes[id]?.let { if (pendingNotes.size < 1000) pendingNotes += layNote(it, shown, footnoteRels) }
                        else if (endnotes.containsKey(id)) endnoteOrder += id to shown
                    }
                    "footnoteRef", "endnoteRef" -> if (!skip) noteMark?.let { text.addText(it, style, breakNonLatin, out) }
                    "pict", "object" -> if (!skip) {
                        // A VML horizontal rule (pandoc's "---"): a gray line across the column.
                        if (c.children().any { it.localName == "rect" && it.getAttributeNS(VML_OFFICE, "hr") in setOf("t", "true") }) {
                            val w = (width - (props.left ?: 0) - (props.right ?: 0)).coerceAtLeast(100)
                            out += text.inline(HwpxElement.Drawing(0, 0, w, 150, DrawingKind.RECTANGLE, lineArgb = null, fillArgb = 0xFFA0A0A0.toInt()), style)
                        } else c.children().firstOrNull { it.namespaceURI == VML && it.localName in VML_SHAPES }?.let { place(vml(it, partRels, depth)) }
                    }
                    "AlternateContent" -> if (c.namespaceURI == NS_MC && nest < 4) alternate(c)?.children()?.forEach { item(it, nest + 1) }
                }
            }
            for (c in r.children()) item(c, 0)
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

        // ---- drawings ----

        /** A drawing: set in the line, or floating ([floating] says where). */
        private class Obj(val el: HwpxElement, val floating: Floating?)

        /** The branch of an <mc:AlternateContent> read here: the first choice
         *  needing only what is read here, else the fallback. */
        private fun alternate(ac: Element): Element? =
            ac.children().firstOrNull { c -> c.localName == "Choice" && c.getAttribute("Requires").split(' ').filter { it.isNotBlank() }.all { it in READ_CHOICES } }
                ?: ac.children().firstOrNull { it.localName == "Fallback" }

        private fun drawing(d: Element, partRels: Map<String, String>, depth: Int): Obj? {
            if (depth > Limits.MAX_NESTING) return null
            val holder = d.child(WP, "inline") ?: d.child(WP, "anchor") ?: return null
            if (holder.child(WP, "docPr")?.getAttribute("hidden").let { it == "1" || it == "true" }) return null
            val ext = holder.child(WP, "extent") ?: return null
            val w = (ext.getAttribute("cx").toLongOrNull() ?: 0) / 127
            val h = (ext.getAttribute("cy").toLongOrNull() ?: 0) / 127
            if (w <= 0 || h <= 0 || w > 2_000_000 || h > 2_000_000) return null
            val data = holder.child(NS_A, "graphic")?.child(NS_A, "graphicData") ?: return null
            val el = data.children().firstNotNullOfOrNull { graphic(it, w, h, partRels, depth) } ?: return null
            return Obj(el, if (holder.localName == "anchor") floating(holder, el) else null)
        }

        /** A picture, shape (or text box) or group, [w] x [h]. */
        private fun graphic(g: Element, w: Long, h: Long, partRels: Map<String, String>, depth: Int): HwpxElement? = when (g.localName) {
            "pic" -> picture(g, w, h, partRels)
            "wsp" -> shape(g, w, h, partRels, depth)
            "wgp", "grpSp" -> group(g, w, h, partRels, depth)
            else -> null
        }

        private fun picture(pic: Element, w: Long, h: Long, partRels: Map<String, String>): HwpxElement? {
            val fill = pic.children().firstOrNull { it.localName == "blipFill" } ?: return null
            val blip = fill.child(NS_A, "blip") ?: return null
            return picture(partRels[blip.getAttributeNS(NS_R, "embed")] ?: return null, w, h, fill.child(NS_A, "srcRect")?.let { r ->
                fun f(a: String) = (r.getAttribute(a).toLongOrNull() ?: 0L) / 100_000.0
                ImageCrop.of(f("l"), f("t"), f("r"), f("b"), 1.0, 1.0)
            })
        }

        /** The picture at [path] in the package, read when drawn. */
        private fun picture(path: String, w: Long, h: Long, crop: ImageCrop? = null): HwpxElement? {
            val entry = zip.getEntry(path) ?: return null
            return HwpxElement.ImageEl(0, 0, w, h, zipEntry = null, crop = crop, bytes = {
                pictures.get(path) {
                    try { zip.readCapped(entry, SafeBitmaps.MAX_IMAGE_BYTES) } catch (e: Exception) { null }
                }
            })
        }

        /** A shape: its outline, fill and any text box in it. */
        private fun shape(sp: Element, w: Long, h: Long, partRels: Map<String, String>, depth: Int): HwpxElement? {
            val spPr = sp.child(WPS, "spPr")
            val style = sp.child(WPS, "style")
            val prst = spPr?.child(NS_A, "prstGeom")?.attr("prst") ?: if (spPr?.child(NS_A, "custGeom") != null) "rect" else "rect"
            val xfrm = spPr?.child(NS_A, "xfrm")
            // A fill set on the shape, else its style's.
            val fill = when {
                spPr?.child(NS_A, "noFill") != null -> null
                spPr?.child(NS_A, "solidFill") != null -> theme.colorIn(spPr.child(NS_A, "solidFill"))
                spPr?.child(NS_A, "gradFill") != null -> theme.colorIn(spPr.child(NS_A, "gradFill")?.first(NS_A, "gs"))
                else -> style?.child(NS_A, "fillRef")?.takeIf { (it.attr("idx")?.toIntOrNull() ?: 0) > 0 }?.let { theme.colorIn(it) }
            }?.takeIf { it ushr 24 != 0 }
            val ln = spPr?.child(NS_A, "ln")
            val lnRef = style?.child(NS_A, "lnRef")?.takeIf { (it.attr("idx")?.toIntOrNull() ?: 0) > 0 }
            val lineColor = when {
                ln?.child(NS_A, "noFill") != null -> null
                ln?.child(NS_A, "solidFill") != null -> theme.colorIn(ln.child(NS_A, "solidFill"))
                else -> lnRef?.let { theme.colorIn(it) }
            }?.takeIf { it ushr 24 != 0 }
            val lineWidth = ((ln?.attr("w")?.toLongOrNull() ?: 9525) / 127).coerceIn(0, 10_000)
            val flipped = (xfrm?.attr("flipH") == "1") != (xfrm?.attr("flipV") == "1")
            val textColor = style?.child(NS_A, "fontRef")?.let { theme.colorIn(it) }
            val box = sp.child(WPS, "txbx")?.child(W, "txbxContent")
            val bodyPr = sp.child(WPS, "bodyPr")
            fun inset(name: String, default: Long) = (bodyPr?.attr(name)?.toLongOrNull()?.div(127) ?: default).coerceIn(0, 100_000)
            val l = inset("lIns", 720); val r = inset("rIns", 720); val t = inset("tIns", 360); val b = inset("bIns", 360)
            val content = box?.let { textBox(it, (w - l - r).coerceAtLeast(500), l, t, textColor, partRels, depth) }.orEmpty()
            val contentBottom = content.maxOfOrNull { it.yHu + it.extentHu } ?: 0L
            val height = if (bodyPr?.child(NS_A, "spAutoFit") != null) maxOf(h, contentBottom + b) else h
            val vAlign = when (bodyPr?.attr("anchor")) { "ctr" -> 1; "b" -> 2; else -> 0 }
            return when (prst) {
                "line", "straightConnector1", "bentConnector2", "bentConnector3", "curvedConnector3" ->
                    HwpxElement.Drawing(0, 0, w, h, DrawingKind.LINE, points = if (flipped) listOf(0L to h, w to 0L) else listOf(0L to 0L, w to h),
                        lineArgb = lineColor ?: return null, lineWidthHu = lineWidth)
                "ellipse" -> HwpxElement.Drawing(0, 0, w, height, DrawingKind.ELLIPSE, lineArgb = lineColor, lineWidthHu = lineWidth, fillArgb = fill, content = content, contentVAlign = vAlign)
                else -> HwpxElement.Drawing(0, 0, w, height, DrawingKind.RECTANGLE, cornerPercent = if (prst == "roundRect") 17 else 0,
                    lineArgb = lineColor, lineWidthHu = lineWidth, fillArgb = fill, content = content, contentVAlign = vAlign)
            }
        }

        /** A text box's paragraphs and tables laid out [width] wide from ([x], [y]). */
        private fun textBox(box: Element, width: Long, x: Long, y: Long, textColor: Int?, partRels: Map<String, String>, depth: Int): List<HwpxElement> {
            val blocks = ArrayList<Element>()
            flattenBlocks(box, blocks, 0)
            val outerPage = pageGeometry; val outerColor = boxTextColor; val outerStyle = tableStyle
            pageGeometry = null; boxTextColor = textColor; tableStyle = emptyList()
            val items = try { layBlocks(blocks, width, depth + 1, partRels) } finally { pageGeometry = outerPage; boxTextColor = outerColor; tableStyle = outerStyle }
            return stack(items, x, y).first
        }

        /** Laid blocks one below the other from ([x], [y]), and the bottom of the last. */
        private fun stack(items: List<Item>, x: Long, y0: Long): Pair<List<HwpxElement>, Long> {
            val out = ArrayList<HwpxElement>()
            var y = y0
            var bottom = y0
            for (item in items) {
                item.laid.lines.forEach { out += it.at(it.xHu + x, it.yHu + y) }
                bottom = maxOf(bottom, y + item.laid.bottom)
                y += item.laid.height
                if (out.size > TextFlow.MAX_LINES) break
            }
            return out to bottom
        }

        /** A group: its members placed in its box, their positions and sizes scaled
         *  from the group's own coordinates. */
        private fun group(g: Element, w: Long, h: Long, partRels: Map<String, String>, depth: Int): HwpxElement? {
            if (depth > Limits.MAX_NESTING) return null
            val xfrm = g.children().firstOrNull { it.localName == "grpSpPr" }?.child(NS_A, "xfrm")
            val chOff = xfrm?.child(NS_A, "chOff"); val chExt = xfrm?.child(NS_A, "chExt")
            val ox = chOff?.attr("x")?.toLongOrNull() ?: 0; val oy = chOff?.attr("y")?.toLongOrNull() ?: 0
            val sx = w * 127.0 / (chExt?.attr("cx")?.toLongOrNull()?.takeIf { it > 0 } ?: (w * 127))
            val sy = h * 127.0 / (chExt?.attr("cy")?.toLongOrNull()?.takeIf { it > 0 } ?: (h * 127))
            val members = ArrayList<HwpxElement>()
            for (c in g.children()) {
                if (c.localName !in setOf("wsp", "pic", "grpSp")) continue
                val cx = c.children().firstOrNull { it.localName == "spPr" || it.localName == "grpSpPr" }?.child(NS_A, "xfrm") ?: continue
                val off = cx.child(NS_A, "off"); val ext = cx.child(NS_A, "ext") ?: continue
                val mw = ((ext.attr("cx")?.toLongOrNull() ?: 0) * sx / 127).toLong()
                val mh = ((ext.attr("cy")?.toLongOrNull() ?: 0) * sy / 127).toLong()
                if (mw < 0 || mh < 0 || mw > 2_000_000 || mh > 2_000_000) continue
                val el = graphic(c, mw.coerceAtLeast(1), mh.coerceAtLeast(1), partRels, depth + 1) ?: continue
                val mx = (((off?.attr("x")?.toLongOrNull() ?: 0) - ox) * sx / 127).toLong()
                val my = (((off?.attr("y")?.toLongOrNull() ?: 0) - oy) * sy / 127).toLong()
                members += el.at(mx, my)
                if (members.size > 10_000) break
            }
            if (members.isEmpty()) return null
            return HwpxElement.Drawing(0, 0, w, h, DrawingKind.RECTANGLE, lineArgb = null, content = members)
        }

        /** Where an anchored drawing goes, and whether text wraps it. */
        private fun floating(a: Element, el: HwpxElement): Floating {
            fun emu(v: String?) = (v?.trim()?.toLongOrNull() ?: 0L).coerceIn(-50_000_000_000, 50_000_000_000) / 127
            val ph = a.child(WP, "positionH"); val pv = a.child(WP, "positionV")
            val wrap = a.children().firstOrNull { it.namespaceURI == WP && it.localName.startsWith("wrap") }?.localName
            val behind = a.getAttribute("behindDoc") == "1" || a.getAttribute("behindDoc") == "true"
            if (a.getAttribute("simplePos") == "1") {
                val p = a.child(WP, "simplePos")
                return Floating(el, "page", null, emu(p?.getAttribute("x")), "page", null, emu(p?.getAttribute("y")), behind, false, 0)
            }
            return Floating(
                el,
                ph?.getAttribute("relativeFrom")?.ifBlank { null } ?: "column", ph?.child(WP, "align")?.textContent?.trim(), emu(ph?.child(WP, "posOffset")?.textContent),
                pv?.getAttribute("relativeFrom")?.ifBlank { null } ?: "paragraph", pv?.child(WP, "align")?.textContent?.trim(), emu(pv?.child(WP, "posOffset")?.textContent),
                behind, wraps = !behind && wrap != null && wrap != "wrapNone", distBottom = emu(a.getAttribute("distB")).coerceIn(0, 100_000)
            )
        }

        /** A VML shape (older Word's): a picture, a text box or a plain shape, floating
         *  when positioned absolutely. */
        private fun vml(shape: Element, partRels: Map<String, String>, depth: Int): Obj? {
            if (depth > Limits.MAX_NESTING) return null
            val css = shape.getAttribute("style").split(';').mapNotNull { d ->
                val k = d.indexOf(':')
                if (k <= 0) null else d.substring(0, k).trim().lowercase() to d.substring(k + 1).trim()
            }.toMap()
            if (css["visibility"] == "hidden" || css["display"] == "none") return null
            val w = cssLength(css["width"]) ?: return null
            val h = cssLength(css["height"]) ?: return null
            if (w <= 0 || h <= 0 || w > 2_000_000 || h > 2_000_000) return null
            val image = shape.child(VML, "imagedata")?.getAttributeNS(NS_R, "id")?.let { partRels[it] }
            val box = shape.child(VML, "textbox")?.let { it.child(W, "txbxContent") }
            val el: HwpxElement = when {
                image != null -> picture(image, w, h) ?: return null
                else -> {
                    val filled = shape.getAttribute("filled").let { it != "f" && it != "false" }
                    val stroked = shape.getAttribute("stroked").let { it != "f" && it != "false" }
                    val fill = if (filled) vmlColor(shape.getAttribute("fillcolor")) ?: 0xFFFFFFFF.toInt() else null
                    val line = if (stroked) vmlColor(shape.getAttribute("strokecolor")) ?: 0xFF000000.toInt() else null
                    val lineWidth = cssLength(shape.getAttribute("strokeweight").ifBlank { "0.75pt" }) ?: 75
                    val content = box?.let { textBox(it, (w - 1440).coerceAtLeast(500), 720, 360, null, partRels, depth) }.orEmpty()
                    if (shape.localName == "line") return null
                    HwpxElement.Drawing(0, 0, w, h, if (shape.localName == "oval") DrawingKind.ELLIPSE else DrawingKind.RECTANGLE,
                        cornerPercent = if (shape.localName == "roundrect") 17 else 0, lineArgb = line, lineWidthHu = lineWidth, fillArgb = fill, content = content)
                }
            }
            if (css["position"] != "absolute") return Obj(el, null)
            val wrap = shape.children().firstOrNull { it.localName == "wrap" }?.getAttribute("type")
            val behind = (css["z-index"]?.toIntOrNull() ?: 0) < 0
            fun from(v: String?, default: String) = when (v) {
                null, "", "text", "char" -> default
                "page" -> "page"
                "margin" -> "margin"
                "line" -> "line"
                else -> default
            }
            return Obj(el, Floating(
                el,
                from(css["mso-position-horizontal-relative"], "column"), css["mso-position-horizontal"]?.takeIf { it != "absolute" }, cssLength(css["margin-left"] ?: css["left"]) ?: 0,
                from(css["mso-position-vertical-relative"], "paragraph"), css["mso-position-vertical"]?.takeIf { it != "absolute" }, cssLength(css["margin-top"] ?: css["top"]) ?: 0,
                behind, wraps = !behind && wrap != null && wrap != "none", distBottom = 0
            ))
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
                    val outerStyle = tableStyle; val outerPage = pageGeometry
                    tableStyle = styleChain; pageGeometry = null
                    val laidBlocks = try { layBlocks(blocks, inner, depth, partRels) } finally { tableStyle = outerStyle; pageGeometry = outerPage }
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
            // Rows of exact height cut off what doesn't fit.
            for (r in grid.indices) for (c in 0 until colCount) {
                val cell = grid[r][c] ?: continue
                val last = (r + cell.rowSpan - 1).coerceAtMost(grid.size - 1)
                if ((r..last).any { exact[it] } && (content[r to c] ?: 0) > (r..last).sumOf { heights[it] }) grid[r][c] = cell.copy(clip = true)
            }
            val ind = tblPr?.child(W, "tblInd")?.twips("w") ?: 0
            val total = cols.sum()
            val x = when (tblPr?.child(W, "jc")?.wAttr("val")) {
                "center" -> (width - total) / 2
                "right", "end" -> width - total
                else -> ind
            }
            // Rows marked as a header, from the first on, repeat on each page.
            val headerRows = rowsXml.takeWhile { tr -> tr.child(W, "trPr")?.child(W, "tblHeader")?.let { it.wAttr("val").isTrue() } == true }.size
            return HwpxElement.Table(x, 0, total, heights.sum(), cols, heights, grid, headerRows, rowsBreak = true)
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
                style = rPr.child(W, "rStyle")?.wAttr("val"),
                // A highlighter color wins over shading.
                background = rPr.child(W, "highlight")?.wAttr("val")?.let { HIGHLIGHT[it] } ?: rPr.child(W, "shd")?.let { shade(it) }
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

    /** Word's highlighter colors. */
    private val HIGHLIGHT = mapOf(
        "yellow" to 0xFFFFFF00, "green" to 0xFF00FF00, "cyan" to 0xFF00FFFF, "magenta" to 0xFFFF00FF,
        "blue" to 0xFF0000FF, "red" to 0xFFFF0000, "darkBlue" to 0xFF000080, "darkCyan" to 0xFF008080,
        "darkGreen" to 0xFF008000, "darkMagenta" to 0xFF800080, "darkRed" to 0xFF800000, "darkYellow" to 0xFF808000,
        "darkGray" to 0xFF808080, "lightGray" to 0xFFC0C0C0, "black" to 0xFF000000
    ).mapValues { it.value.toInt() }

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

    /** The VML shapes read: a text box or picture is in one of these. */
    private val VML_SHAPES = setOf("shape", "rect", "roundrect", "oval", "line", "image")

    /** A CSS length (VML's style attribute) in HWPUNIT. */
    private fun cssLength(v: String?): Long? {
        val m = Regex("""^(-?[0-9.]+)\s*(pt|in|cm|mm|px|pc|emu)?$""").find(v?.trim()?.lowercase() ?: return null) ?: return null
        val n = m.groupValues[1].toDoubleOrNull()?.takeIf { it.isFinite() && kotlin.math.abs(it) < 1e7 } ?: return null
        val hu = when (m.groupValues[2]) {
            "in" -> n * 7200; "cm" -> n * 7200 / 2.54; "mm" -> n * 720 / 2.54; "px" -> n * 75; "pc" -> n * 1200; "emu" -> n / 127
            "pt" -> n * 100
            else -> n / 127 // bare numbers in a style are EMU
        }
        return hu.toLong()
    }

    /** A VML color: #rrggbb, #rgb or a name (with any " [index]" after it). */
    private fun vmlColor(v: String?): Int? {
        val c = v?.trim()?.substringBefore(' ')?.lowercase()?.ifEmpty { null } ?: return null
        if (c.startsWith("#")) {
            val hex = c.drop(1).let { if (it.length == 3) it.map { ch -> "$ch$ch" }.joinToString("") else it }
            return hex(hex.uppercase())
        }
        return when (c) {
            "black" -> 0xFF000000.toInt(); "white" -> 0xFFFFFFFF.toInt(); "red" -> 0xFFFF0000.toInt(); "green" -> 0xFF008000.toInt()
            "blue" -> 0xFF0000FF.toInt(); "yellow" -> 0xFFFFFF00.toInt(); "gray", "grey" -> 0xFF808080.toInt(); "silver" -> 0xFFC0C0C0.toInt()
            "navy" -> 0xFF000080.toInt(); "maroon" -> 0xFF800000.toInt(); "purple" -> 0xFF800080.toInt(); "teal" -> 0xFF008080.toInt()
            "olive" -> 0xFF808000.toInt(); "lime" -> 0xFF00FF00.toInt(); "aqua" -> 0xFF00FFFF.toInt(); "fuchsia" -> 0xFFFF00FF.toInt()
            else -> null
        }
    }

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
        is HwpxElement.Drawing -> if (content.isEmpty()) this else copy(content = content.map { it.replacingText(mark, value) })
        else -> this
    }
}
