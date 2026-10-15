package io.github.baeseungwon1010.officecanvas.pptx

import io.github.baeseungwon1010.officecanvas.slide.Box
import io.github.baeseungwon1010.officecanvas.slide.HAlign
import io.github.baeseungwon1010.officecanvas.slide.Paragraph
import io.github.baeseungwon1010.officecanvas.slide.PathOp
import io.github.baeseungwon1010.officecanvas.slide.Run
import io.github.baeseungwon1010.officecanvas.slide.TextBody
import io.github.baeseungwon1010.officecanvas.slide.VAnchor
import org.w3c.dom.Document
import org.w3c.dom.Element as DomElement
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import io.github.baeseungwon1010.officecanvas.slide.Element as Item

internal const val NS_C = "http://schemas.openxmlformats.org/drawingml/2006/chart"

/**
 * Draws a chart part (`ppt/charts/chartN.xml`) as ordinary shapes: bars as
 * rectangles, lines and pie slices as outlines, labels as text boxes.
 *
 * A chart's data lives in an embedded workbook, but the chart part also caches
 * every value and label it shows (`<c:strCache>`, `<c:numCache>`), so the chart
 * can be drawn from the part alone. What is stored is the data and a few layout
 * hints; the layout itself (where the axes go, the value scale, the space for
 * labels) is PowerPoint's to compute, and is approximated here the way PowerPoint
 * does it by default. Bar and column (clustered, stacked, 100%), line, area, pie
 * and doughnut charts are drawn, combinations of them on shared axes too.
 */
internal object ChartParser {

    private const val EMU_PER_PT = 12700.0
    /** Defaults for a chart that styles nothing itself (PowerPoint 2007's). */
    private const val DEFAULT_TEXT_PT = 10f
    private const val DEFAULT_TITLE_PT = 18f
    private const val TEXT_COLOR = 0xFF000000.toInt()
    private const val GRID_GRAY = 0xFFB3B3B3.toInt()
    /** Caps on what a crafted chart can make us draw. */
    private const val MAX_SERIES = 64
    private const val MAX_POINTS = 2000

    private enum class Kind { BAR, LINE, AREA, PIE, DOUGHNUT }
    private enum class Grouping { STANDARD, STACKED, PERCENT }

    private class TextStyle(val sizePt: Float, val colorArgb: Int, val bold: Boolean, val font: String?)

    private class Labels(val showValue: Boolean, val showPercent: Boolean, val showCategory: Boolean, val format: String?, val style: TextStyle, val position: String?)

    private class Series(
        val name: String,
        val values: List<Double?>,
        val colorArgb: Int?,
        val lineArgb: Int?,
        val lineWidthEmu: Long?,
        val noLine: Boolean,
        val pointColors: Map<Int, Int>,
        val marker: Boolean,
        val labels: Labels?,
        val format: String?
    )

    private class Group(
        val kind: Kind,
        val horizontal: Boolean,
        val grouping: Grouping,
        val series: List<Series>,
        val gapWidth: Int,
        val overlap: Int,
        val varyColors: Boolean,
        val holePercent: Int,
        val firstSliceDeg: Int
    )

    private class Axis(
        val deleted: Boolean,
        val min: Double?,
        val max: Double?,
        val majorUnit: Double?,
        val gridlines: Boolean,
        val gridArgb: Int,
        val lineArgb: Int?,
        val style: TextStyle,
        val format: String?,
        val noLabels: Boolean
    )

    fun parse(doc: Document, frame: Box, theme: Theme): List<Item> {
        val chart = doc.documentElement?.child(NS_C, "chart") ?: return emptyList()
        val plotArea = chart.child(NS_C, "plotArea") ?: return emptyList()
        val base = baseStyle(doc.documentElement.child(NS_C, "txPr"), theme)
        val groups = plotArea.children().mapNotNull { group(it, theme, base) }.filter { it.series.isNotEmpty() }
        if (groups.isEmpty()) return emptyList()
        val categories = categories(plotArea)
        val out = mutableListOf<Item>()

        // Space from the chart's edges, as PowerPoint leaves it.
        val pad = (min(frame.w, frame.h) * 0.04).toLong().coerceAtLeast(EMU_PER_PT.toLong() * 4)
        var top = frame.y + pad
        var bottom = frame.y + frame.h - pad
        var left = frame.x + pad
        var right = frame.x + frame.w - pad

        // Title: its own text, or the only series' name when it has none. A chart
        // with one series and no title element still shows that name unless the
        // automatic title was deleted.
        val onlySeries = groups.flatMap { it.series }.singleOrNull()
        val titleNode = chart.child(NS_C, "title")
        val autoTitle = titleNode == null && onlySeries != null && chart.child(NS_C, "autoTitleDeleted")?.attr("val") == "0"
        if (titleNode != null || autoTitle) {
            val rich = titleNode?.child(NS_C, "tx")?.child(NS_C, "rich")
            val style = textStyle(titleNode?.child(NS_C, "txPr"), theme, base, DEFAULT_TITLE_PT, defaultBold = true)
            val body = rich?.let { theme.textBody(it, StyleChain.of(mapOf(0 to LevelStyle(sizePt = style.sizePt, bold = style.bold, colorArgb = style.colorArgb, latin = style.font, align = HAlign.CENTER))), CHART_BODY) }
                ?: textBody(onlySeries?.name ?: "Chart Title", style, HAlign.CENTER, wrap = true)
            // Room for the title's lines, wrapped to the chart's width.
            val lines = body.paragraphs.sumOf { p ->
                val size = p.runs.maxOfOrNull { it.sizePt } ?: style.sizePt
                ceil(textWidth(p.plainText, size).toDouble() / (right - left).coerceAtLeast(1)).toInt().coerceIn(1, 3)
            }
            val size = body.paragraphs.flatMap { it.runs }.maxOfOrNull { it.sizePt } ?: style.sizePt
            val height = (size * (1.2 * lines + 0.3) * EMU_PER_PT).toLong()
            out += textItem(Box(left, top, right - left, height), body)
            top += height + pad / 2
        }

        // Legend: one entry per series (per category for a pie), with a color key.
        chart.child(NS_C, "legend")?.let { legend ->
            val pos = legend.child(NS_C, "legendPos")?.attr("val") ?: "r"
            val style = textStyle(legend.child(NS_C, "txPr"), theme, base, DEFAULT_TEXT_PT)
            val pie = groups.first().kind in PIE_KINDS
            val entries = if (pie) categories.mapIndexed { i, c -> c to pointColor(groups.first(), groups.first().series.first(), i, theme) }
                else groups.flatMap { g -> g.series.mapIndexed { i, s -> s.name to seriesColor(s, i, theme) } }
            if (entries.isNotEmpty() && entries.size <= MAX_SERIES) {
                val lineH = (style.sizePt * 1.4 * EMU_PER_PT).toLong()
                val key = (style.sizePt * 0.7 * EMU_PER_PT).toLong()
                val widths = entries.map { (name, _) -> key * 2 + textWidth(name, style.sizePt) }
                fun entry(x: Long, y: Long, name: String, color: Int) {
                    out += rect(Box(x, y + (lineH - key) / 2, key, key), color)
                    out += textItem(Box(x + key + key / 2, y, textWidth(name, style.sizePt) + key, lineH), textBody(name, style, HAlign.LEFT))
                }
                if (pos == "b" || pos == "t") {
                    val total = widths.sum()
                    var x = frame.x + (frame.w - total) / 2
                    val y = if (pos == "b") bottom - lineH else top
                    entries.forEachIndexed { i, (name, color) -> entry(x, y, name, color); x += widths[i] }
                    if (pos == "b") bottom -= lineH + pad / 2 else top += lineH + pad / 2
                } else {
                    val w = widths.max()
                    var y = (top + bottom) / 2 - lineH * entries.size / 2
                    val x = if (pos == "l") left else right - w
                    entries.forEach { (name, color) -> entry(x, y, name, color); y += lineH }
                    if (pos == "l") left += w + pad / 2 else right -= w + pad / 2
                }
            }
        }

        // A plot area PowerPoint was told to put somewhere ("inner": the plot itself).
        val manual = plotArea.child(NS_C, "layout")?.child(NS_C, "manualLayout")
        val plot = if (manual != null && manual.child(NS_C, "layoutTarget")?.attr("val") == "inner" &&
            listOf("x", "y", "w", "h").all { manual.child(NS_C, it) != null }) {
            fun f(n: String) = manual.child(NS_C, n)?.attr("val")?.toDoubleOrNull()?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
            Box(frame.x + (frame.w * f("x")).toLong(), frame.y + (frame.h * f("y")).toLong(), (frame.w * f("w")).toLong(), (frame.h * f("h")).toLong())
        } else null

        val first = groups.first()
        if (first.kind in PIE_KINDS) {
            pie(first, categories, plot ?: Box(left, top, right - left, bottom - top), theme, out)
            return out
        }
        axes(groups, categories, plotArea, theme, base, Box(left, top, right - left, bottom - top), plot, out)
        return out
    }

    private val PIE_KINDS = setOf(Kind.PIE, Kind.DOUGHNUT)

    /** PowerPoint's chart text box: no insets, centered vertically. */
    private val CHART_BODY = BodyProps(anchor = VAnchor.MIDDLE, insets = listOf(0L, 0L, 0L, 0L))

    private fun group(node: DomElement, theme: Theme, base: TextStyle): Group? {
        val kind = when (node.localName) {
            "barChart", "bar3DChart" -> Kind.BAR
            "lineChart", "line3DChart", "stockChart" -> Kind.LINE
            "areaChart", "area3DChart" -> Kind.AREA
            "pieChart", "pie3DChart", "ofPieChart" -> Kind.PIE
            "doughnutChart" -> Kind.DOUGHNUT
            else -> return null
        }
        fun v(name: String) = node.child(NS_C, name)?.attr("val")
        val grouping = when (v("grouping")) { "stacked" -> Grouping.STACKED; "percentStacked" -> Grouping.PERCENT; else -> Grouping.STANDARD }
        val groupLabels = labels(node.child(NS_C, "dLbls"), theme, base, null)
        // A line chart shows markers unless it says not to.
        val markers = kind == Kind.LINE && v("marker") != "0"
        val series = node.all(NS_C, "ser").take(MAX_SERIES).map { series(it, theme, base, groupLabels, markers) }
        return Group(
            kind, v("barDir") == "bar", grouping, series,
            v("gapWidth")?.toIntOrNull()?.coerceIn(0, 500) ?: 150,
            v("overlap")?.toIntOrNull()?.coerceIn(-100, 100) ?: if (grouping != Grouping.STANDARD) 100 else 0,
            v("varyColors") == "1" || (kind in PIE_KINDS && v("varyColors") != "0"),
            v("holeSize")?.toIntOrNull()?.coerceIn(10, 90) ?: 50,
            v("firstSliceAng")?.toIntOrNull()?.coerceIn(0, 360) ?: 0
        )
    }

    private fun series(ser: DomElement, theme: Theme, base: TextStyle, groupLabels: Labels?, groupMarkers: Boolean): Series {
        val spPr = ser.child(NS_C, "spPr")
        val fill = theme.fillIn(spPr)
        val ln = spPr?.child(NS_A, "ln")
        val lineFill = ln?.let { theme.fillIn(it) }
        val points = ser.all(NS_C, "dPt").mapNotNull { dpt ->
            val idx = dpt.child(NS_C, "idx")?.attr("val")?.toIntOrNull() ?: return@mapNotNull null
            (theme.fillIn(dpt.child(NS_C, "spPr")) as? Fill.Solid)?.let { idx to it.argb }
        }.toMap()
        val symbol = ser.child(NS_C, "marker")?.child(NS_C, "symbol")?.attr("val")
        val valueRef = ser.child(NS_C, "val")
        return Series(
            name = ser.child(NS_C, "tx")?.let { tx -> cachedStrings(tx).firstOrNull() ?: tx.child(NS_C, "v")?.textContent } ?: "",
            values = cachedNumbers(valueRef),
            colorArgb = (fill as? Fill.Solid)?.argb ?: (fill as? Fill.Shaded)?.gradient?.colors?.firstOrNull(),
            lineArgb = (lineFill as? Fill.Solid)?.argb,
            lineWidthEmu = ln?.longOrNull("w"),
            noLine = lineFill == Fill.None,
            pointColors = points,
            marker = if (symbol != null) symbol != "none" else groupMarkers,
            labels = labels(ser.child(NS_C, "dLbls"), theme, base, groupLabels),
            format = valueRef?.first(NS_C, "formatCode")?.textContent
        )
    }

    /** <c:dLbls>: which parts of a point's label to show; [inherited] when it says nothing. */
    private fun labels(node: DomElement?, theme: Theme, base: TextStyle, inherited: Labels?): Labels? {
        node ?: return inherited
        if (node.child(NS_C, "delete")?.attr("val") == "1") return null
        fun on(name: String) = node.child(NS_C, name)?.attr("val") == "1"
        if (!on("showVal") && !on("showPercent") && !on("showCatName")) return null
        return Labels(
            on("showVal"), on("showPercent"), on("showCatName"),
            node.child(NS_C, "numFmt")?.attr("formatCode"),
            textStyle(node.child(NS_C, "txPr"), theme, base, DEFAULT_TEXT_PT),
            node.child(NS_C, "dLblPos")?.attr("val")
        )
    }

    /** Category labels from the first series that has them (all series share them). */
    private fun categories(plotArea: DomElement): List<String> {
        val series = plotArea.descendants(NS_C, "ser").take(MAX_SERIES)
        for (ser in series) {
            val cat = ser.child(NS_C, "cat") ?: continue
            val strings = cachedStrings(cat)
            if (strings.isNotEmpty()) return strings
            val numbers = cachedNumbers(cat)
            if (numbers.isNotEmpty()) {
                val fmt = cat.first(NS_C, "formatCode")?.textContent
                return numbers.map { it?.let { n -> formatNumber(n, fmt) } ?: "" }
            }
        }
        val count = series.maxOfOrNull { cachedNumbers(it.child(NS_C, "val")).size } ?: 0
        return (1..count).map { "$it" }
    }

    /** A string cache's points by index (the first level of a multi-level one). */
    private fun cachedStrings(ref: DomElement): List<String> {
        val cache = ref.first(NS_C, "strCache") ?: ref.first(NS_C, "lvl") ?: return emptyList()
        return indexed(cache) { it }.map { it.orEmpty() }
    }

    private fun cachedNumbers(ref: DomElement?): List<Double?> {
        val cache = ref?.first(NS_C, "numCache") ?: ref?.first(NS_C, "numLit") ?: return emptyList()
        return indexed(cache) { it.trim().toDoubleOrNull()?.takeIf { d -> d.isFinite() } }
    }

    private fun <T> indexed(cache: DomElement, value: (String) -> T?): List<T?> {
        val pts = cache.all(NS_C, "pt").take(MAX_POINTS)
        val count = (cache.child(NS_C, "ptCount")?.attr("val")?.toIntOrNull() ?: 0)
            .coerceAtLeast(pts.size).coerceAtMost(MAX_POINTS)
        val out = MutableList<T?>(count) { null }
        for (pt in pts) {
            val i = pt.attr("idx")?.toIntOrNull() ?: continue
            if (i in 0 until count) out[i] = pt.child(NS_C, "v")?.textContent?.let(value)
        }
        return out
    }

    // ---- axes, bars, lines and areas ------------------------------------------

    private fun axes(groups: List<Group>, categories: List<String>, plotArea: DomElement, theme: Theme, base: TextStyle,
                     area: Box, manualPlot: Box?, out: MutableList<Item>) {
        val horizontal = groups.first().kind == Kind.BAR && groups.first().horizontal
        val valAx = axis(plotArea.child(NS_C, "valAx"), theme, base)
        val catAx = axis(plotArea.child(NS_C, "catAx") ?: plotArea.child(NS_C, "dateAx"), theme, base)
        val count = max(categories.size, groups.maxOf { g -> g.series.maxOf { it.values.size } })
        if (count == 0) return

        // The value range: stacked groups add up per category, 100% ones span 0..1.
        var lo = 0.0
        var hi = 0.0
        for (g in groups) for (i in 0 until count) {
            when (g.grouping) {
                Grouping.PERCENT -> hi = max(hi, 1.0)
                Grouping.STACKED -> {
                    hi = max(hi, g.series.sumOf { (it.values.getOrNull(i) ?: 0.0).coerceAtLeast(0.0) })
                    lo = min(lo, g.series.sumOf { (it.values.getOrNull(i) ?: 0.0).coerceAtMost(0.0) })
                }
                Grouping.STANDARD -> g.series.forEach { s -> s.values.getOrNull(i)?.let { hi = max(hi, it); lo = min(lo, it) } }
            }
        }
        val scale = Scale.of(valAx.min ?: lo, valAx.max ?: hi, valAx.majorUnit, valAx.min != null, valAx.max != null)
        val percent = groups.all { it.grouping == Grouping.PERCENT }
        val valueFormat = valAx.format?.takeIf { it != "General" } ?: if (percent) "0%" else groups.first().series.first().format

        // Space for the axis labels, then the plot.
        val valueLabels = if (valAx.deleted || valAx.noLabels) emptyList() else scale.ticks.map { formatNumber(it, valueFormat) }
        val valueLabelW = (valueLabels.maxOfOrNull { textWidth(it, valAx.style.sizePt) } ?: 0L)
        val textH = { size: Float -> (size * 1.4 * EMU_PER_PT).toLong() }
        val catLabelsShown = !catAx.deleted && !catAx.noLabels
        val plot = manualPlot ?: if (!horizontal) {
            val leftW = if (valueLabels.isEmpty()) 0L else valueLabelW + (valAx.style.sizePt * 0.6 * EMU_PER_PT).toLong()
            val bottomH = if (catLabelsShown) textH(catAx.style.sizePt) * catLines(categories, (area.w - leftW) / count.coerceAtLeast(1), catAx.style.sizePt) else 0L
            Box(area.x + leftW, area.y, area.w - leftW, area.h - bottomH)
        } else {
            val catW = if (catLabelsShown) (categories.maxOfOrNull { textWidth(it, catAx.style.sizePt) } ?: 0L).coerceAtMost(area.w / 3) + (catAx.style.sizePt * 0.6 * EMU_PER_PT).toLong() else 0L
            val bottomH = if (valueLabels.isEmpty()) 0L else textH(valAx.style.sizePt)
            Box(area.x + catW, area.y, area.w - catW, area.h - bottomH)
        }
        if (plot.w <= 0 || plot.h <= 0) return

        // Value position along the value axis, in EMU from the plot's left (bars) or bottom.
        val valueSpan = if (horizontal) plot.w else plot.h
        fun pos(v: Double) = ((v - scale.min) / (scale.max - scale.min) * valueSpan).toLong().coerceIn(-valueSpan, valueSpan * 2)
        fun at(along: Long, value: Long): Pair<Long, Long> =
            if (horizontal) (plot.x + value) to (plot.y + plot.h - along) else (plot.x + along) to (plot.y + plot.h - value)
        val slot = (if (horizontal) plot.h else plot.w).toDouble() / count

        // Gridlines and value labels.
        val gridColor = valAx.gridArgb
        scale.ticks.forEachIndexed { i, t ->
            val p = pos(t)
            if (valAx.gridlines) {
                out += if (horizontal) line(plot.x + p, plot.y, plot.x + p, plot.y + plot.h, gridColor, 9525)
                else line(plot.x, plot.y + plot.h - p, plot.x + plot.w, plot.y + plot.h - p, gridColor, 9525)
            }
            valueLabels.getOrNull(i)?.let { label ->
                val h = textH(valAx.style.sizePt)
                out += if (horizontal) textItem(Box(plot.x + p - valueLabelW, plot.y + plot.h, valueLabelW * 2, h), textBody(label, valAx.style, HAlign.CENTER))
                else textItem(Box(plot.x - valueLabelW - (valAx.style.sizePt * 0.4 * EMU_PER_PT).toLong(), plot.y + plot.h - p - h / 2, valueLabelW, h), textBody(label, valAx.style, HAlign.RIGHT))
            }
        }

        // Bars first, then areas, then lines on top, as PowerPoint layers them.
        val bars = groups.filter { it.kind == Kind.BAR }
        for (g in bars) {
            val n = if (g.grouping == Grouping.STANDARD) g.series.size else 1
            val overlap = g.overlap / 100.0
            val barW = slot / (n - (n - 1) * overlap + g.gapWidth / 100.0)
            val step = barW * (1 - overlap)
            val groupW = barW + step * (n - 1)
            for (i in 0 until count) {
                var posStack = 0.0
                var negStack = 0.0
                val total = g.series.sumOf { abs(it.values.getOrNull(i) ?: 0.0) }.takeIf { it > 0 } ?: 1.0
                g.series.forEachIndexed { k, s ->
                    var v = s.values.getOrNull(i) ?: return@forEachIndexed
                    if (g.grouping == Grouping.PERCENT) v /= total
                    val (from, to) = when (g.grouping) {
                        Grouping.STANDARD -> scale.base to v
                        else -> if (v >= 0) posStack.also { posStack += v } to posStack else negStack.also { negStack += v } to negStack
                    }
                    val start = i * slot + (slot - groupW) / 2 + (if (g.grouping == Grouping.STANDARD) k * step else 0.0)
                    val a = pos(from); val b = pos(to)
                    val (x0, y0) = at(start.toLong(), max(a, b))
                    val (x1, y1) = at((start + barW).toLong(), min(a, b))
                    val box = Box(min(x0, x1), min(y0, y1), abs(x1 - x0).coerceAtLeast(1), abs(y1 - y0).coerceAtLeast(1))
                    out += rect(box, pointColor(g, s, if (g.varyColors && g.series.size == 1) i else k, theme, i))
                    s.labels?.let { l ->
                        val text = labelText(l, s.values.getOrNull(i)!!, v, categories.getOrNull(i), s.format) ?: return@let
                        val h = textH(l.style.sizePt)
                        val w = textWidth(text, l.style.sizePt) + h
                        val inside = g.grouping != Grouping.STANDARD || l.position in setOf("ctr", "inEnd", "inBase")
                        val lb = if (horizontal) {
                            val cy = box.y + box.h / 2 - h / 2
                            when {
                                inside -> Box(box.x + box.w / 2 - w / 2, cy, w, h)
                                v >= 0 -> Box(box.x + box.w, cy, w, h)
                                else -> Box(box.x - w, cy, w, h)
                            }
                        } else {
                            val cx = box.x + box.w / 2 - w / 2
                            when {
                                inside -> Box(cx, box.y + box.h / 2 - h / 2, w, h)
                                v >= 0 -> Box(cx, box.y - h, w, h)
                                else -> Box(cx, box.y + box.h, w, h)
                            }
                        }
                        out += textItem(lb, textBody(text, l.style, HAlign.CENTER))
                    }
                }
            }
        }
        for (g in groups.filter { it.kind == Kind.AREA || it.kind == Kind.LINE }.sortedBy { if (it.kind == Kind.AREA) 0 else 1 }) {
            val stack = DoubleArray(count)
            g.series.forEachIndexed { k, s ->
                val totals = (0 until count).map { i -> g.series.sumOf { abs(it.values.getOrNull(i) ?: 0.0) }.takeIf { it > 0 } ?: 1.0 }
                val below = stack.copyOf()
                val pts = (0 until count).mapNotNull { i ->
                    var v = s.values.getOrNull(i) ?: return@mapNotNull null
                    if (g.grouping == Grouping.PERCENT) v /= totals[i]
                    if (g.grouping != Grouping.STANDARD) { stack[i] += v; v = stack[i] }
                    i to v
                }
                if (pts.isEmpty()) return@forEachIndexed
                val color = seriesColor(s, k, theme)
                // Lines and areas put their points in the middle of each category's slot.
                fun xy(i: Int, v: Double) = at(((i + 0.5) * slot).toLong(), pos(v))
                if (g.kind == Kind.AREA) {
                    val base = pts.map { (i, _) -> i to (if (g.grouping == Grouping.STANDARD) scale.base else below[i]) }
                    val outline = (pts.map { (i, v) -> xy(i, v) } + base.reversed().map { (i, v) -> xy(i, v) })
                    out += polygon(plot, outline, fill = color, stroke = null, widthEmu = 0, closed = true)
                } else if (!s.noLine && pts.size > 1) {
                    out += polygon(plot, pts.map { (i, v) -> xy(i, v) }, fill = null, stroke = s.lineArgb ?: color,
                        widthEmu = (s.lineWidthEmu ?: 28575L).coerceIn(3175L, 254000L), closed = false)
                }
                if (g.kind == Kind.LINE && s.marker) {
                    val r = (2.5 * EMU_PER_PT).toLong()
                    for ((i, v) in pts) {
                        val (x, y) = xy(i, v)
                        out += Item.Shape(Box(x - r, y - r, 2 * r, 2 * r), "ellipse", color, color, 9525, null)
                    }
                }
                s.labels?.let { l ->
                    for ((i, v) in pts) {
                        val text = labelText(l, s.values.getOrNull(i) ?: continue, v, categories.getOrNull(i), s.format) ?: continue
                        val (x, y) = xy(i, v)
                        val h = textH(l.style.sizePt)
                        val w = textWidth(text, l.style.sizePt) + h
                        out += textItem(Box(x - w / 2, if (l.position == "b") y + h / 4 else y - h - h / 4, w, h), textBody(text, l.style, HAlign.CENTER))
                    }
                }
            }
        }

        // The category axis line where the value axis crosses (zero, or the bottom).
        val zero = pos(scale.base)
        if (!catAx.deleted) {
            catAx.lineArgb?.let { c ->
                out += if (horizontal) line(plot.x + zero, plot.y, plot.x + zero, plot.y + plot.h, c, 9525)
                else line(plot.x, plot.y + plot.h - zero, plot.x + plot.w, plot.y + plot.h - zero, c, 9525)
            }
        }
        if (catLabelsShown) {
            val h = textH(catAx.style.sizePt)
            categories.forEachIndexed { i, label ->
                if (horizontal) {
                    val w = plot.x - area.x - (catAx.style.sizePt * 0.4 * EMU_PER_PT).toLong()
                    val (_, y) = at(((i + 0.5) * slot).toLong(), 0)
                    out += textItem(Box(area.x, y - h / 2, w.coerceAtLeast(1), h), textBody(label, catAx.style, HAlign.RIGHT))
                } else {
                    val (x, _) = at((i * slot).toLong(), 0)
                    val lines = catLines(categories, slot.toLong(), catAx.style.sizePt)
                    out += textItem(Box(x, plot.y + plot.h + h / 6, slot.toLong().coerceAtLeast(1), h * lines),
                        textBody(label, catAx.style, HAlign.CENTER, wrap = true).copy(anchor = VAnchor.TOP))
                }
            }
        }
    }

    /** Lines category labels need when wrapped to [slot] (at most 3). */
    private fun catLines(categories: List<String>, slot: Long, sizePt: Float): Int {
        if (slot <= 0) return 1
        val widest = categories.maxOfOrNull { textWidth(it, sizePt) } ?: 0L
        return ceil(widest.toDouble() / (slot * 0.9)).toInt().coerceIn(1, 3)
    }

    private fun axis(node: DomElement?, theme: Theme, base: TextStyle): Axis {
        val scaling = node?.child(NS_C, "scaling")
        fun d(parent: DomElement?, name: String) = parent?.child(NS_C, name)?.attr("val")?.toDoubleOrNull()?.takeIf { it.isFinite() }
        val ln = node?.child(NS_C, "spPr")?.child(NS_A, "ln")
        val lineFill = ln?.let { theme.fillIn(it) }
        return Axis(
            deleted = node == null || node.child(NS_C, "delete")?.attr("val") == "1",
            min = d(scaling, "min"), max = d(scaling, "max"),
            majorUnit = d(node, "majorUnit")?.takeIf { it > 0 },
            gridlines = node?.child(NS_C, "majorGridlines") != null,
            gridArgb = (node?.child(NS_C, "majorGridlines")?.child(NS_C, "spPr")?.child(NS_A, "ln")?.let { theme.fillIn(it) } as? Fill.Solid)?.argb ?: GRID_GRAY,
            lineArgb = when (lineFill) { is Fill.Solid -> lineFill.argb; Fill.None -> null; else -> GRID_GRAY },
            style = textStyle(node?.child(NS_C, "txPr"), theme, base, DEFAULT_TEXT_PT),
            format = node?.child(NS_C, "numFmt")?.attr("formatCode"),
            noLabels = node?.child(NS_C, "tickLblPos")?.attr("val") == "none"
        )
    }

    /** A value axis scale: [min], [max] and the [ticks] between them, a round step apart. */
    private class Scale(val min: Double, val max: Double, val ticks: List<Double>) {
        /** Where bars start: zero when it is in range, else the end nearest to it. */
        val base: Double get() = 0.0.coerceIn(min, max)

        companion object {
            fun of(lo: Double, hi: Double, unit: Double?, fixedMin: Boolean, fixedMax: Boolean): Scale {
                var a = lo; var b = hi
                if (b <= a) b = a + 1.0
                // Like Excel: about ten steps of 1, 2 or 5 times a power of ten, and
                // the top 5% of the range above the largest value rounded up to a step.
                val span = b - a
                val step = unit?.takeIf { span / it <= 50 } ?: niceStep(span / 10)
                if (!fixedMin) a = floor((a - if (a < 0) span * 0.05 else 0.0) / step) * step
                if (!fixedMax) b = ceil((b + if (b > 0) span * 0.05 else 0.0) / step - 1e-9) * step
                if (b <= a) b = a + step
                val ticks = mutableListOf<Double>()
                var t = ceil(a / step - 1e-9) * step
                while (t <= b + step * 1e-6 && ticks.size < 60) { ticks += t; t += step }
                return Scale(a, b, ticks)
            }

            private fun niceStep(raw: Double): Double {
                if (raw <= 0 || !raw.isFinite()) return 1.0
                val mag = 10.0.pow(floor(log10(raw)))
                val f = raw / mag
                return mag * when { f <= 1 -> 1.0; f <= 2 -> 2.0; f <= 5 -> 5.0; else -> 10.0 }
            }
        }
    }

    // ---- pie and doughnut -----------------------------------------------------

    private fun pie(g: Group, categories: List<String>, area: Box, theme: Theme, out: MutableList<Item>) {
        val s = g.series.first()
        val values = s.values.map { (it ?: 0.0).coerceAtLeast(0.0) }
        val total = values.sum()
        if (total <= 0) return
        val d = min(area.w, area.h)
        val box = Box(area.x + (area.w - d) / 2, area.y + (area.h - d) / 2, d, d)
        val hole = if (g.kind == Kind.DOUGHNUT) g.holePercent / 100.0 else 0.0
        // Slices run clockwise from twelve o'clock (plus the first slice angle).
        var angle = -90.0 + g.firstSliceDeg
        values.forEachIndexed { i, v ->
            if (v <= 0) return@forEachIndexed
            val sweep = v / total * 360.0
            val color = pointColor(g, s, i, theme)
            out += Item.Shape(box, null, color, 0xFFFFFFFF.toInt(), 9525, null, outline = slicePath(angle, sweep, hole))
            s.labels?.let { l ->
                val text = labelText(l, v, v, categories.getOrNull(i), s.format, v / total) ?: return@let
                val mid = Math.toRadians(angle + sweep / 2)
                val r = if (hole > 0) (1 + hole) / 2 else if (l.position == "outEnd") 1.15 else 0.65
                val cx = box.x + box.w / 2 + (cos(mid) * r * box.w / 2).toLong()
                val cy = box.y + box.h / 2 + (sin(mid) * r * box.h / 2).toLong()
                val h = (l.style.sizePt * 1.4 * EMU_PER_PT).toLong() * text.count { it == '\n' }.plus(1)
                val w = text.lines().maxOf { textWidth(it, l.style.sizePt) } + h / 2
                out += textItem(Box(cx - w / 2, cy - h / 2, w, h), textBody(text, l.style, HAlign.CENTER))
            }
            angle += sweep
        }
    }

    /** A slice (or a ring segment when [hole] > 0) in the unit box's coordinates. */
    private fun slicePath(startDeg: Double, sweepDeg: Double, hole: Double): List<PathOp> {
        val ops = mutableListOf<PathOp>()
        fun pt(deg: Double, r: Double) = Math.toRadians(deg).let { (0.5 + cos(it) * r / 2).toFloat() to (0.5 + sin(it) * r / 2).toFloat() }
        fun arc(from: Double, sweep: Double, r: Double) {
            val parts = ceil(abs(sweep) / 90.0).toInt().coerceAtLeast(1)
            val step = sweep / parts
            for (k in 0 until parts) {
                val a0 = from + step * k; val a1 = a0 + step
                val handle = 4.0 / 3.0 * tan(Math.toRadians(step) / 4) * r / 2
                val (x0, y0) = pt(a0, r); val (x1, y1) = pt(a1, r)
                val t0 = Math.toRadians(a0); val t1 = Math.toRadians(a1)
                ops += PathOp.CubicTo(
                    (x0 - sin(t0) * handle).toFloat(), (y0 + cos(t0) * handle).toFloat(),
                    (x1 + sin(t1) * handle).toFloat(), (y1 - cos(t1) * handle).toFloat(), x1, y1
                )
            }
        }
        val sweep = min(sweepDeg, 359.99)
        if (hole > 0) {
            pt(startDeg, 1.0).let { (x, y) -> ops += PathOp.MoveTo(x, y) }
            arc(startDeg, sweep, 1.0)
            pt(startDeg + sweep, hole).let { (x, y) -> ops += PathOp.LineTo(x, y) }
            arc(startDeg + sweep, -sweep, hole)
        } else {
            ops += PathOp.MoveTo(0.5f, 0.5f)
            pt(startDeg, 1.0).let { (x, y) -> ops += PathOp.LineTo(x, y) }
            arc(startDeg, sweep, 1.0)
        }
        ops += PathOp.Close
        return ops
    }

    // ---- colors, text and shapes ---------------------------------------------

    /** A series' own color, else the theme's accents in turn, darker on each round. */
    private fun seriesColor(s: Series, index: Int, theme: Theme): Int = s.colorArgb ?: accent(index, theme)

    private fun pointColor(g: Group, s: Series, index: Int, theme: Theme, point: Int = index): Int =
        s.pointColors[point] ?: if (g.varyColors && g.series.size == 1) accent(index, theme) else seriesColor(s, g.series.indexOf(s).coerceAtLeast(0), theme)

    private fun accent(index: Int, theme: Theme): Int {
        val base = theme.scheme("accent${index % 6 + 1}") ?: DEFAULT_ACCENTS[index % 6]
        val round = index / 6
        if (round == 0) return base
        // Later rounds: 60% and 80% of each channel, as Office's chart palettes do.
        val f = if (round % 2 == 1) 0.6 else 0.8
        fun ch(shift: Int) = (((base shr shift) and 0xFF) * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private val DEFAULT_ACCENTS = intArrayOf(
        0xFF4472C4.toInt(), 0xFFED7D31.toInt(), 0xFFA5A5A5.toInt(), 0xFFFFC000.toInt(), 0xFF5B9BD5.toInt(), 0xFF70AD47.toInt()
    )

    /** The chart's <c:txPr>; [NO_BASE] when it has none, so each part keeps its own default size. */
    private fun baseStyle(txPr: DomElement?, theme: Theme): TextStyle =
        if (txPr == null) NO_BASE else textStyle(txPr, theme, null, DEFAULT_TEXT_PT)

    private val NO_BASE = TextStyle(DEFAULT_TEXT_PT, TEXT_COLOR, false, null)

    /** The default run properties of a <c:txPr>, over [base] (the chart's own). */
    private fun textStyle(txPr: DomElement?, theme: Theme, base: TextStyle?, defaultPt: Float, defaultBold: Boolean = false): TextStyle {
        val def = txPr?.first(NS_A, "defRPr")
        val run = theme.runStyle(def)
        // The chart's own text properties apply to everything; a title is larger
        // (by 1.2) and bold unless it says otherwise.
        val title = defaultPt == DEFAULT_TITLE_PT
        return TextStyle(
            run.sizePt ?: base?.let { if (title) it.sizePt * 1.2f else it.sizePt }?.takeIf { base !== NO_BASE } ?: defaultPt,
            run.colorArgb ?: base?.colorArgb ?: TEXT_COLOR,
            run.bold ?: if (defaultBold) true else base?.bold ?: false,
            theme.font(run.latin ?: run.ea) ?: base?.font ?: theme.font("+mn-lt")
        )
    }

    private fun labelText(l: Labels, raw: Double, shown: Double, category: String?, seriesFormat: String?, fraction: Double? = null): String? {
        val parts = mutableListOf<String>()
        if (l.showCategory && category != null) parts += category
        if (l.showValue) parts += formatNumber(raw, l.format?.takeIf { it != "General" } ?: seriesFormat)
        if (l.showPercent && fraction != null) parts += formatNumber(fraction, "0%")
        return parts.joinToString(if (fraction != null) "\n" else ", ").ifEmpty { null }
    }

    /**
     * Formats [v] with the common parts of an Excel number format: decimals,
     * thousands separators, percent, and literal text around the number.
     */
    internal fun formatNumber(v: Double, format: String?): String {
        val f = format?.substringBefore(';')?.trim().orEmpty()
        if (f.isEmpty() || f.equals("General", ignoreCase = true)) {
            if (v == Math.rint(v) && abs(v) < 1e15) return v.toLong().toString()
            return "%.10g".format(java.util.Locale.ROOT, v).let { s -> if ('e' in s) s else s.trimEnd('0').trimEnd('.') }
        }
        val percent = '%' in f
        val number = f.replace(Regex("\"[^\"]*\"|\\\\.|\\[[^]]*]"), "")
        val core = Regex("[#0,.]+").find(number)?.value ?: return v.toString()
        val decimals = core.substringAfter('.', "").count { it == '0' || it == '#' }.coerceAtMost(15)
        val grouped = core.substringBefore('.').contains(',')
        val x = if (percent) v * 100 else v
        val digits = String.format(java.util.Locale.ROOT, if (grouped) "%,.${decimals}f" else "%.${decimals}f", x)
        // Literal text before and after the digits ("$", " 건", "\원").
        val literal = Regex("\"([^\"]*)\"|\\\\(.)")
        val start = f.indexOf(core)
        fun literalOf(s: String) = literal.findAll(s).joinToString("") { it.groupValues[1] + it.groupValues[2] } +
            s.replace(literal, "").filter { it in "$€£¥₩ ()-+" }
        val prefix = if (start > 0) literalOf(f.substring(0, start)) else ""
        val suffix = literalOf(f.substring(start + core.length).replace("%", ""))
        return prefix + digits + suffix + (if (percent) "%" else "")
    }

    /** About how wide [text] sets at [sizePt], in EMU: full width for CJK, a bit
     *  over half for Latin. Only used to leave room for labels. */
    private fun textWidth(text: String, sizePt: Float): Long =
        (text.sumOf { c -> if (c.code >= 0x1100) 1.0 else if (c.isUpperCase()) 0.65 else 0.55 } * sizePt * EMU_PER_PT).toLong()

    private fun textBody(text: String, style: TextStyle, align: HAlign, wrap: Boolean = false) = TextBody(
        paragraphs = listOf(Paragraph(listOf(Run(text, style.sizePt, style.bold, colorArgb = style.colorArgb, fontFamily = style.font)), align)),
        anchor = VAnchor.MIDDLE, insetLeftEmu = 0, insetTopEmu = 0, insetRightEmu = 0, insetBottomEmu = 0, wrap = wrap
    )

    private fun textItem(box: Box, body: TextBody) = Item.Shape(box, null, null, null, 0, body)

    private fun rect(box: Box, color: Int) = Item.Shape(box, "rect", color, null, 0, null)

    private fun line(x0: Long, y0: Long, x1: Long, y1: Long, color: Int, widthEmu: Long) = Item.Shape(
        Box(min(x0, x1), min(y0, y1), abs(x1 - x0), abs(y1 - y0)), "line", null, color, widthEmu, null,
        flipH = x1 < x0, flipV = y1 < y0
    )

    /** A polyline or polygon through absolute points, drawn in [frame]'s space. */
    private fun polygon(frame: Box, points: List<Pair<Long, Long>>, fill: Int?, stroke: Int?, widthEmu: Long, closed: Boolean): Item.Shape {
        val ops = points.mapIndexed { i, (x, y) ->
            val fx = ((x - frame.x).toDouble() / frame.w).toFloat(); val fy = ((y - frame.y).toDouble() / frame.h).toFloat()
            if (i == 0) PathOp.MoveTo(fx, fy) else PathOp.LineTo(fx, fy)
        } + if (closed) listOf(PathOp.Close) else emptyList()
        return Item.Shape(frame, null, fill, stroke, widthEmu, null, outline = ops)
    }
}
