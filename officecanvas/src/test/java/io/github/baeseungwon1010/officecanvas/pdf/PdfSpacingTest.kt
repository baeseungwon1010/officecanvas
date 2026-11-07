package io.github.baeseungwon1010.officecanvas.pdf

import io.github.baeseungwon1010.officecanvas.ConvertFormat
import io.github.baeseungwon1010.officecanvas.PdfConverter
import org.junit.Assert.assertTrue
import org.junit.Test
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
        return TestPdf.page(text.toString(), mapOf("F1" to "Helvetica"))
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

    /** Hangul in a Type 3 font (no name, as Hancom's export sets it), each syllable 0.9 em wide. */
    private fun hangul(): File {
        val tounicode = "/CIDInit /ProcSet findresource begin 12 dict begin begincmap 1 begincodespacerange <00> <FF> endcodespacerange " +
            "3 beginbfchar <01> <AC00> <02> <B098> <03> <B2E4> endbfchar endcmap CMapName currentdict /CMap defineresource pop end end"
        val glyph = "900 0 0 0 800 700 d1 0 0 m 800 0 l 800 700 l 0 700 l f"
        val font = "<< /Type /Font /Subtype /Type3 /FontBBox [0 0 900 700] /FontMatrix [0.001 0 0 0.001 0 0] " +
            "/CharProcs << /g1 7 0 R /g2 8 0 R /g3 9 0 R >> /Encoding << /Type /Encoding /Differences [1 /g1 /g2 /g3] >> " +
            "/FirstChar 1 /LastChar 3 /Widths [900 900 900] /Resources << >> /ToUnicode 6 0 R >>"
        val stream = { body: String -> "<< /Length ${body.length} >>\nstream\n$body\nendstream" }
        val text = StringBuilder("BT /F1 10 Tf 72 700 Td 14 TL\n")
        repeat(4) { text.append("(" + "\\001\\002\\003 ".repeat(12).trim() + ") '\n") }
        text.append("ET")
        return TestPdf.page(text.toString(), mapOf("F1" to font), listOf(stream(tounicode), stream(glyph), stream(glyph), stream(glyph)))
    }

    @Test
    fun `Hangul in a font without a name is set in Malgun Gothic at the width it has`() {
        val docx = File.createTempFile("hangul", ".docx").apply { deleteOnExit() }
        PdfConverter.convert(hangul(), docx, ConvertFormat.DOCX)
        val xml = entry(docx, "word/document.xml")
        assertTrue(xml, "가나다" in xml)
        assertTrue(xml, xml.contains("w:ascii=\"맑은 고딕\""))
        // Its syllables are a full em wide; these, 0.9 em.
        assertTrue(xml, xml.contains("<w:w w:val=\"90\"/>"))
    }
}
