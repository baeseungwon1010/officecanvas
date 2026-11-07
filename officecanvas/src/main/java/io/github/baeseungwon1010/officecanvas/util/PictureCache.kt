package io.github.baeseungwon1010.officecanvas.util

import android.graphics.Bitmap

/**
 * Decoded pictures kept between draws of a document, most recently used last,
 * within [maxBytes]. A picture may be held at several sizes (a full-page image,
 * then a region at 4x); a lookup takes the one [SafeBitmaps.decode] would have
 * made for the size asked, so a cached draw looks like a fresh one.
 *
 * Bitmaps evicted or cleared are recycled: callers draw a bitmap straight away
 * (onto a bitmap-backed canvas) and never keep it.
 */
internal class PictureCache(private val maxBytes: Long) {

    private class Entry(val key: Any, val bitmap: Bitmap, val largest: Boolean)

    private val entries = ArrayList<Entry>()

    /** Bytes of the bitmaps held. */
    var bytes = 0L
        private set

    val size: Int get() = entries.size

    /** A cached decoding of [key] suited to drawing [targetW] x [targetH] px, or null. */
    fun get(key: Any, targetW: Float, targetH: Float): Bitmap? {
        val tw = targetW.coerceAtLeast(1f)
        val th = targetH.coerceAtLeast(1f)
        for (i in entries.indices.reversed()) {
            val e = entries[i]
            if (e.key != key) continue
            val b = e.bitmap
            // What decode stops at: no longer at least twice the target both ways,
            // from a size that was (or from the picture's own size).
            val tooBig = b.width >= tw * 2 && b.height >= th * 2 && b.width > 2 && b.height > 2
            val bigEnough = e.largest || (b.width >= tw - 1f && b.height >= th - 1f)
            if (!tooBig && bigEnough && !b.isRecycled) {
                entries.removeAt(i)
                entries += e
                return b
            }
        }
        return null
    }

    /**
     * Keeps [bitmap] for [key]; [largest] when no bigger decoding exists. Returns
     * false (the caller then recycles it after drawing) when it is too big to keep.
     */
    fun put(key: Any, bitmap: Bitmap, largest: Boolean): Boolean {
        val size = bitmap.allocationByteCount.toLong()
        if (size > maxBytes / 2) return false
        entries += Entry(key, bitmap, largest)
        bytes += size
        while (bytes > maxBytes && entries.size > 1) {
            val old = entries.removeAt(0)
            bytes -= old.bitmap.allocationByteCount
            old.bitmap.recycle()
        }
        return true
    }

    fun clear() {
        for (e in entries) e.bitmap.recycle()
        entries.clear()
        bytes = 0
    }
}
