package io.github.baeseungwon1010.officecanvas.pptx

import io.github.baeseungwon1010.officecanvas.ppt.AutoNumber
import io.github.baeseungwon1010.officecanvas.ppt.NumberCounter
import io.github.baeseungwon1010.officecanvas.slide.HAlign
import io.github.baeseungwon1010.officecanvas.slide.Paragraph
import io.github.baeseungwon1010.officecanvas.slide.Run
import io.github.baeseungwon1010.officecanvas.slide.TextBody
import io.github.baeseungwon1010.officecanvas.slide.VAnchor
import org.w3c.dom.Element

/** Space before/after or line spacing: either points or a percent of a line. */
internal sealed class Spacing {
    data class Points(val pt: Float) : Spacing()
    data class Percent(val pct: Float) : Spacing()
}

internal sealed class BulletKind {
    object None : BulletKind()
    data class Char(val char: String) : BulletKind()
    data class Number(val scheme: Int, val start: Int) : BulletKind()
}

/**
 * One indent level's paragraph and run defaults. Every field is nullable because
 * "not specified here" and "specified as off" differ: the former keeps looking
 * further up the chain (shape lstStyle -> layout placeholder -> master placeholder
 * -> master text styles).
 */
internal data class LevelStyle(
    val sizePt: Float? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val colorArgb: Int? = null,
    val latin: String? = null,
    val ea: String? = null,
    val align: HAlign? = null,
    val marginLeftEmu: Long? = null,
    val indentEmu: Long? = null,
    val bullet: BulletKind? = null,
    val bulletColorArgb: Int? = null,
    val bulletSizePercent: Float? = null,
    val spaceBefore: Spacing? = null,
    val spaceAfter: Spacing? = null,
    val lineSpacing: Spacing? = null,
    val defaultTabEmu: Long? = null,
    val tabStopsEmu: List<Long>? = null,
    val caps: Boolean? = null
) {
    fun overlaidOn(base: LevelStyle): LevelStyle = LevelStyle(
        sizePt ?: base.sizePt, bold ?: base.bold, italic ?: base.italic, underline ?: base.underline,
        colorArgb ?: base.colorArgb, latin ?: base.latin, ea ?: base.ea, align ?: base.align,
        marginLeftEmu ?: base.marginLeftEmu, indentEmu ?: base.indentEmu, bullet ?: base.bullet,
        bulletColorArgb ?: base.bulletColorArgb, bulletSizePercent ?: base.bulletSizePercent,
        spaceBefore ?: base.spaceBefore, spaceAfter ?: base.spaceAfter, lineSpacing ?: base.lineSpacing,
        defaultTabEmu ?: base.defaultTabEmu, tabStopsEmu ?: base.tabStopsEmu, caps ?: base.caps
    )
}

/** Level styles from most specific to least; [at] merges them for one level. */
internal class StyleChain(private val layers: List<Map<Int, LevelStyle>>) {
    fun at(level: Int): LevelStyle =
        layers.fold(LevelStyle()) { acc, layer -> acc.overlaidOn(layer[level] ?: LevelStyle()) }

    operator fun plus(more: StyleChain) = StyleChain(layers + more.layers)

    companion object {
        val EMPTY = StyleChain(emptyList())
        fun of(vararg layers: Map<Int, LevelStyle>?) = StyleChain(layers.filterNotNull())
    }
}

/** Reads <a:lvl1pPr>..<a:lvl9pPr> (indent levels 0..8) from a <p:titleStyle>,
 *  <p:bodyStyle>, <p:otherStyle>, <p:defaultTextStyle> or an <a:lstStyle>. */
internal fun Theme.levelStyles(container: Element?): Map<Int, LevelStyle> {
    container ?: return emptyMap()
    val out = mutableMapOf<Int, LevelStyle>()
    container.child(NS_A, "defPPr")?.let { out[-1] = paragraphStyle(it) }
    for (level in 1..9) {
        val node = container.child(NS_A, "lvl${level}pPr") ?: continue
        out[level - 1] = paragraphStyle(node)
    }
    // defPPr applies to every level that doesn't say otherwise.
    val def = out.remove(-1) ?: return out
    return (0..8).associateWith { (out[it] ?: LevelStyle()).overlaidOn(def) }
}

/** A <a:pPr> or <a:lvlNpPr>: paragraph properties plus its <a:defRPr>. */
internal fun Theme.paragraphStyle(node: Element): LevelStyle {
    val run = runStyle(node.child(NS_A, "defRPr"))
    val bullet = when {
        node.child(NS_A, "buNone") != null -> BulletKind.None
        node.child(NS_A, "buAutoNum") != null -> node.child(NS_A, "buAutoNum")!!.let {
            BulletKind.Number(AUTONUM_SCHEMES.indexOf(it.getAttribute("type")).let { i -> if (i < 0) 3 else i },
                it.getAttribute("startAt").toIntOrNull() ?: 1)
        }
        node.child(NS_A, "buChar") != null -> BulletKind.Char(
            bulletGlyph(node.child(NS_A, "buChar")!!.getAttribute("char"), node.child(NS_A, "buFont")?.attr("typeface"))
        )
        node.child(NS_A, "buBlip") != null -> BulletKind.Char("•")
        else -> null
    }
    return run.copy(
        align = when (node.getAttribute("algn")) {
            "ctr" -> HAlign.CENTER; "r" -> HAlign.RIGHT; "just", "dist" -> HAlign.JUSTIFY; "l" -> HAlign.LEFT
            else -> null
        },
        marginLeftEmu = node.longOrNull("marL"),
        indentEmu = node.longOrNull("indent"),
        bullet = bullet,
        bulletColorArgb = node.child(NS_A, "buClr")?.let { colorIn(it) },
        bulletSizePercent = node.child(NS_A, "buSzPct")?.longOrNull("val")?.let { it / 1000f },
        spaceBefore = spacing(node.child(NS_A, "spcBef")),
        spaceAfter = spacing(node.child(NS_A, "spcAft")),
        lineSpacing = spacing(node.child(NS_A, "lnSpc")),
        defaultTabEmu = node.longOrNull("defTabSz"),
        tabStopsEmu = node.child(NS_A, "tabLst")?.all(NS_A, "tab")?.mapNotNull { it.longOrNull("pos") }
    )
}

/** A <a:rPr>, <a:defRPr> or <a:endParaRPr>. */
internal fun Theme.runStyle(rPr: Element?): LevelStyle {
    rPr ?: return LevelStyle()
    val fill = fillIn(rPr)
    return LevelStyle(
        // Size is in hundredths of a point.
        sizePt = rPr.longOrNull("sz")?.let { it / 100f },
        bold = rPr.attr("b")?.let { it == "1" || it == "true" },
        italic = rPr.attr("i")?.let { it == "1" || it == "true" },
        underline = rPr.attr("u")?.let { it != "none" },
        colorArgb = when (fill) {
            is Fill.Solid -> fill.argb
            is Fill.Shaded -> fill.gradient.colors.first()
            else -> null
        },
        latin = rPr.child(NS_A, "latin")?.attr("typeface"),
        ea = rPr.child(NS_A, "ea")?.attr("typeface"),
        caps = rPr.attr("cap")?.let { it == "all" }
    )
}

private fun spacing(node: Element?): Spacing? {
    node ?: return null
    node.child(NS_A, "spcPts")?.longOrNull("val")?.let { return Spacing.Points(it / 100f) }
    node.child(NS_A, "spcPct")?.longOrNull("val")?.let { return Spacing.Percent(it / 1000f) }
    return null
}

/** <a:bodyPr> attributes, merged from the shape and its placeholder chain. */
internal data class BodyProps(
    val anchor: VAnchor? = null,
    val insets: List<Long?> = listOf(null, null, null, null),
    /** normAutofit: the scale PowerPoint saved (1 when the text already fit). */
    val fontScale: Float? = null,
    val lineReductionPct: Float? = null,
    val shrink: Boolean? = null,
    val wrap: Boolean? = null
) {
    fun overlaidOn(base: BodyProps) = BodyProps(
        anchor ?: base.anchor,
        insets.zip(base.insets) { a, b -> a ?: b },
        if (shrink != null) fontScale else base.fontScale,
        if (shrink != null) lineReductionPct else base.lineReductionPct,
        shrink ?: base.shrink,
        wrap ?: base.wrap
    )

    companion object {
        fun of(bodyPr: Element?): BodyProps {
            bodyPr ?: return BodyProps()
            val norm = bodyPr.child(NS_A, "normAutofit")
            return BodyProps(
                anchor = when (bodyPr.getAttribute("anchor")) {
                    "ctr" -> VAnchor.MIDDLE; "b" -> VAnchor.BOTTOM; "t" -> VAnchor.TOP
                    else -> null
                },
                insets = listOf("lIns", "tIns", "rIns", "bIns").map { bodyPr.longOrNull(it) },
                fontScale = norm?.longOrNull("fontScale")?.let { it / 100000f },
                lineReductionPct = norm?.longOrNull("lnSpcReduction")?.let { it / 1000f },
                shrink = when {
                    norm != null -> true
                    bodyPr.child(NS_A, "noAutofit") != null || bodyPr.child(NS_A, "spAutoFit") != null -> false
                    else -> null
                },
                wrap = bodyPr.attr("wrap")?.let { it != "none" }
            )
        }
    }
}

/**
 * Builds a [TextBody] from a <p:txBody> (or <a:txBody>, <dsp:txBody>), resolving
 * each paragraph against [chain] and the body properties against [body].
 * [fallbackColor] is the shape style's font color (<a:fontRef>), which sits below
 * anything the text itself or its list styles say.
 */
internal fun Theme.textBody(node: Element, chain: StyleChain, body: BodyProps, fallbackColor: Int? = null): TextBody? {
    val own = StyleChain.of(levelStyles(node.child(NS_A, "lstStyle")))
    val styles = own + chain
    val props = BodyProps.of(node.child(NS_A, "bodyPr")).overlaidOn(body)
    val scale = props.fontScale ?: 1f
    val reduction = props.lineReductionPct ?: 0f
    val counter = NumberCounter()
    val paragraphs = node.all(NS_A, "p").map { paragraph(it, styles, scale, reduction, fallbackColor, counter) }
    if (paragraphs.isEmpty()) return null
    val insets = props.insets
    return TextBody(
        paragraphs = paragraphs,
        anchor = props.anchor ?: VAnchor.TOP,
        insetLeftEmu = insets[0] ?: 91440,
        insetTopEmu = insets[1] ?: 45720,
        insetRightEmu = insets[2] ?: 91440,
        insetBottomEmu = insets[3] ?: 45720,
        // PowerPoint saved the scale that makes the text fit (applied above); the
        // renderer may still shrink a little where substitute fonts run longer.
        autoFit = props.shrink == true,
        autoFitFloor = 0.8f,
        wrap = props.wrap ?: true
    )
}

private fun Theme.paragraph(
    node: Element, chain: StyleChain, fontScale: Float, lineReduction: Float, fallbackColor: Int?, counter: NumberCounter
): Paragraph {
    val pPr = node.child(NS_A, "pPr")
    val level = pPr?.getAttribute("lvl")?.toIntOrNull()?.coerceIn(0, 8) ?: 0
    val base = chain.at(level).let { if (fallbackColor != null && it.colorArgb == null) it.copy(colorArgb = fallbackColor) else it }
    val style = (pPr?.let { paragraphStyle(it) } ?: LevelStyle()).overlaidOn(base)

    val runs = mutableListOf<Run>()
    for (child in node.children()) {
        when (child.localName) {
            // A <a:br> is a hard line break inside the paragraph; carrying it as a
            // run containing "\n" keeps the renderer from needing a second concept.
            "br" -> runs += run(child, "\n", style, fontScale)
            // Fields (slide number, date) carry their last computed text.
            "r", "fld" -> runs += run(child, child.child(NS_A, "t")?.textContent ?: "", style, fontScale)
        }
    }
    val endSize = runStyle(node.child(NS_A, "endParaRPr")).sizePt
    if (runs.isEmpty() && endSize != null) runs += Run("", sizePt = endSize * fontScale)

    val hasText = runs.any { it.text.isNotBlank() }
    val firstSize = runs.firstOrNull { it.text.isNotBlank() }?.sizePt ?: runs.firstOrNull()?.sizePt ?: (style.sizePt ?: 18f) * fontScale
    // Bullets and numbers are not shown on empty paragraphs.
    val bullet = when (val b = style.bullet) {
        is BulletKind.Char -> if (hasText) b.char else null
        is BulletKind.Number -> counter.next(level, AutoNumber(b.scheme, b.start), hasText)
        else -> { counter.next(level, null, hasText); null }
    }
    val margin = style.marginLeftEmu
    val indent = style.indentEmu
    return Paragraph(
        runs = runs,
        align = style.align ?: HAlign.LEFT,
        level = level,
        bullet = bullet,
        spaceBeforePt = spacingPt(style.spaceBefore, firstSize, fontScale),
        spaceAfterPt = spacingPt(style.spaceAfter, firstSize, fontScale),
        lineSpacingPercent = when (val ls = style.lineSpacing) {
            is Spacing.Percent -> ls.pct
            is Spacing.Points -> ls.pt * fontScale / (firstSize * 1.2f) * 100f
            null -> 100f
        }.let { (it - lineReduction).coerceAtLeast(10f) },
        marginLeftEmu = if (margin != null || indent != null) margin ?: 0L else null,
        bulletIndentEmu = if (margin != null || indent != null) ((margin ?: 0L) + (indent ?: 0L)).coerceAtLeast(0L) else null,
        bulletColorArgb = style.bulletColorArgb,
        bulletSizePercent = style.bulletSizePercent ?: 100f,
        tabStopsEmu = style.tabStopsEmu.orEmpty(),
        defaultTabEmu = style.defaultTabEmu ?: 914400
    )
}

private fun Theme.run(node: Element, text: String, paragraph: LevelStyle, fontScale: Float): Run {
    val rPr = node.child(NS_A, "rPr")
    val own = runStyle(rPr)
    val style = own.overlaidOn(paragraph)
    // Hyperlinks take the theme's hyperlink color and an underline unless the run says otherwise.
    val link = rPr?.child(NS_A, "hlinkClick") != null
    val color = own.colorArgb ?: (if (link) scheme("hlink") else null) ?: style.colorArgb ?: scheme("tx1") ?: 0xFF000000.toInt()
    val latin = font(style.latin)
    val ea = font(style.ea)
    // A run of Hangul/CJK text is set in the east-Asian face; the family is only used
    // to pick a metric stand-in, so a Latin face is preferred when the text has Latin.
    val family = if (latin != null && text.any { it.code < 0x2E80 && it.isLetterOrDigit() }) latin else ea ?: latin
    return Run(
        text = if (style.caps == true) text.uppercase() else text,
        sizePt = (style.sizePt ?: 18f) * fontScale,
        bold = style.bold ?: false,
        italic = style.italic ?: false,
        underline = style.underline ?: link,
        colorArgb = color,
        fontFamily = family,
        // Superscript/subscript offset, in 1/1000 of a percent.
        baselinePercent = rPr?.longOrNull("baseline")?.let { it / 1000f } ?: 0f
    )
}

/** Percent spacing is a fraction of the line (font size x 1.2, rounded the way
 *  LibreOffice measures it: size x 1.25). */
private fun spacingPt(s: Spacing?, sizePt: Float, fontScale: Float): Float = when (s) {
    is Spacing.Points -> s.pt * fontScale
    is Spacing.Percent -> s.pct / 100f * sizePt * 1.25f
    null -> 0f
}

/** Bullet characters in symbol fonts, mapped to what they draw. */
private fun bulletGlyph(char: String, font: String?): String {
    val c = char.firstOrNull() ?: return "•"
    val f = font?.lowercase().orEmpty()
    val code = c.code and 0xFF
    return when {
        "wingdings" in f -> when (code) {
            0xA7 -> "▪"; 0x6C -> "●"; 0x6E -> "■"; 0x71 -> "❑"; 0x75 -> "◆"; 0x76 -> "❖"; 0xD8 -> "➢"
            0xFC -> "✓"; 0xE8 -> "➔"; 0xA8 -> "◻"; 0x9F -> "•"; 0x77 -> "⬥"; 0xF0 -> "⇨"
            else -> "•"
        }
        "symbol" in f -> when (code) { 0xB7 -> "•"; 0xAE -> "→"; 0xD8 -> "¬"; else -> if (c.code < 0x80) c.toString() else "•" }
        "webdings" in f -> "▸"
        c.code in 0xF000..0xF0FF -> "•"
        else -> char
    }
}

/** ST_TextAutonumberScheme in schema order, which is also the MS-PPT enum order
 *  formatNumber understands. */
private val AUTONUM_SCHEMES = listOf(
    "alphaLcPeriod", "alphaUcPeriod", "arabicParenR", "arabicPeriod", "romanLcParenBoth", "romanLcParenR",
    "romanLcPeriod", "romanUcPeriod", "alphaLcParenBoth", "alphaLcParenR", "alphaUcParenBoth", "alphaUcParenR",
    "arabicParenBoth", "arabicPlain", "romanUcParenBoth", "romanUcParenR", "ea1ChsPlain", "ea1ChsPeriod",
    "circleNumDbPlain", "circleNumWdWhitePlain", "circleNumWdBlackPlain", "ea1ChtPlain", "ea1ChtPeriod",
    "arabic1Minus", "arabic2Minus", "hebrew2Minus", "arabicDbPlain", "arabicDbPeriod", "ea1JpnChsDbPeriod"
)
