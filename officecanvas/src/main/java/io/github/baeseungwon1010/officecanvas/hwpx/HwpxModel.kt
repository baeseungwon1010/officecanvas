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

internal data class HwpxDoc(val pages: List<HwpxPage>)

/** Element positions are relative to the body area, as Hangul saves them;
 *  [bodyXHu]/[bodyYHu] is where that area starts on the page. */
internal data class HwpxPage(
    val widthHu: Long,
    val heightHu: Long,
    val elements: List<HwpxElement>,
    val bodyXHu: Long = 0,
    val bodyYHu: Long = 0
)

internal sealed class HwpxElement {
    abstract val xHu: Long
    abstract val yHu: Long

    data class TextRun(
        override val xHu: Long,
        override val yHu: Long,
        val widthHu: Long,
        /** The line's own height (<hp:lineseg>'s textheight), used to size a
         *  table row from its actual content rather than the cell's declared
         *  <hp:cellSz>, which is a minimum/default that real content routinely
         *  exceeds without HWPUNIT updating it. */
        val heightHu: Long,
        val text: String,
        val sizePt: Float,
        val colorArgb: Int,
        val bold: Boolean,
        val italic: Boolean,
        val underline: Boolean
    ) : HwpxElement()

    data class Table(
        override val xHu: Long,
        override val yHu: Long,
        val widthHu: Long,
        val heightHu: Long,
        val columnWidthsHu: List<Long>,
        val rowHeightsHu: List<Long>,
        /** Row-major; a cell covered by another cell's span is null. */
        val cells: List<List<TableCell?>>
    ) : HwpxElement()

    data class ImageEl(
        override val xHu: Long,
        override val yHu: Long,
        val widthHu: Long,
        val heightHu: Long,
        /** Entry name inside the .hwpx zip, e.g. "BinData/image1.png". */
        val zipEntry: String
    ) : HwpxElement()
}

internal data class TableCell(
    val colSpan: Int,
    val rowSpan: Int,
    /** Text runs positioned relative to the cell's own top-left corner. */
    val runs: List<HwpxElement.TextRun>
)
