package io.github.baeseungwon1010.officecanvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import io.github.baeseungwon1010.officecanvas.hwp.HwpParser
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
        survives { Ole2Reader.open(file) }
    }

    @Test
    fun `a FAT chain that loops`() {
        // Sector 0 is the FAT and says sector 1 is followed by sector 1.
        val file = temp(ole2(2) {
            putInt(76, 0); putInt(48, 1)
            putInt(512, -3); putInt(516, 1)
        }, ".ppt")
        survives { Ole2Reader.open(file) }
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
    fun `parts with a DOCTYPE are refused`() {
        val secret = File.createTempFile("secret", ".txt").apply { writeText("TOPSECRET"); deleteOnExit() }
        val xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file://${secret.absolutePath}\">]><r>&x;</r>"
        assertNull(SafeXml.parse(xxe.toByteArray()))
        assertNull(SafeXml.parse(xxe.toByteArray(Charsets.UTF_16LE)))
        assertNull(SafeXml.parse(("﻿" + xxe).toByteArray(Charsets.UTF_16BE)))
        assertEquals("r", SafeXml.parse("<r/>".toByteArray())!!.documentElement.tagName)
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

    @Test
    fun `garbage DocInfo and sections`() {
        val random = java.util.Random(7)
        repeat(20) {
            val junk = ByteArray(4096).also { random.nextBytes(it) }
            survives(5) { HwpParser.fromStreams(junk, sequenceOf(junk, junk)) { _, _, _ -> junk } }
        }
    }
}
