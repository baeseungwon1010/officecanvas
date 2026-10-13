package io.github.baeseungwon1010.officecanvas.ppt

import io.github.baeseungwon1010.officecanvas.metafile.TiffDecoder
import io.github.baeseungwon1010.officecanvas.metafile.WmfRenderer
import io.github.baeseungwon1010.officecanvas.slide.Box
import io.github.baeseungwon1010.officecanvas.slide.Crop
import io.github.baeseungwon1010.officecanvas.slide.Gradient
import io.github.baeseungwon1010.officecanvas.slide.Element
import io.github.baeseungwon1010.officecanvas.slide.TextBody
import io.github.baeseungwon1010.officecanvas.slide.VAnchor
import io.github.baeseungwon1010.officecanvas.util.Limits
import io.github.baeseungwon1010.officecanvas.util.SafeBitmaps

private const val EMU_PER_MASTER_UNIT = 1587.5

// FOPT property ids (MS-ODRAW 2.3).
private const val P_ROTATION = 0x0004
private const val P_TEXT_LEFT = 0x0081
private const val P_TEXT_TOP = 0x0082
private const val P_TEXT_RIGHT = 0x0083
private const val P_TEXT_BOTTOM = 0x0084
private const val P_ANCHOR_TEXT = 0x0087
private const val P_CROP_TOP = 0x0100
private const val P_CROP_BOTTOM = 0x0101
private const val P_CROP_LEFT = 0x0102
private const val P_CROP_RIGHT = 0x0103
private const val P_PIB = 0x0104
private const val P_GEO_LEFT = 0x0140
private const val P_GEO_TOP = 0x0141
private const val P_GEO_RIGHT = 0x0142
private const val P_GEO_BOTTOM = 0x0143
private const val P_VERTICES = 0x0145
private const val P_SEGMENTS = 0x0146
private const val P_ADJUST_1 = 0x0147
private const val P_FILL_BACK_COLOR = 0x0183
private const val P_FILL_BACK_OPACITY = 0x0184
private const val P_FILL_ANGLE = 0x018B
private const val P_FILL_FOCUS = 0x018C
private const val P_FILL_TO_LEFT = 0x018D
private const val P_FILL_TO_TOP = 0x018E
private const val P_FILL_TO_RIGHT = 0x018F
private const val P_FILL_TO_BOTTOM = 0x0190
private const val P_FILL_SHADE_COLORS = 0x0197
private const val P_FILL_TYPE = 0x0180
private const val P_FILL_COLOR = 0x0181
private const val P_FILL_OPACITY = 0x0182
private const val P_FILL_BLIP = 0x0186
private const val P_FILL_BOOLS = 0x01BF
private const val P_LINE_COLOR = 0x01C0
private const val P_LINE_WIDTH = 0x01CB
private const val P_LINE_DASHING = 0x01CE
private const val P_LINE_START_ARROW = 0x01D0
private const val P_LINE_END_ARROW = 0x01D1
private const val P_LINE_BOOLS = 0x01FF
private const val P_GROUP_BOOLS = 0x03BF

// FSP flags (MS-ODRAW 2.2.40).
private const val F_GROUP = 0x001
private const val F_PATRIARCH = 0x004
private const val F_DELETED = 0x008
private const val F_FLIP_H = 0x040
private const val F_FLIP_V = 0x080
private const val F_BACKGROUND = 0x400

// Shape types (MSOSPT).
private const val SPT_NOT_PRIMITIVE = 0
private const val SPT_RECT = 1
private const val SPT_ROUND_RECT = 2
private const val SPT_ELLIPSE = 3
private const val SPT_LINE = 20
private const val SPT_TEXT_BOX = 202

/** A shape's FOPT table (plus TertiaryFOPT), falling back to the drawing group's
 *  defaults for anything the shape doesn't set. */
internal class ShapeProps(
    private val own: Map<Int, Int>,
    private val defaults: Map<Int, Int>,
    private val complex: Map<Int, ByteArray> = emptyMap()
) {
    fun int(id: Int): Int? = own[id] ?: defaults[id]
    fun complex(id: Int): ByteArray? = complex[id]
    fun ownInt(id: Int): Int? = own[id]

    /** A boolean packed in a property word: meaningful only when its "use" bit is set. */
    fun bool(id: Int, valueBit: Int, useBit: Int, fromDefaults: Boolean = true): Boolean? {
        for (map in if (fromDefaults) listOf(own, defaults) else listOf(own)) {
            val v = map[id] ?: continue
            if ((v ushr useBit) and 1 != 0) return (v ushr valueBit) and 1 != 0
        }
        return null
    }

    companion object {
        /** Fixed 6-byte entries; complex (variable-length) payloads aren't needed here. */
        fun read(r: RecordReader, opts: List<Rec>): Map<Int, Int> {
            val out = mutableMapOf<Int, Int>()
            for (rec in opts) {
                var off = rec.start
                repeat(rec.instance) {
                    if (off + 6 > rec.end) return@repeat
                    out[r.u16(off) and 0x3FFF] = r.i32(off + 2)
                    off += 6
                }
            }
            return out
        }

        /**
         * Complex properties' payloads, which follow the fixed 6-byte entries in
         * entry order; an entry's value is its payload length. Array payloads
         * (IMsoArray: nElems, nElemsAlloc, cbElem, then elements) are sometimes
         * written with a length that leaves out their own 6-byte header — LibreOffice
         * does this, and the reference readers accept it — so a declared length equal
         * to exactly nElems * cbElem is taken to mean "plus the header".
         */
        fun readComplex(r: RecordReader, opts: List<Rec>): Map<Int, ByteArray> {
            val out = mutableMapOf<Int, ByteArray>()
            for (rec in opts) {
                var entry = rec.start
                var data = rec.start + 6 * rec.instance
                repeat(rec.instance) {
                    if (entry + 6 > rec.end) return@repeat
                    val opid = r.u16(entry)
                    val declared = r.i32(entry + 2)
                    entry += 6
                    if (opid and 0x8000 == 0) return@repeat
                    var length = declared
                    if (data + 6 <= rec.end) {
                        val n = r.u16(data)
                        val size = r.u16(data + 4).let { if (it == 0xFFF0) 4 else it }
                        if (size > 0 && n * size == declared && data + declared + 6 <= rec.end) length = declared + 6
                    }
                    if (length < 0 || data + length > rec.end) return@repeat
                    out[opid and 0x3FFF] = r.data.copyOfRange(data, data + length)
                    data += length
                }
            }
            return out
        }
    }
}

/** A picture from the blip store: raster bytes BitmapFactory can decode, or an EMF. */
internal class Blip(val bytes: ByteArray, val kind: Kind) {
    enum class Kind { RASTER, EMF, WMF }

    fun toPicture(box: Box, rotation: Float = 0f, crop: Crop? = null) = when (kind) {
        Kind.EMF -> Element.Picture(box = box, emfBytes = bytes, rotation = rotation, crop = crop)
        Kind.WMF -> Element.Picture(box = box, wmfBytes = bytes, rotation = rotation, crop = crop)
        Kind.RASTER -> Element.Picture(box = box, imageBytes = bytes, rotation = rotation, crop = crop)
    }
}

/** Pictures: a 1-based "pib" index into the drawing group's blip store, whose
 *  entries point at blip records in the separate "Pictures" stream. */
internal class BlipStore(private val pictures: RecordReader?, private val offsets: List<Int>) {
    // Each picture is extracted once, however many shapes use it, and the bytes
    // extracted for one deck are budgeted: a small deflate bomb referenced by many
    // shapes would otherwise inflate again for each.
    private val cache = HashMap<Int, Blip?>()
    private var budget = MAX_TOTAL_BYTES

    fun blip(pib: Int): Blip? = cache.getOrPut(pib) {
        extract(pib)?.takeIf { it.bytes.size <= budget }?.also { budget -= it.bytes.size }
    }

    private fun extract(pib: Int): Blip? {
        val r = pictures ?: return null
        val offset = offsets.getOrNull(pib - 1) ?: return null
        val rec = r.record(offset) ?: return null
        return when (rec.type) {
            RT.BLIP_JPEG, RT.BLIP_JPEG_CMYK -> raster(r, rec, JPEG_MAGIC)
            RT.BLIP_PNG -> raster(r, rec, PNG_MAGIC)
            RT.BLIP_EMF -> metafile(r, rec, Blip.Kind.EMF)
            RT.BLIP_WMF -> metafile(r, rec, Blip.Kind.WMF)
            RT.BLIP_DIB -> dib(r, rec)
            RT.BLIP_TIFF -> tiff(r, rec)
            RT.BLIP_PICT -> pict(r, rec)
            else -> null
        }
    }

    /** One or two 16-byte GUIDs and sometimes a tag byte precede the image;
     *  finding the format's magic is simpler than decoding which are present. */
    private fun raster(r: RecordReader, rec: Rec, magic: ByteArray): Blip? {
        val searchEnd = minOf(rec.start + 40, rec.end - magic.size)
        for (start in rec.start..searchEnd) {
            if (magic.indices.all { r.data[start + it] == magic[it] }) return Blip(r.data.copyOfRange(start, rec.end), Blip.Kind.RASTER)
        }
        return null
    }

    /** Metafile blips (MS-ODRAW 2.2.24): GUID (a second one for instance 0x3D5),
     *  a 34-byte header ending in cbSave and a compression flag, then the data,
     *  usually deflated. */
    private fun metafile(r: RecordReader, rec: Rec, kind: Blip.Kind, secondGuidInstance: Int? = null): Blip? {
        val secondGuid = rec.instance == (secondGuidInstance ?: if (kind == Blip.Kind.EMF) 0x3D5 else 0x217)
        val header = rec.start + if (secondGuid) 32 else 16
        if (header + 34 > rec.end) return null
        val uncompressed = r.i32(header)
        val saved = r.i32(header + 28)
        val compressed = r.u8(header + 32) == 0
        val dataStart = header + 34
        val dataEnd = minOf(rec.end, dataStart + saved)
        if (dataEnd <= dataStart) return null
        // The header's rcBounds (left, top, right, bottom) is the WMF's only extent; EMF carries its own.
        fun wrap(bytes: ByteArray) =
            if (kind == Blip.Kind.WMF) WmfRenderer.placeable(bytes, r.i32(header + 4), r.i32(header + 8), r.i32(header + 12), r.i32(header + 16)) else bytes
        if (!compressed) return Blip(wrap(r.data.copyOfRange(dataStart, dataEnd)), kind)
        if (uncompressed !in 1..SafeBitmaps.MAX_IMAGE_BYTES) return null
        val out = ByteArray(uncompressed)
        val inflater = java.util.zip.Inflater()
        return try {
            inflater.setInput(r.data, dataStart, dataEnd - dataStart)
            var n = 0
            while (n < out.size && !inflater.finished()) {
                val got = inflater.inflate(out, n, out.size - n)
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                n += got
            }
            Blip(wrap(if (n == out.size) out else out.copyOf(n)), kind)
        } catch (e: java.util.zip.DataFormatException) {
            null
        } finally {
            inflater.end()
        }
    }

    /** A device-independent bitmap blip (MS-ODRAW 2.2.31): GUID(s), a tag byte, then a bare DIB — wrapped into a BMP so BitmapFactory reads it. */
    private fun dib(r: RecordReader, rec: Rec): Blip? {
        val start = rec.start + (if (rec.instance == 0x7A9) 32 else 16) + 1
        if (start + 40 > rec.end) return null
        val bmp = WmfRenderer.dibToBmp(r.data.copyOfRange(start, rec.end)) ?: return null
        return Blip(bmp, Blip.Kind.RASTER)
    }

    /** TIFF blip: GUID(s), a tag byte, then the TIFF file — converted to BMP. */
    private fun tiff(r: RecordReader, rec: Rec): Blip? {
        val start = rec.start + (if (rec.instance == 0x6E5) 32 else 16) + 1
        if (start + 8 > rec.end) return null
        return TiffDecoder.toBmp(r.data.copyOfRange(start, rec.end))?.let { Blip(it, Blip.Kind.RASTER) }
    }

    /**
     * PICT (Mac QuickDraw) blip: stored like a metafile. Rendering QuickDraw is
     * out of reach, but PICTs from Office are nearly always a wrapper around a
     * QuickTime-compressed JPEG (or PNG), which is recovered as-is.
     */
    private fun pict(r: RecordReader, rec: Rec): Blip? {
        val data = metafile(r, rec, Blip.Kind.RASTER, secondGuidInstance = 0x543)?.bytes ?: return null
        for (magic in listOf(JPEG_MAGIC, PNG_MAGIC)) {
            for (i in 0..data.size - magic.size) {
                if (magic.indices.all { data[i + it] == magic[it] }) return Blip(data.copyOfRange(i, data.size), Blip.Kind.RASTER)
            }
        }
        return null
    }

    companion object {
        private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val EMPTY = BlipStore(null, emptyList())

        /** Picture bytes extracted from one deck, in total. */
        private const val MAX_TOTAL_BYTES = 256 shl 20
    }
}

internal sealed class Background {
    data class Solid(val argb: Int) : Background()
    data class Shaded(val gradient: Gradient) : Background()
    data class Picture(val blip: Blip) : Background()
}

internal class Drawing(val background: Background?, val elements: List<Element>)

/** Maps a group's child coordinate space onto the slide (EMU). */
private class Xform(val ox: Long, val oy: Long, val sx: Double, val sy: Double, val gx: Int, val gy: Int) {
    fun box(left: Int, top: Int, right: Int, bottom: Int) = Box(
        ox + ((left - gx) * sx).toLong(), oy + ((top - gy) * sy).toLong(),
        ((right - left) * sx).toLong(), ((bottom - top) * sy).toLong()
    )
}

/**
 * Reads one slide's or master's OfficeArt drawing (DgContainer) into elements.
 * [outlineTexts] resolves placeholders whose text lives in the document's slide
 * list instead of in the shape (OutlineTextRefAtom).
 */
internal class DrawingReader(
    private val r: RecordReader,
    private val defaults: Map<Int, Int>,
    private val blips: BlipStore,
    private val text: TextContext,
    private val outlineTexts: List<List<Rec>>
) {
    fun read(dg: Rec, includePlaceholders: Boolean): Drawing {
        val elements = mutableListOf<Element>()
        var background: Background? = null
        for (child in r.children(dg)) {
            when (child.type) {
                RT.SPGR_CONTAINER -> walk(child, null, includePlaceholders, elements, 0)
                RT.SP_CONTAINER -> background = background ?: readBackground(child)
            }
        }
        return Drawing(background, elements)
    }

    private fun walk(spgr: Rec, xf: Xform?, includePlaceholders: Boolean, out: MutableList<Element>, depth: Int) {
        if (depth > Limits.MAX_NESTING) return
        // The first SpContainer describes the group itself (or the patriarch).
        for ((i, child) in r.children(spgr).withIndex()) {
            when (child.type) {
                RT.SP_CONTAINER -> if (i > 0) out += readShape(child, xf, includePlaceholders)
                RT.SPGR_CONTAINER -> readGroup(child, xf, includePlaceholders, out, depth + 1)
            }
        }
    }

    private fun readGroup(spgr: Rec, parent: Xform?, includePlaceholders: Boolean, out: MutableList<Element>, depth: Int) {
        val head = r.children(spgr).firstOrNull { it.type == RT.SP_CONTAINER } ?: return
        val kids = r.children(head)
        val box = anchorBox(kids, parent) ?: return
        val fspgr = kids.firstOrNull { it.type == RT.FSPGR && it.length >= 16 } ?: return
        val gx0 = r.i32(fspgr.start)
        val gy0 = r.i32(fspgr.start + 4)
        val gw = r.i32(fspgr.start + 8) - gx0
        val gh = r.i32(fspgr.start + 12) - gy0
        if (gw == 0 || gh == 0) return
        walk(spgr, Xform(box.x, box.y, box.w.toDouble() / gw, box.h.toDouble() / gh, gx0, gy0), includePlaceholders, out, depth)
    }

    /** Top-level shapes carry a ClientAnchor in master units — (top, left, right,
     *  bottom), MS-PPT's SmallRect order, not a Win32 RECT's; shapes inside a
     *  group carry a ChildAnchor (left, top, right, bottom) in the group's space. */
    private fun anchorBox(kids: List<Rec>, xf: Xform?): Box? {
        if (xf != null) {
            kids.firstOrNull { it.type == RT.CHILD_ANCHOR && it.length >= 16 }?.let { a ->
                return xf.box(r.i32(a.start), r.i32(a.start + 4), r.i32(a.start + 8), r.i32(a.start + 12))
            }
        }
        val a = kids.firstOrNull { it.type == RT.CLIENT_ANCHOR } ?: return null
        val (top, left, right, bottom) = when {
            a.length >= 16 -> listOf(r.i32(a.start), r.i32(a.start + 4), r.i32(a.start + 8), r.i32(a.start + 12))
            a.length >= 8 -> listOf(r.i16(a.start), r.i16(a.start + 2), r.i16(a.start + 4), r.i16(a.start + 6))
            else -> return null
        }
        return Box(
            (left * EMU_PER_MASTER_UNIT).toLong(), (top * EMU_PER_MASTER_UNIT).toLong(),
            ((right - left) * EMU_PER_MASTER_UNIT).toLong(), ((bottom - top) * EMU_PER_MASTER_UNIT).toLong()
        )
    }

    private fun readShape(sp: Rec, xf: Xform?, includePlaceholders: Boolean): List<Element> {
        val kids = r.children(sp)
        val fsp = kids.firstOrNull { it.type == RT.FSP && it.length >= 8 } ?: return emptyList()
        val flags = r.i32(fsp.start + 4)
        if (flags and (F_DELETED or F_BACKGROUND or F_GROUP or F_PATRIARCH) != 0) return emptyList()
        val placeholder = kids.firstOrNull { it.type == RT.CLIENT_DATA }
            ?.let { r.child(it, RT.PLACEHOLDER_ATOM) } != null
        if (placeholder && !includePlaceholders) return emptyList()

        val opts = kids.filter { it.type == RT.FOPT || it.type == RT.TERTIARY_FOPT }
        val props = ShapeProps(ShapeProps.read(r, opts), defaults, ShapeProps.readComplex(r, opts))
        if (props.bool(P_GROUP_BOOLS, 1, 17, fromDefaults = false) == true) return emptyList() // fHidden

        var box = anchorBox(kids, xf) ?: return emptyList()
        val rotation = ((props.ownInt(P_ROTATION) ?: 0) / 65536f).let { ((it % 360f) + 360f) % 360f }
        // The anchor of a shape turned 45..135 (or 225..315) degrees is its rotated
        // bounding box; the unrotated frame has width and height swapped.
        if (rotation in 45f..135f || rotation in 225f..315f) {
            val cx = box.x + box.w / 2
            val cy = box.y + box.h / 2
            box = Box(cx - box.h / 2, cy - box.w / 2, box.h, box.w)
        }

        props.ownInt(P_PIB)?.let { pib ->
            val picture = blips.blip(pib)?.toPicture(box, rotation, crop(props)) ?: return emptyList()
            // A picture's own outline (a frame drawn around a photo) is only drawn when set explicitly.
            if (props.bool(P_LINE_BOOLS, 3, 19, fromDefaults = false) != true) return listOf(picture)
            val frame = Element.Shape(
                box = box, geometry = "rect", fillArgb = null,
                strokeArgb = resolveColorRef(props.int(P_LINE_COLOR) ?: 0, text.scheme),
                strokeWidthEmu = (props.int(P_LINE_WIDTH) ?: 9525).toLong(), text = null, rotation = rotation
            )
            return listOf(picture, frame)
        }

        val spt = fsp.instance
        val isLine = spt == SPT_LINE || spt in 32..40 // line and straight/elbow/curved connectors
        val noDefaultFrame = placeholder || spt == SPT_TEXT_BOX
        // A freeform is any shape carrying its own vertices, whatever its type
        // (LibreOffice writes them as 0xFFF rather than 0).
        val outline = if (isLine) null else props.complex(P_VERTICES)?.let { v ->
            val geo = intArrayOf(
                props.int(P_GEO_LEFT) ?: 0, props.int(P_GEO_TOP) ?: 0,
                props.int(P_GEO_RIGHT) ?: 21600, props.int(P_GEO_BOTTOM) ?: 21600
            )
            PptGeometry.freeform(v, props.complex(P_SEGMENTS), geo)
        } ?: PptGeometry.preset(spt, IntArray(8) { props.ownInt(P_ADJUST_1 + it) ?: Int.MIN_VALUE })
        val geometry = when {
            isLine -> "line"
            outline != null -> "custom"
            spt == SPT_RECT -> "rect"
            spt == SPT_ROUND_RECT -> "roundRect"
            spt == SPT_ELLIPSE -> "ellipse"
            spt == SPT_TEXT_BOX -> "rect"
            spt == SPT_NOT_PRIMITIVE -> null // no vertices to draw: text only
            else -> "rect"
        }

        val filled = !isLine && geometry != null &&
            (props.bool(P_FILL_BOOLS, 4, 20, fromDefaults = !noDefaultFrame) ?: !noDefaultFrame)
        // A texture/picture fill (a photo cropped into a rectangle) is an image, not a color.
        val fillBlip = if (filled && (props.int(P_FILL_TYPE) ?: 0).let { it == 2 || it == 3 }) {
            props.int(P_FILL_BLIP)?.let { blips.blip(it) }
        } else null
        val gradient = if (filled && fillBlip == null) gradient(props) else null
        val fillArgb = if (filled && fillBlip == null && gradient == null) fillColor(props) else null

        val lined = geometry != null &&
            (props.bool(P_LINE_BOOLS, 3, 19, fromDefaults = !noDefaultFrame) ?: !noDefaultFrame)
        val strokeArgb = if (lined) resolveColorRef(props.int(P_LINE_COLOR) ?: 0, text.scheme) else null
        val strokeWidth = (props.int(P_LINE_WIDTH) ?: 9525).toLong()

        val textBody = kids.firstOrNull { it.type == RT.CLIENT_TEXTBOX }?.let { readText(it, props, kids, placeholder) }
        if (fillArgb == null && gradient == null && strokeArgb == null && textBody == null && fillBlip == null) return emptyList()

        val shape = Element.Shape(
            box = box,
            geometry = geometry,
            fillArgb = fillArgb,
            strokeArgb = strokeArgb,
            strokeWidthEmu = strokeWidth,
            text = textBody,
            rotation = rotation,
            flipH = flags and F_FLIP_H != 0,
            flipV = flags and F_FLIP_V != 0,
            headArrow = (props.ownInt(P_LINE_START_ARROW) ?: 0) != 0,
            tailArrow = (props.ownInt(P_LINE_END_ARROW) ?: 0) != 0,
            dashed = (props.ownInt(P_LINE_DASHING) ?: 0) != 0,
            outline = outline,
            gradient = gradient
        )
        return if (fillBlip != null) listOf(fillBlip.toPicture(box, rotation), shape) else listOf(shape)
    }

    private fun withOpacity(color: Int, opacity: Int?): Int {
        val alpha = ((opacity ?: 0x10000).coerceIn(0, 0x10000) * 255L / 0x10000).toInt()
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun fillColor(props: ShapeProps): Int? {
        val color = resolveColorRef(props.int(P_FILL_COLOR) ?: 0xFFFFFF, text.scheme) ?: return null
        val fill = withOpacity(color, props.int(P_FILL_OPACITY))
        // A pattern fill (two-color hatch/dots) reads, at slide scale, as the two colors mixed.
        if (props.int(P_FILL_TYPE) == 1) {
            val back = resolveColorRef(props.int(P_FILL_BACK_COLOR) ?: 0xFFFFFF, text.scheme) ?: return fill
            return mix(fill, withOpacity(back, props.int(P_FILL_BACK_OPACITY)))
        }
        return fill
    }

    private fun mix(a: Int, b: Int): Int {
        fun c(shift: Int) = (((a ushr shift) and 0xFF) + ((b ushr shift) and 0xFF)) / 2
        return (c(24) shl 24) or (c(16) shl 16) or (c(8) shl 8) or c(0)
    }

    /** Picture crop: four 16.16 fixed-point fractions of the source image. */
    private fun crop(props: ShapeProps): Crop? {
        val t = (props.ownInt(P_CROP_TOP) ?: 0) / 65536f
        val b = (props.ownInt(P_CROP_BOTTOM) ?: 0) / 65536f
        val l = (props.ownInt(P_CROP_LEFT) ?: 0) / 65536f
        val rr = (props.ownInt(P_CROP_RIGHT) ?: 0) / 65536f
        return if (t == 0f && b == 0f && l == 0f && rr == 0f) null else Crop(l, t, rr, b)
    }

    /**
     * Shaded fills (fillType 4..8). Linear ones (4 shade, 7 shade-scale) run
     * from fillColor toward fillBackColor; fillAngle (16.16 degrees) turns that
     * direction — angle a runs the colors along screen angle 90 - a, so 0 is top
     * to bottom and -270 left to right. fillFocus places the back color: 0 at the
     * far end, -50 in the middle with fillColor at both ends (an axial "tube"),
     * +50 the reverse, +/-100 swapped. The centered kinds (5 center, 6 shape,
     * 8 title) become a radial gradient from fillBackColor at the focus point
     * out to fillColor. Verified against LibreOffice renders of the same file.
     */
    private fun gradient(props: ShapeProps): Gradient? {
        val type = props.int(P_FILL_TYPE) ?: return null
        if (type !in 4..8) return null
        val fill = withOpacity(resolveColorRef(props.int(P_FILL_COLOR) ?: 0xFFFFFF, text.scheme) ?: return null, props.int(P_FILL_OPACITY))
        val back = withOpacity(resolveColorRef(props.int(P_FILL_BACK_COLOR) ?: 0xFFFFFF, text.scheme) ?: return null, props.int(P_FILL_BACK_OPACITY))
        if (type == 5 || type == 6 || type == 8) {
            // The focus rectangle (16.16 fractions) is where the back color sits.
            val cx = (((props.int(P_FILL_TO_LEFT) ?: 0x8000) + (props.int(P_FILL_TO_RIGHT) ?: 0x8000)) / 2f / 65536f).coerceIn(0f, 1f)
            val cy = (((props.int(P_FILL_TO_TOP) ?: 0x8000) + (props.int(P_FILL_TO_BOTTOM) ?: 0x8000)) / 2f / 65536f).coerceIn(0f, 1f)
            return Gradient(listOf(back, fill), listOf(0f, 1f), 0f, radial = true, rectangular = type != 8, centerX = cx, centerY = cy)
        }

        val shade = props.complex(P_FILL_SHADE_COLORS)?.let { shadeStops(it) }
        val angle = 90f - (props.int(P_FILL_ANGLE) ?: 0) / 65536f
        if (shade != null) return Gradient(shade.map { it.first }, shade.map { it.second }, angle)
        val focus = (props.int(P_FILL_FOCUS) ?: 0).coerceIn(-100, 100)
        return when {
            focus == 0 -> Gradient(listOf(fill, back), listOf(0f, 1f), angle)
            focus == -100 || focus == 100 -> Gradient(listOf(back, fill), listOf(0f, 1f), angle)
            focus < 0 -> Gradient(listOf(fill, back, fill), listOf(0f, 1f + focus / 100f, 1f), angle)
            else -> Gradient(listOf(back, fill, back), listOf(0f, focus / 100f, 1f), angle)
        }
    }

    /** fillShadeColors: an IMsoArray of (OfficeArtCOLORREF, 16.16 position) pairs. */
    private fun shadeStops(bytes: ByteArray): List<Pair<Int, Float>>? {
        if (bytes.size < 6) return null
        fun i32(o: Int) = (bytes[o].toInt() and 0xFF) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or
            ((bytes[o + 2].toInt() and 0xFF) shl 16) or ((bytes[o + 3].toInt() and 0xFF) shl 24)
        val n = (bytes[0].toInt() and 0xFF) or ((bytes[1].toInt() and 0xFF) shl 8)
        if (n < 2 || 6 + n * 8 > bytes.size) return null
        val stops = (0 until n).mapNotNull { i ->
            val color = resolveColorRef(i32(6 + i * 8), text.scheme) ?: return@mapNotNull null
            color to (i32(10 + i * 8) / 65536f).coerceIn(0f, 1f)
        }.sortedBy { it.second }
        return stops.takeIf { it.size >= 2 }
    }

    private fun readText(textbox: Rec, props: ShapeProps, shapeKids: List<Rec>, placeholder: Boolean): TextBody? {
        val own = r.children(textbox)
        // A placeholder may keep its text in the document's slide list instead.
        val records = own.firstOrNull { it.type == OUTLINE_TEXT_REF_ATOM }
            ?.let { outlineTexts.getOrNull(r.i32(it.start)) } ?: own
        val paragraphs = buildParagraphs(r, records, text, ppt9Numbering(shapeKids))
        if (paragraphs.isEmpty()) return null
        return TextBody(
            paragraphs = paragraphs,
            anchor = when (props.int(P_ANCHOR_TEXT) ?: 0) {
                1, 4, 7 -> VAnchor.MIDDLE
                2, 5, 8, 9 -> VAnchor.BOTTOM
                else -> VAnchor.TOP
            },
            insetLeftEmu = (props.int(P_TEXT_LEFT) ?: 91440).toLong(),
            insetTopEmu = (props.int(P_TEXT_TOP) ?: 45720).toLong(),
            insetRightEmu = (props.int(P_TEXT_RIGHT) ?: 91440).toLong(),
            insetBottomEmu = (props.int(P_TEXT_BOTTOM) ?: 45720).toLong(),
            // Placeholder text that overflows its box is shrunk to fit, as LibreOffice
            // does for the same file (5.31's code listing, 5.43's side text) while
            // leaving text that fits alone. This only works because the bundled
            // metric-compatible fonts measure text the way PowerPoint did; with the
            // device's own fonts it shrank slides that actually fit.
            // Titles are never shrunk (5.45's title starts with a blank line and would
            // otherwise drop to half size; LibreOffice lets it extend past the box instead).
            autoFit = placeholder && !isTitle(records),
            autoFitFloor = 0.5f
        )
    }

    private fun isTitle(records: List<Rec>): Boolean {
        val header = records.firstOrNull { it.type == RT.TEXT_HEADER_ATOM && it.length >= 4 } ?: return false
        val type = r.i32(header.start)
        return type == TextStyles.TYPE_TITLE || type == TextStyles.TYPE_CENTER_TITLE
    }

    /** Auto-numbering lives in a PowerPoint 2000 extension: the shape's ClientData ->
     *  ProgTags -> ProgBinaryTag named "___PPT9" -> BinaryTagData -> StyleTextProp9Atom. */
    private fun ppt9Numbering(shapeKids: List<Rec>): List<AutoNumber?> {
        val clientData = shapeKids.firstOrNull { it.type == RT.CLIENT_DATA } ?: return emptyList()
        val tags = r.child(clientData, RT.PROG_TAGS) ?: return emptyList()
        for (tag in r.children(tags).filter { it.type == RT.PROG_BINARY_TAG }) {
            val parts = r.children(tag)
            val name = parts.firstOrNull { it.type == RT.CSTRING }?.let { String(r.data, it.start, it.length, Charsets.UTF_16LE) }
            if (name != "___PPT9") continue
            val blob = parts.firstOrNull { it.type == RT.BINARY_TAG_DATA } ?: continue
            val atom = r.children(blob).firstOrNull { it.type == RT.STYLE_TEXT_PROP9_ATOM } ?: continue
            return readStyleTextProp9(r, atom)
        }
        return emptyList()
    }

    private fun readBackground(sp: Rec): Background? {
        val kids = r.children(sp)
        val fsp = kids.firstOrNull { it.type == RT.FSP && it.length >= 8 } ?: return null
        if (r.i32(fsp.start + 4) and F_BACKGROUND == 0) return null
        val opts = kids.filter { it.type == RT.FOPT || it.type == RT.TERTIARY_FOPT }
        val props = ShapeProps(ShapeProps.read(r, opts), emptyMap(), ShapeProps.readComplex(r, opts))
        if (props.bool(P_FILL_BOOLS, 4, 20) == false) return null
        val fillType = props.int(P_FILL_TYPE) ?: 0
        // 2 = texture, 3 = picture: the fill is an image from the blip store.
        if (fillType == 2 || fillType == 3) {
            props.int(P_FILL_BLIP)?.let { blips.blip(it) }?.let { return Background.Picture(it) }
        }
        // A background's linear shading is stored running the opposite way from a
        // shape's: LibreOffice reads (and writes) it like that, and renders its own
        // file's top-to-bottom white-to-gray background as intended only this way.
        gradient(props)?.let { g ->
            return Background.Shaded(if (g.radial) g else g.copy(colors = g.colors.reversed(), positions = g.positions.reversed().map { 1f - it }))
        }
        return resolveColorRef(props.int(P_FILL_COLOR) ?: 0xFFFFFF, text.scheme)?.let { Background.Solid(it) }
    }

    private companion object {
        const val OUTLINE_TEXT_REF_ATOM = 0x0F9E
    }
}
