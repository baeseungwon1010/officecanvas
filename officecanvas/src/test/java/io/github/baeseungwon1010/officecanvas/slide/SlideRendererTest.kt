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

    @Test
    fun `draws cropped pictures, SVG-only icons and alternate-content fallbacks`() {
        val file = sample("pptx_features.pptx")
        val deck = PptxParser.parse(file)
        var slide: Bitmap? = null
        // 10in wide at 1000px: 100px per inch.
        SlideRenderer.render(deck.copy(slides = listOf(deck.slides[5])), file, targetLongSidePx = 1000) { _, bmp -> slide = bmp }
        val bmp = slide!!
        savePng(bmp, "pptx_features_pictures.png")
        fun near(x: Int, y: Int, argb: Int) {
            val p = bmp.getPixel(x, y)
            val d = listOf(16, 8, 0).maxOf { kotlin.math.abs(((p shr it) and 0xFF) - ((argb shr it) and 0xFF)) }
            org.junit.Assert.assertTrue("pixel ($x,$y) is ${Integer.toHexString(p)}, expected ${Integer.toHexString(argb)}", d <= 12)
        }
        near(150, 200, 0xFF0000FF.toInt())   // crop keeps the blue right half
        near(500, 200, 0xFF00A050.toInt())   // SVG circle, colored by its CSS class
        near(800, 200, 0xFFFFA000.toInt())   // picture from mc:Fallback
    }

    @Test
    fun `draws shadows under shapes and highlights behind text`() {
        val file = sample("pptx_features.pptx")
        val deck = PptxParser.parse(file)
        var slide: Bitmap? = null
        // 10in wide at 1000px: 100px per inch.
        SlideRenderer.render(deck.copy(slides = listOf(deck.slides[7])), file, targetLongSidePx = 1000) { _, bmp -> slide = bmp }
        val bmp = slide!!
        savePng(bmp, "pptx_features_effects.png")
        fun luma(x: Int, y: Int) = bmp.getPixel(x, y).let { ((it shr 16) and 0xFF) + ((it shr 8) and 0xFF) + (it and 0xFF) } / 3
        // The first box (1..4in, 1..3in) casts a hard black shadow ~11px down and right;
        // the second (5.5..8.5in) has none.
        org.junit.Assert.assertTrue("shadow under the first box: ${luma(405, 305)}", luma(405, 305) < 60)
        org.junit.Assert.assertTrue("no shadow under the second: ${luma(855, 305)}", luma(855, 305) > 200)
        // The box turned 60 degrees in 3-D shows its blue extrusion beside it.
        val blue = (840 until 1000).count { x -> bmp.getPixel(x, 500).let { p -> (p and 0xFF) > 150 && (p shr 16 and 0xFF) < 60 } }
        org.junit.Assert.assertTrue("extrusion pixels: $blue", blue > 10)
        // White text on an amber highlight: the line's background is amber, not white.
        val amber = (0 until 400).count { x -> bmp.getPixel(110 + x, 500).let { p -> (p shr 16 and 0xFF) > 200 && (p and 0xFF) < 80 } }
        org.junit.Assert.assertTrue("highlight pixels: $amber", amber > 50)
    }

    @Test
    fun `table rows grow to fit their text`() {
        val file = sample("pptx_features.pptx")
        val deck = PptxParser.parse(file)
        var slide: Bitmap? = null
        // 10in wide at 1000px: 100px per inch.
        SlideRenderer.render(deck.copy(slides = listOf(deck.slides[6])), file, targetLongSidePx = 1000) { _, bmp -> slide = bmp }
        val bmp = slide!!
        savePng(bmp, "pptx_features_table_rows.png")
        // The rows are saved 0.1in tall at 4.5in; the first holds three 18pt lines,
        // so "below" in the second row is drawn under them (about 5.6in), not
        // over them at 4.6in.
        fun inked(y0: Int, y1: Int) = (y0 until y1).any { y -> (100 until 300).any { x -> bmp.getPixel(x, y) and 0xFF < 100 } }
        org.junit.Assert.assertTrue("text under the first row's", inked(552, 590))
    }

    @Test
    fun `text estimated to overflow shrinks in PowerPoint's steps`() {
        // Ten 20pt lines, 24pt apart at single spacing: 240pt, in a box 230pt
        // tall. Reducing the line spacing by 10% (216pt) is the first step that
        // fits, before any smaller text; 200pt needs 20% (192pt); 181pt needs
        // the text at 92.5% as well (222, 199.8 and then 177.6pt at 0, 10 and
        // 20% less spacing).
        fun chosen(heightPt: Double): Pair<Float, Float> {
            val body = TextBody(
                (1..10).map { Paragraph(listOf(Run("line $it", sizePt = 20f))) },
                insetLeftEmu = 0, insetTopEmu = 0, insetRightEmu = 0, insetBottomEmu = 0,
                autoFit = true, autoFitFloor = 0.4f, estimateFit = true
            )
            val shape = Element.Shape(Box(0, 0, 4 * EMU_PER_INCH.toLong(), (heightPt * EMU_PER_POINT).toLong()), null, null, null, 0, body)
            val deck = Deck(10 * EMU_PER_INCH.toLong(), (7.5 * EMU_PER_INCH).toLong(), listOf(Slide(null, listOf(shape))))
            val fits = mutableListOf<Pair<Float, Float>>()
            SlideRenderer.debugFit = fits
            try {
                SlideRenderer.render(deck, File("unused"), targetLongSidePx = 1000) { _, bmp -> bmp.recycle() }
            } finally {
                SlideRenderer.debugFit = null
            }
            return fits.single()
        }
        org.junit.Assert.assertEquals(1f to 0f, chosen(250.0))
        org.junit.Assert.assertEquals(1f to 10f, chosen(230.0))
        org.junit.Assert.assertEquals(1f to 20f, chosen(200.0))
        org.junit.Assert.assertEquals(0.925f to 20f, chosen(181.0))
    }

    @Test
    fun `Korean text wraps between words, not syllables`() {
        val words = listOf("유사도는", "높지만", "생성된", "지", "육개월이", "넘은", "오래된", "패키지인", "경우")
        val body = TextBody(listOf(Paragraph(listOf(Run(words.joinToString(" "), sizePt = 24f)))))
        // A box about eight syllables wide (10in slide at 1000px: 100px per inch).
        val shape = Element.Shape(Box(0, 0, (2.6 * EMU_PER_INCH).toLong(), 3 * EMU_PER_INCH.toLong()), null, null, null, 0, body)
        val deck = Deck(10 * EMU_PER_INCH.toLong(), (7.5 * EMU_PER_INCH).toLong(), listOf(Slide(null, listOf(shape))))
        val lines = mutableListOf<Pair<String, Float>>()
        SlideRenderer.debugBaselines = lines
        try {
            SlideRenderer.render(deck, File("unused"), targetLongSidePx = 1000) { _, bmp -> bmp.recycle() }
        } finally {
            SlideRenderer.debugBaselines = null
        }
        org.junit.Assert.assertTrue("several lines: $lines", lines.size >= 3)
        for ((text, _) in lines) {
            org.junit.Assert.assertTrue("\"$text\" is whole words", text.split(" ").all { it in words })
        }
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
