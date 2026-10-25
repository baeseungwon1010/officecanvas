package io.github.baeseungwon1010.officecanvas.hwpx

/**
 * Line and page layout for documents that save no layout of their own (HWPML,
 * DOCX): text broken into lines measured with the faces the renderer draws with,
 * stacked at the paragraph's line spacing, and paged through [PageFlow].
 */
internal class TextFlow {

    /** How lines are spaced. */
    sealed class LineSpacing {
        /** Hangul: the line's text height plus [percent] - 100 % of it below. */
        data class Percent(val percent: Long) : LineSpacing()
        /** A fixed pitch, whatever the text. */
        data class Exactly(val hu: Long) : LineSpacing()
        /** The line's height plus this much. */
        data class Between(val hu: Long) : LineSpacing()
        /** At least this pitch. */
        data class AtLeast(val hu: Long) : LineSpacing()
        /** Word: a multiple of the font's single line (ascent, descent and gap),
         *  the text at the bottom of its line. */
        data class Multiple(val factor: Float) : LineSpacing()
    }

    /** How a paragraph is set. Lengths in HWPUNIT; [indent] is the first line's
     *  extra indent (negative: the other lines hang that far in). */
    class ParaShape(
        val align: ParaAlign = ParaAlign.JUSTIFY,
        val indent: Long = 0, val left: Long = 0, val right: Long = 0,
        val prev: Long = 0, val next: Long = 0,
        val spacing: LineSpacing = LineSpacing.Percent(160),
        /** Hangul (and other non-Latin) words may break between any two characters. */
        val breakNonLatin: Boolean = true
    )

    /** One unit of text that isn't broken: a word (with the spaces after it), a
     *  single Hangul character, a tab, an object or a forced line break. [line]:
     *  the font's single line height, for [LineSpacing.Multiple]. */
    class Atom(
        val spans: List<Span>, val width: Float, val trailing: Float, val height: Long,
        val lineBreak: Boolean = false, val line: Long = height * 6 / 5,
        /** A tab whose width depends on where it falls: the paragraph's stops (from
         *  the column's left edge), then every [defaultTab]. */
        val stops: List<TabStop>? = null, val defaultTab: Long = DEFAULT_TAB_HU
    ) {
        val isObject: Boolean get() = spans.any { it.inline != null }
    }

    /** A tab stop [pos] from the column's left edge; text after it is set from
     *  there (LEFT), centered on it, or ending at it (RIGHT). */
    class TabStop(val pos: Long, val align: ParaAlign = ParaAlign.LEFT)

    /** A paragraph laid out from its own top: its lines (and objects set alone on
     *  theirs), where the next paragraph starts ([height]) and where its last line
     *  ends ([bottom]: a cell is sized to that, not to the spacing after it).
     *  [empty]: no text or object at all; [lead]: the space before its first line
     *  (which a page the text runs onto drops). */
    class Laid(val lines: List<HwpxElement>, val height: Long, val bottom: Long, val empty: Boolean, val lead: Long = 0)

    private val widths = HashMap<Pair<CharStyle, String>, Float>()

    /** Measured as drawn; single characters (most Hangul atoms) are remembered. */
    fun measure(text: String, style: CharStyle): Float {
        if (text.length > 2) return HwpxRenderer.widthHu(Span(text, style))
        return widths.getOrPut(style to text) { HwpxRenderer.widthHu(Span(text, style)) }
    }

    /** Adds [text] as atoms: words with their trailing spaces, and Hangul or Han
     *  characters one by one when those may break anywhere. [line]: the font's
     *  single line height (default 1.2 x the size). */
    fun addText(text: String, style: CharStyle, breakNonLatin: Boolean, out: MutableList<Atom>, line: Long? = null) {
        val height = (style.sizePt * 100).toLong()
        val single = line ?: (height * 6 / 5)
        fun add(piece: String) {
            if (piece.isEmpty() || out.size >= MAX_ATOMS) return
            val trimmed = piece.trimEnd(' ')
            val width = measure(piece, style)
            val trailing = if (trimmed.length < piece.length) width - measure(trimmed, style) else 0f
            out += Atom(listOf(Span(piece, style)), width, trailing, height, line = single)
        }
        var from = 0
        var k = 0
        while (k < text.length) {
            val c = text[k]
            if (breakNonLatin && isNonLatin(c)) {
                add(text.substring(from, k))
                var end = k + 1
                while (end < text.length && text[end] == ' ') end++
                add(text.substring(k, end))
                from = end; k = end
                continue
            }
            if (c == ' ') {
                var end = k + 1
                while (end < text.length && text[end] == ' ') end++
                add(text.substring(from, end))
                from = end; k = end
                continue
            }
            k++
        }
        add(text.substring(from))
    }

    fun tab(style: CharStyle, widthHu: Long = DEFAULT_TAB_HU) =
        Atom(listOf(Span("\t", style, widthHu)), widthHu.toFloat(), 0f, (style.sizePt * 100).toLong())

    /** A tab set by [stops] (or every [defaultTab]) where it falls in the line. */
    fun tab(style: CharStyle, stops: List<TabStop>, defaultTab: Long) =
        Atom(listOf(Span("\t", style, 0)), 0f, 0f, (style.sizePt * 100).toLong(), stops = stops.sortedBy { it.pos }, defaultTab = defaultTab.coerceAtLeast(100))

    fun lineBreak(style: CharStyle) = Atom(emptyList(), 0f, 0f, (style.sizePt * 100).toLong(), lineBreak = true)

    fun inline(obj: HwpxElement, style: CharStyle) = Atom(listOf(inlineSpan(obj, style)), obj.widthHu.toFloat(), 0f, obj.extentHu, line = obj.extentHu)

    /**
     * Lays out [atoms] in a column [width] wide: lines filled with whole atoms
     * while they fit (trailing spaces may hang past the edge; a lone atom too wide
     * for any line goes in anyway), an object alone on its line placed on its own.
     * [baseStyle] sizes an empty paragraph's line. [tabStops] (from the column's
     * left) place tabs; without them a tab takes its own width.
     */
    fun lay(input: List<Atom>, shape: ParaShape, width: Long, baseStyle: CharStyle, baseLine: Long? = null): Laid {
        val atoms = input.toMutableList()
        val baseHeight = (baseStyle.sizePt * 100).toLong()
        val baseSingle = baseLine ?: (baseHeight * 6 / 5)
        val lines = ArrayList<HwpxElement>()
        var y = shape.prev
        var bottom = y
        var i = 0
        var first = true
        do {
            val x = shape.left + if (first) shape.indent.coerceAtLeast(0) else (-shape.indent).coerceAtLeast(0)
            val avail = (width - x - shape.right).coerceAtLeast(500)
            val lineAtoms = ArrayList<Atom>()
            var used = 0f
            while (i < atoms.size && !atoms[i].lineBreak) {
                var a = atoms[i]
                if (a.stops != null) {
                    val w = tabWidth(atoms, i, x + used, shape)
                    a = Atom(listOf(Span("\t", a.spans[0].style, w.toLong())), w, 0f, a.height, line = a.line)
                }
                if (lineAtoms.isNotEmpty() && used + a.width - a.trailing > avail) break
                // A word wider than a whole line is broken between its characters.
                if (lineAtoms.isEmpty() && a.width - a.trailing > avail && splitWord(atoms, i)) continue
                lineAtoms += a
                used += a.width
                i++
            }
            val forced = i < atoms.size && atoms[i].lineBreak
            if (forced) i++
            val textHeight = lineAtoms.filter { !it.isObject }.maxOfOrNull { it.height } ?: baseHeight
            val objectHeight = lineAtoms.filter { it.isObject }.maxOfOrNull { it.height } ?: 0L
            val single = lineAtoms.filter { !it.isObject }.maxOfOrNull { it.line } ?: baseSingle
            val last = i >= atoms.size
            val spans = merge(lineAtoms.flatMap { it.spans })
            // The line's box: [lineHeight] tall, text on [baseline] from its top.
            val (lineHeight, pitch, baseline) = when (val s = shape.spacing) {
                is LineSpacing.Multiple -> {
                    val h = maxOf((single * s.factor).toLong(), objectHeight)
                    // The text sits at the bottom of a taller line; its descent is
                    // about a fifth of the single line.
                    Triple(h, h, h - single / 5)
                }
                else -> {
                    val h = maxOf(textHeight, objectHeight)
                    Triple(h, pitch(s, textHeight, h), h - textHeight * 15 / 100)
                }
            }
            if (lineAtoms.size == 1 && objectHeight > 0 && lineAtoms[0].spans.size == 1) {
                val obj = lineAtoms[0].spans[0].inline!!
                val ox = x + when (shape.align) {
                    ParaAlign.CENTER -> ((avail - obj.widthHu) / 2).coerceAtLeast(0)
                    ParaAlign.RIGHT -> (avail - obj.widthHu).coerceAtLeast(0)
                    else -> 0L
                }
                // Hangul sets it at the line's top; Word on the line's bottom.
                lines += obj.at(ox, if (shape.spacing is LineSpacing.Multiple) y + lineHeight - objectHeight else y)
            } else if (spans.isNotEmpty()) {
                lines += HwpxElement.TextRun(
                    xHu = x, yHu = y, widthHu = avail, heightHu = lineHeight, spans = spans,
                    baselineHu = baseline, align = shape.align, lastLine = last || forced
                )
            }
            bottom = y + lineHeight
            y += pitch
            first = false
        } while (i < atoms.size && lines.size < MAX_LINES)
        val empty = atoms.none { a -> a.isObject || a.spans.any { it.text.isNotBlank() } }
        return Laid(lines, y + shape.next, bottom + shape.next, empty, lead = shape.prev)
    }

    /** Replaces the text atom at [i] with one atom per character; false when it
     *  can't be split (an object, a tab, a single character). */
    private fun splitWord(atoms: MutableList<Atom>, i: Int): Boolean {
        val a = atoms[i]
        val span = a.spans.singleOrNull()?.takeIf { it.inline == null && it.tabHu == null && it.text.length > 1 } ?: return false
        val pieces = span.text.map { c ->
            val w = measure(c.toString(), span.style)
            Atom(listOf(span.copy(text = c.toString())), w, if (c == ' ') w else 0f, a.height, line = a.line)
        }
        atoms.removeAt(i)
        atoms.addAll(i, pieces)
        return true
    }

    /** The width of the tab at [i] starting [pos] from the column's left: to the
     *  next stop past it (the hanging indent's edge counts as one), or the next
     *  default stop; a centered or right stop leaves room for the text after it,
     *  up to the next tab. */
    private fun tabWidth(atoms: List<Atom>, i: Int, pos: Float, shape: ParaShape): Float {
        val tab = atoms[i]
        // The edge the other lines start at (a hanging indent's) is a stop too.
        val hang = shape.left + (-shape.indent).coerceAtLeast(0)
        val implicit = if (hang > pos + 1) listOf(TabStop(hang)) else emptyList()
        val stop = (tab.stops.orEmpty() + implicit).filter { it.pos > pos + 1 }.minByOrNull { it.pos }
            ?: return (((pos / tab.defaultTab).toLong() + 1) * tab.defaultTab - pos).coerceAtLeast(0f)
        var follow = 0f
        var k = i + 1
        while (k < atoms.size && atoms[k].stops == null && !atoms[k].lineBreak) { follow += atoms[k].width; k++ }
        if (k > i + 1) follow -= atoms[k - 1].trailing
        return when (stop.align) {
            ParaAlign.RIGHT -> stop.pos - pos - follow
            ParaAlign.CENTER -> stop.pos - pos - follow / 2
            else -> stop.pos - pos
        }.coerceAtLeast(0f)
    }

    /** Hangul's line pitch: a percent of the text height, a fixed pitch, extra
     *  space between lines, or a minimum. */
    private fun pitch(s: LineSpacing, textHeight: Long, height: Long): Long = when (s) {
        is LineSpacing.Exactly -> maxOf(s.hu, 1)
        is LineSpacing.Between -> height + s.hu
        is LineSpacing.AtLeast -> maxOf(height, s.hu)
        is LineSpacing.Percent -> height + textHeight * (s.percent - 100).coerceAtLeast(0) / 100
        is LineSpacing.Multiple -> height
    }

    /**
     * Pages a laid paragraph whose top is at [y] on the current page of [flow]
     * ([bodyHeight] tall): lines go on down and onto the next page when it is
     * full; a table taller than the room left runs over pages. Returns where the
     * paragraph's first line went (for objects anchored to it) and where the next
     * paragraph starts.
     */
    fun place(flow: PageFlow, laid: Laid, y: Long, bodyHeight: Long): Pair<Long, Long> {
        var base = y
        var anchorY: Long? = null
        // Drawings (a paragraph's shading and borders) go with the line after them:
        // whether to start a page is decided by that line.
        var i = 0
        val lines = laid.lines
        while (i < lines.size) {
            var k = i
            while (k < lines.size && lines[k] is HwpxElement.Drawing) k++
            val line = lines.getOrNull(k)
            if (line != null && line !is HwpxElement.Table) {
                val top = base + line.yHu
                if (top + line.extentHu > bodyHeight && top > 0) { flow.breakPage(); base = -lines[i].yHu.coerceAtMost(line.yHu) }
            }
            for (j in i..minOf(k, lines.size - 1)) {
                val el = lines[j]
                val top = base + el.yHu
                if (anchorY == null && el !is HwpxElement.Drawing) anchorY = top
                val pageBefore = flow.page
                flow.add(el.at(el.xHu, top), laidOut = true)
                // A table that ran over pages: the paragraph goes on below its last piece.
                if (el is HwpxElement.Table && flow.page != pageBefore) base = flow.lastTableBottom - el.yHu - el.heightHu
            }
            i = k + 1
        }
        return (anchorY ?: base) to base + laid.height
    }

    companion object {
        /** Lines in one paragraph or cell, whatever a file claims. */
        const val MAX_LINES = 20_000

        /** Words and characters kept from one paragraph (far more than fit on the pages it may have). */
        const val MAX_ATOMS = 200_000

        fun isNonLatin(c: Char) = c.code in 0x1100..0x11FF || c.code in 0x3000..0x9FFF || c.code in 0xAC00..0xD7A3 || c.code in 0xF900..0xFAFF || c.code in 0xFF00..0xFFEF

        /** Consecutive text spans in one style, joined. */
        fun merge(spans: List<Span>): List<Span> {
            val out = ArrayList<Span>(spans.size)
            for (s in spans) {
                val prev = out.lastOrNull()
                if (prev != null && prev.style == s.style && prev.tabHu == null && prev.inline == null && s.tabHu == null && s.inline == null) {
                    out[out.size - 1] = prev.copy(text = prev.text + s.text)
                } else out += s
            }
            return out
        }
    }
}
