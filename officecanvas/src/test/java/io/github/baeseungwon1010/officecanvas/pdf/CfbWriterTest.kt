package io.github.baeseungwon1010.officecanvas.pdf

import io.github.baeseungwon1010.officecanvas.convert.CfbWriter
import io.github.baeseungwon1010.officecanvas.ole.Ole2Reader
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File
import kotlin.random.Random

/** The compound files the HWP writer makes, read back with this library's own reader. */
class CfbWriterTest {

    @Test
    fun `small, large and very large streams read back as written`() {
        val rnd = Random(7)
        // Over 7 MB in all: past the 109 FAT sectors the header holds, into DIFAT sectors.
        val streams = linkedMapOf(
            "FileHeader" to rnd.nextBytes(256),
            "DocInfo" to rnd.nextBytes(5000),
            "BodyText/Section0" to rnd.nextBytes(3000),
            "BinData/BIN0001.png" to rnd.nextBytes(4 shl 20),
            "BinData/BIN0002.jpg" to rnd.nextBytes(6 shl 20)
        )
        val cfb = CfbWriter()
        for ((path, data) in streams) cfb.add(path, data)
        val file = File.createTempFile("cfb", ".hwp").apply { deleteOnExit() }
        file.outputStream().use { cfb.write(it) }
        Ole2Reader.open(file).use { ole ->
            for ((path, data) in streams) assertArrayEquals(path, data, ole.readStream(path))
        }
    }
}
