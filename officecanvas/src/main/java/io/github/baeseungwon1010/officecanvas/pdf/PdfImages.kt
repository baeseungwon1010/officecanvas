package io.github.baeseungwon1010.officecanvas.pdf

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Pictures (8.9) as files a word processor takes: JPEG data as it is, samples
 * (any bit depth, gray, RGB, CMYK, indexed, with a soft mask or as a stencil mask)
 * encoded as PNG. JPEG 2000, CCITT and JBIG2 data aren't decoded.
 */
internal object PdfImages {

    /** [data] is a PNG or JPEG file of [width] x [height] pixels. */
    class Image(val data: ByteArray, val png: Boolean, val width: Int, val height: Int)

    const val MAX_PIXELS = 16_000_000L

    fun decode(doc: PdfDocument, s: PdfStream, fill: Int): Image? = try {
        val dec = doc.decode(s) ?: null
        dec?.let { build(doc, s.dict, it.bytes, it.imageCodec, null, fill, doc.resolve(s.dict["SMask"]) as? PdfStream) }
    } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }

    fun decodeInline(doc: PdfDocument, dict: PdfDict, raw: ByteArray, resources: PdfDict?, fill: Int): Image? = try {
        fun n(vararg k: String) = k.firstNotNullOfOrNull { dict[it] }
        val filters = when (val f = n("F", "Filter")) {
            is PdfName -> listOf(f.name); is PdfArray -> f.items.mapNotNull { (it as? PdfName)?.name }; else -> emptyList()
        }
        val parms = when (val p = n("DP", "DecodeParms")) { is PdfDict -> listOf(p); is PdfArray -> p.items.map { it as? PdfDict }; else -> emptyList() }
        val dec = PdfFilters.decode(raw, filters, parms)
        // Inline abbreviations, and named color spaces from the resources.
        val full = HashMap<String, PdfObj>()
        for ((k, v) in dict.map) full[when (k) { "W" -> "Width"; "H" -> "Height"; "BPC" -> "BitsPerComponent"; "CS" -> "ColorSpace"; "D" -> "Decode"; "IM" -> "ImageMask"; "I" -> "Interpolate"; else -> k }] = v
        (full["ColorSpace"] as? PdfName)?.name?.let { name ->
            full["ColorSpace"] = when (name) { "G" -> PdfName("DeviceGray"); "RGB" -> PdfName("DeviceRGB"); "CMYK" -> PdfName("DeviceCMYK"); "I" -> PdfName("Indexed")
                else -> doc.dict(resources?.get("ColorSpace"))?.get(name) ?: PdfName(name) }
        }
        build(doc, PdfDict(full), dec.bytes, dec.imageCodec, null, fill, null)
    } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }

    /** How many color components a color space has. */
    fun colorComponents(doc: PdfDocument, cs: PdfObj?): Int? = when (val c = doc.resolve(cs)) {
        is PdfName -> when (c.name) { "DeviceGray", "CalGray", "G" -> 1; "DeviceRGB", "CalRGB", "RGB", "Lab" -> 3; "DeviceCMYK", "CMYK" -> 4; else -> null }
        is PdfArray -> when ((doc.resolve(c[0]) as? PdfName)?.name) {
            "ICCBased" -> doc.dict(c[1])?.int("N") ?: 3
            "Indexed", "I" -> 1
            "Separation" -> 1
            "DeviceN" -> doc.array(c[1])?.size ?: 1
            "CalRGB", "Lab" -> 3
            "CalGray" -> 1
            "Pattern" -> 0
            else -> null
        }
        else -> null
    }

    private fun build(doc: PdfDocument, d: PdfDict, data: ByteArray, codec: String?, unused: Any?, fill: Int, smask: PdfStream?): Image? {
        val w = (doc.num(d["Width"]) ?: return null).toInt()
        val h = (doc.num(d["Height"]) ?: return null).toInt()
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) return null
        if (codec == "DCTDecode") return Image(data, false, w, h)
        if (codec != null) return null
        val mask = (doc.resolve(d["ImageMask"]) as? PdfBool)?.value == true
        val bpc = if (mask) 1 else (doc.num(d["BitsPerComponent"])?.toInt() ?: 8).coerceIn(1, 16)
        val decode = doc.array(d["Decode"])?.items?.map { doc.num(it) ?: 0.0 }
        if (mask) {
            // A stencil: the fill color where the sample is 0 (or 1 with Decode [1 0]).
            val paintOn = if (decode != null && decode.size >= 2 && decode[0] > decode[1]) 1 else 0
            val rgba = ByteArray(w * h * 4)
            val rowBytes = (w + 7) / 8
            for (y in 0 until h) for (x in 0 until w) {
                val i = y * rowBytes + x / 8
                val bit = if (i < data.size) (data[i].toInt() shr (7 - x % 8)) and 1 else 1
                val o = (y * w + x) * 4
                rgba[o] = (fill shr 16).toByte(); rgba[o + 1] = (fill shr 8).toByte(); rgba[o + 2] = fill.toByte()
                rgba[o + 3] = if (bit == paintOn) -1 else 0
            }
            return Image(png(rgba, w, h, true), true, w, h)
        }
        val cs = doc.resolve(d["ColorSpace"])
        val model = ColorModel.of(doc, cs) ?: ColorModel.Device(1)
        val comps = model.components
        val rowBits = w.toLong() * comps * bpc
        val rowBytes = ((rowBits + 7) / 8).toInt()
        val maxV = (1 shl bpc) - 1
        val alpha = smask?.let { softMask(doc, it, w, h) }
        val out = ByteArray(w * h * if (alpha != null) 4 else 3)
        val samples = IntArray(comps)
        for (y in 0 until h) {
            val rowStart = y * rowBytes
            for (x in 0 until w) {
                for (k in 0 until comps) {
                    val bitPos = (x.toLong() * comps + k) * bpc
                    var v = sample(data, rowStart, bitPos, bpc)
                    if (decode != null && decode.size >= 2 * comps && model !is ColorModel.Indexed) {
                        val lo = decode[2 * k]; val hi = decode[2 * k + 1]
                        v = ((lo + v.toDouble() / maxV * (hi - lo)) * maxV).toInt().coerceIn(0, maxV)
                    }
                    samples[k] = v
                }
                val rgb = model.rgb(samples, maxV)
                val o = (y * w + x) * if (alpha != null) 4 else 3
                out[o] = (rgb shr 16).toByte(); out[o + 1] = (rgb shr 8).toByte(); out[o + 2] = rgb.toByte()
                if (alpha != null) out[o + 3] = alpha[y * w + x]
            }
        }
        return Image(png(out, w, h, alpha != null), true, w, h)
    }

    private fun sample(data: ByteArray, rowStart: Int, bitPos: Long, bpc: Int): Int {
        if (bpc == 8) { val i = rowStart + bitPos.toInt() / 8; return if (i < data.size) data[i].toInt() and 0xFF else 0 }
        if (bpc == 16) { val i = rowStart + bitPos.toInt() / 8; return if (i + 1 < data.size) ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF) else 0 }
        val i = rowStart + (bitPos / 8).toInt()
        if (i >= data.size) return 0
        val shift = 8 - bpc - (bitPos % 8).toInt()
        return (data[i].toInt() shr shift) and ((1 shl bpc) - 1)
    }

    /** A soft mask's gray samples, scaled to [w] x [h]. */
    private fun softMask(doc: PdfDocument, s: PdfStream, w: Int, h: Int): ByteArray? {
        val mw = doc.num(s.dict["Width"])?.toInt() ?: return null
        val mh = doc.num(s.dict["Height"])?.toInt() ?: return null
        if (mw <= 0 || mh <= 0 || mw.toLong() * mh > MAX_PIXELS) return null
        val dec = doc.decode(s) ?: return null
        if (dec.imageCodec != null) return null
        val bpc = (doc.num(s.dict["BitsPerComponent"])?.toInt() ?: 8).coerceIn(1, 16)
        val rowBytes = ((mw.toLong() * bpc + 7) / 8).toInt()
        val maxV = (1 shl bpc) - 1
        return ByteArray(w * h) { i ->
            val x = (i % w) * mw / w; val y = (i / w) * mh / h
            (sample(dec.bytes, y * rowBytes, x.toLong() * bpc, bpc) * 255 / maxV).toByte()
        }
    }

    private sealed class ColorModel(val components: Int) {
        abstract fun rgb(s: IntArray, maxV: Int): Int

        class Device(n: Int) : ColorModel(n) {
            override fun rgb(s: IntArray, maxV: Int): Int {
                fun f(i: Int) = s[i].toDouble() / maxV
                return when (components) {
                    1 -> PdfPageReader.gray(f(0))
                    3 -> PdfPageReader.rgbOf(f(0), f(1), f(2))
                    4 -> PdfPageReader.cmyk(f(0), f(1), f(2), f(3))
                    else -> PdfPageReader.gray(1 - f(0))
                }
            }
        }

        class Indexed(private val base: ColorModel, private val lookup: ByteArray, private val hival: Int) : ColorModel(1) {
            override fun rgb(s: IntArray, maxV: Int): Int {
                val i = s[0].coerceIn(0, hival)
                val n = base.components
                val vals = IntArray(n) { k -> lookup.getOrNull(i * n + k)?.toInt()?.and(0xFF) ?: 0 }
                return base.rgb(vals, 255)
            }
        }

        /** A separation or DeviceN tint: dark where the tint is full. */
        class Tint(n: Int) : ColorModel(n) {
            override fun rgb(s: IntArray, maxV: Int): Int = PdfPageReader.gray(1 - s.maxOrNull()!!.toDouble() / maxV)
        }

        companion object {
            fun of(doc: PdfDocument, cs: PdfObj?, depth: Int = 0): ColorModel? {
                if (depth > 4) return null
                return when (val c = doc.resolve(cs)) {
                    is PdfName -> when (c.name) {
                        "DeviceGray", "CalGray", "G" -> Device(1); "DeviceRGB", "CalRGB", "RGB" -> Device(3); "DeviceCMYK", "CMYK" -> Device(4)
                        else -> null
                    }
                    is PdfArray -> when ((doc.resolve(c[0]) as? PdfName)?.name) {
                        "ICCBased" -> Device((doc.dict(c[1])?.int("N") ?: 3).let { if (it in setOf(1, 3, 4)) it else 3 })
                        "CalRGB", "Lab" -> Device(3)
                        "CalGray" -> Device(1)
                        "Indexed", "I" -> {
                            val base = of(doc, c[1], depth + 1) ?: Device(3)
                            val hival = doc.num(c[2])?.toInt()?.coerceIn(0, 255) ?: 255
                            val lookup = when (val l = doc.resolve(c[3])) { is PdfString -> l.bytes; is PdfStream -> doc.streamBytes(l) ?: ByteArray(0); else -> ByteArray(0) }
                            Indexed(base, lookup, hival)
                        }
                        "Separation" -> Tint(1)
                        "DeviceN" -> Tint(doc.array(c[1])?.size?.coerceIn(1, 32) ?: 1)
                        else -> null
                    }
                    else -> null
                }
            }
        }
    }

    /** An 8-bit RGB or RGBA PNG. */
    fun png(pixels: ByteArray, w: Int, h: Int, alpha: Boolean): ByteArray {
        val bpp = if (alpha) 4 else 3
        val raw = ByteArray((w * bpp + 1) * h)
        for (y in 0 until h) {
            raw[y * (w * bpp + 1)] = 0
            System.arraycopy(pixels, y * w * bpp, raw, y * (w * bpp + 1) + 1, w * bpp)
        }
        val def = Deflater(6)
        val z = ByteArrayOutputStream(raw.size / 4 + 64)
        def.setInput(raw); def.finish()
        val buf = ByteArray(64 * 1024)
        while (!def.finished()) { val n = def.deflate(buf); z.write(buf, 0, n) }
        def.end()
        val out = ByteArrayOutputStream(z.size() + 64)
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        fun chunk(type: String, data: ByteArray) {
            fun int(v: Int) { out.write(v ushr 24); out.write(v ushr 16); out.write(v ushr 8); out.write(v) }
            int(data.size)
            val t = type.toByteArray(Charsets.ISO_8859_1)
            out.write(t); out.write(data)
            val crc = CRC32(); crc.update(t); crc.update(data)
            int(crc.value.toInt())
        }
        val ihdr = ByteArrayOutputStream()
        fun int(v: Int) { ihdr.write(v ushr 24); ihdr.write(v ushr 16); ihdr.write(v ushr 8); ihdr.write(v) }
        int(w); int(h); ihdr.write(8); ihdr.write(if (alpha) 6 else 2); ihdr.write(0); ihdr.write(0); ihdr.write(0)
        chunk("IHDR", ihdr.toByteArray())
        chunk("IDAT", z.toByteArray())
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }
}
