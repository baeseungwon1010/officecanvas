"""Dev tool: scores how far each of our slide renders (ours_N.png) is from
LibreOffice's (lo_N.png) and builds side-by-side sheets (ours | LibreOffice).
Usage: python3 compare_render.py <dir> <outdir> [slides-per-sheet]"""
import sys

from PIL import Image, ImageChops, ImageFilter, ImageStat

d, out = sys.argv[1], sys.argv[2]
per = int(sys.argv[3]) if len(sys.argv) > 3 else 4
n = 1
scores = []
while True:
    try:
        ours = Image.open(f"{d}/ours_{n}.png").convert("L")
    except FileNotFoundError:
        break
    lo = Image.open(f"{d}/lo_{n}.png").convert("L").resize(ours.size)
    # Blur both so 1-2px font/antialiasing shifts don't dominate the score.
    a = ours.resize((160, 90)).filter(ImageFilter.GaussianBlur(1))
    b = lo.resize((160, 90)).filter(ImageFilter.GaussianBlur(1))
    scores.append((ImageStat.Stat(ImageChops.difference(a, b)).mean[0], n))
    n += 1

scores.sort(reverse=True)
print("worst:", " ".join(f"{s}:{v:.1f}" for v, s in scores[:25]))


def sheet(nums, path):
    first = Image.open(f"{d}/ours_{nums[0]}.png")
    w, h = first.size
    c = Image.new("RGB", (w * 2 + 8, (h + 8) * len(nums)), "black")
    for k, s in enumerate(nums):
        c.paste(Image.open(f"{d}/ours_{s}.png").convert("RGB"), (0, k * (h + 8)))
        c.paste(Image.open(f"{d}/lo_{s}.png").convert("RGB").resize((w, h)), (w + 8, k * (h + 8)))
    c.save(path)


order = [s for _, s in scores]
for i in range(0, len(order), per):
    sheet(order[i:i + per], f"{out}/sheet_{i // per + 1:02d}.png")
print("sheets:", (len(order) + per - 1) // per, "order:", order)
