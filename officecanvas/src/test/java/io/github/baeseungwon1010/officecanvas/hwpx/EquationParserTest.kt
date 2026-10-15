package io.github.baeseungwon1010.officecanvas.hwpx

import io.github.baeseungwon1010.officecanvas.hwpx.EqNode.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scripts in Hangul's equation language, as real documents write them. */
class EquationParserTest {

    private fun parse(s: String) = EquationParser.parse(s)

    @Test
    fun `fractions, roots and greek letters`() {
        assertEquals(
            Fraction(Text("1"), Row(listOf(Text("σ", italic = true), Root(Row(listOf(Text("2"), Text("π", italic = true))))))),
            parse("{1} over { sigma SQRT { 2 pi } }")
        )
        // Upper-case names are capital letters, set upright.
        assertEquals(Text("Σ"), parse("SIGMA"))
    }

    @Test
    fun `scripts attach to what comes before them`() {
        assertEquals(Scripts(Text("x", italic = true), Text("2"), Text("i", italic = true)), parse("x _{i} ^{2}"))
        // A group of one is that one item.
        assertEquals(Scripts(Text("10"), Row(listOf(Text("−"), Text("2"))), null), parse("{ 10}^{-2 }"))
    }

    @Test
    fun `big operators take their limits from from-to or scripts`() {
        val sum = parse("sum _{i=1} ^{n} x_{i}") as Row
        assertEquals(BigOp("∑", Row(listOf(Text("i", italic = true), Text("="), Text("1"))), Text("n", italic = true)), sum.items[0])
        val fromTo = parse("SUM from {set~1} {x}") as Row
        assertEquals("∑", (fromTo.items[0] as BigOp).symbol)
    }

    @Test
    fun `delimiters, matrices and cases`() {
        assertEquals(Fenced("(", Text("a", italic = true), ")"), parse("LEFT ( a RIGHT )"))
        val m = parse("pmatrix{a & b # c & d}") as Fenced
        assertEquals("(", m.left)
        assertEquals(2, (m.body as Matrix).rows.size)
        assertEquals(2, m.body.rows[0].size)
        assertEquals("{", (parse("cases{x # y}") as Fenced).left)
    }

    @Test
    fun `symbols, spaces, quoted text and Hangul words`() {
        val row = parse("a TIMES b LEQ c~APPROX`\"text\" == 미지비트") as Row
        val texts = row.items.filterIsInstance<Text>().map { it.text }
        assertEquals(listOf("a", "×", "b", "≤", "c", "≈", "text", "≡", "미지비트"), texts)
        assertEquals(2, row.items.count { it is Space })
        // Functions are upright, other words italic.
        assertEquals(Text("log"), (parse("log x") as Row).items[0])
        assertEquals(Text("pH", italic = true), (parse("pH") as Text))
    }

    @Test
    fun `broken scripts still parse, and deep nesting is cut off`() {
        parse("{{{a over")
        parse("} } ^ _ & # RIGHT )")
        parse("sqrt")
        val deep = System.nanoTime()
        parse("{".repeat(100_000) + "x" + "}".repeat(100_000))
        parse("x^".repeat(50_000))
        assertTrue((System.nanoTime() - deep) / 1e9 < 5)
    }
}
