package io.github.baeseungwon1010.officecanvas

import android.graphics.Bitmap
import io.github.baeseungwon1010.officecanvas.slide.OfficeFonts
import io.github.baeseungwon1010.officecanvas.slide.SlideRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfficeCanvasTest {

    private fun resource(path: String) = File(javaClass.classLoader!!.getResource(path)!!.toURI())
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun `formats are detected from content, not from the file name`() {
        val renamed = File.createTempFile("deck", ".bin").apply {
            deleteOnExit()
            writeBytes(resource("ppt_samples/sample_deck.ppt").readBytes())
        }
        assertEquals(DocumentFormat.PPT, DocumentFormat.detect(renamed))
        assertEquals(DocumentFormat.PPTX, DocumentFormat.detect(resource("pptx_samples/sample_deck.pptx")))
        assertEquals(DocumentFormat.HWPX, DocumentFormat.detect(resource("hwpx_samples/sample.hwpx")))
    }

    @Test
    fun `other files are not mistaken for documents`() {
        assertNull(DocumentFormat.detect(resource("emf_samples/shapes.emf")))
        val otherZip = File.createTempFile("other", ".zip").apply {
            deleteOnExit()
            java.util.zip.ZipOutputStream(outputStream()).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("word/document.xml")); z.write("<w/>".toByteArray()); z.closeEntry()
            }
        }
        assertNull(DocumentFormat.detect(otherZip))
        try {
            OfficeCanvas.render(context, otherZip) { _, _ -> }
            fail("a .docx-shaped zip should be rejected")
        } catch (e: OfficeCanvasException) {
            // expected
        }
    }

    @Test
    fun `renders every page at the requested size`() {
        for ((path, pages) in listOf(
            "pptx_samples/sample_deck.pptx" to 5,
            "ppt_samples/sample_deck.ppt" to 5,
            "hwpx_samples/sample.hwpx" to 3
        )) {
            val sizes = mutableListOf<Pair<Int, Int>>()
            val count = OfficeCanvas.render(context, resource(path), longSidePx = 640) { _, bitmap: Bitmap ->
                sizes += bitmap.width to bitmap.height
                bitmap.recycle()
            }
            assertEquals("$path: page count", pages, count)
            assertEquals("$path: callbacks", pages, sizes.size)
            assertTrue("$path: long side should be 640px, was $sizes", sizes.all { maxOf(it.first, it.second) == 640 })
        }
    }

    @Test
    fun `bundled metric-compatible fonts load from the library assets`() {
        OfficeCanvas.render(context, resource("pptx_samples/sample_deck.pptx"), longSidePx = 320) { _, b -> b.recycle() }
        val fonts = SlideRenderer.fonts
        assertNotNull(fonts)
        for (kind in OfficeFonts.Kind.values()) {
            assertNotNull("$kind regular", fonts!!.typeface(kind, bold = false, italic = false))
            assertNotNull("$kind bold", fonts.typeface(kind, bold = true, italic = false))
        }
    }
}
