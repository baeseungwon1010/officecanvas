# Architecture

```
OfficeCanvas.render(context, file)
        │  DocumentFormat.detect: zip with ppt/presentation.xml → PPTX
        │                         zip with Contents/content.hpf → HWPX
        │                         zip with word/document.xml → DOCX
        │                         OLE2 with a "PowerPoint Document" stream → PPT
        │                         OLE2 with an "HWP Document File" header → HWP
        │                         XML with an <HWPML> root → HWP (laid out here)
        ├── PPTX ── pptx.PptxParser ──┐
        ├── PPT ─── ppt.PptParser ────┼──► slide.Deck ──► slide.SlideRenderer ──► Bitmap per slide
        │                             │                        └── metafile.EmfRenderer / WmfRenderer
        ├── HWPX ── hwpx.HwpxParser ──┐
        ├── HWP ─── hwp.HwpParser ────┤
        ├── HWPML ─ hwp.HwpmlParser ──┤   (HWPML and DOCX laid out with hwpx.TextFlow)
        └── DOCX ── docx.DocxParser ──┴──► hwpx.HwpxDoc ──► hwpx.HwpxRenderer ──► Bitmap per page
```

Everything except `OfficeCanvas`, `DocumentFormat` and `OfficeCanvasException` is `internal`.

## Packages

**`slide`**: the model both slide parsers produce, and the renderer that draws it.
- `SlideModel.kt`: `Deck` → `Slide` → `Element` (`Shape`, `Picture`, `Table`). All geometry is in EMU (914400 per inch). Text is `TextBody` → `Paragraph` → `Run`. Parsing resolves every kind of inheritance (theme, layout, master, placeholder), so the renderer never looks anything up.
- `SlideRenderer.kt`: draws a `Deck` onto `android.graphics.Canvas`, one slide at a time. Text is laid out with `StaticLayout`, but line height, paragraph spacing and font sizes follow PowerPoint's rules rather than Android's defaults (see FORMAT_NOTES).
- `OfficeFonts.kt`: loads the bundled fonts and maps a document's font name to Carlito (Calibri), Liberation Sans, Serif or Mono.

**`pptx`**: the 2007+ XML format.
- `PptxParser`: presentation → slide list → each slide with its layout, master and theme; placeholders, backgrounds, groups, pictures, tables, SmartArt drawings.
- `Chart.kt` (`ChartParser`): a chart part laid out as ordinary shapes (bars as rectangles, lines and slices as outlines, labels as text boxes), so the renderer needs nothing chart-specific.
- `Theme`: the color scheme, color map, theme fonts and format scheme; color modifiers and fills, including style references.
- `Text`: list styles, paragraph and run properties, body properties, numbering.
- `Geometry`: preset shape formulas and custom geometry paths.
- `Xml`: namespaces and DOM helpers, with external entities disabled.

**`ppt`**: the binary PowerPoint 97–2003 format. Pictures stay in the file: the blip store reads a picture's record when it is drawn, and the deck keeps the file open until `OfficeCanvas` closes it (`Deck.resources`).
- `PptRecords`: record header parsing and navigation.
- `PptParser`: persist directory, slide list, masters, hidden slides; assembles a `Deck`.
- `PptShapes`: the OfficeArt ("Escher") shape tree: groups, anchors, fill, line, pictures, the picture store.
- `PptGeometry`: freeform paths and preset shape outlines.
- `PptText`: text atoms, character and paragraph formatting, master text styles, bullets, automatic numbering, tab rulers, symbol fonts.
- `PptColors`: color schemes and color references.

**`hwpx`**: the model both Hangul formats produce, and its renderer.
- `HwpxModel.kt`: pages of lines (each a list of styled spans with the line's position, width, baseline and alignment), tables (cells with borders, fill, vertical alignment and their own lines, tables and pictures), pictures (with crop) and drawing objects (outlines with fill, line and text box content; groups as drawings holding drawings), in HWPUNIT (7200 per inch) relative to the body area.
- `HwpxParser`: OWPML (the HWPX XML).
- `PageFlow`: splits a section's positioned elements into pages and continues tables and floating objects on the next page.
- `Equation.kt` (`EquationParser`): Hangul's equation scripts as a tree; `EquationLayout`: that tree typeset with Android's text drawing, fitted to the box Hangul saved.
- `Numbering.kt`: paragraph numbers and bullets, counted through the document and put in front of a paragraph's first line.
- `PageDecor`: headers, footers and the page number for each page (odd and even, changing where their controls appear), and where an object placed relative to its paragraph goes.
- `HwpxRenderer`: draws a page, placing spans by alignment within the width Hangul saved for the line.

**`hwp`**: `HwpParser` reads the binary Hangul 5.0 format (records in OLE2 streams) into the same model; pictures stay in the file until drawn, as in `.ppt` (`HwpxDoc.resources`). `HwpmlParser` reads HWPML, the older XML form of `.hwp`; it saves no layout, so it breaks lines itself, measuring spans with `HwpxRenderer.widthHu` (the faces and sizes the renderer draws with), and pages them through `PageFlow`.

**`hwpx/TextFlow`**: line and page layout for formats that save none (HWPML, DOCX): text broken into atoms (words, or single Hangul characters where those may break), measured with `HwpxRenderer.widthHu`, filled into lines (tab stops, a word wider than a line broken between characters), stacked at the paragraph's line spacing (Hangul's percent, or Word's multiple of a font's single line), and paged through `PageFlow` with `laidOut` set, so pages break only where `TextFlow` breaks them.

**`docx`**: `DocxParser` reads WordprocessingML: styles (document defaults, table style, paragraph and character style chains), numbering, tab stops, paragraph borders and shading, tables, pictures (read from the package when drawn; the zip stays open in `HwpxDoc.resources`), shapes, text boxes and groups (DrawingML through `mc:AlternateContent`, or VML), page breaks, sections, headers and footers, footnotes and endnotes, and lays it all out with `TextFlow` into the Hangul model. Floating objects are placed after their paragraph, on the page it landed on (`PageFlow.put`), unless text wraps them, when they go among the paragraph's lines. `Omml` turns Office Math into the `EqNode` tree that `EquationLayout` typesets for Hangul's equations.

**`ole`**: `Ole2Reader`, the OLE2 / Compound File container shared by `.ppt` and `.hwp` (FAT, mini-FAT, storage tree). It reads streams from the file when asked for rather than loading the file whole; close it when done.

**`metafile`**: players for the vector picture formats Office uses, which `BitmapFactory` can't decode: `EmfRenderer` (EMF), `WmfRenderer` (WMF), `SvgRenderer` (the SVG subset of Office icons), plus `TiffDecoder` (TIFF to BMP) and `MetaText` (text drawn inside metafiles).

**`util`**: `SafeXml` (XML parsing for untrusted parts: DTDs refused, hardening features requested one at a time because Android's parser rejects the ones it doesn't know, parts capped at 32 MB), `SafeBitmaps` (raster decoding with a pixel budget) and `Limits` (pages, nesting, input size).

## Memory

Each renderer draws a page, hands the bitmap to the caller and moves on. A 60-slide deck at 1600 px would be around 350 MB of bitmaps if they were collected into a list first. Large embedded photos are decoded with `inSampleSize` close to their drawn size.

## Adding a feature

1. Find out what the file really stores before writing code: `tools/pptdump.py` for `.ppt` records, `tools/hwpdump.py` for `.hwp` records, `tools/wmfdump.py` for metafile records, or unzip a `.pptx` / `.hwpx`.
2. Put a slide that uses the feature into a generator (`tools/make_pptx_features.py` for `.pptx` features, `tools/make_feature_deck.py` for features of the `.ppt` path) and regenerate the fixtures with `tools/build_fixtures.sh`.
3. Extend the parser so the feature ends up in the model, and the renderer if the model needs a new field.
4. Check the result against LibreOffice with `tools/compare.sh`, and add an assertion to the matching test.
