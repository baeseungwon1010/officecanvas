package io.github.baeseungwon1010.officecanvas.slide

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import io.github.baeseungwon1010.officecanvas.metafile.EmfRenderer
import io.github.baeseungwon1010.officecanvas.metafile.SvgRenderer
import io.github.baeseungwon1010.officecanvas.metafile.WmfRenderer
import io.github.baeseungwon1010.officecanvas.util.SafeBitmaps
import io.github.baeseungwon1010.officecanvas.util.readCapped
import java.io.File
import java.util.zip.ZipFile

/**
 * Draws a [Deck] to one [Bitmap] per slide.
 *
 * This is the payoff of the format's own design: every shape carries an absolute
 * position, so rendering is "draw what the file says," never "figure out where this
 * goes." The one thing PowerPoint does compute lazily is line breaking within a text
 * box, and [StaticLayout] does that for free — it is the same class Android TextViews
 * use, so wrapping matches on-device rather than needing a hand-rolled line breaker.
 */
internal object SlideRenderer {

    /** Set true while debugging a slide that renders blank; per-shape draw failures
     *  are otherwise swallowed on purpose so one bad shape doesn't blank a slide. */
    var DEBUG_LOG_DRAW_ERRORS = false

    /** Metric-compatible Office font stand-ins; loaded once from the library's assets by OfficeCanvas.
     *  Null falls back to the device's own fonts. */
    @Volatile
    var fonts: OfficeFonts? = null

    /** Test hook: when set, every drawn text line's (start of text, baseline y px) is appended. */
    var debugBaselines: MutableList<Pair<String, Float>>? = null

    /**
     * Renders every slide at [targetLongSidePx] on its longer edge, one at a time,
     * via [onSlide]. A deck's slides are rendered here, not collected into a
     * `List<Bitmap>` and handed back: a 40-slide lecture deck at 1600px would hold
     * on the order of 200MB of bitmaps alive simultaneously if it did, which is
     * exactly the shape of OOM the PDF import path already streams around one page
     * at a time — this keeps that same shape for pptx.
     */
    fun render(deck: Deck, pptxFile: File, targetLongSidePx: Int, onSlide: (index: Int, bitmap: Bitmap) -> Unit) {
        val scale = if (deck.aspect >= 1.0) {
            targetLongSidePx.toDouble() / deck.widthEmu
        } else {
            targetLongSidePx.toDouble() / deck.heightEmu
        }
        val pxW = (deck.widthEmu * scale).toInt().coerceAtLeast(1)
        val pxH = (deck.heightEmu * scale).toInt().coerceAtLeast(1)

        // Opened defensively rather than unconditionally: this renderer also draws
        // decks produced by PptParser from a legacy binary .ppt (an OLE2 file, not
        // a zip at all — see ppt/PptParser), which never has pictures to
        // look up here, so failing to open it as a zip should not fail the render.
        val zip = try { ZipFile(pptxFile) } catch (e: Exception) { null }
        try {
            deck.slides.forEachIndexed { index, slide ->
                onSlide(index, renderSlide(slide, zip, scale, pxW, pxH))
            }
        } finally {
            zip?.close()
        }
    }

    private fun renderSlide(slide: Slide, zip: ZipFile?, scale: Double, pxW: Int, pxH: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(slide.backgroundArgb ?: Color.WHITE)
            for (element in slide.elements) {
                try {
                    when (element) {
                        is Element.Shape -> drawShape(canvas, element, scale)
                        is Element.Picture -> drawPicture(canvas, element, zip, scale)
                        is Element.Table -> drawTable(canvas, element, scale)
                    }
                } catch (e: Exception) {
                    // One bad shape (a corrupt embedded image, say) should not blank the
                    // rest of a slide that is otherwise fine.
                    if (DEBUG_LOG_DRAW_ERRORS) e.printStackTrace()
                } catch (e: OutOfMemoryError) {
                    // Nor should one picture too large to decode.
                    if (DEBUG_LOG_DRAW_ERRORS) e.printStackTrace()
                } catch (e: StackOverflowError) {
                    if (DEBUG_LOG_DRAW_ERRORS) e.printStackTrace()
                }
            }
        } catch (e: Throwable) {
            bitmap.recycle()
            throw e
        }
        return bitmap
    }

    private fun Box.toRectF(scale: Double): RectF = RectF(
        (x * scale).toFloat(), (y * scale).toFloat(),
        ((x + w) * scale).toFloat(), ((y + h) * scale).toFloat()
    )

    /**
     * A shape turned in 3-D is drawn flat into its turned plane: its box's corners,
     * rotated by the camera and projected, map the whole drawing (fill, outline,
     * text) onto the plane. An extrusion is its silhouette swept back from the
     * shape to its depth, in the extrusion color, before the shape itself.
     */
    private fun drawShape(canvas: Canvas, shape: Element.Shape, scale: Double) {
        val s3 = shape.scene3d
        if (s3 == null) { drawFlatShape(canvas, shape, scale); return }
        val rect = shape.box.toRectF(scale)
        val filled = shape.fillArgb != null || shape.gradient != null
        if (s3.depthEmu > 0 && (filled || shape.strokeArgb != null) && rect.width() > 0f && rect.height() > 0f) {
            val depth = (s3.depthEmu * scale).toFloat()
            val color = s3.extrusionArgb ?: darker(shape.fillArgb ?: shape.gradient?.colors?.firstOrNull() ?: shape.strokeArgb!!)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
            // A step every couple of pixels of depth: the silhouettes overlap, so
            // that reads as a solid side, and a slide of extruded shapes stays quick.
            val steps = (depth / 2).toInt().coerceIn(2, 24)
            for (i in steps downTo 1) {
                canvas.save()
                canvas.concat(plane(rect, s3, -depth * i / steps))
                silhouette(canvas, shape, rect, paint)
                canvas.restore()
            }
        }
        canvas.save()
        canvas.concat(plane(rect, s3, 0f))
        drawFlatShape(canvas, shape, scale)
        canvas.restore()
    }

    /** The shape's outline (or geometry), turned and flipped as it is drawn. */
    private fun silhouette(canvas: Canvas, shape: Element.Shape, rect: RectF, paint: Paint) {
        canvas.withRotation(shape.rotation, rect) {
            canvas.save()
            if (shape.flipH || shape.flipV) canvas.scale(if (shape.flipH) -1f else 1f, if (shape.flipV) -1f else 1f, rect.centerX(), rect.centerY())
            val outline = shape.outline?.let { outlinePath(it, rect) }
            if (outline != null) canvas.drawPath(outline, paint) else drawGeometry(canvas, shape.geometry, rect, paint)
            canvas.restore()
        }
    }

    /**
     * The matrix that maps [rect] (on the slide) onto its plane turned by the
     * camera of [s] and moved [z] px behind it: the corners are rotated about the
     * box's center (in-plane first, then about the horizontal axis, then the
     * vertical one) and projected, with perspective for perspective cameras.
     */
    private fun plane(rect: RectF, s: Scene3d, z: Float): android.graphics.Matrix {
        val cx = rect.centerX(); val cy = rect.centerY()
        val rev = Math.toRadians(s.revDeg.toDouble()); val lat = Math.toRadians(s.latDeg.toDouble()); val lon = Math.toRadians(-s.lonDeg.toDouble())
        // Distance from the eye for a camera with about a 45-degree field of view.
        val eye = if (s.perspective) maxOf(rect.width(), rect.height()) * 2.4 else Double.POSITIVE_INFINITY
        val src = floatArrayOf(rect.left, rect.top, rect.right, rect.top, rect.right, rect.bottom, rect.left, rect.bottom)
        val dst = FloatArray(8)
        for (i in 0 until 4) {
            var x = (src[2 * i] - cx).toDouble(); var y = (src[2 * i + 1] - cy).toDouble(); var zz = z.toDouble()
            // In plane.
            val x0 = x * kotlin.math.cos(rev) - y * kotlin.math.sin(rev); val y0 = x * kotlin.math.sin(rev) + y * kotlin.math.cos(rev)
            x = x0; y = y0
            // About the horizontal axis (latitude), then the vertical one (longitude).
            val y1 = y * kotlin.math.cos(lat) - zz * kotlin.math.sin(lat); val z1 = y * kotlin.math.sin(lat) + zz * kotlin.math.cos(lat)
            y = y1; zz = z1
            val x2 = x * kotlin.math.cos(lon) + zz * kotlin.math.sin(lon); val z2 = -x * kotlin.math.sin(lon) + zz * kotlin.math.cos(lon)
            x = x2; zz = z2
            val k = if (eye.isInfinite()) 1.0 else eye / (eye - zz).coerceAtLeast(eye * 0.2)
            dst[2 * i] = (cx + x * k).toFloat(); dst[2 * i + 1] = (cy + y * k).toFloat()
        }
        return android.graphics.Matrix().apply { setPolyToPoly(src, 0, dst, 0, 4) }
    }

    private fun darker(argb: Int): Int {
        fun ch(shift: Int) = ((argb shr shift) and 0xFF) * 6 / 10
        return (argb and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun drawFlatShape(canvas: Canvas, shape: Element.Shape, scale: Double) {
        val rect = shape.box.toRectF(scale)
        if (shape.geometry == "line") {
            drawLine(canvas, shape, rect, scale)
            return
        }
        if (rect.width() <= 0f || rect.height() <= 0f) return

        val filled = shape.fillArgb != null || shape.gradient != null
        shape.shadow?.takeIf { filled || strokePaint(shape, scale) != null }?.let { s ->
            drawShadow(canvas, s, scale) { paint ->
                canvas.withRotation(shape.rotation, rect) {
                    canvas.save()
                    if (shape.flipH || shape.flipV) canvas.scale(if (shape.flipH) -1f else 1f, if (shape.flipV) -1f else 1f, rect.centerX(), rect.centerY())
                    // The silhouette: the filled outline, or just the line when there is no fill.
                    if (!filled) strokePaint(shape, scale)?.let { paint.style = Paint.Style.STROKE; paint.strokeWidth = it.strokeWidth }
                    val outline = shape.outline?.let { outlinePath(it, rect) }
                    if (outline != null) canvas.drawPath(outline, paint) else drawGeometry(canvas, shape.geometry, rect, paint)
                    canvas.restore()
                }
            }
        }

        canvas.withRotation(shape.rotation, rect) {
            canvas.save()
            // Flips mirror the outline (and its gradient) within the box; text is not mirrored.
            if (shape.flipH || shape.flipV) canvas.scale(if (shape.flipH) -1f else 1f, if (shape.flipV) -1f else 1f, rect.centerX(), rect.centerY())
            val outline = shape.outline?.let { outlinePath(it, rect) }
            fun draw(paint: Paint) = if (outline != null) canvas.drawPath(outline, paint) else drawGeometry(canvas, shape.geometry, rect, paint)
            when {
                shape.gradient != null && shape.gradient.rectangular && shape.geometry != "ellipse" -> {
                    canvas.save()
                    canvas.clipPath(outline ?: Path().apply { addRect(rect, Path.Direction.CW) })
                    drawRectangularGradient(canvas, shape.gradient, rect)
                    canvas.restore()
                }
                shape.gradient != null -> draw(Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.FILL; shader = gradientShader(shape.gradient, rect)
                })
                shape.fillArgb != null -> draw(Paint(Paint.ANTI_ALIAS_FLAG).apply { color = shape.fillArgb; style = Paint.Style.FILL })
            }
            strokePaint(shape, scale)?.let { paint ->
                draw(paint)
                if (shape.outline != null && (shape.headArrow || shape.tailArrow)) drawPathArrows(canvas, shape, rect, paint, scale)
            }
            canvas.restore()
        }
        shape.text?.let { drawText(canvas, it, rect, scale, shape.rotation) }
    }

    /**
     * An outer shadow: [silhouette] drawn with a paint in the shadow's color, moved
     * by its offset (which doesn't turn with the shape) and blurred. Drawn before
     * the shape itself, so the shape covers all but the offset part.
     */
    private fun drawShadow(canvas: Canvas, shadow: Shadow, scale: Double, silhouette: (Paint) -> Unit) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = shadow.argb
            style = Paint.Style.FILL
            // PowerPoint's blur radius spreads the edge over about twice a Gaussian sigma.
            val radius = (shadow.blurEmu * scale / 2).toFloat().coerceAtMost(200f)
            if (radius >= 0.5f) maskFilter = android.graphics.BlurMaskFilter(radius, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        canvas.save()
        canvas.translate((shadow.dxEmu * scale).toFloat(), (shadow.dyEmu * scale).toFloat())
        try { silhouette(paint) } finally { canvas.restore() }
    }

    private fun strokePaint(shape: Element.Shape, scale: Double): Paint? {
        val color = shape.strokeArgb ?: return null
        if (shape.strokeWidthEmu <= 0) return null
        val width = (shape.strokeWidthEmu * scale).toFloat().coerceAtLeast(1f)
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.STROKE
            strokeWidth = width
            if (shape.dashed) pathEffect = DashPathEffect(floatArrayOf(width * 4f, width * 3f), 0f)
        }
    }

    /** A line runs corner to corner of its box (which may have zero width or
     *  height); the flips choose which diagonal and which end is the start. */
    private fun drawLine(canvas: Canvas, shape: Element.Shape, rect: RectF, scale: Double) {
        val paint = strokePaint(shape, scale) ?: return
        val x1 = if (shape.flipH) rect.right else rect.left
        val x2 = if (shape.flipH) rect.left else rect.right
        val y1 = if (shape.flipV) rect.bottom else rect.top
        val y2 = if (shape.flipV) rect.top else rect.bottom
        canvas.withRotation(shape.rotation, rect) {
            canvas.drawLine(x1, y1, x2, y2, paint)
            val minHead = (6 * EMU_PER_POINT * scale).toFloat()
            if (shape.tailArrow) drawArrowhead(canvas, x1, y1, x2, y2, paint, minHead)
            if (shape.headArrow) drawArrowhead(canvas, x2, y2, x1, y1, paint, minHead)
        }
    }

    /** Arrowheads on an open outline (an elbow or curved connector): at the first
     *  point, pointing away from the second, and at the last, pointing along the
     *  final segment. */
    private fun drawPathArrows(canvas: Canvas, shape: Element.Shape, rect: RectF, paint: Paint, scale: Double) {
        val pts = mutableListOf<Pair<Float, Float>>()
        for (op in shape.outline.orEmpty()) when (op) {
            is PathOp.MoveTo -> pts += op.x to op.y
            is PathOp.LineTo -> pts += op.x to op.y
            is PathOp.CubicTo -> { pts += op.x1 to op.y1; pts += op.x2 to op.y2; pts += op.x to op.y }
            PathOp.Close -> {}
        }
        if (pts.size < 2) return
        fun px(p: Pair<Float, Float>) = (rect.left + p.first * rect.width()) to (rect.top + p.second * rect.height())
        fun distinctFrom(from: Int, step: Int): Pair<Float, Float>? {
            var i = from + step
            while (i in pts.indices) { if (pts[i] != pts[from]) return px(pts[i]); i += step }
            return null
        }
        val minHead = (6 * EMU_PER_POINT * scale).toFloat()
        if (shape.headArrow) distinctFrom(0, 1)?.let { (fx, fy) -> val (tx, ty) = px(pts.first()); drawArrowhead(canvas, fx, fy, tx, ty, paint, minHead) }
        if (shape.tailArrow) distinctFrom(pts.size - 1, -1)?.let { (fx, fy) -> val (tx, ty) = px(pts.last()); drawArrowhead(canvas, fx, fy, tx, ty, paint, minHead) }
    }

    /**
     * The common geometric bullets are drawn as shapes rather than glyphs: Office
     * draws them from symbol fonts at a generous size, while the device's fallback
     * glyphs for U+25CF and friends come out a fraction of that. Sized against
     * the bullet's own text size and centered on the text's x-height.
     */
    private fun drawBullet(canvas: Canvas, glyph: String, x: Float, baseline: Float, paint: TextPaint) {
        val em = paint.textSize
        val cy = baseline - em * 0.33f
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paint.color; style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paint.color; style = Paint.Style.STROKE; strokeWidth = em * 0.07f }
        fun square(side: Float, p: Paint) = canvas.drawRect(x + em * 0.05f, cy - side / 2, x + em * 0.05f + side, cy + side / 2, p)
        when (glyph) {
            "●", "⬤" -> canvas.drawCircle(x + em * 0.3f, cy, em * 0.27f, fill)
            "•" -> canvas.drawCircle(x + em * 0.22f, cy, em * 0.17f, fill)
            "○", "❍" -> canvas.drawCircle(x + em * 0.3f, cy, em * 0.25f, stroke)
            "■" -> square(em * 0.55f, fill)
            "▪" -> square(em * 0.36f, fill)
            "□", "◻", "❏", "❐", "❑", "❒" -> square(em * 0.5f, stroke)
            "◆", "♦", "⧫", "⬥", "⬧" -> canvas.drawPath(Path().apply {
                val r = em * 0.32f; val cx = x + r
                moveTo(cx, cy - r); lineTo(cx + r, cy); lineTo(cx, cy + r); lineTo(cx - r, cy); close()
            }, fill)
            "►", "▸", "▶" -> canvas.drawPath(Path().apply {
                val h = if (glyph == "▸") em * 0.36f else em * 0.5f
                moveTo(x + em * 0.05f, cy - h / 2); lineTo(x + em * 0.05f + h * 0.9f, cy); lineTo(x + em * 0.05f, cy + h / 2); close()
            }, fill)
            else -> canvas.drawText(glyph, x, baseline, paint)
        }
    }

    private fun outlinePath(ops: List<PathOp>, rect: RectF): Path {
        fun x(v: Float) = rect.left + v * rect.width()
        fun y(v: Float) = rect.top + v * rect.height()
        val path = Path()
        for (op in ops) when (op) {
            is PathOp.MoveTo -> path.moveTo(x(op.x), y(op.y))
            is PathOp.LineTo -> path.lineTo(x(op.x), y(op.y))
            is PathOp.CubicTo -> path.cubicTo(x(op.x1), y(op.y1), x(op.x2), y(op.y2), x(op.x), y(op.y))
            PathOp.Close -> path.close()
        }
        return path
    }

    private fun gradientShader(g: Gradient, rect: RectF): android.graphics.Shader {
        val colors = g.colors.toIntArray()
        val positions = g.positions.takeIf { it.size == colors.size }?.toFloatArray()
        if (g.radial) {
            val cx = rect.left + g.centerX * rect.width()
            val cy = rect.top + g.centerY * rect.height()
            val radius = Math.hypot(maxOf(cx - rect.left, rect.right - cx).toDouble(), maxOf(cy - rect.top, rect.bottom - cy).toDouble()).toFloat()
            return android.graphics.RadialGradient(cx, cy, radius.coerceAtLeast(1f), colors, positions, android.graphics.Shader.TileMode.CLAMP)
        }
        val rad = Math.toRadians(g.angleDeg.toDouble())
        val dx = Math.cos(rad).toFloat()
        val dy = Math.sin(rad).toFloat()
        val half = (Math.abs(rect.width() * dx) + Math.abs(rect.height() * dy)) / 2f
        return android.graphics.LinearGradient(
            rect.centerX() - dx * half, rect.centerY() - dy * half,
            rect.centerX() + dx * half, rect.centerY() + dy * half,
            colors, positions, android.graphics.Shader.TileMode.CLAMP
        )
    }

    /** Concentric-rectangle shading: four triangles from the focus point to each
     *  edge, each a linear gradient running from the focus out to that edge. */
    private fun drawRectangularGradient(canvas: Canvas, g: Gradient, rect: RectF) {
        val cx = rect.left + g.centerX * rect.width()
        val cy = rect.top + g.centerY * rect.height()
        val colors = g.colors.toIntArray()
        val positions = g.positions.takeIf { it.size == colors.size }?.toFloatArray()
        fun tri(x1: Float, y1: Float, x2: Float, y2: Float, ex: Float, ey: Float) {
            val path = Path().apply { moveTo(cx, cy); lineTo(x1, y1); lineTo(x2, y2); close() }
            // Not anti-aliased: the four triangles share edges, and AA would leave a faint seam along them.
            canvas.drawPath(path, Paint().apply {
                style = Paint.Style.FILL
                shader = android.graphics.LinearGradient(cx, cy, ex, ey, colors, positions, android.graphics.Shader.TileMode.CLAMP)
            })
        }
        tri(rect.left, rect.top, rect.right, rect.top, cx, rect.top)
        tri(rect.right, rect.top, rect.right, rect.bottom, rect.right, cy)
        tri(rect.right, rect.bottom, rect.left, rect.bottom, cx, rect.bottom)
        tri(rect.left, rect.bottom, rect.left, rect.top, rect.left, cy)
    }

    private fun drawArrowhead(canvas: Canvas, fromX: Float, fromY: Float, tipX: Float, tipY: Float, stroke: Paint, minLength: Float) {
        val angle = Math.atan2((tipY - fromY).toDouble(), (tipX - fromX).toDouble())
        val length = maxOf(stroke.strokeWidth * 3.5f, minLength)
        val spread = 0.4
        val path = Path().apply {
            moveTo(tipX, tipY)
            lineTo(tipX - (length * Math.cos(angle - spread)).toFloat(), tipY - (length * Math.sin(angle - spread)).toFloat())
            lineTo(tipX - (length * Math.cos(angle + spread)).toFloat(), tipY - (length * Math.sin(angle + spread)).toFloat())
            close()
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = stroke.color; style = Paint.Style.FILL })
    }

    /**
     * Preset geometry recognition is deliberately narrow: PowerPoint defines ~180
     * preset shapes (stars, arrows, callouts...), and drawing the long tail as its
     * exact outline is not worth it when a plain rectangle already conveys "there is a
     * shape here with this text in it." Ellipse and rounded-rect are common enough
     * (and different enough in silhouette) to be worth the extra branches.
     */
    private fun drawGeometry(canvas: Canvas, geometry: String?, rect: RectF, paint: Paint) {
        when (geometry) {
            "ellipse" -> canvas.drawOval(rect, paint)
            "roundRect" -> {
                val radius = minOf(rect.width(), rect.height()) * 0.12f
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
            else -> canvas.drawRect(rect, paint)
        }
    }

    private fun drawPicture(canvas: Canvas, picture: Element.Picture, zip: ZipFile?, scale: Double) {
        val box = picture.box.toRectF(scale)
        if (box.width() <= 0f || box.height() <= 0f) return
        // A cropped picture is drawn at the size its *uncropped* source would have,
        // shifted so the kept part lands in the box, and clipped to the box.
        val rect = picture.crop?.let { c ->
            val keptW = 1f - c.left - c.right
            val keptH = 1f - c.top - c.bottom
            if (keptW <= 0.01f || keptH <= 0.01f) return
            val fullW = box.width() / keptW
            val fullH = box.height() / keptH
            RectF(box.left - c.left * fullW, box.top - c.top * fullH, box.left - c.left * fullW + fullW, box.top - c.top * fullH + fullH)
        } ?: box
        picture.shadow?.let { s ->
            drawShadow(canvas, s, scale) { paint -> canvas.withRotation(picture.rotation, box) { canvas.drawRect(box, paint) } }
        }
        canvas.save()
        picture.scene3d?.let { canvas.concat(plane(box, it, 0f)) }
        if (picture.rotation != 0f) canvas.rotate(picture.rotation, box.centerX(), box.centerY())
        if (picture.crop != null) canvas.clipRect(box)
        try {
            drawPictureContent(canvas, picture, zip, rect)
        } finally {
            canvas.restore()
        }
    }

    private fun drawPictureContent(canvas: Canvas, picture: Element.Picture, zip: ZipFile?, rect: RectF) {
        // Sources: extracted bytes (.ppt, from its blip store), or a zip entry
        // (.pptx). Vector formats go to their players; the rest is raster. A zip
        // entry's format is told from its bytes, not its name: decks do carry
        // WMF files named .emf.
        picture.emfBytes?.let { EmfRenderer.draw(canvas, it, rect); return }
        picture.wmfBytes?.let { WmfRenderer.draw(canvas, it, rect); return }
        val bytes = picture.imageBytes ?: picture.imageEntry?.let { entryName ->
            zip?.getEntry(entryName)?.let { entry -> zip.readCapped(entry, SafeBitmaps.MAX_IMAGE_BYTES) }
        } ?: return
        when {
            isEmf(bytes) -> { EmfRenderer.draw(canvas, bytes, rect); return }
            isPlaceableWmf(bytes) -> { WmfRenderer.draw(canvas, bytes, rect); return }
            isRawWmf(bytes) -> { WmfRenderer.fromRaw(bytes)?.let { WmfRenderer.draw(canvas, it, rect) }; return }
            isSvg(bytes) -> { SvgRenderer.draw(canvas, bytes, rect); return }
        }
        val bitmap = SafeBitmaps.decode(bytes, rect.width(), rect.height()) ?: return
        try {
            canvas.drawBitmap(bitmap, null, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        } finally {
            bitmap.recycle()
        }
    }

    private fun le32(b: ByteArray, o: Int) = if (b.size < o + 4) 0 else
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    private fun isEmf(b: ByteArray) = le32(b, 0) == 1 && le32(b, 40) == 0x464D4520
    private fun isPlaceableWmf(b: ByteArray) = le32(b, 0) == 0x9AC6CDD7.toInt()
    private fun isRawWmf(b: ByteArray) = b.size > 18 && (b[0].toInt() == 1 || b[0].toInt() == 2) && b[1].toInt() == 0 && b[2].toInt() == 9 && b[3].toInt() == 0
    /** An XML document whose root is <svg> (raster files can carry "<svg" in metadata). */
    private fun isSvg(b: ByteArray): Boolean {
        val head = String(b, 0, minOf(b.size, 4096), Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (!head.startsWith("<")) return false
        val root = Regex("<([A-Za-z][\\w:.-]*)").findAll(head).map { it.groupValues[1] }.firstOrNull { !it.startsWith("!") }
        return root?.substringAfter(':') == "svg"
    }

    private fun drawTable(canvas: Canvas, table: Element.Table, scale: Double) {
        val totalW = table.columnWidthsEmu.sum().coerceAtLeast(1)
        val colXEmu = table.columnWidthsEmu.runningFold(0L) { acc, w -> acc + w }
        val totalH = table.rowHeightsEmu.sum().coerceAtLeast(1)

        // Column widths are relative to the table's own box, not absolute slide
        // coordinates, so they are rescaled to fill the box exactly regardless of
        // rounding drift between the declared grid and the frame's own extent.
        val wScale = table.box.w.toDouble() / totalW
        // Row heights are minimums: a frame saved shorter than they add up to
        // doesn't squeeze them (a taller one stretches them, as PowerPoint saved it).
        val hScale = (table.box.h.toDouble() / totalH).coerceAtLeast(1.0)
        fun colX(i: Int) = table.box.x + (colXEmu[i] * wScale).toLong()
        // A row's height is a minimum: PowerPoint grows it to fit its cells' text.
        val rowPx = table.rowHeightsEmu.map { (it * hScale * scale).toFloat() }.toMutableList()
        for (r in table.cells.indices) for (c in table.cells[r].indices) {
            val cell = table.cells[r][c] ?: continue
            if (cell.rowSpan != 1) continue
            // An empty cell keeps the row's own height.
            val text = cell.text?.takeIf { t -> t.paragraphs.any { !it.isEmpty } } ?: continue
            val w = ((colX((c + cell.colSpan).coerceAtMost(table.columnWidthsEmu.size)) - colX(c)) * scale).toFloat()
            rowPx[r] = maxOf(rowPx[r], textHeight(text, w, scale))
        }
        val rowTop = rowPx.runningFold((table.box.y * scale).toFloat()) { acc, h -> acc + h }
        fun rowY(i: Int) = (rowTop[i] / scale).toLong()

        val gridPaint = table.gridArgb?.let { argb ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb; style = Paint.Style.STROKE; strokeWidth = 1.5f }
        }
        for (r in table.cells.indices) {
            for (c in table.cells[r].indices) {
                val cell = table.cells[r][c] ?: continue
                val cellBox = Box(
                    colX(c), rowY(r),
                    colX((c + cell.colSpan).coerceAtMost(table.columnWidthsEmu.size)) - colX(c),
                    rowY((r + cell.rowSpan).coerceAtMost(table.rowHeightsEmu.size)) - rowY(r)
                )
                val rect = cellBox.toRectF(scale)
                if (cell.fillArgb != null) {
                    canvas.drawRect(rect, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cell.fillArgb; style = Paint.Style.FILL })
                }
                gridPaint?.let { canvas.drawRect(rect, it) }
                cell.text?.let { drawText(canvas, it, rect, scale, 0f) }
            }
        }
    }

    /** How tall [body] sets in a box [widthPx] wide, insets included. */
    private fun textHeight(body: TextBody, widthPx: Float, scale: Double): Float {
        val width = (widthPx - ((body.insetLeftEmu + body.insetRightEmu) * scale).toFloat()).toInt()
        if (width <= 1) return 0f
        val text = body.paragraphs.mapIndexed { i, p -> layParagraph(p, i == 0, width, scale, 1f) }
            .sumOf { (it.before + it.layout.height + it.after).toDouble() }.toFloat()
        return text + ((body.insetTopEmu + body.insetBottomEmu) * scale).toFloat()
    }

    private fun drawText(canvas: Canvas, body: TextBody, box: RectF, scale: Double, rotation: Float) {
        var innerLeft = box.left + (body.insetLeftEmu * scale).toFloat()
        val innerTop = box.top + (body.insetTopEmu * scale).toFloat()
        val innerRight = box.right - (body.insetRightEmu * scale).toFloat()
        val innerBottom = box.bottom - (body.insetBottomEmu * scale).toFloat()
        var width = (innerRight - innerLeft).toInt().coerceAtLeast(1)
        if (width <= 1 && body.wrap) return
        if (!body.wrap) {
            // Unwrapped text runs past its box; lay it out as wide as its longest
            // line and keep that line where the alignment puts it.
            val longest = body.paragraphs.mapIndexed { i, p -> layParagraph(p, i == 0, 100_000, scale, 1f) }
                .maxOfOrNull { lp -> (0 until lp.layout.lineCount).maxOfOrNull { lp.x + lp.layout.getLineWidth(it) } ?: 0f } ?: 0f
            if (longest > width) {
                val extra = kotlin.math.ceil(longest).toInt() + 1 - width
                innerLeft -= when (body.paragraphs.firstOrNull()?.align) {
                    HAlign.CENTER -> extra / 2f
                    HAlign.RIGHT -> extra.toFloat()
                    else -> 0f
                }
                width += extra
            }
        }

        // Auto-fit shrinks the whole box's text by one shared factor to make it fit,
        // rather than each paragraph wrapping independently — so this measures once
        // at full size, then re-lays-out smaller if needed, matching what PowerPoint
        // actually shows instead of clipped overflow text.
        val availableHeight = (innerBottom - innerTop).coerceAtLeast(1f)
        var laid: List<LaidParagraph>
        var totalHeight: Float
        var shrink = 1f
        do {
            laid = body.paragraphs.mapIndexed { i, p -> layParagraph(p, i == 0, width, scale, shrink) }
            totalHeight = laid.sumOf { (it.before + it.layout.height + it.after).toDouble() }.toFloat()
            // Trailing blank paragraphs (often left to make room for a picture) don't
            // count toward overflow, so they can't force the real text to shrink.
            val lastText = body.paragraphs.indexOfLast { !it.isEmpty }
            val fitHeight = laid.take(lastText + 1).sumOf { (it.before + it.layout.height + it.after).toDouble() }.toFloat()
            // Small steps: overflowing by a line shouldn't cost 15% of the text size.
            // A pixel or so of overflow is rounding, not overflow (a one-line title at
            // exactly 1.2 x its size lands a hair past its box in PowerPoint too).
            if (body.autoFit && fitHeight > availableHeight + 1.5f && shrink - 0.05f >= body.autoFitFloor) shrink -= 0.05f else break
        } while (true)

        var y = innerTop + when (body.anchor) {
            VAnchor.TOP -> 0f
            // Text taller than its box overflows both ways when centered, upward
            // when anchored at the bottom, as PowerPoint draws it.
            VAnchor.MIDDLE -> (availableHeight - totalHeight) / 2f
            VAnchor.BOTTOM -> availableHeight - totalHeight
        }

        canvas.withRotation(rotation, box) {
            for (p in laid) {
                y += p.before
                canvas.save()
                canvas.translate(innerLeft + p.x, y)
                p.layout.draw(canvas)
                debugBaselines?.let { out ->
                    for (l in 0 until p.layout.lineCount) {
                        val t = p.layout.text.subSequence(p.layout.getLineStart(l), p.layout.getLineEnd(l)).toString().trim()
                        if (t.isNotEmpty()) out += t to (y + p.layout.getLineBaseline(l))
                    }
                }
                p.bullet?.let { (glyph, paint, bx) ->
                    if (p.layout.lineCount > 0) drawBullet(canvas, glyph, bx - p.x, p.layout.getLineBaseline(0).toFloat(), paint)
                }
                canvas.restore()
                y += p.layout.height + p.after
            }
        }
    }

    /** A laid-out paragraph: [x] is its offset from the inner left edge, and a
     *  separately drawn [bullet] sits at its own x (hanging indent). */
    private class LaidParagraph(
        val layout: StaticLayout,
        val x: Float,
        val before: Float,
        val after: Float,
        val bullet: Triple<String, TextPaint, Float>?
    )

    private fun layParagraph(p: Paragraph, first: Boolean, width: Int, scale: Double, shrink: Float): LaidParagraph {
        val sizePt = p.runs.firstOrNull()?.sizePt ?: 18f
        val before = if (first) 0f else ptToPx(p.spaceBeforePt, scale, shrink)
        val after = ptToPx(p.spaceAfterPt, scale, shrink)

        if (p.marginLeftEmu == null) {
            // Level-based indentation with the bullet inline (what .pptx supplies).
            val indent = LEVEL_INDENT_EMU * scale.toFloat() * (p.level + if (p.bullet != null) 1 else 0)
            val w = (width - indent).toInt().coerceAtLeast(1)
            return LaidParagraph(buildLayout(p, w, scale, shrink, inlineBullet = true, firstLineIndent = 0f), indent, before, after, null)
        }

        // Explicit margins (what .ppt supplies): the bullet hangs at the first-line
        // position and wrapped lines start at the left margin.
        val left = (p.marginLeftEmu * scale).toFloat().coerceIn(0f, width - 1f)
        val firstLine = ((p.bulletIndentEmu ?: p.marginLeftEmu) * scale).toFloat().coerceIn(0f, width - 1f)
        if (p.bullet != null) {
            val firstRun = p.runs.firstOrNull()
            val paint = TextPaint().apply {
                isAntiAlias = true
                // A number bullet ("1.") is set in the paragraph's own text face and weight.
                val numbered = p.bullet.any { it.isLetterOrDigit() }
                val kind = if (numbered) OfficeFonts.kindFor(firstRun?.fontFamily) else OfficeFonts.Kind.SANS
                (kind?.let { fonts?.typeface(it, numbered && firstRun?.bold == true, false) })?.let { typeface = it }
                if (numbered && firstRun?.bold == true && fonts == null) isFakeBoldText = true
                color = p.bulletColorArgb ?: firstRun?.colorArgb ?: Color.BLACK
                textSize = ptToPx(sizePt * p.bulletSizePercent / 100f, scale, shrink).coerceAtLeast(1f)
            }
            val textFirst = maxOf(left, firstLine + paint.measureText(p.bullet + " "))
            val base = minOf(left, firstLine)
            val layout = buildLayout(p, (width - base).toInt().coerceAtLeast(1), scale, shrink,
                inlineBullet = false, firstLineIndent = textFirst - base, restIndent = left - base, layoutOriginPx = base)
            return LaidParagraph(layout, base, before, after, Triple(p.bullet, paint, firstLine))
        }
        val base = minOf(left, firstLine)
        val layout = buildLayout(p, (width - base).toInt().coerceAtLeast(1), scale, shrink,
            inlineBullet = false, firstLineIndent = firstLine - base, restIndent = left - base, layoutOriginPx = base)
        return LaidParagraph(layout, base, before, after, null)
    }

    /** Point sizes are stored in the file; canvas pixels come from EMU * [scale], and a
     *  point is exactly 12700 EMU, so this is the one place font-size unit conversion
     *  happens — everything else in this file already works in pixels. */
    private fun ptToPx(pt: Float, scale: Double, shrink: Float): Float =
        (pt.coerceAtMost(MAX_FONT_PT) * EMU_PER_POINT * scale * shrink).toFloat()

    /** Office's own maximum font size. */
    private const val MAX_FONT_PT = 4000f
    private const val MAX_TAB_STOPS = 256

    private fun buildLayout(
        p: Paragraph, width: Int, scale: Double, shrink: Float,
        inlineBullet: Boolean, firstLineIndent: Float, restIndent: Float = 0f, layoutOriginPx: Float = 0f
    ): StaticLayout {
        val defaultSizePt = p.runs.firstOrNull()?.sizePt ?: 18f
        val spannable = android.text.SpannableStringBuilder()

        if (inlineBullet && p.bullet != null) {
            val start = spannable.length
            spannable.append("${p.bullet} ")
            val sizePx = ptToPx(defaultSizePt, scale, shrink).coerceAtLeast(1f)
            spannable.setSpan(SizeSpan(sizePx), start, spannable.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val runSizes = mutableListOf<Triple<Int, Int, Float>>()
        for (run in p.runs) {
            val start = spannable.length
            // run.text == "\n" marks an explicit <a:br>. StaticLayout honors a literal
            // newline as a forced break within a single layout, so it passes straight
            // through rather than needing a separate line-separator trick.
            spannable.append(run.text)
            runSizes += Triple(start, spannable.length, run.sizePt)
            val end = spannable.length
            val sizePx = ptToPx(run.sizePt, scale, shrink).coerceAtLeast(1f)
            spannable.setSpan(SizeSpan(sizePx), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(android.text.style.ForegroundColorSpan(run.colorArgb), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            run.highlightArgb?.let { spannable.setSpan(android.text.style.BackgroundColorSpan(it), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE) }
            val kind = fonts?.let { OfficeFonts.kindFor(run.fontFamily) }
            val face = kind?.let { fonts?.typeface(it, run.bold, run.italic) }
            // A loaded bold/italic font file already is the style; StyleSpan is only
            // needed (as synthesized bold/slant) when there is no such file.
            val style = when {
                face != null -> if (run.italic && (kind == OfficeFonts.Kind.MONO || kind == OfficeFonts.Kind.CALIBRI)) Typeface.ITALIC else null
                run.bold && run.italic -> Typeface.BOLD_ITALIC
                run.bold -> Typeface.BOLD
                run.italic -> Typeface.ITALIC
                else -> null
            }
            if (face != null) {
                spannable.setSpan(OfficeFonts.Span(face), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (style != null) {
                spannable.setSpan(android.text.style.StyleSpan(style), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (run.underline) {
                spannable.setSpan(android.text.style.UnderlineSpan(), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (face == null) familyFor(run.fontFamily)?.let {
                spannable.setSpan(android.text.style.TypefaceSpan(it), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (run.baselinePercent != 0f) {
                spannable.setSpan(android.text.style.RelativeSizeSpan(0.67f), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                spannable.setSpan(BaselineShiftSpan(run.baselinePercent), start, end, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        // Tab stops are measured from the box's inner edge; Android measures them
        // from where the line's text starts, so shift by this layout's first-line start.
        if (p.runs.any { '\t' in it.text }) {
            val origin = layoutOriginPx + firstLineIndent
            // A hanging indent's left margin is itself a tab stop (as in PowerPoint and Word):
            // the first tab on the first line lands there before any explicit stop.
            val hanging = listOfNotNull(p.marginLeftEmu).map { it * scale }
            val stops = (hanging + p.tabStopsEmu.map { it * scale }).map { it.toFloat() - origin }
                .filter { it > 0.5f }.distinct().sorted().toMutableList()
            val step = (p.defaultTabEmu * scale).toFloat().coerceAtLeast(4f)
            var next = ((stops.lastOrNull()?.plus(origin) ?: 0f) / step).toInt() * step + step - origin
            // Enough implicit stops to cross the line, but never thousands of spans.
            while (next < width * 2 && stops.size < MAX_TAB_STOPS) { if (next > 0f) stops += next; next += step }
            for (stop in stops) {
                spannable.setSpan(android.text.style.TabStopSpan.Standard(stop.toInt()), 0, spannable.length, android.text.Spannable.SPAN_INCLUSIVE_INCLUSIVE)
            }
        }
        // An empty paragraph still occupies one full line; a zero-length text would
        // leave the spans below nothing to apply to (and the line its bare font height).
        if (spannable.isEmpty()) {
            spannable.append(" ")
            spannable.setSpan(SizeSpan(ptToPx(defaultSizePt, scale, shrink).coerceAtLeast(1f)), 0, 1, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // PowerPoint (and LibreOffice reading the same file) makes every line,
        // a paragraph's first included, exactly 1.2 x font size x line spacing
        // tall, with any extra or shortfall taken above the text. Measured against
        // LibreOffice's PDF: 14pt at 90% -> 15.12pt pitch, 18pt at 100% -> 21.6pt.
        // StaticLayout instead uses the font's own height (1.117 em for these faces)
        // and applies the multiplier only between lines.
        // Each line is sized by the largest text actually on it (a run of blank line
        // breaks set in a smaller size makes shorter blank lines, as in 5.19).
        val sizes = FloatArray(spannable.length) { defaultSizePt }
        for ((a, b, size) in runSizes) for (i in a until minOf(b, sizes.size)) sizes[i] = size
        val pitch = ptToPx(1.2f * p.lineSpacingPercent / 100f, scale, shrink)
        val linePx = pitch * (p.runs.filter { it.text.isNotEmpty() }.maxOfOrNull { it.sizePt } ?: defaultSizePt)
        if (linePx > 0f) {
            val mainRun = p.runs.filter { it.text.isNotEmpty() }.maxByOrNull { it.sizePt }
            val descentPx = TextPaint().apply {
                textSize = ptToPx(mainRun?.sizePt ?: defaultSizePt, scale, shrink)
                (OfficeFonts.kindFor(mainRun?.fontFamily)?.let { fonts?.typeface(it, false, false) })?.let { typeface = it }
            }.fontMetrics.descent
            spannable.setSpan(ExactLineHeight(sizes, pitch, descentPx / linePx * pitch), 0, spannable.length, android.text.Spannable.SPAN_INCLUSIVE_INCLUSIVE)
        }
        if (firstLineIndent > 0f || restIndent > 0f) {
            spannable.setSpan(
                android.text.style.LeadingMarginSpan.Standard(firstLineIndent.toInt(), restIndent.toInt()),
                0, spannable.length, android.text.Spannable.SPAN_INCLUSIVE_INCLUSIVE
            )
        }

        val basePaint = TextPaint().apply {
            isAntiAlias = true
            // Unhinted, fractional advances: hinted ones round every glyph to whole
            // pixels, which at slide scale added up to lines ~4% longer than the same
            // font in PowerPoint/LibreOffice and wrapped words that should have fit.
            isLinearText = true
            isSubpixelText = true
            textSize = ptToPx(defaultSizePt, scale, shrink).coerceAtLeast(1f)
            fonts?.typeface(OfficeFonts.Kind.SANS, false, false)?.let { typeface = it }
        }
        val align = when (p.align) {
            HAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            HAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        return StaticLayout.Builder.obtain(spannable, 0, spannable.length, basePaint, width)
            .setAlignment(align)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()
    }

    /** The device has none of the deck's fonts, so each is mapped to the closest
     *  family Android always ships: serif, monospace, condensed, casual, or cursive
     *  (null = the default sans-serif). */
    private fun familyFor(family: String?): String? {
        val f = family?.lowercase() ?: return null
        return when {
            MONO_HINTS.any { it in f } -> "monospace"
            SERIF_HINTS.any { it in f } -> "serif"
            "narrow" in f || "condensed" in f || "impact" in f -> "sans-serif-condensed"
            "comic" in f -> "casual"
            "script" in f || "brush" in f || "hand" in f || "segoe print" in f -> "cursive"
            else -> null
        }
    }

    private val MONO_HINTS = listOf("courier", "consolas", "lucida console", "mono", "fixedsys", "terminal", "d2coding", "굴림체", "바탕체", "돋움체", "gulimche", "dotumche")

    /** Line heights must be whole pixels; the fraction left over from each line is
     *  carried into the next, so a paragraph is as tall as its exact line pitch
     *  says (always rounding up added ~4% at slide scale and tipped boxes into
     *  auto-fit shrinking). */
    /** [sizes]: point size of every character; [pitch]: px of line height per point;
     *  [descentPerPt]: the paragraph font's descent in px per point. */
    private class ExactLineHeight(
        private val sizes: FloatArray,
        private val pitch: Float,
        private val descentPerPt: Float
    ) : android.text.style.LineHeightSpan {
        private var carry = 0f
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
            if (fm.descent - fm.ascent <= 0) return
            var size = 0f
            for (i in start until minOf(end, sizes.size)) if (sizes[i] > size) size = sizes[i]
            if (size <= 0f) size = sizes.lastOrNull() ?: return
            val px = size * pitch
            val descentPx = size * descentPerPt
            val whole = Math.round(px + carry).coerceAtLeast(1)
            carry += px - whole
            // The baseline sits where the paragraph's own font puts it; anything that
            // reaches below that font's descent (a lowered subscript) adds to the line
            // instead of being absorbed — LibreOffice grows "P₁, P₂" lines by exactly
            // that much (3.2pt at 18pt).
            val base = Math.round(descentPx)
            fm.ascent = -(whole - base)
            fm.descent = maxOf(fm.descent, base)
            fm.top = fm.ascent
            fm.bottom = fm.descent
        }
    }

    /** An exact (fractional) text size; AbsoluteSizeSpan only takes whole pixels. */
    private class SizeSpan(private val px: Float) : android.text.style.MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) { tp.textSize = px }
        override fun updateMeasureState(tp: TextPaint) { tp.textSize = px }
    }

    /** Raises (or lowers) a run by [percent] of its line height — PowerPoint's
     *  superscript/subscript offset. */
    private class BaselineShiftSpan(private val percent: Float) : android.text.style.MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) = shift(tp)
        // Measured too, so a lowered subscript deepens its line (see ExactLineHeight).
        override fun updateMeasureState(tp: TextPaint) = shift(tp)
        private fun shift(tp: TextPaint) {
            tp.baselineShift -= (tp.textSize / 0.67f * 1.2f * percent / 100f).toInt()
        }
    }

    private val SERIF_HINTS = listOf("times", "georgia", "garamond", "cambria", "serif", "batang", "바탕", "myeongjo", "명조", "mincho")

    private const val LEVEL_INDENT_EMU = 274638f // ~0.3in per level, PowerPoint's rough default

    private inline fun Canvas.withRotation(degrees: Float, rect: RectF, block: () -> Unit) {
        if (degrees == 0f) { block(); return }
        save()
        rotate(degrees, rect.centerX(), rect.centerY())
        block()
        restore()
    }
}
