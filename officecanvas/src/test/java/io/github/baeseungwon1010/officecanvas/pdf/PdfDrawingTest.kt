package io.github.baeseungwon1010.officecanvas.pdf

import io.github.baeseungwon1010.officecanvas.ConvertFormat
import io.github.baeseungwon1010.officecanvas.PdfConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipFile

/** Drawings (curves, slanted lines) become pictures; a table's rules stay a table. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfDrawingTest {

    @Test
    fun `a drawing becomes one picture, a ruled table stays a table`() {
        // A red disc of four curves with a slanted line across it, and a 2 x 2 ruled table below.
        val k = 0.5523 * 40
        val c = StringBuilder()
        c.append("1 0 0 rg 140 600 m ${140 + k} 600 180 ${640 - k} 180 640 c 180 ${640 + k} ${140 + k} 680 140 680 c ")
        c.append("${140 - k} 680 100 ${640 + k} 100 640 c 100 ${640 - k} ${140 - k} 600 140 600 c f\n")
        c.append("0 0 1 RG 2 w 90 590 m 190 690 l S\n")
        c.append("0 0 0 RG 0.5 w 72 500 m 300 500 l S 72 470 m 300 470 l S 72 440 m 300 440 l S 72 500 m 72 440 l S 186 500 m 186 440 l S 300 500 m 300 440 l S\n")
        c.append("BT /F1 10 Tf 80 480 Td (one) Tj 114 0 Td (two) Tj -114 -30 Td (three) Tj 114 0 Td (four) Tj ET\n")
        c.append("BT /F1 10 Tf 72 400 Td (A paragraph under the table.) Tj ET")
        val out = File.createTempFile("drawing", ".docx").apply { deleteOnExit() }
        PdfConverter.convert(TestPdf.page(c.toString(), mapOf("F1" to "Helvetica")), out, ConvertFormat.DOCX)
        ZipFile(out).use { z ->
            val media = z.entries().toList().filter { it.name.startsWith("word/media/") && it.name.endsWith(".png") }
            assertEquals(media.map { it.name }.toString(), 1, media.size)
            val xml = String(z.getInputStream(z.getEntry("word/document.xml")).readBytes())
            // The picture is the drawing's size (the disc and the line: 100 x 100 pt and a little).
            val cx = Regex("<wp:extent cx=\"(\\d+)\"").find(xml)!!.groupValues[1].toLong() / 12700.0
            assertTrue("$cx", cx in 99.0..104.0)
            assertTrue(xml.contains("<w:tbl>") && xml.contains(">three<"))
            // Its pixels: red in the middle of the disc.
            val png = z.getInputStream(media[0]).readBytes()
            val bmp = android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
            val px = bmp.getPixel(bmp.width * 50 / 100, bmp.height * 70 / 100)
            assertEquals(Integer.toHexString(px), 0xFFFF0000.toInt(), px)
        }
    }
}
