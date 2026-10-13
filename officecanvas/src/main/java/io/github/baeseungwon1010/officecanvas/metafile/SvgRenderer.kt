package io.github.baeseungwon1010.officecanvas.metafile

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.w3c.dom.Element
import org.w3c.dom.Node
import io.github.baeseungwon1010.officecanvas.util.SafeXml
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Draws the SVG subset Office icons use: paths, basic shapes, groups, transforms,
 * fill/stroke attributes and `<style>` class rules. Office 365 inserts icons as
 * SVG pictures, often without a PNG fallback, and Android has no SVG decoder.
 * Text, gradients, patterns, masks, filters and `<use>` are ignored.
 */
internal object SvgRenderer {

    fun draw(canvas: Canvas, svg: ByteArray, dst: RectF) {
        val root = SafeXml.parse(svg, namespaceAware = false)?.documentElement ?: return
        if (root.tagName.substringAfter(':') != "svg") return

        val viewBox = root.getAttribute("viewBox").numbers().takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 }
            ?: floatArrayOf(0f, 0f, length(root.getAttribute("width")) ?: dst.width(), length(root.getAttribute("height")) ?: dst.height())
        // preserveAspectRatio xMidYMid meet, the default.
        val s = minOf(dst.width() / viewBox[2], dst.height() / viewBox[3])
        val tx = dst.left + (dst.width() - viewBox[2] * s) / 2 - viewBox[0] * s
        val ty = dst.top + (dst.height() - viewBox[3] * s) / 2 - viewBox[1] * s

        val rules = HashMap<String, Map<String, String>>()
        root.getElementsByTagName("style").let { list ->
            for (i in 0 until list.length) parseCss(list.item(i).textContent, rules)
        }

        canvas.save()
        canvas.clipRect(dst)
        canvas.translate(tx, ty)
        canvas.scale(s, s)
        try {
            Player(canvas, rules).element(root, Style())
        } finally {
            canvas.restore()
        }
    }

    private data class Style(
        val fill: Int? = 0xFF000000.toInt(),
        val stroke: Int? = null,
        val strokeWidth: Float = 1f,
        val fillOpacity: Float = 1f,
        val strokeOpacity: Float = 1f,
        val opacity: Float = 1f,
        val evenOdd: Boolean = false,
        val display: Boolean = true
    )

    private class Player(private val canvas: Canvas, private val rules: Map<String, Map<String, String>>) {

        fun element(e: Element, parent: Style) {
            val tag = e.tagName.substringAfter(':')
            if (tag in SKIPPED) return
            val style = styleOf(e, parent)
            if (!style.display) return
            val transform = e.getAttribute("transform").takeIf { it.isNotBlank() }?.let { parseTransform(it) }
            if (transform != null) { canvas.save(); canvas.concat(transform) }
            try {
                when (tag) {
                    "svg", "g", "a", "switch" -> for (child in e.childElements()) element(child, style)
                    else -> shape(e, tag)?.let { paint(it, style) }
                }
            } finally {
                if (transform != null) canvas.restore()
            }
        }

        private fun shape(e: Element, tag: String): Path? {
            fun f(name: String) = length(e.getAttribute(name)) ?: 0f
            return when (tag) {
                "path" -> parsePath(e.getAttribute("d"))
                "rect" -> Path().apply {
                    val w = f("width"); val h = f("height")
                    if (w <= 0 || h <= 0) return null
                    var rx = length(e.getAttribute("rx")); var ry = length(e.getAttribute("ry"))
                    if (rx == null) rx = ry; if (ry == null) ry = rx
                    val r = RectF(f("x"), f("y"), f("x") + w, f("y") + h)
                    if ((rx ?: 0f) > 0f) addRoundRect(r, minOf(rx!!, w / 2), minOf(ry!!, h / 2), Path.Direction.CW) else addRect(r, Path.Direction.CW)
                }
                "circle" -> Path().apply { addCircle(f("cx"), f("cy"), f("r"), Path.Direction.CW) }
                "ellipse" -> Path().apply { addOval(RectF(f("cx") - f("rx"), f("cy") - f("ry"), f("cx") + f("rx"), f("cy") + f("ry")), Path.Direction.CW) }
                "line" -> Path().apply { moveTo(f("x1"), f("y1")); lineTo(f("x2"), f("y2")) }
                "polyline", "polygon" -> {
                    val n = e.getAttribute("points").numbers()
                    if (n.size < 4) return null
                    Path().apply {
                        moveTo(n[0], n[1])
                        for (i in 2 until n.size - 1 step 2) lineTo(n[i], n[i + 1])
                        if (tag == "polygon") close()
                    }
                }
                else -> null
            }
        }

        private fun paint(path: Path, s: Style) {
            if (s.evenOdd) path.fillType = Path.FillType.EVEN_ODD
            s.fill?.let { c ->
                canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL; color = withAlpha(c, s.fillOpacity * s.opacity)
                })
            }
            s.stroke?.takeIf { s.strokeWidth > 0f }?.let { c ->
                canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE; color = withAlpha(c, s.strokeOpacity * s.opacity); strokeWidth = s.strokeWidth
                })
            }
        }

        /** Presentation attributes, then class rules, then the style attribute (CSS precedence). */
        private fun styleOf(e: Element, parent: Style): Style {
            val props = LinkedHashMap<String, String>()
            for (name in PROPERTIES) e.getAttribute(name).takeIf { it.isNotBlank() }?.let { props[name] = it.trim() }
            e.getAttribute("class").split(' ').filter { it.isNotBlank() }.forEach { cls -> rules[cls]?.let { props.putAll(it) } }
            e.getAttribute("style").takeIf { it.isNotBlank() }?.let { props.putAll(declarations(it)) }
            var s = parent.copy(opacity = 1f)
            for ((k, v) in props) {
                s = when (k) {
                    "fill" -> s.copy(fill = color(v))
                    "stroke" -> s.copy(stroke = color(v))
                    "stroke-width" -> s.copy(strokeWidth = length(v) ?: s.strokeWidth)
                    "fill-opacity" -> s.copy(fillOpacity = v.toFloatOrNull() ?: 1f)
                    "stroke-opacity" -> s.copy(strokeOpacity = v.toFloatOrNull() ?: 1f)
                    "opacity" -> s.copy(opacity = v.toFloatOrNull() ?: 1f)
                    "fill-rule" -> s.copy(evenOdd = v == "evenodd")
                    "display" -> s.copy(display = v != "none")
                    "visibility" -> s.copy(display = v != "hidden")
                    else -> s
                }
            }
            return s.copy(opacity = s.opacity * parent.opacity)
        }
    }

    private val SKIPPED = setOf("defs", "style", "title", "desc", "metadata", "clipPath", "mask", "linearGradient", "radialGradient", "pattern", "symbol", "text", "use", "image", "filter")
    private val PROPERTIES = listOf("fill", "stroke", "stroke-width", "fill-opacity", "stroke-opacity", "opacity", "fill-rule", "display", "visibility")

    private fun Node.childElements(): List<Element> {
        val out = mutableListOf<Element>()
        for (i in 0 until childNodes.length) (childNodes.item(i) as? Element)?.let { out += it }
        return out
    }

    private fun parseCss(css: String, into: MutableMap<String, Map<String, String>>) {
        val clean = css.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        for (m in Regex("([^{}]+)\\{([^}]*)\\}").findAll(clean)) {
            val decl = declarations(m.groupValues[2])
            for (selector in m.groupValues[1].split(',')) {
                val sel = selector.trim()
                if (sel.startsWith('.') && sel.drop(1).all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                    into[sel.drop(1)] = (into[sel.drop(1)].orEmpty()) + decl
                }
            }
        }
    }

    private fun declarations(s: String): Map<String, String> = s.split(';').mapNotNull { d ->
        val i = d.indexOf(':')
        if (i < 0) null else d.substring(0, i).trim() to d.substring(i + 1).trim().removeSuffix("!important").trim()
    }.toMap()

    private fun withAlpha(c: Int, a: Float): Int = ((((c ushr 24) * a.coerceIn(0f, 1f)).toInt()) shl 24) or (c and 0xFFFFFF)

    private fun color(v: String): Int? {
        val s = v.trim().lowercase()
        return when {
            s == "none" || s == "transparent" || s.startsWith("url(") -> null
            s.startsWith("#") && s.length == 7 -> s.substring(1).toIntOrNull(16)?.let { it or (0xFF shl 24) }
            s.startsWith("#") && s.length == 4 -> s.substring(1).map { "$it$it" }.joinToString("").toIntOrNull(16)?.let { it or (0xFF shl 24) }
            s.startsWith("rgb") -> {
                val n = s.substringAfter('(').substringBefore(')').split(',').map { p ->
                    val t = p.trim(); if (t.endsWith("%")) (t.dropLast(1).toFloatOrNull() ?: 0f) * 2.55f else t.toFloatOrNull() ?: 0f
                }
                if (n.size < 3) null else (0xFF shl 24) or (n[0].toInt().coerceIn(0, 255) shl 16) or (n[1].toInt().coerceIn(0, 255) shl 8) or n[2].toInt().coerceIn(0, 255)
            }
            else -> NAMED[s]?.let { it or (0xFF shl 24) }
        }
    }

    private val NAMED = mapOf("black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000,
        "blue" to 0x0000FF, "gray" to 0x808080, "grey" to 0x808080, "yellow" to 0xFFFF00, "orange" to 0xFFA500,
        "purple" to 0x800080, "silver" to 0xC0C0C0, "navy" to 0x000080, "teal" to 0x008080, "maroon" to 0x800000)

    private fun length(v: String?): Float? {
        val s = v?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return s.removeSuffix("px").removeSuffix("pt").toFloatOrNull()
    }

    private val NUMBER = Regex("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?")

    private fun String.numbers(): FloatArray = NUMBER.findAll(this).map { it.value.toFloat() }.toList().toFloatArray()

    private fun parseTransform(t: String): Matrix {
        val m = Matrix()
        for (op in Regex("(\\w+)\\s*\\(([^)]*)\\)").findAll(t)) {
            val n = op.groupValues[2].numbers()
            val step = Matrix()
            when (op.groupValues[1]) {
                "matrix" -> if (n.size == 6) step.setValues(floatArrayOf(n[0], n[2], n[4], n[1], n[3], n[5], 0f, 0f, 1f))
                "translate" -> step.setTranslate(n.getOrElse(0) { 0f }, n.getOrElse(1) { 0f })
                "scale" -> step.setScale(n.getOrElse(0) { 1f }, n.getOrElse(1) { n.getOrElse(0) { 1f } })
                "rotate" -> if (n.size >= 3) step.setRotate(n[0], n[1], n[2]) else step.setRotate(n.getOrElse(0) { 0f })
                "skewX" -> step.setSkew(tan(Math.toRadians(n.getOrElse(0) { 0f }.toDouble())).toFloat(), 0f)
                "skewY" -> step.setSkew(0f, tan(Math.toRadians(n.getOrElse(0) { 0f }.toDouble())).toFloat())
            }
            m.preConcat(step)
        }
        return m
    }

    /** Reads an SVG path's `d` attribute character by character, since arc flags
     *  may be written without separators ("a5 5 0 011 1"). */
    private class PathScanner(private val d: String) {
        var i = 0
        private fun skip() { while (i < d.length && (d[i].isWhitespace() || d[i] == ',')) i++ }
        fun atCommand(): Boolean { skip(); return i < d.length && d[i].isLetter() }
        fun hasNumber(): Boolean { skip(); return i < d.length && (d[i].isDigit() || d[i] == '-' || d[i] == '+' || d[i] == '.') }
        fun command(): Char { skip(); return d[i++] }
        fun flag(): Boolean { skip(); return d.getOrNull(i++) == '1' }
        fun number(): Float {
            skip()
            val start = i
            if (i < d.length && (d[i] == '-' || d[i] == '+')) i++
            var dot = false
            while (i < d.length && (d[i].isDigit() || (d[i] == '.' && !dot))) { if (d[i] == '.') dot = true; i++ }
            if (i < d.length && (d[i] == 'e' || d[i] == 'E')) {
                i++
                if (i < d.length && (d[i] == '-' || d[i] == '+')) i++
                while (i < d.length && d[i].isDigit()) i++
            }
            return d.substring(start, i).toFloatOrNull() ?: 0f.also { if (i == start) i++ }
        }
        val done get() = run { skip(); i >= d.length }
    }

    private fun parsePath(d: String): Path {
        val path = Path()
        val sc = PathScanner(d)
        var cmd = ' '
        var x = 0f; var y = 0f
        var startX = 0f; var startY = 0f
        var lastCx = 0f; var lastCy = 0f
        var lastCmd = ' '
        var guard = 0
        while (!sc.done && guard++ < 1_000_000) {
            if (sc.atCommand()) cmd = sc.command() else if (cmd == ' ') return path
            val rel = cmd.isLowerCase()
            fun ax(v: Float) = if (rel) x + v else v
            fun ay(v: Float) = if (rel) y + v else v
            val upper = cmd.uppercaseChar()
            when (upper) {
                'M' -> {
                    x = ax(sc.number()); y = ay(sc.number()); path.moveTo(x, y); startX = x; startY = y
                    // Further pairs after a moveto are implicit linetos.
                    cmd = if (rel) 'l' else 'L'
                }
                'L' -> { x = ax(sc.number()); y = ay(sc.number()); path.lineTo(x, y) }
                'H' -> { x = if (rel) x + sc.number() else sc.number(); path.lineTo(x, y) }
                'V' -> { y = if (rel) y + sc.number() else sc.number(); path.lineTo(x, y) }
                'C' -> {
                    val x1 = ax(sc.number()); val y1 = ay(sc.number()); val x2 = ax(sc.number()); val y2 = ay(sc.number())
                    val nx = ax(sc.number()); val ny = ay(sc.number())
                    path.cubicTo(x1, y1, x2, y2, nx, ny); lastCx = x2; lastCy = y2; x = nx; y = ny
                }
                'S' -> {
                    val x1 = if (lastCmd == 'C' || lastCmd == 'S') 2 * x - lastCx else x
                    val y1 = if (lastCmd == 'C' || lastCmd == 'S') 2 * y - lastCy else y
                    val x2 = ax(sc.number()); val y2 = ay(sc.number()); val nx = ax(sc.number()); val ny = ay(sc.number())
                    path.cubicTo(x1, y1, x2, y2, nx, ny); lastCx = x2; lastCy = y2; x = nx; y = ny
                }
                'Q' -> {
                    val qx = ax(sc.number()); val qy = ay(sc.number()); val nx = ax(sc.number()); val ny = ay(sc.number())
                    path.quadTo(qx, qy, nx, ny); lastCx = qx; lastCy = qy; x = nx; y = ny
                }
                'T' -> {
                    val qx = if (lastCmd == 'Q' || lastCmd == 'T') 2 * x - lastCx else x
                    val qy = if (lastCmd == 'Q' || lastCmd == 'T') 2 * y - lastCy else y
                    val nx = ax(sc.number()); val ny = ay(sc.number())
                    path.quadTo(qx, qy, nx, ny); lastCx = qx; lastCy = qy; x = nx; y = ny
                }
                'A' -> {
                    val rx = sc.number(); val ry = sc.number(); val rot = sc.number()
                    val large = sc.flag(); val sweep = sc.flag()
                    val nx = ax(sc.number()); val ny = ay(sc.number())
                    arc(path, x, y, rx, ry, rot, large, sweep, nx, ny); x = nx; y = ny
                }
                'Z' -> { path.close(); x = startX; y = startY }
                else -> return path
            }
            lastCmd = upper
            // A command letter is followed by one or more parameter groups; Z takes none.
            if (upper == 'Z' && !sc.atCommand() && sc.hasNumber()) cmd = ' '
        }
        return path
    }

    /** SVG endpoint arc to cubic Béziers (SVG 1.1 implementation notes, F.6.5). */
    private fun arc(path: Path, x1: Float, y1: Float, rx0: Float, ry0: Float, angle: Float, large: Boolean, sweep: Boolean, x2: Float, y2: Float) {
        if (x1 == x2 && y1 == y2) return
        var rx = abs(rx0.toDouble()); var ry = abs(ry0.toDouble())
        if (rx == 0.0 || ry == 0.0) { path.lineTo(x2, y2); return }
        val phi = Math.toRadians(angle.toDouble())
        val cp = cos(phi); val sp = sin(phi)
        val dx = (x1 - x2) / 2.0; val dy = (y1 - y2) / 2.0
        val x1p = cp * dx + sp * dy; val y1p = -sp * dx + cp * dy
        val lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
        if (lambda > 1) { rx *= sqrt(lambda); ry *= sqrt(lambda) }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = sqrt((num / den).coerceAtLeast(0.0))
        if (large == sweep) coef = -coef
        val cxp = coef * rx * y1p / ry; val cyp = -coef * ry * x1p / rx
        val cx = cp * cxp - sp * cyp + (x1 + x2) / 2.0
        val cy = sp * cxp + cp * cyp + (y1 + y2) / 2.0
        fun ang(ux: Double, uy: Double, vx: Double, vy: Double) = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        val t1 = ang(1.0, 0.0, (x1p - cxp) / rx, (y1p - cyp) / ry)
        var dt = ang((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
        if (!sweep && dt > 0) dt -= 2 * PI else if (sweep && dt < 0) dt += 2 * PI
        val segments = ceil(abs(dt) / (PI / 2)).toInt().coerceAtLeast(1)
        val delta = dt / segments
        val k = 4.0 / 3.0 * tan(delta / 4)
        var t = t1
        fun px(a: Double, b: Double) = (cx + cp * a - sp * b).toFloat()
        fun py(a: Double, b: Double) = (cy + sp * a + cp * b).toFloat()
        repeat(segments) {
            val t2 = t + delta
            val ax1 = rx * (cos(t) - k * sin(t)); val ay1 = ry * (sin(t) + k * cos(t))
            val ax2 = rx * (cos(t2) + k * sin(t2)); val ay2 = ry * (sin(t2) - k * cos(t2))
            val ex = rx * cos(t2); val ey = ry * sin(t2)
            path.cubicTo(px(ax1, ay1), py(ax1, ay1), px(ax2, ay2), py(ax2, ay2), px(ex, ey), py(ex, ey))
            t = t2
        }
    }
}
