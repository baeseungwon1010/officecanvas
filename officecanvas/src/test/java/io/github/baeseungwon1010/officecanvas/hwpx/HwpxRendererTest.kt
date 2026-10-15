package io.github.baeseungwon1010.officecanvas.hwpx

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** See SlideRendererTest for why @GraphicsMode(NATIVE) is required here: the
 *  default LEGACY mode records draw calls without rasterizing real pixels. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HwpxRendererTest {

    private fun sample(name: String): File {
        val url = javaClass.classLoader?.getResource("hwpx_samples/$name")
            ?: throw AssertionError("test resource missing: hwpx_samples/$name")
        return File(url.toURI())
    }

    private fun allSamples(): List<File> {
        val dir = sample("sample.hwpx").parentFile!!
        return dir.listFiles { f -> f.extension == "hwpx" }!!.toList()
    }

    private val outDir = File("build/hwpx_render_check").apply { mkdirs() }

    @Test
    fun `renders every sample document to non-blank bitmaps`() {
        HwpxRenderer.DEBUG_LOG_DRAW_ERRORS = true
        for (file in allSamples()) {
            val doc = HwpxParser.parse(file)
            val bitmaps = mutableListOf<Bitmap>()
            HwpxRenderer.render(doc, file, targetLongSidePx = 800) { _, bmp -> bitmaps += bmp }
            org.junit.Assert.assertEquals("${file.name}: bitmap count should match page count", doc.pages.size, bitmaps.size)

            for ((i, bmp) in bitmaps.withIndex()) {
                val distinctColors = sampleDistinctColors(bmp)
                if (doc.pages[i].elements.isNotEmpty()) {
                    org.junit.Assert.assertTrue(
                        "${file.name} page $i: has ${doc.pages[i].elements.size} elements but rendered as a flat single colour",
                        distinctColors > 1
                    )
                }
                savePng(bmp, "${file.nameWithoutExtension}_page${i + 1}.png")
            }
        }
        println("Rendered PNGs written to: ${outDir.absolutePath}")
    }

    // sample.hwpx (tools/make_sample_hwpx.py) carries a header with the page
    // number field, a page number control, a floating text box, an inline line
    // and a cropped picture.
    private val doc by lazy { HwpxParser.parse(sample("sample.hwpx")) }

    private fun runs(page: Int) = doc.pages[page].elements.filterIsInstance<HwpxElement.TextRun>()

    @Test
    fun `header and page number repeat on every page`() {
        // Numbers start over on the last page, and the first page hides its
        // page number (but not the number in its header).
        val numbers = listOf(1, 2, 1)
        for (i in doc.pages.indices) {
            val r = runs(i)
            val n = numbers[i]
            assertTrue("header on page ${i + 1}", r.any { it.text == "OfficeCanvas 샘플\t쪽 $n" && it.yHu < 0 })
            assertEquals("page number on page ${i + 1}", i != 0, r.any { it.text == "- $n -" && it.align == ParaAlign.CENTER })
        }
    }

    @Test
    fun `a tab keeps the width Hangul saved for it and moves the text after it`() {
        val header = runs(0).first { it.text.startsWith("OfficeCanvas") }
        val tab = header.spans.single { it.text == "\t" }
        assertEquals(20000L, tab.tabHu)
        // The text after the tab starts at least the tab's width past the text before it.
        val bitmap = render(0, 2000)
        val page = doc.pages[0]
        val scale = bitmap.height.toDouble() / page.heightHu
        val row = ((page.bodyYHu + header.yHu + header.baselineHu - 200) * scale).toInt()
        val inked = (0 until bitmap.width).filter { x -> bitmap.getPixel(x, row) and 0xFF < 128 }
        val gaps = inked.zipWithNext().map { (a, b) -> b - a }
        assertTrue("a gap of at least the tab's width: ${gaps.maxOrNull()}", (gaps.maxOrNull() ?: 0) >= (20000 * 0.8 * scale).toInt())
    }

    @Test
    fun `numbered paragraphs and bullets get the marks Hangul would draw`() {
        val heads = doc.pages.flatMap { it.elements }.filterIsInstance<HwpxElement.TextRun>()
            .filter { it.text.endsWith("항목") }.map { it.spans.first().text to it.spans.drop(2).joinToString("") { s -> s.text } }
        // Level 1 counts in digits, level 2 in Hangul syllables and restarts under
        // each level-1 paragraph; the bullet is Wingdings' small bullet.
        assertEquals(listOf("1." to "첫째 항목", "가." to "세부 항목", "2." to "둘째 항목", "•" to "점 항목"), heads)
        // The text after the mark is set off by half the text size.
        val first = doc.pages.flatMap { it.elements }.filterIsInstance<HwpxElement.TextRun>().first { it.text.endsWith("첫째 항목") }
        assertEquals((first.spans[0].style.sizePt * 50).toLong(), first.spans[1].tabHu)
    }

    @Test
    fun `an equation in a line of text is part of the line`() {
        val run = doc.pages.flatMap { it.elements }.filterIsInstance<HwpxElement.TextRun>()
            .single { r -> r.spans.any { it.inline is HwpxElement.Equation } }
        assertEquals("사다리꼴의 넓이는", run.spans.first().text.trim())
        val eq = run.spans.last().inline as HwpxElement.Equation
        assertEquals("{a+b} over {2} TIMES h", eq.script)
        assertEquals(6000L, run.spans.last().tabHu)
        assertEquals(70, eq.baselinePercent)
    }

    @Test
    fun `equations are typeset with fractions above and below the axis`() {
        val layout = EquationLayout(0xFF000000.toInt())
        val plain = layout.layout(EquationParser.parse("a+b"), 100f)
        val frac = layout.layout(EquationParser.parse("{a+b} over {2}"), 100f)
        assertTrue("taller above: ${frac.ascent} vs ${plain.ascent}", frac.ascent > plain.ascent * 1.3f)
        assertTrue("deeper below: ${frac.descent} vs ${plain.descent}", frac.descent > plain.descent * 1.5f)
        val bitmap = Bitmap.createBitmap(frac.width.toInt() + 10, frac.height.toInt() + 10, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xFFFFFFFF.toInt())
        frac.draw(android.graphics.Canvas(bitmap), 5f, 5f + frac.ascent)
        val inked = (0 until bitmap.height).count { y -> (0 until bitmap.width).any { x -> bitmap.getPixel(x, y) and 0xFF < 128 } }
        assertTrue("rows with ink: $inked", inked > frac.height * 0.5f)
    }

    @Test
    fun `a floating text box sits at its offsets from the paragraph`() {
        val page = doc.pages.indexOfFirst { p -> p.elements.any { it is HwpxElement.Drawing && it.kind == DrawingKind.RECTANGLE } }
        val box = doc.pages[page].elements.filterIsInstance<HwpxElement.Drawing>().single { it.kind == DrawingKind.RECTANGLE }
        val anchor = runs(page).first { it.text.startsWith("(9)") }
        assertEquals(42520L - 14000L, box.xHu)
        assertEquals(anchor.yHu + 1500L, box.yHu)
        assertEquals(14000L to 4000L, box.widthHu to box.heightHu)
        assertEquals(0xFF1F3864.toInt(), box.lineArgb)
        assertEquals(0xFFDDEBF7.toInt(), box.fillArgb)
        assertEquals(1, box.contentVAlign)
        val text = box.content.filterIsInstance<HwpxElement.TextRun>().single()
        assertEquals("글상자 안의 글", text.text)
        assertEquals(283L, text.xHu) // the left margin
        assertTrue(runs(page).any { it.text.startsWith("(10)") })
        assertColor(listOf(0xDD, 0xEB, 0xF7), render(page).pixelAt(doc.pages[page], box.xHu + box.widthHu - 1500, box.yHu + 600))
    }

    @Test
    fun `lines are drawn with their color`() {
        val page = doc.pages.indexOfFirst { p -> p.elements.any { it is HwpxElement.Drawing && it.kind == DrawingKind.LINE } }
        val line = doc.pages[page].elements.filterIsInstance<HwpxElement.Drawing>().single { it.kind == DrawingKind.LINE }
        assertEquals(listOf(0L to 0L, 42520L to 0L), line.points)
        assertEquals(0xFFC00000.toInt(), line.lineArgb)
        assertColor(listOf(0xC0, 0, 0), render(page, 4000).pixelAt(doc.pages[page], line.xHu + 20000, line.yHu))
    }

    @Test
    fun `pictures are cropped to their clip`() {
        // The image is red on the left and blue on the right; the clip keeps the
        // right half, so even the picture's left quarter is blue.
        val image = doc.pages.flatMap { it.elements }.filterIsInstance<HwpxElement.ImageEl>().single()
        assertEquals(0.5f, image.crop!!.left, 0.001f)
        val page = doc.pages.indexOfFirst { image in it.elements }
        assertColor(listOf(40, 70, 200), render(page).pixelAt(doc.pages[page], image.xHu + image.widthHu / 4, image.yHu + image.heightHu / 2))
    }

    private fun render(page: Int, longSide: Int = 1000): Bitmap {
        var bitmap: Bitmap? = null
        HwpxRenderer.render(doc.copy(pages = listOf(doc.pages[page])), sample("sample.hwpx"), longSide) { _, b -> bitmap = b }
        return bitmap!!
    }

    /** The pixel at body coordinates ([x], [y] in HWPUNIT) of a rendered page. */
    private fun Bitmap.pixelAt(p: HwpxPage, x: Long, y: Long): Int {
        val scale = height.toDouble() / p.heightHu
        return getPixel(((p.bodyXHu + x) * scale).toInt(), ((p.bodyYHu + y) * scale).toInt())
    }

    private fun assertColor(expected: List<Int>, pixel: Int) {
        val actual = listOf((pixel shr 16) and 0xFF, (pixel shr 8) and 0xFF, pixel and 0xFF)
        assertTrue("pixel $actual, expected $expected", expected.zip(actual).all { (e, a) -> kotlin.math.abs(e - a) <= 6 })
    }

    private fun sampleDistinctColors(bmp: Bitmap): Int {
        val seen = HashSet<Int>()
        val stepX = maxOf(1, bmp.width / 80)
        val stepY = maxOf(1, bmp.height / 80)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                seen += bmp.getPixel(x, y)
                x += stepX
            }
            y += stepY
        }
        return seen.size
    }

    private fun savePng(bmp: Bitmap, name: String) {
        FileOutputStream(File(outDir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
