package io.github.baeseungwon1010.officecanvas.util

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pages are drawn in their own units under whatever matrix the caller set, so a
 * width meant as "at least one pixel" has to be worked out from that matrix:
 * one unit is a pixel in a full-page image but twenty pixels in a region drawn
 * at 20x.
 */
internal object DeviceScale {

    /** Device pixels per canvas unit (the matrix's mean scale; perspective is ignored). */
    @Suppress("DEPRECATION")
    fun pxPerUnit(canvas: Canvas): Float {
        val v = FloatArray(9)
        canvas.matrix.getValues(v)
        val s = sqrt(abs(v[Matrix.MSCALE_X] * v[Matrix.MSCALE_Y] - v[Matrix.MSKEW_X] * v[Matrix.MSKEW_Y]))
        // A rotation leaves a scale of 0.99999994; a page drawn 1:1 keeps whole pixels.
        return if (!s.isFinite() || s <= 0f) 1f else if (abs(s - 1f) < 1e-4f) 1f else s
    }

    /** [px] device pixels in canvas units: the thinnest a line may be drawn. */
    fun minStroke(canvas: Canvas, px: Float = 1f): Float = px / pxPerUnit(canvas)

    /** True when nothing inside [bounds] (canvas units) can reach the clip. */
    @Suppress("DEPRECATION")
    fun outside(canvas: Canvas, bounds: RectF): Boolean = canvas.quickReject(bounds, Canvas.EdgeType.AA)

    /** [r] grown to cover itself turned any amount about its centre. */
    fun anyTurn(r: RectF): RectF {
        val half = sqrt(r.width() * r.width() + r.height() * r.height()) / 2f
        return RectF(r.centerX() - half, r.centerY() - half, r.centerX() + half, r.centerY() + half)
    }
}
