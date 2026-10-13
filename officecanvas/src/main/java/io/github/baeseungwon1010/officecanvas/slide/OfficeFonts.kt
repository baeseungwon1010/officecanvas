package io.github.baeseungwon1010.officecanvas.slide

import android.content.res.AssetManager
import android.graphics.Typeface
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import java.io.File

/**
 * Metric-compatible stand-ins for the fonts Office decks use most: Liberation
 * Sans/Serif/Mono have exactly the glyph widths of Arial/Helvetica, Times New
 * Roman and Courier New, and Carlito those of Calibri (the default Office theme
 * font), so text wraps where PowerPoint wrapped it (Android's Roboto is
 * noticeably narrower, which moved line breaks and made text look smaller).
 * Bundled in assets/officecanvas/fonts under the SIL Open Font License (see
 * LICENSE-Liberation.txt and LICENSE-Carlito.txt next to the fonts). Carlito
 * comes in regular and bold only; italics are slanted from those.
 *
 * Glyphs these fonts lack (Hangul, CJK, symbols) still come from the system's
 * fallback fonts automatically.
 */
internal class OfficeFonts private constructor(private val load: (String) -> Typeface?) {

    enum class Kind { SANS, SERIF, MONO, CALIBRI }

    private val cache = HashMap<String, Typeface?>()

    fun typeface(kind: Kind, bold: Boolean, italic: Boolean): Typeface? {
        val name = when (kind) {
            Kind.SANS -> "LiberationSans"
            Kind.SERIF -> "LiberationSerif"
            Kind.MONO -> "LiberationMono"
            Kind.CALIBRI -> "Carlito"
        } + "-" + when {
            !kind.hasItalic -> if (bold) "Bold" else "Regular"
            bold && italic -> "BoldItalic"
            bold -> "Bold"
            italic -> "Italic"
            else -> "Regular"
        } + ".ttf"
        return cache.getOrPut(name) { load(name) }
    }

    /** Whether italic font files are bundled; otherwise italics are synthesized. */
    private val Kind.hasItalic get() = this == Kind.SANS || this == Kind.SERIF

    /** Sets a specific typeface on a run — unlike TypefaceSpan(String), this takes
     *  a loaded font file, and unlike TypefaceSpan(Typeface) it works below API 28. */
    class Span(private val typeface: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) { tp.typeface = typeface }
        override fun updateMeasureState(tp: TextPaint) { tp.typeface = typeface }
    }

    companion object {
        fun fromAssets(assets: AssetManager) = OfficeFonts { name ->
            try { Typeface.createFromAsset(assets, "officecanvas/fonts/$name") } catch (e: RuntimeException) { null }
        }

        fun fromDirectory(dir: File) = OfficeFonts { name ->
            File(dir, name).takeIf { it.exists() }?.let { Typeface.createFromFile(it) }
        }

        /** Which metric family a deck font maps to; null = not a Latin text face
         *  we have a stand-in for (symbol fonts, script faces), left to the system. */
        fun kindFor(family: String?): Kind? {
            val f = family?.lowercase()?.trim() ?: return Kind.SANS
            return when {
                listOf("courier", "consolas", "lucida console", "mono", "fixedsys", "terminal").any { it in f } -> Kind.MONO
                "calibri" in f -> Kind.CALIBRI
                listOf("times", "georgia", "garamond", "cambria", "book antiqua", "palatino", "century schoolbook").any { it in f } -> Kind.SERIF
                f == "serif" -> Kind.SERIF
                listOf("symbol", "wingdings", "webdings", "dingbats", "sorts", "comic", "script", "brush").any { it in f } -> null
                listOf("batang", "바탕", "myeongjo", "명조", "mincho", "gulim", "굴림", "dotum", "돋움", "malgun", "맑은", "gothic", "고딕", "nanum", "나눔").any { it in f } -> null
                else -> Kind.SANS
            }
        }
    }
}
