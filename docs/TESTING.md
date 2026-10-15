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
| `pptx_samples/pptx_features.pptx` | `tools/make_pptx_features.py` (python-pptx plus hand-written XML) | `.pptx` inheritance and drawing: theme style references and color modifiers, master background and placeholder position, connectors with arrowheads, preset outlines, numbering, superscript, no-wrap and shrunk text, fields, picture crop, an SVG-only icon, `mc:AlternateContent`, the default table style, a hard shadow, an empty effect list, highlighted text, a hidden slide |
| `pptx_samples/charts.pptx` | `tools/make_chart_deck.py` (python-pptx) | one chart per slide: clustered columns with value labels, a fixed axis, gridlines, a legend and a title; stacked bars; a line with markers; a pie with a point color and percent labels; a doughnut |
| `pptx_samples/feature_test.pptx`, `ppt_samples/feature_test.ppt` | `tools/make_feature_deck.py` | one slide per `.ppt` feature: numbering, freeform and preset outlines, gradients, cropping, TIFF, sub/superscript, monospace text |
| `hwpx_samples/sample.hwpx` | `tools/make_sample_hwpx.py` | three A4 pages of Korean text with headings and a table, with line positions computed the way Hangul saves them; a header with a tab and the page number field, a page number, a floating text box, a line, a cropped picture, numbered and bulleted paragraphs, and an equation in a line of text |
| `hwp_samples/sample.hwp` | `tools/make_sample_hwp.py` (records and the OLE2 container written directly) | the same text as a binary `.hwp`, plus a styled centered title, a filled and vertically centered table cell, a cropped picture, a header with a tab and the page number field, a page number with side characters, a floating text box, a line, a page-break flag, numbered and bulleted paragraphs, an equation in a line of text, and a cell that leaves its margins to the table |
| `emf_samples/shapes.emf` | LibreOffice export of an SVG from `tools/make_small_fixtures.py` | paths, polygons, pens and brushes |
| `tiff_samples/*.tif` | `tools/make_small_fixtures.py` (Pillow) | the same image in each compression and color layout the decoder supports |

Regenerate them all with:

```
SOFFICE=/path/to/soffice tools/build_fixtures.sh
```

It needs python-pptx, Pillow and lxml, and LibreOffice for the `.pptx` → `.ppt` and SVG → EMF steps.

The Hangul generators lay text out with fixed metrics instead of a real layout engine, so they exercise the parsers' reading of saved line positions, page breaks and margins, but not Hangul's own layout decisions. Neither file has been opened in Hangul; `sample.hwp` reads with olefile and `tools/hwpdump.py`, but LibreOffice's H2Orestart filter refuses it, so it may lack records that filter expects.

## Hostile input

`MaliciousInputTest` feeds crafted inputs, each a few bytes to about a megabyte, that once crashed, hung or exhausted memory: OLE2 headers with absurd FAT sizes and looping chains, a negative count in a PPT9 atom, a WMF record whose length wraps to zero, EMF and TIFF counts in the billions, SVG nested 10,000 deep, a PNG of 100,000 x 4,000 pixels, an HWPX table declaring 50,000 x 50,000 cells, HWP records nested 1,000 deep or claiming 2 GB, an HWP table declaring 65,535 x 65,535 cells, HWP drawing groups nested 1,000 deep and a polygon claiming two billion points with infinite matrices, HWPX drawing containers nested 5,000 deep and a polygon of 100,000 points with a relative width in the billions, random bytes as HWP streams, one slide listed 50,000 times, a chart claiming two billion points in a thousand series, and parts with a DTD. Each must finish within seconds on a thread with a 1 MB stack, and anything thrown out of `OfficeCanvas.render` must be an `OfficeCanvasException`.

`AndroidXmlParserTest` parses the sample documents with Android's own XML parser (shipped in Robolectric's android-all), because the JDK parser unit tests otherwise use accepts hardening options that Android's rejects.

## Rendering on a device

Robolectric draws with the JVM's Skia, but a device has its own fonts (Hangul comes from the system's Noto CJK), text shaping and memory limits. `DeviceRenderTool` (in `androidTest`) renders documents on a connected device or emulator and writes the pages as PNGs:

```
ANDROID_SERIAL=emulator-5554 tools/device_render.sh outdir doc.hwp deck.pptx ...
```

The script installs the test APK, pushes the documents into its external files directory, runs the tool and pulls `outdir/<name>/ours_N.png` back, with each document's render time (not counting saving the PNGs) in `outdir/TIMES`. The tool is skipped when given nothing to render, so `connectedAndroidTest` stays a no-op. On an Android 14 x86_64 emulator (software rendering), a 75-slide `.ppt` lecture takes about 8 s and a 68-page `.hwp` about 6 s.

## Comparing against LibreOffice

```
SOFFICE=/path/to/soffice tools/compare.sh path/to/deck.ppt [outdir]
```

This renders the document with OfficeCanvas (through the `RenderFileTool` test, which runs only when given `-Pofficecanvas.input`) and with LibreOffice at the same size, then prints:

- a per-page difference score, worst first, and writes side-by-side sheets (OfficeCanvas left, LibreOffice right) with `compare_render.py`
- for slide decks, how far each text line's baseline is from the same line in LibreOffice's PDF, in points, with `compare_baselines.py`

For `.hwp` and `.hwpx`, LibreOffice needs the H2Orestart import filter; set `LO_PROFILE` to the LibreOffice user profile it is installed in. That filter lays text out again instead of using Hangul's saved line positions, so expect differences in line breaks even where OfficeCanvas follows Hangul.

Compare at the size the library renders by default (1600 px). At much smaller sizes pixel rounding dominates the numbers, and shrink-on-overflow can trigger where it shouldn't.

On the generated sample deck, every baseline is within 0.6 pt of LibreOffice's. On a real 75-slide `.ppt` lecture deck, 66 slides have every baseline within 6 pt; most of the rest differ by a few points in regions with many empty lines.

For `.pptx`, `RenderFileTool` also accepts a directory (`-Pofficecanvas.input=<dir>`) and renders every document in it into its own subdirectory, which makes it practical to score a whole set of decks after each change. On 15 real decks (240 slides) the mean difference score is 2.0; the largest remaining differences are charts, 3-D effects and Hangul set in different fallback fonts.

## Looking inside a file

- `tools/pptdump.py deck.ppt [slide]`: each slide's shapes with their type, FOPT properties (including the sizes of complex ones), picture types and PPT9 tags.
- `tools/hwpdump.py doc.hwp [docinfo | section N] [max]`: the records of a `.hwp` stream, with text, line positions, character runs, control ids and page definitions decoded.
- `tools/wmfdump.py deck.ppt <pib> [max]`: the records of one embedded WMF or EMF picture, by its 1-based index in the picture store.
- `.pptx` and `.hwpx` are zip files; unzip them and read the XML.
