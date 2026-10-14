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
    private data class PageGeometry(val widthHu: Long, val heightHu: Long, val bodyXHu: Long = 0, val bodyYHu: Long = 0, val bottomHu: Long = 0)

    fun parse(file: File): HwpxDoc = ZipFile(file).use { zip ->
        lineStarts.get().clear()
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
                parseSection(doc, header, lastGeometry).forEach { (geometry, page) ->
                    lastGeometry = geometry
                    if (pages.size < Limits.MAX_PAGES) pages += page
                }
            } catch (e: Exception) {
                // One unreadable section costs its own pages, not the whole document.
                if (DEBUG_LOG_ERRORS) e.printStackTrace()
            }
        }
        lineStarts.get().clear()
        HwpxDoc(pages)
    }

    /** Splits one section into pages by watching the top-level flow's vertpos: a
     *  reset to a smaller value than the previous line means Hangul started a new
     *  page when it saved this file (verified against a real 3-page document —
     *  see [HwpxDoc]). */
    private fun parseSection(doc: Document, header: Header, fallbackGeometry: PageGeometry): List<Pair<PageGeometry, HwpxPage>> {
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
            if (w != null && h != null && w > 0 && h > 0) PageGeometry(w, h, bodyX, bodyY, bottom) else null
        } ?: fallbackGeometry

        val flow = PageFlow(geometry.heightHu - geometry.bodyYHu - geometry.bottomHu)
        for (p in paragraphs) {
            if (flow.full) break
            // pageBreak="1": the paragraph starts a page even when its first line
            // sits as high as the previous page's last paragraph did.
            val forcedBreak = p.getAttribute("pageBreak") == "1"
            for ((k, el) in parseParagraphFlow(p, header, 0).withIndex()) {
                flow.add(el, startsPage = k == 0 && forcedBreak, startsLine = el in lineStarts.get())
            }
        }
        return flow.finish().map { geometry to HwpxPage(geometry.widthHu, geometry.heightHu, it, geometry.bodyXHu, geometry.bodyYHu) }
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
    /** Elements that begin a line (see PageFlow.add); identity, not equality. */
    private val lineStarts = ThreadLocal.withInitial { java.util.Collections.newSetFromMap(java.util.IdentityHashMap<HwpxElement, Boolean>()) }

    private fun parseParagraphFlow(p: DomElement, header: Header, depth: Int): List<HwpxElement> {
        val text = StringBuilder()
        val inlineAt = mutableMapOf<Int, HwpxElement>() // stream offset -> object
        // Marks where each run's text starts in the concatenated stream, so a
        // lineseg slice can look up which run (and therefore which style) covers it.
        val runBoundaries = mutableListOf<Pair<Int, CharStyle>>()

        for (run in p.all(HP, "run")) {
            val charPrId = run.getAttribute("charPrIDRef").ifBlank { null }
            val style = header.styleOf(charPrId)
            for (child in run.children()) {
                when (child.localName) {
                    "t" -> {
                        runBoundaries += text.length to style
                        text.append(child.textContent ?: "")
                    }
                    "tbl" -> parseTable(child, header, depth + 1)?.let { inlineAt[text.length] = it; text.append(INLINE_OBJECT_MARKER) }
                    "pic" -> parsePicture(child)?.let { inlineAt[text.length] = it; text.append(INLINE_OBJECT_MARKER) }
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
                if (out.size == lineFirst) lineStarts.get() += el
                out += el
            }
            for (offset in start until end) {
                val obj = inlineAt[offset] ?: continue
                if (offset > runStart) {
                    textRunFrom(text, runStart, offset, runBoundaries, seg, align, last)?.let { emit(it) }
                }
                emit(obj.withPosition(seg.long("horzpos"), seg.long("vertpos")))
                runStart = offset + 1
            }
            if (runStart < end) {
                textRunFrom(text, runStart, end, runBoundaries, seg, align, last)?.let { emit(it) }
            }
        }
        return out
    }

    /** The line's text split where its character style changes. */
    private fun textRunFrom(
        text: StringBuilder, start: Int, end: Int,
        runBoundaries: List<Pair<Int, CharStyle>>, seg: DomElement, align: ParaAlign, lastLine: Boolean
    ): HwpxElement.TextRun? {
        if (text.substring(start, end).isBlank()) return null
        val spans = mutableListOf<Span>()
        var pos = start
        while (pos < end) {
            val idx = runBoundaries.indexOfLast { it.first <= pos }.coerceAtLeast(0)
            val style = runBoundaries.getOrNull(idx)?.second ?: CharStyle()
            val next = (runBoundaries.getOrNull(idx + 1)?.first ?: end).coerceIn(pos + 1, end)
            val piece = text.substring(pos, next).replace(INLINE_OBJECT_MARKER.toString(), "")
            if (piece.isNotEmpty()) spans += Span(piece, style)
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

    private fun HwpxElement.withPosition(x: Long, y: Long): HwpxElement = when (this) {
        is HwpxElement.Table -> copy(xHu = x, yHu = y)
        is HwpxElement.ImageEl -> copy(xHu = x, yHu = y)
        is HwpxElement.TextRun -> copy(xHu = x, yHu = y)
    }

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
                val contentBottom = cellRuns.filterIsInstance<HwpxElement.TextRun>().maxOfOrNull { it.yHu + it.heightHu } ?: 0L
                rowHeights[row] = maxOf(rowHeights[row] ?: 0L, contentBottom)
            }
            if (colSpan == 1) {
                val contentRight = cellRuns.filterIsInstance<HwpxElement.TextRun>().maxOfOrNull { it.xHu + it.widthHu } ?: 0L
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
        val sz = pic.child(HP, "sz") ?: return null
        val img = pic.child(HC, "img") ?: return null
        val entry = img.getAttribute("binaryItemIDRef").ifBlank { null } ?: return null
        // The actual zip path for a binary item follows a fixed convention
        // (BinData/<id>.<ext>) but the extension isn't in this reference — resolved
        // by the caller matching a real zip entry, since guessing the extension
        // here would silently draw nothing for anything but the common ones.
        return HwpxElement.ImageEl(0, 0, sz.long("width"), sz.long("height"), entry)
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
