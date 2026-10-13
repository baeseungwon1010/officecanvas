"""Builds the small picture fixtures: tiff_samples/*.tif (a 40x30 image, left half
red, right half blue, in each compression and color layout TiffDecoder supports;
the bilevel one is white and black)
and shapes.svg, which build_fixtures.sh turns into emf_samples/shapes.emf with
LibreOffice (a red square on the left, a blue ellipse on the right).
Run: python3 make_small_fixtures.py <resources dir> <svg outdir>"""
import os
import sys

from PIL import Image

res, svg_dir = sys.argv[1], sys.argv[2]
tiff_dir = os.path.join(res, "tiff_samples")
os.makedirs(tiff_dir, exist_ok=True)

img = Image.new("RGB", (40, 30), (255, 0, 0))
img.paste((0, 0, 255), (20, 0, 40, 30))
img.save(os.path.join(tiff_dir, "rgb_raw.tif"), compression="raw")
img.save(os.path.join(tiff_dir, "rgb_tiff_lzw.tif"), compression="tiff_lzw")
img.save(os.path.join(tiff_dir, "rgb_packbits.tif"), compression="packbits")
img.quantize(2).save(os.path.join(tiff_dir, "palette_lzw.tif"), compression="tiff_lzw")
img.convert("L").save(os.path.join(tiff_dir, "gray_raw.tif"), compression="raw")
bilevel = Image.new("1", (40, 30), 1)
bilevel.paste(0, (20, 0, 40, 30))
bilevel.save(os.path.join(tiff_dir, "bilevel_packbits.tif"), compression="packbits")

with open(os.path.join(svg_dir, "shapes.svg"), "w") as f:
    f.write('<svg xmlns="http://www.w3.org/2000/svg" width="400" height="200" viewBox="0 0 400 200">'
            '<rect x="40" y="40" width="120" height="120" fill="#ff0000" stroke="#000000" stroke-width="2"/>'
            '<ellipse cx="300" cy="100" rx="70" ry="50" fill="#0000ff"/></svg>')
print("ok")
