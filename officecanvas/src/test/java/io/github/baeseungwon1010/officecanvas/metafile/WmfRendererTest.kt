package io.github.baeseungwon1010.officecanvas.metafile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import io.github.baeseungwon1010.officecanvas.ppt.fixSymbolText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WmfRendererTest {

    private fun le(vararg shorts: Int, ints: List<Int> = emptyList()): ByteArray {
        val b = ByteBuffer.allocate(shorts.size * 2 + ints.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        shorts.forEach { b.putShort(it.toShort()) }
        ints.forEach { b.putInt(it) }
        return b.array()
    }

    private fun record(fn: Int, params: ByteArray): ByteArray {
        val words = (6 + params.size) / 2
        return ByteBuffer.allocate(6 + params.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(words).putShort(fn.toShort()).put(params).array()
    }

    /** A 100x100 WMF with a red 80x80 square inset by 10 on every side. */
    private fun redSquareWmf(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(record(0x020B, le(0, 0))) // SetWindowOrg(y, x)
        body.write(record(0x020C, le(100, 100))) // SetWindowExt(y, x)
        // CreateBrushIndirect: style 0 (solid), COLORREF (r,g,b,0), hatch 0
        body.write(record(0x02FC, byteArrayOf(0, 0, 0xFF.toByte(), 0, 0, 0, 0, 0)))
        body.write(record(0x012D, le(0))) // SelectObject(0)
        body.write(record(0x041B, le(90, 90, 10, 10))) // Rectangle: bottom, right, top, left
        body.write(record(0x0000, ByteArray(0)))
        val records = body.toByteArray()
        val header = ByteBuffer.allocate(18).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1).putShort(9).putShort(0x300).putInt((18 + records.size) / 2).putShort(1).putInt(10).putShort(0)
            .array()
        return WmfRenderer.placeable(header + records, 0, 0, 100, 100)
    }

    @Test
    fun `a WMF rectangle is filled with its brush color at the scaled position`() {
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        WmfRenderer.draw(Canvas(bmp), redSquareWmf(), RectF(0f, 0f, 200f, 200f))
        assertEquals("centre should be filled red", Color.RED, bmp.getPixel(100, 100))
        assertEquals("the inset margin stays background", Color.WHITE, bmp.getPixel(5, 5))
    }

    @Test
    fun `a DIB gets a BMP header BitmapFactory can decode`() {
        // 2x2, 24-bit, bottom-up: rows padded to 4 bytes (6 bytes of pixels + 2 padding).
        val dib = ByteBuffer.allocate(40 + 16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(40).putInt(2).putInt(2).putShort(1).putShort(24).putInt(0).putInt(16).putInt(0).putInt(0).putInt(0).putInt(0)
            .put(ByteArray(16) { 0x7F }).array()
        val bmp = WmfRenderer.decodeDib(dib)
        assertNotNull(bmp)
        assertEquals(2, bmp!!.width)
    }

    @Test
    fun `symbol font text and private-use codes become real characters`() {
        assertEquals("[", fixSymbolText("", "Helvetica"))
        assertEquals("αβ", fixSymbolText("ab", "Symbol"))
        assertEquals("→", fixSymbolText("", "Helvetica", "Wingdings 3"))
        assertEquals("plain text", fixSymbolText("plain text", "Arial"))
    }
}
