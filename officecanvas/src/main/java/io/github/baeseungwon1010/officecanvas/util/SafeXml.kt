package io.github.baeseungwon1010.officecanvas.util

import org.w3c.dom.Document
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory

/**
 * XML parsing for parts of untrusted documents.
 *
 * Office and Hangul XML never carries a DOCTYPE, so any part with one is refused
 * before it reaches a parser: that closes entity expansion and external entities
 * whatever the platform's parser does with them. The hardening features are still
 * requested where the parser supports them, one at a time, because Android's
 * built-in parser rejects every feature it doesn't know (requesting them all in one
 * go made every part fail to parse on devices).
 */
internal object SafeXml {

    /** Larger parts are refused: real slide, section and drawing parts are well under
     *  a few MB, and a DOM costs many times its text size. */
    const val MAX_PART_BYTES = 32 shl 20

    fun parse(bytes: ByteArray, namespaceAware: Boolean = true): Document? {
        if (bytes.size > MAX_PART_BYTES || hasDoctype(bytes)) return null
        return try {
            builder(namespaceAware).parse(bytes.inputStream())
        } catch (e: Exception) {
            null
        }
    }

    fun parse(zip: ZipFile, path: String, namespaceAware: Boolean = true): Document? =
        zip.getEntry(path)?.let { zip.readCapped(it, MAX_PART_BYTES) }?.let { parse(it, namespaceAware) }

    private fun builder(namespaceAware: Boolean): DocumentBuilder = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = namespaceAware
        isExpandEntityReferences = false
        for ((feature, value) in FEATURES) {
            try {
                setFeature(feature, value)
            } catch (e: Exception) {
                // Not supported by this parser (Android's); the DOCTYPE check covers it.
            }
        }
        try {
            isXIncludeAware = false
        } catch (e: Exception) {
            // Android's parser throws even when XInclude is being turned off.
        }
    }.newDocumentBuilder()

    private val FEATURES = listOf(
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false
    )

    private val DOCTYPE = "<!DOCTYPE".toByteArray(Charsets.US_ASCII)

    /** "<!DOCTYPE" in UTF-8 or UTF-16 (either byte order). */
    fun hasDoctype(bytes: ByteArray): Boolean {
        fun at(i: Int, step: Int, shift: Int): Boolean {
            for (k in DOCTYPE.indices) {
                val p = i + k * step + shift
                if (p >= bytes.size || bytes[p] != DOCTYPE[k]) return false
                if (step == 2 && bytes[i + k * step + (1 - shift)] != 0.toByte()) return false
            }
            return true
        }
        for (i in bytes.indices) {
            if (bytes[i] == '<'.code.toByte() && at(i, 1, 0)) return true
            if (i + 1 < bytes.size && bytes[i] == '<'.code.toByte() && bytes[i + 1] == 0.toByte() && at(i, 2, 0)) return true
            if (i + 1 < bytes.size && bytes[i] == 0.toByte() && bytes[i + 1] == '<'.code.toByte() && at(i, 2, 1)) return true
        }
        return false
    }
}

/** Reads a whole stream, or null when it is longer than [limit] bytes. */
internal fun InputStream.readCapped(limit: Int): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val n = read(buf)
        if (n < 0) break
        total += n
        if (total > limit) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** A zip entry's bytes, or null when it inflates to more than [limit] bytes
 *  (whatever size the entry claims: a zip bomb can lie in its header). */
internal fun ZipFile.readCapped(entry: ZipEntry, limit: Int): ByteArray? {
    if (entry.size > limit) return null
    return try {
        getInputStream(entry).use { it.readCapped(limit) }
    } catch (e: java.io.IOException) {
        null
    }
}
