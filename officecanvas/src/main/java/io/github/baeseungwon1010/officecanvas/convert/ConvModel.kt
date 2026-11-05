package io.github.baeseungwon1010.officecanvas.convert

/**
 * A document rebuilt from a PDF's pages, for the writers (DOCX, HWPX, HWP):
 * paragraphs of styled runs, tables and pictures in reading order, page by page.
 * Lengths are in points; x positions are from the page's left margin.
 */
internal class ConvDocument(val pages: List<ConvPage>)

internal class ConvPage(
    val width: Double, val height: Double,
    val marginLeft: Double, val marginRight: Double, val marginTop: Double, val marginBottom: Double,
    val blocks: List<ConvBlock>,
    /** Pictures behind the text (backgrounds, ones the text runs over), placed from the page's top left. */
    val floating: List<ConvImage> = emptyList(),
    /** Text repeated at the top and bottom of the pages, and how far it is from the page's edge. */
    val header: List<ConvParagraph> = emptyList(), val headerTop: Double = 0.0,
    val footer: List<ConvParagraph> = emptyList(), val footerBottom: Double = 0.0
)

internal enum class ConvAlign { LEFT, CENTER, RIGHT, JUSTIFY }

internal sealed class ConvBlock

internal class ConvParagraph(
    val runs: List<ConvRun>,
    val align: ConvAlign = ConvAlign.LEFT,
    /** From the column's left edge, the lines after the first. */
    val leftIndent: Double = 0.0,
    val rightIndent: Double = 0.0,
    /** The first line's start relative to [leftIndent] (negative: it hangs to the left). */
    val firstLine: Double = 0.0,
    val spaceBefore: Double = 0.0,
    /** Baseline to baseline, set exactly (each line's box; the baseline at 80 % of it); null: single spacing. */
    val lineHeight: Double? = null,
    /** A picture standing alone in the paragraph, in place of text. */
    val image: ConvImage? = null,
    /** Left tab stops, from the column's left edge, for the tabs in [runs]. */
    val tabs: List<Double> = emptyList(),
    /** A color filling the paragraph's box (a bar behind a heading, a band behind a formula). */
    val shading: Int? = null
) : ConvBlock() {
    val text: String get() = runs.joinToString("") { it.text }
}

internal class ConvRun(
    val text: String,
    val font: String,
    val size: Double,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val color: Int = 0xFF000000.toInt(),
    /** 1 superscript, -1 subscript. */
    val script: Int = 0,
    /** A field standing for [text] ("PAGE": the page number). */
    val field: String? = null
)

internal class ConvBorder(val width: Double, val color: Int)

internal class ConvCell(
    val row: Int, val col: Int, val rowSpan: Int, val colSpan: Int,
    val blocks: List<ConvBlock>,
    val fill: Int? = null,
    val top: ConvBorder? = null, val bottom: ConvBorder? = null, val left: ConvBorder? = null, val right: ConvBorder? = null,
    /** 0 top, 1 middle, 2 bottom. */
    val vAlign: Int = 0
)

internal class ConvTable(
    /** From the column's left edge. */
    val x: Double,
    val colWidths: List<Double>,
    val rowHeights: List<Double>,
    val cells: List<ConvCell>,
    val spaceBefore: Double = 0.0
) : ConvBlock()

/** A picture: PNG or JPEG data, its size on the page, and where it sits. */
internal class ConvImage(
    val data: ByteArray, val png: Boolean, val pixelWidth: Int, val pixelHeight: Int,
    val width: Double, val height: Double,
    /** From the page's top left (floating) or the column's left edge (in the flow). */
    val x: Double, val y: Double
)
