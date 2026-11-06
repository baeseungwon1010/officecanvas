package io.github.baeseungwon1010.officecanvas.pdf

import java.nio.ByteBuffer
import kotlin.math.atan2
import kotlin.math.hypot

/** A shown character: its text, baseline origin and advance on the page (points,
 *  top-left origin, y down), size, font and color. [angle]: the baseline's
 *  direction in degrees (0 for normal text). */
internal class PdfChar(
    val text: String, val x: Double, val y: Double, val width: Double, val size: Double,
    val font: PdfFont, val color: Int, val angle: Double, val invisible: Boolean, val isSpace: Boolean,
    /** Slanted by the text matrix (how some writers fake italics). */
    val slanted: Boolean = false,
    /** The advance the font gives the glyph, without the spacing added to it, and how much wider than tall the glyphs are drawn. */
    val glyphWidth: Double = width, val hScale: Double = 1.0
) {
    /** The letter spacing the text is set with (points after each character), as the layout found it. */
    var tracking = 0.0
    /** The standard face set in place of a font readers seldom have, as the layout chose it. */
    var substitute: String? = null
}

/** A straight stroked line. */
internal class PdfLine(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val width: Double, val color: Int)

/** A filled rectangle. */
internal class PdfRect(val x: Double, val y: Double, val w: Double, val h: Double, val color: Int)

/** A picture placed on the page; [image] decodes it when asked. */
internal class PdfPicture(val x: Double, val y: Double, val w: Double, val h: Double, val image: () -> PdfImages.Image?)

internal class PdfPageContent(
    val width: Double, val height: Double,
    val chars: List<PdfChar>, val lines: List<PdfLine>, val rects: List<PdfRect>, val pictures: List<PdfPicture>
)

/**
 * Runs a page's content stream (8, 9) and collects what text, rulings and pictures
 * it puts where. Clipping, shading and transparency aren't followed; curves only
 * count for their straight segments.
 */
internal class PdfPageReader(private val doc: PdfDocument) {

    private val fonts = HashMap<PdfObj, PdfFont?>()

    private class Matrix(val a: Double, val b: Double, val c: Double, val d: Double, val e: Double, val f: Double) {
        fun times(m: Matrix) = Matrix(
            a * m.a + b * m.c, a * m.b + b * m.d,
            c * m.a + d * m.c, c * m.b + d * m.d,
            e * m.a + f * m.c + m.e, e * m.b + f * m.d + m.f
        )
        fun x(px: Double, py: Double) = a * px + c * py + e
        fun y(px: Double, py: Double) = b * px + d * py + f
        companion object { val IDENTITY = Matrix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0) }
    }

    private class State(
        var ctm: Matrix = Matrix.IDENTITY, var fill: Int = BLACK, var stroke: Int = BLACK, var lineWidth: Double = 1.0,
        var font: PdfFont? = null, var size: Double = 0.0, var charSpacing: Double = 0.0, var wordSpacing: Double = 0.0,
        var hScale: Double = 1.0, var leading: Double = 0.0, var rise: Double = 0.0, var render: Int = 0,
        var fillComponents: Int = 1, var strokeComponents: Int = 1
    ) {
        fun copy() = State(ctm, fill, stroke, lineWidth, font, size, charSpacing, wordSpacing, hScale, leading, rise, render, fillComponents, strokeComponents)
    }

    private lateinit var device: Matrix
    private val chars = ArrayList<PdfChar>()
    private val lines = ArrayList<PdfLine>()
    private val rects = ArrayList<PdfRect>()
    private val pictures = ArrayList<PdfPicture>()
    private var ops = 0L

    fun read(page: PdfDocument.Page): PdfPageContent {
        val box = page.cropBox
        val w = box[2] - box[0]; val h = box[3] - box[1]
        // User space to the page as shown: crop box origin, y down, then /Rotate.
        val flip = Matrix(1.0, 0.0, 0.0, -1.0, -box[0], box[3])
        device = when (page.rotate) {
            90 -> flip.times(Matrix(0.0, 1.0, -1.0, 0.0, h, 0.0))
            180 -> flip.times(Matrix(-1.0, 0.0, 0.0, -1.0, w, h))
            270 -> flip.times(Matrix(0.0, -1.0, 1.0, 0.0, 0.0, w))
            else -> flip
        }
        val (pw, ph) = if (page.rotate == 90 || page.rotate == 270) h to w else w to h
        chars.clear(); lines.clear(); rects.clear(); pictures.clear(); ops = 0
        val content = contentBytes(page.dict["Contents"])
        if (content != null) run(content, page.resources, State(), 0)
        annotations(page)
        return PdfPageContent(pw, ph, chars.toList(), lines.toList(), rects.toList(), pictures.toList())
    }

    /** Annotations' normal appearances (stamps, filled-in form fields, signatures), mapped into their rectangles (12.5.5). */
    private fun annotations(page: PdfDocument.Page) {
        for (a in doc.array(page.dict["Annots"])?.items.orEmpty().take(1000)) {
            val annot = doc.dict(a) ?: continue
            if (annot.name("Subtype") in setOf("Link", "Popup")) continue
            if (((doc.num(annot["F"]) ?: 0.0).toInt() and 2) != 0) continue // hidden
            val ap = doc.resolve(annot["AP"]) as? PdfDict ?: continue
            val n = doc.resolve(ap["N"])
            val stream = (n as? PdfStream) ?: (n as? PdfDict)?.let { states ->
                doc.resolve(states[(doc.resolve(annot["AS"]) as? PdfName)?.name ?: ""]) as? PdfStream
            } ?: continue
            val rect = doc.array(annot["Rect"])?.items?.mapNotNull { doc.num(it) }?.takeIf { it.size == 4 } ?: continue
            val bbox = doc.array(stream.dict["BBox"])?.items?.mapNotNull { doc.num(it) }?.takeIf { it.size == 4 } ?: continue
            val m = doc.array(stream.dict["Matrix"])?.items?.map { doc.num(it) ?: 0.0 }?.takeIf { it.size == 6 } ?: listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
            val form = Matrix(m[0], m[1], m[2], m[3], m[4], m[5])
            // The box as the matrix maps it, fitted to the annotation's rectangle.
            val xs = listOf(form.x(bbox[0], bbox[1]), form.x(bbox[2], bbox[1]), form.x(bbox[0], bbox[3]), form.x(bbox[2], bbox[3]))
            val ys = listOf(form.y(bbox[0], bbox[1]), form.y(bbox[2], bbox[1]), form.y(bbox[0], bbox[3]), form.y(bbox[2], bbox[3]))
            val bw = xs.max() - xs.min(); val bh = ys.max() - ys.min()
            if (bw <= 0 || bh <= 0) continue
            val rx = minOf(rect[0], rect[2]); val ry = minOf(rect[1], rect[3])
            val sx = Math.abs(rect[2] - rect[0]) / bw; val sy = Math.abs(rect[3] - rect[1]) / bh
            val fit = Matrix(sx, 0.0, 0.0, sy, rx - xs.min() * sx, ry - ys.min() * sy)
            val state = State(ctm = form.times(fit))
            val data = doc.streamBytes(stream) ?: continue
            if (data.size < MAX_CONTENT) run(data, doc.dict(stream.dict["Resources"]) ?: page.resources, state, 1)
        }
    }

    private fun contentBytes(o: PdfObj?): ByteArray? {
        return when (val c = doc.resolve(o)) {
            is PdfStream -> doc.streamBytes(c)
            is PdfArray -> {
                val out = java.io.ByteArrayOutputStream()
                for (item in c.items.take(10_000)) {
                    (doc.resolve(item) as? PdfStream)?.let { s -> doc.streamBytes(s)?.let { out.write(it); out.write(10) } }
                    if (out.size() > MAX_CONTENT) break
                }
                out.toByteArray()
            }
            else -> null
        }
    }

    private fun run(data: ByteArray, resources: PdfDict?, initial: State, depth: Int) {
        if (depth > MAX_FORM_DEPTH) return
        val lex = PdfLexer(ByteBuffer.wrap(data))
        val stack = ArrayList<State>()
        var gs = initial.copy()
        val operands = ArrayList<PdfObj>()
        var tm = Matrix.IDENTITY
        var tlm = Matrix.IDENTITY
        // Path being built: subpaths of points (in device space), closed or not, and whether a rectangle.
        val path = ArrayList<MutableList<DoubleArray>>()
        val pathRects = ArrayList<DoubleArray>()
        var cur: MutableList<DoubleArray>? = null
        // ActualText spans replacing the glyphs inside them.
        val actual = ArrayList<String?>()
        var actualUsed = false

        fun num(i: Int): Double = (operands.getOrNull(i) as? PdfNum)?.value?.takeIf { it.isFinite() } ?: 0.0
        fun pt(x: Double, y: Double): DoubleArray { val m = gs.ctm.times(device); return doubleArrayOf(m.x(x, y), m.y(x, y)) }

        fun showText(bytes: ByteArray) {
            val font = gs.font ?: return
            for (g in font.glyphs(bytes)) {
                if (chars.size >= MAX_CHARS) return
                val trm = Matrix(gs.size * gs.hScale, 0.0, 0.0, gs.size, 0.0, gs.rise).times(tm).times(gs.ctm).times(device)
                val ox = trm.e; val oy = trm.f
                val adv = (g.width * gs.size + gs.charSpacing + if (g.isSpace) gs.wordSpacing else 0.0) * gs.hScale
                val unitX = hypot(trm.a, trm.b) / maxOf(gs.size * gs.hScale, 1e-9)
                val size = hypot(trm.c, trm.d)
                val angle = Math.toDegrees(atan2(trm.b, trm.a))
                var text = g.text
                if (actual.isNotEmpty() && actual.last() != null) {
                    text = if (!actualUsed) actual.last()!! else ""
                    actualUsed = true
                }
                if (size > 0.1 && size < 2000 && ox.isFinite() && oy.isFinite()) {
                    // A slant: the glyph's upright leans over (in device space, y down).
                    val lean = if (Math.abs(trm.d) > 1e-9) -trm.c / Math.abs(trm.d) else 0.0
                    chars += PdfChar(text, ox, oy, adv * unitX, size, font, gs.fill, angle, gs.render == 3 || gs.render == 7, g.isSpace && text == " ", lean > 0.12 && Math.abs(angle) < 3,
                        // Width over height of the glyphs: Tz, or a text matrix squeezing them (Hangul's 장평).
                        g.width * gs.size * gs.hScale * unitX, hypot(trm.a, trm.b) / size)
                }
                tm = Matrix(1.0, 0.0, 0.0, 1.0, adv, 0.0).times(tm)
            }
        }

        fun paint(stroke: Boolean, fill: Boolean) {
            val m = gs.ctm.times(device)
            val lw = (gs.lineWidth * hypot(m.a, m.b)).coerceAtLeast(0.1)
            if (fill) for (r in pathRects) {
                if (rects.size < MAX_SHAPES) rects += PdfRect(r[0], r[1], r[2], r[3], gs.fill)
            }
            if (stroke) {
                for (r in pathRects) if (lines.size < MAX_SHAPES) {
                    val (x, y, w, h) = listOf(r[0], r[1], r[2], r[3])
                    lines += PdfLine(x, y, x + w, y, lw, gs.stroke); lines += PdfLine(x + w, y, x + w, y + h, lw, gs.stroke)
                    lines += PdfLine(x, y + h, x + w, y + h, lw, gs.stroke); lines += PdfLine(x, y, x, y + h, lw, gs.stroke)
                }
                for (sp in path) for (k in 1 until sp.size) if (lines.size < MAX_SHAPES && !sp[k].isCurve()) lines += PdfLine(sp[k - 1][0], sp[k - 1][1], sp[k][0], sp[k][1], lw, gs.stroke)
            }
            if (fill) for (sp in path) {
                // A closed four-sided axis-aligned path filled: a rectangle (table rules often are).
                // (Every segment level or upright, every point on the box's edge.)
                if (sp.size in 4..12 && sp.none { it.isCurve() }) {
                    val x0 = sp.minOf { it[0] }; val x1 = sp.maxOf { it[0] }; val y0 = sp.minOf { it[1] }; val y1 = sp.maxOf { it[1] }
                    val axis = (1 until sp.size).all { k -> Math.abs(sp[k][0] - sp[k - 1][0]) < 0.05 || Math.abs(sp[k][1] - sp[k - 1][1]) < 0.05 }
                    val onEdge = sp.all { p -> Math.abs(p[0] - x0) < 0.05 || Math.abs(p[0] - x1) < 0.05 || Math.abs(p[1] - y0) < 0.05 || Math.abs(p[1] - y1) < 0.05 }
                    if (axis && onEdge && x1 - x0 > 0.1 && y1 - y0 > 0.1 && rects.size < MAX_SHAPES) rects += PdfRect(x0, y0, x1 - x0, y1 - y0, gs.fill)
                }
            }
            path.clear(); pathRects.clear(); cur = null
        }

        fun rgb(n: Int, base: Int): Int = when (n) {
            1 -> gray(num(base))
            3 -> rgbOf(num(base), num(base + 1), num(base + 2))
            4 -> cmyk(num(base), num(base + 1), num(base + 2), num(base + 3))
            else -> BLACK
        }

        while (true) {
            if (++ops > MAX_OPS) return
            val o = lex.next() ?: if (lex.pos >= lex.size) break else continue
            if (o !is PdfKeyword) { operands += o; if (operands.size > 64) operands.removeAt(0); continue }
            when (o.word) {
                "q" -> if (stack.size < 64) stack += gs.copy()
                "Q" -> if (stack.isNotEmpty()) gs = stack.removeAt(stack.size - 1)
                "cm" -> if (operands.size >= 6) gs.ctm = Matrix(num(0), num(1), num(2), num(3), num(4), num(5)).times(gs.ctm)
                "w" -> gs.lineWidth = num(0)
                "gs" -> {
                    val ext = doc.dict(doc.dict(resources?.get("ExtGState"))?.get((operands.lastOrNull() as? PdfName)?.name ?: ""))
                    ext?.let { e -> doc.num(e["LW"])?.let { gs.lineWidth = it } }
                }
                // Colors.
                "g" -> { gs.fill = gray(num(0)); gs.fillComponents = 1 }
                "G" -> { gs.stroke = gray(num(0)); gs.strokeComponents = 1 }
                "rg" -> { gs.fill = rgbOf(num(0), num(1), num(2)); gs.fillComponents = 3 }
                "RG" -> { gs.stroke = rgbOf(num(0), num(1), num(2)); gs.strokeComponents = 3 }
                "k" -> { gs.fill = cmyk(num(0), num(1), num(2), num(3)); gs.fillComponents = 4 }
                "K" -> { gs.stroke = cmyk(num(0), num(1), num(2), num(3)); gs.strokeComponents = 4 }
                "cs" -> { gs.fillComponents = components(operands.lastOrNull(), resources); gs.fill = BLACK }
                "CS" -> { gs.strokeComponents = components(operands.lastOrNull(), resources); gs.stroke = BLACK }
                "sc", "scn" -> { val n = operands.count { it is PdfNum }; if (n > 0) gs.fill = rgb(if (n in setOf(1, 3, 4)) n else gs.fillComponents, 0) }
                "SC", "SCN" -> { val n = operands.count { it is PdfNum }; if (n > 0) gs.stroke = rgb(if (n in setOf(1, 3, 4)) n else gs.strokeComponents, 0) }
                // Paths.
                "m" -> { cur = mutableListOf(pt(num(0), num(1))); path += cur!! }
                "l" -> cur?.add(pt(num(0), num(1)))
                "c" -> cur?.add(pt(num(4), num(5)).curve())
                "v", "y" -> cur?.add(pt(num(2), num(3)).curve())
                "h" -> cur?.let { sp -> if (sp.size > 1) sp.add(sp[0].copyOf()) }
                "re" -> {
                    val p0 = pt(num(0), num(1)); val p1 = pt(num(0) + num(2), num(1) + num(3))
                    pathRects += doubleArrayOf(minOf(p0[0], p1[0]), minOf(p0[1], p1[1]), Math.abs(p1[0] - p0[0]), Math.abs(p1[1] - p0[1]))
                }
                "S", "s" -> paint(stroke = true, fill = false)
                "f", "F", "f*" -> paint(stroke = false, fill = true)
                "B", "B*", "b", "b*" -> paint(stroke = true, fill = true)
                "n" -> { path.clear(); pathRects.clear(); cur = null }
                // Text.
                "BT" -> { tm = Matrix.IDENTITY; tlm = Matrix.IDENTITY }
                "ET" -> {}
                "Tc" -> gs.charSpacing = num(0)
                "Tw" -> gs.wordSpacing = num(0)
                "Tz" -> gs.hScale = num(0) / 100
                "TL" -> gs.leading = num(0)
                "Ts" -> gs.rise = num(0)
                "Tr" -> gs.render = num(0).toInt()
                "Tf" -> {
                    val name = (operands.getOrNull(0) as? PdfName)?.name
                    gs.size = num(1)
                    val ref = doc.dict(resources?.get("Font"))?.get(name ?: "")
                    if (ref != null) gs.font = fonts.getOrPut(ref) { try { PdfFont.load(doc, ref) } catch (e: Exception) { null } }
                }
                "Td" -> { tlm = Matrix(1.0, 0.0, 0.0, 1.0, num(0), num(1)).times(tlm); tm = tlm }
                "TD" -> { gs.leading = -num(1); tlm = Matrix(1.0, 0.0, 0.0, 1.0, num(0), num(1)).times(tlm); tm = tlm }
                "Tm" -> { tlm = Matrix(num(0), num(1), num(2), num(3), num(4), num(5)); tm = tlm }
                "T*" -> { tlm = Matrix(1.0, 0.0, 0.0, 1.0, 0.0, -gs.leading).times(tlm); tm = tlm }
                "Tj" -> (operands.lastOrNull() as? PdfString)?.let { showText(it.bytes) }
                "'" -> { tlm = Matrix(1.0, 0.0, 0.0, 1.0, 0.0, -gs.leading).times(tlm); tm = tlm; (operands.lastOrNull() as? PdfString)?.let { showText(it.bytes) } }
                "\"" -> {
                    gs.wordSpacing = num(0); gs.charSpacing = num(1)
                    tlm = Matrix(1.0, 0.0, 0.0, 1.0, 0.0, -gs.leading).times(tlm); tm = tlm
                    (operands.lastOrNull() as? PdfString)?.let { showText(it.bytes) }
                }
                "TJ" -> (operands.lastOrNull() as? PdfArray)?.items?.forEach { item ->
                    when (item) {
                        is PdfString -> showText(item.bytes)
                        is PdfNum -> tm = Matrix(1.0, 0.0, 0.0, 1.0, -item.value / 1000 * gs.size * gs.hScale, 0.0).times(tm)
                        else -> {}
                    }
                }
                // Marked content: ActualText replaces the glyphs it spans.
                "BDC" -> {
                    val props = when (val p = operands.lastOrNull()) {
                        is PdfDict -> p
                        is PdfName -> doc.dict(doc.dict(resources?.get("Properties"))?.get(p.name))
                        else -> null
                    }
                    val t = (doc.resolve(props?.get("ActualText")) as? PdfString)?.text()
                    actual += t; if (t != null) actualUsed = false
                }
                "BMC" -> actual += null
                "EMC" -> if (actual.isNotEmpty()) actual.removeAt(actual.size - 1)
                // XObjects and inline images.
                "Do" -> {
                    val name = (operands.lastOrNull() as? PdfName)?.name ?: ""
                    val ref = doc.dict(resources?.get("XObject"))?.get(name)
                    val x = doc.resolve(ref) as? PdfStream
                    when (x?.dict?.name("Subtype")) {
                        "Image" -> placeImage(gs, x)
                        "Form" -> {
                            val m = doc.array(x.dict["Matrix"])?.items?.map { doc.num(it) ?: 0.0 }?.takeIf { it.size == 6 }
                            val inner = gs.copy()
                            if (m != null) inner.ctm = Matrix(m[0], m[1], m[2], m[3], m[4], m[5]).times(gs.ctm)
                            val data = doc.streamBytes(x)
                            if (data != null && data.size < MAX_CONTENT) run(data, doc.dict(x.dict["Resources"]) ?: resources, inner, depth + 1)
                        }
                    }
                }
                "BI" -> inlineImage(lex, gs, resources)
                "d0", "d1" -> {}
            }
            operands.clear()
        }
    }

    private fun DoubleArray.curve(): DoubleArray = doubleArrayOf(this[0], this[1], 1.0)
    private fun DoubleArray.isCurve() = size > 2

    private fun components(cs: PdfObj?, resources: PdfDict?): Int {
        val name = (cs as? PdfName)?.name ?: return 1
        return when (name) {
            "DeviceGray", "CalGray", "G" -> 1
            "DeviceRGB", "CalRGB", "RGB", "Lab" -> 3
            "DeviceCMYK", "CMYK" -> 4
            "Pattern" -> 0
            else -> PdfImages.colorComponents(doc, doc.dict(resources?.get("ColorSpace"))?.get(name)) ?: 1
        }
    }

    private fun placeImage(gs: State, s: PdfStream) {
        if (pictures.size >= MAX_PICTURES) return
        val m = gs.ctm.times(device)
        val xs = listOf(m.x(0.0, 0.0), m.x(1.0, 0.0), m.x(0.0, 1.0), m.x(1.0, 1.0))
        val ys = listOf(m.y(0.0, 0.0), m.y(1.0, 0.0), m.y(0.0, 1.0), m.y(1.0, 1.0))
        val w = xs.max() - xs.min(); val h = ys.max() - ys.min()
        if (w < 1 || h < 1) return
        val fill = gs.fill
        pictures += PdfPicture(xs.min(), ys.min(), w, h) { PdfImages.decode(doc, s, fill) }
    }

    /** "BI <dict> ID <data> EI" (8.9.7). */
    private fun inlineImage(lex: PdfLexer, gs: State, resources: PdfDict?) {
        val map = LinkedHashMap<String, PdfObj>()
        while (true) {
            val k = lex.next() ?: return
            if (k is PdfKeyword && k.word == "ID") break
            val v = lex.next() ?: return
            if (k is PdfName) map[k.name] = v
            if (map.size > 64) return
        }
        if (PdfLexer.isSpace(lex.peek())) lex.pos++
        val start = lex.pos
        // The data ends at "EI" between white space (data length isn't given).
        var end = start
        while (end < lex.size - 1) {
            if (lex.byteAt(end) == 'E'.code && lex.byteAt(end + 1) == 'I'.code && (end == start || PdfLexer.isSpace(lex.byteAt(end - 1))) &&
                PdfLexer.isDelimiterOrSpace(lex.byteAt(end + 2))) break
            end++
        }
        val data = ByteArray(maxOf(0, end - start - 1)).also { for (i in it.indices) it[i] = lex.byteAt(start + i).toByte() }
        lex.pos = minOf(lex.size, end + 2)
        if (pictures.size >= MAX_PICTURES) return
        val dict = PdfDict(map)
        val m = gs.ctm.times(device)
        val xs = listOf(m.x(0.0, 0.0), m.x(1.0, 0.0), m.x(0.0, 1.0), m.x(1.0, 1.0))
        val ys = listOf(m.y(0.0, 0.0), m.y(1.0, 0.0), m.y(0.0, 1.0), m.y(1.0, 1.0))
        val w = xs.max() - xs.min(); val h = ys.max() - ys.min()
        if (w < 1 || h < 1) return
        val fill = gs.fill
        pictures += PdfPicture(xs.min(), ys.min(), w, h) { PdfImages.decodeInline(doc, dict, data, resources, fill) }
    }

    companion object {
        const val BLACK = 0xFF000000.toInt()
        const val MAX_CONTENT = 64 shl 20
        const val MAX_OPS = 5_000_000L
        const val MAX_CHARS = 500_000
        const val MAX_SHAPES = 200_000
        const val MAX_PICTURES = 2000
        const val MAX_FORM_DEPTH = 12

        private fun byte(v: Double) = (v.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
        fun gray(v: Double): Int { val g = byte(v); return (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
        fun rgbOf(r: Double, g: Double, b: Double): Int = (0xFF shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
        fun cmyk(c: Double, m: Double, y: Double, k: Double): Int {
            val kk = k.coerceIn(0.0, 1.0)
            return rgbOf((1 - c.coerceIn(0.0, 1.0)) * (1 - kk), (1 - m.coerceIn(0.0, 1.0)) * (1 - kk), (1 - y.coerceIn(0.0, 1.0)) * (1 - kk))
        }
    }
}
