package io.github.baeseungwon1010.officecanvas.hwpx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import io.github.baeseungwon1010.officecanvas.slide.OfficeFonts
import io.github.baeseungwon1010.officecanvas.slide.SlideRenderer
import io.github.baeseungwon1010.officecanvas.util.DeviceScale
import io.github.baeseungwon1010.officecanvas.util.Limits
import io.github.baeseungwon1010.officecanvas.util.PageSource
import io.github.baeseungwon1010.officecanvas.util.PictureCache
import io.github.baeseungwon1010.officecanvas.util.SafeBitmaps
import io.github.baeseungwon1010.officecanvas.util.readCapped
import java.io.File
import java.util.zip.ZipFile

/**
 * Draws an [HwpxDoc] to one [Bitmap] per page.
 *
 * Like the pptx renderer, this mostly just draws what the parser already placed:
 * HwpxParser did the work of turning Hangul's cached line/page layout into
 * absolute positions, so there is no line breaking or page-flow logic here.
 */
internal object HwpxRenderer {

    var DEBUG_LOG_DRAW_ERRORS = false

    /** Renders every page at [targetLongSidePx] on its longer edge, one at a time,
     *  via [onPage] — same streaming shape as SlideRenderer.render, for the same
     *  reason: a many-page document should not need every page's bitmap alive at
     *  once. Pages without a size are left out. */
    fun render(doc: HwpxDoc, hwpxFile: File, targetLongSidePx: Int, onPage: (index: Int, bitmap: Bitmap) -> Unit) {
        Pages(doc, hwpxFile, PageSource.RENDER_CACHE_BYTES).use { pages ->
            for (i in 0 until pages.pageCount) onPage(i, pages.render(i, targetLongSidePx))
        }
    }

    /**
     * A document opened for drawing, in reference pixels (each page's long side
     * [PageSource.REF_LONG_SIDE_PX] px) under the canvas's matrix: text is measured,
     * justified and squeezed there, so it sets the same at every zoom. Holds the zip
     * the pictures come from and decoded pictures. One thread at a time.
     */
    class Pages(doc: HwpxDoc, file: File, cacheBytes: Long) : PageSource {
        private val sheets = doc.pages.filter { it.widthHu > 0 && it.heightHu > 0 }
        // A .hwp is an OLE2 file, not a zip; its pictures travel inside the model.
        internal val zip: ZipFile? = try { ZipFile(file) } catch (e: Exception) { null }
        internal val pictures = PictureCache(cacheBytes)
        /** BinData entry for each picture name, found once. */
        internal val entries = HashMap<String, java.util.zip.ZipEntry?>()

        private fun scale(page: HwpxPage) = PageSource.REF_LONG_SIDE_PX.toDouble() / maxOf(page.widthHu, page.heightHu)

        override val pageCount get() = sheets.size
        override fun widthPt(index: Int) = sheets[index].widthHu / HU_PER_PT
        override fun heightPt(index: Int) = sheets[index].heightHu / HU_PER_PT
        override fun refPxPerPt(index: Int) = scale(sheets[index]) * HU_PER_PT

        override fun renderSize(index: Int, longSidePx: Int): Pair<Int, Int> {
            val page = sheets[index]
            val s = longSidePx.toDouble() / maxOf(page.widthHu, page.heightHu)
            return (page.widthHu * s).toInt().coerceAtLeast(1) to (page.heightHu * s).toInt().coerceAtLeast(1)
        }

        override fun drawRef(canvas: Canvas, index: Int) {
            val page = sheets[index]
            val scale = scale(page)
            canvas.save()
            try {
                val sheet = RectF(0f, 0f, (page.widthHu * scale).toFloat(), (page.heightHu * scale).toFloat())
                canvas.clipRect(sheet)
                canvas.drawRect(sheet, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL })
                canvas.translate((page.bodyXHu * scale).toFloat(), (page.bodyYHu * scale).toFloat())
                for (el in page.elements) {
                    try {
                        drawElement(canvas, el, 0L, 0L, this, scale, 0)
                    } catch (e: Exception) {
                        if (DEBUG_LOG_DRAW_ERRORS) e.printStackTrace()
                    } catch (e: OutOfMemoryError) {
                        if (DEBUG_LOG_DRAW_ERRORS) e.printStackTrace()
                    }
                }
            } finally {
                canvas.restore()
            }
        }

        override fun close() {
            pictures.clear()
            zip?.close()
        }
    }

    private const val HU_PER_PT = HWPUNIT_PER_INCH / 72.0

    /**
     * Draws one line. Hangul decided where the line starts and how wide its column
     * is; this places the spans in it by alignment, stretching justified lines to
     * the column width as Hangul does, and squeezing a line that comes out wider
     * than its column in substitute fonts so it can't run into the next cell.
     */
    private fun drawText(
        canvas: Canvas, run: HwpxElement.TextRun, offsetXHu: Double, offsetYHu: Double, scale: Double,
        pages: Pages? = null, depth: Int = 0
    ): Long {
        if (run.spans.isEmpty()) return 0
        val paints = run.spans.map { paintFor(it, scale) }
        val widths = run.spans.mapIndexed { i, span -> spanWidth(span, paints[i], scale) }
        val natural = widths.sum()
        val avail = (run.widthHu * scale).toFloat()
        // Far wider than its column: the writer saved one line for a whole
        // paragraph instead of Hangul's line breaks, so break it here.
        if (avail > 0f && natural > avail * 1.3f) return drawWrapped(canvas, run, paints, offsetXHu, offsetYHu, scale)
        val text = run.text
        val stretch = avail > 0f && natural < avail && when (run.align) {
            ParaAlign.JUSTIFY -> !run.lastLine
            ParaAlign.DISTRIBUTE -> true
            else -> false
        }
        // Where justification adds space: between words, or between all characters.
        val gaps = if (!stretch) 0 else if (run.align == ParaAlign.DISTRIBUTE) (text.trimEnd().length - 1).coerceAtLeast(0)
            else text.trimEnd().count { it == ' ' }
        val extra = if (gaps > 0) (avail - natural) / gaps else 0f
        val squeeze = if (avail > 0f && natural > avail * 1.01f) (avail / natural).coerceAtLeast(0.6f) else 1f
        if (squeeze < 1f) paints.forEach { it.textScaleX *= squeeze }
        val width = if (gaps > 0) avail else natural * squeeze

        val left = ((run.xHu + offsetXHu) * scale).toFloat()
        val baseline0 = ((run.yHu + offsetYHu + run.baselineHu) * scale).toFloat()
        if (run.spans.none { it.inline != null }) {
            val size = paints.maxOf { it.textSize }
            if (DeviceScale.outside(canvas, RectF(left - size, baseline0 - size * 2f, left + maxOf(avail, width) + size, baseline0 + size))) return 0
        }
        var x = left + when (run.align) {
            ParaAlign.CENTER -> ((avail - width) / 2f).coerceAtLeast(0f)
            ParaAlign.RIGHT -> (avail - width).coerceAtLeast(0f)
            else -> 0f
        }
        val baseline = ((run.yHu + offsetYHu + run.baselineHu) * scale).toFloat()
        val lastGap = text.trimEnd().length
        var index = 0
        for ((i, span) in run.spans.withIndex()) {
            val paint = paints[i]
            val y = baseline - span.style.script * paint.textSize * 0.4f
            if (span.tabHu != null) {
                span.inline?.let { drawInline(canvas, it, x, baseline, pages, scale, depth) }
                x += spanWidth(span, paint, scale)
                index += span.text.length
                continue
            }
            if (gaps == 0) {
                background(canvas, span, paint, x, paint.measureText(span.text), y)
                canvas.drawText(span.text, x, y, paint)
                x += paint.measureText(span.text)
                index += span.text.length
                continue
            }
            // Stretched: draw piece by piece, adding the extra space at each gap.
            val distribute = run.align == ParaAlign.DISTRIBUTE
            var pieceStart = 0
            for (k in span.text.indices) {
                val at = index + k
                val isGap = if (distribute) at < lastGap - 1 else span.text[k] == ' ' && at < lastGap
                if (!isGap) continue
                val piece = span.text.substring(pieceStart, k + 1)
                background(canvas, span, paint, x, paint.measureText(piece) + extra, y)
                canvas.drawText(piece, x, y, paint)
                x += paint.measureText(piece) + extra
                pieceStart = k + 1
            }
            if (pieceStart < span.text.length) {
                val piece = span.text.substring(pieceStart)
                background(canvas, span, paint, x, paint.measureText(piece), y)
                canvas.drawText(piece, x, y, paint)
                x += paint.measureText(piece)
            }
            index += span.text.length
        }
        return 0
    }

    /** A span's background color behind [width] px of its text from [x], over the
     *  font's ascent and descent around [baseline]. */
    private fun background(canvas: Canvas, span: Span, paint: Paint, x: Float, width: Float, baseline: Float) {
        val color = span.style.backgroundArgb ?: return
        val fm = paint.fontMetrics
        canvas.drawRect(x, baseline + fm.ascent, x + width, baseline + fm.descent, Paint().apply { this.color = color; style = Paint.Style.FILL })
    }

    /**
     * Breaks [run] into lines that fit its width (at spaces where possible, else
     * between characters, as Hangul does for Korean) and draws them at Hangul's
     * default 160% line pitch. Returns the height added beyond the run's own line.
     */
    private fun drawWrapped(
        canvas: Canvas, run: HwpxElement.TextRun, paints: List<Paint>, offsetXHu: Double, offsetYHu: Double, scale: Double
    ): Long {
        val avail = (run.widthHu * scale).toFloat()
        class Ch(val c: Char, val paint: Int, val w: Float)
        val chars = run.spans.flatMapIndexed { i, span ->
            if (span.tabHu != null) listOf(Ch(' ', i, spanWidth(span, paints[i], scale)))
            else span.text.map { Ch(it, i, paints[i].measureText(it.toString())) }
        }
        val lines = mutableListOf<List<Ch>>()
        var start = 0
        while (start < chars.size && lines.size < 500) {
            var w = 0f
            var end = start
            var lastSpace = -1
            while (end < chars.size && (w + chars[end].w <= avail || end == start)) {
                if (chars[end].c == ' ') lastSpace = end
                w += chars[end].w
                end++
            }
            if (end < chars.size && lastSpace > start) end = lastSpace + 1
            lines += chars.subList(start, end)
            start = end
            while (start < chars.size && chars[start].c == ' ') start++
        }
        val pitchHu = run.heightHu * 160 / 100
        val x0 = ((run.xHu + offsetXHu) * scale).toFloat()
        for ((k, line) in lines.withIndex()) {
            val baseline = ((run.yHu + offsetYHu + run.baselineHu + k * pitchHu) * scale).toFloat()
            val width = line.sumOf { it.w.toDouble() }.toFloat()
            var x = x0 + when (run.align) {
                ParaAlign.CENTER -> ((avail - width) / 2f).coerceAtLeast(0f)
                ParaAlign.RIGHT -> (avail - width).coerceAtLeast(0f)
                else -> 0f
            }
            var i = 0
            while (i < line.size) {
                var j = i
                while (j < line.size && line[j].paint == line[i].paint) j++
                val piece = String(CharArray(j - i) { line[i + it].c })
                canvas.drawText(piece, x, baseline, paints[line[i].paint])
                x += line.subList(i, j).sumOf { it.w.toDouble() }.toFloat()
                i = j
            }
        }
        return (lines.size - 1).coerceAtLeast(0) * pitchHu
    }

    /**
     * An object in a line at [x] (px) on the line's [baseline] (px): an equation
     * puts its own baseline there, anything else its bottom.
     */
    private fun drawInline(canvas: Canvas, obj: HwpxElement, x: Float, baseline: Float, pages: Pages?, scale: Double, depth: Int) {
        if (depth > Limits.MAX_NESTING) return
        val base = baseline / scale
        val top = when (obj) {
            is HwpxElement.Equation -> base - obj.heightHu * obj.baselinePercent / 100.0
            else -> base - obj.extentHu
        }
        drawElement(canvas, obj.at((x / scale).toLong(), top.toLong()), 0, 0, pages, scale, depth + 1)
    }

    /** [span]'s width in HWPUNIT as [drawText] draws it (the same face, size,
     *  width ratio and spacing): for laying out text no layout was saved for. */
    internal fun widthHu(span: Span): Float = spanWidth(span, paintFor(span, MEASURE_SCALE), MEASURE_SCALE) / MEASURE_SCALE.toFloat()

    /** Large enough for exact widths, small enough that sizes stay within Paint's range. */
    private const val MEASURE_SCALE = 0.1

    /** A tab takes the width Hangul saved for it; text, its measured width. */
    private fun spanWidth(span: Span, paint: Paint, scale: Double): Float =
        span.tabHu?.let { (it * scale).toFloat() } ?: paint.measureText(span.text)

    private fun paintFor(span: Span, scale: Double): Paint {
        val style = span.style
        val sizePx = (style.sizePt * (if (style.script != 0) 0.7f else 1f) * HWPUNIT_PER_INCH / 72.0 * scale).toFloat()
        return Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
            textSize = sizePx.coerceIn(1f, 2000f)
            color = style.colorArgb
            typeface = typefaceFor(span)
            if (style.bold && typeface?.isBold != true) isFakeBoldText = true
            if (style.italic && typeface?.isItalic != true) textSkewX = -0.2f
            isUnderlineText = style.underline
            isStrikeThruText = style.strike
            textScaleX = (style.widthPercent.coerceIn(50, 200) / 100f)
            letterSpacing = style.spacingPercent.coerceIn(-50, 50) / 100f
        }
    }

    /**
     * Latin text in a font the bundled metric-compatible faces cover uses those;
     * everything else (Hangul above all) uses the system's serif or sans-serif,
     * chosen from the Hangul face name: Batang/Myeongjo/Gungseo-style faces are
     * serif, Dotum/Gulim/Gothic-style ones sans.
     */
    private fun typefaceFor(span: Span): Typeface? {
        val style = span.style
        val hasHangul = span.text.any { it.code in 0x1100..0x11FF || it.code in 0x3130..0x318F || it.code in 0xAC00..0xD7A3 || it.code in 0x4E00..0x9FFF }
        if (!hasHangul) {
            val kind = OfficeFonts.kindFor(style.latinFace)
            val face = kind?.let { SlideRenderer.fonts?.typeface(it, style.bold, style.italic) }
            if (face != null) return face
        }
        val name = (if (hasHangul) style.hangulFace else style.latinFace ?: style.hangulFace)?.lowercase().orEmpty()
        val serif = SERIF_FACES.any { it in name }
        val family = if (serif) Typeface.SERIF else Typeface.SANS_SERIF
        val flags = (if (style.bold) Typeface.BOLD else 0) or (if (style.italic) Typeface.ITALIC else 0)
        return Typeface.create(family, flags)
    }

    private fun heightOf(el: HwpxElement): Long = el.extentHu

    private val SERIF_FACES = listOf("바탕", "명조", "궁서", "batang", "myeongjo", "gungsuh", "times", "serif", "신명", "한양신명조", "함초롬바탕", "cambria", "georgia", "garamond", "palatino", "book antiqua", "century")

    private fun drawElement(canvas: Canvas, el: HwpxElement, dx: Long, dy: Long, pages: Pages?, scale: Double, depth: Int) {
        when (el) {
            is HwpxElement.TextRun -> { drawText(canvas, el, dx.toDouble(), dy.toDouble(), scale, pages, depth) }
            is HwpxElement.Table -> if (depth <= Limits.MAX_NESTING) drawTable(canvas, el.copy(xHu = el.xHu + dx, yHu = el.yHu + dy), pages, scale, depth)
            is HwpxElement.ImageEl -> drawImage(canvas, el.copy(xHu = el.xHu + dx, yHu = el.yHu + dy), pages, scale)
            is HwpxElement.Drawing -> if (depth <= Limits.MAX_NESTING) drawDrawing(canvas, el.copy(xHu = el.xHu + dx, yHu = el.yHu + dy), pages, scale, depth)
            is HwpxElement.Equation -> drawEquation(canvas, el.copy(xHu = el.xHu + dx, yHu = el.yHu + dy), scale)
        }
    }

    private fun drawTable(canvas: Canvas, table: HwpxElement.Table, pages: Pages?, scale: Double, depth: Int) {
        val totalW = table.columnWidthsHu.sum().coerceAtLeast(1)
        val colXHu = table.columnWidthsHu.runningFold(0L) { acc, w -> acc + w }
        val totalH = table.rowHeightsHu.sum().coerceAtLeast(1)
        val rowYHu = table.rowHeightsHu.runningFold(0L) { acc, h -> acc + h }
        val wScale = table.widthHu.toDouble() / totalW
        val hScale = table.heightHu.toDouble() / totalH
        fun colX(i: Int) = table.xHu + (colXHu[i] * wScale).toLong()
        fun rowY(i: Int) = table.yHu + (rowYHu[i] * hScale).toLong()

        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY; style = Paint.Style.STROKE; strokeWidth = 1.5f
        }
        class Placed(val cell: TableCell, val rect: RectF, val left: Long, val top: Long, val bottom: Long)
        val placed = mutableListOf<Placed>()
        for (r in table.cells.indices) {
            for (c in table.cells[r].indices) {
                val cell = table.cells[r][c] ?: continue
                val left = colX(c); val top = rowY(r)
                val right = colX((c + cell.colSpan).coerceAtMost(table.columnWidthsHu.size))
                val bottom = rowY((r + cell.rowSpan).coerceAtMost(table.rowHeightsHu.size))
                placed += Placed(cell, RectF((left * scale).toFloat(), (top * scale).toFloat(), (right * scale).toFloat(), (bottom * scale).toFloat()), left, top, bottom)
            }
        }
        // Fills first, then text, then borders on top (a neighbour's fill must not hide a line).
        for (p in placed) p.cell.borderFill?.fillArgb?.let { canvas.drawRect(p.rect, Paint().apply { color = it; style = Paint.Style.FILL }) }
        for (p in placed) {
            // Cell text carries positions relative to the cell's own top-left
            // corner, so the cell's origin is added here; a middle- or
            // bottom-aligned cell moves its content down within the cell.
            val content = p.cell.elements.maxOfOrNull { it.yHu + heightOf(it) } ?: 0L
            val room = (p.bottom - p.top - content).coerceAtLeast(0)
            val dy = when (p.cell.vAlign) { 1 -> room / 2; 2 -> room; else -> 0L }
            // Lines broken here (see drawWrapped) push the rest of the cell down.
            var pushed = 0L
            if (p.cell.clip) { canvas.save(); canvas.clipRect(p.rect) }
            for (el in p.cell.elements.sortedBy { it.yHu }) {
                if (el is HwpxElement.TextRun) pushed += drawText(canvas, el, p.left.toDouble(), (p.top + dy + pushed).toDouble(), scale, pages, depth + 1)
                else drawElement(canvas, el, p.left, p.top + dy + pushed, pages, scale, depth + 1)
            }
            if (p.cell.clip) canvas.restore()
        }
        for (p in placed) {
            val bf = p.cell.borderFill
            if (bf == null) { canvas.drawRect(p.rect, gridPaint); continue }
            fun line(b: Border?, x0: Float, y0: Float, x1: Float, y1: Float) {
                b ?: return
                canvas.drawLine(x0, y0, x1, y1, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = b.argb; style = Paint.Style.STROKE; strokeWidth = (b.widthHu * scale).toFloat().coerceAtLeast(DeviceScale.minStroke(canvas))
                })
            }
            val r = p.rect
            line(bf.left, r.left, r.top, r.left, r.bottom)
            line(bf.right, r.right, r.top, r.right, r.bottom)
            line(bf.top, r.left, r.top, r.right, r.top)
            line(bf.bottom, r.left, r.bottom, r.right, r.bottom)
        }
    }

    /** A rectangle, ellipse, line or polygon, filled and outlined, with any text box content on top. */
    private fun drawDrawing(canvas: Canvas, d: HwpxElement.Drawing, pages: Pages?, scale: Double, depth: Int) {
        val r = RectF((d.xHu * scale).toFloat(), (d.yHu * scale).toFloat(),
            ((d.xHu + d.widthHu) * scale).toFloat(), ((d.yHu + d.heightHu) * scale).toFloat())
        val path = android.graphics.Path()
        when (d.kind) {
            DrawingKind.RECTANGLE -> if (d.cornerPercent > 0) {
                val radius = minOf(r.width(), r.height()) * d.cornerPercent.coerceAtMost(50) / 100f
                path.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW)
            } else path.addRect(r, android.graphics.Path.Direction.CW)
            DrawingKind.ELLIPSE -> path.addOval(r, android.graphics.Path.Direction.CW)
            DrawingKind.LINE, DrawingKind.POLYGON -> d.points.forEachIndexed { i, (px, py) ->
                val x = ((d.xHu + px) * scale).toFloat(); val y = ((d.yHu + py) * scale).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        if (d.kind == DrawingKind.POLYGON && d.points.size > 2) path.close()
        val reach = RectF().also { path.computeBounds(it, true); it.union(r); it.sort() }
        val pad = (d.lineWidthHu * scale).toFloat() + 2f
        reach.inset(-pad, -pad)
        // (Its text box content is still drawn when the shape is out of view: text can overflow it.)
        if (!DeviceScale.outside(canvas, reach)) {
            if (d.kind != DrawingKind.LINE) d.fillArgb?.let { canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = it; style = Paint.Style.FILL }) }
            d.lineArgb?.let {
                canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = it; style = Paint.Style.STROKE; strokeWidth = (d.lineWidthHu * scale).toFloat().coerceAtLeast(DeviceScale.minStroke(canvas))
                })
            }
        }
        if (d.content.isEmpty()) return
        val bottom = d.content.maxOf { it.yHu + it.extentHu }
        val room = (d.heightHu - bottom).coerceAtLeast(0)
        val dy = when (d.contentVAlign) { 1 -> room / 2; 2 -> room; else -> 0L }
        for (el in d.content) drawElement(canvas, el, d.xHu, d.yHu + dy, pages, scale, depth + 1)
    }

    /**
     * Typesets the equation at its base size and fits it to the box Hangul saved
     * (Hangul's own metrics differ a little), with the baseline where Hangul put it.
     */
    private fun drawEquation(canvas: Canvas, eq: HwpxElement.Equation, scale: Double) {
        val boxW = (eq.widthHu * scale).toFloat()
        val boxH = (eq.heightHu * scale).toFloat()
        if (boxW < 1f || boxH < 1f) return
        val x0 = (eq.xHu * scale).toFloat(); val y0 = (eq.yHu * scale).toFloat()
        if (DeviceScale.outside(canvas, RectF(x0 - boxW, y0 - boxH, x0 + boxW * 2, y0 + boxH * 2))) return
        val laid = EquationLayout(eq.colorArgb).layout(eq.tree ?: EquationParser.parse(eq.script), (eq.sizeHu * scale).toFloat())
        if (laid.width <= 0f || laid.height <= 0f) return
        val k = minOf(boxW / laid.width, boxH / laid.height, 1.25f).coerceAtLeast(0.2f)
        val baseline = ((eq.yHu * scale) + boxH * eq.baselinePercent / 100f).toFloat()
        canvas.save()
        canvas.translate((eq.xHu * scale).toFloat() + (boxW - laid.width * k) / 2, baseline)
        canvas.scale(k, k)
        laid.draw(canvas, 0f, 0f)
        canvas.restore()
    }

    private fun drawImage(canvas: Canvas, image: HwpxElement.ImageEl, pages: Pages?, scale: Double) {
        val rect = RectF(
            (image.xHu * scale).toFloat(), (image.yHu * scale).toFloat(),
            ((image.xHu + image.widthHu) * scale).toFloat(), ((image.yHu + image.heightHu) * scale).toFloat()
        )
        if (DeviceScale.outside(canvas, rect)) return
        // A cropped picture is drawn at the size the whole image would have, shifted so
        // the visible part lands in the box, and clipped to the box.
        val c = image.crop
        val full = if (c == null) rect else {
            val w = rect.width() / (1f - c.left - c.right)
            val h = rect.height() / (1f - c.top - c.bottom)
            RectF(rect.left - c.left * w, rect.top - c.top * h, rect.left - c.left * w + w, rect.top - c.top * h + h)
        }
        // Decoded for its size on the device: zoomed in, sharper.
        val k = DeviceScale.pxPerUnit(canvas)
        val key: Any? = image.bytes ?: image.zipEntry
        val cached = key?.let { pages?.pictures?.get(it, full.width() * k, full.height() * k) }
        var kept = cached != null
        val bitmap = cached ?: run {
            val bytes = image.bytes?.invoke() ?: run {
                // BinData/<id>.<ext>: the reference in the XML omits the extension, so the
                // matching zip entry is found by prefix rather than assumed.
                val name = image.zipEntry ?: return
                val zip = pages?.zip ?: return
                // (Exactly that name before the extension: "image1" must not find image10.png.)
                val entry = pages.entries.getOrPut(name) {
                    zip.entries().asSequence().firstOrNull { it.name == "BinData/$name" || it.name.startsWith("BinData/$name.") }
                } ?: return
                zip.readCapped(entry, SafeBitmaps.MAX_IMAGE_BYTES)
            } ?: return
            val (decoded, largest) = SafeBitmaps.decodeSized(bytes, full.width() * k, full.height() * k) ?: return
            kept = key != null && pages?.pictures?.put(key, decoded, largest) == true
            decoded
        }
        canvas.save()
        try {
            canvas.clipRect(rect)
            canvas.drawBitmap(bitmap, null, full, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } finally {
            canvas.restore()
            if (!kept) bitmap.recycle()
        }
    }
}
