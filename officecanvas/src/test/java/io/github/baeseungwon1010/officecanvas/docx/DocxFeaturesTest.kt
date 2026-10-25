package io.github.baeseungwon1010.officecanvas.docx

import io.github.baeseungwon1010.officecanvas.hwpx.DrawingKind
import io.github.baeseungwon1010.officecanvas.hwpx.EqNode
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * features.docx is written by tools/make_features_docx.py: floating pictures, a
 * text box (with its VML fallback), a group, an older VML text box, equations,
 * footnotes and an endnote, a title page header, page borders, and a second
 * section that keeps the first's header and restarts its page numbers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DocxFeaturesTest {

    private val file by lazy { File(javaClass.classLoader!!.getResource("docx_samples/features.docx")!!.toURI()) }
    private val doc by lazy { DocxParser.parse(file) }

    /** 1 inch margins on A4, in HWPUNIT. */
    private val left = 7200L
    private val pageWidth = 11906L * 5

    private val first get() = doc.pages[0]
    private fun near(expected: Long, actual: Long, tolerance: Int) =
        assertTrue("$actual, expected $expected", kotlin.math.abs(expected - actual) <= tolerance)
    private fun runs(page: Int) = doc.pages[page].elements.filterIsInstance<HwpxElement.TextRun>()
    private fun run(page: Int, text: String) = runs(page).first { it.text.startsWith(text) }
    private fun texts(els: List<HwpxElement>): List<String> = els.flatMap {
        when (it) {
            is HwpxElement.TextRun -> listOf(it.text)
            is HwpxElement.Drawing -> texts(it.content)
            else -> emptyList()
        }
    }

    @Test
    fun `a picture floating at the page's top right is placed there, out of the text`() {
        val pic = first.elements.filterIsInstance<HwpxElement.ImageEl>().first { it.widthHu == 3 * 360000L / 127 }
        // Right-aligned on the page; 1 cm from its top.
        near(pageWidth - left - pic.widthHu, pic.xHu, 5)
        near(360000L / 127, pic.yHu + first.bodyYHu, 5)
        // The title isn't moved by it.
        assertTrue(run(0, "Feature sample").yHu < 1500)
    }

    @Test
    fun `text goes on below a picture it wraps above and below`() {
        val pic = first.elements.filterIsInstance<HwpxElement.ImageEl>().first { it.widthHu == 4 * 360000L / 127 }
        val after = run(0, "Text below the wrapped picture")
        assertTrue("${after.yHu} vs ${pic.yHu + pic.heightHu}", after.yHu >= pic.yHu + pic.heightHu)
    }

    @Test
    fun `a text box in alternate content is read from the choice, filled, with its text`() {
        val box = first.elements.filterIsInstance<HwpxElement.Drawing>().first { "Text in a text box" in texts(it.content) }
        assertEquals(0xFFDDEBF7.toInt(), box.fillArgb)
        assertEquals(0xFF1F4E79.toInt(), box.lineArgb)
        assertTrue(texts(first.elements).none { it.startsWith("Fallback") })
        assertTrue(run(0, "Text below the text box").yHu >= box.yHu + box.heightHu)
    }

    @Test
    fun `a group's members are scaled into its box`() {
        val group = first.elements.filterIsInstance<HwpxElement.Drawing>().first { g -> g.content.any { it is HwpxElement.Drawing && it.kind == DrawingKind.ELLIPSE } }
        val (circle, square) = group.content.map { it as HwpxElement.Drawing }
        near(group.widthHu / 2, circle.widthHu, 2)
        near(group.widthHu / 2, square.xHu, 2)
        assertEquals(0xFF00B050.toInt(), square.fillArgb)
    }

    @Test
    fun `an older VML text box is drawn with its colors and text`() {
        val box = first.elements.filterIsInstance<HwpxElement.Drawing>().first { "Old VML text box" in texts(it.content) }
        assertEquals(0xFFFFF2CC.toInt(), box.fillArgb)
        assertEquals(17000L, box.widthHu)
        assertTrue(run(0, "Text below the VML box").yHu >= box.yHu + box.heightHu)
    }

    @Test
    fun `equations are typeset from their structure`() {
        val line = run(0, "Inline math")
        val eq = line.spans.mapNotNull { it.inline as? HwpxElement.Equation }.single()
        val parts = (eq.tree as EqNode.Row).items
        assertTrue(parts.first() is EqNode.Fraction)
        assertTrue(parts.any { it is EqNode.Root })
        assertTrue(parts.any { it is EqNode.Scripts && it.sup != null })
        // A fraction stands taller than the text.
        assertTrue(eq.heightHu > line.sizePt * 100)
        // The displayed sum is on its own line, centered.
        val sum = first.elements.filterIsInstance<HwpxElement.Equation>().single()
        val op = ((sum.tree as EqNode.Row).items.first() as EqNode.BigOp)
        assertEquals("∑", op.symbol)
        assertNotNull(op.below); assertNotNull(op.above)
        near((pageWidth - 2 * left - sum.widthHu) / 2, sum.xHu, 20)
    }

    @Test
    fun `footnotes are numbered and set at the bottom of their page`() {
        val line = run(0, "A sentence with a footnote")
        assertEquals(listOf("1", "2"), line.spans.filter { it.style.script == 1 }.map { it.text })
        val notes = runs(0).filter { it.text.contains("footnote.") }
        assertEquals(listOf("1 The first footnote.", "2 The second footnote."), notes.map { it.text })
        // Below everything else in the body, above the bottom margin.
        val bodyHeight = first.heightHu - 7200 - first.bodyYHu
        assertTrue(notes.all { it.yHu > run(0, "An endnote here").yHu && it.yHu + it.heightHu <= bodyHeight + 10 })
    }

    @Test
    fun `the endnote follows the text, numbered in roman`() {
        val line = run(0, "An endnote here")
        assertEquals("i", line.spans.last().text)
        val note = doc.pages.flatMap { it.elements }.filterIsInstance<HwpxElement.TextRun>().first { it.text.endsWith("The endnote.") }
        assertEquals("i The endnote.", note.text)
    }

    @Test
    fun `the title page has its own header, later pages and sections the default`() {
        assertTrue(runs(0).any { it.text == "First page header" })
        assertTrue(runs(0).none { it.text == "Default header" })
        val landscape = doc.pages.last()
        assertTrue(landscape.widthHu > landscape.heightHu)
        assertTrue(runs(doc.pages.size - 1).any { it.text == "Default header" })
        // Numbering restarts at 10 in the second section.
        assertTrue(runs(doc.pages.size - 1).any { it.text == "Page 10" })
        // The title page defines no footer of its own, so it has none.
        assertTrue(runs(0).none { it.text.startsWith("Page") })
    }

    @Test
    fun `page borders are drawn from the page's edge`() {
        val lines = first.elements.filterIsInstance<HwpxElement.Drawing>().filter { it.lineArgb == 0xFF2E75B6.toInt() }
        assertEquals(4, lines.size)
        // 24 pt from the page's left edge.
        assertEquals(2400 - left, lines.minOf { it.xHu })
    }
}
