package io.github.baeseungwon1010.officecanvas.convert

import io.github.baeseungwon1010.officecanvas.docx.DocxParser
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxElement
import java.io.File
import kotlin.math.max

/**
 * Keeping each PDF page to one page. The document is laid out as a word processor
 * would, with this library's own layout of the Word file, and the PDF pages whose
 * text runs onto another page (set in fonts wider than the PDF's, or wrapping
 * sooner) are found and set tighter.
 */
internal object PdfFit {

    private val MARK = Regex("§PG(\\d+)§")

    /**
     * [page] with a mark of its index at its top, for [spilling] to find, and its bottom margin
     * 3 % of the page higher: other fonts than the ones measured with may set the text a little longer.
     */
    fun marked(page: ConvPage, index: Int): ConvPage = ConvPage(
        page.width, page.height, page.marginLeft, page.marginRight, page.marginTop, page.marginBottom + page.height * 0.03,
        listOf(ConvParagraph(listOf(ConvRun("§PG$index§", "", 1.0)), lineHeight = 0.5)) + page.blocks,
        page.floating, page.header, page.headerTop, page.footer, page.footerBottom
    )

    /**
     * The indexes of the pages [write] writes (each [marked]) whose content runs onto a
     * page after their own, as a Word file laid out here; empty if it can't be laid out
     * (no Android graphics to measure text with).
     */
    fun spilling(font: String, write: (PageSink) -> Unit): Set<Int> {
        val tmp = File.createTempFile("officecanvas-fit", ".docx")
        try {
            tmp.outputStream().buffered().use { write(DocxWriter(it, font)) }
            val doc = DocxParser.parse(tmp)
            try {
                val out = HashSet<Int>()
                var current = -1
                for (p in doc.pages) {
                    val mark = texts(p.elements).firstNotNullOfOrNull { MARK.find(it) }?.groupValues?.get(1)?.toIntOrNull()
                    if (mark != null) current = mark else if (current >= 0) out += current
                }
                return out
            } finally {
                doc.resources?.close()
            }
        } catch (e: Exception) {
            return emptySet()
        } catch (e: LinkageError) {
            return emptySet()
        } finally {
            tmp.delete()
        }
    }

    private fun texts(els: List<HwpxElement>): List<String> = els.flatMap {
        when (it) {
            is HwpxElement.TextRun -> listOf(it.text)
            is HwpxElement.Table -> it.cells.flatten().filterNotNull().flatMap { c -> texts(c.elements) }
            is HwpxElement.Drawing -> texts(it.content)
            else -> emptyList()
        }
    }

    /**
     * [page] with its blocks set where they lie on the PDF's page; tables without a line or a fill
     * (columns side by side, labels around a drawing) give up their parts, each set in its place.
     */
    fun fixed(page: ConvPage): ConvPage = ConvPage(
        page.width, page.height, page.marginLeft, page.marginRight, page.marginTop, page.marginBottom,
        page.blocks.flatMap { unwrap(it) }, page.floating, page.header, page.headerTop, page.footer, page.footerBottom, fixed = true
    )

    private fun unwrap(b: ConvBlock): List<ConvBlock> =
        if (b is ConvTable && b.cells.all { c -> c.top == null && c.bottom == null && c.left == null && c.right == null && c.fill == null } &&
            b.cells.all { c -> c.blocks.all { it.at != null } }) b.cells.flatMap { c -> c.blocks.flatMap { unwrap(it) } }
        else listOf(b)

    /** Steps: lines, space and rows this much as tall, and text this many percent as wide. */
    private val HEIGHT = doubleArrayOf(0.9, 0.8, 0.72)
    private val WIDTH = intArrayOf(100, 95, 90)
    val STEPS = HEIGHT.size

    /**
     * [page] set tighter by [step] (1 to [STEPS]): lines, the space between paragraphs and
     * table rows shorter (lines no shorter than their text), and from the second step the
     * text narrower, so fewer lines wrap.
     */
    fun tighten(page: ConvPage, step: Int): ConvPage {
        val k = step.coerceIn(1, STEPS) - 1
        return ConvPage(
            page.width, page.height, page.marginLeft, page.marginRight, page.marginTop, page.marginBottom,
            page.blocks.map { tighten(it, HEIGHT[k], WIDTH[k]) }, page.floating, page.header, page.headerTop, page.footer, page.footerBottom
        )
    }

    private fun tighten(b: ConvBlock, f: Double, width: Int): ConvBlock = when (b) {
        is ConvParagraph -> {
            val size = b.runs.maxOfOrNull { it.size } ?: 0.0
            val runs = if (width == 100) b.runs else b.runs.map { r ->
                ConvRun(r.text, r.font, r.size, r.bold, r.italic, r.underline, r.color, r.script, r.field, r.spacing, max(50, r.scale * width / 100), r.mono, r.serif)
            }
            ConvParagraph(runs, b.align, b.leftIndent, b.rightIndent, b.firstLine, b.spaceBefore * f,
                b.lineHeight?.let { max(it * f, size * 1.05) }, b.image, b.tabs, b.shading, b.rightTabs).also { it.at = b.at }
        }
        is ConvTable -> ConvTable(b.x, b.colWidths, b.rowHeights.map { it * f },
            b.cells.map { c -> ConvCell(c.row, c.col, c.rowSpan, c.colSpan, c.blocks.map { tighten(it, f, width) }, c.fill, c.top, c.bottom, c.left, c.right, c.vAlign) },
            b.spaceBefore * f).also { it.at = b.at }
    }
}
