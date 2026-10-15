"""Builds sample.hwpx: a three-page Korean document with headings, body text and
a table, written the way Hangul saves one — every line carries its position
(<hp:lineseg> vertpos/horzpos/horzsize) relative to the page's body area, and a
new page shows up as vertpos dropping back to 0. HwpxParser reads exactly that,
so the layout here is computed with fixed metrics (10pt text, 160% line pitch,
one full-width character per 1000 HWPUNIT) instead of a real layout engine.

Page furniture and objects: a header with a tab and the page number field, a page number
at the bottom center ("- 2 -"), a floating rectangle text box, an inline line and
a cropped picture.
Run: python3 make_sample_hwpx.py <outdir>"""
import struct
import sys
import zipfile
import zlib
from xml.sax.saxutils import escape

out = sys.argv[1] if len(sys.argv) > 1 else "."

PAGE_W, PAGE_H = 59528, 84186  # A4 in HWPUNIT (1/7200 inch)
MARGIN = dict(left=8504, right=8504, top=5668, bottom=4252, header=4252, footer=4252, gutter=0)
BODY_W = PAGE_W - MARGIN["left"] - MARGIN["right"]
BODY_H = PAGE_H - MARGIN["top"] - MARGIN["header"] - MARGIN["bottom"] - MARGIN["footer"]
CHARS_PER_LINE = 40

# charPr id -> (height in 1/100 pt, color, bold)
CHAR_PRS = {0: (1000, "#000000", False), 1: (1400, "#1F3864", True), 2: (900, "#595959", False)}


class Layout:
    """Tracks the vertical cursor and starts a new page when a line won't fit."""

    def __init__(self):
        self.y = 0
        self.pages = 1

    def place(self, height):
        if self.y + height > BODY_H:
            self.y = 0
            self.pages += 1
        y = self.y
        self.y += height
        return y


def metrics(char_pr):
    size = CHAR_PRS[char_pr][0]
    return size, size * 85 // 100, size * 6 // 10  # textheight, baseline, spacing


def wrap(text):
    return [text[i:i + CHARS_PER_LINE] for i in range(0, len(text), CHARS_PER_LINE)] or [""]


def lineseg(textpos, vertpos, char_pr, width=BODY_W):
    h, base, spacing = metrics(char_pr)
    return (f'<hp:lineseg textpos="{textpos}" vertpos="{vertpos}" vertsize="{h}" textheight="{h}" '
            f'baseline="{base}" spacing="{spacing}" horzpos="0" horzsize="{width}" flags="393216"/>')


def paragraph(layout, text, char_pr=0, first=False, sec_pr=""):
    """sec_pr: section properties and page controls carried by the first run."""
    h, _, spacing = metrics(char_pr)
    segs, pos = [], 0
    for line in wrap(text):
        segs.append(lineseg(pos, layout.place(h + spacing), char_pr))
        pos += len(line)
    return (f'<hp:p id="0" paraPrIDRef="0" styleIDRef="0" pageBreak="0" columnBreak="0" merged="0">'
            f'<hp:run charPrIDRef="{char_pr}">{sec_pr}<hp:t>{escape(text)}</hp:t></hp:run>'
            f'<hp:linesegarray>{"".join(segs)}</hp:linesegarray></hp:p>')


def cell(row, col, width, text):
    h, _, spacing = metrics(0)
    inner = Layout()
    segs, pos = [], 0
    for line in wrap(text):
        segs.append(lineseg(pos, inner.place(h + spacing), 0, width - 1020))
        pos += len(line)
    return (f'<hp:tc name="" header="0" hasMargin="0" protect="0" editable="0" dirty="0" borderFillIDRef="1">'
            f'<hp:subList id="" textDirection="HORIZONTAL" lineWrap="BREAK" vertAlign="CENTER">'
            f'<hp:p id="0" paraPrIDRef="0" styleIDRef="0"><hp:run charPrIDRef="0"><hp:t>{escape(text)}</hp:t></hp:run>'
            f'<hp:linesegarray>{"".join(segs)}</hp:linesegarray></hp:p></hp:subList>'
            f'<hp:cellAddr colAddr="{col}" rowAddr="{row}"/><hp:cellSpan colSpan="1" rowSpan="1"/>'
            f'<hp:cellSz width="{width}" height="2000"/><hp:cellMargin left="510" right="510" top="141" bottom="141"/></hp:tc>')


def table(layout, rows):
    cols = len(rows[0])
    col_w = BODY_W // cols
    row_h = 2600
    height = row_h * len(rows)
    trs = "".join("<hp:tr>" + "".join(cell(r, c, col_w, v) for c, v in enumerate(row)) + "</hp:tr>"
                  for r, row in enumerate(rows))
    tbl = (f'<hp:tbl id="0" zOrder="0" numberingType="TABLE" textWrap="TOP_AND_BOTTOM" pageBreak="CELL" '
           f'repeatHeader="1" rowCnt="{len(rows)}" colCnt="{cols}" cellSpacing="0" borderFillIDRef="1" noAdjust="0">'
           f'<hp:sz width="{BODY_W}" widthRelTo="ABSOLUTE" height="{height}" heightRelTo="ABSOLUTE" protect="0"/>'
           f'<hp:pos treatAsChar="1" affectLSpacing="0" flowWithText="1" allowOverlap="0" holdAnchorAndSO="0" '
           f'vertRelTo="PARA" horzRelTo="PARA" vertAlign="TOP" horzAlign="LEFT" vertOffset="0" horzOffset="0"/>'
           f'{trs}</hp:tbl>')
    y = layout.place(height + 600)
    seg = (f'<hp:lineseg textpos="0" vertpos="{y}" vertsize="{height}" textheight="{height}" baseline="{height}" '
           f'spacing="600" horzpos="0" horzsize="{BODY_W}" flags="393216"/>')
    return (f'<hp:p id="0" paraPrIDRef="0" styleIDRef="0"><hp:run charPrIDRef="0">{tbl}<hp:t/></hp:run>'
            f'<hp:linesegarray>{seg}</hp:linesegarray></hp:p>')


def png(w, h, pixel):
    """A small RGB PNG; pixel(x, y) -> (r, g, b)."""
    raw = b"".join(b"\0" + b"".join(bytes(pixel(x, y)) for x in range(w)) for y in range(h))
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


# Left half red, right half blue; the picture below keeps only the right half.
PICTURE = png(16, 8, lambda x, y: (220, 40, 40) if x < 8 else (40, 70, 200))

IDENTITY = 'e1="1" e2="0" e3="0" e4="0" e5="1" e6="0"'
RENDERING = (f'<hp:renderingInfo><hc:transMatrix {IDENTITY}/><hc:scaMatrix {IDENTITY}/>'
             f'<hc:rotMatrix {IDENTITY}/></hp:renderingInfo>')


def pos(inline, vert_offset=0, horz_offset=0):
    return (f'<hp:pos treatAsChar="{1 if inline else 0}" affectLSpacing="0" flowWithText="1" allowOverlap="0" '
            f'holdAnchorAndSO="0" vertRelTo="PARA" horzRelTo="COLUMN" vertAlign="TOP" horzAlign="LEFT" '
            f'vertOffset="{vert_offset}" horzOffset="{horz_offset}"/>')


def text_box(w, h, text, vert_offset, horz_offset):
    """A rounded rectangle with a fill, placed by offsets from its paragraph."""
    th, base, spacing = metrics(0)
    return (f'<hp:rect id="0" zOrder="1" numberingType="PICTURE" textWrap="IN_FRONT_OF_TEXT" ratio="20">'
            f'<hp:offset x="0" y="0"/><hp:orgSz width="{w}" height="{h}"/><hp:curSz width="{w}" height="{h}"/>'
            f'{RENDERING}<hp:lineShape color="#1F3864" width="71" style="SOLID"/>'
            f'<hc:fillBrush><hc:winBrush faceColor="#DDEBF7" hatchColor="#000000" alpha="0"/></hc:fillBrush>'
            f'<hp:drawText lastWidth="{w}" name="" editable="0"><hp:textMargin left="283" right="283" top="283" bottom="283"/>'
            f'<hp:subList vertAlign="CENTER"><hp:p id="0" paraPrIDRef="0" styleIDRef="0"><hp:run charPrIDRef="0">'
            f'<hp:t>{escape(text)}</hp:t></hp:run><hp:linesegarray>'
            f'{lineseg(0, 0, 0, w - 566)}</hp:linesegarray></hp:p></hp:subList></hp:drawText>'
            f'<hc:pt0 x="0" y="0"/><hc:pt1 x="{w}" y="0"/><hc:pt2 x="{w}" y="{h}"/><hc:pt3 x="0" y="{h}"/>'
            f'<hp:sz width="{w}" widthRelTo="ABSOLUTE" height="{h}" heightRelTo="ABSOLUTE" protect="0"/>'
            f'{pos(False, vert_offset, horz_offset)}</hp:rect>')


def rule(w):
    """A horizontal line, inline (treated as a character)."""
    return (f'<hp:line id="0" zOrder="2" numberingType="PICTURE" textWrap="TOP_AND_BOTTOM" isReverseHV="0">'
            f'<hp:offset x="0" y="0"/><hp:orgSz width="{w}" height="0"/><hp:curSz width="{w}" height="0"/>'
            f'{RENDERING}<hp:lineShape color="#C00000" width="57" style="SOLID"/>'
            f'<hc:startPt x="0" y="0"/><hc:endPt x="{w}" y="0"/>'
            f'<hp:sz width="{w}" widthRelTo="ABSOLUTE" height="0" heightRelTo="ABSOLUTE" protect="0"/>'
            f'{pos(True)}</hp:line>')


def picture(w, h):
    """The PNG at w x h, cropped to its right half (<hp:imgClip> within <hp:imgDim>)."""
    full_w, full_h = 1600, 800
    return (f'<hp:pic id="0" zOrder="3" numberingType="PICTURE" textWrap="TOP_AND_BOTTOM">'
            f'<hp:offset x="0" y="0"/><hp:orgSz width="{full_w}" height="{full_h}"/><hp:curSz width="{w}" height="{h}"/>'
            f'{RENDERING}<hp:imgRect><hc:pt0 x="0" y="0"/><hc:pt1 x="{full_w}" y="0"/>'
            f'<hc:pt2 x="{full_w}" y="{full_h}"/><hc:pt3 x="0" y="{full_h}"/></hp:imgRect>'
            f'<hp:imgClip left="{full_w // 2}" right="{full_w}" top="0" bottom="{full_h}"/>'
            f'<hp:imgDim dimwidth="{full_w}" dimheight="{full_h}"/>'
            f'<hc:img binaryItemIDRef="image1" bright="0" contrast="0" effect="REAL_PIC" alpha="0"/>'
            f'<hp:sz width="{w}" widthRelTo="ABSOLUTE" height="{h}" heightRelTo="ABSOLUTE" protect="0"/>'
            f'{pos(True)}</hp:pic>')


def object_paragraph(layout, objects, height, width):
    """A paragraph holding only inline objects, as one line of the given height."""
    y = layout.place(height + 600)
    seg = (f'<hp:lineseg textpos="0" vertpos="{y}" vertsize="{height}" textheight="{height}" baseline="{height}" '
           f'spacing="600" horzpos="0" horzsize="{width}" flags="393216"/>')
    return (f'<hp:p id="0" paraPrIDRef="0" styleIDRef="0"><hp:run charPrIDRef="0">{objects}<hp:t/></hp:run>'
            f'<hp:linesegarray>{seg}</hp:linesegarray></hp:p>')


def header_ctrl():
    """Header text with a tab (and the width it took) and the page number field,
    and the page number at the bottom."""
    th, base, spacing = metrics(2)
    return ('<hp:ctrl><hp:header id="1" applyPageType="BOTH"><hp:subList vertAlign="BOTTOM">'
            '<hp:p id="0" paraPrIDRef="0" styleIDRef="0"><hp:run charPrIDRef="2"><hp:t>OfficeCanvas 샘플<hp:tab width="20000" leader="0" type="1"/>쪽 </hp:t>'
            '<hp:ctrl><hp:autoNum num="1" numType="PAGE"><hp:autoNumFormat type="DIGIT"/></hp:autoNum></hp:ctrl>'
            f'<hp:t/></hp:run><hp:linesegarray>{lineseg(0, 0, 2)}</hp:linesegarray></hp:p></hp:subList></hp:header></hp:ctrl>'
            '<hp:ctrl><hp:pageNum pos="BOTTOM_CENTER" formatType="DIGIT" sideChar="-"/></hp:ctrl>')


BODY = ("이 문서는 OfficeCanvas의 HWPX 렌더러를 시험하기 위해 만든 합성 문서입니다. 한글은 문서를 저장할 때 "
        "각 줄이 놓일 위치와 페이지가 나뉘는 지점을 미리 계산해서 파일 안에 남겨 두기 때문에, 렌더러는 줄바꿈과 "
        "쪽 나눔을 다시 계산하지 않고 저장된 값을 그대로 읽어 그릴 수 있습니다.")

layout = Layout()
sec_pr = (f'<hp:secPr id="" textDirection="HORIZONTAL" spaceColumns="1134" tabStop="8000" outlineShapeIDRef="1" '
          f'memoShapeIDRef="0" textVerticalWidthHead="0" masterPageCnt="0">'
          f'<hp:pagePr landscape="WIDELY" width="{PAGE_W}" height="{PAGE_H}" gutterType="LEFT_ONLY">'
          f'<hp:margin header="{MARGIN["header"]}" footer="{MARGIN["footer"]}" gutter="{MARGIN["gutter"]}" '
          f'left="{MARGIN["left"]}" right="{MARGIN["right"]}" top="{MARGIN["top"]}" bottom="{MARGIN["bottom"]}"/>'
          f'</hp:pagePr></hp:secPr>')
paras = [paragraph(layout, "1. 문서 구조와 줄 위치", 1, sec_pr=sec_pr + header_ctrl())]
for i in range(8):
    paras.append(paragraph(layout, f"({i + 1}) " + BODY))
paras.append(paragraph(layout, "2. 형식별 비교", 1))
paras.append(table(layout, [("형식", "저장 방식", "비고"),
                            ("PPTX", "zip + XML", "절대 좌표"),
                            ("HWPX", "zip + XML", "프로젝트 문서에 흔함")]))
# The text box floats to the right of its paragraph's first line.
first_line = paragraph(layout, "(9) " + BODY)
paras.append(first_line.replace('<hp:t>', text_box(14000, 4000, "글상자 안의 글", 1500, BODY_W - 14000) + '<hp:t>', 1))
paras.append(object_paragraph(layout, rule(BODY_W), 600, BODY_W))
paras.append(object_paragraph(layout, picture(8000, 8000), 8000, 8000))
for i in range(8):
    paras.append(paragraph(layout, f"({i + 10}) " + BODY))
paras.append(paragraph(layout, "3. 맺음말", 1))
paras.append(paragraph(layout, "이 샘플은 실제 문서를 대신해 테스트에 쓰입니다.", 2))

section = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
           '<hs:sec xmlns:hs="http://www.hancom.co.kr/hwpml/2011/section" '
           'xmlns:hp="http://www.hancom.co.kr/hwpml/2011/paragraph" '
           'xmlns:hc="http://www.hancom.co.kr/hwpml/2011/core">' + "".join(paras) + '</hs:sec>')

char_prs = "".join(
    f'<hh:charPr id="{i}" height="{h}" textColor="{color}" shadeColor="none" useFontSpace="0" useKerning="0" '
    f'symMark="NONE" borderFillIDRef="1">{"<hh:bold/>" if bold else ""}</hh:charPr>'
    for i, (h, color, bold) in CHAR_PRS.items())
header = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
          '<hh:head xmlns:hh="http://www.hancom.co.kr/hwpml/2011/head" version="1.4" secCnt="1">'
          f'<hh:refList><hh:charProperties itemCnt="{len(CHAR_PRS)}">{char_prs}</hh:charProperties></hh:refList>'
          '</hh:head>')

content = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
           '<opf:package xmlns:opf="http://www.idpf.org/2007/opf/" version="" unique-identifier="" id="">'
           '<opf:metadata><opf:title>OfficeCanvas sample</opf:title><opf:language>ko</opf:language></opf:metadata>'
           '<opf:manifest>'
           '<opf:item id="header" href="Contents/header.xml" media-type="application/xml"/>'
           '<opf:item id="section0" href="Contents/section0.xml" media-type="application/xml"/>'
           '<opf:item id="image1" href="BinData/image1.png" media-type="image/png" isEmbeded="1"/>'
           '</opf:manifest><opf:spine><opf:itemref idref="section0" linear="yes"/></opf:spine></opf:package>')

container = ('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
             '<ocf:container xmlns:ocf="urn:oasis:names:tc:opendocument:xmlns:container">'
             '<ocf:rootfiles><ocf:rootfile full-path="Contents/content.hpf" media-type="application/hwpml-package+xml"/>'
             '</ocf:rootfiles></ocf:container>')

with zipfile.ZipFile(f"{out}/sample.hwpx", "w") as z:
    z.writestr(zipfile.ZipInfo("mimetype"), "application/hwp+zip", compress_type=zipfile.ZIP_STORED)
    z.writestr("version.xml", '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
               '<hv:HCFVersion xmlns:hv="http://www.hancom.co.kr/hwpml/2011/version" tagetApplication="WORDPROCESSOR" '
               'major="5" minor="1" micro="0" buildNumber="1" xmlVersion="1.4" application="OfficeCanvas"/>')
    z.writestr("META-INF/container.xml", container, compress_type=zipfile.ZIP_DEFLATED)
    z.writestr("Contents/content.hpf", content, compress_type=zipfile.ZIP_DEFLATED)
    z.writestr("Contents/header.xml", header, compress_type=zipfile.ZIP_DEFLATED)
    z.writestr("Contents/section0.xml", section, compress_type=zipfile.ZIP_DEFLATED)
    z.writestr("BinData/image1.png", PICTURE)

print(f"sample.hwpx: {layout.pages} pages")
