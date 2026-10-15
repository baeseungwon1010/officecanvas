package io.github.baeseungwon1010.officecanvas.util

/**
 * Bytes read lately (pictures), by key, up to [maxBytes] in all, the least
 * recently used dropped first: a picture drawn on every page or slide (a logo)
 * is read and inflated once, without keeping a document's pictures all in memory.
 */
internal class RecentBytes<K>(private val maxBytes: Long) {
    private val map = object : LinkedHashMap<K, ByteArray>(16, 0.75f, true) {}
    private var total = 0L

    /** The bytes for [key], from [load] the first time (null isn't kept). */
    fun get(key: K, load: () -> ByteArray?): ByteArray? = synchronized(map) {
        map[key]?.let { return it }
        val bytes = load() ?: return null
        if (bytes.size <= maxBytes / 4) {
            map[key] = bytes
            total += bytes.size
            val it = map.entries.iterator()
            while (total > maxBytes && it.hasNext()) { total -= it.next().value.size; it.remove() }
        }
        bytes
    }
}
