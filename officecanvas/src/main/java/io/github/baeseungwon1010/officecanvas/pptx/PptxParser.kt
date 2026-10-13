package io.github.baeseungwon1010.officecanvas.pptx

import io.github.baeseungwon1010.officecanvas.slide.*

import org.w3c.dom.Document
import org.w3c.dom.Element as DomElement
import org.w3c.dom.Node
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads a .pptx into a [Deck].
 *
 * A .pptx is a zip of XML, so this needs nothing but java.util.zip and javax.xml —
 * both present on Android and on a plain JVM, which is what lets the parser be
 * tested against real decks without a device.
 *
 * A slide's actual appearance is rarely self-contained: it draws through a chain
 * (slide -> slideLayout -> slideMaster -> theme), inheriting position, decoration,
 * and text style at each hop unless it overrides one. This parser resolves that
 * chain once per slide rather than treating the slide's own XML as the whole story
 * — the alternative is a converter that only renders decks nobody actually made
 * from a template, which is close to none of them.
 */
internal object PptxParser {

    private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

    /** PowerPoint's own default outline weight (1pt) when <a:ln> omits "w". */
    private const val DEFAULT_LINE_WIDTH_EMU = 12700L

    class ParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /** Resolved theme palette: scheme name ("tx1", "accent1", ...) to ARGB. */
    private class Theme(private val colors: Map<String, Int>) {
        /**
         * bg1/tx1/bg2/tx2 are logical slots that a slide master's <p:clrMap> can
         * repoint at any of dk1/lt1/dk2/lt2 (a dark-background master often maps
         * bg1 -> dk1 instead of the "obvious" lt1). Reading that map is more
         * machinery than a converter needs for the gain, so the near-universal
         * default mapping is used directly; a master with an unusual clrMap will
         * see incorrect contrast rather than a missing color.
         */
        fun resolve(schemeName: String): Int? {
            val key = when (schemeName) {
                "bg1" -> "lt1"; "tx1" -> "dk1"
                "bg2" -> "lt2"; "tx2" -> "dk2"
                "phClr" -> return null // inherited placeholder color; not tracked here
                else -> schemeName
            }
            return colors[key]
        }

        companion object {
            val EMPTY = Theme(emptyMap())
        }
    }

    /** Placeholder position/size inherited from a slide's layout, keyed by the
     *  <p:ph> type+idx a shape declares. Only geometry is inherited (a shape with
     *  no xfrm of its own would otherwise vanish); text always comes from the
     *  slide, never from the layout's placeholder prompt text. */
    private class PlaceholderGeometry(
        private val byTypeAndIdx: Map<String, Box>,
        private val byType: Map<String, Box>
    ) {
        fun boxFor(type: String, idx: String?): Box? =
            idx?.let { byTypeAndIdx["$type|$it"] } ?: byType[type]

        companion object {
            val EMPTY = PlaceholderGeometry(emptyMap(), emptyMap())
        }
    }

    /** One level's worth of inherited run/paragraph defaults. Every field is nullable
     *  because "not specified at this level" and "specified as off" are different —
     *  the former keeps looking further up the chain, in [TextStyles.stylesFor]. */
    private data class LevelStyle(
        val sizePt: Float? = null,
        val bold: Boolean? = null,
        val italic: Boolean? = null,
        val underline: Boolean? = null,
        val colorArgb: Int? = null,
        val align: HAlign? = null,
        val bulletChar: String? = null,
        val noBullet: Boolean = false
    ) {
        /** Layout-level values win; only what the layout leaves unset falls through
         *  to the master's style for the same indent level. */
        fun overlaidOn(master: LevelStyle): LevelStyle = LevelStyle(
            sizePt ?: master.sizePt, bold ?: master.bold, italic ?: master.italic,
            underline ?: master.underline, colorArgb ?: master.colorArgb,
            align ?: master.align, bulletChar ?: master.bulletChar, noBullet || master.noBullet
        )
    }

    /** Resolves the run/paragraph defaults a placeholder's text should use when its
     *  own <a:rPr>/<a:pPr> leaves something unset — the layout's copy of that exact
     *  placeholder first, the master's title/body/other style as the fallback. */
    private class TextStyles(
        private val layoutPlaceholderStyles: Map<String, Map<Int, LevelStyle>>,
        private val masterTitleStyle: Map<Int, LevelStyle>,
        private val masterBodyStyle: Map<Int, LevelStyle>,
        private val masterOtherStyle: Map<Int, LevelStyle>
    ) {
        fun stylesFor(phType: String?, idx: String?): Map<Int, LevelStyle> {
            val layoutStyle = idx?.let { layoutPlaceholderStyles["$phType|$it"] } ?: phType?.let { layoutPlaceholderStyles[it] }
            val masterStyle = when (phType) {
                "title", "ctrTitle" -> masterTitleStyle
                null -> masterOtherStyle
                else -> masterBodyStyle
            }
            val levels = (layoutStyle?.keys.orEmpty() + masterStyle.keys)
            return levels.associateWith { lvl -> (layoutStyle?.get(lvl) ?: LevelStyle()).overlaidOn(masterStyle[lvl] ?: LevelStyle()) }
        }

        companion object {
            val EMPTY = TextStyles(emptyMap(), emptyMap(), emptyMap(), emptyMap())
        }
    }

    /** Everything a slide inherits through slide -> layout -> master -> theme,
     *  resolved once so the rest of parsing only ever reads from it. */
    private class SlideContext(
        val theme: Theme,
        val placeholders: PlaceholderGeometry,
        val textStyles: TextStyles,
        val backgroundElements: List<Element>
    ) {
        companion object {
            val EMPTY = SlideContext(Theme.EMPTY, PlaceholderGeometry.EMPTY, TextStyles.EMPTY, emptyList())
        }
    }

    fun parse(file: File): Deck = ZipFile(file).use { zip ->
        val presentation = zip.xml("ppt/presentation.xml")
            ?: throw ParseException("not a .pptx: ppt/presentation.xml is missing")

        val size = presentation.first(P, "sldSz")
        val widthEmu = size?.getAttribute("cx")?.toLongOrNull() ?: 9144000L
        val heightEmu = size?.getAttribute("cy")?.toLongOrNull() ?: 6858000L

        // Slide order lives in presentation.xml as relationship ids, not as file names:
        // slide7.xml can perfectly well be the second slide shown.
        val presRels = zip.rels("ppt/presentation.xml")
        val slidePaths = presentation.descendants(P, "sldId").mapNotNull { node ->
            presRels[node.getAttributeNS(R, "id")]?.let { resolve("ppt", it) }
        }

        val slides = slidePaths.map { path ->
            try {
                parseSlide(zip, path)
            } catch (e: Exception) {
                // One unreadable slide should cost one slide, not the whole deck.
                Slide(backgroundArgb = null, elements = emptyList())
            }
        }
        Deck(widthEmu, heightEmu, slides)
    }

    private fun parseSlide(zip: ZipFile, path: String): Slide {
        val doc = zip.xml(path) ?: return Slide(null, emptyList())
        val rels = zip.rels(path)
        val dir = path.substringBeforeLast('/')
        val ctx = loadSlideContext(zip, path, rels)

        val tree = doc.first(P, "cSld")?.first(P, "spTree")
        val ownElements = tree?.let {
            parseShapeTree(it, rels, dir, Box(0, 0, 0, 0), ctx, skipPlaceholders = false)
        } ?: emptyList()

        // The slide background is a single color in the model; a gradient one is
        // approximated by its first stop rather than dropped to white.
        val background = doc.first(P, "bg")?.first(P, "bgPr")?.let { bgPr ->
            solidFill(bgPr, ctx.theme) ?: gradientFill(bgPr, ctx.theme)?.colors?.firstOrNull()
        }
        return Slide(background, ctx.backgroundElements + ownElements)
    }

    /**
     * Resolves the full inheritance chain for one slide: theme colors, placeholder
     * positions, text style defaults, and the master/layout's own decoration (a
     * logo, a rule under the title, page furniture) that is not part of the slide's
     * own XML at all but is still part of what the slide looks like.
     */
    private fun loadSlideContext(zip: ZipFile, slidePath: String, slideRels: Map<String, String>): SlideContext {
        val layoutPath = slideRels.values.firstOrNull { it.contains("slideLayout") }
            ?.let { resolve(slidePath.substringBeforeLast('/'), it) } ?: return SlideContext.EMPTY
        val layoutDoc = zip.xml(layoutPath) ?: return SlideContext.EMPTY
        val layoutRels = zip.rels(layoutPath)

        val masterPath = layoutRels.values.firstOrNull { it.contains("slideMaster") }
            ?.let { resolve(layoutPath.substringBeforeLast('/'), it) } ?: return SlideContext.EMPTY
        val masterDoc = zip.xml(masterPath) ?: return SlideContext.EMPTY
        val masterRels = zip.rels(masterPath)

        val themePath = masterRels.values.firstOrNull { it.contains("theme") }
            ?.let { resolve(masterPath.substringBeforeLast('/'), it) }
        val theme = themePath?.let { loadTheme(zip, it) } ?: Theme.EMPTY

        val placeholders = loadPlaceholderGeometry(layoutDoc)
        val textStyles = loadTextStyles(layoutDoc, masterDoc, theme)

        // A slide can hide the master's own shapes (<p:sld showMasterSp="0">) while
        // still showing the layout's; there is no equivalent switch for the layout.
        val showMasterShapes = zip.xml(slidePath)?.documentElement?.getAttribute("showMasterSp") != "0"
        val masterDir = masterPath.substringBeforeLast('/')
        val layoutDir = layoutPath.substringBeforeLast('/')
        val masterBg = if (showMasterShapes) {
            masterDoc.first(P, "cSld")?.first(P, "spTree")?.let {
                parseShapeTree(it, masterRels, masterDir, Box(0, 0, 0, 0), SlideContext(theme, PlaceholderGeometry.EMPTY, TextStyles.EMPTY, emptyList()), skipPlaceholders = true)
            }.orEmpty()
        } else emptyList()
        val layoutBg = layoutDoc.first(P, "cSld")?.first(P, "spTree")?.let {
            parseShapeTree(it, layoutRels, layoutDir, Box(0, 0, 0, 0), SlideContext(theme, PlaceholderGeometry.EMPTY, TextStyles.EMPTY, emptyList()), skipPlaceholders = true)
        }.orEmpty()

        return SlideContext(theme, placeholders, textStyles, masterBg + layoutBg)
    }

    private fun loadTheme(zip: ZipFile, themePath: String): Theme {
        val clrScheme = zip.xml(themePath)?.first(A, "themeElements")?.first(A, "clrScheme") ?: return Theme.EMPTY
        val colors = mutableMapOf<String, Int>()
        for (slot in clrScheme.children()) {
            val name = slot.localName ?: continue
            val srgb = slot.child(A, "srgbClr")?.getAttribute("val")?.ifBlank { null }
                ?: slot.child(A, "sysClr")?.getAttribute("lastClr")?.ifBlank { null }
                ?: continue
            srgb.toIntOrNull(16)?.let { colors[name] = (0xFF shl 24) or it }
        }
        return Theme(colors)
    }

    /** Reads the slide's layout and maps each declared placeholder to its position,
     *  so a slide shape that omits its own <a:xfrm> (very common for title/body
     *  placeholders using a layout's default position) can still be placed. */
    private fun loadPlaceholderGeometry(layoutDoc: Document): PlaceholderGeometry {
        val byTypeAndIdx = mutableMapOf<String, Box>()
        val byType = mutableMapOf<String, Box>()
        val tree = layoutDoc.first(P, "cSld")?.first(P, "spTree") ?: return PlaceholderGeometry.EMPTY
        for (sp in tree.all(P, "sp")) {
            val ph = sp.first(P, "nvSpPr")?.first(P, "nvPr")?.first(P, "ph") ?: continue
            val box = sp.first(P, "spPr")?.let { boxOf(it) } ?: continue
            val type = ph.getAttribute("type").ifBlank { "body" }
            val idx = ph.getAttribute("idx").ifBlank { null }
            if (idx != null) byTypeAndIdx["$type|$idx"] = box
            byType.putIfAbsent(type, box)
        }
        return PlaceholderGeometry(byTypeAndIdx, byType)
    }

    private fun loadTextStyles(layoutDoc: Document, masterDoc: Document, theme: Theme): TextStyles {
        val layoutPlaceholderStyles = mutableMapOf<String, Map<Int, LevelStyle>>()
        val layoutTree = layoutDoc.first(P, "cSld")?.first(P, "spTree")
        for (sp in layoutTree?.all(P, "sp").orEmpty()) {
            val ph = sp.first(P, "nvSpPr")?.first(P, "nvPr")?.first(P, "ph") ?: continue
            val lstStyle = sp.first(P, "txBody")?.first(A, "lstStyle") ?: continue
            val levels = parseLevelStyles(lstStyle, theme)
            if (levels.isEmpty()) continue
            val type = ph.getAttribute("type").ifBlank { "body" }
            val idx = ph.getAttribute("idx").ifBlank { null }
            if (idx != null) layoutPlaceholderStyles["$type|$idx"] = levels
            layoutPlaceholderStyles.putIfAbsent(type, levels)
        }

        val txStyles = masterDoc.first(P, "txStyles")
        return TextStyles(
            layoutPlaceholderStyles,
            masterTitleStyle = txStyles?.first(P, "titleStyle")?.let { parseLevelStyles(it, theme) }.orEmpty(),
            masterBodyStyle = txStyles?.first(P, "bodyStyle")?.let { parseLevelStyles(it, theme) }.orEmpty(),
            masterOtherStyle = txStyles?.first(P, "otherStyle")?.let { parseLevelStyles(it, theme) }.orEmpty()
        )
    }

    /** Reads <a:lvl1pPr>..<a:lvl9pPr> (indent levels 0..8) from a <p:titleStyle>,
     *  <p:bodyStyle>, <p:otherStyle>, or a placeholder's own <a:lstStyle>. */
    private fun parseLevelStyles(container: DomElement, theme: Theme): Map<Int, LevelStyle> {
        val out = mutableMapOf<Int, LevelStyle>()
        for (level in 1..9) {
            val lvlNode = container.child(A, "lvl${level}pPr") ?: continue
            val defRPr = lvlNode.child(A, "defRPr")
            out[level - 1] = LevelStyle(
                sizePt = defRPr?.getAttribute("sz")?.toLongOrNull()?.let { it / 100f },
                bold = defRPr?.getAttribute("b")?.ifBlank { null }?.let { it == "1" },
                italic = defRPr?.getAttribute("i")?.ifBlank { null }?.let { it == "1" },
                underline = defRPr?.getAttribute("u")?.ifBlank { null }?.let { it != "none" },
                colorArgb = defRPr?.let { solidFill(it, theme) },
                align = when (lvlNode.getAttribute("algn")) {
                    "ctr" -> HAlign.CENTER; "r" -> HAlign.RIGHT; "just" -> HAlign.JUSTIFY; "l" -> HAlign.LEFT
                    else -> null
                },
                bulletChar = lvlNode.child(A, "buChar")?.getAttribute("char"),
                noBullet = lvlNode.child(A, "buNone") != null
            )
        }
        return out
    }

    /**
     * Walks a shape tree. Groups nest, and a group applies its own transform to the
     * children, so this recurses rather than iterating one level.
     *
     * [skipPlaceholders] is true when walking a master or layout for its background
     * decoration: a placeholder shape there is a "click to add title"-style prompt
     * that is never itself shown, only ever overridden by the slide, so it must not
     * be drawn as if it were content.
     */
    private fun parseShapeTree(
        tree: DomElement,
        rels: Map<String, String>,
        dir: String,
        offset: Box,
        ctx: SlideContext,
        skipPlaceholders: Boolean
    ): List<Element> {
        val out = mutableListOf<Element>()
        for (child in tree.children()) {
            when (child.localName) {
                "sp" -> {
                    val isPlaceholder = child.first(P, "nvSpPr")?.first(P, "nvPr")?.first(P, "ph") != null
                    if (!(skipPlaceholders && isPlaceholder)) {
                        parseShape(child, offset, ctx)?.let { out += it }
                    }
                }
                "pic" -> parsePicture(child, rels, dir, offset)?.let { out += it }
                "graphicFrame" -> parseGraphicFrame(child, offset, ctx.theme)?.let { out += it }
                "grpSp" -> out += parseGroup(child, rels, dir, offset, ctx, skipPlaceholders)
            }
        }
        return out
    }

    /**
     * A group has both a child-space rectangle (chOff/chExt) and a placed rectangle
     * (off/ext); children are expressed in child space and have to be mapped onto the
     * placed one. Ignoring that puts every grouped shape in the wrong place.
     */
    private fun parseGroup(
        node: DomElement,
        rels: Map<String, String>,
        dir: String,
        offset: Box,
        ctx: SlideContext,
        skipPlaceholders: Boolean
    ): List<Element> {
        val xfrm = node.first(P, "grpSpPr")?.first(A, "xfrm")
        val off = xfrm?.first(A, "off")
        val ext = xfrm?.first(A, "ext")
        val chOff = xfrm?.first(A, "chOff")
        val chExt = xfrm?.first(A, "chExt")

        val children = parseShapeTree(node, rels, dir, Box(0, 0, 0, 0), ctx, skipPlaceholders)
        if (off == null || ext == null || chOff == null || chExt == null) {
            return children.map { it.translated(offset.x, offset.y) }
        }

        val gx = off.long("x"); val gy = off.long("y")
        val gw = ext.long("cx"); val gh = ext.long("cy")
        val cx = chOff.long("x"); val cy = chOff.long("y")
        val cw = chExt.long("cx").coerceAtLeast(1)
        val ch = chExt.long("cy").coerceAtLeast(1)
        val sx = gw.toDouble() / cw
        val sy = gh.toDouble() / ch

        return children.map { element ->
            val b = element.box
            element.withBox(
                Box(
                    x = offset.x + gx + ((b.x - cx) * sx).toLong(),
                    y = offset.y + gy + ((b.y - cy) * sy).toLong(),
                    w = (b.w * sx).toLong(),
                    h = (b.h * sy).toLong()
                )
            )
        }
    }

    private fun parseShape(node: DomElement, offset: Box, ctx: SlideContext): Element.Shape? {
        val spPr = node.first(P, "spPr") ?: return null
        val ph = node.first(P, "nvSpPr")?.first(P, "nvPr")?.first(P, "ph")
        val phType = ph?.getAttribute("type")?.ifBlank { "body" }
        val phIdx = ph?.getAttribute("idx")?.ifBlank { null }

        // Most placeholders (title, body, subtitle...) carry no <a:xfrm> of their own
        // and rely entirely on the layout's position; only a shape with neither its
        // own geometry nor an inherited one is truly unplaceable.
        val box = boxOf(spPr) ?: phType?.let { ctx.placeholders.boxFor(it, phIdx) } ?: return null
        val geometry = spPr.first(A, "prstGeom")?.getAttribute("prst")
        val fill = solidFill(spPr, ctx.theme)
        val gradient = if (fill == null) gradientFill(spPr, ctx.theme) else null
        val line = spPr.first(A, "ln")
        return Element.Shape(
            box = box.translate(offset),
            geometry = geometry,
            fillArgb = fill,
            gradient = gradient,
            strokeArgb = line?.let { solidFill(it, ctx.theme) },
            // <a:ln> with no "w" attribute means "use the default weight", not "no
            // border" — a very common way to write a plain hairline outline. Only the
            // complete absence of <a:ln> should mean zero width.
            strokeWidthEmu = line?.let { it.getAttribute("w").toLongOrNull() ?: DEFAULT_LINE_WIDTH_EMU } ?: 0L,
            text = node.first(P, "txBody")?.let { textBody(it, ctx.theme, ctx.textStyles.stylesFor(phType, phIdx)) },
            rotation = rotationOf(spPr)
        )
    }

    private fun parsePicture(
        node: DomElement,
        rels: Map<String, String>,
        dir: String,
        offset: Box
    ): Element.Picture? {
        val spPr = node.first(P, "spPr") ?: return null
        val box = boxOf(spPr) ?: return null
        val embedId = node.first(P, "blipFill")?.first(A, "blip")?.getAttributeNS(R, "embed")
            ?: return null
        val target = rels[embedId] ?: return null
        return Element.Picture(box = box.translate(offset), imageEntry = resolve(dir, target), rotation = rotationOf(spPr))
    }

    private fun parseGraphicFrame(node: DomElement, offset: Box, theme: Theme): Element.Table? {
        val xfrm = node.first(P, "xfrm") ?: return null
        val off = xfrm.first(A, "off") ?: return null
        val ext = xfrm.first(A, "ext") ?: return null
        val box = Box(off.long("x"), off.long("y"), ext.long("cx"), ext.long("cy")).translate(offset)

        val tbl = node.first(A, "graphic")?.first(A, "graphicData")?.first(A, "tbl") ?: return null
        val grid = tbl.first(A, "tblGrid")?.all(A, "gridCol")?.map { it.long("w") } ?: return null

        val rowHeights = mutableListOf<Long>()
        val rows = mutableListOf<List<TableCell?>>()
        for (tr in tbl.all(A, "tr")) {
            rowHeights += tr.long("h")
            rows += tr.children().filter { it.localName == "tc" }.map { tc ->
                // hMerge/vMerge mark the continuation cells of a merge; the spanning
                // cell keeps its content and these are holes to skip when drawing.
                if (tc.getAttribute("hMerge") == "1" || tc.getAttribute("vMerge") == "1") null
                else TableCell(
                    text = tc.first(A, "txBody")?.let { textBody(it, theme, emptyMap()) },
                    fillArgb = tc.first(A, "tcPr")?.let { solidFill(it, theme) },
                    colSpan = tc.getAttribute("gridSpan").toIntOrNull() ?: 1,
                    rowSpan = tc.getAttribute("rowSpan").toIntOrNull() ?: 1
                )
            }
        }
        if (rows.isEmpty()) return null
        return Element.Table(box, grid, rowHeights, rows)
    }

    private fun textBody(node: DomElement, theme: Theme, levelStyles: Map<Int, LevelStyle>): TextBody? {
        val paragraphs = node.all(A, "p").map { paragraph(it, theme, levelStyles) }
        if (paragraphs.isEmpty()) return null
        val bodyPr = node.first(A, "bodyPr")
        return TextBody(
            paragraphs = paragraphs,
            anchor = when (bodyPr?.getAttribute("anchor")) {
                "ctr" -> VAnchor.MIDDLE
                "b" -> VAnchor.BOTTOM
                else -> VAnchor.TOP
            },
            insetLeftEmu = bodyPr?.longOrNull("lIns") ?: 91440,
            insetTopEmu = bodyPr?.longOrNull("tIns") ?: 45720,
            insetRightEmu = bodyPr?.longOrNull("rIns") ?: 91440,
            insetBottomEmu = bodyPr?.longOrNull("bIns") ?: 45720,
            autoFit = bodyPr?.first(A, "normAutofit") != null
        )
    }

    private fun paragraph(node: DomElement, theme: Theme, levelStyles: Map<Int, LevelStyle>): Paragraph {
        val pPr = node.first(A, "pPr")
        val level = pPr?.getAttribute("lvl")?.toIntOrNull() ?: 0
        val fallback = levelStyles[level] ?: LevelStyle()

        val runs = node.children()
            .filter { it.localName == "r" || it.localName == "br" }
            .map { child ->
                // A <a:br> is a hard line break inside the paragraph; carrying it as a
                // run containing "\n" keeps the renderer from needing a second concept.
                if (child.localName == "br") Run("\n") else run(child, theme, fallback)
            }
        return Paragraph(
            runs = runs,
            align = when (pPr?.getAttribute("algn")) {
                "ctr" -> HAlign.CENTER
                "r" -> HAlign.RIGHT
                "just" -> HAlign.JUSTIFY
                "l" -> HAlign.LEFT
                else -> fallback.align ?: HAlign.LEFT
            },
            level = level,
            bullet = bulletOf(pPr, fallback),
            spaceBeforePt = spacingPt(pPr?.first(A, "spcBef")),
            spaceAfterPt = spacingPt(pPr?.first(A, "spcAft")),
            lineSpacingPercent = pPr?.first(A, "lnSpc")?.first(A, "spcPct")
                ?.long("val")?.let { it / 1000f } ?: 100f
        )
    }

    private fun run(node: DomElement, theme: Theme, fallback: LevelStyle): Run {
        val rPr = node.first(A, "rPr")
        return Run(
            text = node.first(A, "t")?.textContent ?: "",
            // Size is in hundredths of a point.
            sizePt = rPr?.getAttribute("sz")?.toLongOrNull()?.let { it / 100f } ?: fallback.sizePt ?: 18f,
            bold = rPr?.getAttribute("b")?.ifBlank { null }?.let { it == "1" } ?: fallback.bold ?: false,
            italic = rPr?.getAttribute("i")?.ifBlank { null }?.let { it == "1" } ?: fallback.italic ?: false,
            underline = rPr?.getAttribute("u")?.ifBlank { null }?.let { it != "none" } ?: fallback.underline ?: false,
            colorArgb = rPr?.let { solidFill(it, theme) } ?: fallback.colorArgb ?: 0xFF000000.toInt(),
            fontFamily = rPr?.first(A, "latin")?.getAttribute("typeface")?.ifBlank { null }
        )
    }

    private fun bulletOf(pPr: DomElement?, fallback: LevelStyle): String? = when {
        pPr?.first(A, "buNone") != null -> null
        pPr?.first(A, "buChar") != null -> pPr.first(A, "buChar")?.getAttribute("char")
        pPr?.first(A, "buAutoNum") != null -> "•"
        // Nothing said either way at this level: fall through to what the layout or
        // master declared for this indent level.
        else -> if (fallback.noBullet) null else fallback.bulletChar
    }

    private fun spacingPt(node: DomElement?): Float =
        node?.first(A, "spcPts")?.long("val")?.let { it / 100f } ?: 0f

    /**
     * Colour resolution: literal sRGB is read directly; a theme reference
     * (<a:schemeClr val="tx1"/>, very common for anything using a default shape
     * style — outlines especially) resolves through [theme]. What is still not
     * handled is a <p:clrMapOvr> on the slide itself overriding the standard
     * bg/tx -> lt/dk mapping, which is rare enough that [Theme.resolve]'s fixed
     * mapping is a reasonable trade rather than plumbing it through everywhere.
     */
    private fun solidFill(parent: DomElement, theme: Theme): Int? {
        // Direct children only. A shape with no fill of its own but an outline that has
        // one would otherwise inherit its own border colour as its fill.
        val fill = parent.child(A, "solidFill") ?: return null
        return colorIn(fill, theme)
    }

    /** The color element (srgbClr or schemeClr, with an optional alpha) inside [holder]. */
    private fun colorIn(holder: DomElement, theme: Theme): Int? {
        val srgb = holder.child(A, "srgbClr")
        val scheme = holder.child(A, "schemeClr")
        val colorNode = srgb ?: scheme ?: return null

        val rgb = if (srgb != null) {
            srgb.getAttribute("val").takeIf { it.length == 6 }?.toIntOrNull(16) ?: return null
        } else {
            (theme.resolve(scheme!!.getAttribute("val")) ?: return null) and 0xFFFFFF
        }
        val alphaPercent = colorNode.child(A, "alpha")?.long("val")
        val alpha = if (alphaPercent != null) (alphaPercent * 255 / 100000).toInt() else 255
        return (alpha shl 24) or rgb
    }

    /**
     * <a:gradFill>: stops in <a:gsLst> (pos in 1/1000 %), then either <a:lin ang>
     * (60000ths of a degree, clockwise from left-to-right — the same convention as
     * [Gradient.angleDeg]) or <a:path> (circle = radial, rect/shape = concentric
     * rectangles) whose <a:fillToRect> insets put the center.
     */
    private fun gradientFill(parent: DomElement, theme: Theme): Gradient? {
        val grad = parent.child(A, "gradFill") ?: return null
        val stops = grad.child(A, "gsLst")?.all(A, "gs").orEmpty().mapNotNull { gs ->
            colorIn(gs, theme)?.let { (gs.longOrNull("pos") ?: 0L) / 100000f to it }
        }.sortedBy { it.first }
        if (stops.isEmpty()) return null
        if (stops.size == 1) return Gradient(listOf(stops[0].second, stops[0].second), listOf(0f, 1f), 0f)
        val colors = stops.map { it.second }
        val positions = stops.map { it.first.coerceIn(0f, 1f) }
        val path = grad.child(A, "path")
        if (path != null) {
            val to = path.child(A, "fillToRect")
            fun frac(attr: String) = (to?.longOrNull(attr) ?: 50000L) / 100000f
            return Gradient(
                colors, positions, 0f,
                radial = true,
                rectangular = path.getAttribute("path") != "circle",
                centerX = (frac("l") + 1f - frac("r")) / 2f,
                centerY = (frac("t") + 1f - frac("b")) / 2f
            )
        }
        val angle = (grad.child(A, "lin")?.longOrNull("ang") ?: 0L) / 60000f
        return Gradient(colors, positions, angle)
    }

    private fun boxOf(spPr: DomElement): Box? {
        val xfrm = spPr.first(A, "xfrm") ?: return null
        val off = xfrm.first(A, "off") ?: return null
        val ext = xfrm.first(A, "ext") ?: return null
        return Box(off.long("x"), off.long("y"), ext.long("cx"), ext.long("cy"))
    }

    /** Rotation is stored in 60000ths of a degree. */
    private fun rotationOf(spPr: DomElement): Float =
        spPr.first(A, "xfrm")?.longOrNull("rot")?.let { it / 60000f } ?: 0f

    // ---- zip / xml helpers -------------------------------------------------

    private fun ZipFile.xml(path: String): Document? {
        val entry = getEntry(path) ?: return null
        return getInputStream(entry).use { stream ->
            try {
                secureDocumentBuilder().parse(stream)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Relationship files map the r:id used inside a part to the file it points at.
     * Targets with a TargetMode of External are dropped: they address something outside
     * the package (an http:// image, say), and resolving one would turn opening a file
     * into fetching a URL on the document's behalf.
     */
    private fun ZipFile.rels(partPath: String): Map<String, String> {
        val dir = partPath.substringBeforeLast('/', "")
        val name = partPath.substringAfterLast('/')
        val relPath = if (dir.isEmpty()) "_rels/$name.rels" else "$dir/_rels/$name.rels"
        val doc = xml(relPath) ?: return emptyMap()
        return doc.documentElement.children()
            .filter { it.getAttribute("TargetMode") != "External" }
            .mapNotNull { rel ->
                val id = rel.getAttribute("Id").ifBlank { return@mapNotNull null }
                val target = rel.getAttribute("Target").ifBlank { return@mapNotNull null }
                id to target
            }.toMap()
    }

    /**
     * XML parsing with every external-resource door shut. A .pptx is frequently a file
     * someone else sent, and an entity reference is enough to turn parsing it into a
     * file read or an outbound request.
     */
    private fun secureDocumentBuilder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setXIncludeAware(false)
    }.newDocumentBuilder()

    /** Resolves a relationship target, which may be relative ("../media/x.png"). */
    private fun resolve(baseDir: String, target: String): String {
        if (target.startsWith("/")) return target.removePrefix("/")
        val parts = baseDir.split('/').toMutableList()
        for (segment in target.split('/')) {
            when (segment) {
                "." , "" -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += segment
            }
        }
        return parts.joinToString("/")
    }

    private fun Node.children(): List<DomElement> {
        val out = mutableListOf<DomElement>()
        val list = childNodes
        for (i in 0 until list.length) (list.item(i) as? DomElement)?.let { out += it }
        return out
    }

    /** First descendant with this namespace and name, searched depth-first. */
    private fun Node.first(ns: String, name: String): DomElement? {
        for (child in children()) {
            if (child.namespaceURI == ns && child.localName == name) return child
            child.first(ns, name)?.let { return it }
        }
        return null
    }

    /** Direct children with this namespace and name — not descendants, so that a
     *  paragraph inside a nested table does not get collected as the outer body's. */
    private fun Node.all(ns: String, name: String): List<DomElement> =
        children().filter { it.namespaceURI == ns && it.localName == name }

    private fun Node.child(ns: String, name: String): DomElement? =
        children().firstOrNull { it.namespaceURI == ns && it.localName == name }

    /** Every descendant with this namespace and name, in document order. Needed where
     *  the element sits inside a wrapper list (p:sldId lives under p:sldIdLst). */
    private fun Node.descendants(ns: String, name: String): List<DomElement> {
        val out = mutableListOf<DomElement>()
        for (child in children()) {
            if (child.namespaceURI == ns && child.localName == name) out += child
            out += child.descendants(ns, name)
        }
        return out
    }

    private fun DomElement.long(attr: String): Long = getAttribute(attr).toLongOrNull() ?: 0L
    private fun DomElement.longOrNull(attr: String): Long? = getAttribute(attr).toLongOrNull()

    private fun Box.translate(by: Box) = Box(x + by.x, y + by.y, w, h)

    private fun Element.translated(dx: Long, dy: Long): Element =
        withBox(Box(box.x + dx, box.y + dy, box.w, box.h))

    private fun Element.withBox(b: Box): Element = when (this) {
        is Element.Shape -> copy(box = b)
        is Element.Picture -> copy(box = b)
        is Element.Table -> copy(box = b)
    }
}
