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
}
