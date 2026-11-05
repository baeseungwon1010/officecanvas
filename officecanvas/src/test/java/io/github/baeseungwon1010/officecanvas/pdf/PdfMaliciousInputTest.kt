package io.github.baeseungwon1010.officecanvas.pdf

import io.github.baeseungwon1010.officecanvas.ConvertFormat
import io.github.baeseungwon1010.officecanvas.OfficeCanvasException
import io.github.baeseungwon1010.officecanvas.PdfConverter
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater

/**
 * PDFs built to break a reader: loops, deep nesting, a decompression bomb, absurd
 * numbers, floods of glyphs and operators. Each must convert or be refused with
 * OfficeCanvasException, in bounded time, on a 1 MB stack.
 */
class PdfMaliciousInputTest {

    private fun survives(seconds: Long = 30, block: () -> Unit): Throwable? {
        var thrown: Throwable? = null
        val t = Thread(null, { try { block() } catch (e: Throwable) { thrown = e } }, "probe", 1L shl 20)
        t.isDaemon = true
        t.start()
        t.join(seconds * 1000)
        if (t.isAlive) fail("still running after ${seconds}s")
        thrown?.let { if (it !is OfficeCanvasException) throw AssertionError("escaped: $it", it) }
        return thrown
    }

    private fun convert(pdf: File, format: ConvertFormat = ConvertFormat.DOCX) {
        val out = File.createTempFile("evil", "." + format.name.lowercase()).apply { deleteOnExit() }
        PdfConverter.convert(pdf, out, format)
    }

    /** A PDF of [objects] ("1 0 obj ... endobj" bodies, numbered from 1) with a correct xref, or none. */
    private fun pdf(objects: List<ByteArray>, trailer: String = "/Root 1 0 R", xref: Boolean = true): File {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.7\n".toByteArray())
        val offsets = ArrayList<Int>()
        for ((i, o) in objects.withIndex()) {
            offsets += out.size()
            out.write("${i + 1} 0 obj\n".toByteArray()); out.write(o); out.write("\nendobj\n".toByteArray())
        }
        val start = out.size()
        if (xref) {
            out.write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n".toByteArray())
            for (off in offsets) out.write(String.format("%010d 00000 n \n", off).toByteArray())
        }
        out.write("trailer\n<< /Size ${objects.size + 1} $trailer >>\nstartxref\n${if (xref) start else 999999}\n%%EOF\n".toByteArray())
        return File.createTempFile("evil", ".pdf").apply { deleteOnExit(); writeBytes(out.toByteArray()) }
    }

    private fun obj(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    private fun stream(dict: String, data: ByteArray) =
        obj("<< $dict /Length ${data.size} >>\nstream\n") + data + obj("\nendstream")

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(9); d.setInput(data); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(1 shl 16)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end(); return out.toByteArray()
    }

    /** Catalog, pages, one page with [content] and [resources]. */
    private fun onePage(content: ByteArray, resources: String = "<< >>", extra: List<ByteArray> = emptyList()) = pdf(listOf(
        obj("<< /Type /Catalog /Pages 2 0 R >>"),
        obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
        obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources $resources /Contents 4 0 R >>"),
        stream("", content)
    ) + extra)

    @Test
    fun `reference loops, a page tree holding itself and a broken xref`() {
        survives { convert(pdf(listOf(obj("6 0 R"), obj("<< >>"), obj("<< >>"), obj("<< >>"), obj("<< >>"), obj("1 0 R")))) }
        survives {
            convert(pdf(listOf(
                obj("<< /Type /Catalog /Pages 2 0 R >>"),
                obj("<< /Type /Pages /Kids [2 0 R 3 0 R 2 0 R] /Count 3 >>"),
                obj("<< /Type /Pages /Kids [2 0 R 3 0 R] /Parent 3 0 R >>")
            )))
        }
        // No usable xref (startxref points past the end): objects are found by scanning.
        val scanned = onePage(obj("BT /F1 12 Tf 72 700 Td (Hello) Tj ET"), "<< /Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >> >>")
        val bytes = scanned.readBytes()
        val broken = String(bytes, Charsets.ISO_8859_1).replace(Regex("startxref\\n\\d+"), "startxref\n123456789")
        survives { convert(File.createTempFile("evil", ".pdf").apply { deleteOnExit(); writeBytes(broken.toByteArray(Charsets.ISO_8859_1)) }) }
        // Prev pointing at itself.
        val selfPrev = String(bytes, Charsets.ISO_8859_1).replace("/Root 1 0 R", "/Root 1 0 R /Prev ${String(bytes, Charsets.ISO_8859_1).substringAfter("startxref\n").substringBefore("\n")}")
        survives { convert(File.createTempFile("evil", ".pdf").apply { deleteOnExit(); writeBytes(selfPrev.toByteArray(Charsets.ISO_8859_1)) }) }
    }

    @Test
    fun `arrays and dictionaries nested a hundred thousand deep`() {
        val deep = "[".repeat(100_000) + "]".repeat(100_000)
        val dicts = "<< /A ".repeat(50_000) + "1" + " >>".repeat(50_000)
        assertNull(survives { convert(onePage(obj("$deep $dicts re f"), "<< /X $dicts /Y $deep >>")) })
    }

    @Test
    fun `a form that draws itself, and a content stream that names itself`() {
        val content = obj("/X0 Do")
        assertNull(survives {
            convert(onePage(content, "<< /XObject << /X0 5 0 R >> >>", listOf(
                stream("/Type /XObject /Subtype /Form /BBox [0 0 100 100] /Resources << /XObject << /X0 5 0 R >> >>", obj("/X0 Do /X0 Do"))
            )))
        })
    }

    @Test
    fun `a decompression bomb stops at the decoder's cap`() {
        val bomb = deflate(ByteArray(300 shl 20))
        val file = onePage(ByteArray(0)).let { base ->
            pdf(listOf(
                obj("<< /Type /Catalog /Pages 2 0 R >>"),
                obj("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
                obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R >>"),
                stream("/Filter /FlateDecode", bomb)
            ))
        }
        assertNull(survives(60) { convert(file) })
    }

    @Test
    fun `absurd sizes, lengths and counts`() {
        assertNull(survives {
            convert(pdf(listOf(
                obj("<< /Type /Catalog /Pages 2 0 R >>"),
                obj("<< /Type /Pages /Kids [3 0 R] /Count 999999999 >>"),
                obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 1e30 -1e30] /Rotate 45 /Resources << /XObject << /I 5 0 R >> >> /Contents 4 0 R >>"),
                obj("<< /Length 99999999999 >>\nstream\nq 1e300 0 0 1e300 0 0 cm /I Do Q 9999999999 Tz BT /F 1e308 Tf (x) Tj ET\nendstream"),
                stream("/Type /XObject /Subtype /Image /Width 1000000000 /Height 1000000000 /BitsPerComponent 8 /ColorSpace /DeviceRGB", ByteArray(10)),
                obj("<< /Type /ObjStm /N 1000000000 /First -5 /Length 3 >>\nstream\n1 0\nendstream")
            )))
        })
    }

    @Test
    fun `millions of glyphs and operators, and a CMap of huge ranges`() {
        val cmap = "begincmap\n" + "1 begincodespacerange <0000> <FFFF> endcodespacerange\n" +
            "100 beginbfrange\n" + "<0000> <FFFF> <0041>\n".repeat(100) + "endbfrange\n" +
            "1 beginbfrange\n<0000> <FFFF> [" + "<0041> ".repeat(70_000) + "]\nendbfrange\nendcmap"
        val text = "(" + "a".repeat(3_000_000) + ") Tj "
        val ops = "0 0 m 1 1 l S ".repeat(1_000_000)
        assertNull(survives(90) {
            convert(onePage(obj("BT /F1 10 Tf 72 700 Td $text ET $ops"),
                "<< /Font << /F1 << /Type /Font /Subtype /Type0 /BaseFont /X /Encoding /Identity-H /ToUnicode 5 0 R /DescendantFonts [<< /Subtype /CIDFontType2 /W [0 [1 2 3] 1 65535 500] >>] >> >> >>",
                listOf(stream("", obj(cmap)))))
        })
    }

    @Test
    fun `broken encryption dictionaries are refused, not crashed on`() {
        for (enc in listOf("<< /Filter /Standard /V 5 /R 6 /U (short) /UE () /O () /P -4 >>", "<< /Filter /Standard /V 2 /R 3 /Length 40000 /O <00> /U <00> /P 0 >>", "<< /Filter /Other >>")) {
            val e = survives {
                convert(pdf(listOf(obj("<< /Type /Catalog /Pages 2 0 R >>"), obj("<< /Type /Pages /Kids [] /Count 0 >>"), obj(enc)), "/Root 1 0 R /Encrypt 3 0 R /ID [<00> <00>]"))
            }
            assertTrue("$enc: $e", e is OfficeCanvasException)
        }
    }

    @Test
    fun `every output format survives the same hostile content`() {
        val file = onePage(obj("BT /F1 10 Tf " + "1 0 0 1 0 0 Tm (x) Tj ".repeat(20_000) + " ET " + "0 0 10 10 re f ".repeat(50_000)),
            "<< /Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica /FirstChar -5 /Widths [1e308 -1] >> >> >>")
        for (f in ConvertFormat.values()) assertNull(survives(60) { convert(file, f) })
    }
}
