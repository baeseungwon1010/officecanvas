package io.github.baeseungwon1010.officecanvas.hwpx

import io.github.baeseungwon1010.officecanvas.util.Limits

/**
 * Splits a section's flow of positioned elements into pages, shared by the .hwpx
 * and .hwp parsers.
 *
 * A new page starts where a line's saved vertical position goes back up, or where
 * a paragraph is marked as starting a page. A table that runs past the bottom of
 * the body area is split between rows, as Hangul does: the rows that fit stay,
 * the rest continue at the top of the following pages, and the text after the
 * table goes on the page where it ends (its saved positions continue from there).
 */
internal class PageFlow(private val bodyHeightHu: Long) {

    private companion object {
        /** Room below which a row isn't cut (about two lines of text). */
        const val MIN_SPLIT_HU = 1500L
    }

    val pages = mutableListOf<MutableList<HwpxElement>>(mutableListOf())
    /** Top of the last line placed on the current page. */
    private var lastY = -1L

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

    /** The page being filled (0-based, counting pages that may end up empty). */
    val page: Int get() = pages.size - 1

    /** The non-empty pages, with their indices as [page] counted them. */
    fun finish(): List<IndexedValue<List<HwpxElement>>> {
        settlePending(0)
        return pages.withIndex().filter { it.value.isNotEmpty() }
    }

    private fun newPage() {
        if (full) return
        pages += mutableListOf<HwpxElement>()
        lastY = -1L
    }

    private fun place(el: HwpxElement) {
        if (el !is HwpxElement.Table || bodyHeightHu <= 0 || el.yHu + el.heightHu <= bodyHeightHu * 102 / 100) {
            pages.last() += el
            return
        }
        var rest: HwpxElement.Table = el
        var guard = 0
        while (true) {
            if (rest.yHu + rest.heightHu <= bodyHeightHu * 102 / 100) {
                pages.last() += rest
                return
            }
            var heights = scaledRowHeights(rest)
            val room = bodyHeightHu - rest.yHu
            var fit = 0
            var used = 0L
            while (fit < heights.size && used + heights[fit] <= room) { used += heights[fit]; fit++ }
            // A row taller than a page can't move to the next one whole: it is cut
            // between its lines, as Hangul does, and continues on the next page.
            if (fit < heights.size && heights[fit] > bodyHeightHu * 9 / 10 && room - used >= MIN_SPLIT_HU) {
                val split = splitRow(rest, fit, heights, room - used)
                if (split != null) {
                    rest = split
                    heights = scaledRowHeights(split)
                    used += heights[fit]
                    fit++
                }
            }
            // A table starting at the top of a page keeps at least one row there.
            if (fit == 0 && rest.yHu <= 0) { fit = 1; used = heights[0] }
            if (fit >= heights.size) { pages.last() += rest; return }
            if (fit > 0) pages.last() += rows(rest, 0, fit, rest.yHu, used)
            // The rest continues at the top of the next page, and what follows the
            // table is placed relative to that page.
            if (full || ++guard > Limits.MAX_PAGES) return
            newPage()
            lastY = 0
            rest = rows(rest, fit, heights.size, 0, heights.drop(fit).sum())
        }
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

    private fun heightOf(el: HwpxElement): Long = el.extentHu

    private fun moved(el: HwpxElement, y: Long): HwpxElement = el.at(el.xHu, y)

    /**
     * [t] with row [row] cut in two at [cut] (from the row's top): each cell's
     * lines and objects that end above the cut stay, the rest move into a new row
     * below, starting at the cell's top margin. Spans across the row grow by one.
     * Null when nothing in the row can be cut there.
     */
    private fun splitRow(t: HwpxElement.Table, row: Int, heights: List<Long>, cut: Long): HwpxElement.Table? {
        val cells = t.cells.getOrNull(row) ?: return null
        var moved = false
        var restHeight = heights[row] - cut
        val top = ArrayList<TableCell?>(cells.size)
        val bottom = ArrayList<TableCell?>(cells.size)
        for (cell in cells) {
            if (cell == null) { top += null; bottom += null; continue }
            // Whatever ends above the cut stays; so does a first piece too tall to
            // cut (a nested table), which is drawn clipped rather than moved forever.
            val stay = cell.elements.filter { it.yHu + it.extentHu <= cut }
                .ifEmpty { cell.elements.filter { it.yHu < cut }.take(1) }
            val go = cell.elements.filter { it !in stay }
            val margin = cell.elements.minOfOrNull { it.yHu }?.coerceIn(0, 2000) ?: 0L
            val shift = (go.minOfOrNull { it.yHu } ?: 0L) - margin
            val shifted = go.map { it.at(it.xHu, it.yHu - shift) }
            if (go.isNotEmpty()) moved = true
            restHeight = maxOf(restHeight, (shifted.maxOfOrNull { it.yHu + it.extentHu } ?: 0L) + margin)
            top += cell.copy(rowSpan = 1, elements = stay, vAlign = 0)
            bottom += cell.copy(elements = shifted, vAlign = 0)
        }
        // Only a cut that leaves less to place makes progress.
        if (!moved || restHeight >= heights[row]) return null
        // Cells spanning down across the cut row now span the new row too.
        val before = t.cells.subList(0, row).mapIndexed { i, r ->
            r.map { c -> c?.let { if (i + it.rowSpan > row) it.copy(rowSpan = it.rowSpan + 1) else it } }
        }
        val newHeights = heights.subList(0, row) + listOf(cut, restHeight) + heights.subList(row + 1, heights.size)
        return t.copy(
            cells = before + listOf(top, bottom) + t.cells.subList(row + 1, t.cells.size),
            rowHeightsHu = newHeights,
            heightHu = newHeights.sum()
        )
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
