package io.github.baeseungwon1010.officecanvas.hwpx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
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
     *  once. */
    fun render(doc: HwpxDoc, hwpxFile: File, targetLongSidePx: Int, onPage: (index: Int, bitmap: Bitmap) -> Unit) {
        ZipFile(hwpxFile).use { zip ->
            doc.pages.forEachIndexed { index, page ->
                val scale = if (page.widthHu >= page.heightHu) {
                    targetLongSidePx.toDouble() / page.widthHu
                } else {
                    targetLongSidePx.toDouble() / page.heightHu
                }
                val pxW = (page.widthHu * scale).toInt().coerceAtLeast(1)
                val pxH = (page.heightHu * scale).toInt().coerceAtLeast(1)
                onPage(index, renderPage(page, zip, scale, pxW, pxH))
            }
        }
    }

    private fun renderPage(page: HwpxPage, zip: ZipFile, scale: Double, pxW: Int, pxH: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.translate((page.bodyXHu * scale).toFloat(), (page.bodyYHu * scale).toFloat())
        for (el in page.elements) {
            try {
                when (el) {
                    is HwpxElement.TextRun -> drawText(canvas, el, 0.0, 0.0, scale)
                    is HwpxElement.Table -> drawTable(canvas, el, scale)
                    is HwpxElement.ImageEl -> drawImage(canvas, el, zip, scale)
                }
            } catch (e: Exception) {
                if (DEBUG_LOG_DRAW_ERRORS) e.printStackTrace()
            }
        }
        return bitmap
    }

    private fun drawText(canvas: Canvas, run: HwpxElement.TextRun, offsetXHu: Double, offsetYHu: Double, scale: Double) {
        val x = ((run.xHu + offsetXHu) * scale).toFloat()
        // HWPUNIT vertpos marks the top of the line; Canvas.drawText's y is the
        // text baseline, so this needs to drop down by roughly the font's ascent
        // (~80% of its size reads close enough for a converter, not exact metrics).
        val sizePx = (run.sizePt * HWPUNIT_PER_INCH / 72.0 * scale).toFloat()
        val y = ((run.yHu + offsetYHu) * scale).toFloat() + sizePx * 0.8f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sizePx.coerceAtLeast(1f)
            color = run.colorArgb
            isFakeBoldText = run.bold
            isUnderlineText = run.underline
            textSkewX = if (run.italic) -0.25f else 0f
        }
        canvas.drawText(run.text, x, y, paint)
    }

    private fun drawTable(canvas: Canvas, table: HwpxElement.Table, scale: Double) {
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
        for (r in table.cells.indices) {
            for (c in table.cells[r].indices) {
                val cell = table.cells[r][c] ?: continue
                val left = colX(c); val top = rowY(r)
                val right = colX((c + cell.colSpan).coerceAtMost(table.columnWidthsHu.size))
                val bottom = rowY((r + cell.rowSpan).coerceAtMost(table.rowHeightsHu.size))
                val rect = RectF((left * scale).toFloat(), (top * scale).toFloat(), (right * scale).toFloat(), (bottom * scale).toFloat())
                canvas.drawRect(rect, gridPaint)
                // Cell text carries positions relative to the cell's own top-left
                // corner (see HwpxParser.parseTable), so the cell's own origin is
                // added back in here rather than at parse time.
                for (run in cell.runs) drawText(canvas, run, left.toDouble(), top.toDouble(), scale)
            }
        }
    }

    private fun drawImage(canvas: Canvas, image: HwpxElement.ImageEl, zip: ZipFile, scale: Double) {
        // BinData/<id>.<ext>: the reference in the XML omits the extension, so the
        // matching zip entry is found by prefix rather than assumed.
        val entry = zip.entries().asSequence().firstOrNull {
            it.name.startsWith("BinData/${image.zipEntry}") || it.name.startsWith("BinData/${image.zipEntry}.")
        } ?: return
        val bitmap = zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) } ?: return
        val rect = RectF(
            (image.xHu * scale).toFloat(), (image.yHu * scale).toFloat(),
            ((image.xHu + image.widthHu) * scale).toFloat(), ((image.yHu + image.heightHu) * scale).toFloat()
        )
        canvas.drawBitmap(bitmap, null, rect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        bitmap.recycle()
    }
}
