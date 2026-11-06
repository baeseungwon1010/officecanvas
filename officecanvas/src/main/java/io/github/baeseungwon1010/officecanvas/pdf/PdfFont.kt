package io.github.baeseungwon1010.officecanvas.pdf

import java.nio.ByteBuffer
import java.nio.charset.Charset

/**
 * A font as text extraction needs it (9.5-9.10): how a string's bytes split into
 * codes, each code's text and width, and the font's face, weight and slant.
 *
 * Text comes from, in order: the ToUnicode CMap; for CID fonts, a Unicode-based or
 * CJK predefined CMap (the codes are the text in that encoding), or the embedded
 * TrueType font's own cmap or glyph names; for simple fonts, the encoding (with its
 * Differences' glyph names) or the embedded font's cmap.
 */
internal class PdfFont private constructor(
    val baseName: String,
    val family: String,
    val bold: Boolean,
    val italic: Boolean,
    val monospace: Boolean,
    val serif: Boolean,
    private val cid: Boolean,
    private val codeMap: PdfCMap?,
    private val toUnicode: PdfCMap?,
    private val simpleText: Array<String?>?,
    private val charset: Charset?,
    private val cidText: ((Int) -> String?)?,
    private val widths: (code: Long, cid: Int) -> Double,
    /** Glyph space to text space: 1/1000 for all but Type 3 fonts. */
    val scale: Double,
    val vertical: Boolean
) {
    class Glyph(val code: Long, val text: String, val width: Double, val isSpace: Boolean)

    private val symbolFont = Regex("(?i)wingdings|webdings|symbol|dingbat").containsMatchIn(baseName)

    /** Symbol fonts' private-use code points (U+F020-F0FF, how Word writes their characters) as the characters they show. */
    private fun unPrivate(t: String): String {
        if (!symbolFont || t.none { it in '\uF020'..'\uF0FF' }) return t
        val wing = !baseName.contains("Symbol", ignoreCase = true)
        return t.map { ch ->
            if (ch !in '\uF020'..'\uF0FF') ch.toString()
            else { val c = ch.code - 0xF000; if (wing) WINGDINGS[c] ?: "•" else PdfEncodings.SYMBOL[c] ?: "•" }
        }.joinToString("")
    }

    /** The glyphs of a shown string; [width] in text space units (before font size). */
    fun glyphs(bytes: ByteArray): List<Glyph> {
        val out = ArrayList<Glyph>(bytes.size)
        if (!cid) {
            for (b in bytes) {
                val c = (b.toInt() and 0xFF).toLong()
                val t = toUnicode?.text(c)?.takeIf { usable(it) } ?: simpleText?.getOrNull(c.toInt()) ?: ""
                out += Glyph(c, unPrivate(t), widths(c, c.toInt()) * scale, c == 32L)
            }
            return out
        }
        val cmap = codeMap ?: PdfCMap.identity()
        cmap.codes(bytes, 2) { code, len ->
            val cidValue = cmap.cid(code) ?: code.toInt()
            val t = toUnicode?.text(code)?.takeIf { usable(it) }
                ?: charset?.let { cs -> decodeCode(code, len, cs) }
                ?: cidText?.invoke(cidValue)
                ?: ""
            out += Glyph(code, unPrivate(t), widths(code, cidValue) * scale, len == 1 && code == 32L)
        }
        return out
    }

    /** A ToUnicode entry that maps to control characters (NUL, mostly) is a writer's mistake. */
    private fun usable(t: String) = t.isEmpty() || t.any { it.code >= 0x20 || it == '\t' }

    private fun decodeCode(code: Long, len: Int, cs: Charset): String? {
        val b = ByteArray(len) { ((code shr (8 * (len - 1 - it))) and 0xFF).toByte() }
        val s = try { String(b, cs) } catch (e: Exception) { return null }
        return s.takeIf { it.isNotEmpty() && '�' !in it }
    }

    companion object {
        /** Wingdings' bullets, boxes, checks and arrows (the ones lists use). */
        private val WINGDINGS: Map<Int, String> = mapOf(
            0x6C to "●", 0x6D to "❍", 0x6E to "■", 0x6F to "□", 0x70 to "◻", 0x71 to "❑", 0x72 to "❒", 0x73 to "⬧", 0x74 to "⧫",
            0x75 to "◆", 0x76 to "❖", 0x77 to "⬥", 0x9F to "•", 0xA0 to "▪", 0xA1 to "○", 0xA2 to "⭕", 0xA4 to "◉", 0xA7 to "▪",
            0xA8 to "◻", 0xD8 to "➢", 0xD9 to "⮙", 0xE0 to "➔", 0xE8 to "➔", 0xF0 to "⇨", 0xFB to "✗", 0xFC to "✓", 0xFD to "☒", 0xFE to "☑",
            0x4A to "☺", 0x4B to "😐", 0x4C to "☹", 0x46 to "☞", 0x43 to "👍", 0x21 to "✏", 0x22 to "✂"
        )

        /** Standard 14 font names to the families documents expect. */
        private val STANDARD = mapOf(
            // LibreOffice's metric-compatible stand-ins, back to the fonts they stand for.
            "Carlito" to "Calibri", "Caladea" to "Cambria", "LiberationSans" to "Arial", "LiberationSerif" to "Times New Roman",
            "LiberationMono" to "Courier New", "LiberationSansNarrow" to "Arial Narrow",
            "Helvetica" to "Arial", "Arial" to "Arial", "Times" to "Times New Roman", "TimesNewRoman" to "Times New Roman",
            "Courier" to "Courier New", "CourierNew" to "Courier New", "Symbol" to "Symbol", "ZapfDingbats" to "Wingdings"
        )

        private val CLONES = listOf(
            listOf("nimbusrom", "nimbusroman", "texgyretermes", "freeserif", "tinos", "timesroman", "times") to "Times New Roman",
            listOf("nimbussan", "texgyreheros", "freesans", "arimo", "helvetica") to "Arial",
            listOf("nimbusmon", "texgyrecursor", "freemono", "cousine", "courier") to "Courier New",
            listOf("cmss", "lmsans", "sfss") to "Arial",
            listOf("cmtt", "lmmono", "sftt") to "Courier New",
            listOf("cmr", "cmbx", "cmti", "cmsl", "cmcsc", "lmroman", "sfrm", "sfbx", "sfti") to "Times New Roman"
        )

        /** Families whose names don't split at their capitals ("DejaVuSansMono"), longest first. */
        private val NAMED = listOf(
            "dejavusansmono" to "DejaVu Sans Mono", "dejavusanscondensed" to "DejaVu Sans Condensed", "dejavusans" to "DejaVu Sans",
            "dejavuserifcondensed" to "DejaVu Serif Condensed", "dejavuserif" to "DejaVu Serif",
            // TeX's Libertine and Biolinum ("LinLibertineT", "LinBiolinumTB"), as LibreOffice ships them.
            "linlibertine" to "Linux Libertine G", "linuxlibertine" to "Linux Libertine G", "linbiolinum" to "Linux Biolinum G", "linuxbiolinum" to "Linux Biolinum G",
            "notosanscjkjp" to "Noto Sans CJK JP", "notosanscjksc" to "Noto Sans CJK SC", "notosanscjktc" to "Noto Sans CJK TC",
            "notoserifcjkjp" to "Noto Serif CJK JP", "notoserifcjksc" to "Noto Serif CJK SC", "notoserifcjktc" to "Noto Serif CJK TC",
            "sourcecodepro" to "Source Code Pro", "sourcesanspro" to "Source Sans Pro", "sourceserifpro" to "Source Serif Pro",
            "jetbrainsmono" to "JetBrains Mono", "sfmono" to "SF Mono", "sfprotext" to "SF Pro Text", "sfprodisplay" to "SF Pro Display"
        )

        /**
         * Families most readers have: Windows' and Office's, and Korean ones (Hangul's, or with a Hangul
         * name). Text in another font is set in a standard one fitted to its widths, which is closer
         * than whatever a reader falls back on.
         */
        fun installed(family: String) = family.lowercase() in INSTALLED || family.any { it in '\uAC00'..'\uD7A3' } || family.startsWith("HY") ||
            family.startsWith("Wingdings") || family.startsWith("Noto Sans CJK") || family.startsWith("Noto Serif CJK")

        private val INSTALLED = setOf(
            "arial", "arial black", "arial narrow", "times new roman", "courier new", "calibri", "calibri light", "cambria", "cambria math",
            "candara", "consolas", "constantia", "corbel", "georgia", "verdana", "tahoma", "trebuchet ms", "segoe ui", "segoe ui symbol",
            "comic sans ms", "impact", "palatino linotype", "book antiqua", "garamond", "century gothic", "century", "lucida console",
            "lucida sans unicode", "symbol", "webdings", "aptos", "bahnschrift", "franklin gothic medium", "gill sans mt", "ms gothic", "ms mincho",
            "malgun gothic", "gulim", "dotum", "batang", "gungsuh", "simsun", "simhei", "microsoft yahei", "meiryo", "yu gothic"
        )

        /** A font's number in its file ("TT153F42o00" from Windows' printer driver, "F4", "C2_0"): no family at all. */
        private val NUMBERED = Regex("TT[0-9A-F]+o\\d+|[A-Z]{1,2}\\d{1,4}|C\\d+_\\d+")

        /** PostScript names of Korean fonts and the names Word and Hangul know them by. */
        private val KOREAN = mapOf(
            "malgungothic" to "맑은 고딕", "malgun" to "맑은 고딕", "batang" to "바탕", "batangche" to "바탕체", "gulim" to "굴림",
            "gulimche" to "굴림체", "dotum" to "돋움", "dotumche" to "돋움체", "gungsuh" to "궁서", "hcrbatang" to "함초롬바탕",
            "hcrdotum" to "함초롬돋움", "hamchorombatang" to "함초롬바탕", "hamchoromdotum" to "함초롬돋움", "nanumgothic" to "나눔고딕",
            "nanummyeongjo" to "나눔명조", "nanumbarungothic" to "나눔바른고딕", "nanumsquare" to "나눔스퀘어", "applesdgothicneo" to "Apple SD Gothic Neo",
            "notosanscjkkr" to "Noto Sans CJK KR", "notosanskr" to "Noto Sans KR", "notoserifcjkkr" to "Noto Serif CJK KR",
            "hygothic" to "HY중고딕", "hygothicmedium" to "HY중고딕", "hysmyeongjo" to "HY신명조", "hysmyeongjomedium" to "HY신명조",
            "맑은고딕" to "맑은 고딕", "hyheadline" to "HY헤드라인M",
            // Hancom's own PostScript names.
            "h2hdrm" to "HY헤드라인M", "h2gtrm" to "HY중고딕", "h2gtre" to "HY견고딕", "h2mjre" to "HY견명조", "hygraphic" to "HY그래픽", "pretendard" to "Pretendard", "spoqahansansneo" to "Spoqa Han Sans Neo"
        )

        private val STYLE_WORDS = Regex("(?i)[-,_ ]?(bold|black|heavy|semibold|demibold|extrabold|ultrabold|medium|light|thin|regular|italic|oblique|roman|book|normal|condensed|narrow|mt|ps|psmt|it|bd|bdit|regu|medi|ital|reguital|mediital)$")

        fun load(doc: PdfDocument, fontObj: PdfObj?, depth: Int = 0): PdfFont? {
            val d = doc.dict(fontObj) ?: return null
            val subtype = d.name("Subtype")
            val rawName = (doc.resolve(d["BaseFont"]) as? PdfName)?.name ?: (doc.resolve(d["Name"]) as? PdfName)?.name ?: ""
            val baseName = decodeName(rawName).substringAfter('+')
            val toUnicode = (doc.resolve(d["ToUnicode"]) as? PdfStream)?.let { s -> doc.streamBytes(s)?.let { PdfCMap.parse(it) } }?.takeIf { it.hasUnicode }
            return if (subtype == "Type0") type0(doc, d, baseName, toUnicode, depth) else simple(doc, d, subtype, baseName, toUnicode)
        }

        /** A font name's bytes (names are bytes; Korean ones UTF-8 or CP949). */
        private fun decodeName(raw: String): String {
            if (raw.all { it.code < 0x80 }) return raw
            val bytes = ByteArray(raw.length) { raw[it].code.toByte() }
            val utf8 = Charsets.UTF_8.newDecoder()
            return try { utf8.decode(ByteBuffer.wrap(bytes)).toString() } catch (e: Exception) {
                korean()?.let { String(bytes, it) } ?: raw
            }
        }

        fun korean(): Charset? = listOf("x-windows-949", "MS949", "windows-949", "EUC-KR").firstNotNullOfOrNull {
            try { Charset.forName(it) } catch (e: Exception) { null }
        }

        private fun charsetFor(cmapName: String): Charset? {
            fun cs(vararg names: String) = names.firstNotNullOfOrNull { try { Charset.forName(it) } catch (e: Exception) { null } }
            return when {
                cmapName.contains("UCS2") || cmapName.contains("UTF16") -> Charsets.UTF_16BE
                cmapName.contains("UTF8") -> Charsets.UTF_8
                cmapName.startsWith("KSC") -> korean()
                cmapName.startsWith("GBK") || cmapName.startsWith("GB-EUC") || cmapName.startsWith("GBpc") -> cs("GBK", "GB2312")
                cmapName.contains("RKSJ") -> cs("windows-31j", "Shift_JIS")
                cmapName.startsWith("B5") || cmapName.startsWith("ETen") || cmapName.startsWith("HKscs") -> cs("Big5-HKSCS", "Big5")
                cmapName.startsWith("EUC") -> cs("EUC-JP")
                else -> null
            }
        }

        private fun type0(doc: PdfDocument, d: PdfDict, baseName: String, toUnicode: PdfCMap?, depth: Int): PdfFont {
            val desc = doc.dict(doc.array(d["DescendantFonts"])?.get(0))
            val enc = doc.resolve(d["Encoding"])
            var charset: Charset? = null
            var vertical = false
            val cmap: PdfCMap = when (enc) {
                is PdfName -> {
                    vertical = enc.name.endsWith("-V")
                    charset = charsetFor(enc.name)
                    PdfCMap.predefined(enc.name) ?: PdfCMap.identity()
                }
                is PdfStream -> {
                    val name = enc.dict.name("CMapName").orEmpty()
                    vertical = (enc.dict.int("WMode") ?: 0) == 1
                    val base = (doc.resolve(enc.dict["UseCMap"]) as? PdfName)?.name
                    charset = charsetFor(base ?: name)
                    doc.streamBytes(enc)?.let { data ->
                        PdfCMap.parse(data, resolveUse = { PdfCMap.predefined(it) }).takeIf { it.hasCodespace }
                    } ?: PdfCMap.identity()
                }
                else -> PdfCMap.identity()
            }
            // Widths: W ([c [w...]] or [c1 c2 w]) and DW, by CID.
            val dw = doc.num(desc?.get("DW")) ?: 1000.0
            val w = HashMap<Int, Double>()
            doc.array(desc?.get("W"))?.items?.let { items ->
                var i = 0
                while (i < items.size && w.size < 200_000) {
                    val first = doc.num(items[i])?.toInt() ?: break
                    val next = doc.resolve(items.getOrNull(i + 1))
                    if (next is PdfArray) {
                        next.items.forEachIndexed { k, v -> doc.num(v)?.let { w[first + k] = it } }
                        i += 2
                    } else {
                        val last = doc.num(next)?.toInt() ?: break
                        val v = doc.num(items.getOrNull(i + 2)) ?: break
                        if (last - first in 0..65535) for (c in first..last) w[c] = v
                        i += 3
                    }
                }
            }
            // CIDs aren't the codes in a predefined CJK CMap, and its CIDs can't be
            // mapped here: guess half or full width from the text.
            val knownCids = enc !is PdfName || enc.name.startsWith("Identity")
            val descriptor = doc.dict(desc?.get("FontDescriptor"))
            val cidText = if (toUnicode == null && charset == null) embeddedText(doc, descriptor, desc, true) else null
            val family = familyOf(baseName)
            return PdfFont(
                baseName, family, isBold(baseName, descriptor), isItalic(baseName, descriptor), isMono(baseName, descriptor), isSerif(baseName, descriptor),
                cid = true, codeMap = cmap, toUnicode = toUnicode, simpleText = null, charset = charset, cidText = cidText,
                widths = { code, c -> if (knownCids) w[c] ?: dw else w[c] ?: if (code < 0x100) dw / 2 else dw },
                scale = 0.001, vertical = vertical
            )
        }

        private fun simple(doc: PdfDocument, d: PdfDict, subtype: String?, baseName: String, toUnicode: PdfCMap?): PdfFont {
            val descriptor = doc.dict(d["FontDescriptor"])
            val flags = doc.num(descriptor?.get("Flags"))?.toInt() ?: 0
            val symbolic = flags and 4 != 0 && flags and 32 == 0
            val enc = doc.resolve(d["Encoding"])
            val plain = baseName.replace(Regex("[-,].*"), "")
            // An embedded Type 1 font's own encoding is the base when the font names none.
            val builtin by lazy { (doc.resolve(descriptor?.get("FontFile")) as? PdfStream)?.let { ff -> doc.streamBytes(ff)?.let { type1Encoding(it) } } }
            var table: Array<String?> = when {
                plain.startsWith("Symbol") -> PdfEncodings.SYMBOL
                enc is PdfName -> PdfEncodings.named(enc.name) ?: PdfEncodings.STANDARD
                enc is PdfDict -> PdfEncodings.named(enc.name("BaseEncoding")) ?: if (subtype == "TrueType") PdfEncodings.WIN_ANSI else builtin ?: PdfEncodings.STANDARD
                subtype == "TrueType" -> PdfEncodings.WIN_ANSI
                else -> builtin ?: PdfEncodings.STANDARD
            }.copyOf()
            val differences = (enc as? PdfDict)?.let { doc.array(it["Differences"]) }
            var namedGlyphs = 0
            differences?.items?.let { items ->
                var code = 0
                for (it in items) when (val o = doc.resolve(it)) {
                    is PdfNum -> code = o.int
                    is PdfName -> { if (code in 0..255) { table[code] = PdfEncodings.glyph(o.name); if (table[code] != null) namedGlyphs++ }; code++ }
                    else -> {}
                }
            }
            // A symbolic font with no usable encoding: its embedded font's own cmap knows better.
            if (toUnicode == null && (symbolic || enc == null) && differences == null) {
                embeddedText(doc, descriptor, null, false)?.let { lookup ->
                    val t2 = arrayOfNulls<String>(256)
                    var found = 0
                    for (c in 0 until 256) { t2[c] = lookup(c); if (t2[c] != null) found++ }
                    if (found > 0) table = Array(256) { t2[it] ?: if (symbolic) null else table[it] }
                }
            }
            val first = doc.num(d["FirstChar"])?.toInt() ?: 0
            val widths = doc.array(d["Widths"])?.items?.map { doc.num(it) ?: 0.0 }
            val missing = doc.num(descriptor?.get("MissingWidth")) ?: 0.0
            val mono = isMono(baseName, descriptor)
            val type3Scale = if (subtype == "Type3") doc.array(d["FontMatrix"])?.items?.getOrNull(0)?.let { doc.num(it) } ?: 0.001 else 0.001
            // A Type 3 font's name is only a name for its glyph procedures ("T3", "F15"):
            // the document's own font stands in.
            val family = if (subtype == "Type3") "" else familyOf(baseName)
            val bold = isBold(baseName, descriptor); val italic = isItalic(baseName, descriptor)
            val glyphText = table
            return PdfFont(
                baseName, family, bold, italic, mono, isSerif(baseName, descriptor),
                cid = false, codeMap = null, toUnicode = toUnicode, simpleText = table, charset = null, cidText = null,
                widths = { code, _ ->
                    val k = code.toInt() - first
                    widths?.getOrNull(k)?.takeIf { it > 0 } ?: missing.takeIf { it > 0 } ?: if (mono) 600.0 else
                        // A standard font named without its widths: the standard ones.
                        (if (widths == null) StandardWidths.width(family, bold, italic, glyphText.getOrNull(code.toInt())) else null)
                            ?: if (code == 32L) 250.0 else 500.0
                },
                scale = if (subtype == "Type3") type3Scale else 0.001,
                vertical = false
            )
        }

        /** A Type 1 font program's built-in encoding ("dup 65 /A put"), unless it is StandardEncoding. */
        private fun type1Encoding(data: ByteArray): Array<String?>? {
            val head = String(data, 0, minOf(data.size, 64 * 1024), Charsets.ISO_8859_1)
            val at = head.indexOf("/Encoding")
            if (at < 0 || head.startsWith("StandardEncoding", at + 10)) return null
            val end = head.indexOf("readonly def", at).let { if (it < 0) head.indexOf("eexec", at) else it }.let { if (it < 0) head.length else it }
            val table = arrayOfNulls<String>(256)
            var found = 0
            for (m in Regex("dup\\s+(\\d+)\\s*/([^\\s/\\[\\]()<>{}]+)\\s+put").findAll(head.substring(at, end))) {
                val code = m.groupValues[1].toInt()
                if (code in 0..255) { table[code] = PdfEncodings.glyph(m.groupValues[2]); found++ }
            }
            return if (found > 0) table else null
        }

        /**
         * Text by CID (or simple code) from the embedded TrueType or OpenType font:
         * its cmap inverted, through CIDToGIDMap, else its glyph names (post).
         */
        private fun embeddedText(doc: PdfDocument, descriptor: PdfDict?, cidFont: PdfDict?, isCid: Boolean): ((Int) -> String?)? {
            // TrueType, or OpenType (CFF outlines in the same table structure).
            val ff = doc.resolve(descriptor?.get("FontFile2")) as? PdfStream
                ?: (doc.resolve(descriptor?.get("FontFile3")) as? PdfStream)?.takeIf { it.dict.name("Subtype") == "OpenType" }
                ?: return null
            val data = doc.streamBytes(ff) ?: return null
            val tt = try { TrueTypeTables.read(data) } catch (e: Exception) { null } ?: return null
            if (!isCid) {
                // A symbolic simple font: codes are looked up in its (3,0) cmap at 0xF000 + code, or (1,0).
                return { code -> tt.codeToText(code) }
            }
            val gidMap = doc.resolve(cidFont?.get("CIDToGIDMap"))
            val gids = (gidMap as? PdfStream)?.let { doc.streamBytes(it) }
            val byGid = tt.gidToText()
            if (byGid.isEmpty()) return null
            return { cidValue ->
                val gid = if (gids != null) {
                    if (cidValue * 2 + 1 < gids.size) ((gids[cidValue * 2].toInt() and 0xFF) shl 8) or (gids[cidValue * 2 + 1].toInt() and 0xFF) else 0
                } else cidValue
                byGid[gid]
            }
        }

        private fun styleWords(name: String) = name.lowercase()

        private fun isBold(name: String, fd: PdfDict?): Boolean {
            val n = styleWords(name)
            // TeX's names: "cmbx10", "LinLibertineTB".
            if (Regex("bold|black|heavy|semibold|demibold|extrabold|ultrabold|,bd|-bd|^cmbx|^lin(libertine|biolinum)[a-z]*b").containsMatchIn(n)) return true
            val weight = fd?.num("FontWeight") ?: 0.0
            val flags = fd?.int("Flags") ?: 0
            return weight >= 600 || flags and (1 shl 18) != 0
        }

        private fun isItalic(name: String, fd: PdfDict?): Boolean {
            val n = styleWords(name)
            if (Regex("italic|oblique|-it$|,it$|bdit").containsMatchIn(n)) return true
            val flags = fd?.int("Flags") ?: 0
            return flags and 64 != 0 || (fd?.num("ItalicAngle") ?: 0.0).let { it < -5 }
        }

        private fun isMono(name: String, fd: PdfDict?) = (fd?.int("Flags") ?: 0) and 1 != 0 || Regex("(?i)courier|mono|consolas|code|coding|menlo|monaco|inconsolata|cascadia").containsMatchIn(name)

        private fun isSerif(name: String, fd: PdfDict?) = (fd?.int("Flags") ?: 0) and 2 != 0 ||
            Regex("(?i)times|serif|georgia|garamond|cambria|batang|myeongjo|mincho|song|minion|palatino|bookman|century").containsMatchIn(name) &&
            !Regex("(?i)sans").containsMatchIn(name)

        /** The family name a document would use for [baseName]. */
        fun familyOf(baseName: String): String {
            // A CMap's name after the font's (Hangul's PDF export: "맑은고딕-WinCharSetFFFF-H").
            var n = baseName.substringAfter('+').replace(Regex("-(WinCharSet\\w*|Identity|Uni[A-Z]\\w*|KSC\\w*)(-[HV])?$"), "")
            if (NUMBERED.matches(n)) return ""
            repeat(3) { n = n.replace(STYLE_WORDS, "") }
            n = n.substringBefore(',').trim('-', ' ', '_')
            STANDARD[n]?.let { return it }
            val key = n.lowercase().replace(Regex("[^a-z0-9가-힣]"), "")
            KOREAN[key]?.let { return it }
            KOREAN.entries.firstOrNull { key.startsWith(it.key) }?.let { return it.value }
            // "ArialMT", "TimesNewRomanPSMT", "Calibri", "CambriaMath".
            // Metric clones (URW, TeX Gyre, GNU Free, Croscore) under the names
            // documents expect, and TeX's Computer and Latin Modern as the nearest of those.
            for ((prefixes, family) in CLONES) if (prefixes.any { key.startsWith(it) }) return family
            NAMED.firstOrNull { key.startsWith(it.first) }?.let { return it.second }
            return when {
                key.startsWith("arial") -> "Arial"
                key.startsWith("timesnewroman") -> "Times New Roman"
                key.startsWith("couriernew") -> "Courier New"
                key.startsWith("calibri") -> "Calibri"
                key.startsWith("cambriamath") -> "Cambria Math"
                key.startsWith("cambria") -> "Cambria"
                key.startsWith("segoeui") -> "Segoe UI"
                key.startsWith("verdana") -> "Verdana"
                key.startsWith("tahoma") -> "Tahoma"
                key.startsWith("georgia") -> "Georgia"
                key.startsWith("consolas") -> "Consolas"
                key.startsWith("symbolmt") -> "Symbol"
                n.isEmpty() -> "Arial"
                else -> n.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            }
        }
    }
}

/** The tables of a TrueType font that map glyphs to text: cmap and post. */
internal class TrueTypeTables private constructor(private val cmaps: List<Pair<Int, Map<Int, Int>>>, private val names: List<String?>) {

    /** Glyph id to text: from the Unicode cmaps, then glyph names. */
    fun gidToText(): Map<Int, String> = byGid

    private val byGid: Map<Int, String> by lazy {
        val out = HashMap<Int, String>()
        for ((kind, map) in cmaps) {
            if (kind != UNICODE) continue
            for ((cp, gid) in map) if (gid != 0 && cp >= 0x20) out.putIfAbsent(gid, String(Character.toChars(cp)))
        }
        names.forEachIndexed { gid, n -> if (n != null && gid !in out) PdfEncodings.glyph(n)?.let { out[gid] = it } }
        out
    }

    /** A symbolic font's code as text: its (3,0) cmap's glyph at 0xF000 + code mapped back through the Unicode one or its name. */
    fun codeToText(code: Int): String? {
        val byGid = gidToText()
        for ((kind, map) in cmaps) {
            val gid = when (kind) {
                SYMBOL -> map[0xF000 + code] ?: map[code]
                MAC -> map[code]
                else -> null
            } ?: continue
            return byGid[gid]
        }
        return null
    }

    companion object {
        private const val UNICODE = 0
        private const val SYMBOL = 1
        private const val MAC = 2

        fun read(data: ByteArray): TrueTypeTables? {
            val b = ByteBuffer.wrap(data)
            if (data.size < 12) return null
            val numTables = b.getShort(4).toInt() and 0xFFFF
            if (numTables > 200) return null
            val tables = HashMap<String, Pair<Int, Int>>()
            for (i in 0 until numTables) {
                val at = 12 + i * 16
                if (at + 16 > data.size) break
                val tag = String(data, at, 4, Charsets.ISO_8859_1)
                tables[tag] = b.getInt(at + 8) to b.getInt(at + 12)
            }
            val cmaps = ArrayList<Pair<Int, Map<Int, Int>>>()
            tables["cmap"]?.let { (off, len) ->
                if (off < 0 || off + 4 > data.size) return@let
                val n = b.getShort(off + 2).toInt() and 0xFFFF
                for (i in 0 until minOf(n, 64)) {
                    val rec = off + 4 + i * 8
                    if (rec + 8 > data.size) break
                    val platform = b.getShort(rec).toInt() and 0xFFFF
                    val encoding = b.getShort(rec + 2).toInt() and 0xFFFF
                    val sub = off + b.getInt(rec + 4)
                    val kind = when {
                        platform == 0 || (platform == 3 && (encoding == 1 || encoding == 10)) -> UNICODE
                        platform == 3 && encoding == 0 -> SYMBOL
                        platform == 1 && encoding == 0 -> MAC
                        else -> continue
                    }
                    subtable(b, sub, data.size)?.let { cmaps += kind to it }
                }
            }
            val names = ArrayList<String?>()
            tables["post"]?.let { (off, _) -> try { postNames(b, off, data.size, names) } catch (e: Exception) { names.clear() } }
            return TrueTypeTables(cmaps, names)
        }

        private fun subtable(b: ByteBuffer, at: Int, size: Int): Map<Int, Int>? {
            if (at < 0 || at + 6 > size) return null
            val out = HashMap<Int, Int>()
            when (b.getShort(at).toInt() and 0xFFFF) {
                0 -> for (c in 0 until 256) if (at + 6 + c < size) out[c] = b.get(at + 6 + c).toInt() and 0xFF
                4 -> {
                    val segX2 = b.getShort(at + 6).toInt() and 0xFFFF
                    val ends = at + 14; val starts = ends + segX2 + 2; val deltas = starts + segX2; val ranges = deltas + segX2
                    if (ranges + segX2 > size) return null
                    for (s in 0 until segX2 / 2) {
                        val end = b.getShort(ends + s * 2).toInt() and 0xFFFF
                        val start = b.getShort(starts + s * 2).toInt() and 0xFFFF
                        val delta = b.getShort(deltas + s * 2).toInt()
                        val rangeOff = b.getShort(ranges + s * 2).toInt() and 0xFFFF
                        if (start > end || end - start > 20000) continue
                        for (c in start..end) {
                            if (c == 0xFFFF) break
                            val gid = if (rangeOff == 0) (c + delta) and 0xFFFF else {
                                val p = ranges + s * 2 + rangeOff + (c - start) * 2
                                if (p + 2 > size) 0 else (b.getShort(p).toInt() and 0xFFFF).let { if (it == 0) 0 else (it + delta) and 0xFFFF }
                            }
                            if (gid != 0) out[c] = gid
                        }
                        if (out.size > 100_000) break
                    }
                }
                6 -> {
                    val first = b.getShort(at + 6).toInt() and 0xFFFF
                    val count = b.getShort(at + 8).toInt() and 0xFFFF
                    for (i in 0 until count) { val p = at + 10 + i * 2; if (p + 2 > size) break; out[first + i] = b.getShort(p).toInt() and 0xFFFF }
                }
                12 -> {
                    val groups = b.getInt(at + 12)
                    for (g in 0 until minOf(groups, 100_000)) {
                        val p = at + 16 + g * 12
                        if (p + 12 > size) break
                        val s = b.getInt(p); val e = b.getInt(p + 4); val gid = b.getInt(p + 8)
                        if (e < s || e - s > 100_000) continue
                        for (c in s..e) out[c] = gid + (c - s)
                        if (out.size > 200_000) break
                    }
                }
                else -> return null
            }
            return out
        }

        private fun postNames(b: ByteBuffer, off: Int, size: Int, out: MutableList<String?>) {
            if (off + 34 > size) return
            if (b.getInt(off) != 0x00020000) return
            val n = b.getShort(off + 32).toInt() and 0xFFFF
            val idx = IntArray(n) { b.getShort(off + 34 + it * 2).toInt() and 0xFFFF }
            var p = off + 34 + n * 2
            val custom = ArrayList<String>()
            while (p < size && custom.size < 65536) {
                val len = b.get(p).toInt() and 0xFF
                if (p + 1 + len > size) break
                val bytes = ByteArray(len); for (k in 0 until len) bytes[k] = b.get(p + 1 + k)
                custom += String(bytes, Charsets.ISO_8859_1)
                p += 1 + len
            }
            for (i in idx) out += if (i < 258) MAC_GLYPHS.getOrNull(i) else custom.getOrNull(i - 258)
        }

        /** The 258 standard Macintosh glyph names (the first ones; the rest are rare). */
        private val MAC_GLYPHS: List<String> = (".notdef .null nonmarkingreturn space exclam quotedbl numbersign dollar percent ampersand quotesingle parenleft parenright asterisk plus comma hyphen period slash " +
            "zero one two three four five six seven eight nine colon semicolon less equal greater question at A B C D E F G H I J K L M N O P Q R S T U V W X Y Z " +
            "bracketleft backslash bracketright asciicircum underscore grave a b c d e f g h i j k l m n o p q r s t u v w x y z braceleft bar braceright asciitilde").split(' ')
    }
}
