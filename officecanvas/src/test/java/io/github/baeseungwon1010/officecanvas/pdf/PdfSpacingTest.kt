package io.github.baeseungwon1010.officecanvas.pdf

import io.github.baeseungwon1010.officecanvas.ConvertFormat
import io.github.baeseungwon1010.officecanvas.PdfConverter
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/** Letter spacing (Tc) and horizontal scaling (Tz) set in the PDF carry over to the document. */
class PdfSpacingTest {

    /** One page of Helvetica at 10 pt, set 0.5 pt tighter (-5 %), its last two lines also 90 % wide. */
    private fun pdf(): File {
        val text = StringBuilder("BT /F1 10 Tf -0.5 Tc 72 700 Td 14 TL\n")
        repeat(4) { text.append("(Letters set closer together than the font has them) '\n") }
        text.append("90 Tz\n")
        repeat(2) { text.append("(Letters set closer and narrower than the font has them) '\n") }
        text.append("ET")
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
            "<< /Length ${text.length} >>\nstream\n$text\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        )
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.7\n".toByteArray())
        val offsets = objects.mapIndexed { i, o -> out.size().also { out.write("${i + 1} 0 obj\n$o\nendobj\n".toByteArray()) } }
        val start = out.size()
        out.write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n".toByteArray())
        for (off in offsets) out.write(String.format("%010d 00000 n \n", off).toByteArray())
        out.write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$start\n%%EOF\n".toByteArray())
        return File.createTempFile("spacing", ".pdf").apply { deleteOnExit(); writeBytes(out.toByteArray()) }
    }

    private fun entry(file: File, name: String) = ZipFile(file).use { z -> String(z.getInputStream(z.getEntry(name)).readBytes()) }

    @Test
    fun `letter spacing and scaling reach DOCX and HWPX`() {
        val src = pdf()
        val docx = File.createTempFile("spacing", ".docx").apply { deleteOnExit() }
        PdfConverter.convert(src, docx, ConvertFormat.DOCX)
        val xml = entry(docx, "word/document.xml")
        // -0.5 pt is -10 twentieths of a point.
        assertTrue(xml, xml.contains("<w:spacing w:val=\"-10\"/>"))
        assertTrue(xml, xml.contains("<w:w w:val=\"90\"/>"))
        val hwpx = File.createTempFile("spacing", ".hwpx").apply { deleteOnExit() }
        PdfConverter.convert(src, hwpx, ConvertFormat.HWPX)
        val header = entry(hwpx, "Contents/header.xml")
        assertTrue(header, header.contains("<hh:spacing hangul=\"-5\""))
        assertTrue(header, header.contains("<hh:ratio hangul=\"90\""))
    }
}
