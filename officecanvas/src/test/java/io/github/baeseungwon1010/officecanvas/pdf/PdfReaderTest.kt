package io.github.baeseungwon1010.officecanvas.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * pdf_samples/ holds LibreOffice's PDF export of docx_samples/sample.docx and
 * features.docx, and encrypted copies of sample.pdf (tools/make_pdf_fixtures.py).
 */
class PdfReaderTest {

    private fun file(name: String) = File(javaClass.classLoader!!.getResource("pdf_samples/$name")!!.toURI())

    private fun pagesText(name: String): List<String> = PdfDocument.open(file(name)).use { doc ->
        val reader = PdfPageReader(doc)
        doc.pages.map { p -> reader.read(p).chars.joinToString("") { it.text } }
    }

    @Test
    fun `reads every page's text through its fonts`() {
        val pages = pagesText("sample.pdf")
        assertEquals(3, pages.size)
        val first = pages[0].replace(" ", "")
        assertTrue(first, "DOCXsample" in first)
        assertTrue(first, "OfficeCanvassampleheader" in first)
        // Hangul set in an embedded subset font, read through its ToUnicode map.
        assertTrue(first, "한글문단은띄어쓰기에서" in first)
        assertTrue(pages[2].replace(" ", "").contains("Afterthepagebreak"))
    }

    @Test
    fun `opens AES-256 and RC4 files locked with an owner password only`() {
        val plain = pagesText("sample.pdf")
        assertEquals(plain, pagesText("sample_aes256.pdf"))
        assertEquals(plain, pagesText("sample_rc4.pdf"))
    }

    @Test
    fun `refuses a file that needs a password to open`() {
        try {
            PdfDocument.open(file("sample_locked.pdf")).close()
            throw AssertionError("opened a locked file")
        } catch (e: PdfCrypt.PasswordException) {
        }
    }

    @Test
    fun `characters carry their size, weight and color`() {
        PdfDocument.open(file("sample.pdf")).use { doc ->
            val chars = PdfPageReader(doc).read(doc.pages[0]).chars
            val title = chars.first { it.text == "D" }
            assertTrue("${title.size}", title.size > 20)
            val red = chars.first { it.text == "r" && it.color != 0xFF000000.toInt() }
            assertEquals(0xFFC00000.toInt(), red.color)
            assertTrue(chars.any { it.text == "B" && it.font.bold })
        }
    }

    @Test
    fun `table rulings, shading and the picture are collected`() {
        PdfDocument.open(file("sample.pdf")).use { doc ->
            val reader = PdfPageReader(doc)
            val all = doc.pages.map { reader.read(it) }
            // The shaded paragraph and the shaded table cell.
            assertTrue(all.any { c -> c.rects.any { it.color == 0xFFFFF2CC.toInt() } })
            assertTrue(all.any { c -> c.rects.any { it.color == 0xFFD9E2F3.toInt() } })
            assertTrue(all.sumOf { it.lines.size + it.rects.size } > 10)
            val pic = all.flatMap { it.pictures }.single { !it.drawing }
            val image = pic.image()!!
            assertEquals(2.0, image.width.toDouble() / image.height, 0.05)
            assertEquals(2.0, pic.w / pic.h, 0.05)
        }
    }

    @Test
    fun `filters decode Flate with predictors, LZW, ASCII85 and run lengths`() {
        val data = "Hello, PDF filters! ".repeat(20).toByteArray()
        val deflater = java.util.zip.Deflater()
        deflater.setInput(data); deflater.finish()
        val z = ByteArray(1000).let { it.copyOf(deflater.deflate(it)) }
        assertEquals(String(data), String(PdfFilters.decode(z, listOf("FlateDecode"), emptyList()).bytes))
        assertEquals("Man ", String(PdfFilters.decode("9jqo^~>".toByteArray(), listOf("ASCII85Decode"), emptyList()).bytes))
        assertEquals("AAAAB", String(PdfFilters.decode(byteArrayOf(-3, 65, 0, 66, -128), listOf("RunLengthDecode"), emptyList()).bytes))
        // LZW (the example from the PDF reference): 45 45 45 45 45 65 45 45 45 66.
        val lzw = byteArrayOf(0x80.toByte(), 0x0B, 0x60, 0x50, 0x22, 0x0C, 0x0C, 0x85.toByte(), 0x01)
        assertEquals(listOf(45, 45, 45, 45, 45, 65, 45, 45, 45, 66), PdfFilters.decode(lzw, listOf("LZWDecode"), emptyList()).bytes.map { it.toInt() })
    }

    @Test
    fun `glyph names and encodings give text`() {
        assertEquals("ﬁ", PdfEncodings.glyph("fi"))
        assertEquals("가", PdfEncodings.glyph("uniAC00"))
        assertEquals("λ", PdfEncodings.glyph("lambda"))
        assertEquals("ﬀa", PdfEncodings.glyph("ff_a"))
        assertEquals("€", PdfEncodings.WIN_ANSI[0x80])
        assertEquals("’", PdfEncodings.STANDARD[0x27])
        assertEquals("α", PdfEncodings.SYMBOL['a'.code])
        assertEquals(256, PdfEncodings.MAC_ROMAN.size)
        assertEquals("é", PdfEncodings.MAC_ROMAN[0x8E])
    }
}
