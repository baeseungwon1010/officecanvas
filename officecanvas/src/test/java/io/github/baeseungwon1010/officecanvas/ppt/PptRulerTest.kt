package io.github.baeseungwon1010.officecanvas.ppt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PptRulerTest {

    /** A TextRulerAtom as a real lecture deck stores its process tables: default tab, two tab
     *  stops, and level-1 left margin / indent (MS-PPT 2.9.36 field order). */
    @Test
    fun `text ruler tab stops and level margins are read in spec order`() {
        val flags = (1 shl 0) or (1 shl 2) or (1 shl 3) or (1 shl 8) // defaultTab, tabs, leftMargin1, indent1
        val payload = ByteBuffer.allocate(4 + 2 + 2 + 8 + 4).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(flags)
            .putShort(576) // defaultTabSize
            .putShort(2).putShort(1398).putShort(0).putShort(2516).putShort(0) // tabs
            .putShort(216).putShort(0) // leftMargin1, indent1
            .array()
        val record = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0).putShort(RT.TEXT_RULER_ATOM.toShort()).putInt(payload.size).put(payload).array()
        val r = RecordReader(record)
        val ruler = readRuler(r, r.record(0)!!)
        assertNotNull(ruler)
        assertEquals(576, ruler!!.defaultTab)
        assertEquals(listOf(1398, 2516), ruler.tabs)
        assertEquals(216, ruler.pfFor(0)?.leftMargin)
        assertEquals(0, ruler.pfFor(0)?.indent)
    }
}
