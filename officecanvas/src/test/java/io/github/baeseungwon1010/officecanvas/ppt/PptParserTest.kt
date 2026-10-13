package io.github.baeseungwon1010.officecanvas.ppt

import android.graphics.BitmapFactory
import android.graphics.Color
import io.github.baeseungwon1010.officecanvas.slide.Element
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

// Robolectric only for the picture test below (BitmapFactory needs a real
// Android runtime); NATIVE graphics mode isn't needed since decoding bytes
// into a Bitmap.Options bounds check doesn't touch Canvas rasterization.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PptParserTest {

    private fun sample(name: String): File {
        val url = javaClass.classLoader?.getResource("ppt_samples/$name")
            ?: throw AssertionError("test resource missing: ppt_samples/$name")
        return File(url.toURI())
    }

    private fun allSamples(): List<File> {
        val dir = sample("sample_deck.ppt").parentFile!!
        return dir.listFiles { f -> f.extension == "ppt" }!!.toList()
    }

    @Test
    fun `parses every sample without throwing and finds at least one slide`() {
        for (file in allSamples()) {
            val deck = try {
                PptParser.parse(file)
            } catch (e: Exception) {
                fail("${file.name} failed to parse: $e"); return
            }
            assertTrue("${file.name}: expected at least one slide", deck.slides.isNotEmpty())
            assertTrue("${file.name}: width should be positive", deck.widthEmu > 0)
            assertTrue("${file.name}: height should be positive", deck.heightEmu > 0)
        }
    }

    @Test
    fun `slide size matches the known 16by9 conversion source`() {
        // sample_deck.pptx is 13.333 x 7.5 in; LibreOffice keeps that in the .ppt.
        val deck = PptParser.parse(sample("sample_deck.ppt"))
        val ratio = deck.widthEmu.toDouble() / deck.heightEmu
        assertEquals(16.0 / 9.0, ratio, 0.01)
    }

    @Test
    fun `known slide text survives parsing at roughly the right position`() {
        val deck = PptParser.parse(sample("sample_deck.ppt"))
        val firstSlideText = deck.slides.first().elements
            .filterIsInstance<Element.Shape>()
            .mapNotNull { it.text }
            .flatMap { it.paragraphs }
            .joinToString(" ") { it.plainText }
        assertTrue("expected 'OfficeCanvas 데모' on the title slide", firstSlideText.contains("OfficeCanvas 데모"))
        assertTrue("expected '샘플 발표 자료' on the title slide", firstSlideText.contains("샘플 발표 자료"))

        // The sidebar: a full-height narrow rectangle at the left edge, filled
        // with #3A5BA0 (tools/make_sample_deck.py).
        val sidebar = deck.slides.first().elements.filterIsInstance<Element.Shape>()
            .firstOrNull { it.box.w < deck.widthEmu / 10 && it.box.h > deck.heightEmu * 0.9 }
        assertNotNull("expected a full-height sidebar-shaped element on the title slide", sidebar)
        assertEquals(0xFF3A5BA0.toInt(), sidebar!!.fillArgb)
    }

    @Test
    fun `slide background color is read`() {
        // sample_deck's slides sit on #F5F4F0; the closing one on a dark blue.
        val deck = PptParser.parse(sample("sample_deck.ppt"))
        assertEquals(0xFFF5F4F0.toInt(), deck.slides.first().backgroundArgb)
        val last = deck.slides.last()
        assertNotNull("last slide should have its own background", last.backgroundArgb)
        assertTrue("last slide's background should be dark, was ${Integer.toHexString(last.backgroundArgb!!)}",
            Color.luminance(last.backgroundArgb!!) < 0.3f)
    }

    @Test
    fun `font sizes are read per run instead of a hardcoded 18pt`() {
        // The title slide has an 18pt label ("OfficeCanvas 데모") above a 48pt
        // headline ("샘플 발표 자료") — differing sizes a hardcoded-18pt parser
        // could never reproduce.
        // Runs split wherever character formatting changes, so a phrase can span
        // several runs; check every run of the paragraph that holds it.
        val deck = PptParser.parse(sample("sample_deck.ppt"))
        val paragraphs = deck.slides.first().elements
            .filterIsInstance<Element.Shape>()
            .mapNotNull { it.text }
            .flatMap { it.paragraphs }

        val title = paragraphs.firstOrNull { it.plainText.contains("OfficeCanvas 데모") }
        val headline = paragraphs.firstOrNull { it.plainText.contains("샘플 발표 자료") }
        assertNotNull("expected a paragraph containing the title text", title)
        assertNotNull("expected a paragraph containing the headline text", headline)
        val titleSizes = title!!.runs.filter { it.text.isNotBlank() }.map { it.sizePt }
        val headlineSizes = headline!!.runs.filter { it.text.isNotBlank() }.map { it.sizePt }
        assertTrue("title runs should be roughly 18pt, were $titleSizes", titleSizes.all { it in 14f..22f })
        assertTrue("headline runs should be roughly 48pt, were $headlineSizes", headlineSizes.all { it in 40f..56f })
    }

    @Test
    fun `pictures decode to non-empty bitmaps at roughly the right position`() {
        // sample_deck's fourth slide holds one PNG, so the blip store / FBSE /
        // foDelay reading (see PptParser's class doc) runs end to end on a file
        // LibreOffice wrote, not on bytes laid out by this test.
        val deck = PptParser.parse(sample("sample_deck.ppt"))
        val pictures = deck.slides[3].elements.filterIsInstance<Element.Picture>()
        assertEquals("expected exactly one picture on the slide", 1, pictures.size)

        val pic = pictures.first()
        assertNotNull("picture should carry decoded bytes, not a zip entry reference", pic.imageBytes)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(pic.imageBytes, 0, pic.imageBytes!!.size, opts)
        assertTrue("picture bytes should decode to a real image", opts.outWidth > 0 && opts.outHeight > 0)

        // Placed at 1in/2in on a 13.333x7.5in slide — roughly the left half,
        // vertically past the midpoint.
        assertTrue("picture should be positioned in the left half", pic.box.x < deck.widthEmu / 2)
        assertTrue("picture should be positioned past the vertical midpoint", pic.box.y > deck.heightEmu / 4)
    }

    @Test
    fun `boxes stay within a sane multiple of slide bounds`() {
        for (file in allSamples()) {
            val deck = PptParser.parse(file)
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
