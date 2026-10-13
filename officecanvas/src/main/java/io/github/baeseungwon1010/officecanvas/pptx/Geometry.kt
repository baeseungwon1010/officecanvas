package io.github.baeseungwon1010.officecanvas.pptx

import io.github.baeseungwon1010.officecanvas.slide.PathOp
import org.w3c.dom.Element
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Outlines for DrawingML shapes, in the shape box's own 0..1 space.
 *
 * Preset geometries (<a:prstGeom prst="rightArrow">) are defined by formulas in the
 * specification's presetShapeDefinitions; the common ones are reproduced here with
 * their adjust values (<a:avLst><a:gd name="adj1" fmla="val 50000"/>). Most
 * adjustments are fractions of the shorter side (ss), so the result depends on
 * the box's aspect ratio. Presets not listed fall back to the renderer's rectangle.
 */
internal object Geometry {

    /** What a preset becomes: an outline, or a name the renderer draws itself. */
    class Result(val geometry: String?, val outline: List<PathOp>?, val open: Boolean = false)

    fun preset(name: String, avLst: Element?, wEmu: Long, hEmu: Long): Result {
        val w = wEmu.toDouble().coerceAtLeast(1.0)
        val h = hEmu.toDouble().coerceAtLeast(1.0)
        val adj = adjustValues(avLst)
        fun a(key: String, default: Int) = (adj[key] ?: default) / 100000.0
        val ss = minOf(w, h)
        val b = PathBuilder(w, h)
        val hc = w / 2; val vc = h / 2

        when (name) {
            "rect", "flowChartProcess", "flowChartPredefinedProcess", "frame", "cube", "can",
            "flowChartMagneticDisk", "flowChartInternalStorage", "bevel" -> return Result("rect", null)
            "ellipse", "flowChartConnector", "cloud", "cloudCallout", "wedgeEllipseCallout", "donut",
            "teardrop", "smileyFace", "sun", "flowChartOr", "flowChartSummingJunction" -> return Result("ellipse", null)
            "line", "straightConnector1" -> return Result("line", null)

            "roundRect", "flowChartAlternateProcess" -> b.roundRect(ss * a("adj", 16667).coerceIn(0.0, 0.5))
            "flowChartTerminator" -> b.roundRect(minOf(w * 0.1609, h / 2), h / 2)
            "round2SameRect" -> b.roundCorners(ss * a("adj1", 16667), ss * a("adj1", 16667), ss * a("adj2", 0), ss * a("adj2", 0))
            "round1Rect" -> b.roundCorners(0.0, ss * a("adj", 16667), 0.0, 0.0)
            "snip1Rect" -> { val d = ss * a("adj", 16667); b.poly(0.0, 0.0, w - d, 0.0, w, d, w, h, 0.0, h) }
            "snip2SameRect" -> { val d = ss * a("adj1", 16667); b.poly(d, 0.0, w - d, 0.0, w, d, w, h, 0.0, h, 0.0, d) }
            "triangle", "flowChartExtract" -> b.poly(w * a("adj", 50000), 0.0, w, h, 0.0, h)
            "flowChartMerge" -> b.poly(0.0, 0.0, w, 0.0, hc, h)
            "rtTriangle" -> b.poly(0.0, 0.0, w, h, 0.0, h)
            "diamond", "flowChartDecision" -> b.poly(hc, 0.0, w, vc, hc, h, 0.0, vc)
            "parallelogram" -> { val x = minOf(ss * a("adj", 25000), w); b.poly(x, 0.0, w, 0.0, w - x, h, 0.0, h) }
            "flowChartInputOutput" -> { val x = w / 5; b.poly(x, 0.0, w, 0.0, w - x, h, 0.0, h) }
            "trapezoid" -> { val x = minOf(ss * a("adj", 25000), hc); b.poly(x, 0.0, w - x, 0.0, w, h, 0.0, h) }
            "flowChartManualOperation" -> { val x = w / 5; b.poly(0.0, 0.0, w, 0.0, w - x, h, x, h) }
            "flowChartManualInput" -> b.poly(0.0, h / 5, w, 0.0, w, h, 0.0, h)
            "pentagon" -> b.poly(hc, 0.0, w, h * 0.3820, w * 0.8090, h, w * 0.1910, h, 0.0, h * 0.3820)
            "hexagon" -> { val x = minOf(ss * a("adj", 25000), hc); b.poly(x, 0.0, w - x, 0.0, w, vc, w - x, h, x, h, 0.0, vc) }
            "flowChartPreparation" -> { val x = w / 5; b.poly(x, 0.0, w - x, 0.0, w, vc, w - x, h, x, h, 0.0, vc) }
            "heptagon" -> b.regular(7)
            "octagon" -> { val x = ss * a("adj", 29289); b.poly(x, 0.0, w - x, 0.0, w, x, w, h - x, w - x, h, x, h, 0.0, h - x, 0.0, x) }
            "decagon" -> b.regular(10)
            "dodecagon" -> b.regular(12)
            "plus", "mathPlus" -> {
                val x = ss * a("adj", 25000)
                b.poly(x, 0.0, w - x, 0.0, w - x, x, w, x, w, h - x, w - x, h - x, w - x, h, x, h, x, h - x, 0.0, h - x, 0.0, x, x, x)
            }
            "star4" -> b.star(4, a("adj", 12500) / 0.5)
            "star5" -> b.star(5, a("adj", 19098) / 0.5)
            "star6" -> b.star(6, a("adj", 28868) / 0.5)
            "star7" -> b.star(7, a("adj", 34601) / 0.5)
            "star8" -> b.star(8, a("adj", 38250) / 0.5)
            "star10" -> b.star(10, a("adj", 42533) / 0.5)
            "star12" -> b.star(12, a("adj", 37500) / 0.5)
            "star16" -> b.star(16, a("adj", 37500) / 0.5)
            "star24" -> b.star(24, a("adj", 37500) / 0.5)
            "star32" -> b.star(32, a("adj", 37500) / 0.5)
            "rightArrow", "notchedRightArrow", "stripedRightArrow" -> {
                val dy = h * a("adj1", 50000) / 2; val x1 = w - minOf(ss * a("adj2", 50000), w)
                b.poly(0.0, vc - dy, x1, vc - dy, x1, 0.0, w, vc, x1, h, x1, vc + dy, 0.0, vc + dy)
            }
            "leftArrow" -> {
                val dy = h * a("adj1", 50000) / 2; val x1 = minOf(ss * a("adj2", 50000), w)
                b.poly(w, vc - dy, x1, vc - dy, x1, 0.0, 0.0, vc, x1, h, x1, vc + dy, w, vc + dy)
            }
            "upArrow" -> {
                val dx = w * a("adj1", 50000) / 2; val y1 = minOf(ss * a("adj2", 50000), h)
                b.poly(hc - dx, h, hc - dx, y1, 0.0, y1, hc, 0.0, w, y1, hc + dx, y1, hc + dx, h)
            }
            "downArrow" -> {
                val dx = w * a("adj1", 50000) / 2; val y1 = h - minOf(ss * a("adj2", 50000), h)
                b.poly(hc - dx, 0.0, hc - dx, y1, 0.0, y1, hc, h, w, y1, hc + dx, y1, hc + dx, 0.0)
            }
            "leftRightArrow" -> {
                val dy = h * a("adj1", 50000) / 2; val x1 = minOf(ss * a("adj2", 50000), hc); val x2 = w - x1
                b.poly(0.0, vc, x1, 0.0, x1, vc - dy, x2, vc - dy, x2, 0.0, w, vc, x2, h, x2, vc + dy, x1, vc + dy, x1, h)
            }
            "upDownArrow" -> {
                val dx = w * a("adj1", 50000) / 2; val y1 = minOf(ss * a("adj2", 50000), vc); val y2 = h - y1
                b.poly(hc, 0.0, w, y1, hc + dx, y1, hc + dx, y2, w, y2, hc, h, 0.0, y2, hc - dx, y2, hc - dx, y1, 0.0, y1)
            }
            "bentUpArrow" -> {
                val y3 = ss * a("adj3", 25000); val dx1 = ss * a("adj2", 25000) * 2; val x1 = w - dx1
                val x3 = w - ss * a("adj2", 25000); val dx2 = ss * a("adj1", 25000) / 2
                val x2 = x3 - dx2; val x4 = x3 + dx2; val y2 = h - ss * a("adj1", 25000)
                b.poly(0.0, y2, x2, y2, x2, y3, x1, y3, x3, 0.0, w, y3, x4, y3, x4, h, 0.0, h)
            }
            "bentArrow" -> {
                val th = ss * a("adj1", 25000); val aw2 = ss * a("adj2", 25000); val ah = ss * a("adj3", 25000)
                val top = aw2 - th / 2; val bot = aw2 + th / 2
                b.poly(0.0, h, 0.0, top, w - ah, top, w - ah, 0.0, w, aw2, w - ah, 2 * aw2, w - ah, bot, th, bot, th, h)
            }
            "homePlate" -> { val x1 = w - minOf(ss * a("adj", 50000), w); b.poly(0.0, 0.0, x1, 0.0, w, vc, x1, h, 0.0, h) }
            "chevron" -> {
                val x1 = minOf(ss * a("adj", 50000), w); val x2 = w - x1
                b.poly(0.0, 0.0, x2, 0.0, w, vc, x2, h, 0.0, h, x1, vc)
            }
            "flowChartOffpageConnector" -> b.poly(0.0, 0.0, w, 0.0, w, h * 0.8, hc, h, 0.0, h * 0.8)
            "flowChartDocument" -> {
                b.move(0.0, 0.0); b.line(w, 0.0); b.line(w, h * 0.8)
                b.cubic(w * 0.75, h * 0.6, w * 0.25, h * 1.1, 0.0, h * 0.9); b.close()
            }
            "wedgeRectCallout", "wedgeRoundRectCallout" -> {
                val tx = hc + w * a("adj1", -20833); val ty = vc + h * a("adj2", 62500)
                when {
                    ty > h -> { val x1 = if (tx < hc) w / 6 else w * 7 / 12; b.poly(0.0, 0.0, w, 0.0, w, h, x1 + w / 6, h, tx, ty, x1, h, 0.0, h) }
                    ty < 0 -> { val x1 = if (tx < hc) w / 6 else w * 7 / 12; b.poly(0.0, 0.0, x1, 0.0, tx, ty, x1 + w / 6, 0.0, w, 0.0, w, h, 0.0, h) }
                    tx < 0 -> { val y1 = if (ty < vc) h / 6 else h * 7 / 12; b.poly(0.0, 0.0, w, 0.0, w, h, 0.0, h, 0.0, y1 + h / 6, tx, ty, 0.0, y1) }
                    tx > w -> { val y1 = if (ty < vc) h / 6 else h * 7 / 12; b.poly(0.0, 0.0, w, 0.0, w, y1, tx, ty, w, y1 + h / 6, w, h, 0.0, h) }
                    else -> return Result("rect", null)
                }
            }
            "bentConnector2" -> { b.move(0.0, 0.0); b.line(w, 0.0); b.line(w, h); return Result(null, b.ops, open = true) }
            "bentConnector3", "bentConnector4", "bentConnector5" -> {
                val x1 = w * a("adj1", 50000)
                b.move(0.0, 0.0); b.line(x1, 0.0); b.line(x1, h); b.line(w, h); return Result(null, b.ops, open = true)
            }
            "curvedConnector2" -> { b.move(0.0, 0.0); b.cubic(w / 2, 0.0, w, h / 2, w, h); return Result(null, b.ops, open = true) }
            "curvedConnector3", "curvedConnector4", "curvedConnector5" -> {
                val x1 = w * a("adj1", 50000)
                b.move(0.0, 0.0); b.cubic(x1, 0.0, x1, h, w, h); return Result(null, b.ops, open = true)
            }
            "arc" -> {
                val st = (adj["adj1"] ?: 16200000) / 60000.0; val en = (adj["adj2"] ?: 0) / 60000.0
                var sw = en - st; if (sw <= 0) sw += 360.0
                b.arcAround(hc, vc, hc, vc, st, sw, moveFirst = true)
                return Result(null, b.ops, open = true)
            }
            else -> return Result("rect", null)
        }
        return Result(null, b.ops)
    }

    /**
     * <a:custGeom>: one or more paths in their own coordinate space (path w/h, or the
     * shape's size when omitted). Returns null when there is nothing drawable.
     * [open] is true when every path is stroke-only (fill="none"), so the shape must
     * not be filled.
     */
    fun custom(custGeom: Element, wEmu: Long, hEmu: Long): Result? {
        val paths = custGeom.child(NS_A, "pathLst")?.all(NS_A, "path").orEmpty()
        if (paths.isEmpty()) return null
        val ops = mutableListOf<PathOp>()
        var allOpen = true
        for (path in paths) {
            val pw = (path.longOrNull("w")?.takeIf { it > 0 } ?: wEmu).toDouble().coerceAtLeast(1.0)
            val ph = (path.longOrNull("h")?.takeIf { it > 0 } ?: hEmu).toDouble().coerceAtLeast(1.0)
            if (path.getAttribute("fill") != "none") allOpen = false
            val b = PathBuilder(pw, ph)
            fun pts(node: Element) = node.all(NS_A, "pt").map { it.long("x").toDouble() to it.long("y").toDouble() }
            for (cmd in path.children()) {
                when (cmd.localName) {
                    "moveTo" -> pts(cmd).firstOrNull()?.let { b.move(it.first, it.second) }
                    "lnTo" -> pts(cmd).firstOrNull()?.let { b.line(it.first, it.second) }
                    "cubicBezTo" -> pts(cmd).takeIf { it.size == 3 }?.let { p ->
                        b.cubic(p[0].first, p[0].second, p[1].first, p[1].second, p[2].first, p[2].second)
                    }
                    "quadBezTo" -> pts(cmd).takeIf { it.size == 2 }?.let { p -> b.quad(p[0].first, p[0].second, p[1].first, p[1].second) }
                    "arcTo" -> b.arcTo(cmd.long("wR").toDouble(), cmd.long("hR").toDouble(),
                        cmd.long("stAng") / 60000.0, cmd.long("swAng") / 60000.0)
                    "close" -> b.close()
                }
            }
            ops += b.ops
        }
        return if (ops.isEmpty()) null else Result(null, ops, open = allOpen)
    }

    private fun adjustValues(avLst: Element?): Map<String, Int> =
        avLst?.all(NS_A, "gd").orEmpty().mapNotNull { gd ->
            val v = gd.getAttribute("fmla").removePrefix("val ").trim().toIntOrNull() ?: return@mapNotNull null
            gd.getAttribute("name") to v
        }.toMap()

    /** Builds [PathOp]s from coordinates in a w x h space, normalized to 0..1. */
    private class PathBuilder(private val w: Double, private val h: Double) {
        val ops = mutableListOf<PathOp>()
        private var cx = 0.0
        private var cy = 0.0

        private fun nx(x: Double) = (x / w).toFloat()
        private fun ny(y: Double) = (y / h).toFloat()

        fun move(x: Double, y: Double) { ops += PathOp.MoveTo(nx(x), ny(y)); cx = x; cy = y }
        fun line(x: Double, y: Double) { ops += PathOp.LineTo(nx(x), ny(y)); cx = x; cy = y }
        fun cubic(x1: Double, y1: Double, x2: Double, y2: Double, x: Double, y: Double) {
            ops += PathOp.CubicTo(nx(x1), ny(y1), nx(x2), ny(y2), nx(x), ny(y)); cx = x; cy = y
        }
        fun quad(qx: Double, qy: Double, x: Double, y: Double) =
            cubic(cx + 2.0 / 3 * (qx - cx), cy + 2.0 / 3 * (qy - cy), x + 2.0 / 3 * (qx - x), y + 2.0 / 3 * (qy - y), x, y)
        fun close() { ops += PathOp.Close }

        fun poly(vararg xy: Double) {
            move(xy[0], xy[1])
            for (i in 2 until xy.size step 2) line(xy[i], xy[i + 1])
            close()
        }

        fun regular(n: Int) {
            val pts = DoubleArray(n * 2)
            for (i in 0 until n) {
                val t = -PI / 2 + 2 * PI * i / n
                pts[i * 2] = w / 2 + w / 2 * cos(t); pts[i * 2 + 1] = h / 2 + h / 2 * sin(t)
            }
            poly(*pts)
        }

        /** [inner]: inner radius as a fraction of the outer one. */
        fun star(points: Int, inner: Double) {
            val pts = DoubleArray(points * 4)
            for (i in 0 until points * 2) {
                val t = -PI / 2 + PI * i / points
                val r = if (i % 2 == 0) 1.0 else inner.coerceIn(0.05, 1.0)
                pts[i * 2] = w / 2 + w / 2 * r * cos(t); pts[i * 2 + 1] = h / 2 + h / 2 * r * sin(t)
            }
            poly(*pts)
        }

        fun roundRect(rx: Double, ry: Double = rx) {
            if (rx <= 0 || ry <= 0) { poly(0.0, 0.0, w, 0.0, w, h, 0.0, h); return }
            move(rx, 0.0); line(w - rx, 0.0); arcTo(rx, ry, 270.0, 90.0)
            line(w, h - ry); arcTo(rx, ry, 0.0, 90.0)
            line(rx, h); arcTo(rx, ry, 90.0, 90.0)
            line(0.0, ry); arcTo(rx, ry, 180.0, 90.0)
            close()
        }

        /** Corner radii clockwise from top left. */
        fun roundCorners(tl: Double, tr: Double, br: Double, bl: Double) {
            move(tl, 0.0); line(w - tr, 0.0); if (tr > 0) arcTo(tr, tr, 270.0, 90.0)
            line(w, h - br); if (br > 0) arcTo(br, br, 0.0, 90.0)
            line(bl, h); if (bl > 0) arcTo(bl, bl, 90.0, 90.0)
            line(0.0, tl); if (tl > 0) arcTo(tl, tl, 180.0, 90.0)
            close()
        }

        /** DrawingML arcTo: the current point lies on an ellipse (radii [wr], [hr]) at
         *  angle [startDeg]; sweep [sweepDeg] clockwise (y down). */
        fun arcTo(wr: Double, hr: Double, startDeg: Double, sweepDeg: Double) {
            val st = Math.toRadians(startDeg)
            arcAround(cx - wr * cos(st), cy - hr * sin(st), wr, hr, startDeg, sweepDeg, moveFirst = false)
        }

        fun arcAround(ox: Double, oy: Double, wr: Double, hr: Double, startDeg: Double, sweepDeg: Double, moveFirst: Boolean) {
            if (abs(sweepDeg) < 1e-6) return
            val segments = ceil(abs(sweepDeg) / 90.0).toInt().coerceAtLeast(1)
            val delta = Math.toRadians(sweepDeg / segments)
            var t = Math.toRadians(startDeg)
            if (moveFirst) move(ox + wr * cos(t), oy + hr * sin(t))
            val k = 4.0 / 3.0 * tan(delta / 4)
            repeat(segments) {
                val t2 = t + delta
                cubic(
                    ox + wr * (cos(t) - k * sin(t)), oy + hr * (sin(t) + k * cos(t)),
                    ox + wr * (cos(t2) + k * sin(t2)), oy + hr * (sin(t2) - k * cos(t2)),
                    ox + wr * cos(t2), oy + hr * sin(t2)
                )
                t = t2
            }
        }
    }
}
