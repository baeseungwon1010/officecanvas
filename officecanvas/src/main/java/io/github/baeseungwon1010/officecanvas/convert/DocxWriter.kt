package io.github.baeseungwon1010.officecanvas.convert

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToLong

/**
 * Writes a [ConvDocument] as a Word document, page by page: pictures go into the
 * package as their page is written, so a long document's pictures are never all
 * in memory. Each PDF page starts a new page; consecutive pages of one size are
 * one section with that page size and margins.
 */
internal class DocxWriter(out: OutputStream, private val defaultFont: String) : PageSink {

    private val zip = ZipOutputStream(out)
    private val body = StringBuilder()
    private val rels = StringBuilder()
    private var images = 0
    private var drawingId = 1
    private var pageIndex = 0
    private var section: ConvPage? = null
    private val types = HashSet<String>()
    /** Fonts the runs use: fixed pitch, serif. */
    private val fontKinds = LinkedHashMap<String, Pair<Boolean, Boolean>>()
    /** The header and footer (from the first page with them), and their distances from the page's edges. */
    private var header: List<ConvParagraph>? = null
    private var footer: List<ConvParagraph>? = null
    private var headerTop = 0.0
    private var footerBottom = 0.0

    override fun page(page: ConvPage) {
        val prev = section
        // A page of another size (or margins) ends the section before it.
        if (prev != null && !sameSetup(prev, page)) {
            body.append("<w:p><w:pPr>").append(sectPr(prev)).append("</w:pPr></w:p>")
        }
        section = page
        if (header == null && page.header.isNotEmpty()) { header = page.header; headerTop = page.headerTop }
        if (footer == null && page.footer.isNotEmpty()) { footer = page.footer; footerBottom = page.footerBottom }
        val first = pageIndex == 0
        pageIndex++
        var broke = first || prev != null && !sameSetup(prev, page)
        // Floating pictures anchor in the page's first paragraph.
        var anchors = page.floating.joinToString("") { anchor(it) }
        // Space before a page's first paragraph is dropped at the top of a page (LibreOffice drops it;
        // a picture floating above would have the text run over it), so a paragraph that tall holds it.
        val head = page.blocks.firstOrNull()
        val lead = (head as? ConvParagraph)?.spaceBefore?.takeIf { it > 2 && !page.fixed }
        if (head !is ConvParagraph || lead != null || page.fixed) {
            body.append("<w:p><w:pPr>")
            if (!broke) body.append("<w:pageBreakBefore/>")
            body.append("<w:spacing w:before=\"0\" w:after=\"0\" w:line=\"${tw((lead ?: 1.0).coerceIn(1.0, 1500.0))}\" w:lineRule=\"exact\"/><w:rPr><w:sz w:val=\"2\"/></w:rPr></w:pPr>")
            body.append(anchors).append("</w:p>")
            anchors = ""
            broke = true
        }
        if (page.fixed) {
            // Each block where it lies on the page: paragraphs in frames, then tables floating. Frames hang
            // on the paragraph after them, so one follows them on this page, before any table could push it on.
            // A picture standing alone floats there instead (in a frame, one as tall as the page ran over).
            val alone = StringBuilder()
            for (b in page.blocks) if (b is ConvParagraph) {
                val at = b.at; val img = b.image
                if (at != null && img != null && b.runs.isEmpty()) {
                    val x = if (b.align == ConvAlign.CENTER) at[0] + (at[2] - at[0] - img.width) / 2 else at[0]
                    alone.append(anchor(ConvImage(img.data, img.png, img.pixelWidth, img.pixelHeight, img.width, img.height, x, at[1])))
                } else paragraph(if (at != null) b.withSpaceBefore(0.0) else b, frame = at)
            }
            body.append("<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/><w:rPr><w:sz w:val=\"2\"/></w:rPr></w:pPr>").append(alone).append("</w:p>")
            for (b in page.blocks) if (b is ConvTable) { table(b, b.at); spacer(1.0) }
            return
        }
        for ((k, b) in page.blocks.withIndex()) {
            when (b) {
                is ConvParagraph -> { paragraph(if (k == 0 && lead != null) b.withSpaceBefore(0.0) else b, pageBreak = !broke, extra = anchors); anchors = ""; broke = true }
                is ConvTable -> { spacer(b.spaceBefore); table(b) }
            }
        }
    }

    /** A paragraph as tall as the space before a table (one is needed anyway: Word joins tables with nothing between them). */
    private fun spacer(height: Double) {
        body.append("<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"${tw(height.coerceIn(1.0, 200.0))}\" w:lineRule=\"exact\"/><w:rPr><w:sz w:val=\"2\"/></w:rPr></w:pPr></w:p>")
    }

    override fun finish() {
        section?.let { body.append(sectPr(it)) }
        // Section properties name the header and footer once they're known.
        var refs = ""
        header?.let { refs += "<w:headerReference w:type=\"default\" r:id=\"rIdHeader\"/>"; part("word/header1.xml", "hdr", it); rels.append("<Relationship Id=\"rIdHeader\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/header\" Target=\"header1.xml\"/>") }
        footer?.let { refs += "<w:footerReference w:type=\"default\" r:id=\"rIdFooter\"/>"; part("word/footer1.xml", "ftr", it); rels.append("<Relationship Id=\"rIdFooter\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer\" Target=\"footer1.xml\"/>") }
        val text = body.toString().replace(SECT_REFS, refs)
            .replace(HEADER_DISTANCE, tw(headerTop).toString()).replace(FOOTER_DISTANCE, tw(footerBottom).toString())
        put("word/document.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document $NS><w:body>$text</w:body></w:document>""")
        put("word/styles.xml", styles())
        // Each font's family and pitch: a reader without the font substitutes one like it (a fixed-pitch one for code).
        put("word/fontTable.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:fonts xmlns:w=\"$W\">" +
            fontKinds.entries.joinToString("") { (name, kind) ->
                "<w:font w:name=\"${esc(name)}\"><w:family w:val=\"${if (kind.first) "modern" else if (kind.second) "roman" else "swiss"}\"/><w:pitch w:val=\"${if (kind.first) "fixed" else "variable"}\"/></w:font>"
            } + "</w:fonts>")
        put("word/settings.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:settings xmlns:w="$W"><w:defaultTabStop w:val="720"/><w:compat><w:compatSetting w:name="compatibilityMode" w:uri="http://schemas.microsoft.com/office/word" w:val="15"/></w:compat></w:settings>""")
        put("word/_rels/document.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/><Relationship Id="rIdSettings" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/><Relationship Id="rIdFonts" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/fontTable" Target="fontTable.xml"/>$rels</Relationships>""")
        put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""")
        val overrides = (if (header != null) "<Override PartName=\"/word/header1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml\"/>" else "") +
            (if (footer != null) "<Override PartName=\"/word/footer1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/>" else "")
        val defaults = types.joinToString("") { ext -> """<Default Extension="$ext" ContentType="image/${if (ext == "jpg") "jpeg" else ext}"/>""" }
        put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>$defaults<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/><Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/><Override PartName="/word/fontTable.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.fontTable+xml"/>$overrides</Types>""")
        zip.finish()
        zip.flush()
    }

    /** A header or footer part: its paragraphs written as the body's are. */
    private fun part(name: String, root: String, paras: List<ConvParagraph>) {
        val saved = body.toString()
        body.setLength(0)
        for (p in paras) paragraph(p)
        val xml = body.toString()
        body.setLength(0); body.append(saved)
        put(name, """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:$root $NS>$xml</w:$root>""")
    }

    private fun put(name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun sameSetup(a: ConvPage, b: ConvPage) = Math.abs(a.width - b.width) < 1 && Math.abs(a.height - b.height) < 1 &&
        Math.abs(a.marginLeft - b.marginLeft) < 1 && Math.abs(a.marginTop - b.marginTop) < 1 && Math.abs(a.marginRight - b.marginRight) < 1

    private fun sectPr(p: ConvPage): String {
        val orient = if (p.width > p.height) " w:orient=\"landscape\"" else ""
        return "<w:sectPr>$SECT_REFS<w:pgSz w:w=\"${tw(p.width)}\" w:h=\"${tw(p.height)}\"$orient/>" +
            "<w:pgMar w:top=\"${tw(p.marginTop)}\" w:right=\"${tw(p.marginRight)}\" w:bottom=\"${tw(p.marginBottom)}\" w:left=\"${tw(p.marginLeft)}\" w:header=\"$HEADER_DISTANCE\" w:footer=\"$FOOTER_DISTANCE\" w:gutter=\"0\"/></w:sectPr>"
    }

    // ---- paragraphs ----

    private fun paragraph(p: ConvParagraph, pageBreak: Boolean = false, extra: String = "", frame: DoubleArray? = null) {
        body.append("<w:p><w:pPr>")
        if (pageBreak) body.append("<w:pageBreakBefore/>")
        // A frame at a place on the page, as tall as its text needs.
        frame?.let { f ->
            body.append("<w:framePr w:w=\"${tw((f[2] - f[0]).coerceAtLeast(2.0))}\" w:h=\"${tw((f[3] - f[1]).coerceAtLeast(1.0))}\" w:hRule=\"atLeast\" ")
            body.append("w:x=\"${tw(f[0].coerceAtLeast(0.0))}\" w:y=\"${tw(f[1].coerceAtLeast(0.0))}\" w:hAnchor=\"page\" w:vAnchor=\"page\" w:wrap=\"around\"/>")
        }
        if (p.tabs.isNotEmpty() || p.rightTabs.isNotEmpty()) {
            body.append("<w:tabs>")
            for ((t, right) in (p.tabs.map { it to false } + p.rightTabs.map { it to true }).sortedBy { it.first }.take(30))
                body.append("<w:tab w:val=\"${if (right) "right" else "left"}\" w:pos=\"${tw(t)}\"/>")
            body.append("</w:tabs>")
        }
        body.append("<w:spacing w:before=\"${tw(p.spaceBefore)}\" w:after=\"0\"")
        val size = p.runs.maxOfOrNull { it.size } ?: 10.0
        val font = p.runs.firstOrNull()?.font ?: defaultFont
        // The PDF's own line pitch, exactly: the fonts Word sets the text in (a
        // Korean fallback is often much taller than the PDF's) don't spread it out.
        p.lineHeight?.let { pitch ->
            body.append(" w:line=\"${tw(pitch.coerceIn(size * 0.8, size * 6))}\" w:lineRule=\"exact\"")
        } ?: body.append(" w:line=\"240\" w:lineRule=\"auto\"")
        body.append("/>")
        p.shading?.let { body.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${hex(it)}\"/>") }
        val left = tw(p.leftIndent); val first = tw(p.firstLine)
        body.append("<w:ind w:left=\"$left\" w:right=\"${tw(p.rightIndent)}\" ")
        body.append(if (first >= 0) "w:firstLine=\"$first\"/>" else "w:hanging=\"${-first}\"/>")
        when (p.align) {
            ConvAlign.CENTER -> body.append("<w:jc w:val=\"center\"/>")
            ConvAlign.RIGHT -> body.append("<w:jc w:val=\"right\"/>")
            ConvAlign.JUSTIFY -> body.append("<w:jc w:val=\"both\"/>")
            ConvAlign.LEFT -> {}
        }
        p.runs.firstOrNull()?.let { body.append("<w:rPr>").append(rPr(it)).append("</w:rPr>") }
        body.append("</w:pPr>")
        body.append(extra)
        p.image?.let { body.append("<w:r>").append(inline(it)).append("</w:r>") }
        for (r in p.runs) run(r)
        body.append("</w:p>")
    }

    private fun rPr(r: ConvRun): String {
        if (r.font.isNotBlank()) fontKinds.putIfAbsent(r.font, r.mono to r.serif)
        val f = esc(r.font.ifBlank { defaultFont })
        val sb = StringBuilder("<w:rFonts w:ascii=\"$f\" w:hAnsi=\"$f\" w:eastAsia=\"$f\" w:cs=\"$f\"/>")
        if (r.bold) sb.append("<w:b/><w:bCs/>")
        if (r.italic) sb.append("<w:i/><w:iCs/>")
        if (r.color and 0xFFFFFF != 0) sb.append("<w:color w:val=\"${hex(r.color)}\"/>")
        val hp = (r.size * 2).roundToLong().coerceIn(2, 3276)
        val spacing = (r.spacing * 20).roundToLong()
        if (spacing != 0L) sb.append("<w:spacing w:val=\"$spacing\"/>")
        if (r.scale != 100) sb.append("<w:w w:val=\"${r.scale}\"/>")
        sb.append("<w:sz w:val=\"$hp\"/><w:szCs w:val=\"$hp\"/>")
        if (r.underline) sb.append("<w:u w:val=\"single\"/>")
        if (r.script != 0) sb.append("<w:vertAlign w:val=\"${if (r.script > 0) "superscript" else "subscript"}\"/>")
        return sb.toString()
    }

    private fun run(r: ConvRun) {
        if (r.text.isEmpty()) return
        if (r.field != null) {
            val p = rPr(r)
            body.append("<w:r><w:rPr>$p</w:rPr><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:rPr>$p</w:rPr><w:instrText xml:space=\"preserve\"> ${r.field} </w:instrText></w:r>")
            body.append("<w:r><w:rPr>$p</w:rPr><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:rPr>$p</w:rPr><w:t>${esc(r.text)}</w:t></w:r><w:r><w:rPr>$p</w:rPr><w:fldChar w:fldCharType=\"end\"/></w:r>")
            return
        }
        body.append("<w:r><w:rPr>").append(rPr(r)).append("</w:rPr>")
        val parts = r.text.split('\t')
        for ((i, part) in parts.withIndex()) {
            if (i > 0) body.append("<w:tab/>")
            if (part.isNotEmpty()) body.append("<w:t xml:space=\"preserve\">").append(esc(part)).append("</w:t>")
        }
        body.append("</w:r>")
    }

    // ---- tables ----

    private fun table(t: ConvTable, at: DoubleArray? = null) {
        val total = t.colWidths.sum()
        body.append("<w:tbl><w:tblPr>")
        // Floating at a place on the page.
        at?.let { a ->
            body.append("<w:tblpPr w:leftFromText=\"0\" w:rightFromText=\"0\" w:topFromText=\"0\" w:bottomFromText=\"0\" w:vertAnchor=\"page\" w:horzAnchor=\"page\" ")
            body.append("w:tblpX=\"${tw(a[0].coerceAtLeast(0.0))}\" w:tblpY=\"${tw(a[1].coerceAtLeast(0.0))}\"/><w:tblOverlap w:val=\"overlap\"/>")
        }
        body.append("<w:tblW w:w=\"${tw(total)}\" w:type=\"dxa\"/><w:tblInd w:w=\"${tw(if (at != null) 0.0 else t.x)}\" w:type=\"dxa\"/><w:tblLayout w:type=\"fixed\"/>")
        body.append("<w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"30\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"30\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid>")
        for (w in t.colWidths) body.append("<w:gridCol w:w=\"${tw(w)}\"/>")
        body.append("</w:tblGrid>")
        val owners = HashMap<Pair<Int, Int>, ConvCell>()
        for (c in t.cells) for (r in c.row until c.row + c.rowSpan) owners[r to c.col] = c
        for (r in t.rowHeights.indices) {
            // The PDF's row height includes its rulings; Word adds the borders to it.
            val rule = t.cells.filter { it.row <= r && it.row + it.rowSpan > r }.maxOfOrNull { maxOf(it.top?.width ?: 0.0, it.bottom?.width ?: 0.0) } ?: 0.0
            val height = maxOf(t.rowHeights[r] - rule, t.rowHeights[r] * 0.8)
            body.append("<w:tr><w:trPr><w:trHeight w:val=\"${tw(height)}\" w:hRule=\"atLeast\"/></w:trPr>")
            var c = 0
            while (c < t.colWidths.size) {
                val cell = owners[r to c]
                if (cell == null) {
                    body.append("<w:tc><w:tcPr><w:tcW w:w=\"${tw(t.colWidths[c])}\" w:type=\"dxa\"/></w:tcPr>$EMPTY_CELL</w:tc>")
                    c++; continue
                }
                val width = t.colWidths.subList(c, minOf(c + cell.colSpan, t.colWidths.size)).sum()
                body.append("<w:tc><w:tcPr><w:tcW w:w=\"${tw(width)}\" w:type=\"dxa\"/>")
                if (cell.colSpan > 1) body.append("<w:gridSpan w:val=\"${cell.colSpan}\"/>")
                if (cell.rowSpan > 1) body.append(if (cell.row == r) "<w:vMerge w:val=\"restart\"/>" else "<w:vMerge/>")
                body.append("<w:tcBorders>").append(border("top", cell.top)).append(border("left", cell.left))
                    .append(border("bottom", cell.bottom)).append(border("right", cell.right)).append("</w:tcBorders>")
                cell.fill?.let { body.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"${hex(it)}\"/>") }
                when (cell.vAlign) { 1 -> body.append("<w:vAlign w:val=\"center\"/>"); 2 -> body.append("<w:vAlign w:val=\"bottom\"/>") }
                body.append("</w:tcPr>")
                if (cell.row == r && cell.blocks.isNotEmpty()) {
                    for (b in cell.blocks) when (b) {
                        is ConvParagraph -> paragraph(b)
                        is ConvTable -> table(b)
                    }
                    if (cell.blocks.last() is ConvTable) body.append("<w:p/>")
                } else body.append(EMPTY_CELL)
                body.append("</w:tc>")
                c += cell.colSpan
            }
            body.append("</w:tr>")
        }
        body.append("</w:tbl>")
    }

    private fun border(side: String, b: ConvBorder?): String =
        if (b == null) "<w:$side w:val=\"nil\"/>"
        else "<w:$side w:val=\"single\" w:sz=\"${(b.width * 8).roundToLong().coerceIn(2, 96)}\" w:space=\"0\" w:color=\"${hex(b.color)}\"/>"

    // ---- pictures ----

    private fun media(img: ConvImage): String {
        images++
        val ext = if (img.png) "png" else "jpg"
        types += ext
        val name = "media/image$images.$ext"
        zip.putNextEntry(ZipEntry("word/$name"))
        zip.write(img.data)
        zip.closeEntry()
        val id = "rIdImg$images"
        rels.append("<Relationship Id=\"$id\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"$name\"/>")
        return id
    }

    private fun graphic(img: ConvImage, id: String, n: Int) =
        "<a:graphic xmlns:a=\"$A\"><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:nvPicPr><pic:cNvPr id=\"$n\" name=\"Picture $n\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"$id\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"${emu(img.width)}\" cy=\"${emu(img.height)}\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic>"

    private fun inline(img: ConvImage): String {
        val id = media(img); val n = drawingId++
        return "<w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"${emu(img.width)}\" cy=\"${emu(img.height)}\"/><wp:docPr id=\"$n\" name=\"Picture $n\"/>" +
            "<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a=\"$A\" noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>${graphic(img, id, n)}</wp:inline></w:drawing>"
    }

    /** A picture behind the text at its place on the page. */
    private fun anchor(img: ConvImage): String {
        val id = media(img); val n = drawingId++
        return "<w:r><w:drawing><wp:anchor distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\" relativeHeight=\"$n\" behindDoc=\"1\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">" +
            "<wp:simplePos x=\"0\" y=\"0\"/><wp:positionH relativeFrom=\"page\"><wp:posOffset>${emu(img.x)}</wp:posOffset></wp:positionH>" +
            "<wp:positionV relativeFrom=\"page\"><wp:posOffset>${emu(img.y)}</wp:posOffset></wp:positionV><wp:extent cx=\"${emu(img.width)}\" cy=\"${emu(img.height)}\"/>" +
            "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:wrapNone/><wp:docPr id=\"$n\" name=\"Picture $n\"/><wp:cNvGraphicFramePr/>${graphic(img, id, n)}</wp:anchor></w:drawing></w:r>"
    }

    // ---- styles ----

    private fun styles(): String {
        val f = esc(defaultFont)
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:styles xmlns:w="$W"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii="$f" w:hAnsi="$f" w:eastAsia="$f" w:cs="$f"/><w:sz w:val="20"/><w:szCs w:val="20"/><w:lang w:val="en-US" w:eastAsia="ko-KR"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style><w:style w:type="table" w:default="1" w:styleId="TableNormal"><w:name w:val="Normal Table"/><w:tblPr><w:tblInd w:w="0" w:type="dxa"/><w:tblCellMar><w:top w:w="0" w:type="dxa"/><w:left w:w="108" w:type="dxa"/><w:bottom w:w="0" w:type="dxa"/><w:right w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr></w:style></w:styles>"""
    }

    companion object {
        /** An empty cell's paragraph, taking no height of its own (the row has its height; a default 10 pt line grew rows set smaller). */
        const val EMPTY_CELL = "<w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"20\" w:lineRule=\"exact\"/><w:rPr><w:sz w:val=\"2\"/></w:rPr></w:pPr></w:p>"
        /** Stand-ins in section properties until the header and footer are known. */
        private const val SECT_REFS = "\u0000REFS\u0000"
        private const val HEADER_DISTANCE = "\u0000HDR\u0000"
        private const val FOOTER_DISTANCE = "\u0000FTR\u0000"
        private const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        private const val A = "http://schemas.openxmlformats.org/drawingml/2006/main"
        private const val NS = "xmlns:w=\"$W\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" " +
            "xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" xmlns:a=\"$A\" " +
            "xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\""

        fun tw(pt: Double): Long = (pt * 20).roundToLong().coerceIn(-31680, 31680 * 4)
        fun emu(pt: Double): Long = (pt * 12700).roundToLong().coerceIn(0, 51206400L * 4)
        fun hex(c: Int) = String.format("%06X", c and 0xFFFFFF)
        fun esc(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s) when {
                ch == '&' -> sb.append("&amp;"); ch == '<' -> sb.append("&lt;"); ch == '>' -> sb.append("&gt;"); ch == '"' -> sb.append("&quot;")
                ch.code < 0x20 && ch != '\t' && ch != '\n' && ch != '\r' -> {}
                ch == '￾' || ch == '￿' -> {}
                else -> sb.append(ch)
            }
            return sb.toString()
        }

        /** A font's single line (ascent, descent, gap) per point of size, as Word measures it. */
        fun lineFactor(font: String): Double {
            val f = font.lowercase()
            return when {
                "맑은" in f || "malgun" in f -> 1.33
                "calibri" in f -> 1.22
                "arial" in f || "times" in f || "helvetica" in f -> 1.15
                "바탕" in f || "batang" in f || "굴림" in f || "돋움" in f -> 1.15
                "함초롬" in f -> 1.2
                "나눔" in f -> 1.15
                else -> 1.17
            }
        }
    }
}

/** Receives a rebuilt document a page at a time. */
internal interface PageSink {
    fun page(page: ConvPage)
    fun finish()
}
