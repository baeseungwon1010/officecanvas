package io.github.baeseungwon1010.officecanvas.hwpx

/** Stands for the current page number inside header and footer text. */
internal const val PAGE_NUMBER_MARK = ''

/** A header's or footer's content, in its own area's coordinates, and its
 *  vertical alignment there (0 top, 1 middle, 2 bottom). */
internal class DecorArea(val content: List<HwpxElement>, val vAlign: Int = 0)

/**
 * What a section repeats on its pages: header and footer content (each possibly
 * different on odd and even pages) and the page number, shared by the .hwpx and
 * .hwp parsers.
 *
 * Header and footer lines are saved relative to their own area (the header area
 * sits between the top margin and the body, the footer area between the body and
 * the bottom margin) and are moved into body coordinates here.
 */
internal data class PageDecor(
    val oddHeader: DecorArea? = null,
    val evenHeader: DecorArea? = null,
    val oddFooter: DecorArea? = null,
    val evenFooter: DecorArea? = null,
    /** 1-3 top left/center/right, 4-6 bottom left/center/right; null for none. */
    val pageNumberPosition: Int? = null,
    /** Drawn on both sides of the page number ("- 3 -"), if any. */
    val pageNumberSide: String = ""
) {
    /** [pages]: 0 both, 1 even, 2 odd, as both formats number them. */
    fun withHeader(area: DecorArea, pages: Int) =
        copy(oddHeader = if (pages != 1) area else oddHeader, evenHeader = if (pages != 2) area else evenHeader)

    fun withFooter(area: DecorArea, pages: Int) =
        copy(oddFooter = if (pages != 1) area else oddFooter, evenFooter = if (pages != 2) area else evenFooter)

    /** The decoration for page [number] (1-based) of a page with [g]'s geometry,
     *  less what [hiding] hides on that page. */
    fun elements(number: Int, g: Geometry, hiding: PageHiding? = null): List<HwpxElement> {
        val out = mutableListOf<HwpxElement>()
        val header = (if (number % 2 == 0) evenHeader else oddHeader)?.takeUnless { hiding?.header == true }
        val footer = (if (number % 2 == 0) evenFooter else oddFooter)?.takeUnless { hiding?.footer == true }
        if (header != null) {
            val dy = -g.headerHu + offset(header, g.headerHu)
            header.content.forEach { out += it.shiftedBy(0, dy).withPageNumber(number) }
        }
        val footerTop = g.heightHu - g.bottomHu - g.footerHu - g.bodyYHu
        if (footer != null) {
            val dy = footerTop + offset(footer, g.footerHu)
            footer.content.forEach { out += it.shiftedBy(0, dy).withPageNumber(number) }
        }
        val pos = pageNumberPosition?.takeUnless { hiding?.pageNumber == true }
        if (pos != null && pos in 1..6) {
            val width = g.widthHu - g.bodyXHu - g.rightHu
            val y = if (pos <= 3) -g.headerHu / 2 - 500 else footerTop + g.footerHu / 2 - 500
            val align = when ((pos - 1) % 3) { 0 -> ParaAlign.LEFT; 1 -> ParaAlign.CENTER; else -> ParaAlign.RIGHT }
            val side = pageNumberSide
            val text = if (side.isBlank()) "$number" else "$side $number $side"
            out += HwpxElement.TextRun(0, y, width, 1000, listOf(Span(text, CharStyle())), 850, align)
        }
        return out
    }

    /** Page geometry in HWPUNIT: page size, body origin, and the margins and
     *  header/footer areas around the body. */
    data class Geometry(
        val widthHu: Long, val heightHu: Long, val bodyXHu: Long, val bodyYHu: Long,
        val rightHu: Long, val bottomHu: Long, val headerHu: Long, val footerHu: Long
    )

    private fun offset(area: DecorArea, areaHu: Long): Long {
        if (area.vAlign == 0 || area.content.isEmpty()) return 0
        val bottom = area.content.maxOf { it.yHu + it.extentHu }
        val room = (areaHu - bottom).coerceAtLeast(0)
        return if (area.vAlign == 1) room / 2 else room
    }

    companion object {
        val NONE = PageDecor()
    }
}

/**
 * Header, footer and page number controls take effect from the page their
 * paragraph is on, replacing earlier ones (for the pages they apply to).
 */
internal class DecorTimeline {
    private val changes = mutableListOf<Pair<Int, PageDecor>>()
    private var current = PageDecor.NONE
    private val hidden = HashMap<Int, PageHiding>()
    private val restarts = HashMap<Int, Int>()

    fun change(page: Int, f: (PageDecor) -> PageDecor) {
        current = f(current)
        changes += page to current
    }

    fun at(page: Int): PageDecor = changes.lastOrNull { it.first <= page }?.second ?: PageDecor.NONE

    /** Hides parts of the decoration on [page] only (a cover page, say). */
    fun hide(page: Int, h: PageHiding) {
        hidden[page] = hidden[page]?.let { PageHiding(it.header || h.header, it.footer || h.footer, it.pageNumber || h.pageNumber) } ?: h
    }

    fun hidingAt(page: Int): PageHiding? = hidden[page]

    /** Page numbers start over at [number] from [page]. */
    fun restart(page: Int, number: Int) { restarts[page] = number }

    /** The number of [page], one past [previous] unless numbering starts over there. */
    fun number(page: Int, previous: Int): Int = restarts[page] ?: (previous + 1)
}

/** What a page hides of its section's decoration. */
internal class PageHiding(val header: Boolean, val footer: Boolean, val pageNumber: Boolean)

/**
 * Where an object placed relative to its paragraph goes, given the line it is
 * anchored on ([lineY], [lineHeight]; [textFirstLine]: the first line, holding
 * text, of a header, footer or text box). Shared by the .hwpx and .hwp parsers.
 *
 * Saved lines are already laid out around such objects, but a paragraph's own
 * top isn't saved. At the top of a list it is the list's top, so an object there
 * that takes its own lines ([takesLines]) and starts within the first line of
 * text pushed that line below itself: it ends where the line starts (a band
 * drawn above a footer, say). Elsewhere the paragraph may start well above the
 * line and the object stays at its offset from the line. (A paragraph holding
 * only the object keeps its line at the object.) One that also moves with the text ([keepsBelowAnchor])
 * can't sit above its paragraph; a negative offset saved for it is left from
 * before the text moved (the next paragraph starts right below it).
 */
internal fun paragraphRelativeY(
    offset: Long, takesLines: Boolean, keepsBelowAnchor: Boolean, height: Long,
    lineY: Long, textFirstLine: Boolean, lineHeight: Long
): Long = when {
    takesLines && textFirstLine && offset in 0 until lineHeight && lineY - height - offset >= 0 -> lineY - height
    keepsBelowAnchor -> maxOf(lineY, lineY + offset)
    else -> lineY + offset
}

internal fun HwpxElement.shiftedBy(dx: Long, dy: Long): HwpxElement = at(xHu + dx, yHu + dy)

/** Replaces page number marks in a line (or a table's lines) with [number]. */
internal fun HwpxElement.withPageNumber(number: Int): HwpxElement = when (this) {
    is HwpxElement.TextRun ->
        if (spans.none { PAGE_NUMBER_MARK in it.text }) this
        else copy(spans = spans.map { it.copy(text = it.text.replace(PAGE_NUMBER_MARK.toString(), number.toString())) })
    is HwpxElement.Table -> copy(cells = cells.map { row -> row.map { c -> c?.copy(elements = c.elements.map { it.withPageNumber(number) }) } })
    is HwpxElement.Drawing -> copy(content = content.map { it.withPageNumber(number) })
    else -> this
}
