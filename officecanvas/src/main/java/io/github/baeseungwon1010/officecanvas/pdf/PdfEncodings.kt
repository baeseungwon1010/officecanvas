package io.github.baeseungwon1010.officecanvas.pdf

/** PDF's simple font encodings (Annex D) and glyph names as Unicode. */
internal object PdfEncodings {

    /** WinAnsiEncoding: Windows-1252, its unassigned codes as bullets. */
    val WIN_ANSI: Array<String?> by lazy { Array(256) { c ->
        when (c) {
            in 0x20..0x7E -> c.toChar().toString()
            in 0xA0..0xFF -> c.toChar().toString()
            in 0x80..0x9F -> CP1252_HIGH[c - 0x80]?.toString()
            else -> null
        }
    } }

    private val CP1252_HIGH: Array<Char?> = arrayOf(
        '€', '•', '‚', 'ƒ', '„', '…', '†', '‡', 'ˆ', '‰', 'Š', '‹', 'Œ', '•', 'Ž', '•',
        '•', '‘', '’', '“', '”', '•', '–', '—', '˜', '™', 'š', '›', 'œ', '•', 'ž', 'Ÿ'
    )

    /** StandardEncoding: ASCII but quotes, and Adobe's own upper half. */
    val STANDARD: Array<String?> by lazy { Array(256) { c ->
        when (c) {
            0x27 -> "’"; 0x60 -> "‘"
            in 0x20..0x7E -> c.toChar().toString()
            else -> STANDARD_HIGH[c]?.toString()
        }
    } }

    private val STANDARD_HIGH: Map<Int, Char> = mapOf(
        0xA1 to '¡', 0xA2 to '¢', 0xA3 to '£', 0xA4 to '⁄', 0xA5 to '¥', 0xA6 to 'ƒ', 0xA7 to '§', 0xA8 to '¤', 0xA9 to '\'',
        0xAA to '“', 0xAB to '«', 0xAC to '‹', 0xAD to '›', 0xAE to 'ﬁ', 0xAF to 'ﬂ', 0xB1 to '–', 0xB2 to '†', 0xB3 to '‡',
        0xB4 to '·', 0xB6 to '¶', 0xB7 to '•', 0xB8 to '‚', 0xB9 to '„', 0xBA to '”', 0xBB to '»', 0xBC to '…', 0xBD to '‰',
        0xBF to '¿', 0xC1 to '`', 0xC2 to '´', 0xC3 to 'ˆ', 0xC4 to '˜', 0xC5 to '¯', 0xC6 to '˘', 0xC7 to '˙', 0xC8 to '¨',
        0xCA to '˚', 0xCB to '¸', 0xCD to '˝', 0xCE to '˛', 0xCF to 'ˇ', 0xD0 to '—', 0xE1 to 'Æ', 0xE3 to 'ª', 0xE8 to 'Ł',
        0xE9 to 'Ø', 0xEA to 'Œ', 0xEB to 'º', 0xF1 to 'æ', 0xF5 to 'ı', 0xF8 to 'ł', 0xF9 to 'ø', 0xFA to 'œ', 0xFB to 'ß'
    )

    /** MacRomanEncoding's upper half. */
    val MAC_ROMAN: Array<String?> by lazy { Array(256) { c ->
        when (c) {
            in 0x20..0x7E -> c.toChar().toString()
            in 0x80..0xFF -> MAC_HIGH[c - 0x80].toString()
            else -> null
        }
    } }

    private const val MAC_HIGH =
        "ÄÅÇÉÑÖÜáàâäãåçéèêëíìîïñóòôöõúùûü†°¢£§•¶ß®©™´¨≠ÆØ∞±≤≥¥µ∂∑∏π∫ªºΩæø¿¡¬√ƒ≈∆«»… ÀÃÕŒœ–—“”‘’÷◊ÿŸ⁄€‹›ﬁﬂ‡·‚„‰ÂÊÁËÈÍÎÏÌÓÔÒÚÛÙıˆ˜¯˘˙˚¸˝˛ˇ"

    /** The Symbol font's built-in encoding (Greek and mathematical signs). */
    val SYMBOL: Array<String?> by lazy { Array(256) { c ->
        when (c) {
            in 0x20..0x7E -> SYMBOL_LOW[c - 0x20].toString()
            in 0xA0..0xFF -> SYMBOL_HIGH.getOrNull(c - 0xA0)?.takeIf { it != ' ' || c == 0xA0 }?.toString()
            else -> null
        }
    } }

    private const val SYMBOL_LOW = " !∀#∃%&∋()∗+,−./0123456789:;<=>?≅ΑΒΧΔΕΦΓΗΙϑΚΛΜΝΟΠΘΡΣΤΥςΩΞΨΖ[∴]⊥_‾αβχδεφγηιϕκλμνοπθρστυϖωξψζ{|}∼"
    private const val SYMBOL_HIGH = "€ϒ′≤⁄∞ƒ♣♦♥♠↔←↑→↓°±″≥×∝∂•÷≠≡≈…⏐⎯↵ℵℑℜ℘⊗⊕∅∩∪⊃⊇⊄⊂⊆∈∉∠∇®©™∏√⋅¬∧∨⇔⇐⇑⇒⇓◊〈®©™∑⎛⎜⎝⎡⎢⎣⎧⎨⎩⎪ 〉∫⌠⎮⌡⎞⎟⎠⎤⎥⎦⎫⎬⎭ "

    fun named(name: String?): Array<String?>? = when (name) {
        "WinAnsiEncoding" -> WIN_ANSI
        "MacRomanEncoding", "MacExpertEncoding" -> MAC_ROMAN
        "StandardEncoding" -> STANDARD
        else -> null
    }

    /** A glyph name as text: Adobe's names for the usual glyphs, uniXXXX, uXXXX[XX], and single letters. */
    fun glyph(name: String): String? {
        GLYPHS[name]?.let { return it }
        if (name.length == 1) return name
        val base = name.substringBefore('.').substringBefore('_')
        if (base != name) {
            if (name.contains('_') && !name.startsWith("uni")) {
                return name.substringBefore('.').split('_').map { glyph(it) ?: return null }.joinToString("")
            }
            return glyph(base)
        }
        if (name.startsWith("uni") && name.length >= 7 && (name.length - 3) % 4 == 0) {
            val sb = StringBuilder()
            var i = 3
            while (i + 4 <= name.length) { sb.append((name.substring(i, i + 4).toIntOrNull(16) ?: return null).toChar()); i += 4 }
            return sb.toString()
        }
        if (name.startsWith("u") && name.length in 5..7) {
            val cp = name.substring(1).toIntOrNull(16) ?: return null
            return if (cp in 0..0x10FFFF) String(Character.toChars(cp)) else null
        }
        return null
    }

    private val GLYPHS: Map<String, String> = buildMap {
        fun put(s: String) {
            // "name=char name=char ..."
            for (pair in s.trim().split(Regex("\\s+"))) {
                val k = pair.indexOf('=')
                if (k > 0) put(pair.substring(0, k), pair.substring(k + 1))
            }
        }
        put("space=  exclam=! quotedbl=\" numbersign=# dollar=$ percent=% ampersand=& quotesingle=' parenleft=( parenright=) asterisk=* plus=+ comma=, hyphen=- period=. slash=/")
        put("zero=0 one=1 two=2 three=3 four=4 five=5 six=6 seven=7 eight=8 nine=9 colon=: semicolon=; less=< equal== greater=> question=? at=@")
        put("bracketleft=[ backslash=\\ bracketright=] asciicircum=^ underscore=_ grave=` braceleft={ bar=| braceright=} asciitilde=~")
        put("exclamdown=¡ cent=¢ sterling=£ currency=¤ yen=¥ brokenbar=¦ section=§ dieresis=¨ copyright=© ordfeminine=ª guillemotleft=« logicalnot=¬ registered=® macron=¯")
        put("degree=° plusminus=± twosuperior=² threesuperior=³ acute=´ mu=µ paragraph=¶ periodcentered=· cedilla=¸ onesuperior=¹ ordmasculine=º guillemotright=» onequarter=¼ onehalf=½ threequarters=¾ questiondown=¿")
        put("Agrave=À Aacute=Á Acircumflex=Â Atilde=Ã Adieresis=Ä Aring=Å AE=Æ Ccedilla=Ç Egrave=È Eacute=É Ecircumflex=Ê Edieresis=Ë Igrave=Ì Iacute=Í Icircumflex=Î Idieresis=Ï")
        put("Eth=Ð Ntilde=Ñ Ograve=Ò Oacute=Ó Ocircumflex=Ô Otilde=Õ Odieresis=Ö multiply=× Oslash=Ø Ugrave=Ù Uacute=Ú Ucircumflex=Û Udieresis=Ü Yacute=Ý Thorn=Þ germandbls=ß")
        put("agrave=à aacute=á acircumflex=â atilde=ã adieresis=ä aring=å ae=æ ccedilla=ç egrave=è eacute=é ecircumflex=ê edieresis=ë igrave=ì iacute=í icircumflex=î idieresis=ï")
        put("eth=ð ntilde=ñ ograve=ò oacute=ó ocircumflex=ô otilde=õ odieresis=ö divide=÷ oslash=ø ugrave=ù uacute=ú ucircumflex=û udieresis=ü yacute=ý thorn=þ ydieresis=ÿ")
        put("Euro=€ quotesinglbase=‚ florin=ƒ quotedblbase=„ ellipsis=… dagger=† daggerdbl=‡ circumflex=ˆ perthousand=‰ Scaron=Š guilsinglleft=‹ OE=Œ Zcaron=Ž")
        put("quoteleft=‘ quoteright=’ quotedblleft=“ quotedblright=” bullet=• endash=– emdash=— tilde=˜ trademark=™ scaron=š guilsinglright=› oe=œ zcaron=ž Ydieresis=Ÿ")
        put("fi=ﬁ fl=ﬂ ff=ﬀ ffi=ﬃ ffl=ﬄ dotlessi=ı Lslash=Ł lslash=ł minus=− fraction=⁄ breve=˘ dotaccent=˙ ring=˚ hungarumlaut=˝ ogonek=˛ caron=ˇ nbspace=  sfthyphen=­")
        put("Alpha=Α Beta=Β Gamma=Γ Delta=Δ Epsilon=Ε Zeta=Ζ Eta=Η Theta=Θ Iota=Ι Kappa=Κ Lambda=Λ Mu=Μ Nu=Ν Xi=Ξ Omicron=Ο Pi=Π Rho=Ρ Sigma=Σ Tau=Τ Upsilon=Υ Phi=Φ Chi=Χ Psi=Ψ Omega=Ω")
        put("alpha=α beta=β gamma=γ delta=δ epsilon=ε zeta=ζ eta=η theta=θ iota=ι kappa=κ lambda=λ nu=ν xi=ξ omicron=ο pi=π rho=ρ sigma=σ sigma1=ς tau=τ upsilon=υ phi=φ chi=χ psi=ψ omega=ω")
        put("theta1=ϑ phi1=ϕ omega1=ϖ Upsilon1=ϒ infinity=∞ partialdiff=∂ summation=∑ product=∏ radical=√ integral=∫ approxequal=≈ notequal=≠ lessequal=≤ greaterequal=≥ equivalence=≡")
        put("arrowleft=← arrowright=→ arrowup=↑ arrowdown=↓ arrowboth=↔ arrowdblleft=⇐ arrowdblright=⇒ arrowdblboth=⇔ element=∈ notelement=∉ intersection=∩ union=∪ propersubset=⊂ propersuperset=⊃")
        put("reflexsubset=⊆ reflexsuperset=⊇ emptyset=∅ gradient=∇ angle=∠ logicaland=∧ logicalor=∨ therefore=∴ universal=∀ existential=∃ proportional=∝ similar=∼ congruent=≅ perpendicular=⊥ dotmath=⋅")
        put("lozenge=◊ circlemultiply=⊗ circleplus=⊕ minute=′ second=″ club=♣ diamond=♦ heart=♥ spade=♠ filledbox=■ H22073=□ H18543=▪ H18551=▫ triagup=▲ triagdn=▼ circle=○ blackcircle=● uni25CF=●")
        put("checkmark=✓ a20=✔ a19=✓ a12=☛ a71=● a72=❍ a73=■ a74=❏ a75=❐ a76=▲ a77=▼ a78=◆ a79=❖ a81=◗ a13=☞ a14=✌ a15=✍ a16=✎ estimated=℮ afii61289=ℓ afii61352=№ onedotenleader=․ twodotenleader=‥")
        put("exclamsmall=! commaaccent=, periodsuperior=. uni00A0=  Ohm=Ω increment=∆ micro=µ")
    }
}
