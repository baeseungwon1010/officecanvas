package io.github.baeseungwon1010.officecanvas.hwpx

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import io.github.baeseungwon1010.officecanvas.slide.OfficeFonts
import io.github.baeseungwon1010.officecanvas.slide.SlideRenderer
import kotlin.math.max

/**
 * Typesets an [EqNode] tree the way Hangul's equation editor sets it, close
 * enough to fill the box Hangul saved: variables in italic serif, numbers and
 * symbols upright, scripts at 70%, fractions centered on the math axis, and
 * delimiters stretched to what they enclose.
 *
 * Every box is measured in pixels at a given size; [draw] puts a laid-out box's
 * baseline at a point.
 */
internal class EquationLayout(private val colorArgb: Int) {

    /** A laid-out piece: its advance, the room above and below its baseline, and
     *  how to draw it with its baseline at (x, y). */
    class Box(val width: Float, val ascent: Float, val descent: Float, val draw: (Canvas, Float, Float) -> Unit) {
        val height get() = ascent + descent
    }

    private fun paint(size: Float, italic: Boolean, bold: Boolean): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = size.coerceIn(0.5f, 4000f)
            color = colorArgb
            typeface = SlideRenderer.fonts?.typeface(OfficeFonts.Kind.SERIF, bold, italic)
                ?: Typeface.create(Typeface.SERIF, (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0))
        }

    fun layout(node: EqNode, size: Float, depth: Int = 0): Box {
        if (depth > 40) return empty()
        return when (node) {
            is EqNode.Text -> text(node.text, size, node.italic, node.bold)
            is EqNode.Space -> Box(node.em * size, 0f, 0f) { _, _, _ -> }
            is EqNode.Row -> row(node.items.map { layout(it, size, depth + 1) })
            is EqNode.Scripts -> scripts(node, size, depth)
            is EqNode.Fraction -> fraction(node, size, depth)
            is EqNode.Root -> root(node, size, depth)
            is EqNode.BigOp -> bigOp(node, size, depth)
            is EqNode.Fenced -> fenced(node.left, layout(node.body, size, depth + 1), node.right, size)
            is EqNode.Matrix -> matrix(node, size, depth)
            is EqNode.Accent -> accent(node, size, depth)
        }
    }

    private fun empty() = Box(0f, 0f, 0f) { _, _, _ -> }

    private fun text(s: String, size: Float, italic: Boolean, bold: Boolean): Box {
        if (s.isEmpty()) return empty()
        val p = paint(size, italic, bold)
        // Relations and binary operators get a little room on each side.
        val pad = if (s.length == 1 && s[0] in "=<>≤≥≠≈≡∼≃≅≪≫∈∉⊂⊃⊆⊇→←⇒⇐⇔↔+−±∓×÷·∗") size * 0.12f else 0f
        val w = p.measureText(s)
        // An italic letter leans out past its advance; leave it that room.
        val lean = if (italic) size * 0.06f else 0f
        return Box(w + 2 * pad + lean, size * 0.72f, size * 0.22f) { c, x, y -> c.drawText(s, x + pad, y, p) }
    }

    private fun row(items: List<Box>): Box {
        if (items.isEmpty()) return empty()
        val asc = items.maxOf { it.ascent }
        val desc = items.maxOf { it.descent }
        return Box(items.sumOf { it.width.toDouble() }.toFloat(), asc, desc) { c, x, y ->
            var cx = x
            for (b in items) { b.draw(c, cx, y); cx += b.width }
        }
    }

    private fun scripts(n: EqNode.Scripts, size: Float, depth: Int): Box {
        val base = layout(n.base, size, depth + 1)
        val small = size * 0.7f
        val sup = n.sup?.let { layout(it, small, depth + 1) }
        val sub = n.sub?.let { layout(it, small, depth + 1) }
        // Superscripts rise with a tall base; subscripts drop below a deep one.
        val supShift = max(size * 0.38f, base.ascent - small * 0.4f)
        val subShift = max(size * 0.18f, base.descent + small * 0.1f).let { s -> if (sup != null) max(s, size * 0.25f) else s }
        val w = base.width + max(sup?.width ?: 0f, sub?.width ?: 0f) + size * 0.04f
        val asc = max(base.ascent, (sup?.let { supShift + it.ascent } ?: 0f))
        val desc = max(base.descent, (sub?.let { subShift + it.descent } ?: 0f))
        return Box(w, asc, desc) { c, x, y ->
            base.draw(c, x, y)
            sup?.draw?.invoke(c, x + base.width + size * 0.02f, y - supShift)
            sub?.draw?.invoke(c, x + base.width + size * 0.02f, y + subShift)
        }
    }

    /** The math axis (where fraction bars sit), above the baseline. */
    private fun axis(size: Float) = size * 0.27f

    private fun fraction(n: EqNode.Fraction, size: Float, depth: Int): Box {
        val num = layout(n.num, size * 0.9f, depth + 1)
        val den = layout(n.den, size * 0.9f, depth + 1)
        val gap = size * 0.12f
        val pad = size * 0.1f
        val w = max(num.width, den.width) + 2 * pad
        val thick = size * 0.05f
        val a = axis(size)
        val asc = a + thick / 2 + gap + num.height
        val desc = (-a + thick / 2 + gap + den.height).coerceAtLeast(0f)
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorArgb; strokeWidth = thick.coerceAtLeast(1f) }
        return Box(w, asc, desc) { c, x, y ->
            val barY = y - a
            num.draw(c, x + (w - num.width) / 2, barY - thick / 2 - gap - num.descent)
            den.draw(c, x + (w - den.width) / 2, barY + thick / 2 + gap + den.ascent)
            if (n.line) c.drawLine(x + pad / 2, barY, x + w - pad / 2, barY, bar)
        }
    }

    private fun root(n: EqNode.Root, size: Float, depth: Int): Box {
        val body = layout(n.body, size, depth + 1)
        val index = n.index?.let { layout(it, size * 0.6f, depth + 1) }
        val gap = size * 0.1f
        val thick = (size * 0.05f).coerceAtLeast(1f)
        val sign = size * 0.55f + (index?.width ?: 0f) * 0.5f
        val asc = body.ascent + gap + thick
        val desc = body.descent
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorArgb; style = Paint.Style.STROKE; strokeWidth = thick; strokeJoin = Paint.Join.ROUND }
        return Box(sign + body.width + size * 0.1f, asc, desc) { c, x, y ->
            val top = y - asc + thick / 2
            val bottom = y + desc
            val path = Path().apply {
                moveTo(x + sign * 0.05f, y - (asc + desc) * 0.35f + desc)
                lineTo(x + sign * 0.3f, y - (asc + desc) * 0.45f + desc)
                lineTo(x + sign * 0.6f, bottom)
                lineTo(x + sign, top)
                lineTo(x + sign + body.width + size * 0.08f, top)
            }
            c.drawPath(path, p)
            index?.draw?.invoke(c, x, y - (asc + desc) * 0.5f + desc)
            body.draw(c, x + sign, y)
        }
    }

    private fun bigOp(n: EqNode.BigOp, size: Float, depth: Int): Box {
        val word = n.symbol.length > 1 // lim
        val op = text(n.symbol, if (word) size else size * 1.5f, italic = false, bold = false)
        // A large symbol sits centered on the axis rather than on the baseline.
        val shift = if (word) 0f else (op.ascent - op.descent) / 2 - axis(size)
        val small = size * 0.7f
        val below = n.below?.let { layout(it, small, depth + 1) }
        val above = n.above?.let { layout(it, small, depth + 1) }
        val opAsc = op.ascent - shift
        val opDesc = op.descent + shift
        if (n.limitsBeside) {
            // Integrals: limits to the right, as scripts.
            val w = op.width + max(below?.width ?: 0f, above?.width ?: 0f) + size * 0.1f
            val asc = max(opAsc, above?.let { opAsc * 0.8f + it.ascent } ?: 0f)
            val desc = max(opDesc, below?.let { opDesc * 0.8f + it.descent } ?: 0f)
            return Box(w, asc, desc) { c, x, y ->
                op.draw(c, x, y + shift)
                above?.draw?.invoke(c, x + op.width, y - opAsc * 0.8f)
                below?.draw?.invoke(c, x + op.width * 0.8f, y + opDesc * 0.8f + (below.ascent * 0.5f))
            }
        }
        val gap = size * 0.08f
        val w = max(op.width, max(below?.width ?: 0f, above?.width ?: 0f)) + size * 0.1f
        val asc = opAsc + (above?.let { gap + it.height } ?: 0f)
        val desc = opDesc + (below?.let { gap + it.height } ?: 0f)
        return Box(w, asc, desc) { c, x, y ->
            op.draw(c, x + (w - op.width) / 2, y + shift)
            above?.draw?.invoke(c, x + (w - above.width) / 2, y - opAsc - gap - above.descent)
            below?.draw?.invoke(c, x + (w - below.width) / 2, y + opDesc + gap + below.ascent)
        }
    }

    private fun fenced(left: String, body: Box, right: String, size: Float): Box {
        val l = delimiter(left, body, size)
        val r = delimiter(right, body, size)
        return Box(l.width + body.width + r.width, max(body.ascent, l.ascent), max(body.descent, l.descent)) { c, x, y ->
            l.draw(c, x, y)
            body.draw(c, x + l.width, y)
            r.draw(c, x + l.width + body.width, y)
        }
    }

    /** [d] stretched vertically to cover [body] (never shorter than a plain one). */
    private fun delimiter(d: String, body: Box, size: Float): Box {
        if (d.isEmpty()) return Box(size * 0.05f, 0f, 0f) { _, _, _ -> }
        val p = paint(size, italic = false, bold = false)
        val plainAsc = size * 0.78f
        val plainDesc = size * 0.24f
        val asc = max(plainAsc, body.ascent + size * 0.05f)
        val desc = max(plainDesc, body.descent + size * 0.05f)
        val stretch = (asc + desc) / (plainAsc + plainDesc)
        val w = p.measureText(d) * (if (stretch > 1.5f) 1.15f else 1f) + size * 0.06f
        return Box(w, asc, desc) { c, x, y ->
            c.save()
            // Scale about the delimiter's middle so it spans [y - asc, y + desc].
            val mid = y + (desc - asc) / 2
            val plainMid = (plainDesc - plainAsc) / 2
            c.translate(x + size * 0.03f, mid)
            c.scale(1f, stretch)
            c.drawText(d, 0f, -plainMid, p)
            c.restore()
        }
    }

    private fun matrix(n: EqNode.Matrix, size: Float, depth: Int): Box {
        val cells = n.rows.map { r -> r.map { layout(it, size, depth + 1) } }
        if (cells.isEmpty()) return empty()
        val cols = cells.maxOf { it.size }
        val colW = (0 until cols).map { j -> cells.maxOf { r -> r.getOrNull(j)?.width ?: 0f } }
        val rowAsc = cells.map { r -> r.maxOfOrNull { it.ascent } ?: size * 0.7f }
        val rowDesc = cells.map { r -> r.maxOfOrNull { it.descent } ?: size * 0.2f }
        val rowGap = size * 0.25f
        val colGap = size * 0.8f
        val total = rowAsc.indices.sumOf { (rowAsc[it] + rowDesc[it]).toDouble() }.toFloat() + rowGap * (cells.size - 1)
        val w = colW.sum() + colGap * (cols - 1) + size * 0.2f
        // Centered on the axis.
        val asc = total / 2 + axis(size)
        val desc = total - asc
        return Box(w, asc, desc) { c, x, y ->
            var top = y - asc
            for ((i, r) in cells.withIndex()) {
                val baseline = top + rowAsc[i]
                var cx = x + size * 0.1f
                for (j in 0 until cols) {
                    r.getOrNull(j)?.let { b ->
                        val dx = when (n.align) { -1 -> 0f; 1 -> colW[j] - b.width; else -> (colW[j] - b.width) / 2 }
                        b.draw(c, cx + dx, baseline)
                    }
                    cx += colW[j] + colGap
                }
                top += rowAsc[i] + rowDesc[i] + rowGap
            }
        }
    }

    private fun accent(n: EqNode.Accent, size: Float, depth: Int): Box {
        val body = layout(n.body, size, depth + 1)
        val thick = (size * 0.05f).coerceAtLeast(1f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorArgb; strokeWidth = thick }
        if (n.under || n.mark == "‾") {
            val gap = size * 0.08f
            return Box(body.width, body.ascent + if (n.under) 0f else gap + thick, body.descent + if (n.under) gap + thick else 0f) { c, x, y ->
                body.draw(c, x, y)
                val ly = if (n.under) y + body.descent + gap else y - body.ascent - gap
                c.drawLine(x, ly, x + body.width, ly, p)
            }
        }
        val mark = text(n.mark, size * 0.8f, italic = false, bold = false)
        val lift = size * 0.15f
        return Box(max(body.width, mark.width), body.ascent + lift + mark.ascent * 0.5f, body.descent) { c, x, y ->
            body.draw(c, x, y)
            mark.draw(c, x + (body.width - mark.width) / 2 + size * 0.05f, y - body.ascent + mark.ascent * 0.6f - lift + size * 0.1f)
        }
    }
}
