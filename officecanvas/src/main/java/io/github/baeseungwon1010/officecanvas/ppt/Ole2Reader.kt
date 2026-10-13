package io.github.baeseungwon1010.officecanvas.ppt

import io.github.baeseungwon1010.officecanvas.util.Limits
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal reader for the OLE2/CFB (Compound File Binary) container format —
 * the outer shell of the legacy binary .ppt (and .doc, .xls) formats, replaced
 * by zip-of-XML in the 2007+ Office formats. Read-only, and only what's needed
 * to pull named top-level streams out by name (a .ppt's "PowerPoint Document"
 * and "Current User"): no storage traversal beyond the root, no write support.
 *
 * Spec: [MS-CFB] (Microsoft's own published Compound File Binary spec).
 *
 * All field reads go through [readInt]/[readLong]/[readShort], which take an
 * absolute byte offset into the whole file. `ByteBuffer.wrap(bytes, off, len)`
 * looks like it would let absolute `getInt(i)` calls be relative to `off`, but
 * the backing array is still the whole buffer — `getInt(i)` reads from `bytes[i]`
 * regardless of the `off`/`len` given to `wrap`, which `off` only sets as the
 * buffer's initial position/limit, not as a base for indexed access. Every
 * "sector at this offset" reader here was silently reading from the *start* of
 * the file until this was caught by every field of the first directory entry
 * checked coming back wrong (a stream size that couldn't shrink).
 */
internal class Ole2Reader private constructor(private val bytes: ByteArray) {

    class FormatException(message: String) : Exception(message)

    private val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private fun readShort(offset: Int): Int = buf.getShort(offset).toInt() and 0xFFFF
    private fun readInt(offset: Int): Int = buf.getInt(offset)
    private fun readLong(offset: Int): Long = buf.getLong(offset)

    private val sectorSize: Int
    /** Whole sectors present in the file after the header. */
    private val sectorCount: Int
    private val miniStreamCutoff: Int
    private val fat: IntArray
    private val miniFat: IntArray
    private val miniStreamFirstSector: Int
    private val miniStreamSize: Long
    private val streams = mutableMapOf<String, Entry>()

    private data class Entry(val firstSector: Int, val size: Long)

    companion object {
        private const val MINI_SECTOR_SIZE = 64
        private val SIGNATURE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
        private const val FREESECT = -1
        private const val ENDOFCHAIN = -2

        fun open(file: File): Ole2Reader {
            if (file.length() > Limits.MAX_PPT_BYTES) throw FormatException("file too large: ${file.length()} bytes")
            return Ole2Reader(file.readBytes())
        }
    }

    init {
        if (bytes.size < 512 || !bytes.copyOfRange(0, 8).contentEquals(SIGNATURE)) {
            throw FormatException("not an OLE2/CFB file (missing D0CF11E0 signature)")
        }
        val sectorShift = readShort(30)
        if (sectorShift != 9 && sectorShift != 12) throw FormatException("unsupported sector size 2^$sectorShift")
        sectorSize = 1 shl sectorShift
        sectorCount = ((bytes.size.toLong() - sectorSize) / sectorSize).toInt().coerceAtLeast(0)
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
            val secOff = sectorOffset(difatSector)
            val entriesPerSector = sectorSize / 4 - 1
            for (i in 0 until entriesPerSector) {
                val loc = readInt(secOff + i * 4)
                if (loc in 0 until sectorCount && fatSectorLocations.size < sectorCount) fatSectorLocations += loc
            }
            difatSector = readInt(secOff + entriesPerSector * 4)
            remainingDifat--
        }

        val fatEntries = fatSectorLocations.size * (sectorSize / 4)
        fat = IntArray(fatEntries)
        var fatIdx = 0
        for (loc in fatSectorLocations) {
            val secOff = sectorOffset(loc)
            for (i in 0 until sectorSize / 4) {
                if (fatIdx < fat.size) fat[fatIdx] = readInt(secOff + i * 4)
                fatIdx++
            }
        }

        // Directory entries (128 bytes each) form a tree via sibling/child ids;
        // only what's needed here — name, size, type, and the mini-stream location
        // the root entry carries — is read out of it, not the tree structure.
        val dirBytes = readChain(firstDirSector, -1)
        val dirBuf = ByteBuffer.wrap(dirBytes).order(ByteOrder.LITTLE_ENDIAN)
        val entryCount = dirBytes.size / 128
        var rootFirstSector = ENDOFCHAIN
        var rootSize = 0L
        for (i in 0 until entryCount) {
            val off = i * 128
            val nameLenBytes = dirBuf.getShort(off + 64).toInt() and 0xFFFF
            if (nameLenBytes <= 0) continue
            val objectType = dirBytes[off + 66].toInt()
            // nameLenBytes counts the trailing null terminator; the actual name is
            // one UTF-16 code unit (2 bytes) shorter.
            val name = String(dirBytes, off, (nameLenBytes - 2).coerceAtLeast(0), Charsets.UTF_16LE)
            val startSector = dirBuf.getInt(off + 116)
            val size = dirBuf.getLong(off + 120)
            when (objectType) {
                5 -> { rootFirstSector = startSector; rootSize = size } // root storage: owns the mini stream
                2 -> streams[name] = Entry(startSector, size) // stream
            }
        }

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

    /** Reads a named top-level stream in full, or null if it doesn't exist. */
    fun readStream(name: String): ByteArray? {
        val entry = streams[name] ?: return null
        return if (entry.size >= miniStreamCutoff) {
            readChain(entry.firstSector, entry.size)
        } else {
            readMiniChain(entry.firstSector, entry.size)
        }
    }

    /** Sector n follows the header, which takes one sector's worth of space. */
    private fun sectorOffset(sector: Int): Int = (sector + 1) * sectorSize

    /** Follows a regular FAT chain from `firstSector`, concatenating full sectors.
     *  `knownSize` trims the result to the stream's declared size (a stream's last
     *  sector is padded); pass -1 when the size isn't known yet (reading the FAT
     *  or directory chains themselves, before they can be sized). */
    private fun readChain(firstSector: Int, knownSize: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var sector = firstSector
        val seen = java.util.BitSet() // a cyclic chain must not hang or grow forever
        while (sector in 0 until sectorCount && !seen[sector]) {
            seen.set(sector)
            out.write(bytes, sectorOffset(sector), sectorSize)
            sector = if (sector < fat.size) fat[sector] else ENDOFCHAIN
        }
        val all = out.toByteArray()
        return if (knownSize in 0..all.size.toLong()) all.copyOf(knownSize.toInt()) else all
    }

    /** Follows a MiniFAT chain — used for streams under the mini-stream cutoff,
     *  which live packed into the root storage's own stream in 64-byte units
     *  instead of getting real sectors of their own. */
    private fun readMiniChain(firstSector: Int, size: Long): ByteArray {
        val miniStreamBytes = readChain(miniStreamFirstSector, miniStreamSize)
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
