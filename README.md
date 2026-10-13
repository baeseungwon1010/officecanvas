# OfficeCanvas

Render PowerPoint (`.pptx`, `.ppt`) and Hangul (`.hwpx`) documents to Android `Bitmap`s, on the device, in plain Kotlin. No native code, no server, no dependencies beyond the Android SDK.

![Slides rendered by OfficeCanvas next to LibreOffice's rendering of the same .ppt](docs/images/ppt_vs_libreoffice.png)

## Why

Showing an Office or Hangul document inside an Android app usually means one of three things: upload it to a conversion service, ship a very large native office suite, or pay for a commercial SDK. The common JVM option, Apache POI, renders slides through `java.awt`, which Android does not have.

Slides and HWPX documents have a property that makes a small renderer possible: every shape, and in HWPX every line of text, is stored with its final position. PowerPoint saves absolute coordinates for each shape; Hangul saves where each line starts and where each page breaks. So the job is reading those positions back and drawing them, not re-running a word processor's layout engine.

## Usage

```kotlin
val pageCount = OfficeCanvas.render(context, file) { index, bitmap ->
    File(outDir, "page$index.png").outputStream().use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    bitmap.recycle()
}
```

- The format is detected from the file's contents (`DocumentFormat.detect(file)`), not its extension.
- Pages are handed over one at a time and never held together, so long decks don't run out of memory. The callback owns each bitmap.
- `longSidePx` (default 1600) sets the size of a page's longer side; the other side follows the page's aspect ratio.
- Rendering is synchronous. Call it off the main thread.
- Anything that isn't a readable PPTX, PPT or HWPX raises `OfficeCanvasException`.
- At most `OfficeCanvas.MAX_PAGES` (1000) pages are rendered.

### Files from other people

Documents are parsed as if they could be hostile, since they usually come from someone else:

- XML parts that carry a DTD are refused, so entity expansion and external entities are closed whichever XML parser the platform provides.
- Sizes and counts read from a file are checked against the data actually present before anything is allocated, every chain or loop in a file is followed at most once, nesting stops at 32 levels, decoded pictures are subsampled to at most 16 million pixels, and output stops at 1000 pages.
- A damaged shape, picture or slide is skipped; anything worse, including running out of memory, comes out as `OfficeCanvasException` rather than an `Error`.

`MaliciousInputTest` holds the crafted files these rules were written against.

The `sample` module is a one-screen app: pick a document and its pages appear in a list.

## Installation

Releases are built by [JitPack](https://jitpack.io/#baeseungwon1010/officecanvas). Add its repository in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

and the dependency in your module:

```kotlin
dependencies {
    implementation("com.github.baeseungwon1010:officecanvas:0.2.1")
}
```

Any commit hash or `main-SNAPSHOT` works as the version too. To build it yourself instead, run `./gradlew :officecanvas:publishToMavenLocal` and depend on `io.github.baeseungwon1010:officecanvas:0.2.1` from `mavenLocal()`.

Requirements: minSdk 24, Java 17 toolchain. The AAR is about 3 MB, most of it bundled fonts (see below).

## What is rendered

| | PPTX | PPT (97–2003) | HWPX |
|---|---|---|---|
| Text with size, color, bold/italic/underline | ✓ | ✓ | ✓ (one style per line) |
| Inheritance from layout / master / theme | ✓ (position, fill, text styles, body properties, color map) | ✓ (title master → master) | — |
| Theme colors with modifiers (lumMod, tint, shade, …) | ✓ | — (stored as RGB) | — |
| Shape styles (`<p:style>` fill, line and font references) | ✓ | — | — |
| Bullets | ✓ | ✓ incl. symbol-font bullets | — |
| Automatic numbering (1. a. i. …) | ✓ | ✓ | — |
| Superscript / subscript | ✓ | ✓ | — |
| Shrink text on overflow | the scale PowerPoint saved | estimated | — |
| Shape fill and outline | ✓ | ✓ | — |
| Lines, connectors, arrowheads, flipped shapes | ✓ (straight, elbow, curved) | ✓ | — |
| Shape outlines | ~65 presets + freeforms | freeforms + common presets | — |
| Gradient fills | linear, radial, rectangular | linear, axial, radial, rectangular | — |
| Tables | ✓ incl. table styles | as the shapes PowerPoint stores | ✓ (approximate spans) |
| SmartArt | from the drawing PowerPoint saves with it | — | — |
| Pictures | PNG, JPEG, EMF, WMF, SVG; crop | PNG, JPEG, EMF, WMF, DIB, TIFF, PICT with embedded JPEG/PNG; crop | PNG, JPEG (lightly tested) |
| Slide / page background | solid, gradient, picture | solid, picture, gradient | white |
| Hidden slides | skipped | skipped, as PowerPoint's own export does | — |

Not rendered: charts, shadows, glow, 3-D effects, text highlight, animations, video, embedded OLE objects other than their preview picture. HWP 5.0 (the older binary `.hwp`) is not supported.

## Fonts

Office documents are mostly set in Calibri, Arial, Times New Roman and Courier New, and line breaks depend on their exact glyph widths. The library bundles fonts with the same metrics under `assets/officecanvas/fonts` (SIL Open Font License 1.1): Carlito for Calibri (regular and bold; italics are slanted) and Liberation Sans, Serif and Mono for the other three. Text in scripts these fonts don't cover, such as Hangul, falls back to the device's system fonts, so its line breaks can differ slightly from the original.

## How close is it

Both slide formats are checked page by page against LibreOffice's PDF output of the same file. For `.pptx`, on 15 real decks (240 slides of lectures, reports and team presentations), the mean per-slide difference score from `tools/compare_render.py` is 2.0, down from 6.1 before table styles, theme style references, inherited backgrounds and placeholders, SVG icons and Carlito were added; most of what remains is Hangul set in different fallback fonts. For `.ppt`, on a 75-slide lecture deck, 66 slides have every text baseline within 6 pt of LibreOffice's. None of these decks are included here, for copyright and privacy reasons. `tools/compare.sh` runs the same comparison on any document; see [docs/TESTING.md](docs/TESTING.md).

LibreOffice is a reference, not ground truth. Where PowerPoint and LibreOffice disagree (symbol fonts, automatic numbering), the renderer follows PowerPoint.

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): packages, data flow, and where to add a format feature
- [docs/FORMAT_NOTES.md](docs/FORMAT_NOTES.md): what the files actually contain, and the traps found while reading them
- [docs/TESTING.md](docs/TESTING.md): test documents, how they're generated, and the comparison tools

## 한국어 소개

OfficeCanvas는 PPTX·PPT·HWPX 문서를 안드로이드 기기 안에서 바로 이미지로 그리는 Kotlin 라이브러리입니다. 변환 서버나 네이티브 라이브러리 없이 동작합니다. 슬라이드와 HWPX는 도형·줄의 최종 위치가 파일 안에 저장되어 있어서, 워드프로세서의 레이아웃 엔진을 다시 구현하지 않고도 원본과 거의 같은 모양을 그릴 수 있습니다. HWPX는 한글이 저장해 둔 줄 위치(`<hp:lineseg>`)와 쪽 나눔을 그대로 사용합니다. JitPack 저장소를 추가하고 `com.github.baeseungwon1010:officecanvas:0.2.1`을 의존성으로 넣은 뒤, 위의 `OfficeCanvas.render` 한 줄로 사용합니다. 옛 한글 바이너리 형식(HWP 5.0)은 아직 지원하지 않습니다.

## License

Apache License 2.0, see [LICENSE](LICENSE). The bundled Liberation fonts are under the SIL Open Font License 1.1; see [NOTICE](NOTICE).
