package io.github.baeseungwon1010.officecanvas.ole

import io.github.baeseungwon1010.officecanvas.util.Limits
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Minimal reader for the OLE2/CFB (Compound File Binary) container format —
 * the outer shell of the legacy binary .ppt (and .doc, .xls) formats and of
 * Hangul's .hwp, replaced by zip-of-XML in the newer formats. Read-only: streams
 * are read by path ("BodyText/Section0") or, at the top level, by name.
 *
 * Spec: [MS-CFB] (Microsoft's own published Compound File Binary spec).
 *
 * Only the header, the FAT and the directory are read when the file is opened;
 * a stream is read from the file when it is asked for (positional reads, a run of
 * consecutive sectors at a time), so a large file is never held in memory whole.
 */
internal class Ole2Reader private constructor(private val channel: FileChannel) : Closeable {

    class FormatException(message: String) : Exception(message)

    private val fileSize = channel.size()
    /** The 512-byte header; every other part is read from the file as needed. */
    private val header = read(0, 512)
    private val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
    private fun readShort(offset: Int): Int = buf.getShort(offset).toInt() and 0xFFFF
    private fun readInt(offset: Int): Int = buf.getInt(offset)

    private val sectorSize: Int
    /** Whole sectors present in the file after the header. */
    private val sectorCount: Int
    private val miniStreamCutoff: Int
    private val fat: IntArray
    private val miniFat: IntArray
    private val miniStreamFirstSector: Int
    private val miniStreamSize: Long
    private val streams = mutableMapOf<String, Entry>()
    private val paths = mutableMapOf<String, Entry>()

    private data class Entry(val firstSector: Int, val size: Long)

    companion object {
        private const val MINI_SECTOR_SIZE = 64
        private val SIGNATURE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        private const val FREESECT = -1
        private const val ENDOFCHAIN = -2

        /** Opens [file], reading only its header, FAT and directory; streams are
         *  read from the file when asked for, so the whole file is never held in
         *  memory at once. Close the reader when done. */
        fun open(file: File): Ole2Reader {
            if (file.length() > Limits.MAX_PPT_BYTES) throw FormatException("file too large: ${file.length()} bytes")
            val channel = RandomAccessFile(file, "r").channel
            return try {
                Ole2Reader(channel)
            } catch (e: Throwable) {
                channel.close()
                throw e
            }
        }
    }

    override fun close() = channel.close()

    /** [length] bytes at [offset], or fewer when the file ends first. */
    private fun read(offset: Long, length: Int): ByteArray {
        val n = (fileSize - offset).coerceIn(0, length.toLong()).toInt()
        val out = ByteArray(n)
        val bb = ByteBuffer.wrap(out)
        while (bb.hasRemaining()) {
            if (channel.read(bb, offset + bb.position()) < 0) break
        }
        return out
    }

    /** One FAT or DIFAT sector as 32-bit values. */
    private fun sectorInts(sector: Int): IntArray {
        val b = ByteBuffer.wrap(read(sectorOffset(sector), sectorSize)).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(b.remaining() / 4) { b.getInt(it * 4) }
    }

    init {
        if (header.size < 512 || !header.copyOfRange(0, 8).contentEquals(SIGNATURE)) {
            throw FormatException("not an OLE2/CFB file (missing D0CF11E0 signature)")
        }
        val sectorShift = readShort(30)
        if (sectorShift != 9 && sectorShift != 12) throw FormatException("unsupported sector size 2^$sectorShift")
        sectorSize = 1 shl sectorShift
        sectorCount = ((fileSize - sectorSize) / sectorSize).toInt().coerceAtLeast(0)
        miniStreamCutoff = readInt(56)
        val firstDirSector = readInt(48)
        val firstMiniFatSector = readInt(60)
        val numMiniFatSectors = readInt(64)
        val firstDifatSector = readInt(68)
        val numDifatSectors = readInt(72)

        // The first 109 FAT sector locations live in the header itself; a file
        // needing more FAT sectors than that chains through DIFAT sectors, which
        // in practice only very large legacy files ever do.
        val fatSectorLocations = mutableListOf<Int>()
        for (i in 0 until 109) {
            val loc = readInt(76 + i * 4)
            if (loc in 0 until sectorCount) fatSectorLocations += loc
        }
        // The DIFAT chain and the FAT are sized by the file itself, never by the
        // counts the header claims, and every chain is followed at most once per
        // sector: a crafted header or a sector pointing at itself must not turn into
        // a huge allocation or an endless loop.
        var difatSector = firstDifatSector
        var remainingDifat = minOf(numDifatSectors, sectorCount)
        val seenDifat = java.util.BitSet()
        while (difatSector in 0 until sectorCount && !seenDifat[difatSector] && remainingDifat > 0) {
            seenDifat.set(difatSector)
            val entries = sectorInts(difatSector)
            val entriesPerSector = sectorSize / 4 - 1
            for (i in 0 until minOf(entriesPerSector, entries.size)) {
                val loc = entries[i]
                if (loc in 0 until sectorCount && fatSectorLocations.size < sectorCount) fatSectorLocations += loc
            }
            difatSector = entries.getOrElse(entriesPerSector) { ENDOFCHAIN }
            remainingDifat--
        }

        val fatEntries = fatSectorLocations.size * (sectorSize / 4)
        fat = IntArray(fatEntries)
        var fatIdx = 0
        for (loc in fatSectorLocations) {
            val entries = sectorInts(loc)
            for (i in 0 until sectorSize / 4) {
                if (fatIdx < fat.size) fat[fatIdx] = entries.getOrElse(i) { FREESECT }
                fatIdx++
            }
        }

        // Directory entries (128 bytes each): name, type, sibling and child ids,
        // first sector and size.
        val dirBytes = readChain(firstDirSector, -1)
        val dirBuf = ByteBuffer.wrap(dirBytes).order(ByteOrder.LITTLE_ENDIAN)
        val entryCount = dirBytes.size / 128
        var rootFirstSector = ENDOFCHAIN
        var rootSize = 0L
        class Dir(val name: String, val type: Int, val left: Int, val right: Int, val child: Int, val start: Int, val size: Long)
        val dirs = ArrayList<Dir?>(entryCount)
        for (i in 0 until entryCount) {
            val off = i * 128
            val nameLenBytes = dirBuf.getShort(off + 64).toInt() and 0xFFFF
            if (nameLenBytes <= 0 || nameLenBytes > 64) { dirs += null; continue }
            // nameLenBytes counts the trailing null terminator; the actual name is
            // one UTF-16 code unit (2 bytes) shorter.
            val name = String(dirBytes, off, (nameLenBytes - 2).coerceAtLeast(0), Charsets.UTF_16LE)
            dirs += Dir(name, dirBytes[off + 66].toInt(), dirBuf.getInt(off + 68), dirBuf.getInt(off + 72),
                dirBuf.getInt(off + 76), dirBuf.getInt(off + 116), dirBuf.getLong(off + 120))
        }
        dirs.firstOrNull { it?.type == 5 }?.let { rootFirstSector = it.start; rootSize = it.size } // root: owns the mini stream

        // Storages form a tree: each storage's children are a binary tree of
        // siblings (left/right) hanging off its child id. Streams are recorded by
        // their full path ("BodyText/Section0") and, for top-level ones, by name.
        val seen = java.util.BitSet()
        fun walk(id: Int, prefix: String) {
            if (id !in dirs.indices || seen[id]) return
            seen.set(id)
            val d = dirs[id] ?: return
            walk(d.left, prefix)
            walk(d.right, prefix)
            when (d.type) {
                2 -> paths["$prefix${d.name}"] = Entry(d.start, d.size)
                1 -> walk(d.child, "$prefix${d.name}/")
            }
        }
        dirs.indexOfFirst { it?.type == 5 }.takeIf { it >= 0 }?.let { root -> seen.set(root); walk(dirs[root]!!.child, "") }
        // Fall back to every stream by bare name when the tree is damaged.
        for (d in dirs) if (d != null && d.type == 2) streams.putIfAbsent(d.name, Entry(d.start, d.size))

        miniFat = if (numMiniFatSectors > 0 && rootFirstSector != ENDOFCHAIN) {
            val miniFatBytes = readChain(firstMiniFatSector, -1)
            val mb = ByteBuffer.wrap(miniFatBytes).order(ByteOrder.LITTLE_ENDIAN)
            IntArray(miniFatBytes.size / 4) { mb.getInt(it * 4) }
        } else {
            IntArray(0)
        }
        miniStreamFirstSector = rootFirstSector
        miniStreamSize = rootSize
    }

    /** Paths of all streams, storages separated by '/'. */
    val streamPaths: Set<String> get() = paths.keys

    /** Whether a stream exists, without reading it. */
    fun hasStream(name: String): Boolean = paths[name] != null || streams[name] != null

    /** Reads a stream by path ("BodyText/Section0") or top-level name, or null if
     *  it doesn't exist. */
    fun readStream(name: String): ByteArray? {
        val entry = paths[name] ?: streams[name] ?: return null
        return if (entry.size >= miniStreamCutoff) {
            readChain(entry.firstSector, entry.size)
        } else {
            readMiniChain(entry.firstSector, entry.size)
        }
    }

    /** Sector n follows the header, which takes one sector's worth of space. */
    private fun sectorOffset(sector: Int): Long = (sector + 1).toLong() * sectorSize

    /** Follows a regular FAT chain from `firstSector`, concatenating full sectors.
     *  `knownSize` trims the result to the stream's declared size (a stream's last
     *  sector is padded); pass -1 when the size isn't known yet (reading the FAT
     *  or directory chains themselves, before they can be sized). Runs of
     *  consecutive sectors are read in one go. */
    private fun readChain(firstSector: Int, knownSize: Long): ByteArray {
        val chain = ArrayList<Int>()
        var sector = firstSector
        val seen = java.util.BitSet() // a cyclic chain must not hang or grow forever
        val wanted = if (knownSize >= 0) (knownSize + sectorSize - 1) / sectorSize else Long.MAX_VALUE
        while (sector in 0 until sectorCount && !seen[sector] && chain.size < wanted) {
            seen.set(sector)
            chain += sector
            sector = if (sector < fat.size) fat[sector] else ENDOFCHAIN
        }
        val all = ByteArray(chain.size * sectorSize)
        var i = 0
        while (i < chain.size) {
            var j = i + 1
            while (j < chain.size && chain[j] == chain[j - 1] + 1) j++
            val piece = read(sectorOffset(chain[i]), (j - i) * sectorSize)
            piece.copyInto(all, i * sectorSize)
            i = j
        }
        return if (knownSize in 0..all.size.toLong()) all.copyOf(knownSize.toInt()) else all
    }

    /** The root storage's stream holding every small stream, read once. */
    private val miniStream: ByteArray by lazy { readChain(miniStreamFirstSector, miniStreamSize) }

    /** Follows a MiniFAT chain — used for streams under the mini-stream cutoff,
     *  which live packed into the root storage's own stream in 64-byte units
     *  instead of getting real sectors of their own. */
    private fun readMiniChain(firstSector: Int, size: Long): ByteArray {
        val miniStreamBytes = miniStream
        val out = ByteArrayOutputStream()
        var sector = firstSector
        val seen = java.util.BitSet()
        while (sector >= 0 && sector < miniStreamBytes.size / MINI_SECTOR_SIZE && !seen[sector]) {
            seen.set(sector)
            val off = sector * MINI_SECTOR_SIZE
            if (off + MINI_SECTOR_SIZE > miniStreamBytes.size) break
            out.write(miniStreamBytes, off, MINI_SECTOR_SIZE)
            sector = if (sector < miniFat.size) miniFat[sector] else ENDOFCHAIN
        }
        val all = out.toByteArray()
        return if (size in 0..all.size.toLong()) all.copyOf(size.toInt()) else all
    }
}
