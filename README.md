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
    implementation("com.github.baeseungwon1010:officecanvas:0.1.0")
}
```

Any commit hash or `main-SNAPSHOT` works as the version too. To build it yourself instead, run `./gradlew :officecanvas:publishToMavenLocal` and depend on `io.github.baeseungwon1010:officecanvas:0.1.0` from `mavenLocal()`.

Requirements: minSdk 24, Java 17 toolchain. The AAR is about 2.4 MB, most of it bundled fonts (see below).

## What is rendered

| | PPTX | PPT (97–2003) | HWPX |
|---|---|---|---|
| Text with size, color, bold/italic/underline | ✓ | ✓ | ✓ (one style per line) |
| Inheritance from layout / master / theme | ✓ | ✓ (title master → master) | — |
| Bullets | ✓ | ✓ incl. symbol-font bullets | — |
| Automatic numbering (1. a. i. …) | drawn as • | ✓ | — |
| Superscript / subscript | — | ✓ | — |
| Shape fill and outline | ✓ | ✓ | — |
| Lines, arrowheads, flipped shapes | lines only | ✓ | — |
| Shape outlines | rectangle, ellipse, rounded rectangle (others as rectangles) | freeform paths + common presets | — |
| Gradient fills | linear, radial, rectangular | linear, axial, radial, rectangular | — |
| Tables | ✓ | as the shapes PowerPoint stores | ✓ (approximate spans) |
| Pictures | PNG, JPEG, EMF, WMF | PNG, JPEG, EMF, WMF, DIB, TIFF, PICT with embedded JPEG/PNG; crop | PNG, JPEG (lightly tested) |
| Slide / page background | solid (gradient → first color) | solid, picture, gradient | white |
| Hidden slides | rendered | skipped, as PowerPoint's own export does | — |

Not rendered: charts, SmartArt, shadows, 3-D effects, animations, video, embedded OLE objects other than their preview picture. HWP 5.0 (the older binary `.hwp`) is not supported.

## Fonts

Office documents are mostly set in Arial, Times New Roman and Courier New, and line breaks depend on their exact glyph widths. The library bundles Liberation Sans, Serif and Mono, which have the same metrics, under `assets/officecanvas/fonts` (SIL Open Font License 1.1). Text in scripts these fonts don't cover, such as Hangul, falls back to the device's system fonts, so its line breaks can differ slightly from the original.

## How close is it

The `.ppt` path was checked slide by slide against LibreOffice's PDF output on two real lecture decks (60 and 75 slides, not included here for copyright reasons): on the 75-slide deck, 66 slides have every text baseline within 6 pt of LibreOffice's. `tools/compare.sh` runs the same comparison on any document; see [docs/TESTING.md](docs/TESTING.md).

LibreOffice is a reference, not ground truth. Where PowerPoint and LibreOffice disagree (symbol fonts, automatic numbering), the renderer follows PowerPoint.

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): packages, data flow, and where to add a format feature
- [docs/FORMAT_NOTES.md](docs/FORMAT_NOTES.md): what the files actually contain, and the traps found while reading them
- [docs/TESTING.md](docs/TESTING.md): test documents, how they're generated, and the comparison tools

## 한국어 소개

OfficeCanvas는 PPTX·PPT·HWPX 문서를 안드로이드 기기 안에서 바로 이미지로 그리는 Kotlin 라이브러리입니다. 변환 서버나 네이티브 라이브러리 없이 동작합니다. 슬라이드와 HWPX는 도형·줄의 최종 위치가 파일 안에 저장되어 있어서, 워드프로세서의 레이아웃 엔진을 다시 구현하지 않고도 원본과 거의 같은 모양을 그릴 수 있습니다. HWPX는 한글이 저장해 둔 줄 위치(`<hp:lineseg>`)와 쪽 나눔을 그대로 사용합니다. JitPack 저장소를 추가하고 `com.github.baeseungwon1010:officecanvas:0.1.0`을 의존성으로 넣은 뒤, 위의 `OfficeCanvas.render` 한 줄로 사용합니다. 옛 한글 바이너리 형식(HWP 5.0)은 아직 지원하지 않습니다.

## License

Apache License 2.0, see [LICENSE](LICENSE). The bundled Liberation fonts are under the SIL Open Font License 1.1; see [NOTICE](NOTICE).
