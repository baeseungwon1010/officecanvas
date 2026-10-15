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
}
