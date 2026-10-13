# Architecture

```
OfficeCanvas.render(context, file)
        │  DocumentFormat.detect: zip with ppt/presentation.xml → PPTX
        │                         zip with Contents/content.hpf → HWPX
        │                         OLE2 with a "PowerPoint Document" stream → PPT
        ├── PPTX ── pptx.PptxParser ──┐
        ├── PPT ─── ppt.PptParser ────┼──► slide.Deck ──► slide.SlideRenderer ──► Bitmap per slide
        │                             │                        └── metafile.EmfRenderer / WmfRenderer
        └── HWPX ── hwpx.HwpxParser ──► hwpx.HwpxDoc ──► hwpx.HwpxRenderer ──► Bitmap per page
```

Everything except `OfficeCanvas`, `DocumentFormat` and `OfficeCanvasException` is `internal`.

## Packages

**`slide`**: the model both slide parsers produce, and the renderer that draws it.
- `SlideModel.kt`: `Deck` → `Slide` → `Element` (`Shape`, `Picture`, `Table`). All geometry is in EMU (914400 per inch). Text is `TextBody` → `Paragraph` → `Run`. Parsing resolves every kind of inheritance (theme, layout, master, placeholder), so the renderer never looks anything up.
- `SlideRenderer.kt`: draws a `Deck` onto `android.graphics.Canvas`, one slide at a time. Text is laid out with `StaticLayout`, but line height, paragraph spacing and font sizes follow PowerPoint's rules rather than Android's defaults (see FORMAT_NOTES).
- `OfficeFonts.kt`: loads the bundled fonts and maps a document's font name to Carlito (Calibri), Liberation Sans, Serif or Mono.

**`pptx`**: the 2007+ XML format.
- `PptxParser`: presentation → slide list → each slide with its layout, master and theme; placeholders, backgrounds, groups, pictures, tables, SmartArt drawings.
- `Theme`: the color scheme, color map, theme fonts and format scheme; color modifiers and fills, including style references.
- `Text`: list styles, paragraph and run properties, body properties, numbering.
- `Geometry`: preset shape formulas and custom geometry paths.
- `Xml`: namespaces and DOM helpers, with external entities disabled.

**`ppt`**: the binary PowerPoint 97–2003 format.
- `Ole2Reader`: the OLE2 / Compound File container (FAT and mini-FAT chains).
- `PptRecords`: record header parsing and navigation.
- `PptParser`: persist directory, slide list, masters, hidden slides; assembles a `Deck`.
- `PptShapes`: the OfficeArt ("Escher") shape tree: groups, anchors, fill, line, pictures, the picture store.
- `PptGeometry`: freeform paths and preset shape outlines.
- `PptText`: text atoms, character and paragraph formatting, master text styles, bullets, automatic numbering, tab rulers, symbol fonts.
- `PptColors`: color schemes and color references.

**`hwpx`**: `HwpxParser` reads OWPML (the HWPX XML) into pages of positioned text runs, tables and pictures; `HwpxRenderer` draws them.

**`metafile`**: players for the vector picture formats Office uses, which `BitmapFactory` can't decode: `EmfRenderer` (EMF), `WmfRenderer` (WMF), `SvgRenderer` (the SVG subset of Office icons), plus `TiffDecoder` (TIFF to BMP) and `MetaText` (text drawn inside metafiles).

## Memory

Each renderer draws a page, hands the bitmap to the caller and moves on. A 60-slide deck at 1600 px would be around 350 MB of bitmaps if they were collected into a list first. Large embedded photos are decoded with `inSampleSize` close to their drawn size.

## Adding a feature

1. Find out what the file really stores before writing code: `tools/pptdump.py` for `.ppt` records, `tools/wmfdump.py` for metafile records, or unzip a `.pptx` / `.hwpx`.
2. Put a slide that uses the feature into a generator (`tools/make_pptx_features.py` for `.pptx` features, `tools/make_feature_deck.py` for features of the `.ppt` path) and regenerate the fixtures with `tools/build_fixtures.sh`.
3. Extend the parser so the feature ends up in the model, and the renderer if the model needs a new field.
4. Check the result against LibreOffice with `tools/compare.sh`, and add an assertion to the matching test.
