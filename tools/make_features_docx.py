"""Builds features.docx: Word's parts that python-docx doesn't write, written here
as XML. Page one has a title page header (the default header is on the later
pages), page borders, a picture floating at the page's top right, a picture the
text wraps above and below, a text box in a shape (with its VML fallback), a
group of two shapes, an older VML text box, equations (a fraction, a root, a sum
with limits, a superscript) and two footnotes; an endnote follows the text. A
second, landscape section names no header, so it keeps the first's, and starts
its page numbers at 10.

Run: python3 make_features_docx.py <outdir>  (needs Pillow)"""
import io
import sys
import zipfile

from PIL import Image

out = sys.argv[1] if len(sys.argv) > 1 else "."

W = 'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"'
NS = (W + ' xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"'
      ' xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"'
      ' xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"'
      ' xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"'
      ' xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"'
      ' xmlns:wpg="http://schemas.microsoft.com/office/word/2010/wordprocessingGroup"'
      ' xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"'
      ' xmlns:v="urn:schemas-microsoft-com:vml" xmlns:o="urn:schemas-microsoft-com:office:office"'
      ' xmlns:w10="urn:schemas-microsoft-com:office:word"'
      ' xmlns:m="http://schemas.openxmlformats.org/officeDocument/2006/math"'
      ' mc:Ignorable=""')
CM = 360000  # EMU per cm


def png(color):
    img = Image.new("RGB", (120, 80), color)
    buf = io.BytesIO()
    img.save(buf, "PNG")
    return buf.getvalue()


def p(text="", ppr="", runs=None):
    body = runs if runs is not None else (f'<w:r><w:t xml:space="preserve">{text}</w:t></w:r>' if text else "")
    return f"<w:p><w:pPr>{ppr}</w:pPr>{body}</w:p>"


def pic_graphic(rid, cx, cy, n):
    return (f'<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">'
            f'<pic:pic><pic:nvPicPr><pic:cNvPr id="{n}" name="p{n}"/><pic:cNvPicPr/></pic:nvPicPr>'
            f'<pic:blipFill><a:blip r:embed="{rid}"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>'
            f'<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="{cx}" cy="{cy}"/></a:xfrm><a:prstGeom prst="rect"/></pic:spPr>'
            f'</pic:pic></a:graphicData></a:graphic>')


def anchor(inner, cx, cy, n, h, v, wrap, behind="0"):
    return (f'<w:r><w:drawing><wp:anchor distT="0" distB="0" distL="0" distR="0" simplePos="0" relativeHeight="{n}" '
            f'behindDoc="{behind}" locked="0" layoutInCell="1" allowOverlap="1">'
            f'<wp:simplePos x="0" y="0"/>{h}{v}<wp:extent cx="{cx}" cy="{cy}"/><wp:effectExtent l="0" t="0" r="0" b="0"/>'
            f'{wrap}<wp:docPr id="{n}" name="a{n}"/><wp:cNvGraphicFramePr/>{inner}</wp:anchor></w:drawing></w:r>')


def shape_xml(cx, cy, fill, text, n):
    return (f'<wps:wsp><wps:cNvSpPr txBox="1"/><wps:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="{cx}" cy="{cy}"/></a:xfrm>'
            f'<a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val="{fill}"/></a:solidFill>'
            f'<a:ln w="12700"><a:solidFill><a:srgbClr val="1F4E79"/></a:solidFill></a:ln></wps:spPr>'
            f'<wps:txbx><w:txbxContent>{p(text)}</w:txbxContent></wps:txbx>'
            f'<wps:bodyPr lIns="91440" tIns="45720" rIns="91440" bIns="45720" anchor="t"/></wps:wsp>')


def math(xml):
    return f'<m:oMath>{xml}</m:oMath>'


def mr(t):
    return f'<m:r><m:t>{t}</m:t></m:r>'


page_pic = anchor(pic_graphic("rIdP1", 3 * CM, 2 * CM, 101), 3 * CM, 2 * CM, 1,
                  '<wp:positionH relativeFrom="page"><wp:align>right</wp:align></wp:positionH>',
                  '<wp:positionV relativeFrom="page"><wp:posOffset>360000</wp:posOffset></wp:positionV>', '<wp:wrapNone/>')
wrapped_pic = anchor(pic_graphic("rIdP2", 4 * CM, 3 * CM, 102), 4 * CM, 3 * CM, 2,
                     '<wp:positionH relativeFrom="column"><wp:posOffset>0</wp:posOffset></wp:positionH>',
                     '<wp:positionV relativeFrom="paragraph"><wp:posOffset>0</wp:posOffset></wp:positionV>', '<wp:wrapTopAndBottom/>')
box = shape_xml(8 * CM, 2 * CM, "DDEBF7", "Text in a text box", 3)
textbox = ('<w:r><mc:AlternateContent><mc:Choice Requires="wps">'
           + anchor(f'<a:graphic><a:graphicData uri="http://schemas.microsoft.com/office/word/2010/wordprocessingShape">{box}</a:graphicData></a:graphic>',
                    8 * CM, 2 * CM, 3,
                    '<wp:positionH relativeFrom="column"><wp:posOffset>0</wp:posOffset></wp:positionH>',
                    '<wp:positionV relativeFrom="paragraph"><wp:posOffset>0</wp:posOffset></wp:positionV>', '<wp:wrapTopAndBottom/>')[5:-6]
           + '</mc:Choice><mc:Fallback><w:pict><v:rect style="width:226pt;height:57pt"><v:textbox><w:txbxContent>'
           + p("Fallback text") + '</w:txbxContent></v:textbox></v:rect></w:pict></mc:Fallback></mc:AlternateContent></w:r>')
group = ('<w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="%d" cy="%d"/><wp:docPr id="4" name="g"/>'
         '<a:graphic><a:graphicData uri="http://schemas.microsoft.com/office/word/2010/wordprocessingGroup"><wpg:wgp><wpg:cNvGrpSpPr/>'
         '<wpg:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="%d" cy="%d"/><a:chOff x="0" y="0"/><a:chExt cx="2000" cy="1000"/></a:xfrm></wpg:grpSpPr>'
         '<wps:wsp><wps:cNvSpPr/><wps:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="1000" cy="1000"/></a:xfrm><a:prstGeom prst="ellipse"/>'
         '<a:solidFill><a:srgbClr val="C00000"/></a:solidFill></wps:spPr><wps:bodyPr/></wps:wsp>'
         '<wps:wsp><wps:cNvSpPr/><wps:spPr><a:xfrm><a:off x="1000" y="0"/><a:ext cx="1000" cy="1000"/></a:xfrm><a:prstGeom prst="rect"/>'
         '<a:solidFill><a:srgbClr val="00B050"/></a:solidFill></wps:spPr><wps:bodyPr/></wps:wsp>'
         '</wpg:wgp></a:graphicData></a:graphic></wp:inline></w:drawing></w:r>') % (4 * CM, 2 * CM, 4 * CM, 2 * CM)
vml_box = ('<w:r><w:pict><v:shape style="position:absolute;margin-left:0;margin-top:0;width:170pt;height:40pt;'
           'mso-position-horizontal-relative:margin;mso-position-vertical-relative:text" fillcolor="#FFF2CC" strokecolor="#7F6000">'
           '<w10:wrap type="topAndBottom"/><v:textbox><w:txbxContent>' + p("Old VML text box")
           + '</w:txbxContent></v:textbox></v:shape></w:pict></w:r>')

equations = (
    math('<m:f><m:num>' + mr("a") + '</m:num><m:den>' + mr("b") + '</m:den></m:f>' + mr("+")
         + '<m:rad><m:radPr><m:degHide m:val="1"/></m:radPr><m:deg/><m:e>' + mr("x") + '</m:e></m:rad>' + mr("+")
         + '<m:sSup><m:e>' + mr("y") + '</m:e><m:sup>' + mr("2") + '</m:sup></m:sSup>'))
display = ('<m:oMathPara>' + math('<m:nary><m:naryPr><m:chr m:val="∑"/></m:naryPr><m:sub>' + mr("i=1") + '</m:sub><m:sup>'
           + mr("n") + '</m:sup><m:e>' + mr("i") + '</m:e></m:nary>' + mr("=") + '<m:f><m:num>' + mr("n(n+1)")
           + '</m:num><m:den>' + mr("2") + '</m:den></m:f>') + '</m:oMathPara>')


def fn(i):
    return f'<w:r><w:rPr><w:vertAlign w:val="superscript"/></w:rPr><w:footnoteReference w:id="{i}"/></w:r>'


body = "".join([
    p(runs=page_pic + '<w:r><w:t>Feature sample</w:t></w:r>', ppr='<w:jc w:val="left"/>'),
    p(runs='<w:r><w:t xml:space="preserve">A sentence with a footnote</w:t></w:r>' + fn(1)
      + '<w:r><w:t xml:space="preserve"> and another</w:t></w:r>' + fn(2) + '<w:r><w:t>.</w:t></w:r>'),
    p(runs=wrapped_pic),
    p("Text below the wrapped picture."),
    p(runs=textbox),
    p("Text below the text box."),
    p(runs=group),
    p(runs=vml_box),
    p("Text below the VML box."),
    p(runs='<w:r><w:t xml:space="preserve">Inline math: </w:t></w:r>' + equations),
    p(runs=display),
    p(runs='<w:r><w:t xml:space="preserve">An endnote here</w:t></w:r>'
      '<w:r><w:rPr><w:vertAlign w:val="superscript"/></w:rPr><w:endnoteReference w:id="1"/></w:r>'),
    p(ppr='<w:sectPr><w:headerReference w:type="default" r:id="rIdH1"/><w:headerReference w:type="first" r:id="rIdH2"/>'
          '<w:footerReference w:type="default" r:id="rIdF1"/><w:titlePg/>'
          '<w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708"/>'
          '<w:pgBorders w:offsetFrom="page"><w:top w:val="single" w:sz="8" w:space="24" w:color="2E75B6"/>'
          '<w:left w:val="single" w:sz="8" w:space="24" w:color="2E75B6"/><w:bottom w:val="single" w:sz="8" w:space="24" w:color="2E75B6"/>'
          '<w:right w:val="single" w:sz="8" w:space="24" w:color="2E75B6"/></w:pgBorders></w:sectPr>'),
    p("The landscape section."),
    '<w:sectPr><w:pgSz w:w="16838" w:h="11906" w:orient="landscape"/>'
    '<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" w:header="708" w:footer="708"/>'
    '<w:pgNumType w:start="10"/></w:sectPr>',
])

document = f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:document {NS}><w:body>{body}</w:body></w:document>'


def part(root, content):
    return f'<?xml version="1.0" encoding="UTF-8" standalone="yes"?><w:{root} {NS}>{content}</w:{root}>'


page_field = ('<w:r><w:t xml:space="preserve">Page </w:t></w:r><w:r><w:fldChar w:fldCharType="begin"/></w:r>'
              '<w:r><w:instrText xml:space="preserve"> PAGE </w:instrText></w:r><w:r><w:fldChar w:fldCharType="separate"/></w:r>'
              '<w:r><w:t>1</w:t></w:r><w:r><w:fldChar w:fldCharType="end"/></w:r>')


def note(kind, i, text):
    ref = "footnoteRef" if kind == "footnote" else "endnoteRef"
    return (f'<w:{kind} w:id="{i}"><w:p><w:r><w:rPr><w:vertAlign w:val="superscript"/></w:rPr><w:{ref}/></w:r>'
            f'<w:r><w:t xml:space="preserve"> {text}</w:t></w:r></w:p></w:{kind}>')


def notes(kind, items):
    sep = (f'<w:{kind} w:type="separator" w:id="-1"><w:p><w:r><w:separator/></w:r></w:p></w:{kind}>'
           f'<w:{kind} w:type="continuationSeparator" w:id="0"><w:p><w:r><w:continuationSeparator/></w:r></w:p></w:{kind}>')
    return part(kind + "s", sep + "".join(note(kind, i, t) for i, t in items))


styles = part("styles", '<w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="22"/></w:rPr></w:rPrDefault>'
              '<w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault></w:docDefaults>'
              '<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>')

rels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
        '<Relationship Id="rIdS" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>'
        '<Relationship Id="rIdH1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header1.xml"/>'
        '<Relationship Id="rIdH2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/header" Target="header2.xml"/>'
        '<Relationship Id="rIdF1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer" Target="footer1.xml"/>'
        '<Relationship Id="rIdFN" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/footnotes" Target="footnotes.xml"/>'
        '<Relationship Id="rIdEN" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/endnotes" Target="endnotes.xml"/>'
        '<Relationship Id="rIdP1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/>'
        '<Relationship Id="rIdP2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image2.png"/>'
        '</Relationships>')

types = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
         '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
         '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
         '<Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/>'
         '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
         '<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>'
         '<Override PartName="/word/header1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>'
         '<Override PartName="/word/header2.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml"/>'
         '<Override PartName="/word/footer1.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"/>'
         '<Override PartName="/word/footnotes.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml"/>'
         '<Override PartName="/word/endnotes.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.endnotes+xml"/>'
         '</Types>')

root_rels = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
             '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
             '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>'
             '</Relationships>')

with zipfile.ZipFile(f"{out}/features.docx", "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("[Content_Types].xml", types)
    z.writestr("_rels/.rels", root_rels)
    z.writestr("word/document.xml", document)
    z.writestr("word/_rels/document.xml.rels", rels)
    z.writestr("word/styles.xml", styles)
    z.writestr("word/header1.xml", part("hdr", p("Default header")))
    z.writestr("word/header2.xml", part("hdr", p("First page header")))
    z.writestr("word/footer1.xml", part("ftr", p(runs=page_field)))
    z.writestr("word/footnotes.xml", notes("footnote", [(1, "The first footnote."), (2, "The second footnote.")]))
    z.writestr("word/endnotes.xml", notes("endnote", [(1, "The endnote.")]))
    z.writestr("word/media/image1.png", png((200, 30, 30)))
    z.writestr("word/media/image2.png", png((30, 30, 200)))
print("features.docx written")
