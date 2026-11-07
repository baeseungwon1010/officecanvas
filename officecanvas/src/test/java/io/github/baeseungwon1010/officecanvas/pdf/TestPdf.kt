package io.github.baeseungwon1010.officecanvas.pdf

import java.io.ByteArrayOutputStream
import java.io.File

/** One-page PDFs written out by hand, for the converter tests. */
internal object TestPdf {

    /**
     * A page ([width] x [height], US Letter by default) drawing [content], with [fonts] as its font resources: name to a base
     * font, or to a whole font dictionary ("<< ... >>"). [extra] objects follow the fonts', numbered
     * from 5 + the number of fonts, for such dictionaries to refer to.
     */
    fun page(content: String, fonts: Map<String, String>, extra: List<String> = emptyList(), width: Int = 612, height: Int = 792): File {
        val names = fonts.keys.toList()
        val fontRefs = names.withIndex().joinToString(" ") { (i, n) -> "/$n ${5 + i} 0 R" }
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $width $height] /Resources << /Font << $fontRefs >> >> /Contents 4 0 R >>",
            "<< /Length ${content.length} >>\nstream\n$content\nendstream"
        ) + names.map { n -> fonts.getValue(n).let { if (it.startsWith("<<")) it else "<< /Type /Font /Subtype /Type1 /BaseFont /$it >>" } } + extra
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.7\n".toByteArray())
        val offsets = objects.mapIndexed { i, o -> out.size().also { out.write("${i + 1} 0 obj\n$o\nendobj\n".toByteArray()) } }
        val start = out.size()
        out.write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n".toByteArray())
        for (off in offsets) out.write(String.format("%010d 00000 n \n", off).toByteArray())
        out.write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$start\n%%EOF\n".toByteArray())
        return File.createTempFile("test", ".pdf").apply { deleteOnExit(); writeBytes(out.toByteArray()) }
    }
}
