package io.github.baeseungwon1010.officecanvas.convert

import java.io.OutputStream
import kotlin.math.roundToLong

/**
 * Writes a [ConvDocument] as a Hangul document (.hwpx, OWPML), page by page:
 * paragraphs with character and paragraph properties collected into the header,
 * tables, pictures in BinData (written as their page is), the header and footer
 * with a page number, and one section per page size. Line layout isn't saved (no
 * <hp:linesegarray>): Hangul lays the text out when it opens the file.
 */
internal class HwpxWriter(out: OutputStream, private val defaultFont: String) : PageSink {

    private val zip = PlainZip(out)
    private val sections = ArrayList<StringBuilder>()
    private var body = StringBuilder()
    private var section: ConvPage? = null
    private var pageIndex = 0
    private var images = 0
    private val imageItems = StringBuilder()
    private var objectId = 1000
    private var header: List<ConvParagraph>? = null
    private var footer: List<ConvParagraph>? = null
    private var headerTop = 0.0
    private var footerBottom = 0.0

    // Properties, numbered as they are first used.
    private val fonts = LinkedHashMap<String, Int>()
    private val charPrs = LinkedHashMap<String, Int>()
    private val paraPrs = LinkedHashMap<String, Int>()
    private val tabPrs = LinkedHashMap<String, Int>()
    private val borderFills = LinkedHashMap<String, Int>()

    init {
        stored("mimetype", "application/hwp+zip".toByteArray(), compress = false)
        font(defaultFont)
        // borderFill 1: none (pages); 2: none (characters, paragraphs).
        borderFill(null, null, null, null, null)
        borderFill(null, null, null, null, null, key = "plain2")
        tabPr(emptyList())
        charPr(ConvRun("", defaultFont, 10.0))
        paraPr(ConvParagraph(emptyList()), false)
    }

    override fun page(page: ConvPage) {
        if (header == null && page.header.isNotEmpty()) { header = page.header; headerTop = page.headerTop }
        if (footer == null && page.footer.isNotEmpty()) { footer = page.footer; footerBottom = page.footerBottom }
        val prev = section
        val newSection = prev == null || !sameSetup(prev, page)
        if (newSection) {
            if (prev != null) sections += body
            body = StringBuilder()
            section = page
        }
        val breakPage = !newSection
        var first = true
        val blocks = page.blocks.ifEmpty { listOf(ConvParagraph(emptyList())) }
        for (b in blocks) {
            val lead = if (first) leadControls(page, newSection) + page.floating.joinToString("") { picture(it, floating = true) } else ""
            when (b) {
                is ConvParagraph -> paragraph(b, pageBreak = first && breakPage, lead = lead)
                is ConvTable -> {
                    if (lead.isNotEmpty() || (first && breakPage)) paragraph(ConvParagraph(emptyList()), pageBreak = first && breakPage, lead = lead)
                    tableParagraph(b)
                }
            }
            first = false
        }
        pageIndex++
    }

    /** What a section's first paragraph carries: the section's page setup, and the header and footer controls. */
    private fun leadControls(page: ConvPage, newSection: Boolean): String {
        if (!newSection) return ""
        val sb = StringBuilder()
        sb.append(secPr(page))
        sb.append("<hp:ctrl><hp:colPr id=\"\" type=\"NEWSPAPER\" layout=\"LEFT\" colCount=\"1\" sameSz=\"1\" sameGap=\"0\"/></hp:ctrl>")
        sb.append(HEADER_CTRL)
        return sb.toString()
    }

    private fun secPr(p: ConvPage): String {
        // A sideways page keeps its upright size, marked NARROWLY.
        val landscape = p.width > p.height
        val w = hu(if (landscape) p.height else p.width); val h = hu(if (landscape) p.width else p.height)
        return "<hp:secPr id=\"\" textDirection=\"HORIZONTAL\" spaceColumns=\"1134\" tabStop=\"8000\" tabStopVal=\"4000\" tabStopUnit=\"HWPUNIT\" outlineShapeIDRef=\"1\" memoShapeIDRef=\"0\" textVerticalWidthHead=\"0\" masterPageCnt=\"0\">" +
            "<hp:grid lineGrid=\"0\" charGrid=\"0\" wonggojiFormat=\"0\"/><hp:startNum pageStartsOn=\"BOTH\" page=\"0\" pic=\"0\" tbl=\"0\" equation=\"0\"/>" +
            "<hp:visibility hideFirstHeader=\"0\" hideFirstFooter=\"0\" hideFirstMasterPage=\"0\" border=\"SHOW_ALL\" fill=\"SHOW_ALL\" hideFirstPageNum=\"0\" hideFirstEmptyLine=\"0\" showLineNumber=\"0\"/>" +
            "<hp:lineNumberShape restartType=\"0\" countBy=\"0\" distance=\"0\" startNumber=\"0\"/>" +
            "<hp:pagePr landscape=\"${if (landscape) "NARROWLY" else "WIDELY"}\" width=\"$w\" height=\"$h\" gutterType=\"LEFT_ONLY\">" +
            "<hp:margin header=\"$HEADER_DISTANCE\" footer=\"$FOOTER_DISTANCE\" gutter=\"0\" left=\"${hu(p.marginLeft)}\" right=\"${hu(p.marginRight)}\" top=\"${hu(p.marginTop)}\" bottom=\"${hu(p.marginBottom)}\"/></hp:pagePr>" +
            "<hp:footNotePr><hp:autoNumFormat type=\"DIGIT\" userChar=\"\" prefixChar=\"\" suffixChar=\")\" supscript=\"0\"/><hp:noteLine length=\"-1\" type=\"SOLID\" width=\"0.12 mm\" color=\"#000000\"/><hp:noteSpacing betweenNotes=\"283\" belowLine=\"567\" aboveLine=\"850\"/><hp:numbering type=\"CONTINUOUS\" newNum=\"1\"/><hp:placement place=\"EACH_COLUMN\" beneathText=\"0\"/></hp:footNotePr>" +
            "<hp:endNotePr><hp:autoNumFormat type=\"DIGIT\" userChar=\"\" prefixChar=\"\" suffixChar=\")\" supscript=\"0\"/><hp:noteLine length=\"14692344\" type=\"SOLID\" width=\"0.12 mm\" color=\"#000000\"/><hp:noteSpacing betweenNotes=\"0\" belowLine=\"567\" aboveLine=\"850\"/><hp:numbering type=\"CONTINUOUS\" newNum=\"1\"/><hp:placement place=\"END_OF_DOCUMENT\" beneathText=\"0\"/></hp:endNotePr>" +
            listOf("BOTH", "EVEN", "ODD").joinToString("") { t -> "<hp:pageBorderFill type=\"$t\" borderFillIDRef=\"1\" textBorder=\"PAPER\" headerInside=\"0\" footerInside=\"0\" fillArea=\"PAPER\"><hp:offset left=\"1417\" right=\"1417\" top=\"1417\" bottom=\"1417\"/></hp:pageBorderFill>" } +
            "</hp:secPr>"
    }

    private fun sameSetup(a: ConvPage, b: ConvPage) = Math.abs(a.width - b.width) < 1 && Math.abs(a.height - b.height) < 1 &&
        Math.abs(a.marginLeft - b.marginLeft) < 1 && Math.abs(a.marginTop - b.marginTop) < 1 && Math.abs(a.marginRight - b.marginRight) < 1

    // ---- paragraphs ----

    private fun paragraph(p: ConvParagraph, pageBreak: Boolean = false, lead: String = "", into: StringBuilder = body) {
        val pp = paraPr(p, pageBreak)
        val firstChar = charPr(p.runs.firstOrNull() ?: ConvRun("", defaultFont, (p.runs.firstOrNull()?.size ?: 10.0)))
        into.append("<hp:p id=\"0\" paraPrIDRef=\"$pp\" styleIDRef=\"0\" pageBreak=\"${if (pageBreak) 1 else 0}\" columnBreak=\"0\" merged=\"0\">")
        if (lead.isNotEmpty()) into.append("<hp:run charPrIDRef=\"$firstChar\">").append(lead).append("</hp:run>")
        p.image?.let { into.append("<hp:run charPrIDRef=\"$firstChar\">").append(picture(it, floating = false)).append("</hp:run>") }
        if (p.runs.isEmpty() && p.image == null && lead.isEmpty()) into.append("<hp:run charPrIDRef=\"$firstChar\"/>")
        for (r in p.runs) {
            if (r.text.isEmpty()) continue
            into.append("<hp:run charPrIDRef=\"${charPr(r)}\">")
            if (r.field == "PAGE") {
                into.append("<hp:ctrl><hp:autoNum num=\"1\" numType=\"PAGE\"><hp:autoNumFormat type=\"DIGIT\" userChar=\"\" prefixChar=\"\" suffixChar=\"\" supscript=\"0\"/></hp:autoNum></hp:ctrl>")
            } else {
                into.append("<hp:t>")
                val parts = r.text.split('\t')
                for ((i, part) in parts.withIndex()) {
                    if (i > 0) into.append("<hp:tab width=\"4000\" leader=\"0\" type=\"1\"/>")
                    into.append(DocxWriter.esc(part))
                }
                into.append("</hp:t>")
            }
            into.append("</hp:run>")
        }
        into.append("</hp:p>")
    }

    private fun paraPr(p: ConvParagraph, pageBreak: Boolean): Int {
        val align = when (p.align) { ConvAlign.CENTER -> "CENTER"; ConvAlign.RIGHT -> "RIGHT"; ConvAlign.JUSTIFY -> "JUSTIFY"; ConvAlign.LEFT -> "LEFT" }
        val size = p.runs.maxOfOrNull { it.size } ?: 10.0
        // Hangul's line spacing is a percent of the text size.
        // The PDF's line pitch, fixed (a one-line paragraph without one: about single spacing, not Hangul's default 160 %).
        val spacing = p.lineHeight?.let { "FIXED:" + hu(it.coerceIn(size * 0.8, size * 6)) } ?: "PERCENT:130"
        val left = hu(p.leftIndent); val right = hu(p.rightIndent); val indent = hu(p.firstLine); val prev = hu(p.spaceBefore)
        val tab = tabPr(p.tabs, p.rightTabs)
        val fill = p.shading?.let { borderFill(null, null, null, null, it) } ?: 2
        val key = "$align|$left|$right|$indent|$prev|$spacing|$tab|$fill"
        return paraPrs.getOrPut(key) { paraPrs.size }
    }

    /** Stops as "pos" (left) or "pos:R" (right), in order. */
    private fun tabPr(stops: List<Double>, right: List<Double> = emptyList()): Int =
        tabPrs.getOrPut((stops.map { hu(it) to "" } + right.map { hu(it) to ":R" }).sortedBy { it.first }.joinToString(",") { "${it.first}${it.second}" }) { tabPrs.size }

    private fun charPr(r: ConvRun): Int {
        val f = font(r.font)
        val key = "$f|${(r.size * 100).roundToLong()}|${r.bold}|${r.italic}|${r.underline}|${r.color and 0xFFFFFF}|${r.script}"
        return charPrs.getOrPut(key) { charPrs.size }
    }

    private fun font(name: String): Int = fonts.getOrPut(name.ifBlank { defaultFont }) { fonts.size }

    private fun borderFill(top: ConvBorder?, bottom: ConvBorder?, left: ConvBorder?, right: ConvBorder?, fill: Int?, key: String? = null): Int {
        fun b(x: ConvBorder?) = x?.let { "${Math.round(it.width * 100)}:${it.color and 0xFFFFFF}" } ?: "-"
        val k = key ?: "${b(top)}|${b(bottom)}|${b(left)}|${b(right)}|${fill?.and(0xFFFFFF) ?: "-"}"
        return borderFills.getOrPut(k) { borderFills.size + 1 }
    }

    // ---- tables ----

    private fun tableParagraph(t: ConvTable) {
        val pp = paraPr(ConvParagraph(emptyList(), spaceBefore = t.spaceBefore), false)
        body.append("<hp:p id=\"0\" paraPrIDRef=\"$pp\" styleIDRef=\"0\" pageBreak=\"0\" columnBreak=\"0\" merged=\"0\"><hp:run charPrIDRef=\"0\">")
        table(t, body)
        body.append("</hp:run></hp:p>")
    }

    /** A table in a cell starts no further left than the cell's text (LibreOffice's reader fails on one before it). */
    private fun inCell(t: ConvTable) = if (t.x >= 0) t else ConvTable(0.0, t.colWidths, t.rowHeights, t.cells, t.spaceBefore)

    private fun table(t: ConvTable, into: StringBuilder) {
        val w = t.colWidths.sumOf { hu(it) }; val h = t.rowHeights.sumOf { hu(it) }
        val id = objectId++
        into.append("<hp:tbl id=\"$id\" zOrder=\"0\" numberingType=\"TABLE\" textWrap=\"TOP_AND_BOTTOM\" textFlow=\"BOTH_SIDES\" lock=\"0\" dropcapstyle=\"None\" pageBreak=\"CELL\" repeatHeader=\"0\" rowCnt=\"${t.rowHeights.size}\" colCnt=\"${t.colWidths.size}\" cellSpacing=\"0\" borderFillIDRef=\"1\" noAdjust=\"0\">")
        into.append("<hp:sz width=\"$w\" widthRelTo=\"ABSOLUTE\" height=\"$h\" heightRelTo=\"ABSOLUTE\" protect=\"0\"/>")
        into.append("<hp:pos treatAsChar=\"0\" affectLSpacing=\"0\" flowWithText=\"1\" allowOverlap=\"0\" holdAnchorAndSO=\"0\" vertRelTo=\"PARA\" horzRelTo=\"COLUMN\" vertAlign=\"TOP\" horzAlign=\"LEFT\" vertOffset=\"0\" horzOffset=\"${hu(t.x).coerceAtLeast(-200000)}\"/>")
        into.append("<hp:outMargin left=\"0\" right=\"0\" top=\"0\" bottom=\"0\"/><hp:inMargin left=\"141\" right=\"141\" top=\"0\" bottom=\"0\"/>")
        val byRow = t.cells.groupBy { it.row }
        for (r in t.rowHeights.indices) {
            into.append("<hp:tr>")
            for (c in byRow[r].orEmpty().sortedBy { it.col }) {
                val cw = t.colWidths.subList(c.col, minOf(c.col + c.colSpan, t.colWidths.size)).sumOf { hu(it) }
                val ch = t.rowHeights.subList(c.row, minOf(c.row + c.rowSpan, t.rowHeights.size)).sumOf { hu(it) }
                val bf = borderFill(c.top, c.bottom, c.left, c.right, c.fill)
                val v = when (c.vAlign) { 1 -> "CENTER"; 2 -> "BOTTOM"; else -> "TOP" }
                into.append("<hp:tc name=\"\" header=\"0\" hasMargin=\"1\" protect=\"0\" editable=\"0\" dirty=\"0\" borderFillIDRef=\"$bf\">")
                into.append("<hp:subList id=\"\" textDirection=\"HORIZONTAL\" lineWrap=\"BREAK\" vertAlign=\"$v\" linkListIDRef=\"0\" linkListNextIDRef=\"0\" textWidth=\"0\" textHeight=\"0\" hasTextRef=\"0\" hasNumRef=\"0\">")
                // An empty cell's paragraph takes no height of its own (the row has its height).
                if (c.blocks.isEmpty()) paragraph(ConvParagraph(emptyList(), lineHeight = 1.0), into = into)
                for (b in c.blocks) when (b) {
                    is ConvParagraph -> paragraph(b, into = into)
                    is ConvTable -> {
                        into.append("<hp:p id=\"0\" paraPrIDRef=\"0\" styleIDRef=\"0\" pageBreak=\"0\" columnBreak=\"0\" merged=\"0\"><hp:run charPrIDRef=\"0\">")
                        table(inCell(b), into)
                        into.append("</hp:run></hp:p>")
                    }
                }
                into.append("</hp:subList><hp:cellAddr colAddr=\"${c.col}\" rowAddr=\"${c.row}\"/><hp:cellSpan colSpan=\"${c.colSpan}\" rowSpan=\"${c.rowSpan}\"/>")
                into.append("<hp:cellSz width=\"$cw\" height=\"$ch\"/><hp:cellMargin left=\"141\" right=\"141\" top=\"0\" bottom=\"0\"/></hp:tc>")
            }
            into.append("</hp:tr>")
        }
        into.append("</hp:tbl>")
    }

    // ---- pictures ----

    private fun picture(img: ConvImage, floating: Boolean): String {
        images++
        val ext = if (img.png) "png" else "jpg"
        val name = "image$images"
        stored("BinData/$name.$ext", img.data)
        imageItems.append("<opf:item id=\"$name\" href=\"BinData/$name.$ext\" media-type=\"image/${if (img.png) "png" else "jpg"}\" isEmbeded=\"1\"/>")
        val w = hu(img.width); val h = hu(img.height)
        val id = objectId++
        val pos = if (floating)
            "<hp:pos treatAsChar=\"0\" affectLSpacing=\"0\" flowWithText=\"0\" allowOverlap=\"1\" holdAnchorAndSO=\"0\" vertRelTo=\"PAPER\" horzRelTo=\"PAPER\" vertAlign=\"TOP\" horzAlign=\"LEFT\" vertOffset=\"${hu(img.y)}\" horzOffset=\"${hu(img.x)}\"/>"
        else "<hp:pos treatAsChar=\"1\" affectLSpacing=\"0\" flowWithText=\"1\" allowOverlap=\"0\" holdAnchorAndSO=\"0\" vertRelTo=\"PARA\" horzRelTo=\"COLUMN\" vertAlign=\"TOP\" horzAlign=\"LEFT\" vertOffset=\"0\" horzOffset=\"0\"/>"
        return "<hp:pic id=\"$id\" zOrder=\"${if (floating) 0 else id}\" numberingType=\"PICTURE\" textWrap=\"${if (floating) "BEHIND_TEXT" else "TOP_AND_BOTTOM"}\" textFlow=\"BOTH_SIDES\" lock=\"0\" dropcapstyle=\"None\" href=\"\" groupLevel=\"0\" instid=\"$id\" reverse=\"0\">" +
            "<hp:offset x=\"0\" y=\"0\"/><hp:orgSz width=\"$w\" height=\"$h\"/><hp:curSz width=\"$w\" height=\"$h\"/><hp:flip horizontal=\"0\" vertical=\"0\"/>" +
            "<hp:rotationInfo angle=\"0\" centerX=\"${w / 2}\" centerY=\"${h / 2}\" rotateimage=\"1\"/>" +
            "<hp:renderingInfo><hc:transMatrix e1=\"1\" e2=\"0\" e3=\"0\" e4=\"0\" e5=\"1\" e6=\"0\"/><hc:scaMatrix e1=\"1\" e2=\"0\" e3=\"0\" e4=\"0\" e5=\"1\" e6=\"0\"/><hc:rotMatrix e1=\"1\" e2=\"0\" e3=\"0\" e4=\"0\" e5=\"1\" e6=\"0\"/></hp:renderingInfo>" +
            "<hc:img binaryItemIDRef=\"$name\" bright=\"0\" contrast=\"0\" effect=\"REAL_PIC\" alpha=\"0\"/>" +
            "<hp:imgRect><hc:pt0 x=\"0\" y=\"0\"/><hc:pt1 x=\"$w\" y=\"0\"/><hc:pt2 x=\"$w\" y=\"$h\"/><hc:pt3 x=\"0\" y=\"$h\"/></hp:imgRect>" +
            "<hp:imgClip left=\"0\" right=\"$w\" top=\"0\" bottom=\"$h\"/><hp:inMargin left=\"0\" right=\"0\" top=\"0\" bottom=\"0\"/><hp:imgDim dimwidth=\"$w\" dimheight=\"$h\"/><hp:effects/>" +
            "<hp:sz width=\"$w\" widthRelTo=\"ABSOLUTE\" height=\"$h\" heightRelTo=\"ABSOLUTE\" protect=\"0\"/>$pos<hp:outMargin left=\"0\" right=\"0\" top=\"0\" bottom=\"0\"/></hp:pic>"
    }

    // ---- package ----

    override fun finish() {
        section?.let { sections += body }
        // Header and footer controls, once known, in each section's first paragraph.
        val decor = StringBuilder()
        val bodyWidth = section?.let { hu(it.width - it.marginLeft - it.marginRight) } ?: 42520
        header?.let { paras -> decor.append(headerFooter("header", paras, bodyWidth, hu(maxOf(section?.marginTop?.minus(headerTop) ?: 0.0, 10.0)))) }
        footer?.let { paras -> decor.append(headerFooter("footer", paras, bodyWidth, hu(maxOf(section?.marginBottom?.minus(footerBottom) ?: 0.0, 10.0)))) }
        val headerDistance = hu(headerTop).toString(); val footerDistance = hu(footerBottom).toString()
        text("version.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes" ?><hv:HCFVersion xmlns:hv="http://www.hancom.co.kr/hwpml/2011/version" tagetApplication="WORDPROCESSOR" major="5" minor="1" micro="1" buildNumber="0" os="1" xmlVersion="1.5" application="Hancom Office Hangul" appVersion="13, 0, 0, 3901 WIN32LEWindows_10"/>""")
        for ((i, s) in sections.withIndex()) {
            val xml = s.toString().replace(HEADER_CTRL, decor.toString()).replace(HEADER_DISTANCE, headerDistance).replace(FOOTER_DISTANCE, footerDistance)
            text("Contents/section$i.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes" ?><hs:sec $NS>$xml</hs:sec>""")
        }
        text("Contents/header.xml", headerXml(sections.size))
        val sectionItems = sections.indices.joinToString("") { "<opf:item id=\"section$it\" href=\"Contents/section$it.xml\" media-type=\"application/xml\"/>" }
        val spine = sections.indices.joinToString("") { "<opf:itemref idref=\"section$it\" linear=\"yes\"/>" }
        text("Contents/content.hpf", """<?xml version="1.0" encoding="UTF-8" standalone="yes" ?><opf:package $NS version="" unique-identifier="" id=""><opf:metadata><opf:title/><opf:language>ko</opf:language></opf:metadata><opf:manifest><opf:item id="header" href="Contents/header.xml" media-type="application/xml"/>$imageItems$sectionItems<opf:item id="settings" href="settings.xml" media-type="application/xml"/></opf:manifest><opf:spine><opf:itemref idref="header" linear="yes"/>$spine</opf:spine></opf:package>""")
        text("settings.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes" ?><ha:HWPApplicationSetting xmlns:ha="http://www.hancom.co.kr/hwpml/2011/app" xmlns:config="urn:oasis:names:tc:opendocument:xmlns:config:1.0"><ha:CaretPosition listIDRef="0" paraIDRef="0" pos="0"/></ha:HWPApplicationSetting>""")
        text("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes" ?><ocf:container xmlns:ocf="urn:oasis:names:tc:opendocument:xmlns:container" xmlns:hpf="http://www.hancom.co.kr/schema/2011/hpf"><ocf:rootfiles><ocf:rootfile full-path="Contents/content.hpf" media-type="application/hwpml-package+xml"/></ocf:rootfiles></ocf:container>""")
        text("META-INF/manifest.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes" ?><odf:manifest xmlns:odf="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0"/>""")
        zip.finish()
    }

    private fun headerFooter(kind: String, paras: List<ConvParagraph>, width: Long, height: Long): String {
        val sb = StringBuilder()
        for (p in paras) paragraph(ConvParagraph(p.runs, p.align, p.leftIndent, p.rightIndent, p.firstLine, 0.0, p.lineHeight, null, p.tabs, rightTabs = p.rightTabs), into = sb)
        return "<hp:ctrl><hp:$kind id=\"${objectId++}\" applyPageType=\"BOTH\"><hp:subList id=\"\" textDirection=\"HORIZONTAL\" lineWrap=\"BREAK\" vertAlign=\"${if (kind == "header") "TOP" else "BOTTOM"}\" linkListIDRef=\"0\" linkListNextIDRef=\"0\" textWidth=\"$width\" textHeight=\"$height\" hasTextRef=\"0\" hasNumRef=\"0\">$sb</hp:subList></hp:$kind></hp:ctrl>"
    }

    private fun headerXml(secCnt: Int): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes" ?><hh:head $NS version="1.5" secCnt="$secCnt"><hh:beginNum page="1" footnote="1" endnote="1" pic="1" tbl="1" equation="1"/><hh:refList>""")
        val langs = listOf("HANGUL", "LATIN", "HANJA", "JAPANESE", "OTHER", "SYMBOL", "USER")
        sb.append("<hh:fontfaces itemCnt=\"${langs.size}\">")
        for (lang in langs) {
            sb.append("<hh:fontface lang=\"$lang\" fontCnt=\"${fonts.size}\">")
            for ((name, id) in fonts) sb.append("<hh:font id=\"$id\" face=\"${DocxWriter.esc(name)}\" type=\"TTF\" isEmbedded=\"0\"><hh:typeInfo familyType=\"FCAT_GOTHIC\" weight=\"6\" proportion=\"0\" contrast=\"0\" strokeVariation=\"1\" armStyle=\"1\" letterform=\"1\" midline=\"1\" xHeight=\"1\"/></hh:font>")
            sb.append("</hh:fontface>")
        }
        sb.append("</hh:fontfaces>")
        sb.append("<hh:borderFills itemCnt=\"${borderFills.size}\">")
        for ((key, id) in borderFills) {
            val parts = key.split('|')
            fun side(tag: String, v: String?): String {
                if (v == null || v == "-" || key.startsWith("plain")) return "<hh:$tag type=\"NONE\" width=\"0.1 mm\" color=\"#000000\"/>"
                val (w, c) = v.split(':')
                return "<hh:$tag type=\"SOLID\" width=\"${mm(w.toDouble() / 100)} mm\" color=\"#${String.format("%06X", c.toInt())}\"/>"
            }
            sb.append("<hh:borderFill id=\"$id\" threeD=\"0\" shadow=\"0\" centerLine=\"NONE\" breakCellSeparateLine=\"0\"><hh:slash type=\"NONE\" Crooked=\"0\" isCounter=\"0\"/><hh:backSlash type=\"NONE\" Crooked=\"0\" isCounter=\"0\"/>")
            sb.append(side("leftBorder", parts.getOrNull(2))).append(side("rightBorder", parts.getOrNull(3))).append(side("topBorder", parts.getOrNull(0))).append(side("bottomBorder", parts.getOrNull(1)))
            sb.append("<hh:diagonal type=\"SOLID\" width=\"0.1 mm\" color=\"#000000\"/>")
            val fill = parts.getOrNull(4)?.takeIf { it != "-" && !key.startsWith("plain") }?.toIntOrNull()
            if (fill != null) sb.append("<hc:fillBrush><hc:winBrush faceColor=\"#${String.format("%06X", fill)}\" hatchColor=\"#999999\" alpha=\"0\"/></hc:fillBrush>")
            sb.append("</hh:borderFill>")
        }
        sb.append("</hh:borderFills>")
        sb.append("<hh:charProperties itemCnt=\"${charPrs.size}\">")
        for ((key, id) in charPrs) {
            val (f, height, bold, italic, under, color, script) = key.split('|')
            val all = { v: String -> "hangul=\"$v\" latin=\"$v\" hanja=\"$v\" japanese=\"$v\" other=\"$v\" symbol=\"$v\" user=\"$v\"" }
            sb.append("<hh:charPr id=\"$id\" height=\"$height\" textColor=\"#${String.format("%06X", color.toInt())}\" shadeColor=\"none\" useFontSpace=\"0\" useKerning=\"0\" symMark=\"NONE\" borderFillIDRef=\"2\">")
            sb.append("<hh:fontRef ${all(f)}/><hh:ratio ${all("100")}/><hh:spacing ${all("0")}/><hh:relSz ${all("100")}/><hh:offset ${all("0")}/>")
            if (italic == "true") sb.append("<hh:italic/>")
            if (bold == "true") sb.append("<hh:bold/>")
            sb.append(if (under == "true") "<hh:underline type=\"BOTTOM\" shape=\"SOLID\" color=\"#${String.format("%06X", color.toInt())}\"/>" else "<hh:underline type=\"NONE\" shape=\"SOLID\" color=\"#000000\"/>")
            sb.append("<hh:strikeout shape=\"NONE\" color=\"#000000\"/><hh:outline type=\"NONE\"/><hh:shadow type=\"NONE\" color=\"#C0C0C0\" offsetX=\"10\" offsetY=\"10\"/>")
            when (script) { "1" -> sb.append("<hh:supscript/>"); "-1" -> sb.append("<hh:subscript/>") }
            sb.append("</hh:charPr>")
        }
        sb.append("</hh:charProperties>")
        sb.append("<hh:tabProperties itemCnt=\"${tabPrs.size}\">")
        for ((key, id) in tabPrs) {
            if (key.isEmpty()) { sb.append("<hh:tabPr id=\"$id\" autoTabLeft=\"0\" autoTabRight=\"0\"/>"); continue }
            sb.append("<hh:tabPr id=\"$id\" autoTabLeft=\"0\" autoTabRight=\"0\">")
            for (stop in key.split(',')) sb.append("<hh:tabItem pos=\"${stop.removeSuffix(":R")}\" type=\"${if (stop.endsWith(":R")) "RIGHT" else "LEFT"}\" leader=\"NONE\"/>")
            sb.append("</hh:tabPr>")
        }
        sb.append("</hh:tabProperties><hh:numberings itemCnt=\"0\"/>")
        sb.append("<hh:paraProperties itemCnt=\"${paraPrs.size}\">")
        for ((key, id) in paraPrs) {
            val (align, left, right, indent, prev, spacing, tab) = key.split('|')
            val fill = key.split('|')[7]
            val (spacingType, spacingValue) = spacing.split(':')
            fun margin(scale: Long) = "<hh:margin><hc:intent value=\"${indent.toLong() * scale}\" unit=\"HWPUNIT\"/><hc:left value=\"${left.toLong() * scale}\" unit=\"HWPUNIT\"/><hc:right value=\"${right.toLong() * scale}\" unit=\"HWPUNIT\"/><hc:prev value=\"${prev.toLong() * scale}\" unit=\"HWPUNIT\"/><hc:next value=\"0\" unit=\"HWPUNIT\"/></hh:margin>" +
                "<hh:lineSpacing type=\"$spacingType\" value=\"${if (spacingType == "FIXED") spacingValue.toLong() * scale else spacingValue.toLong()}\" unit=\"HWPUNIT\"/>"
            sb.append("<hh:paraPr id=\"$id\" tabPrIDRef=\"$tab\" condense=\"0\" fontLineHeight=\"0\" snapToGrid=\"1\" suppressLineNumbers=\"0\" checked=\"0\" textDir=\"AUTO\">")
            sb.append("<hh:align horizontal=\"$align\" vertical=\"BASELINE\"/><hh:heading type=\"NONE\" idRef=\"0\" level=\"0\"/>")
            sb.append("<hh:breakSetting breakLatinWord=\"KEEP_WORD\" breakNonLatinWord=\"KEEP_WORD\" widowOrphan=\"0\" keepWithNext=\"0\" keepLines=\"0\" pageBreakBefore=\"0\" lineWrap=\"BREAK\"/><hh:autoSpacing eAsianEng=\"0\" eAsianNum=\"0\"/>")
            // Hangul writes margins in HWPUNIT for itself and doubled for older readers.
            sb.append("<hp:switch><hp:case hp:required-namespace=\"http://www.hancom.co.kr/hwpml/2016/HwpUnitChar\">${margin(1)}</hp:case><hp:default>${margin(2)}</hp:default></hp:switch>")
            sb.append("<hh:border borderFillIDRef=\"$fill\" offsetLeft=\"0\" offsetRight=\"0\" offsetTop=\"0\" offsetBottom=\"0\" connect=\"0\" ignoreMargin=\"0\"/></hh:paraPr>")
        }
        sb.append("</hh:paraProperties>")
        sb.append("<hh:styles itemCnt=\"1\"><hh:style id=\"0\" type=\"PARA\" name=\"바탕글\" engName=\"Normal\" paraPrIDRef=\"0\" charPrIDRef=\"0\" nextStyleIDRef=\"0\" langID=\"1042\" lockForm=\"0\"/></hh:styles>")
        sb.append("</hh:refList><hh:compatibleDocument targetProgram=\"HWP201X\"><hh:layoutCompatibility/></hh:compatibleDocument><hh:docOption><hh:linkinfo path=\"\" pageInherit=\"0\" footnoteInherit=\"0\"/></hh:docOption><hh:trackchageConfig flags=\"56\"/></hh:head>")
        return sb.toString()
    }

    private operator fun <T> List<T>.component6() = this[5]
    private operator fun <T> List<T>.component7() = this[6]

    private fun text(name: String, xml: String) = stored(name, xml.toByteArray(Charsets.UTF_8))

    /** A zip entry ("mimetype" is the first, uncompressed, as the package format asks). */
    private fun stored(name: String, data: ByteArray, compress: Boolean = true) = zip.add(name, data, compress)

    companion object {
        private const val HEADER_CTRL = "\u0000DECOR\u0000"
        private const val HEADER_DISTANCE = "\u0000HDR\u0000"
        private const val FOOTER_DISTANCE = "\u0000FTR\u0000"
        private const val NS = "xmlns:ha=\"http://www.hancom.co.kr/hwpml/2011/app\" xmlns:hp=\"http://www.hancom.co.kr/hwpml/2011/paragraph\" " +
            "xmlns:hp10=\"http://www.hancom.co.kr/hwpml/2016/paragraph\" xmlns:hs=\"http://www.hancom.co.kr/hwpml/2011/section\" " +
            "xmlns:hc=\"http://www.hancom.co.kr/hwpml/2011/core\" xmlns:hh=\"http://www.hancom.co.kr/hwpml/2011/head\" " +
            "xmlns:hhs=\"http://www.hancom.co.kr/hwpml/2011/history\" xmlns:hm=\"http://www.hancom.co.kr/hwpml/2011/master-page\" " +
            "xmlns:hpf=\"http://www.hancom.co.kr/schema/2011/hpf\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:opf=\"http://www.idpf.org/2007/opf/\" " +
            "xmlns:ooxmlchart=\"http://www.hancom.co.kr/hwpml/2016/ooxmlchart\" xmlns:hwpunitchar=\"http://www.hancom.co.kr/hwpml/2016/HwpUnitChar\" " +
            "xmlns:epub=\"http://www.idpf.org/2007/ops\" xmlns:config=\"urn:oasis:names:tc:opendocument:xmlns:config:1.0\""

        /** Points as HWPUNIT (1/7200 inch). */
        fun hu(pt: Double): Long = (pt * 100).roundToLong().coerceIn(-1_000_000, 10_000_000)

        /** A border width in points as Hangul's millimetre steps. */
        private fun mm(pt: Double): String {
            val steps = listOf(0.1, 0.12, 0.15, 0.2, 0.25, 0.3, 0.4, 0.5, 0.6, 0.7, 1.0, 1.5, 2.0, 3.0, 4.0, 5.0)
            val want = pt * 25.4 / 72
            return steps.minByOrNull { Math.abs(it - want) }!!.toString().removeSuffix(".0").let { if ('.' in it) it else "$it.0" }
        }
    }
}
