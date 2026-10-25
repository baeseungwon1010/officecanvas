package io.github.baeseungwon1010.officecanvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import io.github.baeseungwon1010.officecanvas.hwp.HwpParser
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxRenderer
import io.github.baeseungwon1010.officecanvas.metafile.EmfRenderer
import io.github.baeseungwon1010.officecanvas.metafile.SvgRenderer
import io.github.baeseungwon1010.officecanvas.metafile.TiffDecoder
import io.github.baeseungwon1010.officecanvas.metafile.WmfRenderer
import io.github.baeseungwon1010.officecanvas.ole.Ole2Reader
import io.github.baeseungwon1010.officecanvas.ppt.PptGeometry
import io.github.baeseungwon1010.officecanvas.ppt.Rec
import io.github.baeseungwon1010.officecanvas.ppt.RecordReader
import io.github.baeseungwon1010.officecanvas.ppt.formatNumber
import io.github.baeseungwon1010.officecanvas.ppt.readStyleTextProp9
import io.github.baeseungwon1010.officecanvas.util.SafeBitmaps
import io.github.baeseungwon1010.officecanvas.util.SafeXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Crafted inputs that used to crash, hang or exhaust memory, each a few bytes to a
 * few kilobytes. Every one must now finish quickly, and anything thrown out of the
 * public API must be an OfficeCanvasException. Runs on a thread with a 1 MB stack,
 * close to an Android background thread's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaliciousInputTest {

    /** Runs [block] with a time limit; returns what it threw, failing on a hang or
     *  on anything but an OfficeCanvasException. */
    private fun survives(seconds: Long = 20, block: () -> Unit): Throwable? {
        var thrown: Throwable? = null
        val t = Thread(null, { try { block() } catch (e: Throwable) { thrown = e } }, "probe", 1L shl 20)
        t.isDaemon = true
        t.start()
        t.join(seconds * 1000)
        if (t.isAlive) fail("still running after ${seconds}s")
        thrown?.let { if (it !is OfficeCanvasException) throw AssertionError("escaped: $it", it) }
        return thrown
    }

    private fun render(file: File, longSide: Int = OfficeCanvas.DEFAULT_LONG_SIDE_PX, onPage: (Bitmap) -> Unit = {}) =
        OfficeCanvas.render(RuntimeEnvironment.getApplication(), file, longSide) { _, b -> onPage(b); b.recycle() }

    private fun le(n: Int) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN)
    private fun temp(bytes: ByteArray, suffix: String) = File.createTempFile("evil", suffix).apply { writeBytes(bytes); deleteOnExit() }

    // ---- OLE2 / .ppt --------------------------------------------------------

    private fun ole2(sectors: Int, fill: ByteBuffer.() -> Unit): ByteArray = le(512 * (sectors + 1)).apply {
        put(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))
        putShort(30, 9); putInt(48, -2); putInt(60, -2); putInt(68, -2)
        for (i in 0 until 109) putInt(76 + i * 4, -1)
        fill()
    }.array()

    @Test
    fun `an OLE2 header claiming two million FAT sectors`() {
        val file = temp(ole2(1) { putInt(44, 0x00200000) }, ".ppt")
        val e = survives { render(file) }
        assertTrue(e is OfficeCanvasException)
    }

    @Test
    fun `a DIFAT sector that points at itself`() {
        // Sector 0 is a DIFAT sector whose "next" link is sector 0 again.
        val file = temp(ole2(1) {
            putInt(68, 0); putInt(72, Int.MAX_VALUE)
            for (i in 0 until 127) putInt(512 + i * 4, -1)
            putInt(512 + 127 * 4, 0)
        }, ".ppt")
        survives { Ole2Reader.open(file).close() }
    }

    @Test
    fun `a FAT chain that loops`() {
        // Sector 0 is the FAT and says sector 1 is followed by sector 1.
        val file = temp(ole2(2) {
            putInt(76, 0); putInt(48, 1)
            putInt(512, -3); putInt(516, 1)
        }, ".ppt")
        survives { Ole2Reader.open(file).close() }
    }

    @Test
    fun `StyleTextProp9 with a negative smart-tag count`() {
        val atom = le(28).apply {
            putInt(8, 0) // PF9 masks
            putInt(12, 0) // CF9 masks
            putInt(16, 1 shl 9) // SI: smartTags present
            putInt(20, -4) // count pointing backwards
        }.array()
        survives(5) { readStyleTextProp9(RecordReader(atom), Rec(0, 0, 0, false, 8, atom.size)) }
    }

    @Test
    fun `lettered numbering past 26 squared falls back to digits`() {
        assertEquals("a.", formatNumber(0, 1))
        assertEquals("aa.", formatNumber(0, 27))
        assertEquals("100000.", formatNumber(0, 100_000))
    }

    @Test
    fun `freeform with many segments and few vertices`() {
        val vertices = le(6 + 8).apply { putShort(0, 2); putShort(2, 2); putShort(4, 4) }.array()
        val segments = le(6 + 2 * 65535).apply {
            putShort(0, 65535.toShort()); putShort(2, 65535.toShort()); putShort(4, 2)
            for (i in 0 until 65535) putShort(6 + 2 * i, 0x1FFF) // lineTo x 8191
        }.array()
        survives(5) { PptGeometry.freeform(vertices, segments, null) }
    }

    // ---- metafiles and TIFF -------------------------------------------------

    @Test
    fun `a raw WMF record whose length wraps to zero`() {
        val wmf = le(24).apply { putShort(0, 1); putShort(2, 9); putInt(18, 0x80000000.toInt()); putShort(22, 1) }.array()
        survives(5) { assertNull(WmfRenderer.fromRaw(wmf)) }
    }

    private fun emf(vararg records: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(le(108).apply { putInt(0, 1); putInt(4, 108); putInt(16, 100); putInt(20, 100); putInt(40, 0x464D4520) }.array())
        records.forEach { out.write(it) }
        out.write(le(20).apply { putInt(0, 14); putInt(4, 20) }.array())
        return out.toByteArray()
    }

    private fun drawEmf(bytes: ByteArray) {
        val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        EmfRenderer.draw(Canvas(bmp), bytes, RectF(0f, 0f, 100f, 100f))
        bmp.recycle()
    }

    @Test
    fun `an EMF polyline claiming a billion points`() {
        val poly = le(36).apply { putInt(0, 4); putInt(4, 36); putInt(24, 0x3FFFFFFF) }.array()
        survives(5) { drawEmf(emf(poly)) }
    }

    @Test
    fun `an EMF bitmap record claiming two gigabytes of pixels`() {
        // EMR_STRETCHDIBITS with cbBmi/cbBits far beyond the record.
        val rec = le(120).apply {
            putInt(0, 81); putInt(4, 120)
            putInt(48, 80); putInt(52, 40); putInt(56, 120); putInt(60, 0x7FFFFF00)
        }.array()
        survives(5) { drawEmf(emf(rec)) }
    }

    @Test
    fun `many tiny EMF poly records`() {
        val poly = le(28 + 8).apply { putInt(0, 4); putInt(4, 36); putInt(24, 1) }.array()
        survives(20) { drawEmf(emf(*Array(200_000) { poly })) }
    }

    @Test
    fun `a TIFF tag claiming two billion values`() {
        val tiff = le(64).apply {
            put(0, 'I'.code.toByte()); put(1, 'I'.code.toByte()); putShort(2, 42); putInt(4, 8)
            putShort(8, 1); putShort(10, 256); putShort(12, 4); putInt(14, 0x7FFFFFF0); putInt(18, 30)
        }.array()
        survives(5) { assertNull(TiffDecoder.toBmp(tiff)) }
    }

    @Test
    fun `a TIFF with forty-eight samples per pixel`() {
        val tiff = le(128).apply {
            put(0, 'I'.code.toByte()); put(1, 'I'.code.toByte()); putShort(2, 42); putInt(4, 8)
            putShort(8, 4)
            fun tag(i: Int, tag: Int, value: Int) { val e = 10 + i * 12; putShort(e, tag.toShort()); putShort(e + 2, 4); putInt(e + 4, 1); putInt(e + 8, value) }
            tag(0, 256, 1); tag(1, 257, 4_000_000); tag(2, 277, 48); tag(3, 273, 100)
        }.array()
        survives(5) { assertNull(TiffDecoder.toBmp(tiff)) }
    }

    @Test
    fun `SVG nested ten thousand groups deep`() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\">" + "<g>".repeat(10_000) +
            "<rect width=\"10\" height=\"10\"/>" + "</g>".repeat(10_000) + "</svg>"
        val bmp = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        survives(10) {
            try {
                SvgRenderer.draw(Canvas(bmp), svg.toByteArray(), RectF(0f, 0f, 10f, 10f))
            } catch (e: StackOverflowError) {
                // The platform's DOM builder may itself recurse; the renderer turns
                // this into skipping the picture (see SlideRenderer), not a crash.
            }
        }
    }

    @Test
    fun `SVG with a huge brace-less style block and transform`() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><style>" + "a".repeat(200_000) +
            "</style><g transform=\"" + "x".repeat(200_000) + "\"><rect width=\"10\" height=\"10\"/></g></svg>"
        val bmp = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        survives(5) { SvgRenderer.draw(Canvas(bmp), svg.toByteArray(), RectF(0f, 0f, 10f, 10f)) }
    }

    /** A 1-bit grayscale PNG of [w] x [h] zero pixels: tiny on disk, huge decoded. */
    private fun blankPng(w: Int, h: Int): ByteArray {
        fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
            out.write(ByteBuffer.allocate(4).putInt(data.size).array())
            val td = type.toByteArray() + data
            out.write(td)
            out.write(ByteBuffer.allocate(4).putInt(CRC32().apply { update(td) }.value.toInt()).array())
        }
        val raw = ByteArray(h * (1 + (w + 7) / 8))
        val deflater = Deflater(9).apply { setInput(raw); finish() }
        val z = ByteArrayOutputStream()
        val buf = ByteArray(1 shl 16)
        while (!deflater.finished()) z.write(buf, 0, deflater.deflate(buf))
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        chunk(out, "IHDR", ByteBuffer.allocate(13).putInt(w).putInt(h).put(1).put(0).put(0).put(0).put(0).array())
        chunk(out, "IDAT", z.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    @Test
    fun `a very wide PNG drawn into a box of another shape stays within the pixel budget`() {
        val png = blankPng(100_000, 4_000)
        val bmp = SafeBitmaps.decode(png, 1600f, 900f)
        if (bmp != null) {
            assertTrue("${bmp.width}x${bmp.height}", bmp.width.toLong() * bmp.height <= SafeBitmaps.MAX_PIXELS)
            bmp.recycle()
        }
    }

    // ---- zip formats --------------------------------------------------------

    private fun zip(vararg entries: Pair<String, String>): File {
        val f = File.createTempFile("evil", ".zip").apply { deleteOnExit() }
        ZipOutputStream(f.outputStream()).use { z ->
            for ((name, text) in entries) { z.putNextEntry(ZipEntry(name)); z.write(text.toByteArray()); z.closeEntry() }
        }
        return f
    }

    private fun hwpx(section: String) = zip(
        "mimetype" to "application/hwp+zip",
        "Contents/content.hpf" to """<opf:package xmlns:opf="http://www.idpf.org/2007/opf/"><opf:manifest><opf:item id="s" href="Contents/section0.xml"/></opf:manifest><opf:spine><opf:itemref idref="s"/></opf:spine></opf:package>""",
        "Contents/header.xml" to """<hh:head xmlns:hh="http://www.hancom.co.kr/hwpml/2011/head"/>""",
        "Contents/section0.xml" to section
    )

    private val hs = """xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section" xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph""""

    @Test
    fun `an HWPX table declaring fifty thousand rows and columns`() {
        val file = hwpx("""<hs:sec $hs><hp:p><hp:run><hp:tbl rowCnt="50000" colCnt="50000"><hp:sz width="1000" height="1000"/><hp:tr><hp:tc><hp:cellAddr colAddr="0" rowAddr="0"/></hp:tc></hp:tr></hp:tbl></hp:run><hp:linesegarray><hp:lineseg textpos="0" vertpos="0" horzsize="100"/></hp:linesegarray></hp:p></hs:sec>""")
        survives(10) { render(file) }
    }

    @Test
    fun `an HWPX page with negative size does not become a huge bitmap`() {
        val file = hwpx("""<hs:sec $hs><hp:p><hp:run><hp:secPr><hp:pagePr width="-1" height="-50"/></hp:secPr><hp:t>x</hp:t></hp:run><hp:linesegarray><hp:lineseg textpos="0" vertpos="0" horzsize="100"/></hp:linesegarray></hp:p></hs:sec>""")
        survives(10) { render(file) { assertTrue("${it.width}x${it.height}", maxOf(it.width, it.height) <= OfficeCanvas.DEFAULT_LONG_SIDE_PX) } }
    }

    @Test
    fun `an HWPX section that starts a new page on every line stops at the page limit`() {
        val lines = (0 until 3000).joinToString("") { i -> """<hp:lineseg textpos="$i" vertpos="${if (i % 2 == 0) 100 else 0}" horzsize="100"/>""" }
        val file = hwpx("""<hs:sec $hs><hp:p><hp:run><hp:t>${"x".repeat(3000)}</hp:t></hp:run><hp:linesegarray>$lines</hp:linesegarray></hp:p></hs:sec>""")
        var pages = 0
        survives(60) { pages = render(file, longSide = 32) }
        assertTrue("$pages pages", pages <= OfficeCanvas.MAX_PAGES)
    }

    private fun pptx(slideIds: String, slide: String) = zip(
        "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
        "ppt/presentation.xml" to """<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst>$slideIds</p:sldIdLst><p:sldSz cx="9144000" cy="6858000"/></p:presentation>""",
        "ppt/_rels/presentation.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="slide" Target="slides/slide1.xml"/></Relationships>""",
        "ppt/slides/slide1.xml" to slide
    )

    private val emptySlide = """<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree/></p:cSld></p:sld>"""

    @Test
    fun `one slide listed fifty thousand times is drawn once`() {
        val ids = (0 until 50_000).joinToString("") { """<p:sldId id="${256 + it}" r:id="rId1"/>""" }
        var pages = 0
        survives(30) { pages = render(pptx(ids, emptySlide)) }
        assertEquals(1, pages)
    }

    @Test
    fun `groups nested five thousand deep`() {
        val p = "http://schemas.openxmlformats.org/presentationml/2006/main"
        val slide = """<p:sld xmlns:p="$p"><p:cSld><p:spTree>""" + "<p:grpSp>".repeat(5000) + "</p:grpSp>".repeat(5000) + "</p:spTree></p:cSld></p:sld>"
        survives(30) { render(pptx("""<p:sldId id="256" r:id="rId1"/>""", slide)) }
    }

    @Test
    fun `a chart claiming two billion points in a thousand series`() {
        val ns = """xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart""""
        val slide = """<p:sld $ns><p:cSld><p:spTree><p:graphicFrame><p:xfrm><a:off x="0" y="0"/><a:ext cx="9144000" cy="6858000"/></p:xfrm><a:graphic><a:graphicData><c:chart r:id="rId1"/></a:graphicData></a:graphic></p:graphicFrame></p:spTree></p:cSld></p:sld>"""
        val values = """<c:val><c:numRef><c:numCache><c:ptCount val="2000000000"/><c:pt idx="1999999999"><c:v>1e308</c:v></c:pt><c:pt idx="3"><c:v>-1e308</c:v></c:pt></c:numCache></c:numRef></c:val>"""
        val series = """<c:ser><c:dLbls><c:showVal val="1"/><c:numFmt formatCode="#,##0.${"0".repeat(500)}"/></c:dLbls>$values</c:ser>""".repeat(1000)
        val chart = """<c:chartSpace $ns><c:chart><c:plotArea><c:barChart><c:grouping val="stacked"/>$series</c:barChart><c:valAx><c:majorUnit val="1e-300"/></c:valAx></c:plotArea><c:legend/></c:chart></c:chartSpace>"""
        val file = zip(
            "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
            "ppt/presentation.xml" to """<p:presentation $ns><p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst><p:sldSz cx="9144000" cy="6858000"/></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="slide" Target="slides/slide1.xml"/></Relationships>""",
            "ppt/slides/slide1.xml" to slide,
            "ppt/slides/_rels/slide1.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="chart" Target="../charts/chart1.xml"/></Relationships>""",
            "ppt/charts/chart1.xml" to chart
        )
        // Capped at 64 series; drawing their few hundred labels is what takes the time.
        survives(60) { render(file, longSide = 400) }
    }

    @Test
    fun `parts with a DOCTYPE are refused`() {
        val secret = File.createTempFile("secret", ".txt").apply { writeText("TOPSECRET"); deleteOnExit() }
        val xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file://${secret.absolutePath}\">]><r>&x;</r>"
        assertNull(SafeXml.parse(xxe.toByteArray()))
        assertNull(SafeXml.parse(xxe.toByteArray(Charsets.UTF_16LE)))
        assertNull(SafeXml.parse(("﻿" + xxe).toByteArray(Charsets.UTF_16BE)))
        assertEquals("r", SafeXml.parse("<r/>".toByteArray())!!.documentElement.tagName)
    }

    // ---- DOCX ---------------------------------------------------------------

    private val w = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main""""

    private fun docx(body: String, vararg parts: Pair<String, String>) = zip(
        "[Content_Types].xml" to "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>",
        "word/document.xml" to """<w:document $w><w:body>$body</w:body></w:document>""",
        *parts
    )

    @Test
    fun `a DOCX document part with a DOCTYPE is refused`() {
        val file = zip("word/document.xml" to """<?xml version="1.0"?><!DOCTYPE d [<!ENTITY x "xx">]><w:document $w><w:body><w:p><w:r><w:t>&x;</w:t></w:r></w:p></w:body></w:document>""")
        assertTrue(survives { render(file) } is OfficeCanvasException)
    }

    @Test
    fun `DOCX tables nested a thousand deep, and one spanning a million columns`() {
        val nested = "<w:tbl><w:tr><w:tc>".repeat(1000) + "<w:p/>" + "</w:tc></w:tr></w:tbl>".repeat(1000)
        val wide = """<w:tbl><w:tblGrid>${"<w:gridCol w:w=\"1\"/>".repeat(2000)}</w:tblGrid><w:tr><w:tc><w:tcPr><w:gridSpan w:val="1000000"/></w:tcPr><w:p/></w:tc></w:tr></w:tbl>"""
        survives(20) { render(docx(nested + wide)) }
    }

    @Test
    fun `a DOCX paragraph of two million characters, absurd sizes, spacing and page size`() {
        val body = """<w:p><w:pPr><w:spacing w:line="2000000000" w:before="-99999999" w:after="1e308"/><w:ind w:left="999999999" w:hanging="-5"/>""" +
            """<w:tabs><w:tab w:val="right" w:pos="-1"/></w:tabs></w:pPr><w:r><w:rPr><w:sz w:val="99999999"/><w:spacing w:val="-2000000000"/></w:rPr>""" +
            """<w:t>${"가".repeat(2_000_000)}</w:t><w:tab/></w:r></w:p><w:sectPr><w:pgSz w:w="-5" w:h="NaN"/><w:pgMar w:top="1e30"/></w:sectPr>"""
        var pages = 0
        survives(120) { pages = render(docx(body), longSide = 32) }
        assertTrue("$pages pages", pages <= OfficeCanvas.MAX_PAGES)
    }

    @Test
    fun `DOCX styles based on each other in a loop, and lists counting past every format`() {
        val styles = """<w:styles $w><w:style w:type="paragraph" w:styleId="a"><w:basedOn w:val="b"/></w:style><w:style w:type="paragraph" w:styleId="b"><w:basedOn w:val="a"/></w:style></w:styles>"""
        val numbering = """<w:numbering $w><w:abstractNum w:abstractNumId="0"><w:lvl w:ilvl="0"><w:start w:val="2147483647"/><w:numFmt w:val="koreanDigital"/><w:lvlText w:val="%1%9%1%1%1"/></w:lvl></w:abstractNum><w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num></w:numbering>"""
        val p = """<w:p><w:pPr><w:pStyle w:val="a"/><w:numPr><w:ilvl w:val="99"/><w:numId w:val="1"/></w:numPr></w:pPr><w:r><w:t>x</w:t></w:r></w:p>"""
        survives(20) { render(docx(p.repeat(3000), "word/styles.xml" to styles, "word/numbering.xml" to numbering)) }
    }

    @Test
    fun `a DOCX breaking the page in every run stops at the page limit`() {
        val file = docx("""<w:p>${"<w:r><w:t>x</w:t><w:br w:type=\"page\"/></w:r>".repeat(3000)}</w:p>""")
        var pages = 0
        survives(60) { pages = render(file, longSide = 32) }
        assertTrue("$pages pages", pages <= OfficeCanvas.MAX_PAGES)
    }

    private val dml = """xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"""" +
        """ xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape" xmlns:wpg="http://schemas.microsoft.com/office/word/2010/wordprocessingGroup"""" +
        """ xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" xmlns:v="urn:schemas-microsoft-com:vml" xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math""""

    @Test
    fun `DOCX groups and text boxes nested deep, absurd offsets and VML sizes`() {
        val group = "<wpg:grpSp><wpg:grpSpPr><a:xfrm><a:off x=\"-9999999999999\" y=\"1\"/><a:ext cx=\"9223372036854775807\" cy=\"1\"/><a:chExt cx=\"0\" cy=\"-1\"/></a:xfrm></wpg:grpSpPr>"
        val boxes = ("<w:p><w:r><w:drawing><wp:anchor simplePos=\"1\"><wp:simplePos x=\"-99999999999999999999\" y=\"1e30\"/><wp:extent cx=\"3600000\" cy=\"3600000\"/>" +
            "<a:graphic><a:graphicData><wps:wsp><wps:txbx><w:txbxContent>").repeat(60) + "<w:p/>" +
            "</w:txbxContent></wps:txbx></wps:wsp></a:graphicData></a:graphic></wp:anchor></w:drawing></w:r></w:p>".repeat(60)
        val groups = "<w:p><w:r><w:drawing><wp:inline><wp:extent cx=\"3600000\" cy=\"3600000\"/><a:graphic><a:graphicData><wpg:wgp>" +
            group.repeat(300) + "</wpg:grpSp>".repeat(300) + "</wpg:wgp></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"
        val vml = "<w:p><w:r><w:pict><v:shape style=\"position:absolute;width:1e300pt;height:-5in;margin-left:NaNpt\"/><v:rect style=\"width:99999999in;height:1pt\"/></w:pict></w:r></w:p>"
        val alternates = "<w:p><w:r>" + "<mc:AlternateContent><mc:Fallback><w:r>".repeat(100) + "</w:r></mc:Fallback></mc:AlternateContent>".repeat(100) + "</w:r></w:p>"
        val body = "<w:p $dml/>" + boxes + groups + vml + alternates
        var pages = 0
        // (Read through, not refused: the parts are well formed.)
        assertNull(survives(30) { pages = render(docx(body.replace("<w:p>", "<w:p $dml>"))) })
        assertTrue(pages >= 1)
    }

    @Test
    fun `DOCX equations nested deep, and ten thousand footnote references`() {
        val math = "<w:p $dml><m:oMath>" + "<m:f><m:num>".repeat(200) + "<m:r><m:t>x</m:t></m:r>" + "</m:num></m:f>".repeat(200) + "</m:oMath></w:p>"
        val notes = """<w:footnotes $w><w:footnote w:id="1"><w:p><w:r><w:footnoteRef/><w:footnoteReference w:id="1"/><w:t>${"note ".repeat(500)}</w:t></w:r></w:p></w:footnote></w:footnotes>"""
        val rels = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="f" Type="footnotes" Target="footnotes.xml"/></Relationships>"""
        val refs = "<w:p><w:r>${"<w:footnoteReference w:id=\"1\"/><w:endnoteReference w:id=\"1\"/>".repeat(10_000)}</w:r></w:p>"
        var pages = 0
        assertNull(survives(120) { pages = render(docx(math + refs, "word/footnotes.xml" to notes, "word/_rels/document.xml.rels" to rels), longSide = 32) })
        assertTrue("$pages pages", pages <= OfficeCanvas.MAX_PAGES)
    }

    // ---- HWPML (.hwp saved as XML) -------------------------------------------

    private fun hwpml(body: String, head: String = "") = File.createTempFile("evil", ".hwp").apply {
        deleteOnExit()
        writeText("""<?xml version="1.0" encoding="UTF-8"?><HWPML Version="2.8"><HEAD>$head</HEAD><BODY><SECTION Id="0">$body</SECTION></BODY></HWPML>""")
    }

    @Test
    fun `an HWPML file with a DOCTYPE is refused`() {
        val secret = File.createTempFile("secret", ".txt").apply { writeText("TOPSECRET"); deleteOnExit() }
        val file = File.createTempFile("evil", ".hwp").apply {
            deleteOnExit()
            writeText("""<?xml version="1.0"?><!DOCTYPE HWPML [<!ENTITY x SYSTEM "file://${secret.absolutePath}">]><HWPML><BODY><SECTION><P><TEXT><CHAR>&x;</CHAR></TEXT></P></SECTION></BODY></HWPML>""")
        }
        assertTrue(survives { render(file) } is OfficeCanvasException)
    }

    @Test
    fun `an HWPML table declaring fifty thousand rows and columns`() {
        val file = hwpml("""<P><TEXT><TABLE RowCount="50000" ColCount="50000"><ROW><CELL ColAddr="0" RowAddr="0" Width="1000" Height="1000"><PARALIST><P><TEXT><CHAR>x</CHAR></TEXT></P></PARALIST></CELL></ROW></TABLE></TEXT></P>""")
        survives(10) { render(file) }
    }

    @Test
    fun `an HWPML paragraph of two million characters and absurd sizes`() {
        val head = """<MAPPINGTABLE><CHARSHAPELIST><CHARSHAPE Id="0" Height="2000000000"><RATIO Hangul="-5"/><CHARSPACING Hangul="99999"/></CHARSHAPE></CHARSHAPELIST><PARASHAPELIST><PARASHAPE Id="0"><PARAMARGIN Indent="-2000000000" Left="2000000000" LineSpacingType="Fixed" LineSpacing="0"/></PARASHAPE></PARASHAPELIST></MAPPINGTABLE>"""
        val file = hwpml("""<P ParaShape="0"><TEXT CharShape="0"><CHAR>${"가".repeat(2_000_000)}</CHAR></TEXT></P>""", head)
        var pages = 0
        survives(120) { pages = render(file, longSide = 32) }
        assertTrue("$pages pages", pages <= OfficeCanvas.MAX_PAGES)
    }

    @Test
    fun `an HWPML section breaking the page on every paragraph stops at the page limit`() {
        val file = hwpml((0 until 3000).joinToString("") { """<P PageBreak="true"><TEXT><CHAR>x</CHAR></TEXT></P>""" })
        var pages = 0
        survives(60) { pages = render(file, longSide = 32) }
        assertTrue("$pages pages", pages <= OfficeCanvas.MAX_PAGES)
    }

    // ---- .hwp records -------------------------------------------------------

    private fun hwpRec(tag: Int, level: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        if (payload.size >= 0xFFF) {
            out.write(le(8).putInt(0, tag or (level shl 10) or (0xFFF shl 20)).putInt(4, payload.size).array())
        } else {
            out.write(le(4).putInt(0, tag or (level shl 10) or (payload.size shl 20)).array())
        }
        out.write(payload)
        return out.toByteArray()
    }

    private fun hwp(vararg section: ByteArray) =
        HwpParser.fromStreams(ByteArray(0), section.asSequence()) { _, _, _ -> null }

    @Test
    fun `an HWP record claiming two gigabytes`() {
        val section = le(8).putInt(0, 66 or (0xFFF shl 20)).putInt(4, 0x7FFFFFF0).array()
        survives(5) { hwp(section) }
    }

    @Test
    fun `HWP records nested a thousand levels deep`() {
        val out = ByteArrayOutputStream()
        for (level in 0 until 1023) out.write(hwpRec(if (level % 2 == 0) 66 else 71, level, ByteArray(24)))
        survives(10) { hwp(out.toByteArray()) }
    }

    @Test
    fun `an HWP table declaring 65535 by 65535 cells`() {
        val table = hwpRec(71, 1, le(24).putInt(0, 0x74626C20).putInt(16, 1000).putInt(20, 1000).array()) +
            hwpRec(77, 2, le(22).putShort(4, -1).putShort(6, -1).array()) +
            hwpRec(72, 2, le(34).array())
        val para = hwpRec(66, 0, ByteArray(24)) +
            hwpRec(67, 1, le(18).putShort(0, 11).putShort(14, 11).putShort(16, 13).array()) +
            hwpRec(69, 1, le(36).putInt(8, 1000).array()) + table
        survives(10) { hwp(para) }
    }

    @Test
    fun `an HWP paragraph with lines going up on every line stops at the page limit`() {
        val lines = le(36 * 5000)
        for (i in 0 until 5000) lines.putInt(36 * i, i).putInt(36 * i + 4, if (i % 2 == 0) 100 else 0).putInt(36 * i + 8, 100)
        val text = le(2 * 5001).apply { for (i in 0 until 5000) putShort(2 * i, 'x'.code.toShort()); putShort(10000, 13) }.array()
        val para = hwpRec(66, 0, ByteArray(24)) + hwpRec(67, 1, text) + hwpRec(69, 1, lines.array())
        var pages = 0
        survives(30) { pages = hwp(para).pages.size }
        assertTrue("$pages pages", pages <= OfficeCanvas.MAX_PAGES)
    }

    /** A paragraph holding one drawing object (gso, [w] x [h]) built from [shape] records (level 2 and below). */
    private fun hwpDrawing(w: Int, h: Int, shape: ByteArray): ByteArray =
        hwpRec(66, 0, ByteArray(24)) +
            hwpRec(67, 1, le(18).putShort(0, 11).putShort(14, 11).putShort(16, 13).array()) +
            hwpRec(69, 1, le(36).putInt(8, 1000).array()) +
            hwpRec(71, 1, le(46).putInt(0, 0x67736F20).putInt(4, 1).putInt(16, w).putInt(20, h).array()) + shape

    /** A shape component of kind [id] ("\$con", "\$pol", ...), its id repeated when top-level. */
    private fun hwpShape(id: String, level: Int, extra: ByteBuffer.() -> Unit = {}): ByteArray {
        val code = id.fold(0) { acc, c -> (acc shl 8) or c.code }
        val o = if (level == 2) 8 else 4
        return hwpRec(76, level, le(300).apply { putInt(0, code); if (o == 8) putInt(4, code); putInt(o + 12, 1000); putInt(o + 16, 1000); extra() }.array())
    }

    @Test
    fun `HWP drawing groups nested a thousand levels deep`() {
        val out = ByteArrayOutputStream()
        for (level in 2 until 1023) out.write(hwpShape("\$con", level))
        survives(10) { hwp(hwpDrawing(1000, 1000, out.toByteArray())) }
    }

    @Test
    fun `an HWP polygon claiming two billion points and absurd matrices`() {
        val shape = hwpShape("\$pol", 2) {
            // 65535 scale/rotation pairs, and a translation full of infinities.
            putShort(8 + 42, -1)
            for (i in 0 until 6) putDouble(8 + 44 + 8 * i, Double.POSITIVE_INFINITY)
        } + hwpRec(82, 3, le(4 + 8 * 1000).putInt(0, Int.MAX_VALUE).array())
        survives(10) {
            HwpxRenderer.render(hwp(hwpDrawing(1000, 1000, shape)), File("missing.hwp"), 400) { _, b -> b.recycle() }
        }
    }

    @Test
    fun `HWPX drawing containers nested five thousand deep`() {
        val open = (0 until 5000).joinToString("") { """<hp:container><hp:sz width="1000" height="1000"/>""" }
        val close = "</hp:container>".repeat(5000)
        val file = hwpx("""<hs:sec $hs><hp:p><hp:run>$open$close</hp:run><hp:linesegarray><hp:lineseg textpos="0" vertpos="0" horzsize="100"/></hp:linesegarray></hp:p></hs:sec>""")
        survives(10) { render(file) }
    }

    @Test
    fun `an HWPX polygon with a hundred thousand points and a huge relative size`() {
        val hc = """xmlns:hc="http://www.hancom.co.kr/hwpml/2011/core""""
        val points = (0 until 100_000).joinToString("") { """<hc:pt x="${it % 977 * 1000}" y="${it % 13 * 70000}"/>""" }
        val matrices = """<hc:scaMatrix e1="1e300" e2="0" e3="0" e4="0" e5="NaN" e6="0"/>""".repeat(1000)
        val file = hwpx("""<hs:sec $hs $hc><hp:p><hp:run><hp:polygon><hp:sz width="2000000000" widthRelTo="PARA" height="1000" heightRelTo="ABSOLUTE"/><hp:renderingInfo>$matrices</hp:renderingInfo><hp:lineShape color="#000000" width="10" style="SOLID"/>$points</hp:polygon></hp:run><hp:linesegarray><hp:lineseg textpos="0" vertpos="0" horzsize="100"/></hp:linesegarray></hp:p></hs:sec>""")
        survives(30) { render(file, longSide = 400) }
    }

    @Test
    fun `garbage DocInfo and sections`() {
        val random = java.util.Random(7)
        repeat(20) {
            val junk = ByteArray(4096).also { random.nextBytes(it) }
            survives(5) { HwpParser.fromStreams(junk, sequenceOf(junk, junk)) { _, _, _ -> { junk } } }
        }
    }
}
