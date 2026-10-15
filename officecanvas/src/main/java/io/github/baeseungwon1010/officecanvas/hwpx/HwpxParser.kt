package io.github.baeseungwon1010.officecanvas.hwpx

import io.github.baeseungwon1010.officecanvas.util.Limits
import io.github.baeseungwon1010.officecanvas.util.SafeXml
import org.w3c.dom.Document
import org.w3c.dom.Element as DomElement
import org.w3c.dom.Node
import java.io.File
import java.util.zip.ZipFile

/**
 * Reads a .hwpx into an [HwpxDoc] already split into pages.
 *
 * See [HwpxDoc]'s doc comment for why this can lean on Hangul's own saved layout
 * (<hp:lineseg>) instead of computing line breaks and page breaks itself.
 */
internal object HwpxParser {

    /** Set true while debugging; a per-section parse failure is otherwise swallowed
     *  on purpose so one bad section doesn't fail the whole document. */
    var DEBUG_LOG_ERRORS = false

    private const val HP = "http://www.hancom.co.kr/hwpml/2011/paragraph"
    private const val HH = "http://www.hancom.co.kr/hwpml/2011/head"
    private const val HC = "http://www.hancom.co.kr/hwpml/2011/core"
    private const val OPF = "http://www.idpf.org/2007/opf/"

    class ParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private class Header(
        val charStyles: Map<String, CharStyle>,
        val paraAligns: Map<String, ParaAlign> = emptyMap(),
        val borderFills: Map<String, BorderFill> = emptyMap()
    ) {
        fun styleOf(charPrIdRef: String?): CharStyle = charStyles[charPrIdRef] ?: CharStyle()
    }

    /** One page's worth of geometry, read from the first paragraph's <hp:secPr>
     *  (later sections reuse the last one seen if they omit their own). */
    /** [bottomHu]: bottom margin plus footer area, below the body. */
    private data class PageGeometry(
        val widthHu: Long, val heightHu: Long, val bodyXHu: Long = 0, val bodyYHu: Long = 0, val bottomHu: Long = 0,
        val rightHu: Long = 0, val bottomMarginHu: Long = 0, val headerHu: Long = 0, val footerHu: Long = 0
    )

    fun parse(file: File): HwpxDoc = ZipFile(file).use { zip ->
        state.set(State())
        val contentDoc = zip.xml("Contents/content.hpf")
            ?: throw ParseException("not a .hwpx: Contents/content.hpf is missing")
        val manifest = contentDoc.first(OPF, "manifest")?.all(OPF, "item")
            ?.associate { it.getAttribute("id") to it.getAttribute("href") } ?: emptyMap()
        val spineIds = contentDoc.first(OPF, "spine")?.all(OPF, "itemref")
            ?.filter { it.getAttribute("linear") != "no" }
            ?.map { it.getAttribute("idref") } ?: emptyList()

        val headerPath = manifest["header"] ?: manifest.entries.firstOrNull { it.key.contains("header") }?.value
        val header = headerPath?.let { zip.xml(it) }?.let { parseHeader(it) } ?: Header(emptyMap())

        val sectionPaths = spineIds.mapNotNull { manifest[it] }.ifEmpty {
            // Fall back to filename order if the spine is unreadable — same
            // fallback shape as pptx falling back to zip order, kept as a last
            // resort rather than the primary path because it can be wrong.
            zip.entries().asSequence().map { it.name }
                .filter { it.matches(Regex("Contents/section\\d+\\.xml")) }.sorted().toList()
        }

        val pages = mutableListOf<HwpxPage>()
        var lastGeometry = PageGeometry(59528, 84186) // A4, used only if a section's secPr is unreadable
        for (path in sectionPaths) {
            if (pages.size >= Limits.MAX_PAGES) break
            try {
                val doc = zip.xml(path) ?: continue
                parseSection(doc, header, lastGeometry, pages.size).forEach { (geometry, page) ->
                    lastGeometry = geometry
                    if (pages.size < Limits.MAX_PAGES) pages += page
                }
            } catch (e: Exception) {
                // One unreadable section costs its own pages, not the whole document.
                if (DEBUG_LOG_ERRORS) e.printStackTrace()
            }
        }
        state.remove()
        HwpxDoc(pages)
    }

    /** Splits one section into pages by watching the top-level flow's vertpos: a
     *  reset to a smaller value than the previous line means Hangul started a new
     *  page when it saved this file (verified against a real 3-page document —
     *  see [HwpxDoc]). */
    private fun parseSection(doc: Document, header: Header, fallbackGeometry: PageGeometry, firstPageIndex: Int): List<Pair<PageGeometry, HwpxPage>> {
        val root = doc.documentElement
        val paragraphs = root.all(HP, "p")
        val secPr = paragraphs.firstOrNull()?.run(HP, "run")?.child(HP, "secPr")
        val geometry = secPr?.let {
            val pagePr = it.child(HP, "pagePr")
            val w = pagePr?.longOrNull("width"); val h = pagePr?.longOrNull("height")
            // Lineseg positions are relative to the body area, which starts after the
            // left margin (plus gutter) and below the top margin plus the header area.
            val margin = pagePr?.child(HP, "margin")
            val bodyX = (margin?.longOrNull("left") ?: 0L) + (margin?.longOrNull("gutter") ?: 0L)
            val bodyY = (margin?.longOrNull("top") ?: 0L) + (margin?.longOrNull("header") ?: 0L)
            val bottom = (margin?.longOrNull("bottom") ?: 0L) + (margin?.longOrNull("footer") ?: 0L)
            if (w != null && h != null && w > 0 && h > 0) {
                PageGeometry(w, h, bodyX, bodyY, bottom, margin?.longOrNull("right") ?: 0L, margin?.longOrNull("bottom") ?: 0L,
                    margin?.longOrNull("header") ?: 0L, margin?.longOrNull("footer") ?: 0L)
            } else null
        } ?: fallbackGeometry

        state.get().apply {
            bodyXHu = geometry.bodyXHu; bodyYHu = geometry.bodyYHu
            bodyWidthHu = (geometry.widthHu - geometry.bodyXHu - geometry.rightHu).coerceAtLeast(1)
        }

        val pg = PageDecor.Geometry(geometry.widthHu, geometry.heightHu, geometry.bodyXHu, geometry.bodyYHu,
            geometry.rightHu, geometry.bottomMarginHu, geometry.headerHu, geometry.footerHu)

        val flow = PageFlow(geometry.heightHu - geometry.bodyYHu - geometry.bottomHu)
        val decor = DecorTimeline()
        for (p in paragraphs) {
            if (flow.full) break
            // pageBreak="1": the paragraph starts a page even when its first line
            // sits as high as the previous page's last paragraph did.
            val forcedBreak = p.getAttribute("pageBreak") == "1"
            for ((k, el) in parseParagraphFlow(p, header, 0).withIndex()) {
                flow.add(el, startsPage = k == 0 && forcedBreak, startsLine = el in state.get().lineStarts,
                    floating = el in state.get().placedFloating)
            }
            // Header, footer and page number controls apply from the page their
            // paragraph starts on (flow.page is approximate for a paragraph that
            // runs onto the next page, where these controls are rare).
            for (ctrl in p.all(HP, "run").flatMap { it.all(HP, "ctrl") }.flatMap { it.children() }) {
                decorControl(ctrl, header)?.let { decor.change(flow.page, it) }
            }
        }
        var number = firstPageIndex
        return flow.finish().map { (index, elements) ->
            number++
            geometry to HwpxPage(geometry.widthHu, geometry.heightHu,
                decor.at(index).elements(number, pg) + elements.map { it.withPageNumber(number) },
                geometry.bodyXHu, geometry.bodyYHu)
        }
    }

    /** A header, footer or page number control as a change to the page decoration. */
    private fun decorControl(ctrl: DomElement, header: Header): ((PageDecor) -> PageDecor)? = when (ctrl.localName) {
        "header", "footer" -> ctrl.child(HP, "subList")?.let { list ->
            val area = DecorArea(
                list.all(HP, "p").withIndex().flatMap { (k, p) -> parseParagraphFlow(p, header, 1, firstInList = k == 0) },
                when (list.getAttribute("vertAlign")) { "CENTER" -> 1; "BOTTOM" -> 2; else -> 0 }
            )
            val pages = when (ctrl.getAttribute("applyPageType")) { "EVEN" -> 1; "ODD" -> 2; else -> 0 }
            if (ctrl.localName == "header") { d: PageDecor -> d.withHeader(area, pages) } else { d: PageDecor -> d.withFooter(area, pages) }
        }
        "pageNum" -> {
            val pos = when (ctrl.getAttribute("pos")) {
                "TOP_LEFT" -> 1; "TOP_CENTER" -> 2; "TOP_RIGHT" -> 3
                "BOTTOM_LEFT" -> 4; "BOTTOM_CENTER" -> 5; "BOTTOM_RIGHT" -> 6
                else -> null
            }
            val side = ctrl.getAttribute("sideChar").trim()
            ({ d: PageDecor -> d.copy(pageNumberPosition = pos, pageNumberSide = side) })
        }
        else -> null
    }

    /** Marks an inline table/picture's spot in a paragraph's character stream —
     *  never a real character in the saved document, just a value outside the
     *  Unicode range HWPX text uses so it can share the same offset space as the
     *  actual run text without colliding. */
    private const val INLINE_OBJECT_MARKER = '￼'

    /**
     * A paragraph's runs are concatenated into one character stream (so a lineseg's
     * textpos range can be sliced out of it). A run that itself carries a table or
     * picture contributes one marker character to that stream instead of its text —
     * a table has its own internal paragraphs and coordinate space, not a place in
     * this paragraph's own text flow, but it still needs *a* position in the stream
     * so the lineseg that covers it (often the object's entire line) tells us where
     * Hangul actually placed it, rather than leaving it at a default of (0, 0) that
     * would misdraw its position and corrupt page-break detection (a table's y
     * would then look like it went backwards on every page that has one).
     */
    /** Per-document parsing state (the parser object is shared). */
    private class State {
        /** Elements that begin a line (see PageFlow.add); identity, not equality. */
        val lineStarts: MutableSet<HwpxElement> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
        /** Objects placed by offsets rather than in a line, before and after placing. */
        val floatingOf = java.util.IdentityHashMap<HwpxElement, Floating>()
        val placedFloating: MutableSet<HwpxElement> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
        var bodyXHu = 0L
        var bodyYHu = 0L
        var bodyWidthHu = 42520L
    }

    private val state = ThreadLocal.withInitial { State() }

    /** <hp:pos>: references (PAPER, PAGE, PARA/COLUMN) and offsets; [takesLines]:
     *  text wraps above and below it; [keepsBelowAnchor] as in HwpParser (such an
     *  object that also moves with the text). */
    private class Floating(
        val vertRel: String, val horzRel: String, val vertOffset: Long, val horzOffset: Long,
        val takesLines: Boolean, val keepsBelowAnchor: Boolean
    )

    /** Records how [obj] is placed when its <hp:pos> doesn't treat it as a character. */
    private fun <T : HwpxElement> T.positionedBy(owner: DomElement): T {
        val pos = owner.child(HP, "pos") ?: return this
        if (pos.getAttribute("treatAsChar") == "1") return this
        val takesLines = owner.getAttribute("textWrap") == "TOP_AND_BOTTOM"
        state.get().floatingOf[this] = Floating(
            pos.getAttribute("vertRelTo"), pos.getAttribute("horzRelTo"), pos.long("vertOffset"), pos.long("horzOffset"),
            takesLines, takesLines && pos.getAttribute("flowWithText") == "1"
        )
        return this
    }

    /** An object at its line ([x], [y], [lineHeight]; [textFirstLine]: its
     *  paragraph's first, holding text), or by its offsets when it floats. */
    private fun place(obj: HwpxElement, x: Long, y: Long, textFirstLine: Boolean, lineHeight: Long): HwpxElement {
        val s = state.get()
        val f = s.floatingOf[obj] ?: return obj.at(x, y)
        val px = when (f.horzRel) { "PAPER" -> f.horzOffset - s.bodyXHu; "PAGE" -> f.horzOffset; else -> x + f.horzOffset }
        val py = when (f.vertRel) {
            "PAPER" -> f.vertOffset - s.bodyYHu
            "PAGE" -> f.vertOffset
            else -> paragraphRelativeY(f.vertOffset, f.takesLines, f.keepsBelowAnchor, obj.extentHu, y, textFirstLine, lineHeight)
        }
        return obj.at(px, py).also { s.placedFloating += it }
    }

    /** An object's size from <hp:sz>, which may be a percentage (in 1/100 %) of
     *  the paragraph's or page's width or height instead of HWPUNIT. */
    private fun sizeOf(owner: DomElement): Pair<Long, Long>? {
        val sz = owner.child(HP, "sz") ?: return null
        val s = state.get()
        fun dim(value: Long, rel: String, ref: Long) = if (rel.isBlank() || rel == "ABSOLUTE") value else value * ref / 10000
        val w = dim(sz.long("width"), sz.getAttribute("widthRelTo"), s.bodyWidthHu)
        val h = dim(sz.long("height"), sz.getAttribute("heightRelTo"), s.bodyWidthHu)
        // A straight line has no height (or width).
        return if (w >= 0 && h >= 0 && w + h > 0) w to h else null
    }

    private val SHAPES = setOf("rect", "ellipse", "line", "polygon", "container", "arc", "curve")

    /** A drawing object (<hp:rect>, <hp:line>, ...): one shape, or a group of them. */
    private fun parseDrawing(el: DomElement, header: Header, depth: Int): HwpxElement? {
        val (w, h) = sizeOf(el) ?: return null
        val members = drawingMembers(el, IDENTITY, header, depth, w, h)
        if (members.isEmpty()) return null
        val single = members.singleOrNull()
        return if (single != null && single.xHu == 0L && single.yHu == 0L) single
        else HwpxElement.Drawing(0, 0, w, h, DrawingKind.RECTANGLE, lineArgb = null, content = members)
    }

    /**
     * Shapes in the object's box: each maps its original-size coordinates through
     * its rendering matrices (translation, then scale and rotation pairs) into its
     * parent's, a group's members through the group's. A top-level shape whose
     * matrices leave it at its original size is stretched to the object's size.
     */
    private fun drawingMembers(el: DomElement, parent: DoubleArray, header: Header, depth: Int, boxW: Long, boxH: Long): List<HwpxElement.Drawing> {
        if (depth > Limits.MAX_NESTING) return emptyList()
        val info = el.child(HP, "renderingInfo")
        var m = parent
        for (mx in info?.children().orEmpty()) {
            val e = DoubleArray(6) { mx.getAttribute("e${it + 1}").toDoubleOrNull() ?: if (it == 0 || it == 4) 1.0 else 0.0 }
            if (e.all { it.isFinite() && kotlin.math.abs(it) < 1e7 }) m = multiply(m, e)
        }
        if (el.localName == "container") {
            return el.children().filter { it.localName in SHAPES }.flatMap { drawingMembers(it, m, header, depth + 1, boxW, boxH) }
        }
        val org = el.child(HP, "orgSz")
        val ow = (org?.long("width") ?: boxW).toDouble().coerceAtLeast(1.0)
        val oh = (org?.long("height") ?: boxH).toDouble().coerceAtLeast(1.0)
        if (depth == 0 && m.contentEquals(IDENTITY)) m = doubleArrayOf(boxW / ow, 0.0, 0.0, 0.0, boxH / oh, 0.0)
        fun mapped(pts: List<Pair<Double, Double>>) = pts.map { (x, y) -> (m[0] * x + m[1] * y + m[2]) to (m[3] * x + m[4] * y + m[5]) }
        val corners = listOf(0.0 to 0.0, ow to 0.0, ow to oh, 0.0 to oh)
        fun pt(name: String) = el.child(HC, name)?.let { it.long("x").toDouble() to it.long("y").toDouble() }
        val (kind, points) = when (el.localName) {
            "rect" -> DrawingKind.RECTANGLE to mapped(corners)
            "ellipse", "arc" -> DrawingKind.ELLIPSE to mapped(corners)
            "line" -> DrawingKind.LINE to mapped(listOfNotNull(pt("startPt"), pt("endPt")))
            "polygon", "curve" -> DrawingKind.POLYGON to mapped(el.all(HC, "pt").map { it.long("x").toDouble() to it.long("y").toDouble() })
            else -> return emptyList()
        }
        if (points.size < 2 && kind != DrawingKind.RECTANGLE) return emptyList()
        val minX = points.minOf { it.first }; val minY = points.minOf { it.second }
        val w = (points.maxOf { it.first } - minX).toLong().coerceAtLeast(1)
        val h = (points.maxOf { it.second } - minY).toLong().coerceAtLeast(1)
        val rel = points.map { (x, y) -> (x - minX).toLong() to (y - minY).toLong() }
        val line = el.child(HP, "lineShape")
        val lineArgb = line?.takeIf { it.getAttribute("style").let { s -> s.isNotBlank() && s != "NONE" } }?.getAttribute("color")?.let(::hexColor)
        val fill = el.child(HC, "fillBrush")?.child(HC, "winBrush")?.getAttribute("faceColor")?.let(::hexColor)
        // A text box: <hp:drawText> with margins and a list of paragraphs.
        val drawText = el.child(HP, "drawText")
        val margin = drawText?.child(HP, "textMargin")
        val list = drawText?.child(HP, "subList")
        val content = list?.all(HP, "p").orEmpty().withIndex().flatMap { (k, p) -> parseParagraphFlow(p, header, depth + 1, firstInList = k == 0) }
            .map { it.at(it.xHu + (margin?.long("left") ?: 0L), it.yHu + (margin?.long("top") ?: 0L)) }
        val axisAligned = kind != DrawingKind.RECTANGLE || rel.all { (x, y) -> (x == 0L || x == w) && (y == 0L || y == h) }
        return listOf(HwpxElement.Drawing(
            minX.toLong(), minY.toLong(), w, h, if (axisAligned) kind else DrawingKind.POLYGON, rel,
            el.getAttribute("ratio").toIntOrNull() ?: 0, lineArgb, line?.long("width") ?: 0L, fill, content,
            when (list?.getAttribute("vertAlign")) { "CENTER" -> 1; "BOTTOM" -> 2; else -> 0 }
        ))
    }

    private fun hexColor(v: String): Int? = v.removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)?.let { (0xFF shl 24) or it }

    /** 2x3 affine matrices [a, b, c, d, e, f]: x' = a x + b y + c, y' = d x + e y + f. */
    private val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0)

    private fun multiply(m: DoubleArray, n: DoubleArray) = doubleArrayOf(
        m[0] * n[0] + m[1] * n[3], m[0] * n[1] + m[1] * n[4], m[0] * n[2] + m[1] * n[5] + m[2],
        m[3] * n[0] + m[4] * n[3], m[3] * n[1] + m[4] * n[4], m[3] * n[2] + m[4] * n[5] + m[5]
    )


    /** [firstInList]: [p] starts a header, footer or text box (its top is the list's top). */
    private fun parseParagraphFlow(p: DomElement, header: Header, depth: Int, firstInList: Boolean = false): List<HwpxElement> {
        val text = StringBuilder()
        val inlineAt = mutableMapOf<Int, HwpxElement>() // stream offset -> object
        // Marks where each run's text starts in the concatenated stream, so a
        // lineseg slice can look up which run (and therefore which style) covers it.
        val runBoundaries = mutableListOf<Pair<Int, CharStyle>>()
        val tabWidths = HashMap<Int, Long>()

        for (run in p.all(HP, "run")) {
            val charPrId = run.getAttribute("charPrIDRef").ifBlank { null }
            val style = header.styleOf(charPrId)
            for (child in run.children()) {
                when (child.localName) {
                    "t" -> {
                        runBoundaries += text.length to style
                        // Text with inline markers: a tab carries the width it took.
                        for (node in child.childNodes.let { l -> (0 until l.length).map { l.item(it) } }) {
                            if (node.nodeType == org.w3c.dom.Node.TEXT_NODE || node.nodeType == org.w3c.dom.Node.CDATA_SECTION_NODE) {
                                text.append(node.nodeValue ?: "")
                                continue
                            }
                            when (node.localName) {
                                "tab" -> { tabWidths[text.length] = (node as DomElement).long("width"); text.append('\t') }
                                "nbSpace", "fwSpace" -> text.append(' ')
                                "hyphen" -> text.append('-')
                                "lineBreak" -> text.append(' ')
                            }
                        }
                    }
                    "tbl" -> parseTable(child, header, depth + 1)?.positionedBy(child)?.let { inlineAt[text.length] = it; text.append(INLINE_OBJECT_MARKER) }
                    "pic" -> parsePicture(child)?.positionedBy(child)?.let { inlineAt[text.length] = it; text.append(INLINE_OBJECT_MARKER) }
                    in SHAPES -> parseDrawing(child, header, depth + 1)?.positionedBy(child)?.let { inlineAt[text.length] = it; text.append(INLINE_OBJECT_MARKER) }
                    // A page number field (in headers and footers, mostly).
                    "ctrl" -> if (child.children().any { it.localName == "autoNum" && it.getAttribute("numType") == "PAGE" }) {
                        runBoundaries += text.length to style
                        text.append(PAGE_NUMBER_MARK)
                    }
                }
            }
        }
        if (text.isEmpty()) return emptyList()

        // Direct child only: a table-carrying paragraph's own line is what we need
        // here, not one of the many <hp:linesegarray> that its cells' inner
        // paragraphs also have — a recursive search would find one of those first
        // and place the whole table at whatever position that unrelated cell line
        // happens to have (usually (0, 0) for a cell's first line).
        val lineSegs = p.child(HP, "linesegarray")?.all(HP, "lineseg").orEmpty()
        if (lineSegs.isEmpty()) return emptyList()

        val align = header.paraAligns[p.getAttribute("paraPrIDRef")] ?: ParaAlign.LEFT
        val out = mutableListOf<HwpxElement>()
        for ((i, seg) in lineSegs.withIndex()) {
            val start = seg.long("textpos").toInt().coerceIn(0, text.length)
            val end = if (i + 1 < lineSegs.size) lineSegs[i + 1].long("textpos").toInt().coerceIn(0, text.length) else text.length
            if (end <= start) continue
            val last = i == lineSegs.size - 1

            var runStart = start
            val lineFirst = out.size
            fun emit(el: HwpxElement) {
                val st = state.get()
                if (el !in st.placedFloating && out.drop(lineFirst).all { it in st.placedFloating }) st.lineStarts += el
                out += el
            }
            for (offset in start until end) {
                val obj = inlineAt[offset] ?: continue
                if (offset > runStart) {
                    textRunFrom(text, runStart, offset, runBoundaries, seg, align, last, tabWidths)?.let { emit(it) }
                }
                val textFirstLine = firstInList && i == 0 && text.substring(start, end).any { it != INLINE_OBJECT_MARKER && it != PAGE_NUMBER_MARK && !it.isWhitespace() }
                emit(place(obj, seg.long("horzpos"), seg.long("vertpos"), textFirstLine, seg.long("vertsize")))
                runStart = offset + 1
            }
            if (runStart < end) {
                textRunFrom(text, runStart, end, runBoundaries, seg, align, last, tabWidths)?.let { emit(it) }
            }
        }
        return out
    }

    /** The line's text split where its character style changes. */
    private fun textRunFrom(
        text: StringBuilder, start: Int, end: Int,
        runBoundaries: List<Pair<Int, CharStyle>>, seg: DomElement, align: ParaAlign, lastLine: Boolean,
        tabWidths: Map<Int, Long>
    ): HwpxElement.TextRun? {
        if (text.substring(start, end).isBlank()) return null
        val spans = mutableListOf<Span>()
        var pos = start
        while (pos < end) {
            val idx = runBoundaries.indexOfLast { it.first <= pos }.coerceAtLeast(0)
            val style = runBoundaries.getOrNull(idx)?.second ?: CharStyle()
            val next = (runBoundaries.getOrNull(idx + 1)?.first ?: end).coerceIn(pos + 1, end)
            var from = pos
            for (k in pos..next) {
                if (k < next && text[k] != INLINE_OBJECT_MARKER) continue
                if (k > from) spans += spansOf(text.substring(from, k), from, style, tabWidths)
                from = k + 1
            }
            pos = next
        }
        val height = seg.long("textheight")
        return HwpxElement.TextRun(
            xHu = seg.long("horzpos"), yHu = seg.long("vertpos"), widthHu = seg.long("horzsize"),
            heightHu = height, spans = spans.trimEndSpaces(),
            baselineHu = seg.longOrNull("baseline") ?: (height * 85 / 100),
            align = align, lastLine = lastLine
        )
    }

    /** A line's trailing spaces take no room (alignment measures without them). */
    private fun List<Span>.trimEndSpaces(): List<Span> {
        val out = toMutableList()
        while (out.isNotEmpty()) {
            val t = out.last().text.trimEnd()
            if (t.isEmpty()) out.removeAt(out.size - 1) else { out[out.size - 1] = out.last().copy(text = t); break }
        }
        return out
    }

    private fun HwpxElement.shifted(dx: Long, dy: Long): HwpxElement = withPosition(xHu + dx, yHu + dy)

    /** A line can't be wider than the cell it is in; some writers save the page's
     *  width for lines in cells. */
    private fun HwpxElement.fitTo(width: Long): HwpxElement =
        if (this is HwpxElement.TextRun && width > 0 && xHu + widthHu > width) copy(widthHu = (width - xHu).coerceAtLeast(width / 4)) else this

    private fun HwpxElement.withPosition(x: Long, y: Long): HwpxElement = at(x, y)

    private fun parseTable(tbl: DomElement, header: Header, depth: Int): HwpxElement.Table? {
        if (depth > Limits.MAX_NESTING) return null
        val sz = tbl.child(HP, "sz") ?: return null
        // treatAsChar tables sit inline in the text flow, so their placement is
        // wherever the paragraph put them (an offset would double count) — only a
        // floating table (rare; not in any sample this was tested against) would
        // need <hp:pos>'s own horzOffset/vertOffset, which is left at 0 rather than
        // guessed at without a sample to check against.
        val x = 0L; val y = 0L

        // <hp:tbl> already declares its own grid size, so the cell list is sized
        // once up front rather than grown as cells are seen — growing it caused an
        // out-of-bounds write whenever a later row turned out wider than an
        // already-appended earlier one (a row with a spanned cell, for instance).
        // The declared size is trusted only as far as the cells actually present: a
        // crafted 50000 x 50000 declaration must not allocate 2.5 billion slots.
        val tcs = tbl.all(HP, "tr").flatMap { it.all(HP, "tc") }
        fun addr(attr: String) = tcs.maxOfOrNull { it.child(HP, "cellAddr")?.getAttribute(attr)?.toIntOrNull() ?: 0 } ?: -1
        val rowCnt = (tbl.getAttribute("rowCnt").toIntOrNull() ?: 0).coerceIn(0, addr("rowAddr") + 1)
        val colCnt = (tbl.getAttribute("colCnt").toIntOrNull() ?: 0).coerceIn(0, addr("colAddr") + 1)
        if (rowCnt == 0 || colCnt == 0) return null
        val rows = MutableList(rowCnt) { MutableList<TableCell?>(colCnt) { null } }
        val colWidths = sortedMapOf<Int, Long>()
        val rowHeights = sortedMapOf<Int, Long>()

        // <hp:tc> sits inside <hp:tr> (one per row), not directly under <hp:tbl>.
        for (tc in tcs) {
            val addr = tc.child(HP, "cellAddr") ?: continue
            val span = tc.child(HP, "cellSpan")
            val col = addr.getAttribute("colAddr").toIntOrNull() ?: continue
            val row = addr.getAttribute("rowAddr").toIntOrNull() ?: continue
            if (row !in rows.indices || col !in rows[row].indices) continue
            val colSpan = span?.getAttribute("colSpan")?.toIntOrNull() ?: 1
            val rowSpan = span?.getAttribute("rowSpan")?.toIntOrNull() ?: 1

            // A cell's own nested table/picture is out of scope for this pass (the
            // cell coordinate space would need to nest too) — text only. Lines are
            // relative to the cell's inner area; some writers start each paragraph
            // at 0 rather than below the previous one, and those are stacked.
            val subList = tc.child(HP, "subList")
            val margin = tc.child(HP, "cellMargin")
            var cursor = 0L
            val cellRuns = subList?.all(HP, "p").orEmpty().flatMap { cellP ->
                val segs = cellP.child(HP, "linesegarray")?.all(HP, "lineseg").orEmpty()
                val firstY = segs.firstOrNull()?.long("vertpos")
                val shift = if (firstY != null && firstY < cursor) cursor - firstY else 0L
                segs.lastOrNull()?.let { cursor = it.long("vertpos") + it.long("vertsize").takeIf { v -> v > 0 }.let { v -> v ?: it.long("textheight") } + it.long("spacing") + shift }
                parseParagraphFlow(cellP, header, depth).map { it.shifted(0, shift) }
            }.map { it.shifted(margin?.long("left") ?: 0L, margin?.long("top") ?: 0L) }
                .map { it.fitTo(tc.child(HP, "cellSz")?.long("width")?.minus((margin?.long("left") ?: 0L) + (margin?.long("right") ?: 0L)) ?: 0L) }
            // <hp:cellSz> is a declared minimum that real content routinely outgrows
            // without it being kept in sync — one real sample had a 6-line answer
            // measured at 282 HWPUNIT tall, under half a line, and a 4-column table
            // had columns narrow enough that neighbouring cells' text overlapped
            // when drawn at the declared width. The actual extent of the cell's own
            // text is used instead, for single-row/single-column cells; a spanned
            // cell's size is left to the rows/columns it spans, not attributed to
            // just the first one.
            if (rowSpan == 1) {
                // Pictures and nested tables count too, not just lines of text.
                val contentBottom = cellRuns.maxOfOrNull { it.yHu + it.extentHu } ?: 0L
                rowHeights[row] = maxOf(rowHeights[row] ?: 0L, contentBottom)
            }
            if (colSpan == 1) {
                val contentRight = cellRuns.maxOfOrNull { el ->
                    el.xHu + when (el) {
                        is HwpxElement.TextRun -> el.widthHu
                        is HwpxElement.Table -> el.widthHu
                        is HwpxElement.ImageEl -> el.widthHu
                        is HwpxElement.Drawing -> el.widthHu
                    }
                } ?: 0L
                colWidths[col] = maxOf(colWidths[col] ?: 0L, contentRight)
            }
            rows[row][col] = TableCell(
                colSpan, rowSpan, cellRuns,
                borderFill = header.borderFills[tc.getAttribute("borderFillIDRef")],
                vAlign = when (subList?.getAttribute("vertAlign")) { "CENTER" -> 1; "BOTTOM" -> 2; else -> 0 }
            )
        }
        if (rows.isEmpty()) return null
        val maxCol = colCnt

        return HwpxElement.Table(
            xHu = x, yHu = y,
            widthHu = sz.long("width"), heightHu = sz.long("height"),
            columnWidthsHu = (0 until maxCol).map { colWidths[it] ?: (sz.long("width") / maxOf(maxCol, 1)) },
            rowHeightsHu = (0 until rows.size).map { rowHeights[it] ?: (sz.long("height") / maxOf(rows.size, 1)) },
            cells = rows
        )
    }

    private fun parsePicture(pic: DomElement): HwpxElement.ImageEl? {
        val (picW, picH) = sizeOf(pic)?.takeIf { it.first > 0 && it.second > 0 } ?: return null
        val img = pic.child(HC, "img") ?: return null
        val entry = img.getAttribute("binaryItemIDRef").ifBlank { null } ?: return null
        // The actual zip path for a binary item follows a fixed convention
        // (BinData/<id>.<ext>) but the extension isn't in this reference — resolved
        // by the caller matching a real zip entry, since guessing the extension
        // here would silently draw nothing for anything but the common ones.
        // <hp:imgClip> is the visible part of the picture within its full size,
        // <hp:imgDim> (or, without it, <hp:imgRect>'s corners).
        val rect = pic.child(HP, "imgRect")
        val clip = pic.child(HP, "imgClip")
        val dim = pic.child(HP, "imgDim")?.takeIf { it.long("dimwidth") > 0 && it.long("dimheight") > 0 }
        val crop = if (clip != null && (dim != null || rect != null)) {
            val w = dim?.long("dimwidth")?.toDouble() ?: rect?.child(HC, "pt2")?.long("x")?.toDouble() ?: 0.0
            val h = dim?.long("dimheight")?.toDouble() ?: rect?.child(HC, "pt2")?.long("y")?.toDouble() ?: 0.0
            ImageCrop.of(clip.long("left").toDouble(), clip.long("top").toDouble(), clip.long("right").toDouble(), clip.long("bottom").toDouble(), w, h)
        } else null
        return HwpxElement.ImageEl(0, 0, picW, picH, entry, crop = crop)
    }

    private fun parseHeader(doc: Document): Header {
        val refList = doc.first(HH, "refList")
        // Fonts are listed per script; a character property names one per script.
        val faces = refList?.first(HH, "fontfaces")?.all(HH, "fontface").orEmpty().associate { ff ->
            ff.getAttribute("lang") to ff.all(HH, "font").associate { it.getAttribute("id") to it.getAttribute("face") }
        }
        val styles = mutableMapOf<String, CharStyle>()
        for (charPr in refList?.first(HH, "charProperties")?.all(HH, "charPr").orEmpty()) {
            val id = charPr.getAttribute("id")
            if (id.isBlank()) continue
            val heightHundredthsPt = charPr.getAttribute("height").toLongOrNull() ?: 1000L
            val colorHex = charPr.getAttribute("textColor").removePrefix("#").takeIf { it.length == 6 }
            val fontRef = charPr.child(HH, "fontRef")
            styles[id] = CharStyle(
                sizePt = heightHundredthsPt / 100f,
                colorArgb = colorHex?.toIntOrNull(16)?.let { (0xFF shl 24) or it } ?: 0xFF000000.toInt(),
                bold = charPr.child(HH, "bold") != null,
                italic = charPr.child(HH, "italic") != null,
                underline = charPr.child(HH, "underline")?.getAttribute("type")?.let { it != "NONE" } ?: false,
                hangulFace = fontRef?.getAttribute("hangul")?.let { faces["HANGUL"]?.get(it) },
                latinFace = fontRef?.getAttribute("latin")?.let { faces["LATIN"]?.get(it) },
                widthPercent = charPr.child(HH, "ratio")?.getAttribute("hangul")?.toIntOrNull() ?: 100,
                spacingPercent = charPr.child(HH, "spacing")?.getAttribute("hangul")?.toIntOrNull() ?: 0,
                script = when {
                    charPr.child(HH, "supscript") != null -> 1
                    charPr.child(HH, "subscript") != null -> -1
                    else -> 0
                }
            )
        }
        val aligns = refList?.first(HH, "paraProperties")?.all(HH, "paraPr").orEmpty().associate { pp ->
            pp.getAttribute("id") to when (pp.first(HH, "align")?.getAttribute("horizontal")) {
                "CENTER" -> ParaAlign.CENTER
                "RIGHT" -> ParaAlign.RIGHT
                "JUSTIFY" -> ParaAlign.JUSTIFY
                "DISTRIBUTE", "DISTRIBUTE_SPACE" -> ParaAlign.DISTRIBUTE
                else -> ParaAlign.LEFT
            }
        }
        val borderFills = refList?.first(HH, "borderFills")?.all(HH, "borderFill").orEmpty().associate { bf ->
            fun side(name: String): Border? {
                val b = bf.child(HH, name) ?: return null
                if (b.getAttribute("type").let { it.isBlank() || it == "NONE" }) return null
                val mm = b.getAttribute("width").substringBefore(' ').toFloatOrNull() ?: 0.12f
                val color = b.getAttribute("color").removePrefix("#").toIntOrNull(16) ?: 0
                return Border((0xFF shl 24) or color, (mm * HWPUNIT_PER_INCH / 25.4).toLong())
            }
            val face = bf.first(HC, "winBrush")?.getAttribute("faceColor")?.removePrefix("#")
                ?.takeIf { it.length == 6 }?.toIntOrNull(16)?.let { (0xFF shl 24) or it }
            bf.getAttribute("id") to BorderFill(side("leftBorder"), side("rightBorder"), side("topBorder"), side("bottomBorder"), face)
        }
        return Header(styles, aligns, borderFills)
    }

    // ---- zip / xml helpers -------------------------------------------------

    private fun ZipFile.xml(path: String): Document? = SafeXml.parse(this, path)

    private fun Node.children(): List<DomElement> {
        val out = mutableListOf<DomElement>()
        val list = childNodes
        for (i in 0 until list.length) (list.item(i) as? DomElement)?.let { out += it }
        return out
    }

    private fun Node.first(ns: String, name: String): DomElement? {
        for (child in children()) {
            if (child.namespaceURI == ns && child.localName == name) return child
            child.first(ns, name)?.let { return it }
        }
        return null
    }

    private fun Node.all(ns: String, name: String): List<DomElement> =
        children().filter { it.namespaceURI == ns && it.localName == name }

    private fun Node.child(ns: String, name: String): DomElement? =
        children().firstOrNull { it.namespaceURI == ns && it.localName == name }

    /** Alias for [child] used where "the shape's one direct <hp:run>" reads more
     *  clearly as "run" than "child". */
    private fun Node.run(ns: String, name: String): DomElement? = child(ns, name)

    private fun DomElement.long(attr: String): Long = getAttribute(attr).toLongOrNull() ?: 0L
    private fun DomElement.longOrNull(attr: String): Long? = getAttribute(attr).toLongOrNull()
}
