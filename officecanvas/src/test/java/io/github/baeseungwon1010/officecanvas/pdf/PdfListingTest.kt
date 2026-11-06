package io.github.baeseungwon1010.officecanvas.pdf

import io.github.baeseungwon1010.officecanvas.ConvertFormat
import io.github.baeseungwon1010.officecanvas.PdfConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** A shaded code listing, and a one-page document's footer. */
class PdfListingTest {

    private fun docx(pdf: File): Map<String, String> {
        val out = File.createTempFile("listing", ".docx").apply { deleteOnExit() }
        PdfConverter.convert(pdf, out, ConvertFormat.DOCX)
        return ZipFile(out).use { z -> z.entries().toList().associate { it.name to String(z.getInputStream(it).readBytes()) } }
    }

    @Test
    fun `a shaded listing keeps its lines, indents and spacing, shaded throughout`() {
        // Six lines of Courier, 13 pt apart, each on a dark band 13 pt tall, the bands touching.
        val lines = listOf("int main(void)", "{", "    int x = 1;     // one", "    return x;      // back", "}", "// end")
        val c = StringBuilder()
        for (i in lines.indices) c.append("0.12 0.12 0.12 rg 60 ${700 - i * 13 - 4} 480 13 re f\n")
        c.append("0.86 0.86 0.86 rg BT /F1 9 Tf 13 TL 64 700 Td\n")
        for (l in lines) c.append("(${l.replace("(", "\\(").replace(")", "\\)")}) Tj T*\n")
        c.append("ET\n0 0 0 rg BT /F2 11 Tf 72 560 Td (A paragraph after the listing.) Tj ET")
        val xml = docx(TestPdf.page(c.toString(), mapOf("F1" to "Courier", "F2" to "Helvetica"))).getValue("word/document.xml")
        val paras = Regex("<w:p>(.*?)</w:p>").findAll(xml).map { it.groupValues[1] }.filter { "<w:t" in it }.toList()
        fun text(p: String) = Regex("<w:t[^>]*>([^<]*)</w:t>").findAll(p).joinToString("") { it.groupValues[1] }
        // Each line its own paragraph, the spaces in it kept as they line things up (those before it indent it).
        val code = paras.filter { "1F1F1F" in it }
        assertEquals(paras.map { text(it) }.toString(), lines.map { it.trimStart() }, code.map { text(it).trimEnd() })
        // Indented by a first-line indent inside the shading, which starts at the band.
        val indented = code[2]
        assertTrue(indented, Regex("w:firstLine=\"(\\d+)\"").find(indented)!!.groupValues[1].toInt() > 400)
        // The pitch is the lines' height, no space between them to leave unshaded.
        for (p in code.drop(1)) {
            assertTrue(p, p.contains("w:line=\"260\" w:lineRule=\"exact\""))
            assertTrue(p, p.contains("w:before=\"0\""))
        }
        assertTrue(paras.any { text(it).startsWith("A paragraph after") && "<w:shd" !in it })
    }

    @Test
    fun `a one-page document's footer, and fonts described for readers without them`() {
        val c = "BT /F1 11 Tf 72 700 Td (Body text on the only page.) Tj ET\n" +
            "BT /F1 8 Tf 72 40 Td (Course notes) Tj ET BT /F1 8 Tf 300 40 Td (1) Tj ET\n" +
            "BT /F2 10 Tf 72 660 Td (mono text) Tj ET"
        val parts = docx(TestPdf.page(c, mapOf("F1" to "Helvetica", "F2" to "Courier")))
        val footer = parts.getValue("word/footer1.xml")
        assertTrue(footer, "Course notes" in footer && "PAGE" in footer)
        assertTrue("Course notes" !in parts.getValue("word/document.xml"))
        val fonts = parts.getValue("word/fontTable.xml")
        assertTrue(fonts, fonts.contains("<w:font w:name=\"Courier New\"><w:family w:val=\"modern\"/><w:pitch w:val=\"fixed\"/>"))
        assertTrue(fonts, fonts.contains("<w:font w:name=\"Arial\"><w:family w:val=\"swiss\"/><w:pitch w:val=\"variable\"/>"))
    }
}
