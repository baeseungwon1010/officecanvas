"""Makes pdf_samples/layout.pdf, a page laid out the way the converter finds hard:
two columns of text on the same baselines with a gutter narrower than the gap
that ends a line, a row of pictures that touch, a soft shadow (a one-color,
half-transparent picture), a chart's empty grid with a legend, and a line with
a right tab stop.

Run: python3 make_pdf_layout.py <pdf_samples dir>  (needs PyMuPDF)"""
import sys

import fitz

out = sys.argv[1] if len(sys.argv) > 1 else "."
doc = fitz.open()
page = doc.new_page(width=612, height=792)
FONT, SIZE = "tiro", 10


def fill(words, width):
    """Lines of the words repeated, each as long as fits in width."""
    lines, cur, i = [], "", 0
    while len(lines) < 20:
        w = words[i % len(words)]
        i += 1
        nxt = (cur + " " + w).strip()
        if fitz.get_text_length(nxt, fontname=FONT, fontsize=SIZE) > width:
            lines.append(cur)
            cur = w
        else:
            cur = nxt
    return lines


page.insert_text((50, 70), "Two columns, pictures and a chart", fontname="tibo", fontsize=18)
# Columns 50..300 and 312..562 (a 12 pt gutter, 1.2 em), sharing baselines.
west = fill("west side words run down the left column of the page".split(), 250)
east = fill("east side words run down the right column of the page".split(), 250)


def justified(x, y, line, width):
    """The line's words spread to fill width exactly, as justified text is set."""
    words = line.split()
    gap = (width - sum(fitz.get_text_length(w, fontname=FONT, fontsize=SIZE) for w in words)) / (len(words) - 1)
    for w in words:
        page.insert_text((x, y), w, fontname=FONT, fontsize=SIZE)
        x += fitz.get_text_length(w, fontname=FONT, fontsize=SIZE) + gap


for i, (a, b) in enumerate(zip(west, east)):
    y = 110 + i * 12
    justified(50, y, a, 250)
    justified(312, y, b, 250)

# Three pictures in a row, touching.
for k, color in enumerate([(200, 60, 60), (60, 160, 60), (60, 60, 200)]):
    pix = fitz.Pixmap(fitz.csRGB, fitz.IRect(0, 0, 40, 30), False)
    pix.set_rect(pix.irect, color)
    page.insert_image(fitz.Rect(50 + k * 120, 360, 170 + k * 120, 450), pixmap=pix)
page.insert_text((50, 466), "Pictures in a row", fontname=FONT, fontsize=SIZE)

# A soft shadow: black, 30 % opaque at most.
shadow = fitz.Pixmap(fitz.csRGB, fitz.IRect(0, 0, 60, 30), True)
shadow.set_rect(shadow.irect, (0, 0, 0, 77))
page.insert_image(fitz.Rect(50, 490, 290, 610), pixmap=shadow)

# A chart's grid, 6 x 5 empty cells but the legend's.
for k in range(7):
    page.draw_line((320 + k * 40, 490), (320 + k * 40, 640), color=(0.7, 0.7, 0.7), width=0.5)
for k in range(6):
    page.draw_line((320, 490 + k * 30), (560, 490 + k * 30), color=(0.7, 0.7, 0.7), width=0.5)
page.insert_text((325, 510), "Legend", fontname=FONT, fontsize=8)

# A right tab stop.
page.insert_text((50, 680), "Left", fontname=FONT, fontsize=SIZE)
page.insert_text((562 - fitz.get_text_length("Right", fontname=FONT, fontsize=SIZE), 680), "Right", fontname=FONT, fontsize=SIZE)

doc.save(f"{out}/layout.pdf", garbage=3, deflate=True)
print("layout.pdf written")
