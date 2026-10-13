package io.github.baeseungwon1010.officecanvas.metafile

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import io.github.baeseungwon1010.officecanvas.util.SafeBitmaps
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plays an EMF (Enhanced Metafile, MS-EMF) onto a Canvas, scaled into a target
 * rectangle. Office decks embed diagrams as EMF, which BitmapFactory can't
 * decode, so without this they simply vanish from the slide.
 *
 * Covers the GDI records such diagrams are made of: paths (often text drawn as
 * outlines), lines, polygons, beziers, rectangles and ellipses, pens and
 * brushes, text, embedded DIB bitmaps, and the window/viewport/world
 * transforms. Clipping, raster ops, hatch patterns and EMF+ records are ignored.
 */
internal object EmfRenderer {

    fun draw(canvas: Canvas, emf: ByteArray, dst: RectF) {
        if (emf.size < 88) return
        val buf = ByteBuffer.wrap(emf).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.getInt(0) != EMR_HEADER || buf.getInt(40) != 0x464D4520) return // " EMF"
        val left = buf.getInt(8).toFloat()
        val top = buf.getInt(12).toFloat()
        val right = buf.getInt(16).toFloat()
        val bottom = buf.getInt(20).toFloat()
        if (right <= left || bottom <= top) return

        val toDst = Matrix().apply {
            setTranslate(-left, -top)
            postScale(dst.width() / (right - left + 1), dst.height() / (bottom - top + 1))
            postTranslate(dst.left, dst.top)
        }
        canvas.save()
        canvas.clipRect(dst)
        try {
            Player(buf, canvas, toDst).play()
        } finally {
            canvas.restore()
        }
    }

    private class Pen(val color: Int, val width: Float, val cosmetic: Boolean, val style: Int)
    private class Brush(val color: Int, val hollow: Boolean)
    private class Font(val height: Float, val bold: Boolean, val italic: Boolean, val escapement: Float, val face: String)

    private class State(
        var pen: Pen = BLACK_PEN,
        var brush: Brush? = WHITE_BRUSH,
        var font: Font = DEFAULT_FONT,
        var textColor: Int = 0xFF000000.toInt(),
        var textAlign: Int = 0,
        var fillWinding: Boolean = false,
        var mapMode: Int = MM_TEXT,
        var winOrgX: Float = 0f, var winOrgY: Float = 0f,
        var winExtX: Float = 1f, var winExtY: Float = 1f,
        var vpOrgX: Float = 0f, var vpOrgY: Float = 0f,
        var vpExtX: Float = 1f, var vpExtY: Float = 1f,
        val world: Matrix = Matrix()
    ) {
        fun copy() = State(pen, brush, font, textColor, textAlign, fillWinding, mapMode,
            winOrgX, winOrgY, winExtX, winExtY, vpOrgX, vpOrgY, vpExtX, vpExtY, Matrix(world))
    }

    private class Player(private val b: ByteBuffer, private val canvas: Canvas, private val toDst: Matrix) {
        private val objects = HashMap<Int, Any>()
        private var st = State()
        private val saved = ArrayDeque<State>()
        private var curX = 0f
        private var curY = 0f
        private var path: Path? = null // open path bracket (BEGINPATH..ENDPATH)
        private var bracketPath: Path? = null // closed bracket awaiting FILL/STROKEPATH

        /** End of the record being played: poly records may not read past it. */
        private var recordEnd = 0

        /** Points left for the whole picture; a picture made of millions of tiny
         *  poly records stops drawing rather than building a path without end. */
        private var pointBudget = MAX_POINTS

        fun play() {
            var off = 0
            var guard = 1_000_000
            while (off + 8 <= b.limit() && guard-- > 0 && pointBudget > 0) {
                val type = b.getInt(off)
                val size = b.getInt(off + 4)
                if (size < 8 || off.toLong() + size > b.limit()) break
                if (type == EMR_EOF) break
                recordEnd = off + size
                try {
                    record(type, off, size)
                } catch (e: RuntimeException) {
                    // A malformed record costs that record, not the picture.
                }
                off += size
            }
        }

        private fun i32(o: Int) = b.getInt(o)
        private fun i16(o: Int) = b.getShort(o).toFloat()
        private fun f32(o: Int) = b.getFloat(o)
        private fun color(o: Int): Int {
            val r = b.get(o).toInt() and 0xFF
            val g = b.get(o + 1).toInt() and 0xFF
            val bl = b.get(o + 2).toInt() and 0xFF
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }

        /** Logical (world) coordinates to the target rectangle. */
        private fun transform(): Matrix {
            val m = Matrix(st.world)
            if (st.mapMode == MM_ANISOTROPIC || st.mapMode == MM_ISOTROPIC) {
                val sx = if (st.winExtX != 0f) st.vpExtX / st.winExtX else 1f
                val sy = if (st.winExtY != 0f) st.vpExtY / st.winExtY else 1f
                m.postTranslate(-st.winOrgX, -st.winOrgY)
                m.postScale(sx, sy)
                m.postTranslate(st.vpOrgX, st.vpOrgY)
            } else {
                m.postTranslate(st.vpOrgX - st.winOrgX, st.vpOrgY - st.winOrgY)
            }
            m.postConcat(toDst)
            return m
        }

        private fun record(type: Int, o: Int, size: Int) {
            when (type) {
                EMR_SETMAPMODE -> st.mapMode = i32(o + 8)
                EMR_SETWINDOWEXTEX -> { st.winExtX = i32(o + 8).toFloat(); st.winExtY = i32(o + 12).toFloat() }
                EMR_SETWINDOWORGEX -> { st.winOrgX = i32(o + 8).toFloat(); st.winOrgY = i32(o + 12).toFloat() }
                EMR_SETVIEWPORTEXTEX -> { st.vpExtX = i32(o + 8).toFloat(); st.vpExtY = i32(o + 12).toFloat() }
                EMR_SETVIEWPORTORGEX -> { st.vpOrgX = i32(o + 8).toFloat(); st.vpOrgY = i32(o + 12).toFloat() }
                EMR_SETWORLDTRANSFORM -> st.world.set(xform(o + 8))
                EMR_MODIFYWORLDTRANSFORM -> when (i32(o + 32)) {
                    1 -> st.world.reset()
                    2 -> st.world.preConcat(xform(o + 8))
                    3 -> st.world.postConcat(xform(o + 8))
                    4 -> st.world.set(xform(o + 8))
                }
                EMR_SAVEDC -> if (saved.size < MAX_SAVED_STATES) saved.addLast(st.copy())
                EMR_RESTOREDC -> {
                    var n = -i32(o + 8)
                    while (n-- > 1 && saved.isNotEmpty()) saved.removeLast()
                    saved.removeLastOrNull()?.let { st = it }
                }
                EMR_SETPOLYFILLMODE -> st.fillWinding = i32(o + 8) == 2
                EMR_SETTEXTCOLOR -> st.textColor = color(o + 8)
                EMR_SETTEXTALIGN -> st.textAlign = i32(o + 8)

                EMR_CREATEPEN -> objects[i32(o + 8)] = Pen(color(o + 24), i32(o + 16).toFloat(), cosmetic = false, style = i32(o + 12))
                EMR_EXTCREATEPEN -> {
                    val style = i32(o + 28)
                    objects[i32(o + 8)] = Pen(color(o + 40), i32(o + 32).toFloat(), cosmetic = style and PS_GEOMETRIC == 0, style = style)
                }
                EMR_CREATEBRUSHINDIRECT -> objects[i32(o + 8)] = Brush(color(o + 16), hollow = i32(o + 12) == BS_NULL)
                EMR_EXTCREATEFONTINDIRECTW -> objects[i32(o + 8)] = font(o + 12)
                EMR_DELETEOBJECT -> objects.remove(i32(o + 8))
                EMR_SELECTOBJECT -> select(i32(o + 8))

                EMR_MOVETOEX -> {
                    curX = i32(o + 8).toFloat(); curY = i32(o + 12).toFloat()
                    path?.moveTo(curX, curY)
                }
                EMR_LINETO -> {
                    val x = i32(o + 8).toFloat(); val y = i32(o + 12).toFloat()
                    val p = path
                    if (p != null) p.lineTo(x, y) else strokeOnly(Path().apply { moveTo(curX, curY); lineTo(x, y) })
                    curX = x; curY = y
                }
                EMR_POLYLINETO16, EMR_POLYLINETO -> points(o, type == EMR_POLYLINETO16).let { pts ->
                    val target = path ?: Path().apply { moveTo(curX, curY) }
                    for (i in pts.indices step 2) target.lineTo(pts[i], pts[i + 1])
                    if (path == null) strokeOnly(target)
                    if (pts.size >= 2) { curX = pts[pts.size - 2]; curY = pts[pts.size - 1] }
                }
                EMR_POLYBEZIERTO16, EMR_POLYBEZIERTO -> points(o, type == EMR_POLYBEZIERTO16).let { pts ->
                    val target = path ?: Path().apply { moveTo(curX, curY) }
                    var i = 0
                    while (i + 5 < pts.size) {
                        target.cubicTo(pts[i], pts[i + 1], pts[i + 2], pts[i + 3], pts[i + 4], pts[i + 5])
                        i += 6
                    }
                    if (path == null) strokeOnly(target)
                    if (pts.size >= 2) { curX = pts[pts.size - 2]; curY = pts[pts.size - 1] }
                }
                EMR_POLYBEZIER16, EMR_POLYBEZIER -> points(o, type == EMR_POLYBEZIER16).let { pts ->
                    if (pts.size < 2) return
                    val target = path ?: Path()
                    target.moveTo(pts[0], pts[1])
                    var i = 2
                    while (i + 5 < pts.size) {
                        target.cubicTo(pts[i], pts[i + 1], pts[i + 2], pts[i + 3], pts[i + 4], pts[i + 5])
                        i += 6
                    }
                    if (path == null) strokeOnly(target)
                }
                EMR_POLYLINE16, EMR_POLYLINE, EMR_POLYGON16, EMR_POLYGON -> {
                    val pts = points(o, type == EMR_POLYLINE16 || type == EMR_POLYGON16)
                    if (pts.size < 4) return
                    val poly = path ?: Path()
                    poly.moveTo(pts[0], pts[1])
                    for (i in 2 until pts.size step 2) poly.lineTo(pts[i], pts[i + 1])
                    val closed = type == EMR_POLYGON16 || type == EMR_POLYGON
                    if (closed) poly.close()
                    if (path == null) if (closed) fillAndStroke(poly) else strokeOnly(poly)
                }
                EMR_POLYPOLYGON16, EMR_POLYPOLYLINE16, EMR_POLYPOLYGON, EMR_POLYPOLYLINE -> {
                    val small = type == EMR_POLYPOLYGON16 || type == EMR_POLYPOLYLINE16
                    val closed = type == EMR_POLYPOLYGON16 || type == EMR_POLYPOLYGON
                    val step = if (small) 4 else 8
                    val n = i32(o + 24)
                    if (n < 0 || n > (recordEnd - o - 32) / 4) return
                    var p = o + 32 + 4 * n
                    val poly = path ?: Path()
                    for (k in 0 until n) {
                        val count = i32(o + 32 + 4 * k)
                        if (count < 0 || count > (recordEnd - p) / step || count > pointBudget) return
                        pointBudget -= count
                        for (j in 0 until count) {
                            val x = if (small) i16(p) else i32(p).toFloat()
                            val y = if (small) i16(p + 2) else i32(p + 4).toFloat()
                            p += if (small) 4 else 8
                            if (j == 0) poly.moveTo(x, y) else poly.lineTo(x, y)
                        }
                        if (closed) poly.close()
                    }
                    if (path == null) if (closed) fillAndStroke(poly) else strokeOnly(poly)
                }
                EMR_RECTANGLE, EMR_ELLIPSE, EMR_ROUNDRECT -> {
                    val r = RectF(i32(o + 8).toFloat(), i32(o + 12).toFloat(), i32(o + 16).toFloat(), i32(o + 20).toFloat())
                    val shape = path ?: Path()
                    when (type) {
                        EMR_RECTANGLE -> shape.addRect(r, Path.Direction.CW)
                        EMR_ELLIPSE -> shape.addOval(r, Path.Direction.CW)
                        else -> shape.addRoundRect(r, i32(o + 24) / 2f, i32(o + 28) / 2f, Path.Direction.CW)
                    }
                    if (path == null) fillAndStroke(shape)
                }

                EMR_BEGINPATH -> { path = Path(); bracketPath = null }
                EMR_ENDPATH -> { bracketPath = path; path = null }
                EMR_CLOSEFIGURE -> path?.close()
                EMR_ABORTPATH -> { path = null; bracketPath = null }
                EMR_FILLPATH -> bracketPath?.let { fill(it) }.also { bracketPath = null }
                EMR_STROKEPATH -> bracketPath?.let { strokeOnly(it) }.also { bracketPath = null }
                EMR_STROKEANDFILLPATH -> bracketPath?.let { fillAndStroke(it) }.also { bracketPath = null }

                EMR_EXTTEXTOUTW, EMR_EXTTEXTOUTA -> text(o, wide = type == EMR_EXTTEXTOUTW)
                EMR_STRETCHDIBITS -> bitmap(o, x = i32(o + 24), y = i32(o + 28), w = i32(o + 72), h = i32(o + 76),
                    offBmi = i32(o + 48), cbBmi = i32(o + 52), offBits = i32(o + 56), cbBits = i32(o + 60))
                EMR_BITBLT, EMR_STRETCHBLT -> {
                    val x = i32(o + 24); val y = i32(o + 28); val w = i32(o + 32); val h = i32(o + 36)
                    val cbBmi = i32(o + 88)
                    if (cbBmi == 0) {
                        // No source bitmap: a pattern fill of the destination with the brush.
                        fill(Path().apply { addRect(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat(), Path.Direction.CW) })
                    } else {
                        bitmap(o, x, y, w, h, offBmi = i32(o + 84), cbBmi = cbBmi, offBits = i32(o + 92), cbBits = i32(o + 96))
                    }
                }
            }
        }

        private fun xform(o: Int) = Matrix().apply {
            setValues(floatArrayOf(f32(o), f32(o + 8), f32(o + 16), f32(o + 4), f32(o + 12), f32(o + 20), 0f, 0f, 1f))
        }

        /** Points of a poly record: bounds(16), count(4), then 16- or 32-bit pairs. */
        private fun points(o: Int, small: Boolean): FloatArray {
            // The count is trusted only as far as the record holds that many points.
            val count = i32(o + 24).coerceIn(0, minOf((recordEnd - o - 28) / (if (small) 4 else 8), pointBudget).coerceAtLeast(0))
            pointBudget -= count
            val out = FloatArray(count * 2)
            var p = o + 28
            for (i in 0 until count) {
                out[2 * i] = if (small) i16(p) else i32(p).toFloat()
                out[2 * i + 1] = if (small) i16(p + 2) else i32(p + 4).toFloat()
                p += if (small) 4 else 8
            }
            return out
        }

        private fun select(handle: Int) {
            if (handle and 0x80000000.toInt() != 0) {
                when (handle and 0x7FFFFFFF) {
                    0 -> st.brush = WHITE_BRUSH
                    1 -> st.brush = Brush(0xFFC0C0C0.toInt(), false)
                    2 -> st.brush = Brush(0xFF808080.toInt(), false)
                    3 -> st.brush = Brush(0xFF404040.toInt(), false)
                    4 -> st.brush = Brush(0xFF000000.toInt(), false)
                    5 -> st.brush = null
                    6 -> st.pen = Pen(0xFFFFFFFF.toInt(), 0f, true, 0)
                    7 -> st.pen = BLACK_PEN
                    8 -> st.pen = NULL_PEN
                    in 10..17 -> st.font = DEFAULT_FONT
                }
                return
            }
            when (val obj = objects[handle]) {
                is Pen -> st.pen = obj
                is Brush -> st.brush = if (obj.hollow) null else obj
                is Font -> st.font = obj
            }
        }

        private fun font(o: Int): Font {
            val face = StringBuilder()
            for (i in 0 until 32) {
                val c = b.getChar(o + 28 + 2 * i)
                if (c == '\u0000') break
                face.append(c)
            }
            return Font(
                height = i32(o).toFloat(),
                bold = i32(o + 16) >= 600,
                italic = b.get(o + 20).toInt() != 0,
                escapement = i32(o + 8) / 10f,
                face = face.toString()
            )
        }

        private fun fill(p: Path) {
            val brush = st.brush ?: return
            val t = Path(p).apply {
                fillType = if (st.fillWinding) Path.FillType.WINDING else Path.FillType.EVEN_ODD
                transform(transform())
            }
            canvas.drawPath(t, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = brush.color; style = Paint.Style.FILL })
        }

        private fun strokeOnly(p: Path) {
            val pen = st.pen
            if (pen.style and 0xF == PS_NULL) return
            val m = transform()
            val t = Path(p).apply { transform(m) }
            val width = if (pen.cosmetic || pen.width <= 0f) 1f else m.mapRadius(pen.width)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = pen.color
                style = Paint.Style.STROKE
                strokeWidth = width.coerceAtLeast(0.75f)
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
                when (pen.style and 0xF) {
                    PS_DASH -> pathEffect = DashPathEffect(floatArrayOf(width * 4, width * 2), 0f)
                    PS_DOT -> pathEffect = DashPathEffect(floatArrayOf(width, width * 2), 0f)
                    PS_DASHDOT, PS_DASHDOTDOT -> pathEffect = DashPathEffect(floatArrayOf(width * 4, width * 2, width, width * 2), 0f)
                }
            }
            canvas.drawPath(t, paint)
        }

        private fun fillAndStroke(p: Path) {
            fill(p)
            strokeOnly(p)
        }

        private fun text(o: Int, wide: Boolean) {
            val x = i32(o + 36).toFloat()
            val y = i32(o + 40).toFloat()
            val count = i32(o + 44)
            val strOff = i32(o + 48)
            if (count <= 0 || count > 10_000) return
            val raw = if (wide) {
                String(CharArray(count) { b.getChar(o + strOff + 2 * it) })
            } else {
                String(ByteArray(count) { b.get(o + strOff + it) }, Charsets.ISO_8859_1)
            }
            val font = st.font
            val text = MetaText.displayText(raw, font.face)
            val m = transform()
            val pt = floatArrayOf(x, y)
            m.mapPoints(pt)
            // Positive height is the cell height, negative the em height.
            val logical = if (font.height < 0) -font.height else font.height * 0.85f
            val size = m.mapRadius(if (logical > 0f) logical else 12f)
            if (size < 0.5f) return
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = st.textColor
                textSize = size
                typeface = MetaText.typeface(font.face, font.bold, font.italic)
                isLinearText = true
                isSubpixelText = true
                textAlign = when (st.textAlign and TA_CENTER) {
                    TA_CENTER -> Paint.Align.CENTER
                    TA_RIGHT -> Paint.Align.RIGHT
                    else -> Paint.Align.LEFT
                }
            }
            val fm = paint.fontMetrics
            val baselineShift = when {
                st.textAlign and TA_BASELINE == TA_BASELINE -> 0f
                st.textAlign and TA_BOTTOM != 0 -> -fm.descent
                else -> -fm.ascent
            }
            canvas.save()
            if (font.escapement != 0f) canvas.rotate(-font.escapement, pt[0], pt[1])
            // Per-character advances (offDx at +72), scaled into device pixels along the baseline.
            val offDx = i32(o + 72)
            val dx = if (offDx > 0 && o + offDx + 4L * count <= o + i32(o + 4)) {
                val unit = floatArrayOf(1f, 0f).also { m.mapVectors(it) }.let { Math.hypot(it[0].toDouble(), it[1].toDouble()).toFloat() }
                FloatArray(count) { i32(o + offDx + 4 * it) * unit }
            } else null
            MetaText.draw(canvas, text, pt[0], pt[1] + baselineShift, dx, paint)
            canvas.restore()
        }

        /** An embedded DIB: a BMP file is the DIB with a 14-byte file header in front. */
        private fun bitmap(o: Int, x: Int, y: Int, w: Int, h: Int, offBmi: Int, cbBmi: Int, offBits: Int, cbBits: Int) {
            if (cbBmi <= 0 || cbBits <= 0 || offBmi < 0 || offBits < 0) return
            // Both parts must lie inside the record that carries them.
            if (o.toLong() + offBmi + cbBmi > recordEnd || o.toLong() + offBits + cbBits > recordEnd) return
            val bmp = ByteBuffer.allocate(14 + cbBmi + cbBits).order(ByteOrder.LITTLE_ENDIAN)
            bmp.put('B'.code.toByte()).put('M'.code.toByte()).putInt(14 + cbBmi + cbBits).putInt(0).putInt(14 + cbBmi)
            bmp.put(b.array(), o + offBmi, cbBmi).put(b.array(), o + offBits, cbBits)
            val bitmap = SafeBitmaps.decode(bmp.array()) ?: return
            val corners = floatArrayOf(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
            transform().mapPoints(corners)
            val r = RectF(
                minOf(corners[0], corners[2]), minOf(corners[1], corners[3]),
                maxOf(corners[0], corners[2]), maxOf(corners[1], corners[3])
            )
            try {
                canvas.drawBitmap(bitmap, null, r, Paint(Paint.FILTER_BITMAP_FLAG))
            } finally {
                bitmap.recycle()
            }
        }
    }

    private const val MAX_POINTS = 4_000_000
    private const val MAX_SAVED_STATES = 1024

    private const val EMR_HEADER = 1
    private const val EMR_POLYBEZIER = 2
    private const val EMR_POLYGON = 3
    private const val EMR_POLYLINE = 4
    private const val EMR_POLYBEZIERTO = 5
    private const val EMR_POLYLINETO = 6
    private const val EMR_POLYPOLYLINE = 7
    private const val EMR_POLYPOLYGON = 8
    private const val EMR_SETWINDOWEXTEX = 9
    private const val EMR_SETWINDOWORGEX = 10
    private const val EMR_SETVIEWPORTEXTEX = 11
    private const val EMR_SETVIEWPORTORGEX = 12
    private const val EMR_EOF = 14
    private const val EMR_SETMAPMODE = 17
    private const val EMR_SETPOLYFILLMODE = 19
    private const val EMR_SETTEXTALIGN = 22
    private const val EMR_SETTEXTCOLOR = 24
    private const val EMR_MOVETOEX = 27
    private const val EMR_SAVEDC = 33
    private const val EMR_RESTOREDC = 34
    private const val EMR_SETWORLDTRANSFORM = 35
    private const val EMR_MODIFYWORLDTRANSFORM = 36
    private const val EMR_SELECTOBJECT = 37
    private const val EMR_CREATEPEN = 38
    private const val EMR_CREATEBRUSHINDIRECT = 39
    private const val EMR_DELETEOBJECT = 40
    private const val EMR_ELLIPSE = 42
    private const val EMR_RECTANGLE = 43
    private const val EMR_ROUNDRECT = 44
    private const val EMR_LINETO = 54
    private const val EMR_BEGINPATH = 59
    private const val EMR_ENDPATH = 60
    private const val EMR_CLOSEFIGURE = 61
    private const val EMR_FILLPATH = 62
    private const val EMR_STROKEANDFILLPATH = 63
    private const val EMR_STROKEPATH = 64
    private const val EMR_ABORTPATH = 68
    private const val EMR_BITBLT = 76
    private const val EMR_STRETCHBLT = 77
    private const val EMR_STRETCHDIBITS = 81
    private const val EMR_EXTCREATEFONTINDIRECTW = 82
    private const val EMR_EXTTEXTOUTA = 83
    private const val EMR_EXTTEXTOUTW = 84
    private const val EMR_POLYBEZIER16 = 85
    private const val EMR_POLYGON16 = 86
    private const val EMR_POLYLINE16 = 87
    private const val EMR_POLYBEZIERTO16 = 88
    private const val EMR_POLYLINETO16 = 89
    private const val EMR_POLYPOLYLINE16 = 90
    private const val EMR_POLYPOLYGON16 = 91
    private const val EMR_EXTCREATEPEN = 95

    private const val MM_TEXT = 1
    private const val MM_ISOTROPIC = 7
    private const val MM_ANISOTROPIC = 8
    private const val PS_DASH = 1
    private const val PS_DOT = 2
    private const val PS_DASHDOT = 3
    private const val PS_DASHDOTDOT = 4
    private const val PS_NULL = 5
    private const val PS_GEOMETRIC = 0x10000
    private const val BS_NULL = 1
    private const val TA_RIGHT = 2
    private const val TA_CENTER = 6
    private const val TA_BOTTOM = 8
    private const val TA_BASELINE = 24

    private val BLACK_PEN = Pen(0xFF000000.toInt(), 0f, true, 0)
    private val NULL_PEN = Pen(0, 0f, true, PS_NULL)
    private val WHITE_BRUSH = Brush(0xFFFFFFFF.toInt(), false)
    private val DEFAULT_FONT = Font(-12f, false, false, 0f, "")
}
