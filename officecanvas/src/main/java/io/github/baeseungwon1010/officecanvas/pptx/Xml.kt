package io.github.baeseungwon1010.officecanvas.pptx

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.util.zip.ZipFile
import io.github.baeseungwon1010.officecanvas.util.SafeXml

internal const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
internal const val NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main"
internal const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
internal const val NS_MC = "http://schemas.openxmlformats.org/markup-compatibility/2006"
internal const val NS_DSP = "http://schemas.microsoft.com/office/drawing/2008/diagram"
internal const val NS_ASVG = "http://schemas.microsoft.com/office/drawing/2016/SVG/main"
internal const val NS_DGM = "http://schemas.openxmlformats.org/drawingml/2006/diagram"

/** A part of the package as a DOM, or null when it is missing, too large, carries a
 *  DOCTYPE or doesn't parse (see [SafeXml]). */
internal fun ZipFile.xml(path: String): Document? = SafeXml.parse(this, path)

/**
 * Relationship files map the r:id used inside a part to the file it points at.
 * Targets with a TargetMode of External are dropped: they address something outside
 * the package (an http:// image, say), and resolving one would turn opening a file
 * into fetching a URL on the document's behalf. Targets come back resolved to full
 * zip entry names.
 */
internal fun ZipFile.rels(partPath: String): Map<String, String> {
    val dir = partPath.substringBeforeLast('/', "")
    val name = partPath.substringAfterLast('/')
    val relPath = if (dir.isEmpty()) "_rels/$name.rels" else "$dir/_rels/$name.rels"
    val doc = xml(relPath) ?: return emptyMap()
    return doc.documentElement.children()
        .filter { it.getAttribute("TargetMode") != "External" }
        .mapNotNull { rel ->
            val id = rel.getAttribute("Id").ifBlank { return@mapNotNull null }
            val target = rel.getAttribute("Target").ifBlank { return@mapNotNull null }
            id to resolvePath(dir, target)
        }.toMap()
}

/** Resolves a relationship target, which may be relative ("../media/x.png"). */
internal fun resolvePath(baseDir: String, target: String): String {
    if (target.startsWith("/")) return target.removePrefix("/")
    val parts = baseDir.split('/').filter { it.isNotEmpty() }.toMutableList()
    for (segment in target.split('/')) {
        when (segment) {
            ".", "" -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
            else -> parts += segment
        }
    }
    return parts.joinToString("/")
}

internal fun Node.children(): List<Element> {
    val out = mutableListOf<Element>()
    val list = childNodes
    for (i in 0 until list.length) (list.item(i) as? Element)?.let { out += it }
    return out
}

/** First descendant with this namespace and name, searched depth-first. */
internal fun Node.first(ns: String, name: String): Element? {
    for (child in children()) {
        if (child.namespaceURI == ns && child.localName == name) return child
        child.first(ns, name)?.let { return it }
    }
    return null
}

/** Direct children with this namespace and name — not descendants, so that a
 *  paragraph inside a nested table does not get collected as the outer body's. */
internal fun Node.all(ns: String, name: String): List<Element> =
    children().filter { it.namespaceURI == ns && it.localName == name }

internal fun Node.child(ns: String, name: String): Element? =
    children().firstOrNull { it.namespaceURI == ns && it.localName == name }

/** Every descendant with this namespace and name, in document order. */
internal fun Node.descendants(ns: String, name: String): List<Element> {
    val out = mutableListOf<Element>()
    for (child in children()) {
        if (child.namespaceURI == ns && child.localName == name) out += child
        out += child.descendants(ns, name)
    }
    return out
}

internal fun Element.long(attr: String): Long = getAttribute(attr).toLongOrNull() ?: 0L
internal fun Element.longOrNull(attr: String): Long? = getAttribute(attr).toLongOrNull()
internal fun Element.attr(name: String): String? = getAttribute(name).ifBlank { null }
