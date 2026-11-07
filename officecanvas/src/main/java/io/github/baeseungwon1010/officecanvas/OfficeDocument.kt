package io.github.baeseungwon1010.officecanvas

import android.graphics.Bitmap
import android.graphics.Canvas
import io.github.baeseungwon1010.officecanvas.util.PageSource
import java.io.Closeable

/**
 * A document parsed and laid out once, whose pages can then be drawn at any size,
 * any number of times — the whole page for a thumbnail, or just the part on screen
 * at 6x when the user zooms in. Get one from [OfficeCanvas.open].
 *
 * ```
 * OfficeCanvas.open(context, file).use { doc ->
 *     val bitmap = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
 *     val canvas = Canvas(bitmap)
 *     canvas.translate(-left, -top)   // the visible part, in pixels at this zoom
 *     canvas.scale(pxPerPt, pxPerPt)  // page points to pixels
 *     doc.drawPage(index, canvas)
 * }
 * ```
 *
 * Text is laid out at one fixed size whatever the drawing size, so a line breaks,
 * justifies and shrinks to fit exactly as in [OfficeCanvas.render]'s page images,
 * and a region drawn zoomed in lines up with the same region of a whole page.
 *
 * **Threads:** use a document from one thread at a time (a single background
 * thread that does all the drawing is the intended shape); it is not thread-safe.
 * Drawing does real work on a first draw (decoding pictures), so keep it off the
 * main thread.
 *
 * **Memory:** decoded pictures are kept between draws, up to the cache size given
 * to [OfficeCanvas.open], at the resolution each draw needed; text laid out for the
 * page drawn last is kept too. [close] releases them and the open file.
 */
class OfficeDocument internal constructor(
    /** The document's format. */
    val format: DocumentFormat,
    private val pages: PageSource,
    private val name: String
) : Closeable {

    @Volatile
    private var closed = false

    /** For tests. */
    internal val source: PageSource get() = pages

    /** Pages (or slides) in the document, at most [OfficeCanvas.MAX_PAGES]. */
    val pageCount: Int get() = pages.pageCount

    /** Width of page [index] in points (1/72 inch). */
    fun pageWidthPt(index: Int): Float = pages.widthPt(page(index)).toFloat()

    /** Height of page [index] in points (1/72 inch). */
    fun pageHeightPt(index: Int): Float = pages.heightPt(page(index)).toFloat()

    /**
     * Draws page [index] onto [canvas], whose matrix maps page points (1/72 inch,
     * origin at the page's top left) to the target's pixels. Whatever matrix and clip
     * the caller set are honoured, so a screen-sized bitmap can show a small part of
     * a page at 20x; only what can reach the clip is drawn, and pictures are decoded
     * at the resolution they land at. The page's area is painted white (or the
     * slide's background) first; nothing outside it is touched.
     *
     * Draw onto a bitmap-backed canvas: the pictures drawn are reused and recycled
     * afterwards, which a recording (hardware) canvas would still need.
     *
     * A damaged shape, picture or table is left out; anything worse, including
     * running out of memory, is an [OfficeCanvasException].
     *
     * @throws IndexOutOfBoundsException if [index] is not a page.
     * @throws IllegalStateException once the document is closed.
     */
    fun drawPage(index: Int, canvas: Canvas) {
        page(index)
        val k = pages.refPxPerPt(index)
        canvas.save()
        try {
            // Pages are drawn in their reference pixels; the matrix takes them to points.
            canvas.scale((1.0 / k).toFloat(), (1.0 / k).toFloat())
            pages.drawRef(canvas, index)
        } catch (e: OfficeCanvasException) {
            throw e
        } catch (e: IndexOutOfBoundsException) {
            throw e
        } catch (e: Exception) {
            throw OfficeCanvasException("could not draw page ${index + 1} of $name: ${e.message}", e)
        } catch (e: OutOfMemoryError) {
            throw OfficeCanvasException("ran out of memory drawing page ${index + 1} of $name", e)
        } catch (e: StackOverflowError) {
            throw OfficeCanvasException("$name is nested too deeply to draw", e)
        } finally {
            canvas.restore()
        }
    }

    /** Page [index] as a bitmap [longSidePx] on its longer side, the same picture [OfficeCanvas.render] gives. */
    internal fun render(index: Int, longSidePx: Int): Bitmap {
        page(index)
        return pages.render(index, longSidePx)
    }

    /** Releases the open file and the cached pictures. Drawing afterwards throws. */
    override fun close() {
        if (closed) return
        closed = true
        pages.close()
    }

    private fun page(index: Int): Int {
        check(!closed) { "$name is closed" }
        if (index !in 0 until pages.pageCount) throw IndexOutOfBoundsException("page $index of ${pages.pageCount}")
        return index
    }
}
