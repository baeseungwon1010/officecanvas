package io.github.baeseungwon1010.officecanvas

import io.github.baeseungwon1010.officecanvas.convert.ConvImage
import io.github.baeseungwon1010.officecanvas.convert.DocxWriter
import io.github.baeseungwon1010.officecanvas.convert.HwpxWriter
import io.github.baeseungwon1010.officecanvas.convert.PageSink
import io.github.baeseungwon1010.officecanvas.convert.PdfLayout
import io.github.baeseungwon1010.officecanvas.pdf.PdfCrypt
import io.github.baeseungwon1010.officecanvas.pdf.PdfDocument
import io.github.baeseungwon1010.officecanvas.pdf.PdfPageReader
import io.github.baeseungwon1010.officecanvas.util.Limits
import java.io.File
import java.io.OutputStream

/** The document formats [PdfConverter] writes. */
enum class ConvertFormat {
    /** Word (.docx). */
    DOCX,

    /** Hangul (.hwpx, OWPML). */
    HWPX
}

/**
 * Turns a PDF into an editable document: its text read with each font's encoding,
 * rebuilt into paragraphs (alignment, indents, spacing, fonts, sizes, weights and
 * colors), tables (from the PDF's rulings, with merged cells, borders and shading),
 * and pictures, page by page in reading order. Scanned pages without a text layer
 * come out as their pictures.
 *
 * ```
 * PdfConverter.convert(pdf, File(dir, "out.docx"), ConvertFormat.DOCX)
 * ```
 *
 * Conversion runs on the calling thread and needs no Android APIs.
 */
object PdfConverter {

    /** Documents longer than this are converted up to this many pages. */
    const val MAX_PAGES = Limits.MAX_PAGES

    /**
     * Converts [pdf] into [output] in [format].
     *
     * @return the number of pages converted.
     * @throws OfficeCanvasException if the file isn't a PDF, needs a password, or is damaged beyond reading.
     */
    @JvmStatic
    fun convert(pdf: File, output: File, format: ConvertFormat): Int {
        val tmp = File(output.parentFile, output.name + ".part")
        try {
            val pages = tmp.outputStream().buffered().use { convert(pdf, it, format) }
            if (output.exists()) output.delete()
            if (!tmp.renameTo(output)) { tmp.copyTo(output, overwrite = true); tmp.delete() }
            return pages
        } finally {
            tmp.delete()
        }
    }

    /** Converts [pdf], writing the document to [output] (which is left open). */
    @JvmStatic
    fun convert(pdf: File, output: OutputStream, format: ConvertFormat): Int {
        try {
            PdfDocument.open(pdf).use { doc ->
                val pages = doc.pages.take(MAX_PAGES)
                if (pages.isEmpty()) throw OfficeCanvasException("${pdf.name} has no pages")
                val reader = PdfPageReader(doc)
                // First: what repeats at the pages' tops and bottoms (headers and
                // footers, on at least two pages and most of them).
                // A line with a number repeats only when the number goes with the page's
                // (section numbers at the tops of pages don't).
                val seen = HashMap<String, MutableList<Pair<Int, List<Int>>>>()
                for ((i, p) in pages.withIndex()) for ((k, nums) in PdfLayout.bandKeys(reader.read(p))) seen.getOrPut(k) { ArrayList() } += i to nums
                fun mostly(values: List<Int>, of: Int) = values.isNotEmpty() && values.groupingBy { it }.eachCount().maxOf { it.value } >= of * 0.8
                val repeated = seen.filter { (_, occ) ->
                    occ.size >= 2 && occ.size >= pages.size * 0.4 && (0 until occ.maxOf { it.second.size }).all { k ->
                        // Each number in the line stays the same (a year) or goes with the page number.
                        val vals = occ.mapNotNull { (_, nums) -> nums.getOrNull(k) }
                        mostly(vals, occ.size) || mostly(occ.mapNotNull { (i, nums) -> nums.getOrNull(k)?.minus(i) }, occ.size)
                    }
                }.keys
                // Then where the content lies on each page (for one set of margins per
                // page size), and the commonest font.
                val margins = ArrayList<DoubleArray?>()
                val fonts = HashMap<String, Int>()
                for (p in pages) {
                    val c = reader.read(p)
                    margins += PdfLayout.margins(c, repeated)
                    for (ch in c.chars) if (ch.text.isNotBlank()) fonts.merge(ch.font.family, 1, Int::plus)
                }
                val sizes = pages.map { Math.round(it.cropBox[2] - it.cropBox[0]) to Math.round(it.cropBox[3] - it.cropBox[1]) }
                val shared = HashMap<Pair<Long, Long>, DoubleArray>()
                for ((i, m) in margins.withIndex()) {
                    m ?: continue
                    val key = sizes[i]
                    val cur = shared[key]
                    shared[key] = if (cur == null) m.copyOf() else DoubleArray(4) { k -> minOf(cur[k], m[k]) }
                }
                // Some room at the bottom: the fonts a word processor sets the text in
                // may run a little longer than the PDF's, and a page is never meant to
                // overflow into the next (each PDF page starts a page).
                for (m in shared.values) m[3] = m[3] * 0.4
                val font = fonts.maxByOrNull { it.value }?.key ?: "Arial"
                val sink: PageSink = when (format) {
                    ConvertFormat.DOCX -> DocxWriter(output, font)
                    ConvertFormat.HWPX -> HwpxWriter(output, font)
                }
                for ((i, p) in pages.withIndex()) {
                    val c = reader.read(p)
                    val pictures = c.pictures.mapNotNull { pic ->
                        pic.image()?.let { ConvImage(it.data, it.png, it.width, it.height, pic.w, pic.h, pic.x, pic.y) }
                    }
                    sink.page(PdfLayout.page(c, pictures, shared[sizes[i]], repeated, i + 1))
                }
                sink.finish()
                return pages.size
            }
        } catch (e: OfficeCanvasException) {
            throw e
        } catch (e: PdfCrypt.PasswordException) {
            throw OfficeCanvasException("${pdf.name} needs a password to open", e)
        } catch (e: PdfDocument.ParseException) {
            throw OfficeCanvasException("${pdf.name} isn't a readable PDF: ${e.message}", e)
        } catch (e: Exception) {
            throw OfficeCanvasException("could not convert ${pdf.name}: ${e.message}", e)
        } catch (e: OutOfMemoryError) {
            throw OfficeCanvasException("ran out of memory converting ${pdf.name}", e)
        } catch (e: StackOverflowError) {
            throw OfficeCanvasException("${pdf.name} is nested too deeply to convert", e)
        }
    }
}
