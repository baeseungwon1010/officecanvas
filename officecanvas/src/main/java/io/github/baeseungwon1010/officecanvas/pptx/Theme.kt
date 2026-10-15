package io.github.baeseungwon1010.officecanvas.pptx

import io.github.baeseungwon1010.officecanvas.slide.Gradient
import io.github.baeseungwon1010.officecanvas.slide.Shadow
import org.w3c.dom.Element
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.pow

/**
 * A theme's color scheme, fonts and format scheme, plus the color map in effect
 * for one slide.
 *
 * Shapes rarely name a literal color. They name a scheme slot (`tx1`, `accent2`),
 * often with modifiers (`lumMod 75000` for a darker shade), and shapes inserted with
 * PowerPoint's default style name no fill at all: `<p:style><a:fillRef idx="1">`
 * points into the theme's format scheme instead.
 */
internal class Theme(
    private val colors: Map<String, Int>,
    /** Logical slot (bg1, tx1, bg2, tx2, accent1, ...) to scheme slot (lt1, dk1, ...),
     *  from the master's <p:clrMap>, possibly overridden by a layout or slide. */
    val colorMap: Map<String, String>,
    val majorLatin: String?,
    val minorLatin: String?,
    val majorEa: String?,
    val minorEa: String?,
    private val fillStyles: List<Element>,
    private val lineStyles: List<Element>,
    private val bgFillStyles: List<Element>,
    private val effectStyles: List<Element> = emptyList()
) {
    fun scheme(name: String): Int? = colors[colorMap[name] ?: DEFAULT_MAP[name] ?: name]

    fun withColorMap(map: Map<String, String>) =
        Theme(colors, map, majorLatin, minorLatin, majorEa, minorEa, fillStyles, lineStyles, bgFillStyles, effectStyles)

    /** "+mj-lt" and friends name the theme's fonts rather than a font. */
    fun font(typeface: String?): String? = when (typeface) {
        null, "" -> null
        "+mj-lt" -> majorLatin
        "+mn-lt" -> minorLatin
        "+mj-ea" -> majorEa ?: majorLatin
        "+mn-ea" -> minorEa ?: minorLatin
        else -> if (typeface.startsWith("+")) minorLatin else typeface
    }

    /** idx 1..3 picks a fill style, 1001..1003 a background fill style; 0 is none. */
    fun fillStyle(idx: Int): Element? = when {
        idx in 1..999 -> fillStyles.getOrNull(idx - 1)
        idx >= 1001 -> bgFillStyles.getOrNull(idx - 1001)
        else -> null
    }

    fun lineStyle(idx: Int): Element? = lineStyles.getOrNull(idx - 1)

    /** An <a:effectStyle>'s effect list; idx 0 is none. */
    fun effectStyle(idx: Int): Element? = effectStyles.getOrNull(idx - 1)?.child(NS_A, "effectLst")

    companion object {
        val DEFAULT_MAP = mapOf("bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2")
        val EMPTY = Theme(emptyMap(), DEFAULT_MAP, null, null, null, null, emptyList(), emptyList(), emptyList())

        fun load(zip: ZipFile, path: String): Theme {
            val elements = zip.xml(path)?.first(NS_A, "themeElements") ?: return EMPTY
            val colors = mutableMapOf<String, Int>()
            elements.child(NS_A, "clrScheme")?.children()?.forEach { slot ->
                val name = slot.localName ?: return@forEach
                val hex = slot.child(NS_A, "srgbClr")?.attr("val") ?: slot.child(NS_A, "sysClr")?.attr("lastClr")
                hex?.toIntOrNull(16)?.let { colors[name] = (0xFF shl 24) or it }
            }
            val fonts = elements.child(NS_A, "fontScheme")
            fun face(kind: String, script: String) = fonts?.child(NS_A, kind)?.child(NS_A, script)?.attr("typeface")
            val fmt = elements.child(NS_A, "fmtScheme")
            fun list(name: String) = fmt?.child(NS_A, name)?.children().orEmpty()
            return Theme(
                colors, DEFAULT_MAP,
                face("majorFont", "latin"), face("minorFont", "latin"),
                face("majorFont", "ea"), face("minorFont", "ea"),
                list("fillStyleLst"), list("lnStyleLst"), list("bgFillStyleLst"), list("effectStyleLst")
            )
        }

        /** Reads a <p:clrMap> or <a:overrideClrMapping>'s attributes. */
        fun colorMapOf(node: Element?): Map<String, String>? {
            node ?: return null
            val map = mutableMapOf<String, String>()
            val attrs = node.attributes
            for (i in 0 until attrs.length) {
                val a = attrs.item(i)
                if (a.namespaceURI == null) map[a.nodeName] = a.nodeValue
            }
            return map.takeIf { it.isNotEmpty() }
        }
    }
}

/** The color element inside [holder] (srgbClr, schemeClr, sysClr, prstClr,
 *  scrgbClr or hslClr) with its modifiers applied. [placeholder] stands in for
 *  `phClr`, the color a style reference passes down. */
internal fun Theme.colorIn(holder: Element?, placeholder: Int? = null): Int? {
    holder ?: return null
    for (node in holder.children()) {
        val base: Int = when (node.localName) {
            "srgbClr" -> node.attr("val")?.takeIf { it.length == 6 }?.toIntOrNull(16)?.let { it or (0xFF shl 24) }
            "schemeClr" -> node.attr("val")?.let { if (it == "phClr") placeholder else scheme(it) }
            "sysClr" -> (node.attr("lastClr") ?: SYSTEM_COLORS[node.attr("val")])?.toIntOrNull(16)?.let { it or (0xFF shl 24) }
            "prstClr" -> PRESET_COLORS[node.attr("val")]?.let { it or (0xFF shl 24) }
            "scrgbClr" -> {
                fun c(a: String) = (linearToSrgb(node.long(a) / 100000.0) * 255).toInt().coerceIn(0, 255)
                (0xFF shl 24) or (c("r") shl 16) or (c("g") shl 8) or c("b")
            }
            "hslClr" -> hslToRgb(node.long("hue") / 60000.0 / 360.0, node.long("sat") / 100000.0, node.long("lum") / 100000.0)
            else -> null
        } ?: continue
        return applyModifiers(base, node)
    }
    return null
}

/** The outer shadow in an <a:effectLst>: blur radius, distance and direction (in
 *  1/60000 degree, clockwise from the right) and its color. [placeholder] stands
 *  in for `phClr` (the effect reference's color). */
internal fun Theme.shadowOf(effects: Element?, placeholder: Int? = null): Shadow? {
    val s = effects?.child(NS_A, "outerShdw") ?: return null
    val color = colorIn(s, placeholder) ?: return null
    if (color ushr 24 == 0) return null
    val dist = s.long("dist").coerceIn(0, 2_000_000)
    val dir = Math.toRadians(s.long("dir") / 60000.0)
    return Shadow(color, (dist * kotlin.math.cos(dir)).toLong(), (dist * kotlin.math.sin(dir)).toLong(), s.long("blurRad").coerceIn(0, 2_000_000))
}

/** A shape's or background's fill: a solid color, a gradient, or nothing. */
internal sealed class Fill {
    data class Solid(val argb: Int) : Fill()
    data class Shaded(val gradient: Gradient) : Fill()
    /** A picture (r:embed id, resolved against the declaring part's relationships). */
    data class Picture(val embedId: String, val part: String) : Fill()
    object None : Fill()
}

/** The fill element directly under [parent], or null when it names none at all
 *  (which means "inherit", unlike an explicit <a:noFill>). */
internal fun Theme.fillIn(parent: Element?, placeholder: Int? = null, part: String = ""): Fill? {
    parent ?: return null
    for (node in parent.children()) {
        when (node.localName) {
            "noFill" -> return Fill.None
            "solidFill" -> return colorIn(node, placeholder)?.let { Fill.Solid(it) } ?: Fill.None
            "gradFill" -> return gradientOf(node, placeholder)?.let { Fill.Shaded(it) } ?: Fill.None
            "blipFill" -> {
                val id = node.child(NS_A, "blip")?.getAttributeNS(NS_R, "embed")?.ifBlank { null }
                return if (id != null) Fill.Picture(id, part) else placeholder?.let { Fill.Solid(it) } ?: Fill.None
            }
            // Patterns are drawn as their foreground color, the way they read from a distance.
            "pattFill" -> return colorIn(node.child(NS_A, "fgClr"), placeholder)?.let { Fill.Solid(it) } ?: Fill.None
            "grpFill" -> return null
        }
    }
    return null
}

/** The fill a style reference (<a:fillRef idx="n"><color/></a:fillRef>) stands for. */
internal fun Theme.fillFromRef(ref: Element?, part: String = ""): Fill? {
    ref ?: return null
    val idx = ref.getAttribute("idx").toIntOrNull() ?: return null
    if (idx == 0) return Fill.None
    val color = colorIn(ref)
    val style = fillStyle(idx) ?: return color?.let { Fill.Solid(it) }
    return when (style.localName) {
        "noFill" -> Fill.None
        "solidFill" -> (colorIn(style, color) ?: color)?.let { Fill.Solid(it) }
        "gradFill" -> gradientOf(style, color)?.let { Fill.Shaded(it) } ?: color?.let { Fill.Solid(it) }
        "blipFill" -> style.child(NS_A, "blip")?.getAttributeNS(NS_R, "embed")?.ifBlank { null }
            ?.let { Fill.Picture(it, part) } ?: color?.let { Fill.Solid(it) }
        else -> color?.let { Fill.Solid(it) }
    }
}

/**
 * <a:gradFill>: stops in <a:gsLst> (pos in 1/1000 %), then either <a:lin ang>
 * (60000ths of a degree, clockwise from left-to-right — the same convention as
 * [Gradient.angleDeg]) or <a:path> (circle = radial, rect/shape = concentric
 * rectangles) whose <a:fillToRect> insets put the center.
 */
internal fun Theme.gradientOf(grad: Element, placeholder: Int? = null): Gradient? {
    val stops = grad.child(NS_A, "gsLst")?.all(NS_A, "gs").orEmpty().mapNotNull { gs ->
        colorIn(gs, placeholder)?.let { (gs.longOrNull("pos") ?: 0L) / 100000f to it }
    }.sortedBy { it.first }
    if (stops.isEmpty()) return null
    if (stops.size == 1) return Gradient(listOf(stops[0].second, stops[0].second), listOf(0f, 1f), 0f)
    val colors = stops.map { it.second }
    val positions = stops.map { it.first.coerceIn(0f, 1f) }
    val path = grad.child(NS_A, "path")
    if (path != null) {
        val to = path.child(NS_A, "fillToRect")
        fun frac(attr: String) = (to?.longOrNull(attr) ?: 50000L) / 100000f
        return Gradient(
            colors, positions, 0f,
            radial = true,
            rectangular = path.getAttribute("path") != "circle",
            centerX = (frac("l") + 1f - frac("r")) / 2f,
            centerY = (frac("t") + 1f - frac("b")) / 2f
        )
    }
    val angle = (grad.child(NS_A, "lin")?.longOrNull("ang") ?: 0L) / 60000f
    return Gradient(colors, positions, angle)
}

/**
 * DrawingML color modifiers, in document order. lumMod/lumOff/satMod work in HSL
 * (the way PowerPoint's "darker 25%" / "lighter 40%" theme variants are written);
 * tint and shade mix toward white and black in linear light.
 */
private fun applyModifiers(argb: Int, node: Element): Int {
    var a = (argb ushr 24) / 255.0
    var r = ((argb shr 16) and 0xFF) / 255.0
    var g = ((argb shr 8) and 0xFF) / 255.0
    var b = (argb and 0xFF) / 255.0
    for (m in node.children()) {
        val v = m.long("val") / 100000.0
        when (m.localName) {
            "alpha" -> a = v
            "alphaMod" -> a *= v
            "alphaOff" -> a += v
            "lumMod", "lumOff", "satMod", "satOff", "hueOff", "hueMod", "lum", "sat", "hue" -> {
                val hsl = rgbToHsl(r, g, b)
                when (m.localName) {
                    "lumMod" -> hsl[2] *= v
                    "lumOff" -> hsl[2] += v
                    "lum" -> hsl[2] = v
                    "satMod" -> hsl[1] *= v
                    "satOff" -> hsl[1] += v
                    "sat" -> hsl[1] = v
                    "hueOff" -> hsl[0] += m.long("val") / 60000.0 / 360.0
                    "hueMod" -> hsl[0] *= v
                    "hue" -> hsl[0] = m.long("val") / 60000.0 / 360.0
                }
                val rgb = hslToRgb(((hsl[0] % 1.0) + 1.0) % 1.0, hsl[1].coerceIn(0.0, 1.0), hsl[2].coerceIn(0.0, 1.0))
                r = ((rgb shr 16) and 0xFF) / 255.0; g = ((rgb shr 8) and 0xFF) / 255.0; b = (rgb and 0xFF) / 255.0
            }
            "shade" -> {
                r = linearToSrgb(srgbToLinear(r) * v); g = linearToSrgb(srgbToLinear(g) * v); b = linearToSrgb(srgbToLinear(b) * v)
            }
            "tint" -> {
                fun t(c: Double) = linearToSrgb(srgbToLinear(c) * v + (1 - v))
                r = t(r); g = t(g); b = t(b)
            }
            "inv" -> { r = 1 - r; g = 1 - g; b = 1 - b }
            "gray" -> { val y = 0.3 * r + 0.59 * g + 0.11 * b; r = y; g = y; b = y }
        }
    }
    fun c(x: Double) = (x.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
    return (c(a) shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
}

private fun srgbToLinear(c: Double) = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
private fun linearToSrgb(c: Double): Double {
    val x = c.coerceIn(0.0, 1.0)
    return if (x <= 0.0031308) x * 12.92 else 1.055 * x.pow(1 / 2.4) - 0.055
}

private fun rgbToHsl(r: Double, g: Double, b: Double): DoubleArray {
    val max = maxOf(r, g, b); val min = minOf(r, g, b)
    val l = (max + min) / 2
    if (max == min) return doubleArrayOf(0.0, 0.0, l)
    val d = max - min
    val s = if (l > 0.5) d / (2 - max - min) else d / (max + min)
    val h = when (max) {
        r -> (g - b) / d + (if (g < b) 6 else 0)
        g -> (b - r) / d + 2
        else -> (r - g) / d + 4
    } / 6
    return doubleArrayOf(h, s, l)
}

private fun hslToRgb(h: Double, s: Double, l: Double): Int {
    fun hue(p: Double, q: Double, t0: Double): Double {
        val t = ((t0 % 1.0) + 1.0) % 1.0
        return when {
            t < 1.0 / 6 -> p + (q - p) * 6 * t
            t < 0.5 -> q
            t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
            else -> p
        }
    }
    val (r, g, b) = if (abs(s) < 1e-9) Triple(l, l, l) else {
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        Triple(hue(p, q, h + 1.0 / 3), hue(p, q, h), hue(p, q, h - 1.0 / 3))
    }
    fun c(x: Double) = (x.coerceIn(0.0, 1.0) * 255 + 0.5).toInt()
    return (0xFF shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
}

private val SYSTEM_COLORS = mapOf("windowText" to "000000", "window" to "FFFFFF", "btnFace" to "F0F0F0", "highlight" to "0078D7")

private val PRESET_COLORS = mapOf(
    "black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000, "blue" to 0x0000FF,
    "yellow" to 0xFFFF00, "cyan" to 0x00FFFF, "magenta" to 0xFF00FF, "gray" to 0x808080, "grey" to 0x808080,
    "darkGray" to 0xA9A9A9, "lightGray" to 0xD3D3D3, "orange" to 0xFFA500, "purple" to 0x800080,
    "navy" to 0x000080, "maroon" to 0x800000, "olive" to 0x808000, "teal" to 0x008080, "silver" to 0xC0C0C0,
    "lime" to 0x00FF00, "darkBlue" to 0x00008B, "darkRed" to 0x8B0000, "darkGreen" to 0x006400,
    "lightBlue" to 0xADD8E6, "gold" to 0xFFD700, "pink" to 0xFFC0CB, "brown" to 0xA52A2A
)
