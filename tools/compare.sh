#!/usr/bin/env bash
# Renders a document with OfficeCanvas and with LibreOffice, then compares them:
# a per-page difference score plus side-by-side sheets (compare_render.py) and,
# for slide decks, how far each text baseline sits from LibreOffice's
# (compare_baselines.py). LibreOffice is only a reference; it isn't PowerPoint.
# Usage: tools/compare.sh <document> [outdir]   (SOFFICE=/path/to/soffice)
# Needs: PyMuPDF, Pillow.
set -euo pipefail
doc="$(realpath "$1")"
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$(realpath -m "${2:-$root/build/compare/$(basename "$doc")}")"
SOFFICE="${SOFFICE:-soffice}"
mkdir -p "$out"

# Same long side the library renders at by default: comparing much smaller
# renders makes pixel rounding dominate.
(cd "$root" && ./gradlew -q :officecanvas:testDebugUnitTest --tests '*RenderFileTool*' \
    -Pofficecanvas.input="$doc" -Pofficecanvas.output="$out" -Pofficecanvas.longSide=1600)

"$SOFFICE" --headless --norestore --convert-to pdf --outdir "$out" "$doc" >/dev/null
pdf="$out/$(basename "${doc%.*}").pdf"
python3 - "$pdf" "$out" <<'PY'
import sys, fitz
pdf, out = sys.argv[1], sys.argv[2]
for i, page in enumerate(fitz.open(pdf), 1):
    zoom = 1600 / max(page.rect.width, page.rect.height)
    page.get_pixmap(matrix=fitz.Matrix(zoom, zoom), alpha=False).save(f"{out}/lo_{i}.png")
PY

mkdir -p "$out/sheets"
python3 "$(dirname "$0")/compare_render.py" "$out" "$out/sheets"
if [ -f "$out/ours_base.tsv" ]; then
    python3 "$(dirname "$0")/compare_baselines.py" "$out/ours_base.tsv" "$pdf"
fi
echo "side-by-side sheets: $out/sheets"
