package io.github.baeseungwon1010.officecanvas.metafile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Plays a 16-bit Windows Metafile (WMF) onto a Canvas, scaled into a target
 * rectangle — the older sibling of [EmfRenderer]. Pre-2007 clip art and pasted
 * diagrams are stored as WMF, which BitmapFactory can't decode.
 *
 * Input is a *placeable* WMF (0x9AC6CDD7 header with the bounding box). Covers
 * pens, brushes, polygons/polylines, rectangles, ellipses, text and embedded
 * DIB bitmaps; clipping, raster ops, hatches and arcs are ignored.
 */
internal object WmfRenderer {

    /** Wraps raw WMF bytes (header + records, no placeable header) with one, using [bounds] as the extent. */
    fun placeable(wmf: ByteArray, left: Int, top: Int, right: Int, bottom: Int): ByteArray {
        val out = ByteBuffer.allocate(22 + wmf.size).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(PLACEABLE_KEY).putShort(0)
        out.putShort(left.toShort()).putShort(top.toShort()).putShort(right.toShort()).putShort(bottom.toShort())
        out.putShort(1440).putInt(0).putShort(0)
        out.put(wmf)
        return out.array()
    }

    fun draw(canvas: Canvas, wmf: ByteArray, dst: RectF) {
        if (wmf.size < 40) return
        val b = ByteBuffer.wrap(wmf).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(0) != PLACEABLE_KEY) return
        val left = b.getShort(6).toFloat()
        val top = b.getShort(8).toFloat()
        val right = b.getShort(10).toFloat()
        val bottom = b.getShort(12).toFloat()
        if (right == left || bottom == top) return
        canvas.save()
        canvas.clipRect(dst)
        try {
            Player(b, canvas, dst, left, top, right - left, bottom - top).play()
        } finally {
            canvas.restore()
        }
    }

    private class Pen(val color: Int, val width: Float, val none: Boolean)
    private class Brush(val color: Int, val none: Boolean)
    private class Font(val height: Float, val bold: Boolean, val italic: Boolean, val face: String, val escapement: Float = 0f)

    private class Player(
        private val b: ByteBuffer, private val canvas: Canvas, dst: RectF,
        private var winOrgX: Float, private var winOrgY: Float, private var winExtX: Float, private var winExtY: Float
    ) {
        private val dstRect = dst
        private val objects = HashMap<Int, Any>()
        private var pen = Pen(0xFF000000.toInt(), 0f, false)
        private var brush: Brush = Brush(0xFFFFFFFF.toInt(), false)
        private var font = Font(-12f, false, false, "Arial")
        private var textColor = 0xFF000000.toInt()
        private var textAlign = 0
        private var winding = false
        private var curX = 0f
        private var curY = 0f

        private fun sx() = dstRect.width() / winExtX
        private fun sy() = dstRect.height() / winExtY
        private fun mapX(x: Float) = dstRect.left + (x - winOrgX) * sx()
        private fun mapY(y: Float) = dstRect.top + (y - winOrgY) * sy()

        fun play() {
            var off = 22 + 18 // placeable header + standard WMF header
            var guard = 1_000_000
            while (off + 6 <= b.limit() && guard-- > 0) {
                val words = b.getInt(off)
                val fn = b.getShort(off + 4).toInt() and 0xFFFF
                if (words < 3 || off + words * 2L > b.limit()) break
                if (fn == 0) break
                try {
                    record(fn, off + 6, off + words * 2)
                } catch (e: RuntimeException) {
                    // a malformed record costs that record, not the picture
                }
                off += words * 2
            }
        }

        private fun i16(o: Int) = b.getShort(o).toFloat()
        private fun u16(o: Int) = b.getShort(o).toInt() and 0xFFFF
        private fun color(o: Int): Int {
            val r = b.get(o).toInt() and 0xFF
            val g = b.get(o + 1).toInt() and 0xFF
            val bl = b.get(o + 2).toInt() and 0xFF
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }

        private fun add(obj: Any) {
            var i = 0
            while (objects.containsKey(i)) i++
            objects[i] = obj
        }

        private fun record(fn: Int, p: Int, end: Int) {
            when (fn) {
                0x020B -> { winOrgY = i16(p); winOrgX = i16(p + 2) } // SetWindowOrg
                0x020C -> { // SetWindowExt
                    val y = i16(p); val x = i16(p + 2)
                    if (x != 0f && y != 0f) { winExtY = y; winExtX = x }
                }
                0x0209 -> textColor = color(p) // SetTextColor
                0x012E -> textAlign = u16(p) // SetTextAlign
                0x0106 -> winding = u16(p) == 2 // SetPolyFillMode
                0x02FC -> { // CreateBrushIndirect
                    val style = u16(p)
                    add(Brush(color(p + 2), style == 1 || style == 5))
                }
                0x02FA -> { // CreatePenIndirect
                    val style = u16(p) and 0xF
                    add(Pen(color(p + 6), i16(p + 2), style == 5))
                }
                0x02FB -> { // CreateFontIndirect
                    val face = String(ByteArray(32) { i -> if (p + 18 + i < end) b.get(p + 18 + i) else 0 }, Charsets.ISO_8859_1)
                        .substringBefore('\u0000')
                    add(Font(i16(p), i16(p + 8) >= 600f, b.get(p + 10).toInt() != 0, face, i16(p + 4) / 10f))
                }
                0x012D -> when (val o = objects[u16(p)]) { // SelectObject
                    is Pen -> pen = o
                    is Brush -> brush = o
                    is Font -> font = o
                }
                0x01F0 -> objects.remove(u16(p)) // DeleteObject
                0x0214 -> { curY = i16(p); curX = i16(p + 2) } // MoveTo
                0x0213 -> { // LineTo
                    val y = i16(p); val x = i16(p + 2)
                    val path = Path().apply { moveTo(mapX(curX), mapY(curY)); lineTo(mapX(x), mapY(y)) }
                    stroke(path)
                    curX = x; curY = y
                }
                0x0324, 0x0325 -> { // Polygon, Polyline
                    val n = u16(p)
                    val path = polyPath(p + 2, n, close = fn == 0x0324)
                    if (fn == 0x0324) fillAndStroke(path) else stroke(path)
                }
                0x0538 -> { // PolyPolygon
                    val polys = u16(p)
                    var ptOff = p + 2 + polys * 2
                    val path = Path()
                    for (i in 0 until polys) {
                        val n = u16(p + 2 + i * 2)
                        path.addPath(polyPath(ptOff, n, close = true))
                        ptOff += n * 4
                    }
                    fillAndStroke(path)
                }
                0x041B -> fillAndStroke(Path().apply { addRect(rect(p, p + 2, p + 4, p + 6), Path.Direction.CW) }) // Rectangle
                0x0418 -> fillAndStroke(Path().apply { addOval(rect(p, p + 2, p + 4, p + 6), Path.Direction.CW) }) // Ellipse
                0x061C -> { // RoundRect
                    val r = rect(p + 4, p + 6, p + 8, p + 10)
                    fillAndStroke(Path().apply { addRoundRect(r, i16(p + 2) * sx() / 2, i16(p) * sy() / 2, Path.Direction.CW) })
                }
                0x0521 -> { // TextOut: len, string (even-padded), y, x
                    val len = u16(p)
                    val s = String(ByteArray(len) { b.get(p + 2 + it) }, Charsets.ISO_8859_1)
                    val after = p + 2 + len + (len and 1)
                    text(s, i16(after), i16(after + 2), null)
                }
                0x0A32 -> { // ExtTextOut: y, x, len, options, [rect], string (even-padded), [dx: int16 per char]
                    val len = u16(p + 4)
                    val opts = u16(p + 6)
                    val strAt = p + 8 + if (opts and 0x6 != 0) 8 else 0
                    val s = String(ByteArray(len) { b.get(strAt + it) }, Charsets.ISO_8859_1)
                    val dxAt = strAt + len + (len and 1)
                    val dx = if (dxAt + 2 * len <= end) FloatArray(len) { i16(dxAt + 2 * it) * Math.abs(sx()) } else null
                    text(s, i16(p), i16(p + 2), dx)
                }
                0x0F43 -> dib(p + 22, end, i16(p + 14), i16(p + 16), i16(p + 18), i16(p + 20)) // StretchDIBits
                0x0B41 -> dib(p + 20, end, i16(p + 12), i16(p + 14), i16(p + 16), i16(p + 18)) // DibStretchBlt
            }
        }

        private fun rect(bottom: Int, right: Int, top: Int, left: Int) =
            RectF(mapX(i16(left)), mapY(i16(top)), mapX(i16(right)), mapY(i16(bottom))).also {
                if (it.left > it.right) { val t = it.left; it.left = it.right; it.right = t }
                if (it.top > it.bottom) { val t = it.top; it.top = it.bottom; it.bottom = t }
            }

        private fun polyPath(p: Int, n: Int, close: Boolean): Path {
            val path = Path()
            for (i in 0 until n) {
                val x = mapX(i16(p + i * 4)); val y = mapY(i16(p + i * 4 + 2))
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (close) path.close()
            path.fillType = if (winding) Path.FillType.WINDING else Path.FillType.EVEN_ODD
            return path
        }

        private fun strokePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = pen.color
            strokeWidth = maxOf(1f, pen.width * minOf(sx(), sy()))
            strokeJoin = Paint.Join.ROUND
        }

        private fun stroke(path: Path) { if (!pen.none) canvas.drawPath(path, strokePaint()) }

        private fun fillAndStroke(path: Path) {
            if (!brush.none) canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = brush.color })
            stroke(path)
        }

        private fun text(raw: String, y: Float, x: Float, dx: FloatArray?) {
            // Negative height is the em size; positive is the whole cell (em plus
            // internal leading), about 1.15 em for the usual Office faces.
            val logical = if (font.height < 0) -font.height else font.height / 1.15f
            val size = logical * Math.abs(sy())
            if (size < 1f || raw.isEmpty()) return
            val s = MetaText.displayText(raw, font.face)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = textColor
                textSize = size
                typeface = MetaText.typeface(font.face, font.bold, font.italic)
                isLinearText = true
                isSubpixelText = true
                textAlign = when (this@Player.textAlign and 6) { 6 -> Paint.Align.CENTER; 2 -> Paint.Align.RIGHT; else -> Paint.Align.LEFT }
            }
            val fm = paint.fontMetrics
            val shift = when {
                textAlign and 24 == 24 -> 0f // TA_BASELINE
                textAlign and 8 != 0 -> -fm.descent // TA_BOTTOM
                else -> -fm.ascent // TA_TOP
            }
            canvas.save()
            if (font.escapement != 0f) canvas.rotate(-font.escapement, mapX(x), mapY(y))
            MetaText.draw(canvas, s, mapX(x), mapY(y) + shift, dx, paint)
            canvas.restore()
        }

        /** [at] is the DIB (BITMAPINFO + bits); destination is in logical units. */
        private fun dib(at: Int, end: Int, h: Float, w: Float, y: Float, x: Float) {
            val bmp = decodeDib(ByteArray(end - at) { b.get(at + it) }) ?: return
            val dst = RectF(mapX(x), mapY(y), mapX(x + w), mapY(y + h))
            canvas.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            bmp.recycle()
        }
    }

    private val PLACEABLE_KEY = 0x9AC6CDD7L.toInt()

    /** Decodes a bare DIB (BITMAPINFOHEADER + palette + pixels) by giving it a BMP file header. */
    fun decodeDib(dib: ByteArray): Bitmap? {
        val bmp = dibToBmp(dib) ?: return null
        return BitmapFactory.decodeByteArray(bmp, 0, bmp.size)
    }

    /** A bare DIB with a BITMAPFILEHEADER prepended, or null if the header is implausible. */
    fun dibToBmp(dib: ByteArray): ByteArray? {
        if (dib.size < 40) return null
        val b = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN)
        val headerSize = b.getInt(0)
        if (headerSize < 40 || headerSize > dib.size) return null
        val bits = b.getShort(14).toInt() and 0xFFFF
        val compression = b.getInt(16)
        val used = b.getInt(32)
        val palette = if (used != 0) used else if (bits <= 8) 1 shl bits else 0
        val masks = if (compression == 3 && headerSize == 40) 12 else 0
        val offBits = 14 + headerSize + masks + palette * 4
        val out = ByteBuffer.allocate(14 + dib.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put('B'.code.toByte()).put('M'.code.toByte()).putInt(14 + dib.size).putInt(0).putInt(offBits)
        out.put(dib)
        return out.array()
    }
}
