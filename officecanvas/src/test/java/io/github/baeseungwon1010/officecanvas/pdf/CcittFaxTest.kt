package io.github.baeseungwon1010.officecanvas.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Fax data made by libtiff (tools/make_ccitt.py) decodes to the picture it was made from. */
class CcittFaxTest {

    private val w = 2600
    private val h = 60

    private fun black(x: Int, y: Int) = if (y % 20 == 0) x < 2000 else ((x * (y % 7 + 1)) / 37) % 4 == 0

    private fun check(name: String, k: Int) {
        val data = javaClass.classLoader!!.getResourceAsStream("pdf_samples/$name")!!.readBytes()
        val rows = CcittFax.decode(data, k, w, h, blackIs1 = false, byteAlign = false)
        assertNotNull(rows)
        val rowBytes = (w + 7) / 8
        assertEquals(rowBytes * h, rows!!.size)
        var wrong = 0
        for (y in 0 until h) for (x in 0 until w) {
            val bit = (rows[y * rowBytes + x / 8].toInt() shr (7 - x % 8)) and 1
            // 0 is black (BlackIs1 false).
            if ((bit == 0) != black(x, y)) wrong++
        }
        assertEquals("$name: pixels wrong", 0, wrong)
    }

    @Test
    fun `Group 4`() = check("ccitt_g4.bin", -1)

    @Test
    fun `Group 3 one-dimensional`() = check("ccitt_g3.bin", 0)

    @Test
    fun `Group 3 with two-dimensional lines`() = check("ccitt_g3_2d.bin", 4)

    @Test
    fun `BlackIs1 flips the bits, and data that isn't fax data gives nothing`() {
        val data = javaClass.classLoader!!.getResourceAsStream("pdf_samples/ccitt_g4.bin")!!.readBytes()
        val a = CcittFax.decode(data, -1, w, h, blackIs1 = false, byteAlign = false)!!
        val b = CcittFax.decode(data, -1, w, h, blackIs1 = true, byteAlign = false)!!
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals(a[i].toInt().inv() and 0xFF, b[i].toInt() and 0xFF)
        assertEquals(null, CcittFax.decode(ByteArray(64), -1, w, h, blackIs1 = false, byteAlign = false))
    }
}
