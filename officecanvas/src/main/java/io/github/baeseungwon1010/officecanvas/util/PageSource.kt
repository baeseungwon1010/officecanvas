package io.github.baeseungwon1010.officecanvas.util

import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.Closeable

/**
 * A parsed document's pages, drawn one at a time onto any canvas. Pages are
 * drawn in reference pixels — the page's long side [REF_LONG_SIDE_PX] px — so
 * layout decisions made in pixels come out the same at every output size.
 */
internal interface PageSource : Closeable {
    val pageCount: Int
    fun widthPt(index: Int): Double
    fun heightPt(index: Int): Double

    /** Reference pixels per point on page [index]. */
    fun refPxPerPt(index: Int): Double

    /** Draws page [index] in reference pixels under the canvas's matrix and clip. */
    fun drawRef(canvas: Canvas, index: Int)

    /** The bitmap size of page [index] with [longSidePx] on its longer side. */
    fun renderSize(index: Int, longSidePx: Int): Pair<Int, Int>

    /** Page [index] as a bitmap [longSidePx] on its longer side. */
    fun render(index: Int, longSidePx: Int): Bitmap {
        val (w, h) = renderSize(index, longSidePx)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            // Exactly 1:1 at the reference size, so that output stays pixel for pixel
            // what drawing straight into the bitmap gave.
            if (longSidePx != REF_LONG_SIDE_PX) canvas.scale(longSidePx.toFloat() / REF_LONG_SIDE_PX, longSidePx.toFloat() / REF_LONG_SIDE_PX)
            drawRef(canvas, index)
        } catch (e: Throwable) {
            bitmap.recycle()
            throw e
        }
        return bitmap
    }

    companion object {
        /** The size pages are laid out at: OfficeCanvas.DEFAULT_LONG_SIDE_PX. */
        const val REF_LONG_SIDE_PX = 1600

        /** Picture cache while rendering whole pages: a picture repeated on many pages is decoded once. */
        const val RENDER_CACHE_BYTES = 32L shl 20
    }
}
