package io.github.baeseungwon1010.officecanvas

import io.github.baeseungwon1010.officecanvas.hwpx.HwpxParser
import io.github.baeseungwon1010.officecanvas.pptx.PptxParser
import io.github.baeseungwon1010.officecanvas.slide.Element
import io.github.baeseungwon1010.officecanvas.util.SafeXml
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Parses with Android's own XML parser (org.apache.harmony.xml.parsers, shipped in
 * Robolectric's android-all) instead of the JDK's Xerces that unit tests otherwise
 * get. Android's parser rejects the hardening features Xerces understands, which
 * once made every XML part fail to parse on devices while all tests passed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidXmlParserTest {

    private val property = "javax.xml.parsers.DocumentBuilderFactory"
    private var previous: String? = null

    @Before
    fun useAndroidParser() {
        previous = System.getProperty(property)
        System.setProperty(property, "org.apache.harmony.xml.parsers.DocumentBuilderFactoryImpl")
        assertEquals(
            "org.apache.harmony.xml.parsers.DocumentBuilderFactoryImpl",
            javax.xml.parsers.DocumentBuilderFactory.newInstance().javaClass.name
        )
    }

    @After
    fun restore() {
        previous?.let { System.setProperty(property, it) } ?: System.clearProperty(property)
    }

    private fun resource(path: String) = File(javaClass.classLoader!!.getResource(path)!!.toURI())

    @Test
    fun `pptx parses`() {
        val deck = PptxParser.parse(resource("pptx_samples/sample_deck.pptx"))
        assertEquals(5, deck.slides.size)
        val text = deck.slides[0].elements.filterIsInstance<Element.Shape>().mapNotNull { it.text }
            .flatMap { it.paragraphs }.joinToString(" ") { it.plainText }
        assertTrue(text, "샘플 발표 자료" in text)
    }

    @Test
    fun `hwpx parses`() {
        assertEquals(3, HwpxParser.parse(resource("hwpx_samples/sample.hwpx")).pages.size)
    }

    @Test
    fun `svg and namespace-unaware parsing work`() {
        val doc = SafeXml.parse("<svg viewBox=\"0 0 1 1\"><rect/></svg>".toByteArray(), namespaceAware = false)
        assertEquals("svg", doc!!.documentElement.tagName)
    }

    @Test
    fun `DOCTYPE is still refused`() {
        assertNull(SafeXml.parse("<!DOCTYPE r [<!ENTITY a \"x\">]><r>&a;</r>".toByteArray()))
    }
}
