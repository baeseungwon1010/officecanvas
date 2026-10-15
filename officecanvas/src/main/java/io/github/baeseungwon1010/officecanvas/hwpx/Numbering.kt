package io.github.baeseungwon1010.officecanvas.hwpx

/**
 * Paragraph numbers and bullets, shared by the .hwpx and .hwp parsers.
 *
 * Hangul doesn't store a numbered paragraph's number as text: the paragraph's
 * shape names a numbering (or bullet) definition and a level, and the number is
 * counted while the document is laid out. The saved line positions don't leave
 * room for it either; Hangul draws the number at the start of the first line and
 * the text after it, which is what [ParaHead.prefix] produces.
 */

/** How one level is numbered: [format] with `^1`..`^7` standing for the levels'
 *  numbers ("^1.", "(^5)"), each written in its level's [shape]. */
internal class NumberLevel(val format: String, val shape: Int, val start: Int, val gapPercent: Int)

/** A numbering definition: up to ten levels. */
internal class NumberingDef(val levels: List<NumberLevel>)

/** A bullet definition: the character and its distance from the text. */
internal class BulletDef(val char: String, val gapPercent: Int)

/** A paragraph's heading: [kind] 1 outline, 2 numbered, 3 bullet; [id] 1-based. */
internal class ParaHeading(val kind: Int, val id: Int, val level: Int)

/**
 * Counts numbered paragraphs through a document. A paragraph at some level
 * advances that level's counter and restarts the deeper ones, per definition.
 */
internal class ParaHead(private val numberings: List<NumberingDef>, private val bullets: List<BulletDef>) {
    private val counters = HashMap<Int, IntArray>()

    /** The number or bullet for the next paragraph with [heading], and the gap
     *  after it as a percentage of the text size; null for none. [outlineId] is the
     *  section's numbering for outline paragraphs. */
    fun next(heading: ParaHeading?, outlineId: Int): Pair<String, Int>? {
        heading ?: return null
        if (heading.kind == 3) {
            val b = bullets.getOrNull(heading.id - 1) ?: return null
            return b.char.takeIf { it.isNotBlank() }?.let { it to b.gapPercent }
        }
        val id = if (heading.kind == 1) outlineId else heading.id
        val def = numberings.getOrNull(id - 1) ?: return null
        val level = heading.level.coerceIn(0, def.levels.size - 1).takeIf { def.levels.isNotEmpty() } ?: return null
        val counts = counters.getOrPut(id) { IntArray(def.levels.size) { def.levels[it].start - 1 } }
        counts[level]++
        for (deeper in level + 1 until counts.size) counts[deeper] = def.levels[deeper].start - 1
        val lv = def.levels[level]
        if (lv.format.isEmpty()) return null
        val text = Regex("\\^(\\d+)").replace(lv.format) { m ->
            val n = m.groupValues[1].toInt() - 1
            if (n in counts.indices) format(counts[n].coerceAtLeast(def.levels[n].start), def.levels[n].shape) else ""
        }
        return text to lv.gapPercent
    }

    companion object {
        /** [n] written in number shape [shape] (Hangul's order: 1, ①, I, i, A, a, Ⓐ, ⓐ, 가, ㉮, ㄱ, ㉠, 일, 一, ㊀). */
        fun format(n: Int, shape: Int): String = when (shape) {
            1 -> if (n in 1..20) (0x2460 + n - 1).toChar().toString() else "$n"
            2 -> roman(n)
            3 -> roman(n).lowercase()
            4 -> latin(n, 'A')
            5 -> latin(n, 'a')
            6 -> if (n in 1..26) (0x24B6 + n - 1).toChar().toString() else latin(n, 'A')
            7 -> if (n in 1..26) (0x24D0 + n - 1).toChar().toString() else latin(n, 'a')
            8 -> cycle(n, HANGUL_SYLLABLES)
            9 -> if (n in 1..14) (0x326E + n - 1).toChar().toString() else cycle(n, HANGUL_SYLLABLES)
            10 -> cycle(n, HANGUL_JAMO)
            11 -> if (n in 1..14) (0x3260 + n - 1).toChar().toString() else cycle(n, HANGUL_JAMO)
            12 -> sino(n, "일이삼사오육칠팔구", "십", "백")
            13 -> sino(n, "一二三四五六七八九", "十", "百")
            14 -> if (n in 1..10) (0x3280 + n - 1).toChar().toString() else sino(n, "一二三四五六七八九", "十", "百")
            else -> "$n"
        }

        private const val HANGUL_SYLLABLES = "가나다라마바사아자차카타파하"
        private const val HANGUL_JAMO = "ㄱㄴㄷㄹㅁㅂㅅㅇㅈㅊㅋㅌㅍㅎ"

        private fun cycle(n: Int, letters: String) = if (n < 1) "$n" else letters[(n - 1) % letters.length].toString()

        private fun latin(n: Int, first: Char): String {
            if (n < 1) return "$n"
            var k = n
            val sb = StringBuilder()
            while (k > 0 && sb.length < 8) { k--; sb.insert(0, first + k % 26); k /= 26 }
            return sb.toString()
        }

        private fun roman(n: Int): String {
            if (n !in 1..3999) return "$n"
            val values = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val symbols = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            var k = n
            val sb = StringBuilder()
            for (i in values.indices) while (k >= values[i]) { sb.append(symbols[i]); k -= values[i] }
            return sb.toString()
        }

        /** Sino-Korean or Chinese numerals up to 999. */
        private fun sino(n: Int, digits: String, ten: String, hundred: String): String {
            if (n !in 1..999) return "$n"
            val sb = StringBuilder()
            fun part(d: Int, unit: String) { if (d > 0) { if (d > 1 || unit.isEmpty()) sb.append(digits[d - 1]); sb.append(unit) } }
            part(n / 100, hundred); part(n / 10 % 10, ten); part(n % 10, "")
            return sb.toString()
        }
    }
}

/** Puts [head] (a number or bullet) and the gap after it, [gapPercent] of the text
 *  size, in front of a paragraph's first line. */
internal fun HwpxElement.TextRun.withHead(head: String, gapPercent: Int): HwpxElement.TextRun {
    val style = spans.firstOrNull()?.style ?: return this
    val gap = (style.sizePt * 100 * gapPercent / 100).toLong().coerceIn(0, 20_000)
    return copy(spans = listOf(Span(head, style), Span("\t", style, gap)) + spans)
}
