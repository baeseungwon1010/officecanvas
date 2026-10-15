package io.github.baeseungwon1010.officecanvas.ppt

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Record type ids used by this package (MS-PPT outline records, MS-ODRAW shape records). */
internal object RT {
    const val DOCUMENT = 0x03E8
    const val DOCUMENT_ATOM = 0x03E9
    const val SLIDE = 0x03EE
    const val SLIDE_ATOM = 0x03EF
    const val ENVIRONMENT = 0x03F2
    const val MAIN_MASTER = 0x03F8
    const val SLIDE_SHOW_INFO_ATOM = 0x03F9
    const val DRAWING_GROUP = 0x040B
    const val DRAWING = 0x040C
    const val COLOR_SCHEME_ATOM = 0x07F0
    const val FONT_COLLECTION = 0x07D5
    const val PLACEHOLDER_ATOM = 0x0BC3
    const val TEXT_HEADER_ATOM = 0x0F9F
    const val TEXT_CHARS_ATOM = 0x0FA0
    const val STYLE_TEXT_PROP_ATOM = 0x0FA1
    const val TEXT_MASTER_STYLE_ATOM = 0x0FA3
    const val TEXT_RULER_ATOM = 0x0FA6
    const val TX_INTERACTIVE_INFO_ATOM = 0x0FDF
    const val TEXT_CF_EXCEPTION_ATOM = 0x0FA4
    const val TEXT_PF_EXCEPTION_ATOM = 0x0FA5
    const val TEXT_BYTES_ATOM = 0x0FA8
    const val FONT_ENTITY_ATOM = 0x0FB7
    const val SLIDE_NUMBER_MC_ATOM = 0x0FD8
    const val SLIDE_LIST_WITH_TEXT = 0x0FF0
    const val SLIDE_PERSIST_ATOM = 0x03F3
    const val USER_EDIT_ATOM = 0x0FF5
    const val PERSIST_DIRECTORY_ATOM = 0x1772
    const val CSTRING = 0x0FBA
    const val STYLE_TEXT_PROP9_ATOM = 0x0FAC
    const val PROG_TAGS = 0x1388
    const val PROG_BINARY_TAG = 0x138A
    const val BINARY_TAG_DATA = 0x138B

    const val DGG_CONTAINER = 0xF000
    const val BSTORE_CONTAINER = 0xF001
    const val DG_CONTAINER = 0xF002
    const val SPGR_CONTAINER = 0xF003
    const val SP_CONTAINER = 0xF004
    const val FBSE = 0xF007
    const val FSPGR = 0xF009
    const val FSP = 0xF00A
    const val FOPT = 0xF00B
    const val CLIENT_TEXTBOX = 0xF00D
    const val CHILD_ANCHOR = 0xF00F
    const val CLIENT_ANCHOR = 0xF010
    const val CLIENT_DATA = 0xF011
    const val BLIP_EMF = 0xF01A
    const val BLIP_WMF = 0xF01B
    const val BLIP_PICT = 0xF01C
    const val BLIP_TIFF = 0xF020
    const val BLIP_DIB = 0xF01F
    const val BLIP_JPEG_CMYK = 0xF02A
    const val BLIP_JPEG = 0xF01D
    const val BLIP_PNG = 0xF01E
    const val TERTIARY_FOPT = 0xF122
}

/** One record: header offset, type, instance, whether it is a container, and
 *  the [start, end) range of its payload (clamped to the stream). */
internal data class Rec(val offset: Int, val type: Int, val instance: Int, val isContainer: Boolean, val start: Int, val end: Int) {
    val length: Int get() = end - start
}

/** Little-endian reads and record navigation over one stream. */
internal class RecordReader(val data: ByteArray) {
    private val buf: ByteBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    fun u8(off: Int): Int = data[off].toInt() and 0xFF
    fun u16(off: Int): Int = buf.getShort(off).toInt() and 0xFFFF
    fun i16(off: Int): Int = buf.getShort(off).toInt()
    fun i32(off: Int): Int = buf.getInt(off)

    fun record(offset: Int): Rec? {
        if (offset < 0 || offset + 8 > data.size) return null
        val verInstance = u16(offset)
        val len = i32(offset + 4)
        val start = offset + 8
        val end = (start.toLong() + len).coerceAtMost(data.size.toLong()).toInt()
        if (end < start) return null
        // recVer 0xF marks a container (payload is child records), per MS-PPT/MS-ODRAW.
        return Rec(offset, u16(offset + 2), verInstance ushr 4, verInstance and 0xF == 0xF, start, end)
    }

    fun children(container: Rec): List<Rec> {
        val out = mutableListOf<Rec>()
        var off = container.start
        while (off + 8 <= container.end) {
            val rec = record(off) ?: break
            out += rec
            if (rec.end <= off) break
            off = rec.end
        }
        return out
    }

    fun child(container: Rec, type: Int): Rec? = children(container).firstOrNull { it.type == type }
}
