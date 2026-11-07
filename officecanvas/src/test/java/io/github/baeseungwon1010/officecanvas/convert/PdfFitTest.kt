package io.github.baeseungwon1010.officecanvas.convert

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pages whose text runs onto the next are found, and setting them tighter keeps them to one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfFitTest {

    /** A Letter page with 36 pt margins (720 pt for text) holding [lines] lines 14 pt apart. */
    private fun page(lines: Int) = ConvPage(612.0, 792.0, 36.0, 36.0, 36.0, 36.0,
        List(lines) { ConvParagraph(listOf(ConvRun("Line $it of the page", "Arial", 11.0)), lineHeight = 14.0) })

    private fun spilling(pages: List<ConvPage>) = PdfFit.spilling("Arial") { sink ->
        pages.forEachIndexed { i, p -> sink.page(PdfFit.marked(p, i)) }
        sink.finish()
    }

    @Test
    fun `a page that runs over is found, and fits once set tighter`() {
        // 55 lines need 770 pt; 45 need 630.
        val pages = listOf(page(45), page(55), page(45))
        assertEquals(setOf(1), spilling(pages))
        // A tenth shorter, 693 pt.
        assertEquals(emptySet<Int>(), spilling(listOf(pages[0], PdfFit.tighten(pages[1], 1), pages[2])))
    }
}
