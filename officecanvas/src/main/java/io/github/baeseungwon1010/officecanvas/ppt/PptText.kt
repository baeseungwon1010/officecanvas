package io.github.baeseungwon1010.officecanvas.ppt

import io.github.baeseungwon1010.officecanvas.slide.HAlign
import io.github.baeseungwon1010.officecanvas.slide.Paragraph
import io.github.baeseungwon1010.officecanvas.slide.Run

/** Paragraph formatting from a TextPFException (MS-PPT 2.9.20). Null = not set here. */
internal data class Pf(
    val hasBullet: Boolean? = null,
    val bulletHasFont: Boolean? = null,
    val bulletHasColor: Boolean? = null,
    val bulletHasSize: Boolean? = null,
    val bulletChar: Int? = null,
    val bulletFontRef: Int? = null,
    val bulletSize: Int? = null,
    val bulletColor: Int? = null,
    val align: HAlign? = null,
    val lineSpacing: Int? = null,
    val spaceBefore: Int? = null,
    val spaceAfter: Int? = null,
    val leftMargin: Int? = null,
    val indent: Int? = null
) {
    /** This over [base]: anything set here wins. */
    fun over(base: Pf) = Pf(
        hasBullet ?: base.hasBullet, bulletHasFont ?: base.bulletHasFont, bulletHasColor ?: base.bulletHasColor,
        bulletHasSize ?: base.bulletHasSize, bulletChar ?: base.bulletChar, bulletFontRef ?: base.bulletFontRef,
        bulletSize ?: base.bulletSize, bulletColor ?: base.bulletColor, align ?: base.align,
        lineSpacing ?: base.lineSpacing, spaceBefore ?: base.spaceBefore, spaceAfter ?: base.spaceAfter,
        leftMargin ?: base.leftMargin, indent ?: base.indent
    )
}

/** Character formatting from a TextCFException (MS-PPT 2.9.13). Null = not set here. */
internal data class Cf(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val fontRef: Int? = null,
    val size: Int? = null,
    val color: Int? = null,
    val symbolFontRef: Int? = null,
    /** Baseline offset, percent of line height: > 0 superscript, < 0 subscript. */
    val position: Int? = null,
    /** Index into the text's StyleTextProp9 entries (fontStyle bits 10..13). */
    val pp9rt: Int? = null
) {
    fun over(base: Cf) = Cf(
        bold ?: base.bold, italic ?: base.italic, underline ?: base.underline,
        fontRef ?: base.fontRef, size ?: base.size, color ?: base.color,
        symbolFontRef ?: base.symbolFontRef, position ?: base.position, pp9rt ?: base.pp9rt
    )
}

/** Returns the parsed exception and its byte size, or null if it runs past [end]. */
internal fun readPf(r: RecordReader, start: Int, end: Int): Pair<Pf, Int>? {
    if (start + 4 > end) return null
    val masks = r.i32(start)
    fun bit(n: Int) = (masks ushr n) and 1 != 0
    var off = start + 4
    fun take(n: Int): Int? = if (off + n > end) null else off.also { off += n }

    val flags = if (bit(0) || bit(1) || bit(2) || bit(3)) r.u16(take(2) ?: return null) else 0
    val bulletChar = if (bit(7)) r.u16(take(2) ?: return null) else null
    val bulletFont = if (bit(4)) r.u16(take(2) ?: return null) else null
    val bulletSize = if (bit(6)) r.i16(take(2) ?: return null) else null
    val bulletColor = if (bit(5)) r.i32(take(4) ?: return null) else null
    val align = if (bit(11)) when (r.u16(take(2) ?: return null)) {
        0 -> HAlign.LEFT
        1 -> HAlign.CENTER
        2 -> HAlign.RIGHT
        else -> HAlign.JUSTIFY
    } else null
    val lineSpacing = if (bit(12)) r.i16(take(2) ?: return null) else null
    val spaceBefore = if (bit(13)) r.i16(take(2) ?: return null) else null
    val spaceAfter = if (bit(14)) r.i16(take(2) ?: return null) else null
    val leftMargin = if (bit(8)) r.i16(take(2) ?: return null) else null
    val indent = if (bit(10)) r.i16(take(2) ?: return null) else null
    if (bit(15)) take(2) ?: return null // defaultTabSize
    if (bit(20)) { // tabStops: count + count * TabStop(4)
        val count = r.u16(take(2) ?: return null)
        take(count * 4) ?: return null
    }
    if (bit(16)) take(2) ?: return null // fontAlign
    if (bit(17) || bit(18) || bit(19)) take(2) ?: return null // wrapFlags
    if (bit(21)) take(2) ?: return null // textDirection

    fun flag(maskBit: Int, flagBit: Int) = if (bit(maskBit)) (flags ushr flagBit) and 1 != 0 else null
    return Pf(
        hasBullet = flag(0, 0), bulletHasFont = flag(1, 1), bulletHasColor = flag(2, 2), bulletHasSize = flag(3, 3),
        bulletChar = bulletChar, bulletFontRef = bulletFont, bulletSize = bulletSize, bulletColor = bulletColor,
        align = align, lineSpacing = lineSpacing, spaceBefore = spaceBefore, spaceAfter = spaceAfter,
        leftMargin = leftMargin, indent = indent
    ) to (off - start)
}

/** The pp10ext/newEATypeface/csTypeface/pp11ext mask bits only say that extra
 *  data exists in separate extension records; nothing for them is stored inline. */
internal fun readCf(r: RecordReader, start: Int, end: Int): Pair<Cf, Int>? {
    if (start + 4 > end) return null
    val masks = r.i32(start)
    fun bit(n: Int) = (masks ushr n) and 1 != 0
    var off = start + 4
    fun take(n: Int): Int? = if (off + n > end) null else off.also { off += n }

    val hasStyle = bit(0) || bit(1) || bit(2) || bit(4) || bit(5) || bit(7) || bit(9) || (masks ushr 10) and 0xF != 0
    val style = if (hasStyle) r.u16(take(2) ?: return null) else 0
    val fontRef = if (bit(16)) r.u16(take(2) ?: return null) else null
    if (bit(21)) take(2) ?: return null // oldEAFontRef
    if (bit(22)) take(2) ?: return null // ansiFontRef
    val symbolFontRef = if (bit(23)) r.u16(take(2) ?: return null) else null
    val size = if (bit(17)) r.u16(take(2) ?: return null).takeIf { it in 1..4000 } else null
    val color = if (bit(18)) r.i32(take(4) ?: return null) else null
    val position = if (bit(19)) r.i16(take(2) ?: return null).takeIf { it in -100..100 } else null

    return Cf(
        bold = if (bit(0)) style and 0x1 != 0 else null,
        italic = if (bit(1)) style and 0x2 != 0 else null,
        underline = if (bit(2)) style and 0x4 != 0 else null,
        fontRef = fontRef, size = size, color = color, symbolFontRef = symbolFontRef,
        position = position, pp9rt = if (hasStyle) (style ushr 10) and 0xF else null
    ) to (off - start)
}

/**
 * Master text styles: per text type (title, body, other, ...), up to five
 * indent levels of paragraph+character formatting. A placeholder's text stores
 * only what differs from these, so without them titles and bullets fall back
 * to plain 18pt black.
 */
internal class TextStyles(private val base: Pair<Pf, Cf>, private val byType: Map<Int, Array<Pair<Pf, Cf>?>>) {

    fun at(textType: Int, level: Int): Pair<Pf, Cf> {
        // centerTitle inherits title; centerBody/halfBody/quarterBody inherit body.
        val chain = when (textType) {
            TYPE_CENTER_TITLE -> listOf(TYPE_TITLE, TYPE_CENTER_TITLE)
            TYPE_CENTER_BODY, TYPE_HALF_BODY, TYPE_QUARTER_BODY -> listOf(TYPE_BODY, textType)
            TYPE_TITLE, TYPE_BODY, TYPE_NOTES, TYPE_OTHER -> listOf(textType)
            else -> listOf(TYPE_OTHER)
        }
        var pf = base.first
        var cf = base.second
        for (type in chain) {
            val levels = byType[type] ?: continue
            for (l in if (level > 0) listOf(0, level) else listOf(0)) {
                val (p, c) = levels.getOrNull(l) ?: continue
                pf = p.over(pf)
                cf = c.over(cf)
            }
        }
        return pf to cf
    }

    /** These styles with any text types this master doesn't define taken from [fallback]. */
    fun withFallback(fallback: TextStyles?): TextStyles =
        if (fallback == null) this else TextStyles(base, fallback.byType + byType)

    companion object {
        const val TYPE_TITLE = 0
        const val TYPE_BODY = 1
        const val TYPE_NOTES = 2
        const val TYPE_OTHER = 4
        const val TYPE_CENTER_BODY = 5
        const val TYPE_CENTER_TITLE = 6
        const val TYPE_HALF_BODY = 7
        const val TYPE_QUARTER_BODY = 8

        /** Reads every TextMasterStyleAtom directly under [container]. */
        fun read(r: RecordReader, container: Rec, base: Pair<Pf, Cf>): TextStyles {
            val byType = mutableMapOf<Int, Array<Pair<Pf, Cf>?>>()
            for (atom in r.children(container).filter { it.type == RT.TEXT_MASTER_STYLE_ATOM }) {
                readLevels(r, atom)?.let { byType[atom.instance] = it }
            }
            return TextStyles(base, byType)
        }

        private fun readLevels(r: RecordReader, atom: Rec): Array<Pair<Pf, Cf>?>? = try {
            val levels = arrayOfNulls<Pair<Pf, Cf>>(5)
            val count = r.u16(atom.start)
            var off = atom.start + 2
            for (i in 0 until minOf(count, 5)) {
                // Types 5..8 prefix each level with its own level number.
                val level = if (atom.instance >= TYPE_CENTER_BODY) r.u16(off).also { off += 2 } else i
                val (pf, pfSize) = readPf(r, off, atom.end) ?: break
                off += pfSize
                val (cf, cfSize) = readCf(r, off, atom.end) ?: break
                off += cfSize
                if (level in 0..4) levels[level] = pf to cf
            }
            levels
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }
}

/** Everything a text box needs from its surroundings to resolve its formatting. */
internal class TextContext(
    val styles: TextStyles,
    val scheme: ColorScheme,
    val fonts: List<String>,
    val slideNumber: Int
)

private const val PT_PER_MASTER_UNIT = 72.0 / 576.0
private const val EMU_PER_MASTER_UNIT = 1587.5

/**
 * Turns a text box's records (TextHeaderAtom, TextCharsAtom/TextBytesAtom,
 * StyleTextPropAtom, meta-character atoms) into paragraphs, splitting runs at
 * every character-formatting change so partial emphasis (a single blue word in
 * a sentence) survives.
 */
internal fun buildParagraphs(r: RecordReader, records: List<Rec>, ctx: TextContext, numbering: List<AutoNumber?> = emptyList()): List<Paragraph> {
    val textRec = records.firstOrNull { it.type == RT.TEXT_CHARS_ATOM || it.type == RT.TEXT_BYTES_ATOM } ?: return emptyList()
    val raw = if (textRec.type == RT.TEXT_CHARS_ATOM) {
        String(r.data, textRec.start, textRec.length, Charsets.UTF_16LE)
    } else {
        String(r.data, textRec.start, textRec.length, Charsets.ISO_8859_1)
    }
    if (raw.isEmpty()) return emptyList()
    val textType = records.firstOrNull { it.type == RT.TEXT_HEADER_ATOM }?.let { r.i32(it.start) } ?: TextStyles.TYPE_OTHER

    // Meta characters ('*' placeholders) that stand for a field value.
    val replacements = mutableMapOf<Int, String>()
    records.filter { it.type == RT.SLIDE_NUMBER_MC_ATOM && it.length >= 4 }
        .forEach { replacements[r.i32(it.start)] = ctx.slideNumber.toString() }
    fun display(from: Int, to: Int): String = buildString {
        for (i in from until to) {
            val repl = replacements[i]
            when {
                repl != null -> append(repl)
                raw[i] == '\u000B' -> append('\n') // vertical tab: line break inside a paragraph
                else -> append(raw[i])
            }
        }
    }

    // Style run counts cover one more character than is stored: the implied
    // final paragraph terminator.
    val style = records.firstOrNull { it.type == RT.STYLE_TEXT_PROP_ATOM }
        ?.let { parseStyleRuns(r, it, raw.length + 1) }

    val ruler = records.firstOrNull { it.type == RT.TEXT_RULER_ATOM }?.let { readRuler(r, it) }
    // Hyperlinked ranges (TxInteractiveInfoAtom: first and one-past-last character),
    // drawn in the color scheme's hyperlink color and underlined, as PowerPoint does.
    val links = records.filter { it.type == RT.TX_INTERACTIVE_INFO_ATOM && it.length >= 8 }
        .map { r.i32(it.start) until r.i32(it.start + 4) }.filter { !it.isEmpty() && it.first >= 0 }
    fun linkAt(i: Int) = links.any { i in it }
    fun nextLinkEdge(i: Int) = links.flatMap { listOf(it.first, it.last + 1) }.filter { it > i }.minOrNull() ?: Int.MAX_VALUE
    val paragraphs = mutableListOf<Paragraph>()
    val counter = NumberCounter()
    var ps = 0
    while (ps <= raw.length) {
        var pe = raw.indexOf('\r', ps)
        if (pe < 0) pe = raw.length
        val (level, pfOver) = style?.pfAt(ps) ?: (0 to Pf())
        val (masterPf, masterCf) = ctx.styles.at(textType, level)
        // The text box's own ruler sits between the master's indents and any
        // per-paragraph override.
        val pf = pfOver.over(ruler?.pfFor(level)?.over(masterPf) ?: masterPf)

        val runs = mutableListOf<Run>()
        var a = ps
        do {
            val (cfOver, runEnd) = style?.cfAt(a) ?: (Cf() to Int.MAX_VALUE)
            val b = minOf(pe, runEnd, nextLinkEdge(a)).coerceAtLeast(a)
            val cf = cfOver.over(masterCf)
            runs += makeRun(display(a, b), cf, ctx).let { run ->
                if (b > a && linkAt(a)) run.copy(colorArgb = ctx.scheme[6] ?: run.colorArgb, underline = true) else run
            }
            a = b
        } while (a < pe)

        // Which StyleTextProp9 entry (and so which auto-number scheme) applies is chosen
        // by the pp9rt of the paragraph's first character run.
        val firstCf = style?.cfAt(ps)?.first
        val scheme = numbering.getOrNull(firstCf?.pp9rt ?: 0)
        val hasText = runs.any { it.text.isNotBlank() }
        val number = counter.next(level, if (pf.hasBullet == true && hasText) scheme else null, hasText)
        paragraphs += makeParagraph(runs, pf, level, ctx, number).let { p ->
            if (ruler == null) p else p.copy(
                tabStopsEmu = ruler.tabs.map { (it * EMU_PER_MASTER_UNIT).toLong() },
                defaultTabEmu = ((ruler.defaultTab ?: 576) * EMU_PER_MASTER_UNIT).toLong()
            )
        }
        if (pe >= raw.length) break
        ps = pe + 1
    }
    return paragraphs
}

private fun makeRun(text: String, cf: Cf, ctx: TextContext): Run {
    val font = cf.fontRef?.let { ctx.fonts.getOrNull(it) }
    return Run(
    text = fixSymbolText(text, font, cf.symbolFontRef?.let { ctx.fonts.getOrNull(it) }),
    sizePt = (cf.size ?: 18).toFloat(),
    bold = cf.bold ?: false,
    italic = cf.italic ?: false,
    underline = cf.underline ?: false,
    colorArgb = cf.color?.let { resolveTextColor(it, ctx.scheme) } ?: ctx.scheme[1] ?: argb(0, 0, 0),
    fontFamily = font,
    baselinePercent = (cf.position ?: 0).toFloat()
    )
}

/**
 * Text typed in a symbol font (Symbol, Wingdings, ...) is stored as that font's
 * own code points, or as the same codes offset into the Private Use Area
 * (U+F0xx) — both show as boxes or stray letters in a normal font. Known symbol
 * fonts are translated to the Unicode characters they draw; a stray U+F0xx in
 * any other font is just its low byte (U+F05B is "[").
 */
internal fun fixSymbolText(text: String, font: String?, symbolFont: String? = null): String {
    if (text.none { it.code >= 0xF000 } && !isSymbolFont(font)) return text
    return buildString(text.length) {
        for (ch in text) {
            val code = ch.code
            val low = if (code in 0xF000..0xF0FF) code - 0xF000 else code
            // A U+F0xx character belongs to the run's symbol-font slot (e.g. a Wingdings 3
            // arrow), not to its normal text font.
            val face = if (code in 0xF000..0xF0FF && symbolFont != null) symbolFont else font
            val table = symbolTable(face)
            when {
                table != null && low in 0x20..0xFF ->
                    append(table[low] ?: if (face?.lowercase()?.contains("symbol") == true) ch else '\u2022')
                code in 0xF020..0xF0FF -> append(low.toChar())
                else -> append(ch)
            }
        }
    }
}

private fun isSymbolFont(font: String?) = symbolTable(font) != null

private fun symbolTable(font: String?): Map<Int, String>? = when (font?.lowercase()?.trim()) {
    "symbol" -> SYMBOL_TEXT
    "wingdings 3" -> WINGDINGS3
    "wingdings", "wingdings 2" -> WINGDINGS
    "monotype sorts", "zapf dingbats", "itc zapf dingbats", "zapfdingbats" -> DINGBATS
    "webdings" -> WEBDINGS
    else -> null
}

/** The Symbol font's Greek letters and math operators (its A-Z/a-z are Greek, not Latin). */
private val SYMBOL_TEXT: Map<Int, String> by lazy { buildMap {
    putAll(SYMBOL)
    "ΑΒΧΔΕΦΓΗΙϑΚΛΜΝΟΠΘΡΣΤΥςΩΞΨΖ".forEachIndexed { i, c -> put(0x41 + i, c.toString()) }
    "αβχδεφγηιϕκλμνοπθρστυϖωξψζ".forEachIndexed { i, c -> put(0x61 + i, c.toString()) }
    for (c in 0x20..0x40) put(c, c.toChar().toString())
    for (c in listOf(0x5B, 0x5D, 0x7B, 0x7C, 0x7D)) put(c, c.toChar().toString())
    putAll(mapOf(
        0xB1 to "±", 0xA3 to "≤", 0xB3 to "≥", 0xB4 to "×", 0xB8 to "÷", 0xB9 to "≠", 0xBA to "≡", 0xBB to "≈",
        0xA5 to "∞", 0xB0 to "°", 0xD6 to "√", 0xB6 to "∂", 0xD1 to "∇", 0xE5 to "∑", 0xD5 to "∏", 0xF2 to "∫",
        0xAC to "←", 0xAD to "↑", 0xAE to "→", 0xAF to "↓", 0xAB to "↔", 0xDE to "⇒", 0xDC to "⇐", 0xDB to "⇔",
        0xCE to "∈", 0xC7 to "∩", 0xC8 to "∪", 0xCC to "⊂", 0xCD to "⊆", 0xC6 to "∅", 0x22 to "∀", 0x24 to "∃",
        0xD9 to "∧", 0xDA to "∨", 0xD8 to "¬", 0xB7 to "•", 0xA8 to "♦", 0xA7 to "♣", 0xAA to "♠", 0xA9 to "♥"
    ))
} }

private fun makeParagraph(runs: List<Run>, pf: Pf, level: Int, ctx: TextContext, number: String? = null): Paragraph {
    val sizePt = runs.firstOrNull()?.sizePt ?: 18f
    val hasText = runs.any { it.text.isNotBlank() }
    val bullet = if (number != null) number else if (pf.hasBullet == true && hasText) {
        val font = if (pf.bulletHasFont == true) pf.bulletFontRef?.let { ctx.fonts.getOrNull(it) } else null
        bulletGlyph(pf.bulletChar, font)
    } else null
    val bulletColor = if (pf.bulletHasColor == true) pf.bulletColor?.let { resolveTextColor(it, ctx.scheme) } else null
    val bulletSize = pf.bulletSize?.takeIf { pf.bulletHasSize == true && it in 25..400 }?.toFloat() ?: 100f

    return Paragraph(
        runs = runs,
        align = pf.align ?: HAlign.LEFT,
        level = level,
        bullet = bullet,
        spaceBeforePt = spacingPt(pf.spaceBefore, sizePt),
        spaceAfterPt = spacingPt(pf.spaceAfter, sizePt),
        lineSpacingPercent = pf.lineSpacing?.let { v ->
            if (v >= 0) v.toFloat() else (-v * PT_PER_MASTER_UNIT / (sizePt * 1.2) * 100).toFloat()
        }?.takeIf { it > 0f } ?: 100f,
        marginLeftEmu = pf.leftMargin?.let { (it * EMU_PER_MASTER_UNIT).toLong() },
        bulletIndentEmu = pf.indent?.let { (it * EMU_PER_MASTER_UNIT).toLong() },
        bulletColorArgb = bulletColor,
        bulletSizePercent = bulletSize
    )
}

/** Space before/after: non-negative = percent of a line, negative = master units. */
private fun spacingPt(value: Int?, sizePt: Float): Float = when {
    value == null -> 0f
    value >= 0 -> value / 100f * sizePt * 1.25f
    else -> (-value * PT_PER_MASTER_UNIT).toFloat()
}

/** Bullets are usually a character in a symbol font (Monotype Sorts 'n' is a
 *  black square); those code points mean nothing in a normal font, so the common
 *  ones are mapped to the Unicode shape they draw, and any other character from
 *  a symbol font becomes a plain bullet rather than a stray letter. */
internal fun bulletGlyph(code: Int?, font: String?): String {
    if (code == null || code == 0) return "•"
    val c = if (code in 0xF000..0xF0FF) code - 0xF000 else code
    val table = when (font?.lowercase()?.trim()) {
        null -> null
        "wingdings 3" -> WINGDINGS3
        "wingdings", "wingdings 2" -> WINGDINGS
        "monotype sorts", "zapf dingbats", "itc zapf dingbats", "zapfdingbats" -> DINGBATS
        "webdings" -> WEBDINGS
        "symbol" -> SYMBOL
        else -> return if (c < 0x20) "•" else String(Character.toChars(c))
    }
    return table?.get(c) ?: "•"
}

private val WINGDINGS = mapOf(
    0x6C to "●", 0x6D to "❍", 0x6E to "■", 0x6F to "□", 0x70 to "◻", 0x71 to "❑", 0x72 to "❒",
    0x73 to "⬧", 0x74 to "⧫", 0x75 to "◆", 0x76 to "❖", 0x77 to "⬥", 0x9F to "•", 0xA1 to "○",
    0xA7 to "▪", 0xA8 to "◻", 0xD8 to "➢", 0xE0 to "➔", 0xE8 to "➔", 0xFC to "✓", 0xFE to "☑"
)
private val DINGBATS = mapOf(
    0x6C to "●", 0x6D to "❍", 0x6E to "■", 0x6F to "❏", 0x70 to "❐", 0x71 to "❑", 0x72 to "❒",
    0x73 to "▲", 0x74 to "▼", 0x75 to "◆", 0x76 to "❖", 0x33 to "✓", 0x34 to "✔", 0xA8 to "♣", 0xD8 to "➘"
)
private val WINGDINGS3 = mapOf(0x5B to "→", 0x5C to "←", 0x5D to "↑", 0x5E to "↓")
private val WEBDINGS = mapOf(0x33 to "◂", 0x34 to "►", 0x35 to "▴", 0x36 to "▾", 0x3C to "■", 0x3D to "▪", 0x6E to "●")
private val SYMBOL = mapOf(0xB7 to "•", 0x2D to "−", 0xAE to "→", 0xD7 to "·", 0xA8 to "♦")

/** PF and CF runs of a StyleTextPropAtom, as (count, value) lists. */
private class StyleRuns(val pf: List<Triple<Int, Int, Pf>>, val cf: List<Pair<Int, Cf>>) {
    // Lookups come in increasing index order, so each list is walked once overall
    // rather than from the start on every call (quadratic for many short runs).
    private var pfRun = 0
    private var pfStart = 0
    private var cfRun = 0
    private var cfStart = 0

    /** Indent level and paragraph formatting in effect at [index]. */
    fun pfAt(index: Int): Pair<Int, Pf> {
        if (index < pfStart) { pfRun = 0; pfStart = 0 }
        while (pfRun < pf.size && index >= pfStart + pf[pfRun].first) { pfStart += pf[pfRun].first; pfRun++ }
        val run = pf.getOrNull(pfRun) ?: pf.lastOrNull() ?: return 0 to Pf()
        return run.second.coerceIn(0, 4) to run.third
    }

    /** Character formatting at [index] and the index where that run ends. */
    fun cfAt(index: Int): Pair<Cf, Int> {
        if (index < cfStart) { cfRun = 0; cfStart = 0 }
        while (cfRun < cf.size && index >= cfStart + cf[cfRun].first) { cfStart += cf[cfRun].first; cfRun++ }
        val run = cf.getOrNull(cfRun) ?: return (cf.lastOrNull()?.second ?: Cf()) to Int.MAX_VALUE
        return run.second to cfStart + run.first
    }
}

/** TextPFRun list (count, indentLevel, TextPFException) then TextCFRun list
 *  (count, TextCFException), each summing to [length]. Null if it doesn't add up. */
private fun parseStyleRuns(r: RecordReader, atom: Rec, length: Int): StyleRuns? {
    var off = atom.start
    val pf = mutableListOf<Triple<Int, Int, Pf>>()
    var sum = 0
    while (sum < length) {
        if (off + 6 > atom.end) return null
        val count = r.i32(off)
        val level = r.u16(off + 4)
        if (count <= 0) return null
        val (p, size) = readPf(r, off + 6, atom.end) ?: return null
        off += 6 + size
        pf += Triple(count, level, p)
        sum += count
    }
    val cf = mutableListOf<Pair<Int, Cf>>()
    sum = 0
    while (sum < length) {
        if (off + 4 > atom.end) break // a truncated CF list still leaves the PF runs usable
        val count = r.i32(off)
        if (count <= 0) break
        val (c, size) = readCf(r, off + 4, atom.end) ?: break
        off += 4 + size
        cf += count to c
        sum += count
    }
    return StyleRuns(pf, cf)
}

/** One StyleTextProp9 entry's auto-numbering: a TextAutoNumberSchemeEnum and its first number. */
internal data class AutoNumber(val scheme: Int, val start: Int)

/**
 * Numbers a list the way PowerPoint does: per indent level, consecutive
 * numbered paragraphs count up; a non-numbered paragraph at that level or a
 * shallower one restarts it, while deeper (sub-item) paragraphs leave it alone.
 * Empty paragraphs show nothing and don't break the sequence.
 */
internal class NumberCounter {
    private val value = IntArray(5)
    private val scheme = IntArray(5) { -1 }

    fun next(level: Int, n: AutoNumber?, hasText: Boolean): String? {
        val l = level.coerceIn(0, 4)
        if (!hasText) return null
        for (deeper in l + 1 until 5) scheme[deeper] = -1
        if (n == null) { scheme[l] = -1; return null }
        value[l] = if (scheme[l] == n.scheme) value[l] + 1 else n.start
        scheme[l] = n.scheme
        return formatNumber(n.scheme, value[l])
    }
}

/** TextAutoNumberSchemeEnum (MS-PPT 2.13.28) rendered for number [n]. */
internal fun formatNumber(scheme: Int, n: Int): String {
    fun alpha(upper: Boolean): String {
        // Letters repeat past z (aa, bbb, ...), so very long lists switch to digits
        // rather than building ever-longer labels.
        if (n > 26 * 26) return n.toString()
        val c = ((if (upper) 'A' else 'a') + (n - 1) % 26).toString()
        return c.repeat((n - 1) / 26 + 1)
    }
    fun roman(upper: Boolean): String {
        var v = n.coerceIn(1, 3999)
        val sb = StringBuilder()
        for ((value, sym) in listOf(1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
            50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I")) {
            while (v >= value) { sb.append(sym); v -= value }
        }
        return if (upper) sb.toString() else sb.toString().lowercase()
    }
    fun circled(black: Boolean) = when {
        black && n in 1..10 -> (0x2776 + n - 1).toChar().toString()
        black && n in 11..20 -> (0x24EB + n - 11).toChar().toString()
        !black && n in 1..20 -> (0x2460 + n - 1).toChar().toString()
        else -> "($n)"
    }
    fun wide(s: String) = s.map { if (it in '0'..'9') (it - '0' + 0xFF10).toChar() else it }.joinToString("")
    return when (scheme) {
        0 -> alpha(false) + "."
        1 -> alpha(true) + "."
        2 -> "$n)"
        4 -> "(" + roman(false) + ")"
        5 -> roman(false) + ")"
        6 -> roman(false) + "."
        7 -> roman(true) + "."
        8 -> "(" + alpha(false) + ")"
        9 -> alpha(false) + ")"
        10 -> "(" + alpha(true) + ")"
        11 -> alpha(true) + ")"
        12 -> "($n)"
        13 -> "$n"
        14 -> "(" + roman(true) + ")"
        15 -> roman(true) + ")"
        18, 19 -> circled(black = false)
        20 -> circled(black = true)
        26, 28 -> wide("$n")
        29 -> wide("$n") + "．"
        else -> "$n."
    }
}

/**
 * StyleTextProp9Atom (MS-PPT 2.9.66): an array of {TextPFException9,
 * TextCFException9, TextSIException}, each a mask word followed by the fields
 * it enables. Only the auto-number scheme is kept; null where an entry has none.
 */
internal fun readStyleTextProp9(r: RecordReader, atom: Rec): List<AutoNumber?> {
    val out = mutableListOf<AutoNumber?>()
    var off = atom.start
    while (off + 12 <= atom.end) {
        // PF9: bulletBlipRef (bit 23), fBulletHasAutoNumber (bit 25), scheme + startNum (bit 24)
        val pfMasks = r.i32(off); off += 4
        fun pf(bit: Int) = (pfMasks ushr bit) and 1 != 0
        if (pf(23)) off += 2
        var has = false
        if (pf(25)) { has = r.i16(off) != 0; off += 2 }
        var number: AutoNumber? = null
        if (pf(24)) { number = AutoNumber(r.u16(off), r.i16(off + 2).coerceAtLeast(1)); off += 4 }
        // CF9: pp10runid + unused (bit 20)
        if (off + 4 > atom.end) break
        val cfMasks = r.i32(off); off += 4
        if ((cfMasks ushr 20) and 1 != 0) off += 4
        // SI: spellInfo, lid, altLid (bits 0..2), bidi (6), pp10 group (5), smartTags (9)
        if (off + 4 > atom.end) break
        val si = r.i32(off); off += 4
        fun sib(bit: Int) = (si ushr bit) and 1 != 0
        if (sib(0)) off += 2
        if (sib(1)) off += 2
        if (sib(2)) off += 2
        if (sib(6)) off += 2
        if (sib(5)) off += 4
        if (sib(9)) {
            // smartTags: a count, then that many 4-byte entries. A negative or
            // oversized count would move the cursor backwards and loop forever.
            if (off + 4 > atom.end) break
            val tags = r.i32(off)
            if (tags < 0 || tags > (atom.end - off - 4) / 4) break
            off += 4 + 4 * tags
        }
        if (off > atom.end) break
        out += if (has || (number != null && !pf(25))) number ?: AutoNumber(3, 1) else null
    }
    return out
}

/**
 * TextRulerAtom (MS-PPT 2.9.35/2.9.36): a text box's tab stops and per-level
 * left margin / first-line indent, all in master units. Flags (bit: field):
 * 0 defaultTabSize, 1 cLevels, 2 tabs, 3..7 leftMargin1..5, 8..12 indent1..5;
 * fields follow in the order cLevels, defaultTabSize, tabs, then
 * leftMargin1, indent1, leftMargin2, indent2, ...
 */
internal class Ruler(val defaultTab: Int?, val tabs: List<Int>, private val margins: Array<Int?>, private val indents: Array<Int?>) {
    fun pfFor(level: Int): Pf? {
        val l = level.coerceIn(0, 4)
        if (margins[l] == null && indents[l] == null) return null
        return Pf(leftMargin = margins[l], indent = indents[l])
    }
}

internal fun readRuler(r: RecordReader, atom: Rec): Ruler? {
    if (atom.length < 4) return null
    val flags = r.i32(atom.start)
    fun bit(n: Int) = (flags ushr n) and 1 != 0
    var off = atom.start + 4
    fun take(n: Int): Int? = if (off + n > atom.end) null else off.also { off += n }
    if (bit(1)) take(2) ?: return null // cLevels
    val defaultTab = if (bit(0)) r.u16(take(2) ?: return null) else null
    val tabs = mutableListOf<Int>()
    if (bit(2)) {
        val count = r.u16(take(2) ?: return null)
        repeat(count) {
            val at = take(4) ?: return null
            tabs += r.i16(at) // TabStop: position (master units), then type
        }
    }
    val margins = arrayOfNulls<Int>(5)
    val indents = arrayOfNulls<Int>(5)
    for (l in 0 until 5) {
        if (bit(3 + l)) margins[l] = r.i16(take(2) ?: return null)
        if (bit(8 + l)) indents[l] = r.i16(take(2) ?: return null)
    }
    return Ruler(defaultTab?.takeIf { it > 0 }, tabs.filter { it > 0 }.sorted(), margins, indents)
}
