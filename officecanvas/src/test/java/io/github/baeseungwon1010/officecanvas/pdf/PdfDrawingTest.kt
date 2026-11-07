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

    @Test
    fun `a slide's text is set where it was, in frames`() {
        // A 16:9 slide: a title, and two labels on either side.
        val c = "BT /F1 28 Tf 60 470 Td (A slide title) Tj ET\n" +
            "BT /F1 14 Tf 80 250 Td (Left label) Tj ET\n" +
            "BT /F1 14 Tf 700 120 Td (Right label) Tj ET"
        val out = File.createTempFile("slide", ".docx").apply { deleteOnExit() }
        PdfConverter.convert(TestPdf.page(c, mapOf("F1" to "Helvetica"), width = 960, height = 540), out, ConvertFormat.DOCX)
        val xml = ZipFile(out).use { z -> String(z.getInputStream(z.getEntry("word/document.xml")).readBytes()) }
        val frames = Regex("<w:framePr [^>]*w:x=\"(\\d+)\" w:y=\"(\\d+)\"").findAll(xml).map { it.groupValues[1].toInt() / 20.0 to it.groupValues[2].toInt() / 20.0 }.toList()
        assertEquals(xml, 3, frames.size)
        // The right label: its line near 540 - 120 pt from the top, its text indented to x = 700 pt in its frame.
        val right = Regex("<w:p>((?:(?!</w:p>).)*Right label(?:(?!</w:p>).)*)</w:p>").find(xml)!!.groupValues[1]
        val y = Regex("<w:framePr [^>]*w:y=\"(\\d+)\"").find(right)!!.groupValues[1].toInt() / 20.0
        val x = Regex("<w:framePr [^>]*w:x=\"(\\d+)\"").find(right)!!.groupValues[1].toInt() / 20.0
        val w = Regex("<w:framePr w:w=\"(\\d+)\"").find(right)!!.groupValues[1].toInt() / 20.0
        val indent = Regex("<w:ind w:left=\"(\\d+)\"").find(right)!!.groupValues[1].toInt() / 20.0
        // (Indented to it, or set against the frame's right edge where the label ends.)
        assertTrue(right, y in 390.0..420.0 && (x + indent in 695.0..705.0 || "<w:jc w:val=\"right\"/>" in right && x + w in 760.0..780.0))
    }
}
