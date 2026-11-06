package io.github.baeseungwon1010.officecanvas.pdf

import io.github.baeseungwon1010.officecanvas.ConvertFormat
import io.github.baeseungwon1010.officecanvas.OfficeCanvasException
import io.github.baeseungwon1010.officecanvas.PdfConverter
import io.github.baeseungwon1010.officecanvas.docx.DocxParser
import io.github.baeseungwon1010.officecanvas.hwp.HwpParser
import io.github.baeseungwon1010.officecanvas.hwpx.DrawingKind
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxElement
import io.github.baeseungwon1010.officecanvas.ole.Ole2Reader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.ZipFile

/**
 * Converts pdf_samples/sample.pdf (LibreOffice's export of docx_samples/sample.docx)
 * and reads the result back: the DOCX with this library's own Word reader, the
 * HWPX by its XML, the HWP with its own Hangul reader.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfConverterTest {

    private fun file(name: String) = File(javaClass.classLoader!!.getResource("pdf_samples/$name")!!.toURI())

    private fun convert(name: String, format: ConvertFormat): File {
        val out = File.createTempFile("conv", "." + format.name.lowercase()).apply { deleteOnExit() }
        assertEquals(3, PdfConverter.convert(file(name), out, format))
        return out
    }

    private fun texts(els: List<HwpxElement>): List<String> = els.flatMap {
        when (it) {
            is HwpxElement.TextRun -> listOf(it.text)
            is HwpxElement.Table -> it.cells.flatten().filterNotNull().flatMap { c -> texts(c.elements) }
            is HwpxElement.Drawing -> texts(it.content)
            else -> emptyList()
        }
    }

    @Test
    fun `DOCX keeps the pages, text, table, picture and header and footer`() {
        val doc = DocxParser.parse(convert("sample.pdf", ConvertFormat.DOCX))
        try {
            assertEquals(3, doc.pages.size)
            val page1 = texts(doc.pages[0].elements).joinToString("\n")
            assertTrue(page1, "DOCX sample" in page1)
            assertTrue(page1, page1.replace(" ", "").contains("한글문단은띄어쓰기에서"))
            // The header repeated on every page went into the header, the page
            // number in the footer became a field (so it reads 2 on page 2).
            for ((i, p) in doc.pages.withIndex()) {
                val runs = p.elements.filterIsInstance<HwpxElement.TextRun>()
                assertTrue(runs.any { it.text.contains("OfficeCanvas sample header") && it.yHu < 0 })
                assertTrue(runs.any { it.text.trim() == "Page ${i + 1}" })
            }
            val all = doc.pages.flatMap { it.elements }
            // The table: three columns, a cell merged across and one merged down, a shaded cell.
            val table = all.filterIsInstance<HwpxElement.Table>().single { it.columnWidthsHu.size == 3 }
            val cells = table.cells.flatten().filterNotNull()
            assertTrue(cells.any { it.colSpan == 2 })
            assertTrue(cells.any { it.rowSpan == 3 })
            assertTrue(cells.any { it.borderFill?.fillArgb == 0xFFD9E2F3.toInt() })
            assertTrue("Merged across" in texts(listOf(table)).joinToString(" "))
            // The shaded paragraph, and the picture.
            assertTrue(all.any { it is HwpxElement.Drawing && it.kind == DrawingKind.RECTANGLE && it.fillArgb == 0xFFFFF2CC.toInt() })
            assertTrue(all.any { it is HwpxElement.ImageEl })
            // Bold, italic and red runs.
            val runs = all.filterIsInstance<HwpxElement.TextRun>().flatMap { it.spans }
            assertTrue(runs.any { it.text.startsWith("Bold") && it.style.bold })
            assertTrue(runs.any { it.text.startsWith("italic") && it.style.italic })
            assertTrue(runs.any { it.text.startsWith("red") && it.style.colorArgb == 0xFFC00000.toInt() })
        } finally {
            doc.resources?.close()
        }
    }

    @Test
    fun `HWPX has the same content in Hangul's structure`() {
        ZipFile(convert("sample.pdf", ConvertFormat.HWPX)).use { z ->
            assertEquals("mimetype", z.entries().nextElement().name)
            assertEquals("application/hwp+zip", String(z.getInputStream(z.getEntry("mimetype")).readBytes()))
            val section = String(z.getInputStream(z.getEntry("Contents/section0.xml")).readBytes())
            assertTrue("DOCX sample" in section)
            assertTrue(section.contains("colCnt=\"3\""))
            assertTrue(section.contains("colSpan=\"2\"") && section.contains("rowSpan=\"3\""))
            assertTrue(section.contains("<hp:header") && section.contains("numType=\"PAGE\""))
            val header = String(z.getInputStream(z.getEntry("Contents/header.xml")).readBytes())
            assertTrue(header.contains("faceColor=\"#D9E2F3\""))
            assertTrue(z.entries().toList().any { it.name.startsWith("BinData/") })
            val hpf = String(z.getInputStream(z.getEntry("Contents/content.hpf")).readBytes())
            assertTrue(hpf.contains("BinData/image1"))
        }
    }

    @Test
    fun `HWP opens with the library's own Hangul reader`() {
        val out = convert("sample.pdf", ConvertFormat.HWP)
        Ole2Reader.open(out).use { ole ->
            for (s in listOf("FileHeader", "DocInfo", "BodyText/Section0", "BinData/BIN0001.png")) assertTrue(s, ole.hasStream(s) || s.startsWith("BinData") && ole.hasStream("BinData/BIN0001.jpg"))
            assertEquals("HWP Document File", String(ole.readStream("FileHeader")!!, 0, 17, Charsets.US_ASCII))
        }
        val doc = HwpParser.parse(out)
        try {
            val all = texts(doc.pages.flatMap { it.elements }).joinToString("\n")
            assertTrue(all, "DOCX sample" in all)
            assertTrue(all, "Merged across" in all)
            val tables = doc.pages.flatMap { it.elements }.filterIsInstance<HwpxElement.Table>()
            assertTrue(tables.any { t -> t.columnWidthsHu.size == 3 && t.cells.flatten().filterNotNull().any { it.colSpan == 2 } })
        } finally {
            doc.resources?.close()
        }
    }

    private fun all(els: List<HwpxElement>): List<HwpxElement> = els.flatMap {
        listOf(it) + when (it) {
            is HwpxElement.Table -> it.cells.flatten().filterNotNull().flatMap { c -> all(c.elements) }
            is HwpxElement.Drawing -> all(it.content)
            else -> emptyList()
        }
    }

    @Test
    fun `columns stay side by side, pictures in a row stay in a row`() {
        val out = File.createTempFile("conv", ".docx").apply { deleteOnExit() }
        assertEquals(1, PdfConverter.convert(file("layout.pdf"), out, ConvertFormat.DOCX))
        val doc = DocxParser.parse(out)
        try {
            // Everything still fits on its one page.
            assertEquals(1, doc.pages.size)
            val els = all(doc.pages[0].elements)
            val lines = els.filterIsInstance<HwpxElement.TextRun>().map { it.text }
            // The columns share baselines across a 1.2 em gutter: no line runs from one into the other.
            assertTrue(lines.joinToString("\n"), lines.none { "west" in it && "east" in it })
            assertTrue(lines.any { "west" in it } && lines.any { "east" in it })
            val tables = els.filterIsInstance<HwpxElement.Table>()
            assertTrue(tables.any { t -> t.columnWidthsHu.size == 2 && texts(listOf(t)).joinToString(" ").let { "west" in it && "east" in it } })
            // The three touching pictures share a row; the shadow is gone; the chart's empty grid isn't a table.
            assertEquals(3, els.count { it is HwpxElement.ImageEl })
            assertTrue(tables.any { t -> t.cells.size == 1 && t.cells[0].count { c -> c != null && c.elements.any { it is HwpxElement.ImageEl } } == 3 })
            assertTrue(tables.none { it.columnWidthsHu.size * it.rowHeightsHu.size >= 12 })
            // The right tab stop keeps its line.
            assertTrue(lines.joinToString("\n"), lines.any { "Left" in it && "Right" in it })
        } finally {
            doc.resources?.close()
        }
    }

    @Test
    fun `tables without rules down are tables, a list and pairs of words are not`() {
        val out = File.createTempFile("conv", ".docx").apply { deleteOnExit() }
        assertEquals(1, PdfConverter.convert(file("tables.pdf"), out, ConvertFormat.DOCX))
        val doc = DocxParser.parse(out)
        try {
            // (A table of one row is how side-by-side columns are written.)
            val tables = all(doc.pages[0].elements).filterIsInstance<HwpxElement.Table>().filter { it.rowHeightsHu.size > 1 }
            fun cellTexts(t: HwpxElement.Table) = t.cells.map { row -> row.map { c -> c?.let { texts(it.elements).joinToString(" ").replace(Regex("\\s+"), " ").trim() } ?: "" } }
            assertEquals(tables.map { cellTexts(it) }.toString(), 2, tables.size)
            // Three columns, five rows: the wrapped cell stays in its row.
            val three = cellTexts(tables.single { it.columnWidthsHu.size == 3 })
            assertEquals(5, three.size)
            assertEquals(listOf("Item", "Size", "Meaning"), three[0].filter { it.isNotEmpty() })
            assertTrue(three.toString(), three[2].any { "set bits are known" in it && "(the rest unknown)" in it })
            assertTrue(three.toString(), three[4].containsAll(listOf("ct", "2048 bit", "ciphertext")))
            // The rule under the heading is the first row's bottom border.
            val head = tables.single { it.columnWidthsHu.size == 3 }.cells[0].filterNotNull()
            assertTrue(head.all { (it.borderFill?.bottom?.widthHu ?: 0) > 0 })
            val two = cellTexts(tables.single { it.columnWidthsHu.size == 2 })
            assertEquals(listOf("0110", "subtract"), two.last())
            // The list and the pairs of words aren't tables of rows.
            val lines = all(doc.pages[0].elements).filterIsInstance<HwpxElement.TextRun>().map { it.text }
            assertTrue(lines.toString(), lines.any { "first point" in it } && lines.any { "Room" in it })
        } finally {
            doc.resources?.close()
        }
    }

    @Test
    fun `an encrypted PDF converts like the plain one`() {
        val plain = DocxParser.parse(convert("sample.pdf", ConvertFormat.DOCX))
        val aes = DocxParser.parse(convert("sample_aes256.pdf", ConvertFormat.DOCX))
        try {
            assertEquals(texts(plain.pages.flatMap { it.elements }), texts(aes.pages.flatMap { it.elements }))
        } finally {
            plain.resources?.close(); aes.resources?.close()
        }
    }

    @Test
    fun `a PDF that needs a password, or isn't one, is refused`() {
        for (name in listOf("sample_locked.pdf")) {
            try {
                PdfConverter.convert(file(name), File.createTempFile("conv", ".docx").apply { deleteOnExit() }, ConvertFormat.DOCX)
                fail("converted $name")
            } catch (e: OfficeCanvasException) {
                assertTrue(e.message!!, "password" in e.message!!)
            }
        }
        val notPdf = File.createTempFile("conv", ".pdf").apply { deleteOnExit(); writeText("hello") }
        try {
            PdfConverter.convert(notPdf, File.createTempFile("conv", ".docx").apply { deleteOnExit() }, ConvertFormat.DOCX)
            fail("converted a text file")
        } catch (e: OfficeCanvasException) {
        }
    }
}
