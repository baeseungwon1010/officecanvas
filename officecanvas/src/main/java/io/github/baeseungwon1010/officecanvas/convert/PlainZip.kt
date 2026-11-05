package io.github.baeseungwon1010.officecanvas.convert

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * A zip writer that puts each entry's sizes and CRC in its local header, with no
 * data descriptors and no flags, as Hangul writes its packages: some readers of
 * .hwpx (LibreOffice's H2Orestart among them) refuse the data descriptors that
 * java.util.zip.ZipOutputStream writes after every compressed entry.
 */
internal class PlainZip(private val out: OutputStream) {

    private class Central(val name: ByteArray, val method: Int, val crc: Long, val csize: Long, val size: Long, val offset: Long)

    private val entries = ArrayList<Central>()
    private var offset = 0L

    fun add(name: String, data: ByteArray, compress: Boolean = true) {
        val crc = CRC32().apply { update(data) }.value
        val body = if (compress) deflate(data) else data
        // A compressed entry no smaller than the data is stored as it is.
        val stored = !compress || body.size >= data.size
        val payload = if (stored) data else body
        val method = if (stored) 0 else 8
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val header = ByteArrayOutputStream(30 + nameBytes.size)
        header.int(0x04034b50); header.short(if (stored) 10 else 20); header.short(0); header.short(method)
        header.short(0); header.short(0x21) // time 00:00:00, date 1980-01-01, as Hangul writes
        header.int(crc.toInt()); header.int(payload.size); header.int(data.size)
        header.short(nameBytes.size); header.short(0)
        header.write(nameBytes)
        out.write(header.toByteArray())
        out.write(payload)
        entries += Central(nameBytes, method, crc, payload.size.toLong(), data.size.toLong(), offset)
        offset += header.size() + payload.size
    }

    fun finish() {
        val dir = ByteArrayOutputStream()
        for (e in entries) {
            dir.int(0x02014b50); dir.short(20); dir.short(if (e.method == 0) 10 else 20); dir.short(0); dir.short(e.method)
            dir.short(0); dir.short(0x21)
            dir.int(e.crc.toInt()); dir.int(e.csize.toInt()); dir.int(e.size.toInt())
            dir.short(e.name.size); dir.short(0); dir.short(0); dir.short(0); dir.short(0); dir.int(0); dir.int(e.offset.toInt())
            dir.write(e.name)
        }
        val bytes = dir.toByteArray()
        out.write(bytes)
        val end = ByteArrayOutputStream()
        end.int(0x06054b50); end.short(0); end.short(0); end.short(entries.size); end.short(entries.size)
        end.int(bytes.size); end.int(offset.toInt()); end.short(0)
        out.write(end.toByteArray())
        out.flush()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            d.setInput(data); d.finish()
            val buf = ByteArrayOutputStream(data.size / 3 + 64)
            val chunk = ByteArray(64 * 1024)
            while (!d.finished()) buf.write(chunk, 0, d.deflate(chunk))
            return buf.toByteArray()
        } finally {
            d.end()
        }
    }

    private fun ByteArrayOutputStream.short(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }
    private fun ByteArrayOutputStream.int(v: Int) { short(v and 0xFFFF); short((v ushr 16) and 0xFFFF) }
}
