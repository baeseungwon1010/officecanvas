package io.github.baeseungwon1010.officecanvas.pdf

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A path painted on the page, in device space (points, y down): subpaths of points,
 * curves as (x, y, c1x, c1y, c2x, c2y), and rectangles (x, y, w, h). [fill] and
 * [stroke] are the colors it was painted with, null where it wasn't; [clip] is the
 * clipping path's box.
 */
internal class PdfShape(
    val subpaths: List<List<DoubleArray>>, val rects: List<DoubleArray>,
    val fill: Int?, val stroke: Int?, val lineWidth: Double, val evenOdd: Boolean,
    val clip: DoubleArray?, val fillAlpha: Double, val strokeAlpha: Double
) {
    /** The box it covers, within its clip; null if clipped away. */
    val box: DoubleArray? = run {
        val xs = subpaths.flatMap { sp -> sp.flatMap { p -> p.indices.filter { it % 2 == 0 }.map { p[it] } } } + rects.flatMap { listOf(it[0], it[0] + it[2]) }
        val ys = subpaths.flatMap { sp -> sp.flatMap { p -> p.indices.filter { it % 2 == 1 }.map { p[it] } } } + rects.flatMap { listOf(it[1], it[1] + it[3]) }
        if (xs.isEmpty()) null else {
            val half = if (stroke != null) lineWidth / 2 else 0.0
            var b = doubleArrayOf(xs.min() - half, ys.min() - half, xs.max() + half, ys.max() + half)
            if (clip != null) b = doubleArrayOf(max(b[0], clip[0]), max(b[1], clip[1]), min(b[2], clip[2]), min(b[3], clip[3]))
            b.takeIf { it[2] > it[0] && it[3] > it[1] }
        }
    }

    /**
     * A drawing rather than a table's rule, a cell's or a paragraph's shading, or an
     * underline: a curve, a slanted line, or a filled shape that isn't a rectangle.
     */
    val isArt: Boolean get() {
        if (fill == null && stroke == null) return false
        for (sp in subpaths) {
            if (sp.any { it.size > 2 }) return true
            for (k in 1 until sp.size) {
                val dx = abs(sp[k][0] - sp[k - 1][0]); val dy = abs(sp[k][1] - sp[k - 1][1])
                if (dx > 0.5 && dy > 0.5) return true
            }
            // A filled shape of level and upright sides other than a rectangle (an arrow, an L).
            if (fill != null && sp.distinctBy { (Math.round(it[0] * 10) to Math.round(it[1] * 10)) }.size > 5) return true
        }
        // A filled box (a slide's background, a panel, a highlight): drawn too, as there's nothing else to
        // draw it with but a cell's or a paragraph's shading, which only some are, and that in the same place.
        if (fill != null) {
            if (rects.any { it[2] >= 3 && it[3] >= 3 }) return true
            if (subpaths.any { sp -> sp.size >= 4 && sp.maxOf { it[0] } - sp.minOf { it[0] } >= 3 && sp.maxOf { it[1] } - sp.minOf { it[1] } >= 3 }) return true
        }
        return false
    }
}

/**
 * Drawings as pictures: curves, slanted lines and shapes other than rectangles,
 * grouped where they lie close together, each group drawn with everything else
 * painted inside it. Rectangles and level or upright lines on their own stay what
 * the layout reads them as (rules, borders, shading).
 */
internal object PdfArt {

    /** Groups closer than this (points) are one drawing. */
    private const val JOIN = 6.0
    /** At most this many pixels a drawing, and at most this many times its size in points. */
    private const val MAX_PIXELS = 2_000_000.0
    private const val MAX_SCALE = 3.0

    fun pictures(shapes: List<PdfShape>, pageW: Double, pageH: Double): List<PdfPicture> {
        val seeds = shapes.filter { s -> s.isArt && s.box != null && !isWhite(s) }
        if (seeds.isEmpty() || seeds.size > 20_000) return emptyList()
        // Boxes of seeds within JOIN of each other, merged until none are.
        val boxes = ArrayList<DoubleArray>()
        for (s in seeds) boxes += s.box!!.copyOf()
        var merged = true
        var rounds = 0
        while (merged && rounds++ < 50) {
            merged = false
            var i = 0
            while (i < boxes.size) {
                var j = i + 1
                while (j < boxes.size) {
                    val a = boxes[i]; val b = boxes[j]
                    if (b[0] <= a[2] + JOIN && a[0] <= b[2] + JOIN && b[1] <= a[3] + JOIN && a[1] <= b[3] + JOIN) {
                        a[0] = min(a[0], b[0]); a[1] = min(a[1], b[1]); a[2] = max(a[2], b[2]); a[3] = max(a[3], b[3])
                        boxes.removeAt(j); merged = true
                    } else j++
                }
                i++
            }
        }
        val out = ArrayList<PdfPicture>()
        for (b in boxes) {
            val x0 = max(b[0], 0.0); val y0 = max(b[1], 0.0); val x1 = min(b[2], pageW); val y1 = min(b[3], pageH)
            val w = x1 - x0; val h = y1 - y0
            if (w < 2 || h < 2 || w * h < 30) continue
            // What's painted inside it: the seeds, and the rules and boxes drawn with them (a chart's axes).
            val inside = shapes.filter { s ->
                val sb = s.box ?: return@filter false
                val iw = min(sb[2], x1 + 1) - max(sb[0], x0 - 1); val ih = min(sb[3], y1 + 1) - max(sb[1], y0 - 1)
                iw > 0 && ih > 0 && iw * ih >= (sb[2] - sb[0]) * (sb[3] - sb[1]) * 0.8
            }
            out += PdfPicture(x0, y0, w, h, drawing = true) { render(inside, x0, y0, w, h) }
            if (out.size >= 200) break
        }
        return out
    }

    private fun isWhite(s: PdfShape): Boolean {
        fun white(c: Int?) = c == null || (c shr 16 and 0xFF) > 250 && (c shr 8 and 0xFF) > 250 && (c and 0xFF) > 250
        return white(s.fill) && white(s.stroke)
    }

    /** The shapes drawn over a transparent [w] x [h] box at [x0], [y0], as PNG; null without Android's graphics (a plain JVM). */
    private fun render(shapes: List<PdfShape>, x0: Double, y0: Double, w: Double, h: Double): PdfImages.Image? = try {
        val scale = min(MAX_SCALE, sqrt(MAX_PIXELS / (w * h))).coerceAtLeast(0.5)
        val pw = (w * scale).toInt().coerceAtLeast(1); val ph = (h * scale).toInt().coerceAtLeast(1)
        val bmp = android.graphics.Bitmap.createBitmap(pw, ph, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(bmp)
            canvas.scale(scale.toFloat(), scale.toFloat())
            canvas.translate((-x0).toFloat(), (-y0).toFloat())
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            for (s in shapes) {
                val path = android.graphics.Path()
                for (sp in s.subpaths) {
                    if (sp.isEmpty()) continue
                    path.moveTo(sp[0][0].toFloat(), sp[0][1].toFloat())
                    for (k in 1 until sp.size) {
                        val p = sp[k]
                        if (p.size >= 6) path.cubicTo(p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toFloat(), p[0].toFloat(), p[1].toFloat())
                        else path.lineTo(p[0].toFloat(), p[1].toFloat())
                    }
                    if (sp.size > 2 && abs(sp.last()[0] - sp[0][0]) < 0.01 && abs(sp.last()[1] - sp[0][1]) < 0.01) path.close()
                }
                for (r in s.rects) path.addRect(r[0].toFloat(), r[1].toFloat(), (r[0] + r[2]).toFloat(), (r[1] + r[3]).toFloat(), android.graphics.Path.Direction.CW)
                path.fillType = if (s.evenOdd) android.graphics.Path.FillType.EVEN_ODD else android.graphics.Path.FillType.WINDING
                canvas.save()
                s.clip?.let { c -> canvas.clipRect(c[0].toFloat(), c[1].toFloat(), c[2].toFloat(), c[3].toFloat()) }
                s.fill?.let { c ->
                    paint.style = android.graphics.Paint.Style.FILL
                    paint.color = (c and 0xFFFFFF) or ((s.fillAlpha * 255).toInt().coerceIn(0, 255) shl 24)
                    canvas.drawPath(path, paint)
                }
                s.stroke?.let { c ->
                    paint.style = android.graphics.Paint.Style.STROKE
                    paint.strokeWidth = s.lineWidth.toFloat()
                    paint.color = (c and 0xFFFFFF) or ((s.strokeAlpha * 255).toInt().coerceIn(0, 255) shl 24)
                    canvas.drawPath(path, paint)
                }
                canvas.restore()
            }
            val out = java.io.ByteArrayOutputStream()
            if (bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) PdfImages.Image(out.toByteArray(), true, pw, ph) else null
        } finally {
            bmp.recycle()
        }
    } catch (e: Exception) { null } catch (e: LinkageError) { null } catch (e: OutOfMemoryError) { null }
}
