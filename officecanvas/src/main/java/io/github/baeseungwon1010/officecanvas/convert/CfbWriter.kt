package io.github.baeseungwon1010.officecanvas.convert

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes an OLE2 compound file (CFB, version 3: 512-byte sectors), the container
 * of .hwp files. Streams under 4096 bytes go in the mini stream (64-byte sectors)
 * as the format requires; storages ("BodyText", "BinData") hold their streams in
 * red-black trees built as all-black, which every reader accepts.
 */
internal class CfbWriter {

    private class Node(val name: String, val stream: ByteArray?) {
        val children = ArrayList<Node>()
        var id = 0
        var left = NONE; var right = NONE; var child = NONE
        var start = END; var size = 0L
    }

    private val root = Node("Root Entry", null)

    /** Adds a stream at [path] ("BodyText/Section0"), creating its storages. */
    fun add(path: String, data: ByteArray) {
        val parts = path.split('/')
        var dir = root
        for (p in parts.dropLast(1)) dir = dir.children.firstOrNull { it.name == p && it.stream == null } ?: Node(p, null).also { dir.children += it }
        dir.children += Node(parts.last(), data)
    }

    fun write(out: OutputStream) {
        // Directory order: root first, then depth first.
        val nodes = ArrayList<Node>()
        fun collect(n: Node) { n.id = nodes.size; nodes += n; for (c in n.children) collect(c) }
        collect(root)
        // Each storage's children as a balanced binary tree in CFB name order.
        for (n in nodes) if (n.stream == null && n.children.isNotEmpty()) {
            val sorted = n.children.sortedWith(compareBy<Node> { it.name.length }.thenBy { it.name.uppercase() })
            fun build(lo: Int, hi: Int): Int {
                if (lo > hi) return NONE
                val mid = (lo + hi) / 2
                val m = sorted[mid]
                m.left = build(lo, mid - 1); m.right = build(mid + 1, hi)
                return m.id
            }
            n.child = build(0, sorted.size - 1)
        }
        // The mini stream holds the small streams, in 64-byte sectors.
        val mini = ByteArrayOutputStream()
        val miniFat = ArrayList<Int>()
        for (n in nodes) {
            val s = n.stream ?: continue
            n.size = s.size.toLong()
            if (s.size >= CUTOFF || s.isEmpty()) { if (s.isEmpty()) n.start = END; continue }
            val first = miniFat.size
            val count = (s.size + MINI - 1) / MINI
            for (k in 0 until count) miniFat += if (k == count - 1) END else first + k + 1
            n.start = first
            mini.write(s)
            val pad = count * MINI - s.size
            if (pad > 0) mini.write(ByteArray(pad))
        }
        // Sectors: big streams, the mini stream, the mini FAT, the directory, then the FAT.
        val sectors = ArrayList<ByteArray>()
        val fat = ArrayList<Int>()
        fun place(data: ByteArray): Int {
            if (data.isEmpty()) return END
            val first = sectors.size
            val count = (data.size + SECTOR - 1) / SECTOR
            for (k in 0 until count) {
                val chunk = ByteArray(SECTOR)
                System.arraycopy(data, k * SECTOR, chunk, 0, minOf(SECTOR, data.size - k * SECTOR))
                sectors += chunk
                fat += if (k == count - 1) END else first + k + 1
            }
            return first
        }
        for (n in nodes) { val s = n.stream ?: continue; if (s.size >= CUTOFF) n.start = place(s) }
        val miniBytes = mini.toByteArray()
        root.start = place(miniBytes); root.size = miniBytes.size.toLong()
        if (miniBytes.isEmpty()) root.start = END
        val miniFatBytes = ints(miniFat, SECTOR / 4)
        val miniFatStart = if (miniFat.isEmpty()) END else place(miniFatBytes)
        val dir = ByteArray(((nodes.size + 3) / 4) * 4 * 128)
        for ((i, n) in nodes.withIndex()) entry(n).copyInto(dir, i * 128)
        // Unused directory slots are empty entries.
        for (i in nodes.size until dir.size / 128) emptyEntry().copyInto(dir, i * 128)
        val dirStart = place(dir)
        // The FAT also lists its own sectors, and the DIFAT's: the header names the
        // first 109 FAT sectors, DIFAT sectors 127 more each (a file over about 7 MB).
        var fatSectors = 1
        var difatSectors: Int
        while (true) {
            difatSectors = if (fatSectors > HEADER_FATS) (fatSectors - HEADER_FATS + 126) / 127 else 0
            if (sectors.size + fatSectors + difatSectors <= fatSectors * (SECTOR / 4)) break
            fatSectors++
        }
        val fatStart = sectors.size
        repeat(fatSectors) { fat += FAT_SECTOR }
        val difatStart = fatStart + fatSectors
        repeat(difatSectors) { fat += DIFAT_SECTOR }
        val fatBytes = ints(fat, fatSectors * SECTOR / 4)
        for (k in 0 until fatSectors) sectors += fatBytes.copyOfRange(k * SECTOR, (k + 1) * SECTOR)
        for (k in 0 until difatSectors) {
            val b = ByteBuffer.allocate(SECTOR).order(ByteOrder.LITTLE_ENDIAN)
            for (j in 0 until 127) { val i = HEADER_FATS + k * 127 + j; b.putInt(if (i < fatSectors) fatStart + i else FREE) }
            b.putInt(if (k < difatSectors - 1) difatStart + k + 1 else END)
            sectors += b.array()
        }
        out.write(header(fatSectors, fatStart, dirStart, miniFatStart, (miniFat.size + SECTOR / 4 - 1) / (SECTOR / 4),
            if (difatSectors > 0) difatStart else END, difatSectors))
        for (s in sectors) out.write(s)
        out.flush()
    }

    private fun header(fatSectors: Int, fatStart: Int, dirStart: Int, miniFatStart: Int, miniFatSectors: Int, difatStart: Int, difatSectors: Int): ByteArray {
        val b = ByteBuffer.allocate(SECTOR).order(ByteOrder.LITTLE_ENDIAN)
        b.put(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))
        b.put(ByteArray(16))
        b.putShort(0x3E); b.putShort(3); b.putShort(0xFFFE.toShort()); b.putShort(9); b.putShort(6)
        b.put(ByteArray(6)); b.putInt(0); b.putInt(fatSectors); b.putInt(dirStart); b.putInt(0); b.putInt(CUTOFF)
        b.putInt(miniFatStart); b.putInt(miniFatSectors); b.putInt(difatStart); b.putInt(difatSectors)
        for (k in 0 until HEADER_FATS) b.putInt(if (k < fatSectors) fatStart + k else FREE)
        return b.array()
    }

    private fun entry(n: Node): ByteArray {
        val b = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        val name = n.name.take(31)
        for (ch in name) b.putChar(ch)
        b.position(64)
        b.putShort(((name.length + 1) * 2).toShort())
        b.put(if (n === root) 5 else if (n.stream == null) 1 else 2)
        b.put(1) // black
        b.putInt(n.left); b.putInt(n.right); b.putInt(n.child)
        b.put(ByteArray(16)); b.putInt(0); b.putLong(0); b.putLong(0)
        b.putInt(n.start); b.putInt(n.size.toInt()); b.putInt(0)
        return b.array()
    }

    private fun emptyEntry(): ByteArray {
        val b = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        b.position(68); b.putInt(NONE); b.putInt(NONE); b.putInt(NONE)
        return b.array()
    }

    private fun ints(values: List<Int>, slots: Int): ByteArray {
        val count = maxOf(slots, ((values.size + SECTOR / 4 - 1) / (SECTOR / 4)) * (SECTOR / 4))
        val b = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) b.putInt(values.getOrElse(i) { FREE })
        return b.array()
    }

    private companion object {
        const val SECTOR = 512
        const val MINI = 64
        const val CUTOFF = 4096
        const val END = -2
        const val FREE = -1
        const val FAT_SECTOR = -3
        const val DIFAT_SECTOR = -4
        const val HEADER_FATS = 109
        const val NONE = -1
    }
}
