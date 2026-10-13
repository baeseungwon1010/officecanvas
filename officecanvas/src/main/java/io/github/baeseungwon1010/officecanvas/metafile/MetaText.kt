package io.github.baeseungwon1010.officecanvas.metafile

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import io.github.baeseungwon1010.officecanvas.ppt.fixSymbolText
import io.github.baeseungwon1010.officecanvas.slide.OfficeFonts
import io.github.baeseungwon1010.officecanvas.slide.SlideRenderer

/** Text drawing shared by the EMF and WMF players. */
internal object MetaText {

    /** The face to draw a metafile font with: the bundled metric-compatible
     *  Office stand-in when there is one, else the system serif/sans. */
    fun typeface(face: String, bold: Boolean, italic: Boolean): Typeface {
        val kind = OfficeFonts.kindFor(face)
        kind?.let { SlideRenderer.fonts?.typeface(it, bold, italic) }?.let { return it }
        val serif = face.lowercase().let { "times" in it || ("serif" in it && "sans" !in it) || "symbol" in it }
        return Typeface.create(if (serif) Typeface.SERIF else Typeface.SANS_SERIF, when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        })
    }

    /** Symbol/Wingdings text in a metafile (equations use Symbol for Greek and
     *  operators) is stored as that font's own codes. */
    fun displayText(text: String, face: String): String = fixSymbolText(text, face)

    /**
     * Draws [text] starting at ([x], [y]) on the baseline. When the record
     * carries per-character advances ([dx], already in device pixels) each
     * character is placed at its own position — equations and diagram labels
     * rely on that spacing, and drawing the string as one run with the device
     * font's own advances jams or spreads words.
     */
    fun draw(canvas: Canvas, text: String, x: Float, y: Float, dx: FloatArray?, paint: Paint) {
        if (dx == null || dx.size < text.length) {
            canvas.drawText(text, x, y, paint)
            return
        }
        val total = dx.take(text.length).sum()
        var cx = when (paint.textAlign) {
            Paint.Align.CENTER -> x - total / 2
            Paint.Align.RIGHT -> x - total
            else -> x
        }
        val align = paint.textAlign
        paint.textAlign = Paint.Align.LEFT
        for (i in text.indices) {
            canvas.drawText(text, i, i + 1, cx, y, paint)
            cx += dx[i]
        }
        paint.textAlign = align
    }
}
