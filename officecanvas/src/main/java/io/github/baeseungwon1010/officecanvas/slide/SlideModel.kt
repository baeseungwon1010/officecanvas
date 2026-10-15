package io.github.baeseungwon1010.officecanvas.slide

/**
 * A parsed .pptx, in the only terms the renderer needs: absolutely positioned boxes.
 *
 * PowerPoint stores every shape's position and size in the file itself, so unlike a
 * word processor document there is no layout to compute — no line boxes to stack, no
 * page breaks to find. That is what makes rendering slides on-device tractable at all,
 * and it is why this model is flat: parsing resolves inheritance and grouping, and the
 * renderer only has to draw what it is handed.
 *
 * All geometry is in EMU (English Metric Units), the unit the format uses:
 * 914400 per inch, 12700 per point.
 */

internal const val EMU_PER_INCH = 914400.0
internal const val EMU_PER_POINT = 12700.0

internal data class Deck(
    val widthEmu: Long,
    val heightEmu: Long,
    val slides: List<Slide>,
    /** The open file the slides' pictures are read from as they are drawn (a
     *  .ppt's); whoever renders the deck closes it afterwards. */
    val resources: java.io.Closeable? = null
) {
    val aspect: Double get() = widthEmu.toDouble() / heightEmu.toDouble()
}

internal data class Slide(
    /** Solid background colour as 0xAARRGGBB, or null to leave the page white. */
    val backgroundArgb: Int?,
    val elements: List<Element>
)

internal data class Box(val x: Long, val y: Long, val w: Long, val h: Long)

internal sealed class Element {
    abstract val box: Box

    data class Shape(
        override val box: Box,
        /** Preset geometry name ("rect", "ellipse", "roundRect", ...); null for a plain text box. */
        val geometry: String?,
        val fillArgb: Int?,
        val strokeArgb: Int?,
        val strokeWidthEmu: Long,
        val text: TextBody?,
        val rotation: Float = 0f,
        val flipH: Boolean = false,
        val flipV: Boolean = false,
        /** Arrowheads at the line's start/end ("line" geometry only). */
        val headArrow: Boolean = false,
        val tailArrow: Boolean = false,
        val dashed: Boolean = false,
        /** Exact outline in the box's own 0..1 space (freeforms and non-rectangular
         *  presets); when set it replaces [geometry] for fill and stroke. */
        val outline: List<PathOp>? = null,
        /** Replaces [fillArgb] when set. */
        val gradient: Gradient? = null,
        val shadow: Shadow? = null,
        val scene3d: Scene3d? = null
    ) : Element()

    data class Picture(
        override val box: Box,
        /** Entry name inside the .pptx zip, e.g. "ppt/media/image3.png". Null when
         *  the source isn't a zip at all and [data] is used instead — a legacy
         *  .ppt stores images in a separate OLE2 stream, not zip entries (see
         *  ppt/PptParser). Exactly one of the two is set. */
        val imageEntry: String? = null,
        /** A picture read from the document only when it is drawn. */
        val data: PictureData? = null,
        val rotation: Float = 0f,
        /** Fractions of the source image trimmed from each edge (negative = padding). */
        val crop: Crop? = null,
        val shadow: Shadow? = null,
        val scene3d: Scene3d? = null
    ) : Element()

    data class Table(
        override val box: Box,
        val columnWidthsEmu: List<Long>,
        val rowHeightsEmu: List<Long>,
        /** Row-major; a cell spanned by a merge is null. */
        val cells: List<List<TableCell?>>,
        /** Color of the cell grid lines; null draws none. */
        val gridArgb: Int? = 0xFFCCCCCC.toInt()
    ) : Element()
}

internal sealed class PathOp {
    data class MoveTo(val x: Float, val y: Float) : PathOp()
    data class LineTo(val x: Float, val y: Float) : PathOp()
    data class CubicTo(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x: Float, val y: Float) : PathOp()
    object Close : PathOp()
}

/** A linear gradient: [colors] at [positions] (0..1), running at [angleDeg]
 *  (0 = left to right, 90 = top to bottom). [radial] instead spreads it out from
 *  ([centerX], [centerY]) in the box's 0..1 space — as concentric rectangles when
 *  [rectangular] (PowerPoint's "from center"/"shape" shading on a box), else circles. */
internal data class Gradient(
    val colors: List<Int>,
    val positions: List<Float>,
    val angleDeg: Float,
    val radial: Boolean = false,
    val rectangular: Boolean = false,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f
)

/**
 * A shape turned in 3-D: the camera's rotation about the horizontal axis
 * ([latDeg]), the vertical one ([lonDeg]) and the view direction ([revDeg]),
 * whether it looks in [perspective], and an extrusion [depthEmu] deep behind the
 * shape in [extrusionArgb] (null: a darker shade of the fill).
 */
internal data class Scene3d(
    val latDeg: Float, val lonDeg: Float, val revDeg: Float, val perspective: Boolean,
    val depthEmu: Long = 0, val extrusionArgb: Int? = null
)

/** An outer shadow: the shape's silhouette in [argb] (with its alpha), offset by
 *  ([dxEmu], [dyEmu]) and blurred over [blurEmu]. */
internal data class Shadow(val argb: Int, val dxEmu: Long, val dyEmu: Long, val blurEmu: Long)

/**
 * Picture bytes left in the file until drawn, so a deck's pictures aren't all in
 * memory at once: raster bytes BitmapFactory can decode, an EMF, or a placeable
 * WMF. [load] returns null when they can't be read.
 */
internal class PictureData(val kind: Kind, val load: () -> ByteArray?) {
    enum class Kind { RASTER, EMF, WMF }
}

internal data class Crop(val left: Float, val top: Float, val right: Float, val bottom: Float)

internal data class TableCell(val text: TextBody?, val fillArgb: Int?, val colSpan: Int = 1, val rowSpan: Int = 1)

internal enum class VAnchor { TOP, MIDDLE, BOTTOM }
internal enum class HAlign { LEFT, CENTER, RIGHT, JUSTIFY }

internal data class TextBody(
    val paragraphs: List<Paragraph>,
    val anchor: VAnchor = VAnchor.TOP,
    val insetLeftEmu: Long = 91440,
    val insetTopEmu: Long = 45720,
    val insetRightEmu: Long = 91440,
    val insetBottomEmu: Long = 45720,
    /** PowerPoint's "shrink text on overflow"; the renderer may scale down to fit. */
    val autoFit: Boolean = false,
    /** The smallest scale auto-fit may shrink the text to. */
    val autoFitFloor: Float = 0.45f,
    /** Auto-fit is to be worked out as PowerPoint does it (nothing was saved, as in
     *  a .ppt): text scale in 7.5% steps, and at each a line spacing reduction of
     *  0, 10 or 20%, the first pair that fits. Otherwise small corrections only. */
    val estimateFit: Boolean = false,
    /** False when lines run on past the box instead of wrapping (wrap="none"). */
    val wrap: Boolean = true
)

internal data class Paragraph(
    val runs: List<Run>,
    val align: HAlign = HAlign.LEFT,
    /** Indent level 0..8, used for bullet indentation. */
    val level: Int = 0,
    val bullet: String? = null,
    val spaceBeforePt: Float = 0f,
    val spaceAfterPt: Float = 0f,
    val lineSpacingPercent: Float = 100f,
    /** Where wrapped text lines start, from the text box's inner left edge. Null
     *  = derive from [level]. */
    val marginLeftEmu: Long? = null,
    /** Where the first line (and its bullet) starts; null = [marginLeftEmu]. */
    val bulletIndentEmu: Long? = null,
    val bulletColorArgb: Int? = null,
    val bulletSizePercent: Float = 100f,
    /** Explicit tab stops, from the text box's inner left edge. */
    val tabStopsEmu: List<Long> = emptyList(),
    /** Spacing of the implicit tab stops after the explicit ones. */
    val defaultTabEmu: Long = 914400
) {
    val isEmpty: Boolean get() = runs.all { it.text.isEmpty() }
    val plainText: String get() = runs.joinToString("") { it.text }
}

internal data class Run(
    val text: String,
    val sizePt: Float = 18f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val colorArgb: Int = 0xFF000000.toInt(),
    val fontFamily: String? = null,
    /** Superscript (> 0) / subscript (< 0) offset as a percent of the line height. */
    val baselinePercent: Float = 0f,
    /** Text highlight (a marker-pen background behind the run), if any. */
    val highlightArgb: Int? = null,
    /** An outer shadow behind the letters (<a:effectLst> in the run's properties). */
    val shadow: Shadow? = null
)
