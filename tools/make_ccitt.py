"""Makes pdf_samples/ccitt_g4.bin, ccitt_g3.bin and ccitt_g3_2d.bin: one 2600 x 60
picture of runs coded as fax data, Group 4, Group 3 one-dimensional and Group 3
two-dimensional (lines coded either way, each tagged; as libtiff codes them),
for CcittFaxTest. Every 20th row is black for its first 2000 pixels (makeup codes
past 1728); the rest are runs that change length from row to row.

Run: python3 make_ccitt.py <pdf_samples dir>  (needs Pillow built with libtiff)"""
import os
import sys

from PIL import Image

out = sys.argv[1] if len(sys.argv) > 1 else "."
W, H = 2600, 60


def black(x, y):
    return x < 2000 if y % 20 == 0 else ((x * (y % 7 + 1)) // 37) % 4 == 0


# Pillow saves a 1-bit picture as 0 = black and libtiff codes samples of 0 as fax
# white, so the picture is saved inverted: the fax data's white is the pattern's white.
im = Image.new("1", (W, H), 0)
px = im.load()
for y in range(H):
    for x in range(W):
        if black(x, y):
            px[x, y] = 1
for comp, name, options in (("group4", "ccitt_g4.bin", {}), ("group3", "ccitt_g3.bin", {}), ("group3", "ccitt_g3_2d.bin", {292: 1})):
    tmp = os.path.join(out, name + ".tif")
    im.save(tmp, compression=comp, tiffinfo={278: H, **options})
    t = Image.open(tmp)
    off, size = t.tag_v2[273][0], t.tag_v2[279][0]
    with open(tmp, "rb") as f:
        f.seek(off)
        data = f.read(size)
    t.close()
    os.remove(tmp)
    with open(os.path.join(out, name), "wb") as f:
        f.write(data)
