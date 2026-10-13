package io.github.baeseungwon1010.officecanvas.metafile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** shapes.emf is LibreOffice's EMF export of a red square (left) and a blue
 *  ellipse (right) — paths, polygons, pens and brushes, as Office diagrams use. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EmfRendererTest {

    @Test
    fun `draws the red square on the left and the blue ellipse on the right`() {
        val emf = javaClass.classLoader!!.getResourceAsStream("emf_samples/shapes.emf")!!.readBytes()
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        EmfRenderer.draw(canvas, emf, RectF(0f, 0f, 400f, 200f))

        fun isRed(c: Int) = Color.red(c) > 200 && Color.green(c) < 60 && Color.blue(c) < 60
        fun isBlue(c: Int) = Color.blue(c) > 200 && Color.red(c) < 60 && Color.green(c) < 60
        val left = (0 until 200 step 4).flatMap { x -> (0 until 200 step 4).map { y -> bitmap.getPixel(x, y) } }
        val right = (200 until 400 step 4).flatMap { x -> (0 until 200 step 4).map { y -> bitmap.getPixel(x, y) } }
        assertTrue("left half should be mostly red square", left.count(::isRed) > left.size / 4)
        assertTrue("left half should have no blue", left.none(::isBlue))
        assertTrue("right half should hold the blue ellipse", right.count(::isBlue) > right.size / 5)
        assertTrue("right half should have no red", right.none(::isRed))
    }

    @Test
    fun `garbage input draws nothing and does not throw`() {
        val bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
        EmfRenderer.draw(Canvas(bitmap), ByteArray(200) { it.toByte() }, RectF(0f, 0f, 50f, 50f))
        EmfRenderer.draw(Canvas(bitmap), ByteArray(3), RectF(0f, 0f, 50f, 50f))
    }
}
