package io.github.baeseungwon1010.officecanvas.hwpx

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HwpxParserTest {

    private fun sample(name: String): File {
        val url = javaClass.classLoader?.getResource("hwpx_samples/$name")
            ?: throw AssertionError("test resource missing: hwpx_samples/$name")
        return File(url.toURI())
    }

    private fun allSamples(): List<File> {
        val dir = sample("sample.hwpx").parentFile!!
        return dir.listFiles { f -> f.extension == "hwpx" }!!.toList()
    }

    @Test
    fun `parses every sample without throwing and finds at least one page`() {
        for (file in allSamples()) {
            val doc = try {
                HwpxParser.parse(file)
            } catch (e: Exception) {
                fail("${file.name} failed to parse: $e"); return
            }
            assertTrue("${file.name}: expected at least one page", doc.pages.isNotEmpty())
            for (page in doc.pages) {
                assertTrue("${file.name}: page width should be positive", page.widthHu > 0)
                assertTrue("${file.name}: page height should be positive", page.heightHu > 0)
            }
        }
    }

    @Test
    fun `pages split where the saved line positions start over`() {
        // tools/make_sample_hwpx.py lays its lines out on three A4 pages, marking
        // each new page the way Hangul does: vertpos drops back to 0. The parser
        // has no layout engine of its own, so this is its whole page-break logic.
        val doc = HwpxParser.parse(sample("sample.hwpx"))
        assertEquals(3, doc.pages.size)
    }

    @Test
    fun `page geometry and body margins come from the section properties`() {
        val page = HwpxParser.parse(sample("sample.hwpx")).pages.first()
        assertEquals(59528L, page.widthHu)
        assertEquals(84186L, page.heightHu)
        assertEquals(8504L, page.bodyXHu) // left margin
        assertEquals(5668L + 4252L, page.bodyYHu) // top margin + header area
    }

    @Test
    fun `Korean text survives parsing, including inside tables`() {
        val doc = HwpxParser.parse(sample("sample.hwpx"))
        val elements = doc.pages.flatMap { it.elements }
        val topLevelText = elements.filterIsInstance<HwpxElement.TextRun>().joinToString(" ") { it.text }
        val tableText = elements.filterIsInstance<HwpxElement.Table>()
            .flatMap { it.cells.flatten().filterNotNull() }
            .flatMap { it.runs }
            .joinToString(" ") { it.text }
        assertTrue("expected a section heading at the top level", topLevelText.contains("문서 구조"))
        assertTrue("expected body text inside a table cell", tableText.contains("프로젝트"))
    }

    @Test
    fun `text runs never carry a null or garbled size`() {
        for (file in allSamples()) {
            val doc = HwpxParser.parse(file)
            for (page in doc.pages) {
                for (el in page.elements) {
                    val runs = when (el) {
                        is HwpxElement.TextRun -> listOf(el)
                        is HwpxElement.Table -> el.cells.flatten().filterNotNull().flatMap { it.runs }
                        else -> emptyList()
                    }
                    for (run in runs) {
                        assertTrue("${file.name}: run size ${run.sizePt} out of sane range", run.sizePt in 1f..200f)
                    }
                }
            }
        }
    }

    @Test
    fun `tables report consistent column counts`() {
        for (file in allSamples()) {
            val doc = HwpxParser.parse(file)
            for (page in doc.pages) {
                for (table in page.elements.filterIsInstance<HwpxElement.Table>()) {
                    for (row in table.cells) {
                        assertEquals(
                            "${file.name}: row width should match column count",
                            table.columnWidthsHu.size, row.size
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `cells without margins of their own take the table's, and empty rows keep their height`() {
        val table = HwpxParser.parse(sample("sample.hwpx")).pages.flatMap { it.elements }
            .filterIsInstance<HwpxElement.Table>().single()
        // hasMargin="0" with an unset <hp:cellMargin>: the table's 510 / 141.
        val first = table.cells[0][0]!!.runs.first()
        assertEquals(510L, first.xHu)
        assertEquals(141L, first.yHu)
        // The last row is empty: it is as tall as its cells declare.
        assertEquals(4, table.rowHeightsHu.size)
        assertTrue("${table.rowHeightsHu}", table.rowHeightsHu.last() >= 2000)
    }

    @Test
    fun `a NARROWLY page is laid out sideways`() {
        fun doc(landscape: String): HwpxDoc {
            val f = File.createTempFile("page", ".hwpx").apply { deleteOnExit() }
            java.util.zip.ZipOutputStream(f.outputStream()).use { z ->
                fun put(name: String, text: String) { z.putNextEntry(java.util.zip.ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
                put("mimetype", "application/hwp+zip")
                put("Contents/content.hpf", """<opf:package xmlns:opf="http://www.idpf.org/2007/opf/"><opf:manifest><opf:item id="s" href="Contents/section0.xml"/></opf:manifest><opf:spine><opf:itemref idref="s"/></opf:spine></opf:package>""")
                put("Contents/header.xml", """<hh:head xmlns:hh="http://www.hancom.co.kr/hwpml/2011/head"/>""")
                put("Contents/section0.xml", """<hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section" xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph"><hp:p><hp:run><hp:secPr><hp:pagePr landscape="$landscape" width="59528" height="84186"><hp:margin left="2267" right="2267" top="1417" bottom="1417" header="0" footer="0" gutter="0"/></hp:pagePr></hp:secPr><hp:t>x</hp:t></hp:run><hp:linesegarray><hp:lineseg textpos="0" vertpos="0" vertsize="1000" textheight="1000" baseline="850" spacing="600" horzpos="0" horzsize="79652"/></hp:linesegarray></hp:p></hs:sec>""")
            }
            return HwpxParser.parse(f)
        }
        // A real form saved as NARROWLY 59528 x 84186 had its lines 79652 wide:
        // 84186 less the two side margins.
        val sideways = doc("NARROWLY").pages.single()
        assertEquals(84186L to 59528L, sideways.widthHu to sideways.heightHu)
        val upright = doc("WIDELY").pages.single()
        assertEquals(59528L to 84186L, upright.widthHu to upright.heightHu)
    }
}
