package io.github.baeseungwon1010.officecanvas

import android.content.Context
import android.graphics.Bitmap
import io.github.baeseungwon1010.officecanvas.docx.DocxParser
import io.github.baeseungwon1010.officecanvas.hwp.HwpParser
import io.github.baeseungwon1010.officecanvas.hwp.HwpmlParser
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxParser
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxRenderer
import io.github.baeseungwon1010.officecanvas.ppt.PptParser
import io.github.baeseungwon1010.officecanvas.pptx.PptxParser
import io.github.baeseungwon1010.officecanvas.slide.OfficeFonts
import io.github.baeseungwon1010.officecanvas.slide.SlideRenderer
import io.github.baeseungwon1010.officecanvas.util.Limits
import java.io.File

/**
 * Renders PPTX, PPT, HWPX and HWP documents to bitmaps, one page (or slide) at a time.
 *
 * ```
 * val pages = OfficeCanvas.render(context, file) { index, bitmap ->
 *     bitmap.compress(Bitmap.CompressFormat.PNG, 100, File(dir, "page$index.png").outputStream())
 *     bitmap.recycle()
 * }
 * ```
 *
 * Rendering is synchronous and does real work (parsing, layout, image decoding):
 * call it off the main thread.
 */
object OfficeCanvas {

    /** Long side, in pixels, of each rendered page unless asked otherwise. */
    const val DEFAULT_LONG_SIDE_PX = 1600

    /** Documents longer than this are rendered up to this many pages. */
    const val MAX_PAGES = Limits.MAX_PAGES

    /**
     * Renders every page of [file] in order. Each bitmap is handed to [onPage] as
     * soon as it is drawn and is owned by the caller from then on (recycle it when
     * done); pages are never all held in memory at once.
     *
     * @param longSidePx size of a page's longer side in pixels; the other side
     *   follows the page's own aspect ratio.
     * @param format skips content-based detection when the caller already knows.
     * At most [MAX_PAGES] pages are rendered. Damaged or hostile content costs the
     * shape, picture or page it is in where possible; anything worse, including
     * running out of memory, is reported as [OfficeCanvasException].
     *
     * @return the number of pages rendered.
     * @throws OfficeCanvasException if the file isn't a supported document or is damaged.
     */
    @JvmStatic
    @JvmOverloads
    fun render(
        context: Context,
        file: File,
        longSidePx: Int = DEFAULT_LONG_SIDE_PX,
        format: DocumentFormat? = null,
        onPage: (index: Int, bitmap: Bitmap) -> Unit
    ): Int {
        require(longSidePx > 0) { "longSidePx must be positive, was $longSidePx" }
        var pages = 0
        val counting: (Int, Bitmap) -> Unit = { index, bitmap -> pages++; onPage(index, bitmap) }
        var kind: DocumentFormat? = format
        try {
            kind = kind ?: DocumentFormat.detect(file)
                ?: throw OfficeCanvasException("not a PPTX, PPT, DOCX, HWPX or HWP document: ${file.name}")
            loadFonts(context)
            when (kind) {
                DocumentFormat.PPTX, DocumentFormat.PPT -> {
                    val deck = if (kind == DocumentFormat.PPTX) PptxParser.parse(file) else PptParser.parse(file)
                    try {
                        SlideRenderer.render(deck, file, longSidePx, counting)
                    } finally {
                        deck.resources?.close()
                    }
                }
                DocumentFormat.HWPX, DocumentFormat.HWP, DocumentFormat.DOCX -> {
                    val doc = when {
                        kind == DocumentFormat.HWPX -> HwpxParser.parse(file)
                        kind == DocumentFormat.DOCX -> DocxParser.parse(file)
                        HwpmlParser.isHwpml(file) -> HwpmlParser.parse(file)
                        else -> HwpParser.parse(file)
                    }
                    try {
                        HwpxRenderer.render(doc, file, longSidePx, counting)
                    } finally {
                        doc.resources?.close()
                    }
                }
            }
        } catch (e: OfficeCanvasException) {
            throw e
        } catch (e: Exception) {
            throw OfficeCanvasException("could not render ${file.name} as $kind: ${e.message}", e)
        } catch (e: OutOfMemoryError) {
            throw OfficeCanvasException("ran out of memory rendering ${file.name}", e)
        } catch (e: StackOverflowError) {
            throw OfficeCanvasException("${file.name} is nested too deeply to render", e)
        }
        return pages
    }

    private fun loadFonts(context: Context) {
        if (SlideRenderer.fonts == null) {
            synchronized(this) {
                if (SlideRenderer.fonts == null) SlideRenderer.fonts = OfficeFonts.fromAssets(context.applicationContext.assets)
            }
        }
    }
}
