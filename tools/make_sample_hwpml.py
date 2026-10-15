"""Builds sample_hwpml.hwp: an HWPML document (the XML some systems still save
.hwp files as). HWPML saves no layout, so what the tests check is how it is laid
out: a centered bold title, a long paragraph that wraps inside the body width, a
forced line break, a table set in the text with a thin empty spacer row, a table
placed by offsets from the page corner, a page break, and enough paragraphs on the
last page to run over onto another one.

Run: python3 make_sample_hwpml.py <outdir>"""
import sys
from xml.sax.saxutils import escape

out = sys.argv[1] if len(sys.argv) > 1 else "."

PAGE_W, PAGE_H = 59528, 84188
LEFT, RIGHT, TOP, BOTTOM, HEADER, FOOTER = 8504, 8504, 5668, 4252, 4252, 4252

BODY = ("한글 문서의 옛 XML 형식에는 줄을 나눈 위치가 저장되지 않으므로 글자의 폭을 재서 본문 폭 안에서 "
        "직접 줄을 나누고 문단 모양의 줄 간격대로 쌓는다. Latin words such as layout and pagination are kept "
        "whole and wrap at spaces, while Hangul may break between any two characters.")


def char_shape(i, height, bold=False):
    return (f'<CHARSHAPE Id="{i}" Height="{height}" TextColor="0" ShadeColor="4294967295" UseFontSpace="false" '
            f'UseKerning="false" SymMark="0"><FONTID Hangul="0" Hanja="0" Japanese="0" Latin="0" Other="0" '
            f'Symbol="0" User="0"/><RATIO Hangul="100" Hanja="100" Japanese="100" Latin="100" Other="100" '
            f'Symbol="100" User="100"/><CHARSPACING Hangul="0" Hanja="0" Japanese="0" Latin="0" Other="0" '
            f'Symbol="0" User="0"/><RELSIZE Hangul="100" Hanja="100" Japanese="100" Latin="100" Other="100" '
            f'Symbol="100" User="100"/><CHAROFFSET Hangul="0" Hanja="0" Japanese="0" Latin="0" Other="0" '
            f'Symbol="0" User="0"/>{"<BOLD/>" if bold else ""}</CHARSHAPE>')


def para_shape(i, align, spacing):
    return (f'<PARASHAPE Id="{i}" Align="{align}" VerAlign="Baseline" HeadingType="None" Level="0" '
            f'BreakLatinWord="KeepWord" BreakNonLatinWord="true"><PARAMARGIN Indent="0" Left="0" Right="0" '
            f'Prev="0" Next="0" LineSpacingType="Percent" LineSpacing="{spacing}"/></PARASHAPE>')


def border_fill(i, line, fill):
    side = lambda name: f'<{name} Type="{"Solid" if line else "None"}" Width="0.12mm" Color="0"/>'
    return (f'<BORDERFILL Id="{i}" ThreeD="false" Shadow="false">{side("LEFTBORDER")}{side("RIGHTBORDER")}'
            f'{side("TOPBORDER")}{side("BOTTOMBORDER")}<FILLBRUSH><WINDOWBRUSH FaceColor="{fill}" HatchColor="0"/>'
            f'</FILLBRUSH></BORDERFILL>')


def p(text, char=0, para=0, page_break=False, objects=""):
    chars = "".join(f"<CHAR>{escape(t)}</CHAR>" if isinstance(t, str) else t[0] for t in text)
    return (f'<P ParaShape="{para}" Style="0"{" PageBreak=\"true\"" if page_break else ""}>'
            f'<TEXT CharShape="{char}">{objects}{chars}</TEXT></P>')


def cell(row, col, width, height, text):
    body = p([text]) if text else '<P ParaShape="0" Style="0"><TEXT CharShape="0"></TEXT></P>'
    return (f'<CELL ColAddr="{col}" RowAddr="{row}" ColSpan="1" RowSpan="1" Width="{width}" Height="{height}" '
            f'HasMargin="false" BorderFill="1"><PARALIST TextDirection="0" LineWrap="Break" VertAlign="Center">'
            f'{body}</PARALIST></CELL>')


def table(rows, widths, heights, inline, voff=0, hoff=0):
    w, h = sum(widths), sum(heights)
    trs = "".join("<ROW>" + "".join(cell(r, c, widths[c], heights[r], v) for c, v in enumerate(row)) + "</ROW>"
                  for r, row in enumerate(rows))
    rel = 'VertRelTo="Para" HorzRelTo="Column"' if inline else 'VertRelTo="Page" HorzRelTo="Page"'
    return (f'<TABLE PageBreak="Cell" RowCount="{len(rows)}" ColCount="{len(widths)}" BorderFill="1">'
            f'<SHAPEOBJECT TextWrap="{"TopAndBottom" if inline else "InFrontOfText"}"><SIZE Width="{w}" Height="{h}"/>'
            f'<POSITION TreatAsChar="{"true" if inline else "false"}" {rel} VertAlign="Top" HorzAlign="Left" '
            f'VertOffset="{voff}" HorzOffset="{hoff}"/></SHAPEOBJECT>'
            f'<INSIDEMARGIN Left="510" Right="510" Top="141" Bottom="141"/>{trs}</TABLE>')


head = ('<HEAD SecCnt="1"><MAPPINGTABLE><FACENAMELIST><FONTFACE Count="1" Lang="Hangul"><FONT Id="0" Name="함초롬바탕"/>'
        '</FONTFACE><FONTFACE Count="1" Lang="Latin"><FONT Id="0" Name="함초롬바탕"/></FONTFACE></FACENAMELIST>'
        f'<BORDERFILLLIST Count="1">{border_fill(1, True, 16777215)}</BORDERFILLLIST>'
        f'<CHARSHAPELIST Count="2">{char_shape(0, 1000)}{char_shape(1, 1600, bold=True)}</CHARSHAPELIST>'
        f'<PARASHAPELIST Count="2">{para_shape(0, "Justify", 160)}{para_shape(1, "Center", 160)}</PARASHAPELIST>'
        '</MAPPINGTABLE></HEAD>')

secdef = (f'<SECDEF TextDirection="0"><PAGEDEF Landscape="0" Width="{PAGE_W}" Height="{PAGE_H}" GutterType="LeftOnly">'
          f'<PAGEMARGIN Left="{LEFT}" Right="{RIGHT}" Top="{TOP}" Bottom="{BOTTOM}" Header="{HEADER}" '
          f'Footer="{FOOTER}" Gutter="0"/></PAGEDEF></SECDEF>')

body_w = PAGE_W - LEFT - RIGHT
paras = [
    p(["HWPML 견본"], char=1, para=1, objects=secdef),
    p([BODY]),
    p(["첫 줄", ("<CHAR><LINEBREAK/></CHAR>",), "줄바꿈 뒤"]),
    p([], objects=table([("항목", "내용"), ("", ""), ("형식", "HWPML")], [10000, body_w - 10000], [2000, 400, 2000], True)),
    # Placed by offsets from the page corner, whatever the text does.
    p(["떠 있는 표 옆의 문단"], objects=table([("위치 고정",)], [12000], [3000], False, voff=60000, hoff=30000)),
    p(["둘째 쪽"], page_break=True),
] + [p([f"({i + 1}) " + BODY]) for i in range(14)]

xml = ('<?xml version="1.0" encoding="UTF-8" standalone="no" ?><HWPML Version="2.8" SubVersion="8.0.0.0" Style2="embed">'
       f'{head}<BODY><SECTION Id="0">{"".join(paras)}</SECTION></BODY><TAIL><BINDATASTORAGE/></TAIL></HWPML>')
with open(f"{out}/sample_hwpml.hwp", "w", encoding="utf-8") as f:
    f.write(xml)
print("sample_hwpml.hwp written")
