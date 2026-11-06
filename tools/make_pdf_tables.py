"""Makes pdf_samples/tables.pdf: tables with no rules down, the way Word sets a
table styled with only a rule under its heading. A three-column table with one
cell wrapped onto a second line, a two-column table under a rule, and two things
that must stay text: a bulleted list ending at a rule, and pairs of words with no
rule.

Run: python3 make_pdf_tables.py <pdf_samples dir>  (needs PyMuPDF)"""
import sys

import fitz

out = sys.argv[1] if len(sys.argv) > 1 else "."
doc = fitz.open()
page = doc.new_page(width=612, height=792)
FONT, SIZE = "tiro", 11


def text(x, y, s):
    page.insert_text((x, y), s, fontname=FONT, fontsize=SIZE)


page.insert_text((72, 72), "Tables without rules down", fontname="tibo", fontsize=16)

# Three columns at 72, 200 and 330; a rule under the heading.
y = 110
for row in [("Item", "Size", "Meaning"), ("N", "2048 bit", "public modulus"), ("MASK", "1024 bit", "set bits are known"),
            ("e", "65537", "public exponent"), ("ct", "2048 bit", "ciphertext")]:
    for x, s in zip((72, 200, 330), row):
        text(x, y, s)
    if row[0] == "MASK":
        y += 14
        text(330, y, "(the rest unknown)")
    if row[0] == "Item":
        page.draw_line((68, y + 5), (470, y + 5), width=0.75)
    y += 20

# Two columns under a rule.
y += 20
for i, row in enumerate([("Control", "Function"), ("0000", "AND"), ("0001", "OR"), ("0010", "add"), ("0110", "subtract")]):
    text(72, y, row[0]); text(250, y, row[1])
    if i == 0:
        page.draw_line((68, y + 5), (400, y + 5), width=0.75)
    y += 18

# A list ending at a rule across the page.
y += 20
for s in ["first point", "second point", "third point", "fourth point"]:
    text(72, y, "•"); text(100, y, s)
    y += 16
page.draw_line((72, y - 8), (540, y - 8), width=0.75)

# Pairs of words, no rule.
y += 20
for a, b in [("Name", "Kim"), ("Room", "301"), ("Day", "Monday")]:
    text(72, y, a); text(250, y, b)
    y += 16

doc.save(f"{out}/tables.pdf", garbage=3, deflate=True)
print("tables.pdf written")
