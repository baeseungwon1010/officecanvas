package io.github.baeseungwon1010.officecanvas.pdf

import io.github.baeseungwon1010.officecanvas.ConvertFormat
import io.github.baeseungwon1010.officecanvas.PdfConverter
import io.github.baeseungwon1010.officecanvas.ole.Ole2Reader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater

/**
 * Files built to make reading slow or large stay cheap: a font's cmap repeating one huge
 * range, a ToUnicode CMap of 200 000 ranges no text uses, a million codespace ranges, fax
 * data coding a row a bit. Each took minutes, or ran out of memory, before its limit.
 */
class PdfLimitsTest {

    private fun obj(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun stream(dict: String, data: ByteArray) = obj("<< $dict /Length ${data.size} >>\nstream\n") + data + obj("\nendstream")
    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(9); d.setInput(data); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(1 shl 16)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end(); return out.toByteArray()
    }

    /** One page drawing [content] with [resources]; [extra] objects numbered from 5. */
    private fun page(content: String, resources: String, extra: List<ByteArray>): File {
        val objs = listOf(obj("<< /Type /Catalog /Pages 2 0 R >>"), obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
            obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources $resources /Contents 4 0 R >>"), stream("", obj(content))) + extra
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.7\n".toByteArray())
        val offsets = objs.mapIndexed { i, o -> out.size().also { out.write("${i + 1} 0 obj\n".toByteArray()); out.write(o); out.write("\nendobj\n".toByteArray()) } }
        val start = out.size()
        out.write("xref\n0 ${objs.size + 1}\n0000000000 65535 f \n".toByteArray())
        for (off in offsets) out.write(String.format("%010d 00000 n \n", off).toByteArray())
        out.write("trailer\n<< /Size ${objs.size + 1} /Root 1 0 R >>\nstartxref\n$start\n%%EOF\n".toByteArray())
        return File.createTempFile("limits", ".pdf").apply { deleteOnExit(); writeBytes(out.toByteArray()) }
    }

    private fun readQuickly(f: File, seconds: Double = 10.0) {
        val t0 = System.nanoTime()
        val doc = PdfDocument.open(f)
        try { PdfPageReader(doc).read(doc.pages[0]) } finally { doc.close() }
        val s = (System.nanoTime() - t0) / 1e9
        assertTrue("took $s s", s < seconds)
    }

    /** A TrueType font whose cmap (format 12) repeats one group of 100 000 codes [groups] times, from [records] cmap records. */
    private fun ttf(groups: Int, records: Int): ByteArray {
        val b = ByteArrayOutputStream()
        fun u16(v: Int) { b.write(v shr 8 and 0xFF); b.write(v and 0xFF) }
        fun u32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
        u32(0x00010000); u16(1); u16(0); u16(0); u16(0)
        b.write("cmap".toByteArray()); u32(0); u32(28); u32(0)
        u16(0); u16(records)
        repeat(records) { u16(3); u16(10); u32(4 + records * 8) }
        u16(12); u16(0); u32(16 + 12 * groups); u32(0); u32(groups)
        repeat(groups) { u32(0); u32(99_999); u32(1) }
        return b.toByteArray()
    }

    @Test
    fun `a cmap repeating one large range, or read through many records, is read once and within a budget`() {
        for ((groups, records) in listOf(100_000 to 1, 1 to 64)) {
            val font = stream("/Filter /FlateDecode", deflate(ttf(groups, records)))
            readQuickly(page("BT /F1 10 Tf 72 700 Td (abc) Tj ET",
                "<< /Font << /F1 << /Type /Font /Subtype /TrueType /BaseFont /Big /FontDescriptor 6 0 R >> >> >>",
                listOf(font, obj("<< /Type /FontDescriptor /FontName /Big /Flags 4 /FontFile2 5 0 R >>"))))
        }
    }

    @Test
    fun `a ToUnicode CMap of many ranges no text uses costs a lookup, not a scan, per glyph`() {
        val cm = StringBuilder("/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n1 begincodespacerange <00> <FF> endcodespacerange\n")
        repeat(600) { cm.append("333 beginbfrange\n"); repeat(333) { cm.append("<FF> <FF> <0041>\n") }; cm.append("endbfrange\n") }
        cm.append("endcmap end end")
        readQuickly(page("BT /F1 10 Tf 72 700 Td (" + "a".repeat(20_000) + ") Tj ET",
            "<< /Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica /ToUnicode 5 0 R >> >> >>",
            listOf(stream("/Filter /FlateDecode", deflate(cm.toString().toByteArray())))))
    }

    @Test
    fun `a CMap of a million codespace ranges keeps only a few`() {
        val cm = StringBuilder("begincmap\n")
        repeat(2000) { cm.append("500 begincodespacerange\n"); repeat(500) { cm.append("<FFFFFFFF> <FFFFFFFF>\n") }; cm.append("endcodespacerange\n") }
        cm.append("endcmap")
        readQuickly(page("BT /F1 10 Tf 72 700 Td <" + "0102".repeat(5000) + "> Tj ET",
            "<< /Font << /F1 << /Type /Font /Subtype /Type0 /BaseFont /X /Encoding 5 0 R /DescendantFonts [<< /Type /Font /Subtype /CIDFontType2 /BaseFont /X >>] >> >> >>",
            listOf(stream("/Type /CMap /CMapName /Big /Filter /FlateDecode", deflate(cm.toString().toByteArray())))))
    }

    @Test
    fun `fax data coding a whole row a bit decodes to no more rows than allowed`() {
        // Group 4: each 1 bit is a row the same as the one above. 8 KB of them would be 65 536 rows of 8 KB.
        val rows = CcittFax.decode(ByteArray(8192) { -1 }, -1, 65536, 0, blackIs1 = false, byteAlign = false)
        assertTrue("${rows?.size}", rows == null || rows.size <= 64 shl 20)
        // In a picture, as many rows as the picture has, however many the fax data says.
        val image = stream("/Type /XObject /Subtype /Image /Width 100 /Height 10 /BitsPerComponent 1 /ColorSpace /DeviceGray " +
            "/Filter /CCITTFaxDecode /DecodeParms << /K -1 /Columns 100 /Rows 400000000 >>", ByteArray(8192) { -1 })
        val f = page("q 100 0 0 10 72 700 cm /Im1 Do Q", "<< /XObject << /Im1 5 0 R >> >>", listOf(image))
        val doc = PdfDocument.open(f)
        try {
            val img = PdfPageReader(doc).read(doc.pages[0]).pictures.single().image()
            assertTrue(img == null || img.width == 100 && img.height == 10)
        } finally { doc.close() }
    }

    @Test
    fun `a picture placed many times is stored once`() {
        val hex = (0 until 64 * 64).joinToString("") { "%02X".format((it * 37) and 0xFF) }
        val image = stream("/Type /XObject /Subtype /Image /Width 64 /Height 64 /BitsPerComponent 8 /ColorSpace /DeviceGray /Filter /ASCIIHexDecode", obj(hex + ">"))
        val content = (0 until 100).joinToString(" ") { k -> "q 20 0 0 20 ${40 + k % 10 * 50} ${80 + k / 10 * 60} cm /Im1 Do Q" }
        val f = page(content, "<< /XObject << /Im1 5 0 R >> >>", listOf(image))
        for (format in listOf(ConvertFormat.DOCX, ConvertFormat.HWPX)) {
            val out = File.createTempFile("once", "." + format.name.lowercase()).apply { deleteOnExit() }
            PdfConverter.convert(f, out, format)
            val media = java.util.zip.ZipFile(out).use { z -> z.entries().toList().count { it.name.startsWith("word/media/") || it.name.startsWith("BinData/") } }
            assertEquals(format.name, 1, media)
        }
        val hwp = File.createTempFile("once", ".hwp").apply { deleteOnExit() }
        PdfConverter.convert(f, hwp, ConvertFormat.HWP)
        Ole2Reader.open(hwp).use { ole ->
            assertTrue(ole.hasStream("BinData/BIN0001.png"))
            assertTrue(!ole.hasStream("BinData/BIN0002.png"))
        }
    }
}
