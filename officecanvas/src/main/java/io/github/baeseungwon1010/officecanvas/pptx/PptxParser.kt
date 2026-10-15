package io.github.baeseungwon1010.officecanvas.pptx

import io.github.baeseungwon1010.officecanvas.slide.Box
import io.github.baeseungwon1010.officecanvas.slide.Crop
import io.github.baeseungwon1010.officecanvas.slide.Deck
import io.github.baeseungwon1010.officecanvas.slide.Slide
import io.github.baeseungwon1010.officecanvas.slide.TableCell
import io.github.baeseungwon1010.officecanvas.util.Limits
import io.github.baeseungwon1010.officecanvas.util.SafeXml
import org.w3c.dom.Document
import org.w3c.dom.Element as DomElement
import java.io.File
import java.util.zip.ZipFile
import io.github.baeseungwon1010.officecanvas.slide.Element as Item

/**
 * Reads a .pptx into a [Deck].
 *
 * A .pptx is a zip of XML, so this needs nothing but java.util.zip and javax.xml —
 * both present on Android and on a plain JVM, which is what lets the parser be
 * tested against real decks without a device.
 *
 * A slide's actual appearance is rarely self-contained: it draws through a chain
 * (slide -> slideLayout -> slideMaster -> theme), inheriting position, fill, body
 * properties, text style and background at each hop unless it overrides one. This
 * parser resolves that chain once per slide, so the model it hands the renderer is
 * flat.
 */
internal object PptxParser {

    /** PowerPoint's own default outline weight when <a:ln> omits "w". */
    private const val DEFAULT_LINE_WIDTH_EMU = 12700L

    class ParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** A placeholder on a layout or master: what a slide placeholder inherits. */
    private class Placeholder(val type: String, val idx: String?, val node: DomElement, val part: String) {
        val spPr: DomElement? get() = node.child(NS_P, "spPr")
        val style: DomElement? get() = node.child(NS_P, "style")
        val txBody: DomElement? get() = node.child(NS_P, "txBody")
    }

    /** A layout or master part. */
    private class Part(val path: String, val doc: Document, val rels: Map<String, String>) {
        val root: DomElement get() = doc.documentElement
        val tree: DomElement? get() = root.child(NS_P, "cSld")?.child(NS_P, "spTree")
        val placeholders: List<Placeholder> by lazy {
            tree?.let { t -> collectPlaceholders(t, path) }.orEmpty()
        }
    }

    /** Everything one slide inherits, resolved for its color map. */
    /** Parts read once per deck: many slides share a layout, master and theme, and
     *  a crafted deck could otherwise make every slide re-read one huge part. */
    private class DeckCache(val zip: ZipFile) {
        private val parts = HashMap<String, Part?>()
        private val themes = HashMap<String, Theme>()
        private val docs = HashMap<String, Document?>()
        private val relsByPart = HashMap<String, Map<String, String>>()

        fun part(path: String?): Part? = path?.let { p -> parts.getOrPut(p) { zip.xml(p)?.let { Part(p, it, rels(p)) } } }
        fun theme(path: String): Theme = themes.getOrPut(path) { Theme.load(zip, path) }
        fun xml(path: String): Document? = docs.getOrPut(path) { zip.xml(path) }
        fun rels(path: String): Map<String, String> = relsByPart.getOrPut(path) { zip.rels(path) }
    }

    private class SlideContext(
        val cache: DeckCache,
        val theme: Theme,
        val layout: Part?,
        val master: Part?,
        val titleStyle: Map<Int, LevelStyle>,
        val bodyStyle: Map<Int, LevelStyle>,
        val otherStyle: Map<Int, LevelStyle>,
        val defaultStyle: Map<Int, LevelStyle>,
        val tableStyles: Map<String, DomElement>
    ) {
        fun rels(part: String): Map<String, String> = cache.rels(part)

        /** The layout and master placeholders a slide placeholder inherits from, nearest first. */
        fun inherited(type: String, idx: String?): List<Placeholder> {
            val fromLayout = layout?.placeholders?.let { list ->
                idx?.let { i -> list.firstOrNull { it.idx == i } } ?: list.firstOrNull { it.type == type }
                    ?: list.firstOrNull { masterType(it.type) == masterType(type) && type != "obj" }
            }
            val fromMaster = master?.placeholders?.firstOrNull { it.type == masterType(fromLayout?.type ?: type) }
            return listOfNotNull(fromLayout, fromMaster)
        }

        fun categoryStyle(type: String?): Map<Int, LevelStyle> = when (type) {
            null -> defaultStyle
            "title", "ctrTitle" -> titleStyle
            "dt", "ftr", "sldNum", "hdr" -> otherStyle
            else -> bodyStyle
        }
    }

    fun parse(file: File): Deck = ZipFile(file).use { zip ->
        val presentation = zip.xml("ppt/presentation.xml")
            ?: throw ParseException("not a .pptx: ppt/presentation.xml is missing")

        val size = presentation.first(NS_P, "sldSz")
        val widthEmu = size?.getAttribute("cx")?.toLongOrNull() ?: 9144000L
        val heightEmu = size?.getAttribute("cy")?.toLongOrNull() ?: 6858000L

        // Slide order lives in presentation.xml as relationship ids, not as file names:
        // slide7.xml can perfectly well be the second slide shown.
        val presRels = zip.rels("ppt/presentation.xml")
        val slidePaths = presentation.descendants(NS_P, "sldId").mapNotNull { presRels[it.getAttributeNS(NS_R, "id")] }
            .distinct().take(Limits.MAX_PAGES)
        val defaultTextStyle = presentation.first(NS_P, "defaultTextStyle")
        val tableStyles = zip.xml("ppt/tableStyles.xml")?.documentElement?.all(NS_A, "tblStyle").orEmpty()
            .associateBy { it.getAttribute("styleId") }

        val cache = DeckCache(zip)

        val slides = slidePaths.mapNotNull { path ->
            val doc = zip.xml(path) ?: return@mapNotNull Slide(null, emptyList())
            // Hidden slides are left out, as PowerPoint's own PDF export does.
            if (doc.documentElement.getAttribute("show") == "0") return@mapNotNull null
            try {
                parseSlide(cache, path, doc, widthEmu, heightEmu, defaultTextStyle, tableStyles)
            } catch (e: Exception) {
                // One unreadable slide should cost one slide, not the whole deck.
                Slide(backgroundArgb = null, elements = emptyList())
            }
        }
        Deck(widthEmu, heightEmu, slides)
    }

    private fun parseSlide(
        cache: DeckCache, path: String, doc: Document, widthEmu: Long, heightEmu: Long,
        defaultTextStyle: DomElement?, tableStyles: Map<String, DomElement>
    ): Slide {
        val slide = Part(path, doc, cache.rels(path))
        val layout = cache.part(slide.rels.values.firstOrNull { "slideLayout" in it })
        val master = cache.part(layout?.rels?.values?.firstOrNull { "slideMaster" in it })
        val themePath = master?.rels?.values?.firstOrNull { "/theme" in it }

        // Color map: the master's, unless the layout or slide overrides it.
        var colorMap = Theme.colorMapOf(master?.root?.child(NS_P, "clrMap")) ?: Theme.DEFAULT_MAP
        for (p in listOfNotNull(layout, slide)) {
            Theme.colorMapOf(p.root.child(NS_P, "clrMapOvr")?.child(NS_A, "overrideClrMapping"))?.let { colorMap = it }
        }
        val theme = (themePath?.let { cache.theme(it) } ?: Theme.EMPTY).withColorMap(colorMap)

        val txStyles = master?.root?.child(NS_P, "txStyles")
        val ctx = SlideContext(
            cache, theme, layout, master,
            titleStyle = theme.levelStyles(txStyles?.child(NS_P, "titleStyle")),
            bodyStyle = theme.levelStyles(txStyles?.child(NS_P, "bodyStyle")),
            otherStyle = theme.levelStyles(txStyles?.child(NS_P, "otherStyle")),
            defaultStyle = theme.levelStyles(defaultTextStyle),
            tableStyles = tableStyles
        )

        val elements = mutableListOf<Item>()
        var backgroundArgb: Int? = null
        // The background comes from the nearest part that declares one.
        val bg = listOfNotNull(slide, layout, master).firstNotNullOfOrNull { p ->
            p.root.child(NS_P, "cSld")?.child(NS_P, "bg")?.let { bg ->
                bg.child(NS_P, "bgPr")?.let { theme.fillIn(it, part = p.path) }
                    ?: bg.child(NS_P, "bgRef")?.let { theme.fillFromRef(it, part = p.path) }
            }
        }
        val full = Box(0, 0, widthEmu, heightEmu)
        when (bg) {
            is Fill.Solid -> backgroundArgb = bg.argb
            is Fill.Shaded -> elements += Item.Shape(full, "rect", null, null, 0, null, gradient = bg.gradient)
            is Fill.Picture -> ctx.rels(bg.part)[bg.embedId]?.let { elements += Item.Picture(full, imageEntry = it) }
            else -> {}
        }

        // "Hide background graphics" on a slide hides both the layout's and the
        // master's own shapes; a layout can hide just the master's.
        val showBackgroundShapes = slide.root.getAttribute("showMasterSp") != "0"
        val showMasterShapes = showBackgroundShapes && layout?.root?.getAttribute("showMasterSp") != "0"
        if (showMasterShapes && master != null) master.tree?.let { elements += shapeTree(it, master.path, ctx, decoration = true) }
        if (showBackgroundShapes && layout != null) layout.tree?.let { elements += shapeTree(it, layout.path, ctx, decoration = true) }
        slide.tree?.let { elements += shapeTree(it, path, ctx, decoration = false) }
        return Slide(backgroundArgb, elements)
    }

    /**
     * Walks a shape tree. Groups nest and transform their children, so this recurses.
     * [decoration] is true for a master or layout: its placeholders are "click to add
     * title" prompts that are never shown themselves, only inherited from.
     */
    private fun shapeTree(tree: DomElement, part: String, ctx: SlideContext, decoration: Boolean, depth: Int = 0): List<Item> {
        if (depth > Limits.MAX_NESTING) return emptyList()
        val out = mutableListOf<Item>()
        for (child in tree.children()) {
            when (child.localName) {
                "sp", "cxnSp" -> {
                    val isPlaceholder = placeholderOf(child) != null
                    if (!(decoration && isPlaceholder)) out += shape(child, NS_P, part, ctx)
                }
                "pic" -> picture(child, part, ctx)?.let { out += it }
                "graphicFrame" -> out += graphicFrame(child, part, ctx)
                "grpSp" -> out += group(child, part, ctx, decoration, depth + 1)
                // Newer content (equations, some 3-D models) comes with a fallback
                // older readers understand, usually a picture of it.
                "AlternateContent" -> {
                    val branch = child.child(NS_MC, "Fallback")?.takeIf { it.children().isNotEmpty() }
                        ?: child.child(NS_MC, "Choice")
                    branch?.let { out += shapeTree(it, part, ctx, decoration, depth + 1) }
                }
            }
        }
        return out
    }

    /**
     * A group has a child-space rectangle (chOff/chExt) and a placed rectangle
     * (off/ext); its children are in child space and are mapped onto the placed one.
     */
    private fun group(node: DomElement, part: String, ctx: SlideContext, decoration: Boolean, depth: Int): List<Item> {
        val children = shapeTree(node, part, ctx, decoration, depth)
        val xfrm = node.child(NS_P, "grpSpPr")?.child(NS_A, "xfrm") ?: return children
        val off = xfrm.child(NS_A, "off"); val ext = xfrm.child(NS_A, "ext")
        val chOff = xfrm.child(NS_A, "chOff"); val chExt = xfrm.child(NS_A, "chExt")
        if (off == null || ext == null || chOff == null || chExt == null) return children
        val sx = ext.long("cx").toDouble() / chExt.long("cx").coerceAtLeast(1)
        val sy = ext.long("cy").toDouble() / chExt.long("cy").coerceAtLeast(1)
        return children.map { it.mapped(chOff.long("x"), chOff.long("y"), off.long("x"), off.long("y"), sx, sy) }
    }

    private fun placeholderOf(node: DomElement): DomElement? =
        node.children().firstOrNull { it.localName?.startsWith("nv") == true }?.child(NS_P, "nvPr")?.child(NS_P, "ph")

    /**
     * A shape or connector. [ns] is the namespace of its own wrapper elements: p for
     * slides, dsp for SmartArt drawings (whose children are otherwise DrawingML).
     */
    private fun shape(node: DomElement, ns: String, part: String, ctx: SlideContext, origin: Box? = null): List<Item> {
        val theme = ctx.theme
        val spPr = node.child(ns, "spPr")
        val ph = if (ns == NS_P) placeholderOf(node) else null
        val phType = ph?.let { it.attr("type") ?: "obj" }
        val phIdx = ph?.attr("idx")
        val inherited = if (phType != null) ctx.inherited(phType, phIdx) else emptyList()

        // Most placeholders carry no <a:xfrm> and take the layout's (or master's) position.
        val box = (spPr?.let { boxOf(it) } ?: inherited.firstNotNullOfOrNull { it.spPr?.let(::boxOf) } ?: return emptyList())
            .let { if (origin != null) Box(it.x + origin.x, it.y + origin.y, it.w, it.h) else it }
        val xfrm = spPr?.child(NS_A, "xfrm")
        val geomSource = spPr?.takeIf { it.child(NS_A, "prstGeom") != null || it.child(NS_A, "custGeom") != null }
            ?: inherited.firstNotNullOfOrNull { p -> p.spPr?.takeIf { it.child(NS_A, "prstGeom") != null || it.child(NS_A, "custGeom") != null } }
        val geom = geomSource?.child(NS_A, "custGeom")?.let { Geometry.custom(it, box.w, box.h) }
            ?: geomSource?.child(NS_A, "prstGeom")?.let { Geometry.preset(it.getAttribute("prst"), it.child(NS_A, "avLst"), box.w, box.h) }
            ?: Geometry.Result(if (node.localName == "cxnSp") "line" else "rect", null)

        val style = node.child(ns, "style") ?: inherited.firstNotNullOfOrNull { it.style }
        val fill = theme.fillIn(spPr, part = part)
            ?: inherited.firstNotNullOfOrNull { theme.fillIn(it.spPr, part = it.part) }
            ?: theme.fillFromRef(style?.child(NS_A, "fillRef"), part)
            ?: Fill.None

        val ln = spPr?.child(NS_A, "ln")
        val lnSources = listOfNotNull(ln) + inherited.mapNotNull { it.spPr?.child(NS_A, "ln") }
        val lnRef = style?.child(NS_A, "lnRef")
        val refColor = theme.colorIn(lnRef)
        val refLine = lnRef?.getAttribute("idx")?.toIntOrNull()?.let { theme.lineStyle(it) }
        val lnFill = lnSources.firstNotNullOfOrNull { theme.fillIn(it) }
            ?: refLine?.let { theme.fillIn(it, refColor) }
            ?: refColor?.let { Fill.Solid(it) }
        val strokeColor = (lnFill as? Fill.Solid)?.argb ?: (lnFill as? Fill.Shaded)?.gradient?.colors?.first()
        val strokeWidth = lnSources.firstNotNullOfOrNull { it.longOrNull("w") } ?: refLine?.longOrNull("w") ?: DEFAULT_LINE_WIDTH_EMU
        val dash = lnSources.firstNotNullOfOrNull { it.child(NS_A, "prstDash")?.attr("val") } ?: refLine?.child(NS_A, "prstDash")?.attr("val")
        fun arrow(end: String) = (lnSources + listOfNotNull(refLine)).firstNotNullOfOrNull { it.child(NS_A, end)?.attr("type") }
            ?.let { it != "none" } ?: false

        // Effects: the shape's own list (an empty one means none), else its
        // placeholder's, else the theme effect style its style points at.
        val effects = (listOfNotNull(spPr) + inherited.mapNotNull { it.spPr }).firstNotNullOfOrNull { it.child(NS_A, "effectLst") }
            ?: style?.child(NS_A, "effectRef")?.getAttribute("idx")?.toIntOrNull()?.let { theme.effectStyle(it) }
        val shadow = theme.shadowOf(effects, theme.colorIn(style?.child(NS_A, "effectRef")))

        val fontRef = style?.child(NS_A, "fontRef")
        val textNode = node.child(ns, "txBody")
        val chain = if (phType != null) {
            StyleChain(inherited.map { theme.levelStyles(it.txBody?.child(NS_A, "lstStyle")) } + listOf(ctx.categoryStyle(phType)))
        } else StyleChain.of(ctx.defaultStyle, ctx.otherStyle)
        val body = inherited.foldRight(BodyProps()) { p, acc -> BodyProps.of(p.txBody?.child(NS_A, "bodyPr")).overlaidOn(acc) }
        val text = textNode?.let { theme.textBody(it, chain, body, theme.colorIn(fontRef)) }
            ?.takeIf { t -> t.paragraphs.any { !it.isEmpty } }

        // SmartArt drawings give the text its own rectangle.
        val txXfrm = node.child(ns, "txXfrm")?.let { boxOf(it, direct = true) }
            ?.let { if (origin != null) Box(it.x + origin.x, it.y + origin.y, it.w, it.h) else it }

        val out = mutableListOf<Item>()
        if (fill is Fill.Picture) {
            ctx.rels(fill.part)[fill.embedId]?.let { out += Item.Picture(box, imageEntry = it, rotation = rotationOf(spPr)) }
        }
        out += Item.Shape(
            box = box,
            geometry = geom.geometry,
            fillArgb = if (geom.open) null else (fill as? Fill.Solid)?.argb,
            strokeArgb = strokeColor,
            strokeWidthEmu = if (strokeColor != null) strokeWidth else 0L,
            text = if (txXfrm == null) text else null,
            rotation = rotationOf(spPr),
            flipH = xfrm?.getAttribute("flipH") == "1",
            flipV = xfrm?.getAttribute("flipV") == "1",
            headArrow = arrow("headEnd"),
            tailArrow = arrow("tailEnd"),
            dashed = dash != null && dash != "solid",
            outline = geom.outline,
            gradient = if (geom.open) null else (fill as? Fill.Shaded)?.gradient,
            shadow = shadow
        )
        if (txXfrm != null && text != null) {
            out += Item.Shape(txXfrm, null, null, null, 0, text, rotation = rotationOf(spPr))
        }
        return out
    }

    private fun picture(node: DomElement, part: String, ctx: SlideContext, frame: Box? = null): Item.Picture? {
        val spPr = node.child(NS_P, "spPr")
        val ph = placeholderOf(node)
        val box = frame ?: spPr?.let { boxOf(it) }
            ?: ph?.let { ctx.inherited(it.attr("type") ?: "obj", it.attr("idx")).firstNotNullOfOrNull { p -> p.spPr?.let(::boxOf) } }
            ?: return null
        val blipFill = node.child(NS_P, "blipFill") ?: return null
        val blip = blipFill.child(NS_A, "blip") ?: return null
        // Office 365 icons can be SVG only, with no raster fallback in r:embed.
        val embedId = blip.getAttributeNS(NS_R, "embed").ifBlank { null }
            ?: blip.descendants(NS_ASVG, "svgBlip").firstOrNull()?.getAttributeNS(NS_R, "embed")?.ifBlank { null }
            ?: return null
        val target = ctx.rels(part)[embedId] ?: return null
        // srcRect trims each edge by a fraction (in 1/1000 %); negative values pad.
        val crop = blipFill.child(NS_A, "srcRect")?.let { r ->
            fun f(a: String) = (r.longOrNull(a) ?: 0L) / 100000f
            Crop(f("l"), f("t"), f("r"), f("b")).takeIf { it.left != 0f || it.top != 0f || it.right != 0f || it.bottom != 0f }
        }
        val shadow = ctx.theme.shadowOf(spPr?.child(NS_A, "effectLst")
            ?: node.child(NS_P, "style")?.child(NS_A, "effectRef")?.getAttribute("idx")?.toIntOrNull()?.let { ctx.theme.effectStyle(it) })
        return Item.Picture(box = box, imageEntry = target, rotation = spPr?.let { rotationOf(it) } ?: 0f, crop = crop, shadow = shadow)
    }

    private fun graphicFrame(node: DomElement, part: String, ctx: SlideContext): List<Item> {
        val xfrm = node.child(NS_P, "xfrm") ?: return emptyList()
        val off = xfrm.child(NS_A, "off") ?: return emptyList()
        val ext = xfrm.child(NS_A, "ext") ?: return emptyList()
        val box = Box(off.long("x"), off.long("y"), ext.long("cx"), ext.long("cy"))
        val data = node.first(NS_A, "graphicData") ?: return emptyList()

        data.child(NS_A, "tbl")?.let { return listOfNotNull(table(it, box, ctx)) }
        data.child(NS_DGM, "relIds")?.let { return smartArt(it, box, part, ctx) }
        // A chart part caches the values it shows; it is drawn as shapes.
        data.child(NS_C, "chart")?.let { ref ->
            val chartPath = ctx.rels(part)[ref.getAttributeNS(NS_R, "id")] ?: return emptyList()
            val doc = ctx.cache.xml(chartPath) ?: return emptyList()
            return ChartParser.parse(doc, box, ctx.theme)
        }
        // An embedded OLE object (an Excel sheet, an equation) carries a preview picture.
        data.descendants(NS_P, "pic").firstOrNull()?.let { pic -> return listOfNotNull(picture(pic, part, ctx, frame = box)) }
        return emptyList()
    }

    /**
     * SmartArt is stored as data plus layout rules, but PowerPoint also saves the
     * shapes it drew (a "drawing" part, named from the data part's dataModelExt),
     * in the frame's coordinate space. Those are drawn like ordinary shapes.
     */
    private fun smartArt(relIds: DomElement, frame: Box, part: String, ctx: SlideContext): List<Item> {
        val rels = ctx.rels(part)
        val dataPath = rels[relIds.getAttributeNS(NS_R, "dm")] ?: return emptyList()
        val drawingId = ctx.cache.xml(dataPath)?.descendants(NS_DSP, "dataModelExt")?.firstOrNull()?.attr("relId")
        val drawingPath = drawingId?.let { rels[it] }
            ?: ctx.rels(dataPath).values.firstOrNull { "drawing" in it.substringAfterLast('/') }
            ?: return emptyList()
        val tree = ctx.cache.xml(drawingPath)?.first(NS_DSP, "spTree") ?: return emptyList()
        val origin = Box(frame.x, frame.y, 0, 0)
        val out = mutableListOf<Item>()
        fun walk(t: DomElement, depth: Int) {
            if (depth > Limits.MAX_NESTING) return
            for (child in t.children()) when (child.localName) {
                "sp" -> out += shape(child, NS_DSP, drawingPath, ctx, origin)
                "grpSp" -> walk(child, depth + 1)
            }
        }
        walk(tree, 0)
        return out
    }

    /**
     * A table, with cell fills and text colors from its table style (tableStyles.xml,
     * or PowerPoint's default "Medium Style 2 – Accent 1" when the style isn't in the
     * file) unless a cell sets its own.
     */
    private fun table(tbl: DomElement, box: Box, ctx: SlideContext): Item.Table? {
        val theme = ctx.theme
        val grid = tbl.child(NS_A, "tblGrid")?.all(NS_A, "gridCol")?.map { it.long("w") } ?: return null
        val tblPr = tbl.child(NS_A, "tblPr")
        val styleId = tblPr?.child(NS_A, "tableStyleId")?.textContent?.trim()
        // No style id means no style (tableStyles.xml's "def" is only what new
        // tables get); the default style is built in for when it's named but not stored.
        val style = styleId?.let { ctx.tableStyles[it] ?: if (it == DEFAULT_TABLE_STYLE) defaultTableStyle() else null }
        fun flag(name: String) = tblPr?.getAttribute(name) == "1"
        val rowsXml = tbl.all(NS_A, "tr")
        val lastRow = rowsXml.size - 1

        fun parts(r: Int, c: Int, lastCol: Int): List<DomElement> {
            style ?: return emptyList()
            val names = mutableListOf<String>()
            if (flag("firstRow") && r == 0) names += "firstRow"
            if (flag("lastRow") && r == lastRow) names += "lastRow"
            if (flag("firstCol") && c == 0) names += "firstCol"
            if (flag("lastCol") && c == lastCol) names += "lastCol"
            if (flag("bandRow")) {
                val band = r - if (flag("firstRow")) 1 else 0
                if (band >= 0) names += if (band % 2 == 0) "band1H" else "band2H"
            }
            if (flag("bandCol")) names += if ((c - if (flag("firstCol")) 1 else 0) % 2 == 0) "band1V" else "band2V"
            names += "wholeTbl"
            return names.mapNotNull { style.child(NS_A, it) }
        }

        var gridArgb: Int? = null
        val rowHeights = mutableListOf<Long>()
        val rows = rowsXml.mapIndexed { r, tr ->
            rowHeights += tr.long("h")
            val tcs = tr.all(NS_A, "tc")
            tcs.mapIndexed { c, tc ->
                // hMerge/vMerge mark the continuation cells of a merge; the spanning
                // cell keeps its content and these are holes to skip when drawing.
                if (tc.getAttribute("hMerge") == "1" || tc.getAttribute("vMerge") == "1") return@mapIndexed null
                val tcPr = tc.child(NS_A, "tcPr")
                val styleParts = parts(r, c, tcs.size - 1)
                val fill = theme.fillIn(tcPr) ?: styleParts.firstNotNullOfOrNull { p ->
                    p.child(NS_A, "tcStyle")?.let { s -> theme.fillIn(s.child(NS_A, "fill")) ?: theme.fillFromRef(s.child(NS_A, "fillRef")) }
                }
                val txStyle = styleParts.mapNotNull { it.child(NS_A, "tcTxStyle") }
                val textStyle = LevelStyle(
                    colorArgb = txStyle.firstNotNullOfOrNull { theme.colorIn(it) ?: theme.colorIn(it.child(NS_A, "fontRef")) },
                    bold = txStyle.firstNotNullOfOrNull { it.attr("b") }?.let { it == "on" }
                )
                if (gridArgb == null) {
                    gridArgb = listOf("lnB", "lnT", "lnL", "lnR").firstNotNullOfOrNull { tcPr?.child(NS_A, it) }
                        ?.let { (theme.fillIn(it) as? Fill.Solid)?.argb }
                        ?: styleParts.firstNotNullOfOrNull { p ->
                            p.child(NS_A, "tcStyle")?.child(NS_A, "tcBdr")?.children()?.firstNotNullOfOrNull { b ->
                                (theme.fillIn(b.child(NS_A, "ln")) as? Fill.Solid)?.argb
                            }
                        }
                }
                TableCell(
                    text = tc.child(NS_A, "txBody")?.let {
                        theme.textBody(it, StyleChain.of(mapOf(0 to textStyle), ctx.otherStyle), BodyProps(
                            anchor = when (tcPr?.getAttribute("anchor")) {
                                "ctr" -> io.github.baeseungwon1010.officecanvas.slide.VAnchor.MIDDLE
                                "b" -> io.github.baeseungwon1010.officecanvas.slide.VAnchor.BOTTOM
                                else -> null
                            },
                            insets = listOf(tcPr?.longOrNull("marL") ?: 91440, tcPr?.longOrNull("marT") ?: 45720,
                                tcPr?.longOrNull("marR") ?: 91440, tcPr?.longOrNull("marB") ?: 45720)
                        ))
                    },
                    fillArgb = (fill as? Fill.Solid)?.argb ?: (fill as? Fill.Shaded)?.gradient?.colors?.first(),
                    colSpan = tc.getAttribute("gridSpan").toIntOrNull() ?: 1,
                    rowSpan = tc.getAttribute("rowSpan").toIntOrNull() ?: 1
                )
            }
        }
        if (rows.isEmpty()) return null
        return Item.Table(box, grid, rowHeights, rows, gridArgb = gridArgb)
    }

    private const val DEFAULT_TABLE_STYLE = "{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}"

    /** PowerPoint's default table style, which decks reference without storing it. */
    private fun defaultTableStyle(): DomElement {
        fun fill(color: String) = "<a:fill><a:solidFill>$color</a:solidFill></a:fill>"
        val accent = "<a:schemeClr val=\"accent1\"/>"
        fun tint(v: Int) = "<a:schemeClr val=\"accent1\"><a:tint val=\"$v\"/></a:schemeClr>"
        val border = "<a:tcBdr><a:insideH><a:ln w=\"12700\"><a:solidFill><a:schemeClr val=\"lt1\"/></a:solidFill></a:ln></a:insideH></a:tcBdr>"
        val xml = """<a:tblStyle xmlns:a="$NS_A" styleId="$DEFAULT_TABLE_STYLE">
            <a:wholeTbl><a:tcTxStyle><a:schemeClr val="dk1"/></a:tcTxStyle><a:tcStyle>$border${fill(tint(20000))}</a:tcStyle></a:wholeTbl>
            <a:band1H><a:tcStyle>${fill(tint(40000))}</a:tcStyle></a:band1H>
            <a:band1V><a:tcStyle>${fill(tint(40000))}</a:tcStyle></a:band1V>
            <a:lastCol><a:tcTxStyle b="on"><a:schemeClr val="lt1"/></a:tcTxStyle><a:tcStyle>${fill(accent)}</a:tcStyle></a:lastCol>
            <a:firstCol><a:tcTxStyle b="on"><a:schemeClr val="lt1"/></a:tcTxStyle><a:tcStyle>${fill(accent)}</a:tcStyle></a:firstCol>
            <a:lastRow><a:tcTxStyle b="on"><a:schemeClr val="lt1"/></a:tcTxStyle><a:tcStyle>${fill(accent)}</a:tcStyle></a:lastRow>
            <a:firstRow><a:tcTxStyle b="on"><a:schemeClr val="lt1"/></a:tcTxStyle><a:tcStyle>${fill(accent)}</a:tcStyle></a:firstRow>
            </a:tblStyle>"""
        return SafeXml.parse(xml.toByteArray())!!.documentElement
    }

    private fun boxOf(spPr: DomElement, direct: Boolean = false): Box? {
        val xfrm = if (direct) spPr else spPr.child(NS_A, "xfrm") ?: return null
        val off = xfrm.child(NS_A, "off") ?: return null
        val ext = xfrm.child(NS_A, "ext") ?: return null
        return Box(off.long("x"), off.long("y"), ext.long("cx"), ext.long("cy"))
    }

    /** Rotation is stored in 60000ths of a degree. */
    private fun rotationOf(spPr: DomElement?): Float =
        spPr?.child(NS_A, "xfrm")?.longOrNull("rot")?.let { it / 60000f } ?: 0f

    private fun collectPlaceholders(tree: DomElement, part: String, depth: Int = 0): List<Placeholder> {
        val out = mutableListOf<Placeholder>()
        if (depth > Limits.MAX_NESTING) return out
        for (child in tree.children()) {
            when (child.localName) {
                "sp", "pic", "graphicFrame" -> placeholderOf(child)?.let { ph ->
                    out += Placeholder(ph.attr("type") ?: "obj", ph.attr("idx"), child, part)
                }
                "grpSp" -> out += collectPlaceholders(child, part, depth + 1)
            }
        }
        return out
    }

    /** The master only has title, body and the footer placeholders. */
    private fun masterType(type: String): String = when (type) {
        "title", "ctrTitle" -> "title"
        "dt", "ftr", "sldNum", "hdr" -> type
        else -> "body"
    }

    private fun Item.mapped(cx: Long, cy: Long, gx: Long, gy: Long, sx: Double, sy: Double): Item {
        fun map(b: Box) = Box(gx + ((b.x - cx) * sx).toLong(), gy + ((b.y - cy) * sy).toLong(), (b.w * sx).toLong(), (b.h * sy).toLong())
        return when (this) {
            is Item.Shape -> copy(box = map(box), shadow = shadow?.let { it.copy(dxEmu = (it.dxEmu * sx).toLong(), dyEmu = (it.dyEmu * sy).toLong()) })
            is Item.Picture -> copy(box = map(box), shadow = shadow?.let { it.copy(dxEmu = (it.dxEmu * sx).toLong(), dyEmu = (it.dyEmu * sy).toLong()) })
            is Item.Table -> copy(box = map(box))
        }
    }
}
