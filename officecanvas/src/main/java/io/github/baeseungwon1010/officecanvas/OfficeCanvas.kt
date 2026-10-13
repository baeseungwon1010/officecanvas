package io.github.baeseungwon1010.officecanvas

import android.content.Context
import android.graphics.Bitmap
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxParser
import io.github.baeseungwon1010.officecanvas.hwpx.HwpxRenderer
import io.github.baeseungwon1010.officecanvas.ppt.PptParser
import io.github.baeseungwon1010.officecanvas.pptx.PptxParser
import io.github.baeseungwon1010.officecanvas.slide.OfficeFonts
import io.github.baeseungwon1010.officecanvas.slide.SlideRenderer
import java.io.File

/**
 * Renders PPTX, PPT and HWPX documents to bitmaps, one page (or slide) at a time.
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

    /**
     * Renders every page of [file] in order. Each bitmap is handed to [onPage] as
     * soon as it is drawn and is owned by the caller from then on (recycle it when
     * done); pages are never all held in memory at once.
     *
     * @param longSidePx size of a page's longer side in pixels; the other side
     *   follows the page's own aspect ratio.
     * @param format skips content-based detection when the caller already knows.
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
        val kind = format ?: DocumentFormat.detect(file)
            ?: throw OfficeCanvasException("not a PPTX, PPT or HWPX document: ${file.name}")
        var pages = 0
        val counting: (Int, Bitmap) -> Unit = { index, bitmap -> pages++; onPage(index, bitmap) }
        try {
            when (kind) {
                DocumentFormat.PPTX, DocumentFormat.PPT -> {
                    loadFonts(context)
                    val deck = if (kind == DocumentFormat.PPTX) PptxParser.parse(file) else PptParser.parse(file)
                    SlideRenderer.render(deck, file, longSidePx, counting)
                }
                DocumentFormat.HWPX -> HwpxRenderer.render(HwpxParser.parse(file), file, longSidePx, counting)
            }
        } catch (e: OfficeCanvasException) {
            throw e
        } catch (e: Exception) {
            throw OfficeCanvasException("could not render ${file.name} as $kind: ${e.message}", e)
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
