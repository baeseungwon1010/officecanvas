package io.github.baeseungwon1010.officecanvas.slide

import io.github.baeseungwon1010.officecanvas.pptx.PptxParser

import android.graphics.Bitmap
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the .pptx samples end-to-end and writes the pixels to disk, because a
 * renderer that runs without throwing is not the same thing as a renderer that draws
 * anything. Output lands in build/pptx_render_check/ for visual inspection — this is
 * the fastest feedback loop available (no device, no emulator, actual Canvas/Bitmap).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Robolectric's default (LEGACY) graphics mode only records draw calls; it does not
// rasterize them into the Bitmap's actual pixels, which is why this test exists.
// NATIVE mode runs the real Skia path so getPixel() reflects what was actually drawn.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SlideRendererTest {

    private fun sample(name: String): File {
        val url = javaClass.classLoader?.getResource("pptx_samples/$name")
            ?: throw AssertionError("test resource missing: pptx_samples/$name")
        return File(url.toURI())
    }

    private fun allSamples(): List<File> {
        val dir = sample("sample_deck.pptx").parentFile!!
        return dir.listFiles { f -> f.extension == "pptx" }!!.toList()
    }

    private val outDir = File("build/pptx_render_check").apply { mkdirs() }

    @Test
    fun `renders every sample deck to non-blank bitmaps`() {
        SlideRenderer.DEBUG_LOG_DRAW_ERRORS = true
        for (file in allSamples()) {
            val deck = PptxParser.parse(file)
            val bitmaps = mutableListOf<Bitmap>()
            SlideRenderer.render(deck, file, targetLongSidePx = 800) { _, bmp -> bitmaps += bmp }
            org.junit.Assert.assertEquals("${file.name}: bitmap count should match slide count", deck.slides.size, bitmaps.size)

            for ((i, bmp) in bitmaps.withIndex()) {
                org.junit.Assert.assertTrue("${file.name} slide $i: width should be positive", bmp.width > 0)
                org.junit.Assert.assertTrue("${file.name} slide $i: height should be positive", bmp.height > 0)

                val distinctColors = sampleDistinctColors(bmp)
                // A slide with any shape, text, or picture on it should show more than
                // one colour. A count of 1 means the draw calls silently did nothing —
                // exactly the failure mode "it didn't throw" would miss.
                if (deck.slides[i].elements.isNotEmpty()) {
                    org.junit.Assert.assertTrue(
                        "${file.name} slide $i: has ${deck.slides[i].elements.size} elements but rendered as a flat single colour",
                        distinctColors > 1
                    )
                }
            }

            savePng(bitmaps.first(), "${file.nameWithoutExtension}_slide1.png")
            if (bitmaps.size > 1) savePng(bitmaps[bitmaps.size / 2], "${file.nameWithoutExtension}_slideMid.png")
        }
        println("Rendered PNGs written to: ${outDir.absolutePath}")
    }

    private fun sampleDistinctColors(bmp: Bitmap): Int {
        val seen = HashSet<Int>()
        val stepX = maxOf(1, bmp.width / 60)
        val stepY = maxOf(1, bmp.height / 60)
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
