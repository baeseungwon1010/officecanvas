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
import io.github.baeseungwon1010.officecanvas.util.PageSource
import java.io.File

/**
 * Renders PPTX, PPT, DOCX, HWPX and HWP documents: every page to a bitmap with
 * [render], or any page at any size onto a canvas with [open].
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
        // Whole pages one after another: a picture on many pages (a logo) is decoded once.
        open(context, file, format, RENDER_CACHE_BYTES).use { doc ->
            for (i in 0 until doc.pageCount) {
                val bitmap = guarded(file, doc.format) { doc.render(i, longSidePx) }
                onPage(i, bitmap)
            }
            return doc.pageCount
        }
    }

    /** Pictures [open] keeps decoded between draws unless asked otherwise: 48 MB. */
    const val DEFAULT_PICTURE_CACHE_BYTES = 48L shl 20

    private const val RENDER_CACHE_BYTES = 32L shl 20

    /**
     * Parses and lays out [file] once, for drawing its pages at any size with
     * [OfficeDocument.drawPage] as often as needed (a page redrawn sharp each time
     * the user zooms). Close it when done; use it from one thread at a time.
     *
     * The same limits as [render] apply: at most [MAX_PAGES] pages, damaged or
     * hostile content costs the shape, picture or page it is in where possible, and
     * anything worse is an [OfficeCanvasException].
     *
     * @param format skips content-based detection when the caller already knows.
     * @param pictureCacheBytes how much decoded picture data to keep between draws.
     * @throws OfficeCanvasException if the file isn't a supported document or is damaged.
     */
    @JvmStatic
    @JvmOverloads
    fun open(
        context: Context,
        file: File,
        format: DocumentFormat? = null,
        pictureCacheBytes: Long = DEFAULT_PICTURE_CACHE_BYTES
    ): OfficeDocument {
        require(pictureCacheBytes >= 0) { "pictureCacheBytes must not be negative, was $pictureCacheBytes" }
        return guarded(file, format) {
            val kind = format ?: DocumentFormat.detect(file)
                ?: throw OfficeCanvasException("not a PPTX, PPT, DOCX, HWPX or HWP document: ${file.name}")
            loadFonts(context)
            val pages: PageSource = when (kind) {
                DocumentFormat.PPTX, DocumentFormat.PPT -> {
                    val deck = if (kind == DocumentFormat.PPTX) PptxParser.parse(file) else PptParser.parse(file)
                    withResources(deck.resources) { SlideRenderer.Pages(deck, file, pictureCacheBytes) }
                }
                DocumentFormat.HWPX, DocumentFormat.HWP, DocumentFormat.DOCX -> {
                    val doc = when {
                        kind == DocumentFormat.HWPX -> HwpxParser.parse(file)
                        kind == DocumentFormat.DOCX -> DocxParser.parse(file)
                        HwpmlParser.isHwpml(file) -> HwpmlParser.parse(file)
                        else -> HwpParser.parse(file)
                    }
                    withResources(doc.resources) { HwpxRenderer.Pages(doc, file, pictureCacheBytes) }
                }
            }
            OfficeDocument(kind, pages, file.name)
        }
    }

    /** [pages] closing [resources] (the open file a model reads pictures from) along with itself. */
    private inline fun withResources(resources: java.io.Closeable?, pages: () -> PageSource): PageSource {
        val p = try { pages() } catch (e: Throwable) { resources?.close(); throw e }
        if (resources == null) return p
        return object : PageSource by p {
            override fun render(index: Int, longSidePx: Int) = p.render(index, longSidePx)
            override fun close() { try { p.close() } finally { resources.close() } }
        }
    }

    /** Runs [block], reporting anything but an [OfficeCanvasException] as one. */
    private inline fun <T> guarded(file: File, kind: DocumentFormat?, block: () -> T): T = try {
        block()
    } catch (e: OfficeCanvasException) {
        throw e
    } catch (e: Exception) {
        throw OfficeCanvasException("could not render ${file.name} as $kind: ${e.message}", e)
    } catch (e: OutOfMemoryError) {
        throw OfficeCanvasException("ran out of memory rendering ${file.name}", e)
    } catch (e: StackOverflowError) {
        throw OfficeCanvasException("${file.name} is nested too deeply to render", e)
    }

    private fun loadFonts(context: Context) {
        if (SlideRenderer.fonts == null) {
            synchronized(this) {
                if (SlideRenderer.fonts == null) SlideRenderer.fonts = OfficeFonts.fromAssets(context.applicationContext.assets)
            }
        }
    }
}
