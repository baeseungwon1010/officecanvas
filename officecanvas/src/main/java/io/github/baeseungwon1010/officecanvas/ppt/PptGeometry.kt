package io.github.baeseungwon1010.officecanvas.ppt

import io.github.baeseungwon1010.officecanvas.slide.PathOp

/**
 * Outlines for shapes that aren't a plain rectangle/ellipse: freeforms (their
 * vertices and path segments are stored in the shape's FOPT) and the common
 * preset shapes (MSOSPT), all in the shape box's own 0..1 space.
 */
internal object PptGeometry {

    /** IMsoArray (MS-ODRAW 2.2.5) element bytes: a 6-byte header (nElems,
     *  nElemsAlloc, cbElem) then the elements. cbElem 0xFFF0 means 4-byte elements. */
    private class MsoArray(val bytes: ByteArray) {
        private fun u16(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8)
        private fun i32(o: Int) = u16(o) or (u16(o + 2) shl 16)
        val count = if (bytes.size >= 6) u16(0) else 0
        val elemSize = if (bytes.size >= 6) u16(4).let { if (it == 0xFFF0) 4 else it } else 0
        fun ok() = elemSize > 0 && 6 + count * elemSize <= bytes.size
        /** A point stored as two int16 (4-byte elements) or two int32 (8-byte). */
        fun point(i: Int): Pair<Float, Float> {
            val o = 6 + i * elemSize
            return if (elemSize == 4) Pair(u16(o).toShort().toFloat(), u16(o + 2).toShort().toFloat())
            else Pair(i32(o).toFloat(), i32(o + 4).toFloat())
        }
        fun segment(i: Int): Int = u16(6 + i * elemSize)
    }

    /**
     * pVertices + pSegmentInfo → path. Each segment word is a type in the top
     * 3 bits (0 lineTo, 1 curveTo, 2 moveTo, 3 close, 4 end, 5/6 escapes) and a
     * count in the low 13; curveTo consumes three vertices per count. Missing
     * segment info means "one polyline through every vertex". The geometry box
     * (geoLeft..geoBottom) maps vertices to 0..1; a degenerate one falls back
     * to the vertices' own extent.
     */
    fun freeform(vertices: ByteArray, segments: ByteArray?, geo: IntArray?): List<PathOp>? {
        val v = MsoArray(vertices)
        if (!v.ok() || v.count < 2) return null
        val pts = (0 until v.count).map { v.point(it) }
        var left = geo?.get(0)?.toFloat() ?: 0f
        var top = geo?.get(1)?.toFloat() ?: 0f
        var right = geo?.get(2)?.toFloat() ?: 21600f
        var bottom = geo?.get(3)?.toFloat() ?: 21600f
        if (right <= left || bottom <= top) {
            left = pts.minOf { it.first }; right = pts.maxOf { it.first }
            top = pts.minOf { it.second }; bottom = pts.maxOf { it.second }
        }
        val w = (right - left).takeIf { it > 0f } ?: 1f
        val h = (bottom - top).takeIf { it > 0f } ?: 1f
        fun nx(i: Int) = (pts[i].first - left) / w
        fun ny(i: Int) = (pts[i].second - top) / h

        val ops = mutableListOf<PathOp>()
        val s = segments?.let { MsoArray(it) }?.takeIf { it.ok() && it.count > 0 }
        if (s == null) {
            ops += PathOp.MoveTo(nx(0), ny(0))
            for (i in 1 until pts.size) ops += PathOp.LineTo(nx(i), ny(i))
            return ops
        }
        var vi = 0
        for (k in 0 until s.count) {
            val seg = s.segment(k)
            val count = (seg and 0x1FFF).coerceAtLeast(1)
            when (seg ushr 13) {
                // Each loop stops once the vertices run out, so a huge segment
                // count can't spin through millions of no-op repetitions.
                0 -> for (j in 0 until count) { if (vi >= pts.size) break; ops += PathOp.LineTo(nx(vi), ny(vi)); vi++ }
                1 -> for (j in 0 until count) {
                    if (vi + 2 >= pts.size) { vi = pts.size; break }
                    ops += PathOp.CubicTo(nx(vi), ny(vi), nx(vi + 1), ny(vi + 1), nx(vi + 2), ny(vi + 2))
                    vi += 3
                }
                2 -> if (vi < pts.size) { ops += PathOp.MoveTo(nx(vi), ny(vi)); vi++ }
                3 -> ops += PathOp.Close
                4 -> {}
                else -> vi += seg and 0xFF // escapes: low byte is how many vertices they consume
            }
        }
        // A path must start with a moveTo for Android's Path to place it.
        if (ops.firstOrNull() !is PathOp.MoveTo) ops.add(0, PathOp.MoveTo(nx(0), ny(0)))
        return ops
    }

    private fun poly(vararg xy: Float): List<PathOp> {
        val ops = mutableListOf<PathOp>(PathOp.MoveTo(xy[0], xy[1]))
        var i = 2
        while (i + 1 < xy.size) { ops += PathOp.LineTo(xy[i], xy[i + 1]); i += 2 }
        ops += PathOp.Close
        return ops
    }

    private fun star(points: Int, inner: Float): List<PathOp> {
        val xy = FloatArray(points * 4)
        for (i in 0 until points * 2) {
            val r = if (i % 2 == 0) 0.5f else 0.5f * inner
            val a = Math.toRadians(-90.0 + i * 180.0 / points)
            xy[i * 2] = 0.5f + (r * Math.cos(a)).toFloat()
            xy[i * 2 + 1] = 0.5f + (r * Math.sin(a)).toFloat()
        }
        return poly(*xy)
    }

    /** Adjust value [i] (FOPT 0x147 + i) as a 0..1 fraction of the 21600 shape grid. */
    private fun adj(adjusts: IntArray, i: Int, default: Int) = (adjusts.getOrNull(i)?.takeIf { it != Int.MIN_VALUE } ?: default) / 21600f

    /**
     * The common MSOSPT presets as outlines, honoring their first adjust values
     * where they set the defining proportion (arrow head/shaft, triangle apex,
     * trapezoid/parallelogram slant). Anything not listed falls back to the
     * caller's rectangle.
     */
    fun preset(spt: Int, adjusts: IntArray): List<PathOp>? = when (spt) {
        4, 110 -> poly(0.5f, 0f, 1f, 0.5f, 0.5f, 1f, 0f, 0.5f) // diamond, flowchart decision
        5 -> adj(adjusts, 0, 10800).let { poly(it, 0f, 1f, 1f, 0f, 1f) } // isosceles triangle
        6 -> poly(0f, 0f, 1f, 1f, 0f, 1f) // right triangle
        7, 111 -> adj(adjusts, 0, 5400).let { poly(it, 0f, 1f, 0f, 1f - it, 1f, 0f, 1f) } // parallelogram, flowchart data
        8 -> adj(adjusts, 0, 5400).let { poly(0f, 0f, 1f, 0f, 1f - it, 1f, it, 1f) } // trapezoid (MSO: wide top)
        9 -> adj(adjusts, 0, 5400).let { poly(it, 0f, 1f - it, 0f, 1f, 0.5f, 1f - it, 1f, it, 1f, 0f, 0.5f) } // hexagon
        10 -> adj(adjusts, 0, 6326).let { poly(it, 0f, 1f - it, 0f, 1f, it, 1f, 1f - it, 1f - it, 1f, it, 1f, 0f, 1f - it, 0f, it) } // octagon
        11 -> adj(adjusts, 0, 5400).let { poly(it, 0f, 1f - it, 0f, 1f - it, it, 1f, it, 1f, 1f - it, 1f - it, 1f - it, 1f - it, 1f, it, 1f, it, 1f - it, 0f, 1f - it, 0f, it, it, it) } // plus
        12 -> star(5, 0.38f)
        56 -> poly(0.5f, 0f, 1f, 0.38f, 0.81f, 1f, 0.19f, 1f, 0f, 0.38f) // pentagon
        92 -> star(24, 0.75f)
        58 -> star(8, 0.75f)
        59 -> star(16, 0.75f)
        60 -> star(32, 0.85f)
        187 -> star(4, 0.38f)
        13 -> { // right arrow: adj0 = head start x, adj1 = shaft top y
            val hx = adj(adjusts, 0, 16200); val sy = adj(adjusts, 1, 5400)
            poly(0f, sy, hx, sy, hx, 0f, 1f, 0.5f, hx, 1f, hx, 1f - sy, 0f, 1f - sy)
        }
        66 -> { // left arrow
            val hx = adj(adjusts, 0, 5400); val sy = adj(adjusts, 1, 5400)
            poly(1f, sy, hx, sy, hx, 0f, 0f, 0.5f, hx, 1f, hx, 1f - sy, 1f, 1f - sy)
        }
        68 -> { // up arrow
            val hy = adj(adjusts, 0, 5400); val sx = adj(adjusts, 1, 5400)
            poly(sx, 1f, sx, hy, 0f, hy, 0.5f, 0f, 1f, hy, 1f - sx, hy, 1f - sx, 1f)
        }
        67 -> { // down arrow
            val hy = adj(adjusts, 0, 16200); val sx = adj(adjusts, 1, 5400)
            poly(sx, 0f, sx, hy, 0f, hy, 0.5f, 1f, 1f, hy, 1f - sx, hy, 1f - sx, 0f)
        }
        69 -> { // left-right arrow
            val hx = adj(adjusts, 0, 4320); val sy = adj(adjusts, 1, 5400)
            poly(0f, 0.5f, hx, 0f, hx, sy, 1f - hx, sy, 1f - hx, 0f, 1f, 0.5f, 1f - hx, 1f, 1f - hx, 1f - sy, hx, 1f - sy, hx, 1f)
        }
        70 -> { // up-down arrow
            val hy = adj(adjusts, 0, 4320); val sx = adj(adjusts, 1, 5400)
            poly(0.5f, 0f, 1f, hy, 1f - sx, hy, 1f - sx, 1f - hy, 1f, 1f - hy, 0.5f, 1f, 0f, 1f - hy, sx, 1f - hy, sx, hy, 0f, hy)
        }
        15 -> adj(adjusts, 0, 16200).let { poly(0f, 0f, it, 0f, 1f, 0.5f, it, 1f, 0f, 1f) } // home plate (pentagon arrow)
        55 -> adj(adjusts, 0, 16200).let { poly(0f, 0f, it, 0f, 1f, 0.5f, it, 1f, 0f, 1f, 1f - it, 0.5f) } // chevron
        else -> null
    }
}
