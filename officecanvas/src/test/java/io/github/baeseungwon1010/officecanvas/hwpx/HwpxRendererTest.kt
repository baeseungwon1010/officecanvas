package io.github.baeseungwon1010.officecanvas.hwpx

import android.graphics.Bitmap
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
