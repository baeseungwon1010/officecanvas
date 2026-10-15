package io.github.baeseungwon1010.officecanvas.hwpx

/**
 * Hangul's equation language, as stored in a .hwp `EQEDIT` record or a .hwpx
 * `<hp:script>`: an EQN-like script such as `{1} over {sigma SQRT {2 pi}}` or
 * `sum _{i=1} ^{n} x_{i}`. The file keeps only the script and the box Hangul
 * typeset it into, so the formula is typeset again here ([EquationLayout]) and
 * fitted to that box.
 *
 * Words are separated by spaces, which take no room themselves; `~` is a space
 * and `` ` `` a quarter of one. Braces group. Keywords for structure are matched
 * without regard to case (`SQRT`, `sqrt`), symbol names with it (`sigma` is σ,
 * `SIGMA` Σ).
 */
internal sealed class EqNode {
    /** Text set in one style: [italic] for variables, upright for numbers, functions, symbols. */
    data class Text(val text: String, val italic: Boolean = false, val bold: Boolean = false) : EqNode()
    data class Row(val items: List<EqNode>) : EqNode()
    data class Space(val em: Float) : EqNode()
    data class Scripts(val base: EqNode, val sup: EqNode?, val sub: EqNode?) : EqNode()
    data class Fraction(val num: EqNode, val den: EqNode, val line: Boolean = true) : EqNode()
    data class Root(val body: EqNode, val index: EqNode? = null) : EqNode()
    /** A large operator (∑, ∏, ∫, lim) with limits [below] and [above]. */
    data class BigOp(val symbol: String, val below: EqNode?, val above: EqNode?, val limitsBeside: Boolean = false) : EqNode()
    /** [body] between delimiters stretched to its height; "" for none. */
    data class Fenced(val left: String, val body: EqNode, val right: String) : EqNode()
    /** Rows of cells; [align] -1 left, 0 center, 1 right. */
    data class Matrix(val rows: List<List<EqNode>>, val align: Int = 0) : EqNode()
    data class Accent(val body: EqNode, val mark: String, val under: Boolean = false) : EqNode()
}

internal object EquationParser {

    /** Caps for scripts from untrusted files. */
    private const val MAX_TOKENS = 20_000
    private const val MAX_DEPTH = 32

    fun parse(script: String): EqNode = Parser(tokenize(script.take(100_000))).parseAll()

    // ---- tokens ----------------------------------------------------------------

    private sealed class Tok {
        data class Word(val s: String) : Tok()
        data class Num(val s: String) : Tok()
        data class Quoted(val s: String) : Tok()
        data class Sym(val s: String) : Tok()
        data class Gap(val em: Float) : Tok()
    }

    /** Operators written with more than one character, longest first. */
    private val MULTI = listOf("<=>", "...", "==", "!=", "<=", ">=", "+-", "-+", "->", "<-", "=>", "<<", ">>", "''")

    private fun tokenize(s: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < s.length && out.size < MAX_TOKENS) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c == '~' -> { out += Tok.Gap(0.3f); i++ }
                c == '`' -> { out += Tok.Gap(0.08f); i++ }
                c == '"' -> {
                    val end = s.indexOf('"', i + 1).let { if (it < 0) s.length else it }
                    out += Tok.Quoted(s.substring(i + 1, end)); i = end + 1
                }
                c.isDigit() || (c == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                    var j = i
                    while (j < s.length && (s[j].isDigit() || s[j] == '.')) j++
                    out += Tok.Num(s.substring(i, j)); i = j
                }
                c in 'a'..'z' || c in 'A'..'Z' -> {
                    var j = i
                    while (j < s.length && (s[j] in 'a'..'z' || s[j] in 'A'..'Z')) j++
                    out += Tok.Word(s.substring(i, j)); i = j
                }
                c.isLetter() -> {
                    // Hangul and other scripts: a run of them is one upright word.
                    var j = i
                    while (j < s.length && s[j].isLetter() && s[j] !in 'a'..'z' && s[j] !in 'A'..'Z') j++
                    out += Tok.Quoted(s.substring(i, j)); i = j
                }
                else -> {
                    val multi = MULTI.firstOrNull { s.startsWith(it, i) }
                    if (multi != null) { out += Tok.Sym(multi); i += multi.length } else { out += Tok.Sym(c.toString()); i++ }
                }
            }
        }
        return out
    }

    // ---- symbols ---------------------------------------------------------------

    private val GREEK = listOf(
        "alpha" to 'α', "beta" to 'β', "gamma" to 'γ', "delta" to 'δ', "epsilon" to 'ε', "zeta" to 'ζ', "eta" to 'η',
        "theta" to 'θ', "iota" to 'ι', "kappa" to 'κ', "lambda" to 'λ', "mu" to 'μ', "nu" to 'ν', "xi" to 'ξ',
        "omicron" to 'ο', "pi" to 'π', "rho" to 'ρ', "sigma" to 'σ', "tau" to 'τ', "upsilon" to 'υ', "phi" to 'φ',
        "chi" to 'χ', "psi" to 'ψ', "omega" to 'ω'
    )
    private val GREEK_LOWER = GREEK.associate { (n, c) -> n to c.toString() }
    private val GREEK_UPPER = GREEK.associate { (n, c) -> n.uppercase() to c.uppercaseChar().toString() } +
        mapOf("vartheta" to "ϑ", "varphi" to "ϕ", "varepsilon" to "ϵ", "varsigma" to "ς")

    /** Named symbols, matched without case. */
    private val SYMBOLS = mapOf(
        "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "plusminus" to "±", "mp" to "∓", "minusplus" to "∓",
        "approx" to "≈", "sim" to "∼", "simeq" to "≃", "cong" to "≅", "equiv" to "≡", "neq" to "≠",
        "leq" to "≤", "geq" to "≥", "ll" to "≪", "gg" to "≫", "prec" to "≺", "succ" to "≻",
        "in" to "∈", "notin" to "∉", "ni" to "∋", "owns" to "∋", "subset" to "⊂", "supset" to "⊃", "superset" to "⊃",
        "subseteq" to "⊆", "supseteq" to "⊇", "cup" to "∪", "cap" to "∩", "emptyset" to "∅",
        "inf" to "∞", "infty" to "∞", "infinity" to "∞", "partial" to "∂", "nabla" to "∇", "forall" to "∀", "exist" to "∃", "exists" to "∃",
        "therefore" to "∴", "because" to "∵", "angle" to "∠", "deg" to "°", "prime" to "′", "perp" to "⊥", "bot" to "⊥",
        "propto" to "∝", "wedge" to "∧", "vee" to "∨", "neg" to "¬", "oplus" to "⊕", "otimes" to "⊗", "odot" to "⊙",
        "cdots" to "⋯", "ldots" to "…", "vdots" to "⋮", "ddots" to "⋱", "dots" to "…", "aleph" to "ℵ", "hbar" to "ℏ",
        "rarrow" to "→", "larrow" to "←", "uarrow" to "↑", "darrow" to "↓", "lrarrow" to "↔", "uparrow" to "↑", "downarrow" to "↓",
        "rightarrow" to "→", "leftarrow" to "←", "mapsto" to "↦", "triangle" to "△", "box" to "□", "diamond" to "◇",
        "bullet" to "•", "star" to "⋆", "circ" to "∘", "dagger" to "†", "ell" to "ℓ", "wp" to "℘",
        "smallsum" to "∑", "smallprod" to "∏", "dint" to "∬", "tint" to "∭", "vert" to "|", "lfloor" to "⌊", "rfloor" to "⌋",
        "lceil" to "⌈", "rceil" to "⌉", "langle" to "⟨", "rangle" to "⟩"
    )
    /** Uppercase double arrows, which differ from the lowercase single ones. */
    private val CASED_SYMBOLS = mapOf("RARROW" to "⇒", "LARROW" to "⇐", "LRARROW" to "⇔", "UARROW" to "⇑", "DARROW" to "⇓", "IMPLIES" to "⇒", "IFF" to "⇔")
    private val OPERATORS = mapOf(
        "<=>" to "⇔", "..." to "…", "==" to "≡", "!=" to "≠", "<=" to "≤", ">=" to "≥", "+-" to "±", "-+" to "∓",
        "->" to "→", "<-" to "←", "=>" to "⇒", "<<" to "≪", ">>" to "≫", "''" to "″", "-" to "−", "*" to "∗", "'" to "′"
    )
    /** Upright function names. */
    private val FUNCTIONS = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "sinh", "cosh", "tanh", "coth", "arcsin", "arccos", "arctan",
        "log", "ln", "lg", "exp", "max", "min", "sup", "inf", "det", "dim", "mod", "gcd", "lcm", "arg", "deg", "ker", "hom", "Pr",
        "lim", "Lim", "liminf", "limsup"
    )
    private val BIG_OPS = mapOf(
        "sum" to "∑", "prod" to "∏", "coprod" to "∐", "int" to "∫", "oint" to "∮", "dint" to "∬", "tint" to "∭",
        "odint" to "∯", "otint" to "∰", "union" to "⋃", "bigcup" to "⋃", "inter" to "⋂", "bigcap" to "⋂",
        "bigoplus" to "⨁", "bigotimes" to "⨂", "bigodot" to "⨀", "lim" to "lim", "smallinter" to "∩", "smallunion" to "∪"
    )
    private val ACCENTS = mapOf(
        "hat" to "^", "bar" to "‾", "vec" to "→", "dot" to "˙", "ddot" to "¨", "tilde" to "~", "check" to "ˇ",
        "acute" to "´", "grave" to "`", "dyad" to "↔", "arch" to "⌒", "under" to "_", "overline" to "‾", "underline" to "_"
    )
    private val MATRICES = setOf("matrix", "pmatrix", "bmatrix", "dmatrix", "cases", "pile", "lpile", "rpile", "eqalign", "stack")
    private val DELIMS = mapOf("(" to "(", ")" to ")", "[" to "[", "]" to "]", "{" to "{", "}" to "}", "|" to "|", "<" to "⟨", ">" to "⟩", "." to "",
        "langle" to "⟨", "rangle" to "⟩", "lfloor" to "⌊", "rfloor" to "⌋", "lceil" to "⌈", "rceil" to "⌉", "vert" to "|", "dline" to "‖")

    // ---- parser ----------------------------------------------------------------

    private class Parser(val toks: List<Tok>) {
        var pos = 0
        var depth = 0

        fun peek(): Tok? = toks.getOrNull(pos)
        fun next(): Tok? = toks.getOrNull(pos++)
        fun peekWord(vararg names: String): Boolean = (peek() as? Tok.Word)?.s?.lowercase() in names
        fun peekSym(s: String) = (peek() as? Tok.Sym)?.s == s

        fun parseAll(): EqNode {
            val items = ArrayList<EqNode>()
            while (pos < toks.size) {
                items += parseSeq(stopAtBrace = false)
                if (pos < toks.size) pos++ // a stray closing brace or separator
            }
            return row(items)
        }

        /** Items up to a closing brace, cell separator, RIGHT or the end. */
        fun parseSeq(stopAtBrace: Boolean = true, stopAtCells: Boolean = false, stopAtRight: Boolean = false): EqNode {
            if (++depth > MAX_DEPTH) { depth--; skipGroup(); return EqNode.Row(emptyList()) }
            val items = ArrayList<EqNode>()
            loop@ while (true) {
                val t = peek() ?: break
                when {
                    t is Tok.Sym && t.s == "}" -> if (stopAtBrace) break@loop else break@loop
                    t is Tok.Sym && (t.s == "&" || t.s == "#") && stopAtCells -> break@loop
                    t is Tok.Word && stopAtRight && t.s.lowercase() == "right" -> break@loop
                    t is Tok.Word && t.s.lowercase() in setOf("over", "atop") -> {
                        next()
                        val num = items.removeLastOrNull() ?: EqNode.Row(emptyList())
                        val den = parseScripted() ?: EqNode.Row(emptyList())
                        items += EqNode.Fraction(num, den, line = t.s.lowercase() == "over")
                    }
                    else -> {
                        val before = pos
                        parseScripted()?.let { items += it }
                        if (pos == before) next() // never stall on a token nothing takes
                    }
                }
            }
            depth--
            return row(items)
        }

        private fun skipGroup() {
            var level = 0
            while (pos < toks.size) {
                val t = toks[pos]
                if (t is Tok.Sym && t.s == "{") level++
                if (t is Tok.Sym && t.s == "}") { if (level == 0) return; level-- }
                pos++
            }
        }

        /** An atom with any sub- and superscripts after it. */
        fun parseScripted(): EqNode? {
            val base = parseAtom() ?: return null
            var sup: EqNode? = null
            var sub: EqNode? = null
            while (true) {
                val t = peek()
                val word = (t as? Tok.Word)?.s?.lowercase()
                when {
                    t is Tok.Sym && t.s == "^" || word == "sup" || word == "lsup" -> { next(); sup = parseAtom() ?: EqNode.Row(emptyList()) }
                    t is Tok.Sym && t.s == "_" || word == "sub" || word == "lsub" -> { next(); sub = parseAtom() ?: EqNode.Row(emptyList()) }
                    else -> break
                }
            }
            return if (sup == null && sub == null) base else EqNode.Scripts(base, sup, sub)
        }

        fun group(): EqNode? {
            if (!peekSym("{")) return parseAtom()
            next()
            val g = parseSeq()
            if (peekSym("}")) next()
            return g
        }

        fun parseAtom(): EqNode? {
            if (depth > MAX_DEPTH) return null
            val t = peek() ?: return null
            when (t) {
                is Tok.Num -> { next(); return EqNode.Text(t.s) }
                is Tok.Quoted -> { next(); return EqNode.Text(t.s) }
                is Tok.Gap -> { next(); return EqNode.Space(t.em) }
                is Tok.Sym -> {
                    if (t.s == "{") { depth++; val g = group(); depth--; return g }
                    if (t.s == "}" || t.s == "^" || t.s == "_" || t.s == "&" || t.s == "#") return null
                    next()
                    return EqNode.Text(OPERATORS[t.s] ?: t.s)
                }
                is Tok.Word -> { next(); return word(t.s) }
            }
        }

        fun word(w: String): EqNode? {
            val lower = w.lowercase()
            CASED_SYMBOLS[w]?.let { return EqNode.Text(it) }
            GREEK_LOWER[w]?.let { return EqNode.Text(it, italic = true) }
            GREEK_UPPER[w]?.let { return EqNode.Text(it) }
            when (lower) {
                "sqrt", "root" -> {
                    // "root n of x" has an index; "sqrt x" none.
                    if (lower == "root") {
                        val index = group()
                        if (peekWord("of")) next()
                        return EqNode.Root(group() ?: EqNode.Row(emptyList()), index)
                    }
                    return EqNode.Root(group() ?: EqNode.Row(emptyList()))
                }
                "left" -> {
                    val open = delimiter()
                    depth++
                    val body = parseSeq(stopAtBrace = false, stopAtRight = true)
                    depth--
                    if (peekWord("right")) next()
                    val close = delimiter()
                    return EqNode.Fenced(open, body, close)
                }
                "right" -> { delimiter(); return null }
                "rm", "it", "bold" -> {
                    val body = parseAtom() ?: return null
                    return restyle(body, italic = lower == "it", bold = lower == "bold")
                }
                "from", "to" -> return null
            }
            if (lower in MATRICES) return matrix(lower)
            ACCENTS[lower]?.let { mark ->
                val body = parseAtom() ?: EqNode.Row(emptyList())
                return EqNode.Accent(body, mark, under = lower == "under" || lower == "underline")
            }
            BIG_OPS[lower]?.let { symbol -> return bigOp(symbol, beside = lower in setOf("int", "oint", "dint", "tint", "odint", "otint")) }
            SYMBOLS[lower]?.let { return EqNode.Text(it) }
            if (w in FUNCTIONS || lower in FUNCTIONS) return EqNode.Text(w)
            // Any other word is a run of variables.
            return EqNode.Text(w, italic = true)
        }

        fun delimiter(): String {
            val t = next() ?: return ""
            return when (t) {
                is Tok.Sym -> DELIMS[t.s] ?: t.s
                is Tok.Word -> DELIMS[t.s.lowercase()] ?: ""
                else -> ""
            }
        }

        fun bigOp(symbol: String, beside: Boolean): EqNode {
            var below: EqNode? = null
            var above: EqNode? = null
            while (true) {
                val t = peek()
                val word = (t as? Tok.Word)?.s?.lowercase()
                when {
                    word == "from" || (t is Tok.Sym && t.s == "_") -> { next(); below = group() }
                    word == "to" || (t is Tok.Sym && t.s == "^") -> { next(); above = group() }
                    else -> break
                }
            }
            return EqNode.BigOp(symbol, below?.takeUnless { it.isEmpty() }, above?.takeUnless { it.isEmpty() }, beside)
        }

        fun matrix(kind: String): EqNode {
            if (!peekSym("{")) return EqNode.Text(kind, italic = true)
            next()
            depth++
            val rows = ArrayList<MutableList<EqNode>>()
            var row = ArrayList<EqNode>()
            while (pos < toks.size && rows.size < 200) {
                val cell = parseSeq(stopAtCells = true)
                row += cell
                val t = next() ?: break
                if (t is Tok.Sym && t.s == "&") continue
                rows += row
                row = ArrayList()
                if (t is Tok.Sym && t.s == "}") break
            }
            if (row.isNotEmpty()) rows += row
            depth--
            // pile/eqalign/cases stack their rows; a pile's cells are one column.
            val align = when (kind) { "lpile", "cases" -> -1; "rpile" -> 1; else -> 0 }
            val m = EqNode.Matrix(rows.map { it.take(50) }, align)
            return when (kind) {
                "pmatrix" -> EqNode.Fenced("(", m, ")")
                "bmatrix" -> EqNode.Fenced("[", m, "]")
                "dmatrix" -> EqNode.Fenced("|", m, "|")
                "cases" -> EqNode.Fenced("{", m, "")
                else -> m
            }
        }

        fun restyle(n: EqNode, italic: Boolean, bold: Boolean): EqNode = when (n) {
            is EqNode.Text -> n.copy(italic = italic, bold = bold || n.bold)
            is EqNode.Row -> EqNode.Row(n.items.map { restyle(it, italic, bold) })
            else -> n
        }
    }

    private fun EqNode.isEmpty() = this is EqNode.Row && items.isEmpty()

    private fun row(items: List<EqNode>): EqNode = items.singleOrNull() ?: EqNode.Row(items)
}
