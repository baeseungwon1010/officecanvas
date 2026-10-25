package io.github.baeseungwon1010.officecanvas.hwpx

/**
 * A parsed .hwpx, already broken into pages of absolutely-positioned pieces.
 *
 * This looks like the slide model (see the `slide` package) for the same reason it can
 * exist at all: HWPX stores each line's position (<hp:lineseg>: vertpos, horzpos,
 * horzsize) as computed by Hangul at save time, not just the flowing text. A run
 * of paragraphs starting a new page shows vertpos reset to a value at or near 0 —
 * confirmed against a real 3-page document, where the paragraph the layout engine
 * places at the top of PDF page 3 is exactly the paragraph whose first lineseg
 * resets to vertpos 0. So instead of a layout engine (line breaking, page breaks),
 * this parser mostly reads positions Hangul already computed. The trade is that a
 * hand-built .hwpx with no linesegarray (some non-Hangul writers might omit it)
 * would parse as one page with everything piled at y=0; that has not come up in
 * any real sample and there is no cheap way to detect it, so it is left as a known
 * gap rather than guessed around.
 *
 * All geometry is in HWPUNIT: 7200 per inch (confirmed: an A4 section's stored
 * width, 59528, divided by 8.2677in comes out to 7200.0).
 */

internal const val HWPUNIT_PER_INCH = 7200.0

internal data class HwpxDoc(
    val pages: List<HwpxPage>,
    /** The open file pictures are read from as they are drawn (a .hwp's);
     *  whoever renders the document closes it afterwards. */
    val resources: java.io.Closeable? = null
)

/** Element positions are relative to the body area, as Hangul saves them;
 *  [bodyXHu]/[bodyYHu] is where that area starts on the page. */
internal data class HwpxPage(
    val widthHu: Long,
    val heightHu: Long,
    val elements: List<HwpxElement>,
    val bodyXHu: Long = 0,
    val bodyYHu: Long = 0
)

/** Paragraph alignment. JUSTIFY stretches every line but a paragraph's last;
 *  DISTRIBUTE stretches every line, spreading the space between all characters. */
internal enum class ParaAlign { LEFT, RIGHT, CENTER, JUSTIFY, DISTRIBUTE }

internal data class CharStyle(
    val sizePt: Float = 10f,
    val colorArgb: Int = 0xFF000000.toInt(),
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    /** Font faces for Hangul (and other CJK) text and for Latin text. */
    val hangulFace: String? = null,
    val latinFace: String? = null,
    /** 장평: glyph width as a percent of normal. */
    val widthPercent: Int = 100,
    /** 자간: extra space between characters as a percent of the font size. */
    val spacingPercent: Int = 0,
    /** 1 for superscript, -1 for subscript. */
    val script: Int = 0
)

/** A stretch of a line in one character style. */
/** Styled text; a tab is a span of its own ("\t") with the width Hangul gave
 *  it ([tabHu]), since both formats save each tab's resulting width. An object
 *  set in the line like a character (an equation, a small picture) is a span
 *  too, [inline], taking its own width. */
internal data class Span(val text: String, val style: CharStyle, val tabHu: Long? = null, val inline: HwpxElement? = null)

/** [piece] (which starts at [start] in its paragraph) as spans, each tab on its own
 *  with its saved width from [tabWidths] (by paragraph offset). */
internal fun spansOf(piece: String, start: Int, style: CharStyle, tabWidths: Map<Int, Long>): List<Span> {
    if ('\t' !in piece) return listOf(Span(piece, style))
    val out = mutableListOf<Span>()
    var from = 0
    for ((k, c) in piece.withIndex()) {
        if (c != '\t') continue
        if (k > from) out += Span(piece.substring(from, k), style)
        out += Span("\t", style, (tabWidths[start + k] ?: DEFAULT_TAB_HU).coerceIn(0, 100_000))
        from = k + 1
    }
    if (from < piece.length) out += Span(piece.substring(from), style)
    return out
}

/** Hangul's default tab stop spacing (40 pt). */
internal const val DEFAULT_TAB_HU = 4000L

/** A shaded area and border lines; a null side draws no line. */
internal data class Border(val argb: Int, val widthHu: Long)
internal data class BorderFill(
    val left: Border? = null,
    val right: Border? = null,
    val top: Border? = null,
    val bottom: Border? = null,
    val fillArgb: Int? = null
)

internal sealed class HwpxElement {
    abstract val xHu: Long
    abstract val yHu: Long

    /** One line as Hangul laid it out: where it starts, how wide its column is,
     *  how tall it is and where its baseline sits, and its text in styled spans. */
    data class TextRun(
        override val xHu: Long,
        override val yHu: Long,
        val widthHu: Long,
        /** The line's own height (<hp:lineseg>'s textheight), used to size a
         *  table row from its actual content rather than the cell's declared
         *  <hp:cellSz>, which is a minimum/default that real content routinely
         *  exceeds without HWPUNIT updating it. */
        val heightHu: Long,
        val spans: List<Span>,
        /** Baseline offset from the line's top. */
        val baselineHu: Long = heightHu * 85 / 100,
        val align: ParaAlign = ParaAlign.LEFT,
        /** The paragraph's last line, which JUSTIFY leaves unstretched. */
        val lastLine: Boolean = true
    ) : HwpxElement() {
        val text: String get() = spans.joinToString("") { it.text }
        val sizePt: Float get() = spans.maxOfOrNull { it.style.sizePt } ?: 10f
    }

    data class Table(
        override val xHu: Long,
        override val yHu: Long,
        val widthHu: Long,
        val heightHu: Long,
        val columnWidthsHu: List<Long>,
        val rowHeightsHu: List<Long>,
        /** Row-major; a cell covered by another cell's span is null. */
        val cells: List<List<TableCell?>>,
        /** Leading rows repeated at the top of each page the table runs onto. */
        val headerRows: Int = 0,
        /** Rows may be cut at a page's end (Word); otherwise only a row taller
         *  than a page is (Hangul). */
        val rowsBreak: Boolean = false
    ) : HwpxElement()

    data class ImageEl(
        override val xHu: Long,
        override val yHu: Long,
        val widthHu: Long,
        val heightHu: Long,
        /** Entry name inside the .hwpx zip, e.g. "BinData/image1.png"; null when
         *  [bytes] reads the picture (a .hwp keeps pictures in OLE2 streams, read
         *  from the file only when drawn). */
        val zipEntry: String?,
        val bytes: (() -> ByteArray?)? = null,
        /** Fractions of the source image trimmed from each edge. */
        val crop: ImageCrop? = null
    ) : HwpxElement()

    /**
     * A drawing object: a rectangle (corners rounded by [cornerPercent] of the
     * shorter side), an ellipse, or a line or polygon through [points] (relative to
     * the box). [content] is a text box's lines, or a group's member objects,
     * positioned relative to the box.
     */
    data class Drawing(
        override val xHu: Long,
        override val yHu: Long,
        val widthHu: Long,
        val heightHu: Long,
        val kind: DrawingKind,
        val points: List<Pair<Long, Long>> = emptyList(),
        val cornerPercent: Int = 0,
        val lineArgb: Int? = 0xFF000000.toInt(),
        val lineWidthHu: Long = 0,
        val fillArgb: Int? = null,
        val content: List<HwpxElement> = emptyList(),
        val contentVAlign: Int = 0
    ) : HwpxElement()

    /**
     * An equation: Hangul's equation script, typeset again into the box Hangul
     * saved for it. [sizeHu] is the base font size (1000 = 10 pt) and
     * [baselinePercent] where the baseline sits, from the box's top.
     */
    data class Equation(
        override val xHu: Long,
        override val yHu: Long,
        val widthHu: Long,
        val heightHu: Long,
        val script: String,
        val sizeHu: Long = 1000,
        val colorArgb: Int = 0xFF000000.toInt(),
        val baselinePercent: Int = 85
    ) : HwpxElement()
}

internal data class ImageCrop(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    companion object {
        /** From a clip rectangle inside a [w] x [h] image; null when nothing is cut. */
        fun of(left: Double, top: Double, right: Double, bottom: Double, w: Double, h: Double): ImageCrop? {
            if (w <= 0 || h <= 0) return null
            val c = ImageCrop((left / w).toFloat(), (top / h).toFloat(), ((w - right) / w).toFloat(), ((h - bottom) / h).toFloat())
            val ok = listOf(c.left, c.top, c.right, c.bottom).all { it in 0f..0.95f } && c.left + c.right < 0.95f && c.top + c.bottom < 0.95f
            return if (ok && (c.left > 0.001f || c.top > 0.001f || c.right > 0.001f || c.bottom > 0.001f)) c else null
        }
    }
}

internal enum class DrawingKind { RECTANGLE, ELLIPSE, LINE, POLYGON }

internal data class TableCell(
    val colSpan: Int,
    val rowSpan: Int,
    /** Lines, nested tables and pictures, positioned relative to the cell's own
     *  top-left corner. */
    val elements: List<HwpxElement>,
    /** Borders and shading; null draws the default thin grid. */
    val borderFill: BorderFill? = null,
    /** 0 top, 1 middle, 2 bottom: where the content sits in a taller cell. */
    val vAlign: Int = 0
) {
    val runs: List<HwpxElement.TextRun> get() = elements.filterIsInstance<HwpxElement.TextRun>()
}

/** How far an element reaches down from its top. */
internal val HwpxElement.extentHu: Long
    get() = when (this) {
        is HwpxElement.TextRun -> heightHu
        is HwpxElement.Table -> heightHu
        is HwpxElement.ImageEl -> heightHu
        is HwpxElement.Drawing -> heightHu
        is HwpxElement.Equation -> heightHu
    }

/** How wide an element is. */
internal val HwpxElement.widthHu: Long
    get() = when (this) {
        is HwpxElement.TextRun -> widthHu
        is HwpxElement.Table -> widthHu
        is HwpxElement.ImageEl -> widthHu
        is HwpxElement.Drawing -> widthHu
        is HwpxElement.Equation -> widthHu
    }

/** [obj] as a span of a line of text. */
internal fun inlineSpan(obj: HwpxElement, style: CharStyle) = Span(INLINE_OBJECT, style, obj.widthHu.coerceIn(0, 1_000_000), obj)

/** Stands for an object in a line's text. */
internal const val INLINE_OBJECT = "\uFFFC"

/** The element moved to ([x], [y]). */
internal fun HwpxElement.at(x: Long, y: Long): HwpxElement = when (this) {
    is HwpxElement.TextRun -> copy(xHu = x, yHu = y)
    is HwpxElement.Table -> copy(xHu = x, yHu = y)
    is HwpxElement.ImageEl -> copy(xHu = x, yHu = y)
    is HwpxElement.Drawing -> copy(xHu = x, yHu = y)
    is HwpxElement.Equation -> copy(xHu = x, yHu = y)
}

/** A cell's place along one side of a table's grid: its first column, how many it spans, its width. */
internal data class GridSpan(val start: Int, val span: Int, val sizeHu: Long)

/**
 * The widths of a table's [count] columns, worked out from where each cell's
 * edges fall: a cell's right edge is its left edge plus its width, so every row
 * places some of the column lines, including those only spanned cells reach. A
 * line no cell places is spread evenly between its neighbours. Null when the
 * cells don't add up to a grid (a line before the one to its left).
 */
internal fun gridSizes(cells: List<GridSpan>, count: Int, totalHu: Long): List<Long>? {
    if (count <= 0) return null
    val edge = arrayOfNulls<Long>(count + 1)
    edge[0] = 0L
    var changed = true
    while (changed) {
        changed = false
        for ((start, span, size) in cells) {
            if (start !in 0 until count || size <= 0) continue
            val end = (start + span.coerceAtLeast(1)).coerceAtMost(count)
            val a = edge[start]; val b = edge[end]
            if (a != null && b == null) { edge[end] = a + size; changed = true }
            else if (a == null && b != null && b - size >= 0) { edge[start] = b - size; changed = true }
        }
    }
    if (edge[count] == null) edge[count] = maxOf(totalHu, edge.filterNotNull().max())
    var i = 0
    while (i < count) {
        val next = (i + 1..count).first { edge[it] != null }
        val a = edge[i]!!; val b = edge[next]!!
        for (k in i + 1 until next) edge[k] = a + (b - a) * (k - i) / (next - i)
        i = next
    }
    val sizes = (0 until count).map { edge[it + 1]!! - edge[it]!! }
    return sizes.takeIf { s -> s.all { it > 0 } }
}

/**
 * A cell's [elements] with whatever comes after a table moved below it when the
 * table is taller than the room its line was saved with (its rows grew to their
 * content); what follows keeps its spacing. Objects [floating] by offsets don't
 * push anything. The same rule as PageFlow's for a page.
 */
internal fun clearOfTables(elements: List<HwpxElement>, floating: (HwpxElement) -> Boolean): List<HwpxElement> {
    if (elements.none { it is HwpxElement.Table }) return elements
    val out = elements.toMutableList()
    var shift = 0L
    var top = 0L
    var bottom: Long? = null
    for (i in elements.indices.sortedBy { elements[it].yHu }) {
        val el = elements[i]
        val y = el.yHu
        bottom?.let { b -> if (y > top && y + shift < b) shift += b - (y + shift) }
        if (shift != 0L) out[i] = el.at(el.xHu, y + shift)
        if (el is HwpxElement.Table && !floating(el)) { top = y; bottom = y + shift + el.heightHu }
    }
    return out
}
