package io.github.baeseungwon1010.officecanvas.pptx

import io.github.baeseungwon1010.officecanvas.slide.*

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Runs the parser against decks saved by a real writer (python-pptx, see
 * tools/make_sample_deck.py and tools/make_feature_deck.py) instead of XML written
 * by hand to match the parser's own assumptions: relationship indirection, theme
 * inheritance and layouts all come from the writer, not from this test.
 */
class PptxParserTest {

    private fun sample(name: String): File {
        val url = javaClass.classLoader.getResource("pptx_samples/$name")
            ?: throw AssertionError("test resource missing: pptx_samples/$name")
        return File(url.toURI())
    }

    private fun allSamples(): List<File> =
        File(sample("sample_deck.pptx").parentFile.path).listFiles { f -> f.extension == "pptx" }!!.toList()

    @Test
    fun `parses every sample without throwing and finds at least one slide`() {
        for (file in allSamples()) {
            val deck = try {
                PptxParser.parse(file)
            } catch (e: Exception) {
                fail("${file.name} failed to parse: $e"); return
            }
            assertTrue("${file.name}: expected at least one slide", deck.slides.isNotEmpty())
            assertTrue("${file.name}: width should be positive", deck.widthEmu > 0)
            assertTrue("${file.name}: height should be positive", deck.heightEmu > 0)
        }
    }

    @Test
    fun `slide order follows the presentation's slide list, not zip entry order`() {
        // sample_deck's slides, in order: title, cards, table, picture, closing.
        val deck = PptxParser.parse(sample("sample_deck.pptx"))
        assertEquals(5, deck.slides.size)
        fun text(i: Int) = deck.slides[i].elements.filterIsInstance<Element.Shape>()
            .flatMap { it.text?.paragraphs.orEmpty() }.joinToString(" ") { it.plainText }
        assertTrue("first slide should be the title slide", text(0).contains("샘플 발표 자료"))
        assertTrue("last slide should be the closing slide", text(4).contains("감사합니다"))
    }

    @Test
    fun `every picture reference points at an entry that actually exists in the zip`() {
        for (file in allSamples()) {
            val deck = PptxParser.parse(file)
            java.util.zip.ZipFile(file).use { zip ->
                for (slide in deck.slides) {
                    for (pic in slide.elements.filterIsInstance<Element.Picture>().filter { it.imageEntry != null }) {
                        assertNotNull(
                            "${file.name}: picture points at missing entry ${pic.imageEntry}",
                            zip.getEntry(pic.imageEntry)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `text runs never carry a null or garbled size`() {
        for (file in allSamples()) {
            val deck = PptxParser.parse(file)
            for (slide in deck.slides) {
                for (shape in slide.elements.filterIsInstance<Element.Shape>()) {
                    for (p in shape.text?.paragraphs.orEmpty()) {
                        for (run in p.runs) {
                            assertTrue("${file.name}: run size ${run.sizePt} out of sane range", run.sizePt in 1f..400f)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `tables report consistent column counts`() {
        for (file in allSamples()) {
            val deck = PptxParser.parse(file)
            for (slide in deck.slides) {
                for (table in slide.elements.filterIsInstance<Element.Table>()) {
                    for (row in table.cells) {
                        assertEquals(
                            "${file.name}: row width should match grid column count",
                            table.columnWidthsEmu.size, row.size
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `boxes stay within a sane multiple of slide bounds`() {
        // Grouped/rotated shapes can legitimately extend past the slide edge, so this
        // is a sanity bound against a broken group transform, not a strict clip check.
        for (file in allSamples()) {
            val deck = PptxParser.parse(file)
            val w = deck.widthEmu; val h = deck.heightEmu
            for (slide in deck.slides) {
                for (el in slide.elements) {
                    val b = el.box
                    assertTrue("${file.name}: element x=${b.x} far outside slide width $w", b.x > -w * 2 && b.x < w * 3)
                    assertTrue("${file.name}: element y=${b.y} far outside slide height $h", b.y > -h * 2 && b.y < h * 3)
                }
            }
        }
    }
}
