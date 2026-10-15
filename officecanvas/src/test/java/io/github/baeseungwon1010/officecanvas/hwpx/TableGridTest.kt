package io.github.baeseungwon1010.officecanvas.hwpx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TableGridTest {

    @Test
    fun `a column only spanned cells cover takes its width from where they end`() {
        // Row 0: [0..2) 3000 | [2] 1000. Row 1: [0] 1000 | [1..3) 3000.
        // Column 1 is never on its own; its edges come from both rows.
        val cells = listOf(GridSpan(0, 2, 3000), GridSpan(2, 1, 1000), GridSpan(0, 1, 1000), GridSpan(1, 2, 3000))
        assertEquals(listOf(1000L, 2000L, 1000L), gridSizes(cells, 3, 4000))
    }

    @Test
    fun `a line no cell places is spread evenly between its neighbours`() {
        assertEquals(listOf(1500L, 1500L, 1000L), gridSizes(listOf(GridSpan(0, 2, 3000), GridSpan(2, 1, 1000)), 3, 4000))
    }

    @Test
    fun `cells that don't add up give no grid`() {
        assertNull(gridSizes(listOf(GridSpan(0, 1, 3000), GridSpan(0, 2, 2000)), 2, 3000))
    }

    private fun line(y: Long, text: String) = HwpxElement.TextRun(0, y, 10000, 1000, listOf(Span(text, CharStyle())), 850)
    private fun table(y: Long, height: Long) =
        HwpxElement.Table(0, y, 10000, height, listOf(10000), listOf(height), listOf(listOf(TableCell(1, 1, emptyList()))))

    @Test
    fun `what follows a table that grew past its saved room moves below it, keeping its spacing`() {
        val els = listOf(line(0, "before"), table(1600, 30000), line(3200, "after"), line(4800, "next"))
        val out = clearOfTables(els) { false }
        assertEquals(listOf(0L, 1600L, 31600L, 33200L), out.map { it.yHu })
    }

    @Test
    fun `content saved clear of a table, and floating tables, stay put`() {
        val els = listOf(table(0, 1000), line(1600, "after"))
        assertEquals(listOf(0L, 1600L), clearOfTables(els) { false }.map { it.yHu })
        val floating = listOf(table(0, 30000), line(1600, "beside"))
        assertEquals(listOf(0L, 1600L), clearOfTables(floating) { it is HwpxElement.Table }.map { it.yHu })
    }
}
