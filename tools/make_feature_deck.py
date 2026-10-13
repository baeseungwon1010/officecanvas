"""Builds feature_test.pptx: one slide per .ppt-parser feature, so converting it
to binary .ppt with LibreOffice gives a known-content fixture for PptParser tests
(numbering, freeform/preset outlines, gradients, picture crop, TIFF, sub/superscript,
monospace font). Run: python3 make_feature_deck.py <outdir>, or tools/build_fixtures.sh"""
import io
import sys

from lxml import etree
from PIL import Image
from pptx import Presentation
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE
from pptx.oxml.ns import qn
from pptx.util import Inches, Pt

out = sys.argv[1] if len(sys.argv) > 1 else "."
prs = Presentation()
prs.slide_width, prs.slide_height = Inches(10), Inches(7.5)
blank = prs.slide_layouts[6]


def textbox(slide, x, y, w, h):
    return slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h)).text_frame


# 1: auto-numbered list (1. 2. 3., then a plain line, then a. b.)
s = prs.slides.add_slide(blank)
tf = textbox(s, 1, 1, 8, 5)
items = [("First", "arabicPeriod"), ("Second", "arabicPeriod"), ("Third", "arabicPeriod"),
         ("Plain line", None), ("Alpha one", "alphaLcPeriod"), ("Alpha two", "alphaLcPeriod")]
for i, (text, scheme) in enumerate(items):
    p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
    p.text = text
    p.font.size = Pt(28)
    if scheme:
        pPr = p._p.get_or_add_pPr()
        pPr.set("marL", "457200")
        pPr.set("indent", "-457200")
        an = etree.SubElement(pPr, qn("a:buAutoNum"))
        an.set("type", scheme)

# 2: outlines — freeform triangle-ish polygon + presets
s = prs.slides.add_slide(blank)
ff = s.shapes.build_freeform(Inches(0.5), Inches(4))
ff.add_line_segments([(Inches(2.5), Inches(4)), (Inches(1.5), Inches(6.5))], close=True)
fshape = ff.convert_to_shape()
fshape.fill.solid(); fshape.fill.fore_color.rgb = RGBColor(0xFF, 0, 0)
for i, kind in enumerate([MSO_SHAPE.ISOSCELES_TRIANGLE, MSO_SHAPE.DIAMOND, MSO_SHAPE.RIGHT_ARROW, MSO_SHAPE.STAR_5_POINT]):
    sh = s.shapes.add_shape(kind, Inches(0.5 + i * 2.3), Inches(0.8), Inches(2), Inches(2))
    sh.fill.solid(); sh.fill.fore_color.rgb = RGBColor(0, 0x70, 0xC0)

# 3: gradients — horizontal (red->blue, left to right) and vertical (green->yellow, top to bottom)
s = prs.slides.add_slide(blank)
for i, (c1, c2, angle) in enumerate([((255, 0, 0), (0, 0, 255), 0), ((0, 160, 0), (255, 255, 0), 90)]):
    sh = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0.5 + i * 4.8), Inches(1.5), Inches(4.2), Inches(4))
    sh.fill.gradient()
    sh.fill.gradient_angle = angle
    stops = sh.fill.gradient_stops
    stops[0].color.rgb = RGBColor(*c1); stops[0].position = 0
    stops[1].color.rgb = RGBColor(*c2); stops[1].position = 1.0

# 4: pictures — a cropped PNG (left half: red, right half: blue; crop keeps the right half)
#    and a TIFF; plus sub/superscript and monospace text
s = prs.slides.add_slide(blank)
img = Image.new("RGB", (200, 100), (255, 0, 0))
img.paste((0, 0, 255), (100, 0, 200, 100))
buf = io.BytesIO(); img.save(buf, "PNG"); buf.seek(0)
pic = s.shapes.add_picture(buf, Inches(0.5), Inches(0.5), Inches(3), Inches(3))
pic.crop_left = 0.5
tif = Image.new("RGB", (120, 80), (0, 200, 0))
tbuf = io.BytesIO(); tif.save(tbuf, "TIFF"); tbuf.seek(0)
s.shapes.add_picture(tbuf, Inches(5), Inches(0.5), Inches(3), Inches(2))
tf = textbox(s, 0.5, 4.5, 9, 2)
p = tf.paragraphs[0]
for text, base in [("E=mc", 0), ("2", 30000), (" H", 0), ("2", -25000), ("O", 0)]:
    r = p.add_run(); r.text = text; r.font.size = Pt(32)
    if base:
        r._r.get_or_add_rPr().set("baseline", str(base))
p2 = tf.add_paragraph(); r = p2.add_run(); r.text = "mono text"; r.font.name = "Courier New"; r.font.size = Pt(24)

# 5: background gradient (white top -> gray bottom), an axial gradient (blue-white-blue,
#    left to right) and a radial one (white center -> red edge)
s = prs.slides.add_slide(blank)
bg = s.background.fill
bg.gradient()
bg.gradient_angle = 270  # python-pptx: counter-clockwise from left-to-right, so 270 = top to bottom
bg.gradient_stops[0].color.rgb = RGBColor(255, 255, 255); bg.gradient_stops[0].position = 0
bg.gradient_stops[1].color.rgb = RGBColor(128, 128, 128); bg.gradient_stops[1].position = 1.0
A = "http://schemas.openxmlformats.org/drawingml/2006/main"


def grad_xml(stops, tail):
    gs = "".join(f'<a:gs pos="{p}"><a:srgbClr val="{c}"/></a:gs>' for p, c in stops)
    return etree.fromstring(f'<a:gradFill xmlns:a="{A}" rotWithShape="1"><a:gsLst>{gs}</a:gsLst>{tail}</a:gradFill>')


for i, (stops, tail) in enumerate([
    ([(0, "0000FF"), (50000, "FFFFFF"), (100000, "0000FF")], '<a:lin ang="0" scaled="0"/>'),
    ([(0, "FFFFFF"), (100000, "FF0000")], '<a:path path="circle"><a:fillToRect l="50000" t="50000" r="50000" b="50000"/></a:path>'),
]):
    sh = s.shapes.add_shape(MSO_SHAPE.RECTANGLE, Inches(0.5 + i * 4.8), Inches(1.5), Inches(4.2), Inches(4))
    spPr = sh._element.spPr
    for old in spPr.findall(qn("a:solidFill")) + spPr.findall(qn("a:gradFill")):
        spPr.remove(old)
    spPr.insert(2, grad_xml(stops, tail))

prs.save(f"{out}/feature_test.pptx")
print("ok")
