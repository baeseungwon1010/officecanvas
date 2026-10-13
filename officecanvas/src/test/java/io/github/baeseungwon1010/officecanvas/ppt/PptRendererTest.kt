package io.github.baeseungwon1010.officecanvas.ppt

import android.graphics.Bitmap
import io.github.baeseungwon1010.officecanvas.slide.SlideRenderer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** See pptx's SlideRendererTest for why @GraphicsMode(NATIVE) is required. This
 *  reuses SlideRenderer itself — PptParser's whole point is to produce the same
 *  Deck model pptx does, so there is no separate ppt renderer to test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PptRendererTest {

    private fun sample(name: String): File {
        val url = javaClass.classLoader?.getResource("ppt_samples/$name")
            ?: throw AssertionError("test resource missing: ppt_samples/$name")
        return File(url.toURI())
    }

    private fun allSamples(): List<File> {
        val dir = sample("sample_deck.ppt").parentFile!!
        return dir.listFiles { f -> f.extension == "ppt" }!!.toList()
    }

    private val outDir = File("build/ppt_render_check").apply { mkdirs() }

    @Test
    fun `renders every sample ppt to non-blank bitmaps`() {
        SlideRenderer.DEBUG_LOG_DRAW_ERRORS = true
        for (file in allSamples()) {
            val deck = PptParser.parse(file)
            val bitmaps = mutableListOf<Bitmap>()
            SlideRenderer.render(deck, file, targetLongSidePx = 800) { _, bmp -> bitmaps += bmp }
            org.junit.Assert.assertEquals("${file.name}: bitmap count should match slide count", deck.slides.size, bitmaps.size)

            for ((i, bmp) in bitmaps.withIndex()) {
                val distinctColors = sampleDistinctColors(bmp)
                val hasContent = deck.slides[i].elements.any {
                    (it is io.github.baeseungwon1010.officecanvas.slide.Element.Shape && it.text != null) ||
                        it is io.github.baeseungwon1010.officecanvas.slide.Element.Picture
                }
                if (hasContent) {
                    org.junit.Assert.assertTrue(
                        "${file.name} slide $i: has text or a picture but rendered as a flat single colour",
                        distinctColors > 1
                    )
                }
                savePng(bmp, "${file.nameWithoutExtension}_slide${i + 1}.png")
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
