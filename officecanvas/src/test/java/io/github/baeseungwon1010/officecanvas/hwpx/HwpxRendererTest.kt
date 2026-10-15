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
        for (i in doc.pages.indices) {
            val r = runs(i)
            assertTrue("header on page ${i + 1}", r.any { it.text == "OfficeCanvas 샘플 · 쪽 ${i + 1}" && it.yHu < 0 })
            assertTrue("page number on page ${i + 1}", r.any { it.text == "- ${i + 1} -" && it.align == ParaAlign.CENTER })
        }
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
