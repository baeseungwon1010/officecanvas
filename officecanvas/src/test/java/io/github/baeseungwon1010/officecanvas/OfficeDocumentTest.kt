package io.github.baeseungwon1010.officecanvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import io.github.baeseungwon1010.officecanvas.hwpx.CharStyle
import io.github.baeseungwon1010.officecanvas.hwpx.DrawingKind
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxDoc
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxElement
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxPage
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxRenderer
import io.github.baeseungwon1010.officecanvas.hwpx.Span
import io.github.baeseungwon1010.officecanvas.slide.Box
import io.github.baeseungwon1010.officecanvas.slide.Deck
import io.github.baeseungwon1010.officecanvas.slide.Element
import io.github.baeseungwon1010.officecanvas.slide.Paragraph
import io.github.baeseungwon1010.officecanvas.slide.Run
import io.github.baeseungwon1010.officecanvas.slide.Slide
import io.github.baeseungwon1010.officecanvas.slide.SlideRenderer
import io.github.baeseungwon1010.officecanvas.slide.TextBody
import io.github.baeseungwon1010.officecanvas.util.PictureCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfficeDocumentTest {

    private fun resource(path: String) = File(javaClass.classLoader!!.getResource(path)!!.toURI())
    private val context get() = RuntimeEnvironment.getApplication()

    private val fixtures = listOf(
        "pptx_samples/sample_deck.pptx", "ppt_samples/sample_deck.ppt", "docx_samples/features.docx",
        "hwpx_samples/sample.hwpx", "hwp_samples/sample.hwp"
    )

    /** Page [index] drawn into a [w] x [h] bitmap at [pxPerPt], its top left [left], [top] px into the page. */
    private fun OfficeDocument.draw(index: Int, w: Int, h: Int, pxPerPt: Float, left: Float = 0f, top: Float = 0f): Bitmap {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.MAGENTA)
        canvas.translate(-left, -top)
        canvas.scale(pxPerPt, pxPerPt)
        drawPage(index, canvas)
        return bitmap
    }

    /** Pixels whose channels differ by more than [tolerance], as a fraction of all. */
    private fun differing(a: Bitmap, b: Bitmap, tolerance: Int = 24): Double {
        assertEquals(a.width to a.height, b.width to b.height)
        val pa = IntArray(a.width * a.height).also { a.getPixels(it, 0, a.width, 0, 0, a.width, a.height) }
        val pb = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        var n = 0
        for (i in pa.indices) {
            val x = pa[i]; val y = pb[i]
            if (abs((x shr 16 and 0xFF) - (y shr 16 and 0xFF)) > tolerance ||
                abs((x shr 8 and 0xFF) - (y shr 8 and 0xFF)) > tolerance ||
                abs((x and 0xFF) - (y and 0xFF)) > tolerance) n++
        }
        return n.toDouble() / pa.size
    }

    @Test
    fun `a page drawn at the size render gives matches render`() {
        for (path in fixtures) {
            val file = resource(path)
            val rendered = mutableListOf<Bitmap>()
            OfficeCanvas.render(context, file, longSidePx = 800) { _, b -> rendered += b }
            OfficeCanvas.open(context, file).use { doc ->
                assertEquals(path, rendered.size, doc.pageCount)
                for ((i, page) in rendered.withIndex()) {
                    val pxPerPt = 800f / maxOf(doc.pageWidthPt(i), doc.pageHeightPt(i))
                    val drawn = doc.draw(i, page.width, page.height, pxPerPt)
                    // The same drawing under a matrix that differs in its last bits.
                    val d = differing(page, drawn)
                    assertTrue("$path page ${i + 1}: $d of pixels differ", d < 0.001)
                    drawn.recycle(); page.recycle()
                }
            }
        }
    }

    @Test
    fun `a region drawn at 4x is the same as that part of the whole page at 4x`() {
        for (path in fixtures) {
            OfficeCanvas.open(context, resource(path)).use { doc ->
                val pxPerPt = 4 * 400f / maxOf(doc.pageWidthPt(0), doc.pageHeightPt(0))
                val w = (doc.pageWidthPt(0) * pxPerPt).toInt(); val h = (doc.pageHeightPt(0) * pxPerPt).toInt()
                val whole = doc.draw(0, w, h, pxPerPt)
                for ((left, top) in listOf(w / 5 to h / 7, w / 2 to h / 3, 0 to 0)) {
                    val region = doc.draw(0, 500, 360, pxPerPt, left.toFloat(), top.toFloat())
                    val crop = Bitmap.createBitmap(whole, left, top, minOf(500, w - left), minOf(360, h - top))
                    val part = Bitmap.createBitmap(region, 0, 0, crop.width, crop.height)
                    val d = differing(crop, part)
                    assertTrue("$path at ($left, $top): $d of pixels differ", d < 0.001)
                }
                whole.recycle()
            }
        }
    }

    private fun hwpxDoc(vararg elements: HwpxElement) =
        OfficeDocument(DocumentFormat.HWPX, HwpxRenderer.Pages(HwpxDoc(listOf(HwpxPage(59528, 84188, elements.toList(), 0, 0))), File("none.hwpx"), 1L shl 20), "test")

    /** Rows (or columns) of [b] with ink at column (row) [at]. */
    private fun inkRun(b: Bitmap, at: Int, vertical: Boolean): Int =
        (0 until if (vertical) b.height else b.width).count {
            val p = if (vertical) b.getPixel(at, it) else b.getPixel(it, at)
            Color.red(p) < 200
        }

    @Test
    fun `hairlines stay a device pixel or so at high zoom`() {
        // A line of no width, a 0.1 mm table border, and a slide line 0.25 pt wide.
        val doc = hwpxDoc(
            HwpxElement.Drawing(1000, 1000, 20000, 0, DrawingKind.LINE, listOf(0L to 0L, 20000L to 0L), lineWidthHu = 0),
        )
        doc.use {
            // A page 80x zoomed: a point is 160 px.
            val zoomed = it.draw(0, 400, 200, 160f, left = 2000f, top = 1600f - 100f)
            val rows = inkRun(zoomed, 200, vertical = true)
            assertTrue("a zero-width line is $rows px thick at 80x", rows in 1..3)
            val whole = it.draw(0, 300, 424, 300f / 595.28f)
            assertTrue("and still shows at 1x", inkRun(whole, 100, vertical = true) in 1..3)
        }
        val deck = Deck(12192000, 6858000, listOf(Slide(null, listOf(
            Element.Shape(Box(914400, 914400, 9144000, 0), "line", null, Color.BLACK, 3175, null)
        ))))
        OfficeDocument(DocumentFormat.PPTX, SlideRenderer.Pages(deck, File("none.pptx"), 1L shl 20), "test").use {
            // 0.25 pt at 40 px per point is 10 px; a one-pixel floor at the
            // reference size (1600 px across 960 pt) would have made it 24 px.
            val rows = inkRun(it.draw(0, 400, 200, 40f, left = 4000f, top = 72f * 40 - 100f), 200, vertical = true)
            assertTrue("a 0.25 pt line is $rows px thick at 40 px/pt", rows in 9..12)
        }
    }

    /** Left, top, right and bottom edges of the ink in [b]: pixels at least half covered. */
    private fun ink(b: Bitmap): FloatArray {
        var minX = Int.MAX_VALUE; var maxX = -1; var minY = Int.MAX_VALUE; var maxY = -1
        for (y in 0 until b.height) for (x in 0 until b.width) {
            if (Color.red(b.getPixel(x, y)) > 127) continue
            minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }
        return floatArrayOf(minX.toFloat(), minY.toFloat(), maxX + 1f, maxY + 1f)
    }

    @Test
    fun `text stays where it is between 1x and 6x`() {
        // A justified line, and a slide text box that wraps: both laid out the same at every zoom.
        val style = CharStyle(sizePt = 12f, latinFace = "Arial")
        val hwpx = hwpxDoc(HwpxElement.TextRun(7200, 7200, 30000, 1200, listOf(Span("Squeezed justified words", style)),
            align = io.github.baeseungwon1010.officecanvas.hwpx.ParaAlign.JUSTIFY, lastLine = false))
        val body = TextBody(listOf(Paragraph(listOf(Run("Text that wraps in its box, twice over", 18f, fontFamily = "Arial")))))
        val box = Element.Shape(Box(914400, 914400, 2743200, 1371600), null, null, null, 0, body)
        val deck = Deck(12192000, 6858000, listOf(Slide(null, listOf(box))))
        val slides = OfficeDocument(DocumentFormat.PPTX, SlideRenderer.Pages(deck, File("none.pptx"), 1L shl 20), "test")
        // The text is within (60, 60) to (460, 180) points on both pages.
        for ((name, doc) in listOf("hwpx" to hwpx, "slide" to slides)) doc.use {
            val one = ink(it.draw(0, 400, 120, 1f, left = 60f, top = 60f))
            val six = ink(it.draw(0, 2400, 720, 6f, left = 360f, top = 360f))
            val twelve = ink(it.draw(0, 4800, 1440, 12f, left = 720f, top = 720f))
            for (k in 0..3) {
                // Within a pixel of the 1x image (glyphs are rasterised there) ...
                assertEquals("$name edge $k at 1x and 6x: ${one.toList()} ${six.toList()}", one[k] * 6, six[k], 6f)
                // ... and to a couple of pixels between two zoomed-in draws.
                assertEquals("$name edge $k at 6x and 12x: ${six.toList()} ${twelve.toList()}", six[k] * 2, twelve[k], 2f)
            }
        }
    }

    private fun png(color: Int, size: Int): ByteArray {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return java.io.ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    @Test
    fun `decoded pictures stay within the cache and go on close`() {
        val file = File.createTempFile("pictures", ".hwpx").apply { deleteOnExit() }
        java.util.zip.ZipOutputStream(file.outputStream()).use { z ->
            for (k in 0 until 4) { z.putNextEntry(java.util.zip.ZipEntry("BinData/image$k.png")); z.write(png(Color.rgb(40 * k, 0, 0), 1200)); z.closeEntry() }
        }
        val pictures = (0 until 4).map { k -> HwpxElement.ImageEl(k * 14000L, 0, 14000, 14000, "image$k") }
        val bound = 8L shl 20
        val pages = HwpxRenderer.Pages(HwpxDoc(listOf(HwpxPage(59528, 84188, pictures, 0, 0))), file, bound)
        val doc = OfficeDocument(DocumentFormat.HWPX, pages, "test")
        val held = mutableListOf<Bitmap>()
        val cache = pages.pictures
        for (pxPerPt in listOf(0.5f, 1f, 2f, 4f, 2f)) {
            doc.draw(0, 600, 300, pxPerPt).recycle()
            assertTrue("${cache.bytes} bytes held", cache.bytes <= bound)
            held += cacheBitmaps(cache)
        }
        // Drawing again at a size seen before decodes nothing.
        val before = cacheBitmaps(cache).toSet()
        doc.draw(0, 600, 300, 2f).recycle()
        assertEquals(before, cacheBitmaps(cache).toSet())
        doc.close()
        assertEquals(0, cache.size)
        assertTrue(held.all { it.isRecycled })
        try { doc.drawPage(0, Canvas()); fail("drawing a closed document") } catch (e: IllegalStateException) { }
    }

    private fun cacheBitmaps(cache: PictureCache): List<Bitmap> {
        val f = PictureCache::class.java.getDeclaredField("entries").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val entries = f.get(cache) as List<Any>
        return entries.map { e -> e.javaClass.getDeclaredField("bitmap").apply { isAccessible = true }.get(e) as Bitmap }
    }

    @Test
    fun `bad files fail to open as they fail to render, and page numbers are checked`() {
        val notADocument = File.createTempFile("other", ".zip").apply {
            deleteOnExit()
            java.util.zip.ZipOutputStream(outputStream()).use { z -> z.putNextEntry(java.util.zip.ZipEntry("word/document.xml")); z.write("<w/>".toByteArray()); z.closeEntry() }
        }
        try { OfficeCanvas.open(context, notADocument).close(); fail("a .docx without a body should be rejected") } catch (e: OfficeCanvasException) { }
        OfficeCanvas.open(context, resource("pptx_samples/sample_deck.pptx")).use { doc ->
            assertEquals(DocumentFormat.PPTX, doc.format)
            for (bad in listOf(-1, doc.pageCount)) {
                try { doc.drawPage(bad, Canvas(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))); fail("page $bad") } catch (e: IndexOutOfBoundsException) { }
            }
            assertTrue(doc.pageWidthPt(0) > doc.pageHeightPt(0))
        }
    }
}
