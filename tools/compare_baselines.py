"""Dev tool: matches each text line our renderer drew (ours_base.tsv: slide, baseline
in points, line start) to the same line in LibreOffice's PDF of the deck and
reports the vertical offset (ours - LibreOffice, in points) per slide.
Usage: python3 compare_baselines.py ours_base.tsv deck.pdf [slide...]"""
import sys
from collections import defaultdict

import fitz

ours = defaultdict(list)
for line in open(sys.argv[1], encoding="utf-8"):
    n, y, t = line.rstrip("\n").split("\t", 2)
    ours[int(n)].append((float(y), t))
doc = fitz.open(sys.argv[2])
only = {int(a) for a in sys.argv[3:]}


def norm(s):
    return "".join(ch for ch in s if ch.isalnum())[:12]


summary = []
for n in sorted(ours):
    if only and n not in only:
        continue
    page = doc[n - 1]
    lo = []
    for b in page.get_text("dict")["blocks"]:
        for l in b.get("lines", []):
            text = "".join(s["text"] for s in l["spans"])
            if l["spans"]:
                lo.append((l["spans"][0]["origin"][1], text))
    diffs = []
    used = set()
    for y, t in ours[n]:
        key = norm(t)
        if len(key) < 5:
            continue
        for i, (ly, lt) in enumerate(lo):
            if i not in used and norm(lt).startswith(key[:8]):
                used.add(i)
                diffs.append((round(y - ly, 1), t[:28]))
                break
    if diffs:
        vals = [d for d, _ in diffs]
        summary.append((n, len(vals), round(sum(vals) / len(vals), 1), max(vals, key=abs)))
        if only:
            for d in diffs:
                print(n, d)
for s in summary:
    print("slide %d: %d lines, mean %+.1fpt, worst %+.1fpt" % s)
