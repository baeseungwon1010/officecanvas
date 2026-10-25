package io.github.baeseungwon1010.officecanvas.hwpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageFlowTest {

    private fun line(y: Long, text: String) = HwpxElement.TextRun(0, y, 40000, 1000, listOf(Span(text, CharStyle())), 850)

    @Test
    fun `a table row taller than a page is cut between its lines and continues`() {
        // One row holding 200 lines, 1600 apart: over four pages of 70000.
        val lines = (0 until 200).map { line(141 + it * 1600L, "line $it") }
        val row = listOf(TableCell(1, 1, lines))
        val table = HwpxElement.Table(0, 0, 40000, 2000, listOf(40000), listOf(2000), listOf(row))
            .let { it.copy(rowHeightsHu = listOf(lines.last().yHu + 1141), heightHu = lines.last().yHu + 1141) }
        val flow = PageFlow(70000)
        flow.add(table)
        val pages = flow.finish().map { it.value }
        assertTrue("${pages.size} pages", pages.size in 4..6)
        // Every line shows up exactly once, inside its page's body.
        val shown = pages.flatMap { p ->
            p.filterIsInstance<HwpxElement.Table>().flatMap { t ->
                assertTrue("piece ${t.yHu}+${t.heightHu}", t.yHu + t.heightHu <= 70000 * 102 / 100)
                t.cells.flatten().filterNotNull().flatMap { it.runs }.onEach { r ->
                    assertTrue("line at ${r.yHu} in a ${t.heightHu} piece", r.yHu + r.heightHu <= t.heightHu)
                }
            }
        }.map { it.text }
        assertEquals(lines.map { it.text }, shown)
    }

    private fun table(y: Long, height: Long) =
        HwpxElement.Table(0, y, 40000, height, listOf(40000), listOf(height), listOf(listOf(TableCell(1, 1, emptyList()))))

    private fun texts(page: List<HwpxElement>) = page.filterIsInstance<HwpxElement.TextRun>().map { it.text to it.yHu }

    @Test
    fun `text saved where a grown table now is goes below it`() {
        val flow = PageFlow(70000)
        flow.add(line(0, "title"))
        flow.add(table(2000, 30000))          // saved as 10000 tall
        flow.add(line(12000, "after"))
        flow.add(line(13600, "next"))
        assertEquals(listOf("title" to 0L, "after" to 32000L, "next" to 33600L), texts(flow.finish().single().value))
    }

    @Test
    fun `text saved clear of a table stays where it was`() {
        val flow = PageFlow(70000)
        flow.add(table(2000, 10000))
        flow.add(line(13000, "after"))
        assertEquals(listOf("after" to 13000L), texts(flow.finish().single().value))
    }

    @Test
    fun `a line saved inside an object text wraps around goes below it, and past the page onto the next`() {
        val flow = PageFlow(70000)
        flow.add(line(0, "first"))
        flow.add(table(1600, 60000), floating = true, takesLines = true)
        flow.add(line(20000, "below"))   // inside the object: moves to 61600
        flow.add(line(30000, "later"))   // 71600 is past the page: next page
        flow.add(line(31600, "last"))
        val pages = flow.finish().map { it.value }
        assertEquals(listOf("first" to 0L, "below" to 61600L), texts(pages[0]))
        assertEquals(listOf("later" to 0L, "last" to 1600L), texts(pages[1]))
    }

    @Test
    fun `a line just inside the top of a wrapped object stays`() {
        val flow = PageFlow(70000)
        flow.add(table(5000, 20000), floating = true, takesLines = true)
        flow.add(line(5400, "beside"))
        assertEquals(listOf("beside" to 5400L), texts(flow.finish().single().value))
    }

    @Test
    fun `a blank paragraph saved higher than the line before starts a page`() {
        val flow = PageFlow(70000)
        flow.add(line(60000, "end of page one"))
        flow.skipLine(2000, startsPage = false)
        flow.add(line(3600, "page two"))
        val pages = flow.finish().map { it.value }
        assertEquals(listOf(listOf("end of page one" to 60000L), listOf("page two" to 3600L)), pages.map { texts(it) })
    }

    @Test
    fun `header rows repeat at the top of each page a table runs onto`() {
        // A header row and 9 rows of 20000: about three pages of 70000.
        val rows = (0 until 10).map { r -> listOf(TableCell(1, 1, listOf(line(100, "row $r")))) }
        val heights = listOf(2000L) + List(9) { 20000L }
        val table = HwpxElement.Table(0, 0, 40000, heights.sum(), listOf(40000), heights, rows, headerRows = 1)
        val flow = PageFlow(70000)
        flow.add(table, laidOut = true)
        val pages = flow.finish().map { it.value.filterIsInstance<HwpxElement.Table>().single() }
        assertTrue("${pages.size} pages", pages.size >= 3)
        for (piece in pages) assertEquals("row 0", piece.cells[0][0]!!.runs.single().text)
        // Every other row shows up once.
        assertEquals((1 until 10).map { "row $it" }, pages.flatMap { p -> p.cells.drop(1).map { it[0]!!.runs.single().text } })
    }
}
