package io.github.baeseungwon1010.officecanvas.convert

import io.github.baeseungwon1010.officecanvas.pdf.PdfChar
import io.github.baeseungwon1010.officecanvas.pdf.PdfFont
import io.github.baeseungwon1010.officecanvas.pdf.StandardWidths
import java.util.IdentityHashMap
import io.github.baeseungwon1010.officecanvas.pdf.PdfPageContent
import io.github.baeseungwon1010.officecanvas.pdf.PdfPicture
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Rebuilds a page's structure from where its characters, rulings and pictures are
 * ([PdfPageContent]): characters into lines (split at wide gaps and at table
 * rulings), ruled areas into tables (rows and columns from the rulings, merged
 * cells where a ruling is missing), lines into blocks and blocks into reading
 * order (columns found by cutting the page along empty bands), blocks into
 * paragraphs (by spacing, indents and line ends), with alignment, indents, line
 * spacing and styled runs measured from the lines.
 *
 * Nothing here is particular to one kind of document: every threshold is relative
 * to the text's size.
 */
internal object PdfLayout {

    // ---- lines ----------------------------------------------------------------

    /** A run of characters on one baseline, between gaps. */
    class Line(val chars: List<PdfChar>, val baseline: Double) {
        val x0 = chars.minOf { it.x }
        val x1 = chars.maxOf { it.x + it.width }
        val size: Double = chars.groupingBy { Math.round(it.size * 2) / 2.0 }.eachCount().maxByOrNull { it.value }!!.key
        val top = baseline - size * 0.85
        val bottom = baseline + size * 0.25
        val text: String get() = chars.joinToString("") { it.text }
    }

    class Ruling(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val width: Double, val color: Int) {
        val horizontal get() = abs(y1 - y0) < abs(x1 - x0)
    }

    /** The share of the page's height at its top and bottom where headers and footers are looked for. */
    private const val BAND = 0.11

    /** The lines in a page's top and bottom bands: their keys ("T:"/"B:" and the text, digits as #) and the numbers in them, to find what repeats. */
    /** A line in a page's top or bottom band: where its baseline is and the numbers in it. */
    class Band(val baseline: Double, val numbers: List<Int>)

    fun bandKeys(content: PdfPageContent): Map<String, Band> {
        val h = content.height
        val out = HashMap<String, Band>()
        val chars = dedupe(content.chars.filter { !it.invisible && it.text.isNotEmpty() })
        // Headers are set small; a slide's title, repeated from slide to slide, isn't one
        // (on a slide of pictures, the title may be most of its text).
        val sizes = chars.filter { it.text.isNotBlank() }.map { it.size }.sorted()
        val usual = sizes.getOrNull(sizes.size / 2) ?: 0.0
        for (l in lines(chars, emptyList())) {
            if (l.size > usual * 1.25 || l.size > 16) continue
            val k = when {
                l.bottom < h * BAND -> "T:" + key(l)
                l.top > h * (1 - BAND) -> "B:" + key(l)
                else -> continue
            }
            // Of lines alike (a page number, and a table's lone numbers), the outermost.
            val had = out[k]
            if (had != null && (if (k.startsWith("T:")) had.baseline <= l.baseline else had.baseline >= l.baseline)) continue
            out[k] = Band(l.baseline, Regex("\\d+").findAll(l.text).mapNotNull { it.value.take(6).toIntOrNull() }.toList())
        }
        return out
    }

    private fun key(l: Line) = l.text.replace(Regex("\\s+"), "").replace(Regex("\\d+"), "#")

    /**
     * A header or footer line: one repeated at the pages' tops or bottoms, or there a
     * page number standing alone ("1/1", "- 3 -", "Page 2 of 5"), which a one-page
     * document can't repeat.
     */
    private fun isRepeated(l: Line, h: Double, repeated: Set<String>, pageNumber: Int) =
        (l.bottom < h * BAND && "T:" + key(l) in repeated) || (l.top > h * (1 - BAND) && "B:" + key(l) in repeated) ||
            ((l.bottom < h * BAND || l.top > h * (1 - BAND)) && PAGE_NUMBER.matchEntire(spaced(l).trim())?.groupValues?.get(1)?.toIntOrNull() == pageNumber)

    private val PAGE_NUMBER = Regex("[-–—(\\[\\s]*(?:page|p\\.|pg\\.?)?\\s*(\\d{1,4})\\s*(?:(?:/|of)\\s*\\d{1,4})?\\s*(?:쪽|페이지)?[-–—)\\]\\s]*", RegexOption.IGNORE_CASE)

    /**
     * A page's structure; [margins] (left, top, right, bottom) set the column the
     * positions are measured from. Lines whose [bandKeys] are in [repeated] are the
     * header and footer; [pageNumber] in them becomes a page number field.
     */
    fun page(content: PdfPageContent, pictures: List<ConvImage>, margins: DoubleArray? = null, repeated: Set<String> = emptySet(), pageNumber: Int = 1): ConvPage {
        val w = content.width; val h = content.height
        // Text a viewer would show (or, on a scanned page, the invisible text over the scan).
        val visible = content.chars.filter { !it.invisible }
        val ocr = visible.count { it.text.isNotBlank() } < 10 && content.chars.count { it.invisible && it.text.isNotBlank() } > 20
        val chars = (if (ocr) content.chars else visible).filter { c ->
            c.text.isNotEmpty() && c.x > -c.size && c.x < w + c.size && c.y > -c.size && c.y < h + c.size * 2
        }
        val rulings = rulings(content, w, h)
        val lines = lines(dedupe(chars), rulings.filter { !it.horizontal })
        tracking(lines)
        val tables = tables(rulings, lines, content)
        val inTable = HashSet<Line>()
        for (t in tables) for (n in t.nested()) inTable += n.lines
        val found = lines.filter { it !in inTable && isRepeated(it, h, repeated, pageNumber) }
        // What stands on a header's or footer's baseline in its band is part of it (a title beside
        // the page number: on a one-page document nothing repeats to find it by).
        fun inBand(l: Line) = l.bottom < h * BAND || l.top > h * (1 - BAND)
        val bandLines = found + lines.filter { l ->
            l !in inTable && l !in found && inBand(l) && found.any { f -> abs(f.baseline - l.baseline) < f.size * 0.3 && l.size <= f.size * 1.3 }
        }
        val free = lines.filter { it !in inTable && it !in bandLines }
        // Pictures: those the text runs over float behind it; the rest are in the flow.
        val floating = ArrayList<ConvImage>()
        val flowPictures = ArrayList<ConvImage>()
        val placed = ArrayList<Pair<ConvImage, Any>>() // picture and where it goes: floating, flowPictures or a table
        for (p in pictures) {
            if (ocr && p.width * p.height > w * h * 0.5) continue
            val overText = free.any { l -> l.x0 < p.x + p.width && l.x1 > p.x && l.top < p.y + p.height && l.bottom > p.y } ||
                tables.any { t -> t.x0 < p.x + p.width && t.x1 > p.x && t.y0 < p.y + p.height && t.y1 > p.y && !(p.x >= t.x0 && p.x + p.width <= t.x1 && p.y >= t.y0 && p.y + p.height <= t.y1) }
            val inCell = tables.flatMap { it.nested() }.filter { t -> p.x >= t.x0 - 1 && p.x + p.width <= t.x1 + 1 && p.y >= t.y0 - 1 && p.y + p.height <= t.y1 + 1 }
                .minByOrNull { (it.x1 - it.x0) * (it.y1 - it.y0) }
            // Pictures laid over each other (a photo over another) can't both be in the flow.
            val overPicture = pictures.any { o -> o !== p && o.width * o.height <= w * h * 0.6 &&
                min(o.x + o.width, p.x + p.width) - max(o.x, p.x) > 2 && min(o.y + o.height, p.y + p.height) - max(o.y, p.y) > 2 }
            // In a cell too, a picture over its text (a seal over a signature) floats.
            val overCellText = inCell != null && inCell.lines.any { l -> l.x0 < p.x + p.width && l.x1 > p.x && l.top < p.y + p.height && l.bottom > p.y }
            placed += p to when {
                p.floats || overPicture || overCellText -> floating
                inCell != null -> inCell
                overText || p.width * p.height > w * h * 0.6 -> floating
                else -> flowPictures
            }
        }
        // Strips of one picture drawn edge to edge (a cover's background in 17 bands) float together
        // if one must: one band floating behind a title and the rest in the flow split the picture.
        if (placed.size in 2..2000) {
            val parent = IntArray(placed.size) { it }
            fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
            fun touch(a: ConvImage, b: ConvImage): Boolean {
                val ox = min(a.x + a.width, b.x + b.width) - max(a.x, b.x)
                val oy = min(a.y + a.height, b.y + b.height) - max(a.y, b.y)
                return ox > -1 && oy > -1 && (ox > min(a.width, b.width) * 0.5 || oy > min(a.height, b.height) * 0.5)
            }
            for (i in placed.indices) for (j in i + 1 until placed.size) if (touch(placed[i].first, placed[j].first)) parent[find(i)] = find(j)
            val floats = placed.indices.filter { placed[it].second === floating }.map { find(it) }.toSet()
            for (i in placed.indices) if (find(i) in floats) placed[i] = placed[i].first to floating
        }
        // (By identity: two empty lists are equal.)
        for ((p, where) in placed) when {
            where is TableGrid -> where.pictures += p
            where === floating -> floating += p
            else -> flowPictures += p
        }
        // Margins: where the content is, within reason.
        val boxes = free.map { doubleArrayOf(it.x0, it.top, it.x1, it.bottom) } + tables.map { doubleArrayOf(it.x0, it.y0, it.x1, it.y1) } +
            flowPictures.map { doubleArrayOf(it.x, it.y, it.x + it.width, it.y + it.height) }
        val left = margins?.get(0) ?: (boxes.minOfOrNull { it[0] } ?: w * 0.1).coerceIn(0.0, w * 0.3)
        val right = margins?.get(2) ?: (w - (boxes.maxOfOrNull { it[2] } ?: w * 0.9)).coerceIn(0.0, w * 0.3)
        val top = margins?.get(1) ?: (boxes.minOfOrNull { it[1] } ?: h * 0.1).coerceIn(0.0, h * 0.3)
        val bottom = margins?.get(3) ?: (h - (boxes.maxOfOrNull { it[3] } ?: h * 0.9)).coerceIn(0.0, h * 0.3)
        val items = ArrayList<Item>()
        blocks(free).forEach { items += Item.Text(it) }
        tables.forEach { items += Item.Grid(it) }
        flowPictures.forEach { items += Item.Picture(it) }
        val fills = freeFills(content, tables)
        /** [items] in reading order as blocks between [x0] and [x1], the first measured from [top]. */
        fun flow(items: List<Item>, x0: Double, x1: Double, top: Double): List<ConvBlock> {
            val blocks = ArrayList<ConvBlock>()
            var prevBottom = top
            for (item in items) {
                when (item) {
                    is Item.Text -> {
                        // Text wraps at the column's edge, or before whatever stands to its right.
                        val wrap = items.filter { o -> o !== item && o.x0 >= item.x1 - 1 && o.y0 < item.y1 && o.y1 > item.y0 }.minOfOrNull { it.x0 - 4 } ?: x1
                        val paras = paragraphs(item.lines, x0, x1, rulings, maxOf(wrap, item.x1), fills)
                        var first = true
                        for ((para, bounds) in paras) {
                            val before = if (first) (bounds.first - prevBottom).coerceAtLeast(0.0) else para.spaceBefore
                            blocks += if (first) para.withSpaceBefore(before) else para
                            prevBottom = bounds.second
                            first = false
                        }
                    }
                    is Item.Grid -> {
                        blocks += item.table.build(x0, x1, rulings, (item.table.y0 - prevBottom).coerceAtLeast(0.0))
                        prevBottom = item.table.y1
                    }
                    is Item.Picture -> {
                        val p = item.picture
                        val img = ConvImage(p.data, p.png, p.pixelWidth, p.pixelHeight, p.width, p.height, p.x - x0, p.y)
                        val center = abs((p.x + p.width / 2) - (x0 + x1) / 2) < 6 && p.x - x0 > 10
                        blocks += ConvParagraph(emptyList(), if (center) ConvAlign.CENTER else ConvAlign.LEFT,
                            leftIndent = if (center) 0.0 else (p.x - x0).coerceAtLeast(0.0), spaceBefore = (p.y - prevBottom).coerceAtLeast(0.0), image = img)
                        prevBottom = p.y + p.height
                    }
                    is Item.Columns -> {
                        // Parts side by side stay side by side: a borderless row, one cell each.
                        val edges = ArrayList<Double>()
                        edges += x0
                        for (k in 1 until item.parts.size) edges += max(item.parts[k].minOf { it.x0 } - 4, edges.last() + 1)
                        edges += max(x1, item.x1)
                        val cells = item.parts.mapIndexed { k, part ->
                            ConvCell(0, k, 1, 1, flow(part, edges[k] + 1.5, edges[k + 1] - 1.5, item.y0))
                        }
                        blocks += ConvTable(0.0, (0 until item.parts.size).map { edges[it + 1] - edges[it] }, listOf(item.y1 - item.y0), cells,
                            (item.y0 - prevBottom).coerceAtLeast(0.0))
                        prevBottom = item.y1
                    }
                }
            }
            return blocks
        }
        val blocks = flow(order(items), left, w - right, top)
        fun decor(ls: List<Line>): List<ConvParagraph> = if (ls.isEmpty()) emptyList() else
            blocks(ls).flatMap { paragraphs(it, left, w - right, rulings) }.map { (p, _) -> withPageField(p, pageNumber) }
        val heads = bandLines.filter { it.bottom < h * BAND }
        val feet = bandLines.filter { it.top > h * (1 - BAND) }
        return ConvPage(w, h, left, right, top, bottom, blocks, floating,
            decor(heads), heads.minOfOrNull { it.top } ?: 0.0, decor(feet), feet.maxOfOrNull { h - it.bottom } ?: 0.0)
    }

    /** [p] with [n] (the page's number) written as a page number field. */
    private fun withPageField(p: ConvParagraph, n: Int): ConvParagraph {
        val runs = ArrayList<ConvRun>()
        for (r in p.runs) {
            var last = 0
            for (m in Regex("\\d+").findAll(r.text)) {
                if (m.value.toIntOrNull() != n) continue
                if (m.range.first > last) runs += r.copyText(r.text.substring(last, m.range.first))
                runs += ConvRun(m.value, r.font, r.size, r.bold, r.italic, r.underline, r.color, r.script, field = "PAGE", spacing = r.spacing, scale = r.scale, mono = r.mono, serif = r.serif)
                last = m.range.last + 1
            }
            runs += if (last == 0) r else r.copyText(r.text.substring(last))
        }
        return ConvParagraph(runs.filter { it.text.isNotEmpty() }, p.align, p.leftIndent, p.rightIndent, p.firstLine, 0.0, p.lineHeight, null, p.tabs, rightTabs = p.rightTabs)
    }

    private fun ConvParagraph.withSpaceBefore(v: Double) =
        ConvParagraph(runs, align, leftIndent, rightIndent, firstLine, v, lineHeight, image, tabs, shading, rightTabs)

    /** Filled rectangles outside the tables, which may lie behind paragraphs. */
    private fun freeFills(content: PdfPageContent, tables: List<TableGrid>) = content.rects.filter { r ->
        !isWhite(r.color) && r.w > 6 && r.h > 4 && r.w * r.h < content.width * content.height * 0.5 &&
            tables.none { t -> r.x >= t.x0 - 1 && r.x + r.w <= t.x1 + 1 && r.y >= t.y0 - 1 && r.y + r.h <= t.y1 + 1 }
    }

    /** Where a page's content lies (left, top, right, bottom margins), from its text (but the [repeated] header and footer), rulings and pictures. */
    /** A picture's place on its page, to find the ones at one place on many pages. */
    fun placeKey(p: PdfPicture) = "${Math.round(p.x)},${Math.round(p.y)},${Math.round(p.w)},${Math.round(p.h)}"

    fun margins(content: PdfPageContent, repeated: Set<String> = emptySet(), pageNumber: Int = 1, decorations: Set<String> = emptySet()): DoubleArray? {
        val w = content.width; val h = content.height
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>(); val xe = ArrayList<Double>(); val ye = ArrayList<Double>()
        for (l in lines(dedupe(content.chars.filter { !it.invisible && it.text.isNotEmpty() }), emptyList())) {
            if (l.x0 < 0 || l.x1 > w || l.top < 0 || l.bottom > h || isRepeated(l, h, repeated, pageNumber)) continue
            xs += l.x0; xe += l.x1; ys += l.top; ye += l.bottom
        }
        for (p in content.pictures) if (p.w * p.h < w * h * 0.6 && placeKey(p) !in decorations) { xs += p.x; xe += p.x + p.w; ys += p.y; ye += p.y + p.h }
        // Rulings and shading: a table's frame reaches past its text. Not a bar along the
        // page's edge (a banner, a bleed), which no table reaches.
        fun inside(x0: Double, y0: Double, x1: Double, y1: Double) = x0 > w * 0.01 && y0 > h * 0.01 && x1 < w * 0.99 && y1 < h * 0.99
        for (l in content.lines) if (inside(minOf(l.x0, l.x1), minOf(l.y0, l.y1), maxOf(l.x0, l.x1), maxOf(l.y0, l.y1))) {
            xs += minOf(l.x0, l.x1); xe += maxOf(l.x0, l.x1); ys += minOf(l.y0, l.y1); ye += maxOf(l.y0, l.y1)
        }
        for (r in content.rects) if (r.w * r.h < w * h * 0.6 && inside(r.x, r.y, r.x + r.w, r.y + r.h)) {
            xs += r.x; xe += r.x + r.w; ys += r.y; ye += r.y + r.h
        }
        if (xs.isEmpty()) return null
        return doubleArrayOf(xs.min().coerceIn(0.0, w * 0.3), ys.min().coerceIn(0.0, h * 0.3), (w - xe.max()).coerceIn(0.0, w * 0.3), (h - ye.max()).coerceIn(0.0, h * 0.3))
    }

    /** Drops characters drawn twice in the same place (fake bold, shadows). */
    private fun dedupe(chars: List<PdfChar>): List<PdfChar> {
        val seen = HashSet<Triple<String, Long, Long>>()
        return chars.filter { c ->
            val key = Triple(c.text, Math.round(c.x / max(c.size * 0.15, 0.5)), Math.round(c.y / max(c.size * 0.15, 0.5)))
            seen.add(key)
        }
    }

    /** Stroked lines, and filled rectangles thin enough to be lines, as horizontal and vertical rulings. */
    private fun rulings(content: PdfPageContent, w: Double, h: Double): List<Ruling> {
        val out = ArrayList<Ruling>()
        for (l in content.lines) {
            val dx = abs(l.x1 - l.x0); val dy = abs(l.y1 - l.y0)
            if (dx < 2 && dy < 2) continue
            if (dy <= 0.6 || dy < dx * 0.02) out += Ruling(min(l.x0, l.x1), (l.y0 + l.y1) / 2, max(l.x0, l.x1), (l.y0 + l.y1) / 2, l.width, l.color)
            else if (dx <= 0.6 || dx < dy * 0.02) out += Ruling((l.x0 + l.x1) / 2, min(l.y0, l.y1), (l.x0 + l.x1) / 2, max(l.y0, l.y1), l.width, l.color)
        }
        for (r in content.rects) {
            if (isWhite(r.color)) continue
            if (r.h <= 2.5 && r.w > 2) out += Ruling(r.x, r.y + r.h / 2, r.x + r.w, r.y + r.h / 2, max(r.h, 0.3), r.color)
            else if (r.w <= 2.5 && r.h > 2) out += Ruling(r.x + r.w / 2, r.y, r.x + r.w / 2, r.y + r.h, max(r.w, 0.3), r.color)
        }
        return out.filter { it.x1 >= -5 && it.x0 <= w + 5 && it.y1 >= -5 && it.y0 <= h + 5 }
    }

    private fun isWhite(c: Int) = (c shr 16 and 0xFF) > 245 && (c shr 8 and 0xFF) > 245 && (c and 0xFF) > 245

    /**
     * Characters into lines: same baseline (within a third of their size; smaller
     * characters raised or lowered beside bigger ones join them as scripts), in x
     * order, split where the gap is wider than about an em or a vertical ruling
     * runs between them.
     */
    fun lines(chars: List<PdfChar>, verticals: List<Ruling>): List<Line> {
        val horizontal = chars.filter { abs(it.angle) < 3 }
        val rotated = chars.filter { abs(it.angle) >= 3 }
        val out = ArrayList<Line>()
        // Group by baseline, biggest characters first so scripts find their line.
        val groups = ArrayList<MutableList<PdfChar>>()
        val baselines = ArrayList<Double>()
        val sizes = ArrayList<Double>()
        for (c in horizontal.sortedWith(compareByDescending<PdfChar> { Math.round(it.size) }.thenBy { it.y }.thenBy { it.x })) {
            var best = -1
            var bestD = Double.MAX_VALUE
            for (g in groups.indices) {
                val d = abs(c.y - baselines[g])
                val tol = if (c.size < sizes[g] * 0.85) sizes[g] * 0.45 else min(c.size, sizes[g]) * 0.3
                if (d <= tol && d < bestD) { best = g; bestD = d }
            }
            if (best < 0) { groups += mutableListOf(c); baselines += c.y; sizes += c.size } else groups[best] += c
            if (groups.size > 20_000) break
        }
        // Column gutters can be narrower than the gap that splits a line: a gap splits
        // it too where the lines around it, with text on both sides, leave the same place empty.
        val ink = groups.map { m -> m.filter { !it.isSpace && it.text.isNotBlank() }.sortedBy { it.x } }
        val byY = groups.indices.sortedBy { baselines[it] }
        val rank = IntArray(groups.size).also { r -> byY.forEachIndexed { i, g -> r[g] = i } }
        fun gutter(g: Int, m: Double, em: Double): Boolean {
            // Lines with ink on both sides and none there, counted outward each way up to
            // the first line with ink there (a heading across both columns ends them).
            var both = 0
            for (dir in intArrayOf(-1, 1)) {
                var i = rank[g] + dir
                while (i in byY.indices && abs(baselines[byY[i]] - baselines[g]) < em * 6) {
                    val row = ink[byY[i]]
                    i += dir
                    if (row.any { it.x < m + em * 0.15 && it.x + it.width > m - em * 0.15 }) break
                    if (row.any { it.x + it.width <= m } && row.any { it.x >= m }) both++
                }
            }
            return both >= 2
        }
        for ((g, members) in groups.withIndex()) {
            val sorted = members.sortedBy { it.x }
            var cur = ArrayList<PdfChar>()
            var lastInk: PdfChar? = null
            var pieceStart = Double.NaN
            for (c in sorted) {
                val prev = cur.lastOrNull()
                val isInk = !c.isSpace && c.text.isNotBlank()
                if (prev != null) {
                    val gap = c.x - (prev.x + prev.width)
                    val em = max(min(prev.size, c.size), 1.0)
                    val ruled = verticals.any { v -> v.x0 > prev.x + prev.width * 0.5 && v.x0 < c.x + c.width * 0.5 && v.y0 < baselines[g] && v.y1 > baselines[g] - em * 0.6 }
                    val inkGap = lastInk?.let { c.x - (it.x + it.width) } ?: 0.0
                    // A column's text on the left, not a bullet or a number.
                    val gutter = isInk && inkGap > em * 0.8 && inkGap <= em * 1.6 && lastInk != null && lastInk.x + lastInk.width - pieceStart > em * 4 &&
                        gutter(g, c.x - inkGap / 2, em)
                    if (gap > em * 1.6 || ruled || gap < -em * 3 || gutter) { out += Line(cur, baselines[g]); cur = ArrayList(); pieceStart = Double.NaN }
                }
                cur += c
                if (isInk) { lastInk = c; if (pieceStart.isNaN()) pieceStart = c.x }
            }
            if (cur.isNotEmpty()) out += Line(cur, baselines[g])
        }
        val joined = ArrayList(withScripts(out))
        out.clear(); out += joined
        // Rotated text: each run in drawing order as a line at its top.
        var run = ArrayList<PdfChar>()
        for (c in rotated) {
            val prev = run.lastOrNull()
            if (prev != null && (abs(prev.angle - c.angle) > 3 || hypot(c.x - prev.x, c.y - prev.y) > prev.size * 2)) { out += rotatedLine(run); run = ArrayList() }
            run += c
        }
        if (run.isNotEmpty()) out += rotatedLine(run)
        return out.filter { l -> l.chars.any { it.text.isNotBlank() } }
    }

    /**
     * Scripts raised or lowered further than grouping by baseline allows (an exponent
     * set high): a short line of smaller characters, within a bigger line's span and
     * just above or below its baseline, joins that line.
     */
    private fun withScripts(lines: List<Line>): List<Line> {
        val byY = lines.sortedBy { it.baseline }
        val ys = DoubleArray(byY.size) { byY[it].baseline }
        val extra = java.util.IdentityHashMap<Line, MutableList<PdfChar>>()
        val moved = java.util.IdentityHashMap<Line, Boolean>()
        for (s in lines) {
            if (s.chars.size > 6 || s.x1 - s.x0 > s.size * 6) continue
            // Hosts' baselines lie within an em or so of the script's.
            var i = java.util.Arrays.binarySearch(ys, s.baseline - s.size * 2.5).let { if (it < 0) -it - 1 else it }
            var host: Line? = null
            while (i < byY.size && ys[i] < s.baseline + s.size * 2.5) {
                val h = byY[i++]
                if (h === s || moved.containsKey(h) || s.size >= h.size * 0.85 || extra.containsKey(s)) continue
                if (s.x0 < h.x0 - h.size || s.x1 > h.x1 + h.size) continue
                if (s.baseline < h.baseline - h.size * 0.9 || s.baseline > h.baseline + h.size * 0.5) continue
                if (host == null || abs(h.baseline - s.baseline) < abs(host.baseline - s.baseline)) host = h
            }
            if (host == null) continue
            extra.getOrPut(host) { ArrayList() } += s.chars
            moved[s] = true
        }
        if (moved.isEmpty()) return lines
        return lines.filter { !moved.containsKey(it) }.map { l ->
            extra[l]?.let { Line((l.chars + it).sortedBy { c -> c.x }, l.baseline) } ?: l
        }
    }

    private fun hypot(a: Double, b: Double) = Math.hypot(a, b)

    private fun rotatedLine(chars: List<PdfChar>): Line {
        val x = chars.minOf { it.x }; val y = chars.minOf { it.y }
        val size = chars.map { it.size }.sorted()[chars.size / 2]
        var cx = x
        val flat = chars.map { c -> PdfChar(c.text, cx, y + size, size * 0.6, size, c.font, c.color, 0.0, c.invisible, c.isSpace).also { cx += size * 0.6 } }
        return Line(flat, y + size)
    }

    // ---- runs -------------------------------------------------------------------

    /** A line's text as styled runs, spaces put where the gaps are. */
    private fun runs(line: Line, rulings: List<Ruling>, out: MutableList<ConvRun>) {
        // An underline: a thin line just below the baseline, no longer than the text (a cell's border isn't one).
        val underlines = rulings.filter {
            it.horizontal && it.y0 > line.baseline && it.y0 < line.baseline + line.size * 0.3 && it.width < line.size * 0.2 &&
                it.x0 >= line.x0 - line.size * 0.6 && it.x1 <= line.x1 + line.size * 0.6
        }
        var prev: PdfChar? = null
        val sb = StringBuilder()
        var style: ConvRun? = null
        // In a standard face standing in for the PDF's font: its width there and in the PDF, scaled to match.
        var pdfWidth = 0.0; var faceWidth = 0.0
        fun flush() {
            style?.let { s ->
                val fit = if (faceWidth > 0) pdfWidth / faceWidth else 1.0
                val r = if (s.font in STANDARD && faceWidth > 0 && fit in 0.6..1.4) ConvRun(s.text, s.font, s.size, s.bold, s.italic, s.underline, s.color, s.script, s.field, s.spacing, Math.round(fit * 100).toInt(), s.mono, s.serif) else s
                if (sb.isNotEmpty()) out += r.copyText(sb.toString())
            }
            sb.clear(); pdfWidth = 0.0; faceWidth = 0.0
        }
        for (c in line.chars) {
            // (Spaces in a monospaced line line things up: each one stays.)
            if (c.isSpace || c.text.isBlank()) { if (sb.isNotEmpty() && (sb.last() != ' ' || c.font.monospace)) sb.append(' '); prev = c; continue }
            val script = when {
                c.size < line.size * 0.85 && c.y < line.baseline - line.size * 0.15 -> 1
                c.size < line.size * 0.85 && c.y > line.baseline + line.size * 0.08 -> -1
                else -> 0
            }
            val under = underlines.any { u -> u.x0 <= c.x + c.width * 0.5 && u.x1 >= c.x + c.width * 0.5 }
            val size = if (script != 0) line.size else Math.round(c.size * 2) / 2.0
            // Sizes go in half points: the width they lose or gain is scaled back, with the PDF's own scaling.
            val scale = if (script != 0) 100 else Math.round(c.hScale * c.size / size * 100).toInt().coerceIn(50, 200)
            val s = ConvRun("", c.substitute ?: c.font.family, size, c.font.bold, c.font.italic || c.slanted, under, c.color and 0xFFFFFF or (0xFF shl 24), script,
                spacing = Math.round(c.tracking * 20) / 20.0, scale = scale, mono = c.font.monospace, serif = c.font.serif)
            val p = prev
            if (p != null && sb.isNotEmpty() && sb.last() != ' ') {
                val gap = c.x - (p.x + p.width)
                if (gap > min(p.size, c.size) * 0.18) sb.append(' ')
            }
            if (style == null || !same(style, s)) {
                // A space before a style change stays with the earlier run.
                flush(); style = s
            }
            sb.append(c.text)
            if (c.substitute != null) StandardWidths.width(c.substitute!!, c.font.bold, c.font.italic, c.text)?.let { faceWidth += it * size / 1000; pdfWidth += c.glyphWidth }
            prev = c
        }
        flush()
    }

    private fun same(a: ConvRun, b: ConvRun) = a.font == b.font && a.size == b.size && a.bold == b.bold && a.italic == b.italic &&
        a.underline == b.underline && a.color == b.color && a.script == b.script && a.spacing == b.spacing && a.scale == b.scale

    /**
     * Letter spacing, per font and size on the page: how much further on than its width
     * each character's neighbor starts, as the tighter lines have it (justified lines
     * spread wider, kerned pairs come closer). Within a tenth of an em; more is a font
     * whose widths are wrong, not spacing.
     */
    private fun tracking(lines: List<Line>) {
        val gaps = HashMap<Pair<PdfFont, Long>, MutableList<Double>>()
        for (l in lines) for (i in 0 until l.chars.size - 1) {
            val a = l.chars[i]; val b = l.chars[i + 1]
            if (a.isSpace || b.isSpace || a.text.isBlank() || b.text.isBlank() || a.font !== b.font || a.size != b.size || abs(a.angle) >= 3 || a.font.family.isEmpty()) continue
            val d = (b.x - a.x - a.glyphWidth) / a.size
            if (d > -0.3 && d < 0.3) gaps.getOrPut(a.font to Math.round(a.size * 100)) { ArrayList() } += d
        }
        val found = gaps.mapValues { (_, v) -> if (v.size < 20) 0.0 else v.sorted()[v.size / 4].takeIf { abs(it) in 0.01..0.1 } ?: 0.0 }
        for (l in lines) for (c in l.chars) found[c.font to Math.round(c.size * 100)]?.let { c.tracking = it * c.size }
        // A font readers seldom have, setting no CJK text here, is set in the standard face like it.
        val cjk = HashSet<PdfFont>()
        for (l in lines) for (c in l.chars) if (c.text.any { it in '\u1100'..'\u11FF' || it in '\u2E80'..'\u9FFF' || it in '\uAC00'..'\uD7AF' || it in '\uF900'..'\uFAFF' || it in '\uFF00'..'\uFFEF' }) cjk += c.font
        val standard = IdentityHashMap<PdfFont, String?>()
        for (l in lines) for (c in l.chars) c.substitute = standard.getOrPut(c.font) {
            val f = c.font
            if (f.family.isEmpty() || f in cjk || PdfFont.installed(f.family) || f.family in STANDARD) null
            else if (f.monospace) "Courier New" else if (f.serif) "Times New Roman" else "Arial"
        }
    }

    private val STANDARD = setOf("Arial", "Times New Roman", "Courier New")

    // ---- blocks and reading order ----------------------------------------------

    private sealed class Item {
        abstract val x0: Double; abstract val y0: Double; abstract val x1: Double; abstract val y1: Double
        class Text(val lines: List<Line>) : Item() {
            override val x0 = lines.minOf { it.x0 }; override val y0 = lines.minOf { it.top }
            override val x1 = lines.maxOf { it.x1 }; override val y1 = lines.maxOf { it.bottom }
        }
        class Grid(val table: TableGrid) : Item() {
            override val x0 = table.x0; override val y0 = table.y0; override val x1 = table.x1; override val y1 = table.y1
        }
        class Picture(val picture: ConvImage) : Item() {
            override val x0 = picture.x; override val y0 = picture.y; override val x1 = picture.x + picture.width; override val y1 = picture.y + picture.height
        }
        /** Parts standing side by side, left to right, each in reading order. */
        class Columns(val parts: List<List<Item>>) : Item() {
            override val x0 = parts.first().minOf { it.x0 }; override val y0 = parts.minOf { p -> p.minOf { it.y0 } }
            override val x1 = parts.last().maxOf { it.x1 }; override val y1 = parts.maxOf { p -> p.maxOf { it.y1 } }
        }
    }

    /** Lines into blocks: each line joins the block above it when close below it, overlapping it and of about its size. */
    private fun blocks(lines: List<Line>): List<List<Line>> {
        val blocks = ArrayList<MutableList<Line>>()
        val byY = lines.sortedBy { it.baseline }
        val rank = java.util.IdentityHashMap<Line, Int>().also { m -> byY.forEachIndexed { i, l -> m[l] = i } }
        val textRight = lines.maxOfOrNull { it.x1 } ?: 0.0
        // The gutter between columns: what's left of the gap between [a] and [b] beside
        // the lines near [l] (up to a line across it, a heading over both columns),
        // still half an em wide, with lines on both sides.
        fun gutter(l: Line, a: Double, b: Double): Boolean {
            val i0 = rank.getValue(l)
            // Each way on its own (a title above both columns narrows nothing below).
            return intArrayOf(-1, 1).any { dir ->
                var lo = a; var hi = b
                var left = 0; var right = 0
                var i = i0 + dir
                while (i in byY.indices && abs(byY[i].baseline - l.baseline) < l.size * 6) {
                    val o = byY[i]
                    i += dir
                    when {
                        o.x1 <= lo -> left++
                        o.x0 >= hi -> right++
                        o.x0 < lo && o.x1 < hi -> { lo = o.x1; left++ }
                        o.x0 > lo && o.x1 > hi -> { hi = o.x0; right++ }
                        else -> break
                    }
                }
                hi - lo > l.size * 0.5 && left >= 2 && right >= 2
            }
        }
        for (l in lines.sortedWith(compareBy<Line> { it.baseline }.thenBy { it.x0 })) {
            var target: MutableList<Line>? = null
            for (b in blocks.asReversed()) {
                val last = b.last()
                val gap = l.top - last.bottom
                val overlap = min(l.x1, b.maxOf { it.x1 }) - max(l.x0, b.minOf { it.x0 })
                val sizeOk = l.size / last.size in 0.7..1.43
                // Another piece of a line in this block (after a wide gap, a tab) joins it, but not a
                // column's line beside the other column's (a bullet or a number beside its text leaves a
                // gap down the list too, but has no column on its left). Far apart, only after a short
                // piece (a table's rows without rulings) or up to the text's right edge (a right tab
                // stop), not after a paragraph's line (a caption beside it).
                val sameRow = b.any { o ->
                    abs(o.baseline - l.baseline) < o.size * 0.3 && o.x1 <= l.x0 + o.size && run {
                        val far = l.x0 - o.x1 >= max(o.size, l.size) * 6
                        (!far || o.x1 - o.x0 < o.size * 15 || l.x1 > textRight - l.size) &&
                            !((far || max(l.x1 - l.x0, o.x1 - o.x0) > l.size * 15 && o.x1 - o.x0 > o.size * 4) && gutter(l, o.x1, l.x0))
                    }
                }
                if (sameRow) { target = b; break }
                if (abs(l.baseline - last.baseline) < last.size * 0.3) continue
                // A line under a row of separate labels (under pictures), reaching under another of them, follows none
                // (raised or lowered pieces of a formula, smaller than the line, aren't labels).
                val underRow = last.size > l.size * 0.8 &&
                    blocks.any { o -> o !== b && abs(o.last().baseline - last.baseline) < last.size * 0.3 && l.x0 < o.last().x1 && l.x1 > o.last().x0 }
                // Left-aligned text under centered lines (an author's name and address) starts another block: a line
                // off the block's center, starting well left of it, or under lines centered on one another.
                val bx0 = b.minOf { it.x0 }; val bx1 = b.maxOf { it.x1 }
                val centers = b.map { (it.x0 + it.x1) / 2 }
                val stacked = b.size >= 2 && centers.max() - centers.min() < l.size * 0.5 && b.maxOf { it.x0 } - bx0 > l.size
                val offCenter = abs((l.x0 + l.x1) / 2 - (bx0 + bx1) / 2) > l.size
                val outLeft = offCenter && (stacked || l.x0 < bx0 - l.size * 2)
                if (gap < max(l.size, last.size) * 1.0 && gap > -last.size * 0.5 && overlap > -l.size * 0.5 && sizeOk && !underRow && !outLeft) { target = b; break }
                if (l.top - b.last().bottom > l.size * 3) continue
            }
            if (target == null) blocks += mutableListOf(l) else target += l
        }
        // Lines in rows (pieces of one line, after a gap, share its row), each row in x order.
        return blocks.map { b ->
            val sorted = b.sortedBy { it.baseline }
            var row = 0
            val rowOf = HashMap<Line, Int>()
            for ((i, l) in sorted.withIndex()) {
                if (i > 0 && l.baseline - sorted[i - 1].baseline > min(l.size, sorted[i - 1].size) * 0.3) row++
                rowOf[l] = row
            }
            sorted.sortedWith(compareBy<Line> { rowOf[it] }.thenBy { it.x0 })
        }
    }

    /**
     * Reading order: cut the region along an empty vertical band crossing it all
     * (columns, kept side by side as one [Item.Columns]), else along its widest
     * empty horizontal band (read top to bottom), and so on in each part.
     */
    private fun order(items: List<Item>, depth: Int = 0): List<Item> {
        if (items.size <= 1 || depth > 40) return items.sortedBy { it.y0 }
        // Vertical cut: the widest x gap crossing all items.
        val byX = items.sortedBy { it.x0 }
        var reach = byX[0].x1
        var best: Pair<Int, Double>? = null
        for (i in 1 until byX.size) {
            val gap = byX[i].x0 - reach
            if (best == null || gap > best.second) best = i to gap
            reach = max(reach, byX[i].x1)
        }
        /** The parts either side of the vertical cut, side by side, if they run side by side. */
        fun columns(): List<Item>? {
            val l = byX.subList(0, best!!.first); val r = byX.subList(best.first, byX.size)
            // Columns run side by side: most of the smaller side's items share heights with the other's.
            // Items to the right of others but stacked between them (labels and their formulas) aren't columns.
            val (small, big) = if (l.size <= r.size) l to r else r to l
            val beside = small.count { s -> big.any { b -> min(s.y1, b.y1) - max(s.y0, b.y0) > 0 } }
            if (l.isEmpty() || r.isEmpty() || beside < small.size * 0.5) return null
            fun parts(side: List<Item>) = order(side, depth + 1).let { (it.singleOrNull() as? Item.Columns)?.parts ?: listOf(it) }
            return listOf(Item.Columns(parts(l) + parts(r)))
        }
        // Text needs a clear gap; pictures alone may touch (a row of them).
        val minGap = if (items.all { it is Item.Picture }) -2.0 else 9.0
        if (best != null && best.second > minGap) columns()?.let { return it }
        val byY = items.sortedBy { it.y0 }
        var bottom = byY[0].y1
        var bestY: Pair<Int, Double>? = null
        for (i in 1 until byY.size) {
            val gap = byY[i].y0 - bottom
            if (gap > 0 && (bestY == null || gap > bestY.second)) bestY = i to gap
            bottom = max(bottom, byY[i].y1)
        }
        if (bestY != null) {
            val cut = byY[bestY.first].y0
            val (t, b) = items.partition { it.y1 <= cut + 0.01 }
            if (t.isNotEmpty() && b.isNotEmpty()) return order(t, depth + 1) + order(b, depth + 1)
        }
        // Neither cut: parts that only touch (a line running up to the next column) still stand side by side,
        // or else one above the other.
        if (best != null && best.second > -2.0) columns()?.let { return it }
        var reachY = byY[0].y1
        var touch: Pair<Int, Double>? = null
        for (i in 1 until byY.size) {
            val gap = byY[i].y0 - reachY
            if (gap > -2.0 && (touch == null || gap > touch.second)) touch = i to gap
            reachY = max(reachY, byY[i].y1)
        }
        if (touch != null) return order(byY.subList(0, touch.first), depth + 1) + order(byY.subList(touch.first, byY.size), depth + 1)
        return byY
    }

    // ---- paragraphs -----------------------------------------------------------

    /** A line's text with a space where a gap was left for one. */
    private fun spaced(l: Line): String {
        val sb = StringBuilder()
        var prev: PdfChar? = null
        for (c in l.chars) {
            if (prev != null && !c.isSpace && !prev.isSpace && c.x - (prev.x + prev.width) > c.size * 0.2) sb.append(' ')
            sb.append(c.text); prev = c
        }
        return sb.toString()
    }

    private val BULLET = Regex("^\\s*([•●○◦▪■□◆◇▶▷►‣⁃∙·\\-–—※✓✔➢➤]|\\(?\\d{1,3}[.)]|\\(?[a-zA-Z가-힣][.)]|[①-⑳]|[ⅰ-ⅹⅠ-Ⅹ][.)]?)\\s")

    /**
     * A block's lines into paragraphs, each with the vertical range it covers.
     * A paragraph ends where the spacing grows, a line ends well short of the
     * block's right edge, the next line is indented, starts a list item, or its
     * size or weight changes.
     */
    /** A paragraph's line box: its baseline pitch, or for one line about the text's own height. */
    private fun lineBox(pitch: Double?, size: Double) = pitch ?: (size * 1.2)

    private fun paragraphs(
        lines: List<Line>, colLeft: Double, colRight: Double, rulings: List<Ruling>, wrapRight: Double = colRight,
        fills: List<io.github.baeseungwon1010.officecanvas.pdf.PdfRect> = emptyList()
    ): List<Pair<ConvParagraph, Pair<Double, Double>>> {
        // Pieces of one line join it (the pieces of a line split at a wide gap, e.g. a tab).
        val rows = ArrayList<MutableList<Line>>()
        for (l in lines) {
            val r = rows.lastOrNull()
            if (r != null && abs(r.last().baseline - l.baseline) < l.size * 0.3) r += l else rows += mutableListOf(l)
        }
        val blockLeft = rows.minOf { r -> r.first().x0 }
        val blockRight = rows.maxOf { r -> r.last().x1 }
        val pitches = rows.zipWithNext { a, b -> b.first().baseline - a.first().baseline }.filter { it > 0 }
        val typicalPitch = pitches.sorted().getOrNull(pitches.size / 2)
        val groups = ArrayList<MutableList<MutableList<Line>>>()
        for ((i, row) in rows.withIndex()) {
            val g = groups.lastOrNull()
            val prev = rows.getOrNull(i - 1)
            val start = g == null || prev == null || run {
                val first = row.first()
                val pf = prev.first()
                val pitch = first.baseline - pf.baseline
                val size = first.size
                val bigGap = typicalPitch != null && pitch > typicalPitch * 1.35 && pitch > size * 1.6
                // The line before ended though this line's first word would have fitted after it.
                // (A word ends at a space drawn or at a gap left for one.)
                val word = ArrayList<PdfChar>()
                for (c in first.chars) {
                    if (c.isSpace || c.text.isBlank()) break
                    val p = word.lastOrNull()
                    if (p != null && c.x - (p.x + p.width) > c.size * 0.2) break
                    word += c
                }
                val firstWord = if (word.isEmpty()) size else word.last().x + word.last().width - word.first().x
                val shortPrev = wrapRight - prev.last().x1 > firstWord + size * 0.6 && prev.last().x1 < blockRight - size * 0.5 ||
                    prev.last().x1 < blockRight - size * 4
                val indented = first.x0 > blockLeft + size * 0.8 && pf.x0 <= blockLeft + size * 0.3
                val bullet = BULLET.containsMatchIn(row.joinToString(" ") { spaced(it) })
                // Code doesn't wrap: each line of a monospaced listing is its own.
                val code = first.chars.first().font.monospace && pf.chars.first().font.monospace
                val styleChange = abs(first.size - pf.size) > 0.6 || first.chars.first().font.bold != pf.chars.first().font.bold && first.chars.count { it.font.bold } * 2 > first.chars.size
                bigGap || shortPrev || indented || bullet || styleChange || code
            }
            if (start) groups += mutableListOf(row) else g!! += row
        }
        val out = ArrayList<Pair<ConvParagraph, Pair<Double, Double>>>()
        var prevBottom: Double? = null
        var prevBase: Double? = null
        for ((gi, g) in groups.withIndex()) {
            val runs = ArrayList<ConvRun>()
            val tabs = ArrayList<Double>()
            val rightTabs = ArrayList<Double>()
            for ((k, row) in g.withIndex()) {
                if (k > 0) {
                    // Lines of one paragraph join with a space (a hyphen at a line's end joins the word).
                    val last = runs.lastOrNull()
                    if (last != null) {
                        val t = last.text
                        val narrow = blockRight - blockLeft < row.first().size * 4
                        if (t.endsWith("-") && t.length > 1 && t[t.length - 2].isLetter() && row.first().text.firstOrNull()?.isLowerCase() == true)
                            runs[runs.size - 1] = last.copyText(t.dropLast(1))
                        // A column a few characters wide wraps between characters.
                        else if (narrow) {}
                        else if (!t.endsWith(" ")) runs[runs.size - 1] = last.copyText("$t ")
                    }
                }
                for ((j, piece) in row.withIndex()) {
                    if (j > 0) {
                        // A wide gap mid-line: a tab.
                        val last = runs.lastOrNull()
                        if (last != null) runs[runs.size - 1] = last.copyText(last.text.trimEnd() + "\t")
                        // The last piece ending at the column's edge is right-aligned there.
                        if (j == row.size - 1 && piece.x1 > colRight - piece.size * 0.5) rightTabs += min(piece.x1, colRight) - colLeft
                        else tabs += piece.x0 - colLeft
                    }
                    runs(piece, rulings, runs)
                }
            }
            val firstRow = g.first(); val first = firstRow.first()
            val others = g.drop(1)
            // Where the text starts: spaces before it (code indented with them) are dropped, so they indent it.
            fun start(l: Line) = l.chars.firstOrNull { !it.isSpace && it.text.isNotBlank() }?.x ?: l.x0
            val left = (if (others.isNotEmpty()) others.minOf { start(it.first()) } else start(first)) - colLeft
            val firstLine = start(first) - colLeft - left
            val rights = g.map { it.last().x1 }
            val lefts = g.map { start(it.first()) }
            val colW = colRight - colLeft
            val center = (lefts.first() + rights.first()) / 2
            val align = when {
                g.size == 1 && abs(center - (colLeft + colRight) / 2) < first.size * 1.5 && lefts.first() - colLeft > first.size * 2 -> ConvAlign.CENTER
                g.size == 1 && colRight - rights.first() < first.size * 1.0 && lefts.first() - colLeft > colW * 0.3 -> ConvAlign.RIGHT
                g.size > 1 && g.all { r -> abs((r.first().x0 + r.last().x1) / 2 - center) < first.size } && lefts.distinct().size > 1 && lefts.first() - colLeft > first.size * 2 -> ConvAlign.CENTER
                g.size > 2 && rights.dropLast(1).let { rs -> rs.max() - rs.min() < first.size * 0.5 } -> ConvAlign.JUSTIFY
                else -> ConvAlign.LEFT
            }
            // A fill behind the paragraph's text (a bar, a band) shades it.
            val textX0 = g.minOf { it.first().x0 }; val textX1 = g.maxOf { it.last().x1 }
            // (One box may hold several paragraphs: a code block, a note.)
            val lastBase = g.last().first().baseline
            val shade = fills.filter { f -> f.x <= textX0 + 1 && f.x + f.w >= textX1 - 1 && f.y <= first.baseline - first.size * 0.5 && f.y + f.h >= lastBase }
                .minByOrNull { it.w * it.h }
            // A shaded line on its own the usual pitch from the one before or after, on a band that tall (a code
            // listing's), is as tall as that pitch, not set apart by space before it, which the shading wouldn't fill.
            val steps = listOfNotNull(prevBase?.let { first.baseline - it }, groups.getOrNull(gi + 1)?.let { it.first().first().baseline - g.last().first().baseline })
            val pitch = g.zipWithNext { a, b -> b.first().baseline - a.first().baseline }.filter { it > 0 }.sorted().let { it.getOrNull(it.size / 2) }
                ?: typicalPitch?.takeIf { t -> shade != null && shade.h > t * 0.9 && steps.any { abs(it - t) < t * 0.08 } && t < first.size * 2.5 }
            val box = lineBox(pitch, g.maxOf { r -> r.maxOf { it.size } })
            // The paragraph's box: its first baseline at 80 % of its first line box, its last 20 % above the bottom.
            val boxTop = first.baseline - box * 0.8
            val boxBottom = g.last().first().baseline + box * 0.2
            val spaceBefore = prevBottom?.let { (boxTop - it).coerceAtLeast(0.0) } ?: 0.0
            // Shading starts at the indent: a shaded line indented in its box starts at the box, its text a first-line indent in.
            val shadeLeft = shade?.let { it.x - colLeft }?.takeIf { g.size == 1 && it < left && align == ConvAlign.LEFT }
            val para = ConvParagraph(
                runs.filter { it.text.isNotEmpty() }.let { trimEnd(it) },
                align,
                // A centered or right-aligned paragraph is placed by its alignment, not an indent.
                leftIndent = if (align == ConvAlign.CENTER || align == ConvAlign.RIGHT) 0.0 else (shadeLeft ?: left).coerceAtLeast(-colLeft),
                rightIndent = if (align == ConvAlign.RIGHT) (colRight - rights.max()).coerceIn(0.0, colW * 0.5) else 0.0,
                firstLine = if (align == ConvAlign.CENTER || align == ConvAlign.RIGHT) 0.0 else firstLine + (shadeLeft?.let { left - it.coerceAtLeast(-colLeft) } ?: 0.0),
                spaceBefore = spaceBefore,
                lineHeight = box,
                tabs = tabs.map { Math.round(it * 2) / 2.0 }.distinct().sorted(),
                shading = shade?.color,
                rightTabs = rightTabs.map { Math.round(it * 2) / 2.0 }.distinct().sorted()
            )
            out += para to (boxTop to boxBottom)
            prevBottom = boxBottom
            prevBase = g.last().first().baseline
        }
        return out
    }

    private fun trimEnd(runs: List<ConvRun>): List<ConvRun> {
        if (runs.isEmpty()) return runs
        val last = runs.last()
        return runs.dropLast(1) + last.copyText(last.text.trimEnd())
    }

    private fun ConvRun.copyText(t: String) = ConvRun(t, font, size, bold, italic, underline, color, script, field, spacing, scale, mono, serif)

    // ---- tables -----------------------------------------------------------------

    /** A ruled table found on the page: its grid lines and what falls in it. */
    class TableGrid(val xs: List<Double>, val ys: List<Double>, val hs: List<Ruling>, val vs: List<Ruling>, val fills: List<io.github.baeseungwon1010.officecanvas.pdf.PdfRect>) {
        val x0 = xs.first(); val x1 = xs.last(); val y0 = ys.first(); val y1 = ys.last()
        val lines = ArrayList<Line>()
        /** Tables inside this one's cells. */
        val children = ArrayList<TableGrid>()
        /** This table and those nested in it. */
        fun nested(): List<TableGrid> = listOf(this) + children.flatMap { it.nested() }
        fun encloses(o: TableGrid) = x0 <= o.x0 + 1 && x1 >= o.x1 - 1 && y0 <= o.y0 + 1 && y1 >= o.y1 - 1 && (x1 - x0) * (y1 - y0) > (o.x1 - o.x0) * (o.y1 - o.y0)
        val pictures = ArrayList<ConvImage>()

        fun build(colLeft: Double, colRight: Double, rulings: List<Ruling>, spaceBefore: Double): ConvTable {
            val nr = ys.size - 1; val nc = xs.size - 1
            // Cells merge where the ruling between them is missing.
            fun vCovered(x: Double, ya: Double, yb: Double) = covered(vs.filter { abs(it.x0 - x) < 1.5 }.map { it.y0 to it.y1 }, ya, yb)
            fun hCovered(y: Double, xa: Double, xb: Double) = covered(hs.filter { abs(it.y0 - y) < 1.5 }.map { it.x0 to it.x1 }, xa, xb)
            val owner = Array(nr) { IntArray(nc) { -1 } }
            val cells = ArrayList<IntArray>() // r, c, rowSpan, colSpan
            for (r in 0 until nr) for (c in 0 until nc) {
                if (owner[r][c] >= 0) continue
                var cs = 1
                while (c + cs < nc && !vCovered(xs[c + cs], ys[r], ys[r + 1]) && owner[r][c + cs] < 0) cs++
                var rs = 1
                while (r + rs < nr && (c until c + cs).all { k -> !hCovered(ys[r + rs], xs[k], xs[k + 1]) && owner[r + rs][k] < 0 }) rs++
                for (rr in r until r + rs) for (cc in c until c + cs) owner[rr][cc] = cells.size
                cells += intArrayOf(r, c, rs, cs)
            }
            val out = cells.map { (r, c, rs, cs) ->
                val cx0 = xs[c]; val cx1 = xs[c + cs]; val cy0 = ys[r]; val cy1 = ys[r + rs]
                val inside = lines.filter { l -> (l.x0 + l.x1) / 2 in cx0..cx1 && l.baseline - l.size * 0.3 in cy0..cy1 }
                // Paragraphs and pictures in the cell, top to bottom.
                val placed = ArrayList<Pair<Double, ConvBlock>>()
                for (b in blocks(inside)) {
                    var first = true
                    for ((p, bounds) in paragraphs(b, cx0 + 1.5, cx1 - 1.5, rulings)) {
                        placed += bounds.first to if (first) p.withSpaceBefore(0.0) else p
                        first = false
                    }
                }
                for (p in pictures) if (p.x + p.width / 2 in cx0..cx1 && p.y + p.height / 2 in cy0..cy1) {
                    placed += p.y to ConvParagraph(emptyList(), ConvAlign.CENTER, image = ConvImage(p.data, p.png, p.pixelWidth, p.pixelHeight,
                        min(p.width, cx1 - cx0 - 2), p.height * min(1.0, (cx1 - cx0 - 2) / p.width), 0.0, 0.0))
                }
                // Tables in the cell, each built in it.
                for (t in children) if ((t.x0 + t.x1) / 2 in cx0..cx1 && (t.y0 + t.y1) / 2 in cy0..cy1) placed += t.y0 to t.build(cx0 + 1.5, cx1 - 1.5, rulings, 0.0)
                val blocks = placed.sortedBy { it.first }.map { it.second }
                // Vertical position of the text in the cell.
                val textTop = inside.minOfOrNull { it.top }; val textBottom = inside.maxOfOrNull { it.bottom }
                val vAlign = if (textTop == null || textBottom == null) 0 else {
                    val above = textTop - cy0; val below = cy1 - textBottom
                    when { abs(above - below) < max(3.0, (cy1 - cy0) * 0.1) && above > 4 -> 1; below < above * 0.4 -> 2; else -> 0 }
                }
                val fill = fills.filter { f -> f.x <= cx0 + 2 && f.x + f.w >= cx1 - 2 && f.y <= cy0 + 2 && f.y + f.h >= cy1 - 2 && f.w < (x1 - x0) * 1.05 }
                    .minByOrNull { it.w * it.h }?.color?.takeIf { !isWhite(it) }
                fun border(found: Ruling?) = found?.takeIf { it.width > 0 }?.let { ConvBorder(it.width.coerceIn(0.25, 6.0), it.color) }
                fun hBorder(y: Double) = border(hs.firstOrNull { abs(it.y0 - y) < 1.5 && it.x0 <= (cx0 + cx1) / 2 && it.x1 >= (cx0 + cx1) / 2 })
                fun vBorder(x: Double) = border(vs.firstOrNull { abs(it.x0 - x) < 1.5 && it.y0 <= (cy0 + cy1) / 2 && it.y1 >= (cy0 + cy1) / 2 })
                ConvCell(r, c, rs, cs, blocks, fill, hBorder(cy0), hBorder(cy1), vBorder(cx0), vBorder(cx1), vAlign)
            }
            return ConvTable(x0 - colLeft, xs.zipWithNext { a, b -> b - a }, ys.zipWithNext { a, b -> b - a }, out, spaceBefore)
        }

        private fun covered(spans: List<Pair<Double, Double>>, a: Double, b: Double): Boolean {
            if (b - a <= 0) return true
            var have = 0.0
            for ((s, e) in spans) have += (min(e, b) - max(s, a)).coerceAtLeast(0.0)
            return have >= (b - a) * 0.6
        }
    }

    /**
     * Ruled tables: horizontal and vertical rulings that cross each other, in groups;
     * a group with at least two of each that holds text is a table whose columns and
     * rows are its distinct ruling positions.
     */
    private fun tables(rulings: List<Ruling>, lines: List<Line>, content: PdfPageContent): List<TableGrid> {
        val hs = merge(rulings.filter { it.horizontal }, true)
        val vs = merge(rulings.filter { !it.horizontal }, false)
        if (hs.size + vs.size > 20_000) return emptyList()
        if (vs.size < 2) {
            val open = if (hs.size >= 3) openTables(hs, lines, content) else emptyList()
            val taken = open.flatMapTo(HashSet()) { it.lines }
            return open + streamTables(lines.filter { it !in taken }, hs, content)
        }
        // Union-find over crossings.
        val all = hs + vs
        val parent = IntArray(all.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        val tol = 2.0
        for ((i, h) in hs.withIndex()) for ((j, v) in vs.withIndex()) {
            if (v.x0 >= h.x0 - tol && v.x0 <= h.x1 + tol && h.y0 >= v.y0 - tol && h.y0 <= v.y1 + tol) {
                val a = find(i); val b = find(hs.size + j); if (a != b) parent[a] = b
            }
        }
        val groups = all.indices.groupBy { find(it) }
        val out = ArrayList<TableGrid>()
        for ((_, members) in groups) {
            val gh = members.filter { it < hs.size }.map { hs[it] }
            val gv = members.filter { it >= hs.size }.map { vs[it - hs.size] }
            if (gh.size < 2 || gv.size < 2) continue
            val xs = cluster(gv.map { it.x0 } + listOf(gh.minOf { it.x0 }, gh.maxOf { it.x1 }))
            val ys = cluster(gh.map { it.y0 } + listOf(gv.minOf { it.y0 }, gv.maxOf { it.y1 }))
            if (xs.size < 2 || ys.size < 2) continue
            val width = xs.last() - xs.first(); val height = ys.last() - ys.first()
            if (width < 20 || height < 8) continue
            val inside = lines.filter { l -> (l.x0 + l.x1) / 2 in xs.first()..xs.last() && l.baseline - l.size * 0.3 in ys.first()..ys.last() }
            // A frame with nothing in it is a drawing, not a table.
            if (inside.isEmpty()) continue
            // Nor is a grid of mostly empty cells: a chart's gridlines, with a legend in one.
            val cells = (xs.size - 1) * (ys.size - 1)
            if (cells >= 12) {
                val used = inside.map { l ->
                    val cx = (l.x0 + l.x1) / 2; val cy = l.baseline - l.size * 0.3
                    xs.indexOfLast { it <= cx } to ys.indexOfLast { it <= cy }
                }.toSet().size
                if (used * 10 < cells) continue
            }
            val grid = TableGrid(xs, ys, gh, gv, content.rects)
            grid.lines += inside
            out += grid
        }
        // A frame around (most of) the page, if only divided across, is a page border;
        // a one-cell frame around a table is the table's box. A table inside another's
        // cell is built in that cell.
        val pageArea = content.width * content.height
        val kept = out.filter { t ->
            !(t.xs.size == 2 && ((t.x1 - t.x0) * (t.y1 - t.y0) > pageArea * 0.45 || t.ys.size == 2 && out.any { o -> o !== t && t.encloses(o) }))
        }
        for (t in kept) t.lines.clear()
        for (l in lines) kept.filter { t -> (l.x0 + l.x1) / 2 in t.x0..t.x1 && l.baseline - l.size * 0.3 in t.y0..t.y1 }
            .minByOrNull { (it.x1 - it.x0) * (it.y1 - it.y0) }?.lines?.add(l)
        // Each table under the smallest one around it.
        for (t in kept) kept.filter { o -> o !== t && o.encloses(t) }.minByOrNull { (it.x1 - it.x0) * (it.y1 - it.y0) }?.children?.add(t)
        for (t in kept) t.children.retainAll { c -> c.nested().any { it.lines.isNotEmpty() } }
        val ruled = kept.filter { t -> t.nested().any { it.lines.isNotEmpty() } && kept.none { o -> o !== t && o.encloses(t) } }
        val found = ruled + openTables(hs.filter { h -> ruled.none { t -> h.y0 >= t.y0 - 1 && h.y0 <= t.y1 + 1 && h.x0 >= t.x0 - 2 && h.x1 <= t.x1 + 2 } }, lines, content)
        val taken = HashSet<Line>().also { s -> for (t in found) for (n in t.nested()) s += n.lines }
        return found + streamTables(lines.filter { it !in taken }, hs, content)
    }

    /**
     * Tables with no rules down and at most a rule or two across (one under the
     * heading): three or more columns of short pieces on shared baselines (two under
     * a rule), three or more rows deep. A line on its own close under a row continues one of that
     * row's cells.
     */
    private fun streamTables(lines: List<Line>, hs: List<Ruling>, content: PdfPageContent): List<TableGrid> {
        fun ink(l: Line) = l.chars.filter { !it.isSpace && it.text.isNotBlank() }
        fun inkX0(l: Line) = ink(l).minOfOrNull { it.x } ?: l.x0
        fun inkX1(l: Line) = ink(l).maxOfOrNull { it.x + it.width } ?: l.x1
        val rows = ArrayList<MutableList<Line>>()
        for (l in lines.filter { l -> l.chars.all { abs(it.angle) < 3 } }.sortedBy { it.baseline }) {
            val last = rows.lastOrNull()
            if (last != null && abs(last[0].baseline - l.baseline) < min(last[0].size, l.size) * 0.3) last += l else rows += mutableListOf(l)
        }
        rows.forEach { r -> r.sortBy { it.x0 } }
        val out = ArrayList<TableGrid>()
        var i = 0
        while (i < rows.size) {
            if (rows[i].size < 2) { i++; continue }
            val run = arrayListOf<List<Line>>(rows[i])
            var j = i + 1
            while (j < rows.size) {
                val prev = run.last(); val r = rows[j]
                val em = max(prev[0].size, r[0].size)
                val step = r[0].baseline - prev[0].baseline
                if (step > em * 2.6) break
                // A line on its own continues a cell: under the first column only if it ends before the second.
                val underFirst = inkX1(r[0]) < run[0][1].x0 - em
                if (r.size < 2 && (step > em * 1.5 || inkX0(r[0]) < run[0].minOf { inkX0(it) } + em && !underFirst)) break
                // Running text ends it.
                if (r.any { inkX1(it) - inkX0(it) > em * 18 } || run.size >= 200) break
                run += r; j++
            }
            // The rows that follow may be something else: the longest run that is a table.
            var end = run.size
            var table: TableGrid? = null
            while (end >= 3 && table == null) { table = streamTable(run.subList(0, end), hs, content, ::inkX0, ::inkX1); if (table == null) end-- }
            if (table == null) { i++; continue }
            out += table
            i += end
        }
        return out
    }

    private val LIST_MARK = Regex("[\\p{So}\\p{Sm}\\p{Pd}\\p{Po}•◦▪▫■□●○◆◇►▶➢✓✔-]|\\(?[0-9A-Za-z가-힣]{1,3}[.)]")

    private fun streamTable(run: List<List<Line>>, hs: List<Ruling>, content: PdfPageContent, inkX0: (Line) -> Double, inkX1: (Line) -> Double): TableGrid? {
        val multi = run.filter { it.size >= 2 }
        if (multi.size < 3) return null
        val pieces = run.flatten()
        val em = pieces.map { it.size }.sorted()[pieces.size / 2]
        // Short pieces, not running text; and not a code listing.
        if (pieces.any { inkX1(it) - inkX0(it) > em * 18 }) return null
        if (pieces.sumOf { l -> l.chars.count { it.font.monospace } } * 2 > pieces.sumOf { it.chars.size }) return null
        val x0 = pieces.minOf { inkX0(it) }; val x1 = pieces.maxOf { inkX1(it) }
        // Columns: where no row's text is, an em or more wide.
        val bins = ((x1 - x0) * 2).toInt().coerceIn(1, 20_000)
        val cover = IntArray(bins)
        for (r in multi) {
            val hit = BooleanArray(bins)
            for (l in r) for (b in ((inkX0(l) - x0) * 2).toInt().coerceIn(0, bins - 1)..((inkX1(l) - x0) * 2).toInt().coerceIn(0, bins - 1)) hit[b] = true
            for (b in 0 until bins) if (hit[b]) cover[b]++
        }
        val seps = ArrayList<Double>()
        var b = 0
        while (b < bins) {
            if (cover[b] == 0) {
                var e = b
                while (e + 1 < bins && cover[e + 1] == 0) e++
                // The boundary just before the next column's text, which then starts its cell.
                if (b > 0 && e < bins - 1 && e - b >= em * 2) seps += x0 + (e + 1) / 2.0 - 2
                b = e + 1
            } else b++
        }
        if (seps.isEmpty()) return null
        val starts = listOf(x0 - 1) + seps
        fun column(l: Line) = starts.indexOfLast { it <= (inkX0(l) + inkX1(l)) / 2 }
        // No piece across a column boundary, and text in each column in most rows.
        if (pieces.any { l -> seps.any { x -> inkX0(l) < x && inkX1(l) > x } }) return null
        // Bullets or numbers before text are a list.
        if (pieces.filter { column(it) == 0 }.all { LIST_MARK.matches(it.text.trim()) }) return null
        if ((0..seps.size).any { c -> multi.count { r -> r.any { column(it) == c } } * 5 < multi.size * 3 }) return null
        // Rows: each row of several pieces starts one; a line on its own continues it.
        val bands = ArrayList<MutableList<Line>>()
        for (r in run) if (r.size >= 2 || bands.isEmpty()) bands += r.toMutableList() else bands.last() += r
        val tops = bands.map { r -> r.minOf { it.top } }; val bottoms = bands.map { r -> r.maxOf { it.bottom } }
        val across = hs.filter { h -> min(h.x1, x1) - max(h.x0, x0) > (x1 - x0) * 0.5 }
        fun rule(a: Double, b: Double) = across.filter { it.y0 in a..b }.minByOrNull { abs(it.y0 - (a + b) / 2) }
        val used = ArrayList<Ruling>()
        val ys = ArrayList<Double>()
        ys += rule(tops[0] - em * 1.2, tops[0])?.also { used += it }?.y0 ?: (tops[0] - 2)
        for (k in 1 until bands.size) ys += rule(bottoms[k - 1], tops[k])?.also { used += it }?.y0 ?: ((bottoms[k - 1] + tops[k]) / 2)
        ys += rule(bottoms.last(), bottoms.last() + em * 1.2)?.also { used += it }?.y0 ?: (bottoms.last() + 2)
        if (ys.zipWithNext().any { (a, c) -> c <= a }) return null
        // Two columns only under a rule across them (pairs of words otherwise are often just text).
        if (seps.size < 2 && used.isEmpty()) return null
        val left = min(x0 - 2, used.minOfOrNull { it.x0 } ?: x0); val right = max(x1 + 2, used.maxOfOrNull { it.x1 } ?: x1)
        val xs = listOf(left) + seps + listOf(right)
        // Invisible boundaries everywhere, so no cells merge; the rules found are drawn.
        val virtualH = ys.filter { y -> used.none { abs(it.y0 - y) < 0.5 } }.map { Ruling(left, it, right, it, 0.0, 0) }
        val virtualV = seps.flatMap { x -> ys.zipWithNext().map { (a, c) -> Ruling(x, a, x, c, 0.0, 0) } }
        val grid = TableGrid(xs, ys, used + virtualH, virtualV, content.rects)
        grid.lines += pieces
        return grid
    }

    /**
     * Tables ruled only across (a common Word style): a stack of horizontal rules of
     * one width makes the rows; the columns are where the text leaves the same gap
     * in most rows. Between cells there is an invisible boundary, except where a
     * row's text runs across it (the cells merge there).
     */
    private fun openTables(hs: List<Ruling>, lines: List<Line>, content: PdfPageContent): List<TableGrid> {
        val out = ArrayList<TableGrid>()
        // Rules at one height (a row's rule broken where a cell spans it) count as one.
        class Rule(val y: Double, val x0: Double, val x1: Double, val parts: List<Ruling>)
        val rules = ArrayList<Rule>()
        for (h in hs.sortedBy { it.y0 }) {
            val last = rules.lastOrNull()
            if (last != null && abs(last.y - h.y0) < 1.0) rules[rules.size - 1] = Rule(last.y, min(last.x0, h.x0), max(last.x1, h.x1), last.parts + h)
            else rules += Rule(h.y0, h.x0, h.x1, listOf(h))
        }
        val sorted = rules.filter { it.x1 - it.x0 > 40 }
        val used = HashSet<Rule>()
        for (start in sorted) {
            if (start in used) continue
            val group = arrayListOf(start)
            for (h in sorted) {
                if (h in used || h === start || h.y <= group.last().y + 1) continue
                if (h.y - group.last().y > 80) break
                val overlap = min(h.x1, start.x1) - max(h.x0, start.x0)
                if (overlap > (start.x1 - start.x0) * 0.7 && overlap > (h.x1 - h.x0) * 0.7) group += h
            }
            if (group.size < 3) continue
            val ys = cluster(group.map { it.y })
            if (ys.size < 3) continue
            val x0 = group.minOf { it.x0 }; val x1 = group.maxOf { it.x1 }
            val pieces = lines.filter { l -> (l.x0 + l.x1) / 2 in x0..x1 && l.baseline - l.size * 0.3 in ys.first()..ys.last() }
            if (pieces.size < 4) continue
            // How many rows' text covers each half point across.
            val rows = ys.size - 1
            val bins = ((x1 - x0) * 2).toInt().coerceIn(1, 20_000)
            val cover = IntArray(bins)
            for (r in 0 until rows) {
                val hit = BooleanArray(bins)
                for (l in pieces) if (l.baseline - l.size * 0.3 in ys[r]..ys[r + 1]) {
                    for (b in ((l.x0 - x0) * 2).toInt().coerceIn(0, bins - 1)..((l.x1 - x0) * 2).toInt().coerceIn(0, bins - 1)) hit[b] = true
                }
                for (b in 0 until bins) if (hit[b]) cover[b]++
            }
            val seps = ArrayList<Double>()
            var b = 0
            while (b < bins) {
                if (cover[b] <= rows / 5) {
                    var e = b
                    while (e + 1 < bins && cover[e + 1] <= rows / 5) e++
                    // A gap at either edge is margin, not a boundary.
                    if (b > 0 && e < bins - 1 && e - b >= 2) seps += x0 + (b + e + 1) / 4.0
                    b = e + 1
                } else b++
            }
            if (seps.isEmpty()) continue
            val xs = listOf(x0) + seps + listOf(x1)
            val virtual = ArrayList<Ruling>()
            for (x in seps) for (r in 0 until rows) {
                val crossed = pieces.any { l -> l.baseline - l.size * 0.3 in ys[r]..ys[r + 1] && l.x0 < x - 0.5 && l.x1 > x + 0.5 }
                if (!crossed) virtual += Ruling(x, ys[r], x, ys[r + 1], 0.0, 0)
            }
            used += group
            val grid = TableGrid(xs, ys, group.flatMap { it.parts }, virtual, content.rects)
            grid.lines += pieces
            out += grid
        }
        return out
    }

    /** Collinear rulings that touch, joined. */
    private fun merge(rs: List<Ruling>, horizontal: Boolean): List<Ruling> {
        val sorted = rs.sortedWith(compareBy<Ruling> { Math.round((if (horizontal) it.y0 else it.x0) * 2) }.thenBy { if (horizontal) it.x0 else it.y0 })
        val out = ArrayList<Ruling>()
        for (r in sorted) {
            val last = out.lastOrNull()
            if (last != null && horizontal && abs(last.y0 - r.y0) < 1.0 && r.x0 <= last.x1 + 2) {
                out[out.size - 1] = Ruling(last.x0, last.y0, max(last.x1, r.x1), last.y1, max(last.width, r.width), last.color)
            } else if (last != null && !horizontal && abs(last.x0 - r.x0) < 1.0 && r.y0 <= last.y1 + 2) {
                out[out.size - 1] = Ruling(last.x0, last.y0, last.x1, max(last.y1, r.y1), max(last.width, r.width), last.color)
            } else out += r
        }
        return out
    }

    /** Positions within 2 points of each other as one, sorted. */
    private fun cluster(vals: List<Double>): List<Double> {
        val out = ArrayList<MutableList<Double>>()
        for (v in vals.sorted()) {
            val last = out.lastOrNull()
            if (last != null && v - last.last() < 2.5) last += v else out += mutableListOf(v)
        }
        return out.map { it.average() }
    }
}
