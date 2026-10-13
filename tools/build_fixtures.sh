#!/usr/bin/env bash
# Regenerates every test document under officecanvas/src/test/resources from the
# generator scripts in this directory. The .ppt files are LibreOffice's binary
# export of the generated .pptx files and shapes.emf is its export of an SVG,
# so LibreOffice must be installed (SOFFICE=/path/to/soffice to point at a
# specific one).
# Needs: python-pptx, Pillow, lxml.
set -euo pipefail
cd "$(dirname "$0")"
RES=../officecanvas/src/test/resources
SOFFICE="${SOFFICE:-soffice}"
mkdir -p "$RES/pptx_samples" "$RES/ppt_samples" "$RES/hwpx_samples" "$RES/emf_samples"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

python3 make_sample_deck.py "$tmp"
python3 make_feature_deck.py "$tmp"
python3 make_sample_hwpx.py "$RES/hwpx_samples"
python3 make_small_fixtures.py "$RES" "$tmp"

cp "$tmp/sample_deck.pptx" "$tmp/feature_test.pptx" "$RES/pptx_samples/"
"$SOFFICE" --headless --norestore --convert-to ppt --outdir "$tmp" "$tmp/sample_deck.pptx" "$tmp/feature_test.pptx" >/dev/null
cp "$tmp/sample_deck.ppt" "$tmp/feature_test.ppt" "$RES/ppt_samples/"
"$SOFFICE" --headless --norestore --convert-to emf --outdir "$tmp" "$tmp/shapes.svg" >/dev/null
cp "$tmp/shapes.emf" "$RES/emf_samples/"
ls -l "$RES"/*_samples
