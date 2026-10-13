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
}
