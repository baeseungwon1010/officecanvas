package io.github.baeseungwon1010.officecanvas.util

/**
 * Bounds on what a document can make the library do. Each is far beyond what real
 * documents need, and each closes a way for a crafted file to exhaust memory, the
 * stack, storage (pages are usually saved by the caller) or time.
 */
internal object Limits {
    /** Pages or slides rendered from one document; the rest are left out. */
    const val MAX_PAGES = 1000

    /** Nesting of groups (shapes in groups in groups) and of tables in tables. */
    const val MAX_NESTING = 32

    /** A .ppt is read into memory whole. */
    const val MAX_PPT_BYTES = 512L shl 20
}
