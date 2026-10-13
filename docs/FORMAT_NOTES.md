# Format notes

What the files contain, as found by reading real files byte by byte, and the places where the specifications or intuition led somewhere wrong. Each note says how it was confirmed. "LibreOffice" below means comparing against LibreOffice's PDF export of the same file.

## PPTX

A zip of XML parts. Every shape has an absolute position (`<a:xfrm>`, in EMU), so there is no layout to compute; the work is resolving inheritance. Checked against LibreOffice on 15 real decks (see TESTING).

- **Inheritance chain.** A slide uses a layout, the layout a master, the master a theme. A slide placeholder matches the layout placeholder with the same `idx` (or, without one, the same type), and that one the master placeholder of the same kind (title, body, date, footer, slide number; subtitles and content placeholders map to body). Position, geometry, fill, outline, body properties (`<a:bodyPr>` anchor, insets, wrap, autofit) and list styles are each taken from the nearest level that sets them. Most placeholders on real slides store no position at all, and a title placeholder's bottom anchor usually lives on the master, so skipping either level drops or misplaces text.
- **Text styles, nearest first:** the run, the paragraph, the shape's own `<a:lstStyle>`, the layout placeholder's, the master placeholder's, then the master's `titleStyle`, `bodyStyle` or `otherStyle`. Text boxes that aren't placeholders use the presentation's `defaultTextStyle`.
- **Backgrounds** come from the slide, else the layout, else the master: `<p:bgPr>` with a solid, gradient or picture fill, or `<p:bgRef idx>` into the theme's background fill styles (idx 1001 and up). A picture background is resolved against the relationships of the part that declares it.
- **Colors.** `<a:schemeClr>` goes through the master's `<p:clrMap>` (overridable per layout and slide with `<p:clrMapOvr>`), so a dark master that maps `bg1` to `dk1` comes out dark. Modifiers apply in document order: `lumMod`/`lumOff`/`satMod` in HSL (PowerPoint's "darker 25%" theme variants are `lumMod 75000`), `tint`/`shade` mixed toward white or black in linear light, plus `alpha`. `sysClr`, `prstClr`, `scrgbClr` and `hslClr` are read too.
- **Shape styles.** Shapes inserted with PowerPoint's default style name no fill or outline at all; `<p:style>` refers into the theme's format scheme instead (`<a:fillRef idx="3"><a:schemeClr val="accent1"/>` = the third fill style, with `phClr` standing for accent1). An explicit fill or outline in `<p:spPr>` wins, then the placeholder chain's, then the style's. `<a:fontRef>`'s color is the text color of last resort.
- **Text.** Shrink-on-overflow (`<a:normAutofit fontScale="62500" lnSpcReduction="20000">`) stores the scale PowerPoint computed, which is applied as is (the renderer may shrink a little further where substitute fonts run longer). Bullets and numbers are not shown on empty paragraphs. `<a:buAutoNum>` numbers like the `.ppt` path; percent spacing before and after is measured like the `.ppt` path; `<a:fld>` (slide numbers, dates) shows its saved text; hyperlinks without their own color take the theme's `hlink` color and an underline.
- **Geometry.** Preset shapes are drawn from the specification's formulas, honoring adjust values, which are mostly fractions of the shorter side, so an arrow's head depends on the box's aspect ratio. Connectors (`<p:cxnSp>`) are straight, elbow or curved, with arrowheads at either end. `<a:custGeom>` paths (lines, cubic and quadratic Béziers, `arcTo`) are converted to cubic outlines; paths marked `fill="none"` are stroked only.
- **SVG icons.** Office 365 inserts icons as `<asvg:svgBlip>`, often with no raster fallback in `r:embed`. They're drawn by a small SVG player (paths, basic shapes, transforms, `<style>` class rules, which override presentation attributes).
- **Picture formats are told by content.** A `.emf` part can be a WMF without a placeable header; its extent then comes from its own `SetWindowOrg`/`SetWindowExt` records. A PNG can carry "<svg" inside C2PA metadata, so SVG is only recognized by its root element.
- **SmartArt** is stored as data plus layout rules, but PowerPoint also saves the shapes it drew: the data part's `<dsp:dataModelExt relId>` names a drawing part, whose shapes (in the frame's coordinate space, with their own text rectangles in `<dsp:txXfrm>`) are drawn as ordinary shapes.
- **Tables** take cell fills and text color and weight from their table style (`ppt/tableStyles.xml`; the header row, banded rows, first/last column flags). PowerPoint's default style is referenced by id without being stored, so it's built in.
- **`mc:AlternateContent`** (equations, newer content) is drawn from its `Fallback` branch, usually a picture.
- **Hidden slides** (`<p:sld show="0">`) are skipped.
- **Not read.** Charts (LibreOffice draws them from the cached data; nothing here does yet), shadows, glow, soft edges, 3-D rotation and bevels, text highlight, strikethrough, `phClr` outside style references, preset shapes outside the list (drawn as rectangles or ellipses).

## PPT (PowerPoint 97–2003)

An OLE2 compound file. The streams that matter are `PowerPoint Document` (records), `Current User` (where the newest edit is) and `Pictures` (embedded images). Records follow MS-PPT for the document structure and MS-ODRAW ("Escher") for shapes. Coordinates are master units, 1/576 inch, which is 1587.5 EMU; a 16:9 slide stores 7680 × 4320.

### Structure

- **Incremental saves.** Each save appends a `UserEditAtom` with its own `PersistDirectoryAtom` and a link to the previous one. Start from `CurrentUserAtom.offsetToCurrentEdit` and keep the first offset seen for each persist id; that is the current version of each object.
- **Slide list.** `SlideListWithText` instance 0 lists slides in order, instance 1 masters, instance 2 notes. Using any other instance draws masters or notes pages as slides.
- **Masters are found by slide id, not persist id.** `SlideAtom.masterIdRef` matches the `slideId` field of a `SlidePersistAtom` in the master list. Looking it up in the persist directory finds the wrong object.
- **What a slide inherits.** `SlideAtom` flags say whether the slide follows its master's objects (logos, bars, footers), color scheme and background. A master can be a title master that in turn follows a main master; text styles always come from the main master's `TextMasterStyleAtom`s.
- **The drawing group is not where its name suggests.** `DggContainer` (default shape properties and the picture store) is inside record `0x040B` (DrawingGroup), not a direct child of the document. The `Environment` container, `0x03F2`, holds the font list and the document's default text formatting.
- **Hidden slides** have bit 2 set in `SSSlideInfoAtom` at offset 10. They are skipped, as PowerPoint's and LibreOffice's PDF export do.

### Shapes

- **`OfficeArtClientAnchor` is (top, left, right, bottom)**, not the (left, top, right, bottom) of a Win32 `RECT`. With the wrong order every shape on a slide lands on the same y. Found by converting a `.ppt` back to `.pptx` and lining up coordinates.
- **Shapes in groups** use a `ChildAnchor` in the group's own coordinate space, given by the group's `FSPGR` rectangle, which maps onto the group's anchor.
- **Text placement inside a shape** depends on the vertical anchor (FOPT property `0x87`). Ignoring it stacks title and subtitle text at the top of their shared box.
- **Default fill and line.** A shape that doesn't set fill or line takes them from the drawing group's default FOPT. Placeholders and text boxes don't: their unset values mean "none", which is what they inherit from the master's placeholder.
- **Complex FOPT properties** (vertex and segment arrays of freeforms) are stored after all the fixed 6-byte entries, in the same order. Some writers (LibreOffice) give an array's length without its own 6-byte header: when `nElems × cbElem` equals the declared length, read 6 more bytes.
- **Gradients.** Linear fills go from `fillColor` to `fillBackColor` in screen direction `90 − fillAngle`. `fillFocus = -50` means axial: `fillColor` at both ends, the back color in the middle. A slide background gradient is stored in the opposite direction. Only calibrated against LibreOffice, since no PowerPoint-made gradient sample was available.

### Colors

- A slide or master has eight scheme colors (background, text, shadow, title, fills, accent, two hyperlink accents); the active set is the `ColorSchemeAtom` with instance 1.
- Shape colors (`OfficeArtCOLORREF`) are RGB, or a scheme index when flag `0x08` is set in the high byte.
- Text colors (`ColorIndexStruct`) are RGB when the last byte is `0xFE`, a scheme index when it is below 8.

### Text

- **Formatting runs.** `StyleTextPropAtom` holds paragraph runs then character runs, each counting one character more than the text atom stores (the implied final paragraph mark).
- **Extension mask bits carry no data here.** In `TextCFException`, the `pp10ext`, `newEATypeface`, `csTypeface` and `pp11ext` mask bits announce fields of `TextCFException10`, which lives in extension records; nothing for them is inline (Apache POI reads them the same way). An older version of this parser gave up on the whole atom when it saw them, so most real files came out as plain 18 pt black text.
- **Master text styles.** A placeholder stores only what differs from the master's `TextMasterStyleAtom` for its text type (title, body, other, center title, …) and indent level. Without them titles lose their size, color and alignment, and bullets disappear.
- **Automatic numbering is in a PowerPoint 2000 extension.** The shape's `ClientData` holds `ProgTags` → a `ProgBinaryTag` named `___PPT9` → `BinaryTagData` → `StyleTextProp9Atom`, an array of {`TextPFException9`, `TextCFException9`, `TextSIException`}. A paragraph's entry is picked by `pp9rt`, bits 10–13 of `fontStyle` in the character run at the paragraph's start. Numbers continue per indent level and restart when a non-numbered paragraph at the same or a shallower level comes between.
- **Bullets are characters in symbol fonts**: Monotype Sorts `n` is ■, `l` is ●; Webdings `4` is ▸; Wingdings has its own table. They're mapped to the Unicode shapes they draw. Text typed in a symbol font, or stored as private-use code points U+F0xx, is translated the same way, using the run's symbol font (`symbolFontRef`) for U+F0xx characters.
- **Tabs.** `TextRulerAtom` gives tab stops, the default tab width and per-level margins. The hanging-indent position itself acts as the first tab stop, as in PowerPoint and Word; without that, columns of tab-separated text shift by one stop.
- **Shrink on overflow** has no on/off flag in the 97–2003 records. The renderer applies it to non-title placeholders, only when the text really overflows (more than 1.5 px, so rounding doesn't count), down to 50%, and ignores trailing empty paragraphs (decks often leave blank lines where a picture goes). These rules separate the slides LibreOffice shrinks from the ones it doesn't on a 75-slide lecture deck. They only work because font metrics match (see Rendering).

### Pictures

- A picture shape's `pib` property is a 1-based index into the drawing group's blip store; each `FBSE` entry's `foDelay` is an offset into the `Pictures` stream.
- Raster blips start with one or two 16-byte GUIDs and sometimes a tag byte. Scanning for the format's magic bytes is simpler than decoding which of those are present.
- Metafile blips (EMF, WMF) have a 34-byte header and are usually zlib-compressed. A WMF blip has no placeable header of its own; one is built from the blip header's `rcBounds`.
- DIB blips are bitmaps without a file header; prefixing a BMP header makes them decodable. TIFF is converted to BMP (uncompressed, LZW, PackBits). PICT is only read when it wraps a JPEG or PNG.

## HWPX

A zip of XML (OWPML). `Contents/content.hpf` lists the parts and their order; `Contents/header.xml` defines character shapes; each `Contents/sectionN.xml` holds the text.

- **Hangul saves its own layout.** Every paragraph has `<hp:linesegarray>`: for each line, the character offset where it starts (`textpos`) and its position and size (`vertpos`, `horzpos`, `horzsize`, `textheight`). A new page shows up as `vertpos` dropping back to 0. Checked on a real three-page document: the paragraph LibreOffice puts at the top of page 3 is the one whose first line resets to 0.
- **Positions are relative to the body area**, which starts at the left margin plus gutter and below the top margin plus the header area (`<hp:pagePr><hp:margin>` in the first paragraph's `<hp:secPr>`). Confirmed against LibreOffice's text positions.
- **Use the paragraph's own line array.** A paragraph holding a table also contains the line arrays of the table's cell paragraphs. Searching recursively finds one of those first and puts the table at that cell line's position.
- **Table cell sizes are measured, not trusted.** `<hp:cellSz>` is a declared minimum that real content often outgrows; one sample declared 282 HWPUNIT (under half a line) for a six-line answer. Cell sizes come from the extent of their text's line positions instead.
- **Not handled.** Mixed formatting within one line is drawn in the style at the line's start. Tables and pictures inside table cells, headers and footers, and paragraph numbering (generated by Hangul, not stored as text) are not drawn. Row and column spans are approximated.

## Rendering

These are not stored in the files; they are where Android's text defaults differ from PowerPoint's and had to be measured.

- **Fonts.** Line breaks only match when glyph widths match. Liberation Sans, Serif and Mono have the metrics of Arial/Helvetica, Times New Roman and Courier New. With Android's Roboto, lines came out about 5% shorter, so text wrapped elsewhere and looked smaller.
- **Exact sizes and widths.** Font sizes keep their fractions (Android's `AbsoluteSizeSpan` truncates to whole pixels), and text is measured unhinted with subpixel positioning. Hinted widths round each glyph to a whole pixel, which made lines about 4% longer.
- **Line height.** Every line, including a paragraph's first, is `largest font size on the line × 1.2 × line spacing %`, carrying fractions into the next line. A subscript that drops below the font's descent makes its line taller. Space before and after a paragraph given in percent is `size × 1.25 × %`. Both were measured from text baselines in LibreOffice's PDF output (14 pt at 90% → 15.12 pt between baselines). `StaticLayout` instead uses the font's own height (1.117 em) and applies the multiplier only between lines.
- **Bullets as shapes.** ●, ■, ◆ and ► are drawn as shapes rather than glyphs, because a device's fallback font draws ● much smaller than Office does. Number bullets use the paragraph's font and weight.
- **Large pictures.** A 2000 px diagram drawn into a 260 px box is decoded with `inSampleSize` and then halved step by step. One bilinear downscale made thin lines and small text disappear.
- **Text inside metafiles.** Symbol-font characters are translated, per-character spacing arrays (`dx`) are honored, and a positive font height is a cell height (divided by 1.15 for the em size). Equations stored as WMF (τ, α, ≤) only came out right with all three.
- **EMF and WMF playback** covers paths, polygons, polylines, Béziers, rectangles, ellipses, pens, brushes, fonts, text, embedded DIBs and the window, viewport and world transforms. Clipping, raster operations, hatch patterns and EMF+ records are ignored.
