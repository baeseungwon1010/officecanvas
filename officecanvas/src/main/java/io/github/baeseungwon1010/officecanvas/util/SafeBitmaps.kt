package io.github.baeseungwon1010.officecanvas.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Decoding of embedded raster images with a pixel budget. A few kilobytes of PNG or
 * RLE BMP can declare tens of thousands of pixels on a side, so the size is read
 * first and the image subsampled until it fits.
 */
internal object SafeBitmaps {

    /** About 64 MB as ARGB_8888. */
    const val MAX_PIXELS = 16_000_000L

    /** Embedded pictures larger than this are skipped. */
    const val MAX_IMAGE_BYTES = 64 shl 20

    /**
     * Decodes at roughly the size it will be drawn ([targetW] x [targetH] px). A
     * single bilinear draw from a picture many times larger than its box (a 2000px
     * diagram into a 260px slot) samples only a few source pixels per output pixel,
     * so thin lines and small text vanish; subsampled decoding plus halving steps
     * averages them in instead. Null when the image can't be read or sized.
     */
    fun decode(bytes: ByteArray, targetW: Float, targetH: Float): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth.toLong()
        val h = bounds.outHeight.toLong()
        if (w <= 0 || h <= 0) return null
        var sample = 1
        val tw = targetW.coerceAtLeast(1f)
        val th = targetH.coerceAtLeast(1f)
        while (w / (sample * 2) >= tw * 2 && h / (sample * 2) >= th * 2) sample *= 2
        while ((w / sample) * (h / sample) > MAX_PIXELS) sample *= 2
        var bmp = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: OutOfMemoryError) {
            null
        } ?: return null
        while (bmp.width >= tw * 2 && bmp.height >= th * 2 && bmp.width > 2 && bmp.height > 2) {
            val half = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
            if (half !== bmp) bmp.recycle()
            bmp = half
        }
        return bmp
    }

    /** Decodes with only the pixel budget, when the drawn size isn't known. */
    fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth.toLong()
        val h = bounds.outHeight.toLong()
        if (w <= 0 || h <= 0) return null
        var sample = 1
        while ((w / sample) * (h / sample) > MAX_PIXELS) sample *= 2
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}
