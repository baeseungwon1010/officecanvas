# Testing

```
./gradlew :officecanvas:testDebugUnitTest
```

The tests run on the JVM with Robolectric in `NATIVE` graphics mode, which uses the real Skia drawing code, so a test can read back the pixels a renderer produced. The default `LEGACY` mode only records draw calls and leaves bitmaps blank.

## Test documents

Every document under `officecanvas/src/test/resources` is generated, so the repository carries no third-party content and each file's contents are known:

| File | Made by | What it covers |
|---|---|---|
| `pptx_samples/sample_deck.pptx` | `tools/make_sample_deck.py` (python-pptx) | an ordinary deck: background color, a full-width sidebar, 18 pt and 48 pt Korean titles, cards, a table, a picture at (1 in, 2 in), a dark closing slide |
| `ppt_samples/sample_deck.ppt` | LibreOffice export of the above | the same content through the binary format |
| `pptx_samples/pptx_features.pptx` | `tools/make_pptx_features.py` (python-pptx plus hand-written XML) | `.pptx` inheritance and drawing: theme style references and color modifiers, master background and placeholder position, connectors with arrowheads, preset outlines, numbering, superscript, no-wrap and shrunk text, fields, picture crop, an SVG-only icon, `mc:AlternateContent`, the default table style, a hidden slide |
| `pptx_samples/feature_test.pptx`, `ppt_samples/feature_test.ppt` | `tools/make_feature_deck.py` | one slide per `.ppt` feature: numbering, freeform and preset outlines, gradients, cropping, TIFF, sub/superscript, monospace text |
| `hwpx_samples/sample.hwpx` | `tools/make_sample_hwpx.py` | three A4 pages of Korean text with headings and a table, with line positions computed the way Hangul saves them |
| `emf_samples/shapes.emf` | LibreOffice export of an SVG from `tools/make_small_fixtures.py` | paths, polygons, pens and brushes |
| `tiff_samples/*.tif` | `tools/make_small_fixtures.py` (Pillow) | the same image in each compression and color layout the decoder supports |

Regenerate them all with:

```
SOFFICE=/path/to/soffice tools/build_fixtures.sh
```

It needs python-pptx, Pillow and lxml, and LibreOffice for the `.pptx` → `.ppt` and SVG → EMF steps.

The `.hwpx` generator lays text out with fixed metrics instead of a real layout engine, so it exercises the parser's reading of saved line positions, page breaks and margins, but not Hangul's own layout decisions. It has not been opened in Hangul.

## Comparing against LibreOffice

```
SOFFICE=/path/to/soffice tools/compare.sh path/to/deck.ppt [outdir]
```

This renders the document with OfficeCanvas (through the `RenderFileTool` test, which runs only when given `-Pofficecanvas.input`) and with LibreOffice at the same size, then prints:

- a per-page difference score, worst first, and writes side-by-side sheets (OfficeCanvas left, LibreOffice right) with `compare_render.py`
- for slide decks, how far each text line's baseline is from the same line in LibreOffice's PDF, in points, with `compare_baselines.py`

Compare at the size the library renders by default (1600 px). At much smaller sizes pixel rounding dominates the numbers, and shrink-on-overflow can trigger where it shouldn't.

On the generated sample deck, every baseline is within 0.6 pt of LibreOffice's. On a real 75-slide `.ppt` lecture deck, 66 slides have every baseline within 6 pt; most of the rest differ by a few points in regions with many empty lines.

For `.pptx`, `RenderFileTool` also accepts a directory (`-Pofficecanvas.input=<dir>`) and renders every document in it into its own subdirectory, which makes it practical to score a whole set of decks after each change. On 15 real decks (240 slides) the mean difference score is 2.0; the largest remaining differences are charts, 3-D effects and Hangul set in different fallback fonts.

## Looking inside a file

- `tools/pptdump.py deck.ppt [slide]`: each slide's shapes with their type, FOPT properties (including the sizes of complex ones), picture types and PPT9 tags.
- `tools/wmfdump.py deck.ppt <pib> [max]`: the records of one embedded WMF or EMF picture, by its 1-based index in the picture store.
- `.pptx` and `.hwpx` are zip files; unzip them and read the XML.
