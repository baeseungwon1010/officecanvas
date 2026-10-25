package io.github.baeseungwon1010.officecanvas.docx

import io.github.baeseungwon1010.officecanvas.hwpx.EqNode
import io.github.baeseungwon1010.officecanvas.pptx.children
import org.w3c.dom.Element

/**
 * Word's equations (Office Math, `<m:oMath>`) as the equation tree the Hangul
 * equation typesetter draws ([EqNode]). Office Math saves structure, not layout:
 * fractions, scripts, radicals, n-ary operators, delimiters, matrices, accents,
 * function names and limits, each with its arguments in `<m:e>`, `<m:num>` and the like.
 */
internal object Omml {

    const val M = "http://schemas.openxmlformats.org/officeDocument/2006/math"

    private const val MAX_DEPTH = 32

    fun parse(math: Element): EqNode = row(math, 0, upright = false)

    /** The children of [el] in order, as one row. [upright]: letters set upright
     *  (a function's name). */
    private fun row(el: Element?, depth: Int, upright: Boolean): EqNode {
        el ?: return EqNode.Row(emptyList())
        if (depth > MAX_DEPTH) return EqNode.Row(emptyList())
        val items = ArrayList<EqNode>()
        for (c in el.children()) {
            if (c.namespaceURI != M) continue
            node(c, depth + 1, upright)?.let { if (it is EqNode.Row) items += it.items else items += it }
        }
        return if (items.size == 1) items[0] else EqNode.Row(items)
    }

    private fun Element.m(name: String): Element? = children().firstOrNull { it.namespaceURI == M && it.localName == name }

    /** A property's value: `<m:xPr><m:name m:val="..."/></m:xPr>`. */
    private fun Element.prop(pr: String, name: String): String? =
        m(pr)?.m(name)?.let { it.getAttributeNS(M, "val").ifEmpty { "on" } }

    private fun String?.on() = this != null && this != "0" && this != "off" && this != "false"

    private fun node(c: Element, depth: Int, upright: Boolean): EqNode? = when (c.localName) {
        "r" -> run(c, upright)
        "f" -> {
            val num = row(c.m("num"), depth, upright)
            val den = row(c.m("den"), depth, upright)
            when (c.prop("fPr", "type")) {
                "lin", "skw" -> EqNode.Row(listOf(num, EqNode.Text("/"), den))
                "noBar" -> EqNode.Fraction(num, den, line = false)
                else -> EqNode.Fraction(num, den)
            }
        }
        "sSup" -> EqNode.Scripts(row(c.m("e"), depth, upright), row(c.m("sup"), depth, upright), null)
        "sSub" -> EqNode.Scripts(row(c.m("e"), depth, upright), null, row(c.m("sub"), depth, upright))
        "sSubSup" -> EqNode.Scripts(row(c.m("e"), depth, upright), row(c.m("sup"), depth, upright), row(c.m("sub"), depth, upright))
        "sPre" -> EqNode.Row(listOf(
            EqNode.Scripts(EqNode.Row(emptyList()), row(c.m("sup"), depth, upright), row(c.m("sub"), depth, upright)),
            row(c.m("e"), depth, upright)
        ))
        "rad" -> {
            val deg = if (c.prop("radPr", "degHide").on()) null else c.m("deg")?.takeIf { it.children().isNotEmpty() }?.let { row(it, depth, upright) }
            EqNode.Root(row(c.m("e"), depth, upright), deg)
        }
        "nary" -> {
            val symbol = c.prop("naryPr", "chr")?.takeIf { it != "on" } ?: "∫"
            val beside = (c.prop("naryPr", "limLoc") ?: if (symbol in INTEGRALS) "subSup" else "undOvr") == "subSup"
            val sub = if (c.prop("naryPr", "subHide").on()) null else c.m("sub")?.takeIf { it.children().isNotEmpty() }?.let { row(it, depth, upright) }
            val sup = if (c.prop("naryPr", "supHide").on()) null else c.m("sup")?.takeIf { it.children().isNotEmpty() }?.let { row(it, depth, upright) }
            EqNode.Row(listOf(EqNode.BigOp(symbol, sub, sup, limitsBeside = beside), row(c.m("e"), depth, upright)))
        }
        "d" -> {
            fun chr(name: String, default: String) = c.m("dPr")?.m(name)?.let { it.getAttributeNS(M, "val") } ?: default
            val begin = chr("begChr", "(")
            val end = chr("endChr", ")")
            val sep = chr("sepChr", "|")
            val parts = c.children().filter { it.namespaceURI == M && it.localName == "e" }.map { row(it, depth, upright) }
            val body = parts.flatMapIndexed { i, p -> if (i == 0) listOf(p) else listOf(EqNode.Text(sep), p) }
            EqNode.Fenced(begin, EqNode.Row(body), end)
        }
        "m" -> EqNode.Matrix(
            c.children().filter { it.namespaceURI == M && it.localName == "mr" }.take(200).map { r ->
                r.children().filter { it.namespaceURI == M && it.localName == "e" }.take(200).map { row(it, depth, upright) }
            }
        )
        "eqArr" -> EqNode.Matrix(c.children().filter { it.namespaceURI == M && it.localName == "e" }.take(500).map { listOf(row(it, depth, upright)) }, align = 0)
        "acc" -> {
            val chr = c.prop("accPr", "chr")?.takeIf { it != "on" } ?: "̂"
            EqNode.Accent(row(c.m("e"), depth, upright), ACCENTS[chr] ?: chr)
        }
        "bar" -> EqNode.Accent(row(c.m("e"), depth, upright), "‾", under = c.prop("barPr", "pos") == "bot")
        "groupChr" -> {
            val chr = c.prop("groupChrPr", "chr")?.takeIf { it != "on" } ?: "⏟"
            val top = c.prop("groupChrPr", "pos") == "top"
            EqNode.Accent(row(c.m("e"), depth, upright), if (chr == "⏟" || chr == "⏞") "‾" else chr, under = !top)
        }
        "func" -> EqNode.Row(listOf(row(c.m("fName"), depth, upright = true), EqNode.Space(0.17f), row(c.m("e"), depth, upright)))
        "limLow" -> limit(row(c.m("e"), depth, true), below = row(c.m("lim"), depth, upright), above = null)
        "limUpp" -> limit(row(c.m("e"), depth, true), below = null, above = row(c.m("lim"), depth, upright))
        "box", "borderBox", "phant" -> row(c.m("e"), depth, upright)
        "oMath", "oMathPara" -> row(c, depth, upright)
        // Properties and anything unknown: properties draw nothing; an unknown
        // structure is read for the text in it.
        else -> if (c.localName.endsWith("Pr")) null else row(c, depth, upright)
    }

    /** "lim" and the like with limits under or over them; anything else as scripts. */
    private fun limit(base: EqNode, below: EqNode?, above: EqNode?): EqNode {
        val word = (base as? EqNode.Text)?.text
        return if (word != null) EqNode.BigOp(word, below, above) else EqNode.Scripts(base, above, below)
    }

    /** A run's text: letters in italic unless the run is set plain (or [upright]),
     *  digits and signs upright. */
    private fun run(r: Element, upright: Boolean): EqNode? {
        val text = r.children().filter { it.namespaceURI == M && it.localName == "t" }.joinToString("") { it.textContent }
        if (text.isEmpty()) return null
        val pr = r.m("rPr")
        val sty = pr?.m("sty")?.getAttributeNS(M, "val")
        val plain = upright || pr?.m("nor") != null || sty == "p" || sty == "b"
        val bold = sty == "b" || sty == "bi"
        if (plain) return EqNode.Text(text, italic = false, bold = bold)
        // Split into letters (italic) and everything else (upright).
        val out = ArrayList<EqNode>()
        var k = 0
        while (k < text.length) {
            val letter = text[k].isLetter()
            var end = k + 1
            while (end < text.length && text[end].isLetter() == letter) end++
            out += EqNode.Text(text.substring(k, end), italic = letter && text[k].code < 0x1100, bold = bold)
            k = end
        }
        return if (out.size == 1) out[0] else EqNode.Row(out)
    }

    private val INTEGRALS = setOf("∫", "∬", "∭", "∮", "∯", "∰")

    /** Office Math's accent characters (combining marks) as the marks drawn. */
    private val ACCENTS = mapOf(
        "̂" to "^", "̃" to "~", "̄" to "‾", "̅" to "‾", "̇" to "˙", "̈" to "¨",
        "̌" to "ˇ", "⃗" to "→", "⃖" to "←", "́" to "´", "̀" to "`", "̆" to "˘"
    )
}
