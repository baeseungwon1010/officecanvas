package io.github.baeseungwon1010.officecanvas.ppt

internal fun argb(r: Int, g: Int, b: Int, a: Int = 0xFF): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

/**
 * A slide's eight-color scheme (background, text/lines, shadow, title text,
 * fills, accent, accent+hyperlink, accent+followed hyperlink). Shape and text
 * colors are often stored as an index into it rather than as RGB.
 */
internal class ColorScheme(private val colors: IntArray) {
    operator fun get(index: Int): Int? = colors.getOrNull(index)

    companion object {
        fun read(r: RecordReader, atom: Rec): ColorScheme? {
            if (atom.length < 32) return null
            return ColorScheme(IntArray(8) { i ->
                val o = atom.start + 4 * i
                argb(r.u8(o), r.u8(o + 1), r.u8(o + 2))
            })
        }

        val DEFAULT = ColorScheme(intArrayOf(
            argb(255, 255, 255), argb(0, 0, 0), argb(128, 128, 128), argb(0, 0, 0),
            argb(0xBB, 0xE0, 0xE3), argb(0x33, 0x33, 0x99), argb(0, 0x99, 0x99), argb(0x99, 0xCC, 0)
        ))
    }
}

/** MS-ODRAW OfficeArtCOLORREF: RGB bytes plus a flag byte that can turn the
 *  first byte into a scheme index. System/palette indexes can't be resolved
 *  without the application's palette, so they yield null. */
internal fun resolveColorRef(value: Int, scheme: ColorScheme): Int? {
    val red = value and 0xFF
    val green = (value ushr 8) and 0xFF
    val blue = (value ushr 16) and 0xFF
    val flags = (value ushr 24) and 0xFF
    return when {
        flags and 0x08 != 0 -> scheme[red]
        flags and (0x01 or 0x10) != 0 -> null
        else -> argb(red, green, blue)
    }
}

/** MS-PPT ColorIndexStruct used by text: RGB, or a scheme index when the last
 *  byte is below 8; 0xFE marks a literal RGB value. */
internal fun resolveTextColor(value: Int, scheme: ColorScheme): Int? {
    val index = (value ushr 24) and 0xFF
    return when {
        index == 0xFE -> argb(value and 0xFF, (value ushr 8) and 0xFF, (value ushr 16) and 0xFF)
        index < 8 -> scheme[index]
        else -> null
    }
}
