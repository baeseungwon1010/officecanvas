package io.github.baeseungwon1010.officecanvas.hwpx

import io.github.baeseungwon1010.officecanvas.util.Limits

/**
 * Splits a section's flow of positioned elements into pages, shared by the .hwpx
 * and .hwp parsers.
 *
 * A new page starts where a line's saved vertical position goes back up, or where
 * a paragraph is marked as starting a page. A table that runs past the bottom of
 * the body area is split between rows, as Hangul does: the rows that fit stay,
 * and the rest open the next page at its top, where Hangul placed the following
 * text below them.
 */
internal class PageFlow(private val bodyHeightHu: Long) {

    val pages = mutableListOf<MutableList<HwpxElement>>(mutableListOf())
    /** Top of the last line placed on the current page. */
    private var lastY = -1L
    /** What moves to the top of the next page: the rest of a split table. */
    private val carry = mutableListOf<HwpxElement>()

    /**
     * Floating objects that run past the bottom of their page, with the page they
     * were anchored on. Hangul either moves such an object to the top of the next
     * page, and then starts that page's text below it, or leaves it hanging off the
     * bottom; the next page's first line position tells which.
     */
    private class Pending(val original: HwpxElement, val page: Int)
    private val pending = mutableListOf<Pending>()

    val full: Boolean get() = pages.size >= Limits.MAX_PAGES

    /**
     * [startsLine]: [el] is the first piece of a line (later pieces of the same line
     * share its position). Two lines can't start at the same height on one page,
     * so a line starting at or above the previous one opens a new page.
     * [floating]: an object placed by offsets rather than in a line; it never
     * starts a page.
     */
    fun add(el: HwpxElement, startsPage: Boolean = false, startsLine: Boolean = true, floating: Boolean = false) {
        if (!floating) {
            val wrapped = if (startsLine) el.yHu <= lastY else el.yHu < lastY
            if (wrapped || (startsPage && pages.last().isNotEmpty())) newPage()
            lastY = el.yHu
            settlePending(el.yHu)
        }
        if (floating && bodyHeightHu > 0 && el.yHu + heightOf(el) > bodyHeightHu * 102 / 100 && heightOf(el) <= bodyHeightHu) {
            pending += Pending(el, pages.size - 1)
            return
        }
        place(el)
    }

    /** Pages left over from a split table at the end of the section. */
    fun finish(): List<List<HwpxElement>> {
        settlePending(0)
        while (carry.isNotEmpty() && !full) newPage()
        return pages.filter { it.isNotEmpty() }
    }

    private fun newPage() {
        if (full) return
        pages += mutableListOf<HwpxElement>()
        lastY = -1L
        val moving = carry.toList()
        carry.clear()
        moving.forEach { place(it) }
    }

    private fun place(el: HwpxElement) {
        if (el !is HwpxElement.Table || bodyHeightHu <= 0 || el.yHu + el.heightHu <= bodyHeightHu * 102 / 100) {
            pages.last() += el
            return
        }
        val heights = scaledRowHeights(el)
        val room = bodyHeightHu - el.yHu
        var fit = 0
        var used = 0L
        while (fit < heights.size && used + heights[fit] <= room) { used += heights[fit]; fit++ }
        // A table starting at the top of a page keeps at least one row there.
        if (fit == 0 && el.yHu <= 0) { fit = 1; used = heights[0] }
        if (fit == 0) {
            carry += el.copy(yHu = 0)
            return
        }
        if (fit >= heights.size) { pages.last() += el; return }
        pages.last() += rows(el, 0, fit, el.yHu, used)
        carry += rows(el, fit, heights.size, 0, heights.drop(fit).sum())
    }

    /** Decides the pending objects once a line on a later page is known. */
    private fun settlePending(firstLineY: Long) {
        if (pending.isEmpty()) return
        val later = pending.filter { it.page < pages.size - 1 }
        if (later.isEmpty() && firstLineY != 0L) return
        val room = later.maxOfOrNull { heightOf(it.original) } ?: 0L
        for (p in later.ifEmpty { pending.toList() }) {
            if (later.isNotEmpty() && firstLineY >= room * 9 / 10) pages.last() += moved(p.original, 0)
            else pages[p.page] += p.original
            pending.remove(p)
        }
    }

    private fun heightOf(el: HwpxElement): Long = when (el) {
        is HwpxElement.TextRun -> el.heightHu
        is HwpxElement.Table -> el.heightHu
        is HwpxElement.ImageEl -> el.heightHu
    }

    private fun moved(el: HwpxElement, y: Long): HwpxElement = when (el) {
        is HwpxElement.TextRun -> el.copy(yHu = y)
        is HwpxElement.Table -> el.copy(yHu = y)
        is HwpxElement.ImageEl -> el.copy(yHu = y)
    }

    private fun scaledRowHeights(t: HwpxElement.Table): List<Long> {
        val total = t.rowHeightsHu.sum().coerceAtLeast(1)
        return t.rowHeightsHu.map { it * t.heightHu / total }
    }

    /** Rows [from, to) as a table of their own; spans crossing the cut are cut too. */
    private fun rows(t: HwpxElement.Table, from: Int, to: Int, y: Long, height: Long): HwpxElement.Table {
        val cells = t.cells.subList(from, to).mapIndexed { r, row ->
            row.map { cell -> cell?.let { if (r + it.rowSpan > to - from) it.copy(rowSpan = to - from - r) else it } }
        }
        return t.copy(yHu = y, heightHu = height, rowHeightsHu = scaledRowHeights(t).subList(from, to), cells = cells)
    }
}
